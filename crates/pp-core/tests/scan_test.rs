// Scanner (plan M5): a made-up photo of a tilted sheet on a dark table, with a shadow across it and dark bars of
// "writing", comes out with its corners found, straightened and cleaned: white paper, black bars where they were.
use image::{Rgb, RgbImage};
use pp_core::scan::{self, Look, Pt};

/// The sheet's corners in the photo (top left, top right, bottom right, bottom left): a perspective, not a rectangle.
const SHEET: [(f32, f32); 4] = [(220.0, 140.0), (980.0, 190.0), (1050.0, 1180.0), (160.0, 1120.0)];

/// Whether (x, y) is inside the four-sided shape `q` (convex, clockwise as the photo shows).
fn inside(q: &[(f32, f32); 4], x: f32, y: f32) -> bool {
    (0..4).all(|i| {
        let (a, b) = (q[i], q[(i + 1) % 4]);
        (b.0 - a.0) * (y - a.1) - (b.1 - a.1) * (x - a.0) >= 0.0
    })
}

/// Where a point given as a share of the sheet (s across, t down) lands in the photo (bilinear inside the corners).
fn on_sheet(s: f32, t: f32) -> (f32, f32) {
    let [a, b, c, d] = SHEET;
    let top = (a.0 + (b.0 - a.0) * s, a.1 + (b.1 - a.1) * s);
    let bot = (d.0 + (c.0 - d.0) * s, d.1 + (c.1 - d.1) * s);
    (top.0 + (bot.0 - top.0) * t, top.1 + (bot.1 - top.1) * t)
}

/// 1200 x 1300: a dark wood table, a cream sheet, a shadow over its right part, three dark bars (4 % of the sheet tall) a fifth, half and
/// four fifths of the way down, from 20 % to 80 % across.
fn photo() -> RgbImage {
    let mut img = RgbImage::from_fn(1200, 1300, |x, y| {
        if inside(&SHEET, x as f32, y as f32) {
            let shade = if x > 700 { 0.72 } else { 1.0 };
            Rgb([(235.0 * shade) as u8, (228.0 * shade) as u8, (205.0 * shade) as u8])
        } else {
            Rgb([70 + (x % 7) as u8, 45, 30])
        }
    });
    for t in [0.2f32, 0.5, 0.8] {
        for i in 0..=1200 {
            let s = 0.2 + 0.6 * i as f32 / 1200.0;
            for dt in 0..=80 {
                let (x, y) = on_sheet(s, t + dt as f32 * 0.0005);
                img.put_pixel(x as u32, y as u32, Rgb([30, 30, 35]));
                img.put_pixel(x as u32 + 1, y as u32, Rgb([30, 30, 35]));
            }
        }
    }
    img
}

#[test]
fn finds_the_sheets_corners() {
    let found = scan::find_corners(&photo());
    for (f, want) in found.iter().zip(SHEET) {
        let d = (f.x - want.0).hypot(f.y - want.1);
        assert!(d < 25.0, "corner {f:?} is {d:.0} px from {want:?}");
    }
}

#[test]
fn straightens_and_cleans_to_white_paper_and_black_writing() {
    let p = photo();
    let corners = SHEET.map(|(x, y)| Pt { x, y });
    let page = scan::straighten(&p, &corners).unwrap();
    let (w, h) = page.dimensions();
    // as big as the sheet's longest edges
    assert!((880..=1000).contains(&w) && (980..=1100).contains(&h), "{w}x{h}");
    let doc = scan::clean(&page, Look::Document);
    // the three bars come out straight across, in the light and in the shadow: going down a column at 30 % and
    // at 75 % across meets exactly three dark runs, each about as tall as a bar (4 % of the page)
    for s in [0.3f32, 0.75] {
        let x = (s * w as f32) as u32;
        let mut runs = Vec::new();
        let mut len = 0;
        for y in 0..h {
            if doc.get_pixel(x, y)[0] < 90 {
                len += 1;
            } else if len > 0 {
                runs.push(len);
                len = 0;
            }
        }
        let tall = h as f32 * 0.04;
        assert!(runs.len() == 3 && runs.iter().all(|&r| (r as f32) > tall * 0.6 && (r as f32) < tall * 1.6), "dark runs at {s}: {runs:?}");
    }
    // paper is white, also under the shadow, and between the bars
    for (s, t) in [(0.1f32, 0.1f32), (0.9, 0.35), (0.5, 0.65), (0.9, 0.95)] {
        let v = doc.get_pixel((s * w as f32) as u32, (t * h as f32) as u32)[0];
        assert!(v > 235, "paper at ({s}, {t}) is {v}");
    }
    // grey and colour keep the size and stretch the levels
    let g = scan::clean(&page, Look::Grey);
    assert_eq!(g.dimensions(), (w, h));
    let c = scan::clean(&page, Look::Colour);
    assert!(c.pixels().any(|p| p[0] > 250) && c.pixels().any(|p| p[0] < 10));
}

#[test]
fn a_photo_without_a_clear_sheet_keeps_the_whole_photo() {
    let flat = RgbImage::from_pixel(300, 200, Rgb([128, 128, 128]));
    let c = scan::find_corners(&flat);
    assert_eq!((c[0].x, c[0].y, c[2].x, c[2].y), (0.0, 0.0, 300.0, 200.0)); // pinned: the photo's own corners
}

#[test]
fn bad_corners_are_an_error() {
    let p = RgbImage::from_pixel(100, 100, Rgb([255, 255, 255]));
    let line = [Pt { x: 0.0, y: 0.0 }, Pt { x: 50.0, y: 50.0 }, Pt { x: 100.0, y: 100.0 }, Pt { x: 25.0, y: 25.0 }];
    assert!(scan::straighten(&p, &line).is_err());
    let tiny = [Pt { x: 0.0, y: 0.0 }, Pt { x: 2.0, y: 0.0 }, Pt { x: 2.0, y: 2.0 }, Pt { x: 0.0, y: 2.0 }];
    assert!(scan::straighten(&p, &tiny).is_err());
    let nan = [Pt { x: f32::NAN, y: 0.0 }, Pt { x: 50.0, y: 0.0 }, Pt { x: 50.0, y: 50.0 }, Pt { x: 0.0, y: 50.0 }];
    assert!(scan::straighten(&p, &nan).is_err());
}
