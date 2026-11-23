// Marks (plan M4): typed text, highlighter and pen land where they were placed as the page shows on screen, on
// upright and turned pages, as real annotations (readable back with lopdf), drawn by PDFium; the highlighter
// lets the page show through; bad input is an error.
use std::path::PathBuf;

use lopdf::{dictionary, Document, Object, Stream};
use pp_core::annotate::{self, Mark};
use pp_core::view::{Area, Engine};

/// PDFium binds once per process: every check shares one engine.
fn engine() -> &'static Engine {
    static E: std::sync::OnceLock<Engine> = std::sync::OnceLock::new();
    E.get_or_init(|| {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../vendor/pdfium/win-x64/bin");
        Engine::load(&dir).expect("run scripts/fetch_pdfium.sh first")
    })
}

/// One A4 page, optionally turned, with a black bar drawn across it at shown y 400..420 when upright.
fn page(rotate: i64) -> Document {
    let mut d = Document::with_version("1.7");
    let pages_id = d.new_object_id();
    let c = d.add_object(Stream::new(dictionary! {}, b"0 g 0 422 595 20 re f".to_vec()));
    let p = d.add_object(dictionary! { "Type" => "Page", "Parent" => pages_id, "MediaBox" => vec![0.into(), 0.into(), 595.into(), 842.into()],
        "Rotate" => rotate, "Contents" => c, "Resources" => dictionary! {} });
    d.objects.insert(pages_id, Object::Dictionary(dictionary! { "Type" => "Pages", "Kids" => vec![p.into()], "Count" => 1 }));
    let cat = d.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    d.trailer.set("Root", cat);
    d
}

/// The page drawn 1 pixel per point (as shown, turned).
fn shown(doc: &mut Document) -> image::RgbaImage {
    let bytes = pp_core::save(doc).unwrap();
    let v = engine().open(bytes, None).unwrap();
    let (w, _) = v.page_size(1).unwrap();
    v.render(1, w.round() as i32).unwrap()
}

fn dark(p: &image::Rgba<u8>) -> bool {
    p[0] < 100 && p[1] < 100 && p[2] < 160
}

/// How many dark pixels inside a shown box.
fn dark_in(img: &image::RgbaImage, x: u32, y: u32, w: u32, h: u32) -> usize {
    (y..y + h).flat_map(|j| (x..x + w).map(move |i| (i, j))).filter(|&(i, j)| dark(img.get_pixel(i, j))).count()
}

fn subtypes(d: &Document) -> Vec<String> {
    let page = *d.get_pages().get(&1).unwrap();
    let annots = d.get_dictionary(page).unwrap().get(b"Annots").unwrap().as_array().unwrap().clone();
    annots.iter().map(|a| {
        let a = d.get_dictionary(a.as_reference().unwrap()).unwrap();
        String::from_utf8_lossy(a.get(b"Subtype").unwrap().as_name().unwrap()).into_owned()
    }).collect()
}

#[test]
fn typed_text_lands_where_placed_and_reads_upright() {
    for rotate in [0, 90, 180, 270] {
        let mut d = page(rotate);
        let text = "MMMMMMMM".to_string();
        let (_, w, h) = annotate::text_box(&text, 24.0);
        annotate::annotate(&mut d, 1, &[Mark::Text { at: (100.0, 100.0), text, size: 24.0 }]).unwrap();
        assert_eq!(subtypes(&d), ["FreeText"]);
        let img = shown(&mut d);
        let inside = dark_in(&img, 100, 100, w as u32, h as u32);
        assert!(inside > 300, "text drawn in its box on a page turned {rotate}: {inside} dark pixels");
        // upright: a long line of letters runs right, so the box's right half has ink and the strip below has none
        assert!(dark_in(&img, 100 + w as u32 / 2, 100, w as u32 / 2, h as u32) > 100, "runs right on a page turned {rotate}");
        assert_eq!(dark_in(&img, 100, 100 + h as u32 + 2, w as u32, 40), 0, "nothing below the box on a page turned {rotate}");
    }
}

#[test]
fn spanish_text_keeps_its_letters() {
    let mut d = page(0);
    annotate::annotate(&mut d, 1, &[Mark::Text { at: (50.0, 50.0), text: "Año: ¿sí?\nOtra línea".into(), size: 12.0 }]).unwrap();
    let bytes = pp_core::save(&mut d).unwrap();
    let back = Document::load_mem(&bytes).unwrap();
    let page = *back.get_pages().get(&1).unwrap();
    let a = back.get_dictionary(page).unwrap().get(b"Annots").unwrap().as_array().unwrap()[0].as_reference().unwrap();
    let c = back.get_dictionary(a).unwrap().get(b"Contents").unwrap().as_str().unwrap().to_vec();
    // a UTF-16 text string: the mark, then the letters
    assert_eq!(&c[..2], &[0xFE, 0xFF]);
    let units: Vec<u16> = c[2..].chunks(2).map(|p| u16::from_be_bytes([p[0], p[1]])).collect();
    assert_eq!(String::from_utf16(&units).unwrap(), "Año: ¿sí?\nOtra línea");
    // two lines: the box is taller than one line of 12 points
    let (_, _, h) = annotate::text_box("Año: ¿sí?\nOtra línea", 12.0);
    assert!(h > 2.0 * 12.0);
}

#[test]
fn the_highlighter_shows_the_page_through() {
    for rotate in [0, 180] {
        let mut d = page(rotate);
        let before = shown(&mut d.clone());
        // find the black bar as shown, then highlight across it and above it
        let (bw, bh) = before.dimensions();
        let bar_y = (0..bh).find(|&y| dark(before.get_pixel(bw / 2, y))).expect("the bar");
        let bar_x = (0..bw).find(|&x| dark(before.get_pixel(x, bar_y + 2))).unwrap_or(0);
        let area = Area { x: (bar_x + 10) as f32, y: bar_y as f32 - 30.0, w: 60.0, h: 50.0 };
        annotate::annotate(&mut d, 1, &[Mark::Highlight { areas: vec![area] }]).unwrap();
        assert_eq!(subtypes(&d), ["Highlight"]);
        let img = shown(&mut d);
        let paper = img.get_pixel(bar_x + 40, bar_y - 15);
        assert!(paper[0] > 200 && paper[1] > 180 && paper[2] < 140, "paper turns yellow on a page turned {rotate}: {paper:?}");
        assert!(dark(img.get_pixel(bar_x + 40, bar_y + 2)), "the bar still shows through on a page turned {rotate}");
        let outside = img.get_pixel(bar_x + 40, bar_y - 60);
        assert!(outside[2] > 200, "nothing yellow outside on a page turned {rotate}");
    }
}

#[test]
fn the_pen_draws_its_lines_and_adds_to_existing_marks() {
    for rotate in [0, 270] {
        let mut d = page(rotate);
        annotate::annotate(&mut d, 1, &[Mark::Text { at: (300.0, 500.0), text: "x".into(), size: 10.0 }]).unwrap();
        // an "L" as shown: down, then right
        let lines = vec![vec![(100.0, 100.0), (100.0, 300.0), (250.0, 300.0)], vec![(400.0, 150.0)]];
        annotate::annotate(&mut d, 1, &[Mark::Ink { lines, pen: 4.0 }]).unwrap();
        assert_eq!(subtypes(&d), ["FreeText", "Ink"]);
        let img = shown(&mut d);
        assert!(dark_in(&img, 97, 180, 6, 40) > 50, "down stroke on a page turned {rotate}");
        assert!(dark_in(&img, 160, 297, 40, 6) > 50, "right stroke on a page turned {rotate}");
        assert!(dark_in(&img, 397, 147, 6, 6) > 5, "a single touch leaves a dot on a page turned {rotate}");
        assert_eq!(dark_in(&img, 160, 150, 60, 60), 0, "inside the L stays paper on a page turned {rotate}");
    }
}

#[test]
fn bad_input_is_an_error() {
    let mut d = page(0);
    let text = |t: &str, size: f32| Mark::Text { at: (10.0, 10.0), text: t.into(), size };
    assert!(annotate::annotate(&mut d, 2, &[text("a", 12.0)]).is_err());
    assert!(annotate::annotate(&mut d, 1, &[]).is_err());
    assert!(annotate::annotate(&mut d, 1, &[text("  ", 12.0)]).is_err());
    assert!(annotate::annotate(&mut d, 1, &[text("a", 0.0)]).is_err());
    assert!(annotate::annotate(&mut d, 1, &[text("a", f32::NAN)]).is_err());
    assert!(annotate::annotate(&mut d, 1, &[Mark::Highlight { areas: vec![Area { x: 0.0, y: 0.0, w: 0.0, h: 5.0 }] }]).is_err());
    assert!(annotate::annotate(&mut d, 1, &[Mark::Ink { lines: vec![vec![(f32::NAN, 1.0)]], pen: 2.0 }]).is_err());
    assert!(annotate::annotate(&mut d, 1, &[Mark::Ink { lines: vec![vec![(1.0, 1.0)]], pen: 0.0 }]).is_err());
    let huge = vec![vec![(1.0, 1.0); annotate::MAX_PEN_POINTS + 1]];
    assert!(annotate::annotate(&mut d, 1, &[Mark::Ink { lines: huge, pen: 2.0 }]).is_err());
    // nothing was added by the refused calls
    let page = *d.get_pages().get(&1).unwrap();
    assert!(d.get_dictionary(page).unwrap().get(b"Annots").is_err());
}
