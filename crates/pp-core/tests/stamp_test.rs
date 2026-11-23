// Stamps (plan M4 sign): a picture with a transparent border lands where it was placed, as the page shows on
// screen, on upright and turned pages; PDFium draws ink inside and paper around it; the page's own drawing state
// can't move it; bad input is an error.
use std::io::Cursor;
use std::path::PathBuf;

use lopdf::{dictionary, Document, Object, Stream};
use pp_core::stamp;
use pp_core::view::{Area, Engine};

/// PDFium binds once per process: every check shares one engine.
fn engine() -> &'static Engine {
    static E: std::sync::OnceLock<Engine> = std::sync::OnceLock::new();
    E.get_or_init(|| {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../vendor/pdfium/win-x64/bin");
        Engine::load(&dir).expect("run scripts/fetch_pdfium.sh first")
    })
}

/// 100 x 50: a black middle (60 x 30) inside a fully transparent border, like ink on nothing.
fn ink() -> Vec<u8> {
    let img = image::RgbaImage::from_fn(100, 50, |x, y| {
        if (20..80).contains(&x) && (10..40).contains(&y) { image::Rgba([0, 0, 0, 255]) } else { image::Rgba([255, 0, 0, 0]) }
    });
    let mut out = Cursor::new(Vec::new());
    img.write_to(&mut out, image::ImageFormat::Png).unwrap();
    out.into_inner()
}

/// One A4 page, optionally turned, whose content ends with a scaled, unbalanced graphics state (a hostile page).
fn page(rotate: i64, messy: bool) -> Document {
    let mut d = Document::with_version("1.7");
    let pages_id = d.new_object_id();
    let body: &[u8] = if messy { b"0.5 0 0 0.5 100 100 cm 1 0 0 rg" } else { b"" };
    let c = d.add_object(Stream::new(dictionary! {}, body.to_vec()));
    let p = d.add_object(dictionary! { "Type" => "Page", "Parent" => pages_id, "MediaBox" => vec![0.into(), 0.into(), 595.into(), 842.into()],
        "Rotate" => rotate, "Contents" => c, "Resources" => dictionary! {} });
    d.objects.insert(pages_id, Object::Dictionary(dictionary! { "Type" => "Pages", "Kids" => vec![p.into()], "Count" => 1 }));
    let cat = d.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    d.trailer.set("Root", cat);
    d
}

/// The page drawn 1 pixel per point (as shown, turned), and whether the pixel at (x, y) is dark.
fn dark_at(doc: &mut Document, x: u32, y: u32) -> bool {
    let bytes = pp_core::save(doc).unwrap();
    let e = engine();
    let v = e.open(bytes, None).unwrap();
    let (w, _) = v.page_size(1).unwrap();
    let img = v.render(1, w.round() as i32).unwrap();
    let p = img.get_pixel(x, y);
    p[0] < 80 && p[1] < 80 && p[2] < 80
}

#[test]
fn lands_where_placed_with_paper_around_the_ink() {
    for messy in [false, true] {
        let area = Area { x: 100.0, y: 200.0, w: 200.0, h: 100.0 };
        let mut d = page(0, messy);
        stamp::stamp(&mut d, 1, area, &ink()).unwrap();
        // the ink is the middle 60 % x 60 % of the picture: its centre is the area's centre
        assert!(dark_at(&mut d.clone(), 200, 250), "ink at the centre (messy page: {messy})");
        // the transparent border shows the page: 5 points inside the area's left edge is paper
        assert!(!dark_at(&mut d.clone(), 105, 250), "paper through the transparent border (messy page: {messy})");
        // and nothing outside the area
        assert!(!dark_at(&mut d, 400, 250));
    }
}

#[test]
fn a_turned_page_gets_it_where_the_screen_shows_it() {
    for rotate in [90, 180, 270] {
        // a wide area on the page as shown (a turned A4 shows 842 wide by 595 tall when 90 or 270)
        let area = Area { x: 300.0, y: 100.0, w: 200.0, h: 100.0 };
        let mut d = page(rotate, false);
        stamp::stamp(&mut d, 1, area, &ink()).unwrap();
        assert!(dark_at(&mut d.clone(), 400, 150), "centre dark on a page turned {rotate}");
        // the picture is upright as shown: its wide black middle reaches 50 points right of centre, not 50 down
        assert!(dark_at(&mut d.clone(), 450, 150), "upright on a page turned {rotate}");
        assert!(!dark_at(&mut d, 400, 190), "and not tall on a page turned {rotate}");
    }
}

#[test]
fn bad_input_is_an_error() {
    let mut d = page(0, false);
    assert!(stamp::stamp(&mut d, 2, Area { x: 0.0, y: 0.0, w: 10.0, h: 10.0 }, &ink()).is_err());
    assert!(stamp::stamp(&mut d, 1, Area { x: 0.0, y: 0.0, w: 0.0, h: 10.0 }, &ink()).is_err());
    assert!(stamp::stamp(&mut d, 1, Area { x: f32::NAN, y: 0.0, w: 10.0, h: 10.0 }, &ink()).is_err());
    assert!(stamp::stamp(&mut d, 1, Area { x: 0.0, y: 0.0, w: 10.0, h: 10.0 }, b"not a picture").is_err());
}

#[test]
fn a_drawn_signature_becomes_ink_on_nothing() {
    // an "L": down then right, drawn in screen units
    let strokes = vec![vec![(10.0, 10.0), (10.0, 110.0), (60.0, 110.0)]];
    let png = stamp::ink_picture(&strokes, 4.0).unwrap();
    let img = image::load_from_memory(&png).unwrap().to_rgba8();
    // cropped to the drawing: about 50 wide by 100 tall plus the pen, so taller than wide
    assert!(img.height() > img.width(), "{}x{}", img.width(), img.height());
    let inked = img.pixels().filter(|p| p[3] > 200).count();
    let clear = img.pixels().filter(|p| p[3] == 0).count();
    assert!(inked > 1000 && clear > inked, "ink {inked}, clear {clear}");
    // the corner of the L is ink, the opposite corner is nothing
    let (w, h) = (img.width(), img.height());
    assert!(img.get_pixel(w / 10, h - h / 20)[3] > 200);
    assert!(img.get_pixel(w - w / 10, h / 20)[3] == 0);
    // and it stamps like any picture
    let mut d = page(0, false);
    stamp::stamp(&mut d, 1, Area { x: 100.0, y: 100.0, w: 50.0, h: 100.0 }, &png).unwrap();
    assert!(stamp::ink_picture(&[], 4.0).is_err());
    assert!(stamp::ink_picture(&[vec![(0.0, 0.0)]], 0.0).is_err());
}
