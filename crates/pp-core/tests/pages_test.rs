// Page operations keep the right pages in the right order, survive a save/open round trip, and refuse bad input.
use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Document, Object, Stream};
use pp_core::pages::{self, Pick};
use pp_core::Error;

/// A PDF whose page i says "<tag> i"; the page size is inherited from the Pages node (tests the inheritance path).
fn doc(tag: &str, n: u32) -> Document {
    let mut d = Document::with_version("1.5");
    let pages_id = d.new_object_id();
    let font = d.add_object(dictionary! { "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Courier" });
    let res = d.add_object(dictionary! { "Font" => dictionary! { "F1" => font } });
    let mut kids = vec![];
    for i in 1..=n {
        let content = Content {
            operations: vec![
                Operation::new("BT", vec![]),
                Operation::new("Tf", vec!["F1".into(), 24.into()]),
                Operation::new("Td", vec![72.into(), 700.into()]),
                Operation::new("Tj", vec![Object::string_literal(format!("{tag} {i}"))]),
                Operation::new("ET", vec![]),
            ],
        };
        let c = d.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
        let p = d.add_object(dictionary! { "Type" => "Page", "Parent" => pages_id, "Contents" => c });
        kids.push(p.into());
    }
    d.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => kids, "Count" => n as i64, "Resources" => res,
            "MediaBox" => vec![0.into(), 0.into(), 595.into(), 842.into()], "Rotate" => 90,
        }),
    );
    let cat = d.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    d.trailer.set("Root", cat);
    d
}

/// Text of each page after a save and a fresh open, so the test reads what another reader would.
fn texts(mut d: Document) -> Vec<String> {
    let bytes = pp_core::save(&mut d).unwrap();
    let d = pp_core::open(&bytes, None).unwrap();
    let n = d.get_pages().len() as u32;
    (1..=n).map(|p| d.extract_text(&[p]).unwrap().trim().to_string()).collect()
}

fn rotations(d: &Document) -> Vec<i64> {
    d.get_pages()
        .values()
        .map(|id| d.get_dictionary(*id).unwrap().get(b"Rotate").unwrap().as_i64().unwrap())
        .collect()
}

#[test]
fn merge_keeps_every_page_in_order() {
    let out = pages::merge(&[doc("A", 2), doc("B", 3)]).unwrap();
    assert_eq!(texts(out), ["A 1", "A 2", "B 1", "B 2", "B 3"]); // pinned: page texts of the two made files
}

#[test]
fn extract_reorders_and_repeats() {
    let out = pages::extract(&doc("A", 4), &[3, 1, 3]).unwrap();
    assert_eq!(texts(out), ["A 3", "A 1", "A 3"]); // pinned: picked order with a repeat
}

#[test]
fn delete_and_split() {
    assert_eq!(texts(pages::delete(&doc("A", 5), &[2, 4]).unwrap()), ["A 1", "A 3", "A 5"]); // pinned: kept pages
    let parts = pages::split(&doc("A", 5), &[3, 5]).unwrap();
    let got: Vec<Vec<String>> = parts.into_iter().map(texts).collect();
    assert_eq!(got, vec![vec!["A 1", "A 2"], vec!["A 3", "A 4"], vec!["A 5"]]); // pinned: parts 1-2, 3-4, 5
}

#[test]
fn inherited_attributes_and_rotation_travel_with_the_page() {
    let out = pages::rotate(&doc("A", 3), &[2], 90).unwrap();
    // the source pages inherit Rotate 90 from their parent; page 2 turns once more, -90 wraps to 270
    assert_eq!(rotations(&out), [90, 180, 90]); // pinned: inherited 90, +90 on page 2
    let back = pages::assemble(&[doc("A", 1)], &[Pick::turned(0, 1, -180)]).unwrap();
    assert_eq!(rotations(&back), [270]); // pinned: 90 - 180 wraps to 270
    let page = back.get_dictionary(*back.get_pages().values().next().unwrap()).unwrap();
    assert!(page.get(b"MediaBox").is_ok() && page.get(b"Resources").is_ok());
}

#[test]
fn bad_picks_are_errors_not_panics() {
    let a = doc("A", 2);
    assert_eq!(pages::extract(&a, &[3]).unwrap_err(), Error::NoSuchPage(0, 3));
    assert_eq!(pages::extract(&a, &[]).unwrap_err(), Error::Empty);
    assert_eq!(pages::assemble(&[a], &[Pick::new(1, 1)]).unwrap_err(), Error::NoSuchPage(1, 0));
}

#[test]
fn garbage_is_damaged_not_a_crash() {
    assert!(matches!(pp_core::open(b"not a pdf at all", None), Err(Error::Damaged(_))));
    assert!(matches!(pp_core::open(b"%PDF-1.7\n1 0 obj << /Type /Catalog /Pages 1 0 R >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF", None), Err(_) | Ok(_)));
}
