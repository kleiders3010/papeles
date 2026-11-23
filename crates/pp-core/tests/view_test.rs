// Reading through PDFium: page sizes, pictures, text and search on PDFs we make ourselves; bad files are errors.
// Needs vendor/pdfium/win-x64/bin (scripts/fetch_pdfium.sh).
use std::path::PathBuf;

use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Document, Object, Stream};
use pp_core::view::Engine;
use pp_core::Error;

fn engine() -> Engine {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../vendor/pdfium/win-x64/bin");
    Engine::load(&dir).expect("run scripts/fetch_pdfium.sh first")
}

/// Pages with the given lines of text (Helvetica 20 pt, from the top), 595 x 842 except where `wide` turns it.
fn pdf(pages: &[&[&str]], wide: bool) -> Vec<u8> {
    let mut d = Document::with_version("1.7");
    let pages_id = d.new_object_id();
    let font = d.add_object(dictionary! { "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica" });
    let mut kids = vec![];
    for lines in pages {
        let mut ops = vec![Operation::new("BT", vec![]), Operation::new("Tf", vec!["F1".into(), 20.into()]), Operation::new("TL", vec![28.into()]),
                           Operation::new("Td", vec![72.into(), 760.into()])];
        for l in lines.iter() {
            ops.push(Operation::new("Tj", vec![Object::string_literal(*l)]));
            ops.push(Operation::new("T*", vec![]));
        }
        ops.push(Operation::new("ET", vec![]));
        let c = d.add_object(Stream::new(dictionary! {}, Content { operations: ops }.encode().unwrap()));
        let mb: Vec<Object> = if wide { vec![0.into(), 0.into(), 842.into(), 595.into()] } else { vec![0.into(), 0.into(), 595.into(), 842.into()] };
        let p = d.add_object(dictionary! { "Type" => "Page", "Parent" => pages_id, "Contents" => c, "MediaBox" => mb,
            "Resources" => dictionary! { "Font" => dictionary! { "F1" => font } } });
        kids.push(p.into());
    }
    let n = kids.len() as i64;
    d.objects.insert(pages_id, Object::Dictionary(dictionary! { "Type" => "Pages", "Kids" => kids, "Count" => n }));
    let cat = d.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    d.trailer.set("Root", cat);
    pp_core::save(&mut d).unwrap()
}

#[test]
fn sizes_text_and_pictures() {
    let e = engine();
    let v = e.open(pdf(&[&["Hello world"], &["Second page"]], false), None).unwrap();
    assert_eq!(v.page_count(), 2); // pinned: two pages made
    let (w, h) = v.page_size(1).unwrap();
    assert!((w - 595.0).abs() < 0.5 && (h - 842.0).abs() < 0.5, "{w} x {h}");
    assert!(v.text(2).unwrap().contains("Second page"));
    let img = v.render(1, 600).unwrap();
    assert_eq!(img.width(), 600); // pinned: asked width
    assert!((img.height() as f32 - 600.0 * 842.0 / 595.0).abs() <= 1.0);
    // white page with black text: mostly white, some dark pixels
    let dark = img.pixels().filter(|p| p[0] < 100).count();
    assert!(dark > 50 && dark < (img.width() * img.height() / 10) as usize, "dark pixels {dark}");
    // the size cap holds whatever is asked
    let big = v.render(1, 100_000).unwrap();
    assert!(big.width() <= 4096 && big.height() <= 4096);
}

#[test]
fn search_finds_every_hit_with_areas_on_the_page() {
    let e = engine();
    let v = e.open(pdf(&[&["The cat sat", "on the CAT mat"], &["no animals here"], &["a cat again"]], false), None).unwrap();
    let hits = v.search("cat", 100).unwrap();
    assert_eq!(hits.iter().map(|h| h.page).collect::<Vec<_>>(), [1, 1, 3]); // pinned: two on page 1 (any case), one on page 3
    let a = hits[0].areas[0];
    // first line sits near the top: y measured from the top edge, about 82 pt down (842 - 760)
    assert!(a.x > 72.0 && a.y > 60.0 && a.y < 100.0 && a.w > 5.0 && a.h > 5.0, "{a:?}");
    assert_eq!(v.search("cat", 2).unwrap().len(), 2); // pinned: the limit holds
    assert!(v.search("   ", 10).unwrap().is_empty());
}

#[test]
fn bad_input_is_an_error() {
    let e = engine();
    assert!(matches!(e.open(b"nothing like a pdf".to_vec(), None), Err(Error::Damaged(_))));
    let v = e.open(pdf(&[&["x"]], true), None).unwrap();
    assert_eq!(v.page_size(1).unwrap().0.round(), 842.0); // pinned: wide page made 842 wide
    assert!(matches!(v.render(2, 100), Err(Error::NoSuchPage(_, 2))));
}

#[test]
fn a_turned_and_moved_page_keeps_its_text() {
    // 2026-11-23: the app's page editor (delete 2, move 3 up, turn it) saved a first page with no text
    let e = engine();
    let src = pp_core::open(&pdf(&[&["page one"], &["page two"], &["page three"]], false), None).unwrap();
    let mut out = pp_core::pages::assemble(&[src], &[pp_core::pages::Pick::turned(0, 3, 90), pp_core::pages::Pick::new(0, 1)]).unwrap();
    let v = e.open(pp_core::save(&mut out).unwrap(), None).unwrap();
    assert_eq!(v.page_count(), 2); // pinned: two picked
    let (t1, t2) = (v.text(1).unwrap(), v.text(2).unwrap());
    assert!(t1.contains("page three"), "page 1: {t1:?} / page 2: {t2:?}");
    assert!(t2.contains("page one"), "page 2: {t2:?}");
    let (w, h) = v.page_size(1).unwrap();
    assert!(w > h, "turned page is wide: {w} x {h}");
}
