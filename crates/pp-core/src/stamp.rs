//! Sign (plan M4): a picture (a drawn signature, a stamp) laid onto a page, transparency kept so the paper's lines
//! show around the ink. Where it goes is given as the reader shows the page: points from the top left of the page
//! as turned on screen (/Rotate), so a tap on a turned page lands where it was tapped, the picture upright.
use lopdf::{dictionary, Dictionary, Document, Object, ObjectId, Stream};

use crate::view::Area;
use crate::{Error, Result};

/// The biggest stamp picture kept: plenty for a signature, small enough not to swell the PDF.
pub const MAX_STAMP_SIDE: u32 = 2000;

fn damaged(e: impl std::fmt::Display) -> Error {
    Error::Damaged(e.to_string())
}

/// The picture as an image XObject plus its transparency as a soft mask.
fn picture(doc: &mut Document, bytes: &[u8]) -> Result<(ObjectId, u32, u32)> {
    let (img, _) = crate::make::decode(bytes)?;
    let img = if img.width() > MAX_STAMP_SIDE || img.height() > MAX_STAMP_SIDE {
        img.resize(MAX_STAMP_SIDE, MAX_STAMP_SIDE, image::imageops::FilterType::Lanczos3)
    } else {
        img
    };
    let (w, h) = (img.width(), img.height());
    let rgba = img.to_rgba8();
    let rgb: Vec<u8> = rgba.pixels().flat_map(|p| [p[0], p[1], p[2]]).collect();
    let mut dict = dictionary! {
        "Type" => "XObject", "Subtype" => "Image", "Width" => w as i64, "Height" => h as i64,
        "ColorSpace" => "DeviceRGB", "BitsPerComponent" => 8,
    };
    if rgba.pixels().any(|p| p[3] < 255) {
        let alpha: Vec<u8> = rgba.pixels().map(|p| p[3]).collect();
        let mut m = Stream::new(dictionary! {
            "Type" => "XObject", "Subtype" => "Image", "Width" => w as i64, "Height" => h as i64,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
        }, alpha);
        m.compress().map_err(damaged)?;
        dict.set("SMask", doc.add_object(m));
    }
    let mut s = Stream::new(dict, rgb);
    s.compress().map_err(damaged)?;
    Ok((doc.add_object(s), w, h))
}

/// Lays `png` (or a JPEG) onto `page` (from 1) at `area` (points, top left, as the page shows on screen).
pub fn stamp(doc: &mut Document, page: u32, area: Area, png: &[u8]) -> Result<()> {
    let page_id = *doc.get_pages().get(&page).ok_or(Error::NoSuchPage(0, page))?;
    if !(area.w > 0.5 && area.h > 0.5 && area.w.is_finite() && area.h.is_finite() && area.x.is_finite() && area.y.is_finite()) {
        return Err(Error::Damaged("the stamp has no size".into()));
    }
    // a point as shown (top-left origin, turned) to the page's own space, and the screen's directions with it
    let f = crate::annotate::Frame::of(doc, page_id)?;
    let (right, (ux, uy)) = f.right_up();
    let (img, _, _) = picture(doc, png)?;
    // the picture's own square: x along the screen's right, y along the screen's up, from its bottom-left corner
    let (ox, oy) = f.to_user(area.x, area.y + area.h);
    let (a, b) = (right.0 * area.w, right.1 * area.w);
    let (c, d) = (ux * area.h, uy * area.h);

    // the page gets its own Resources (inherited ones copied onto it) with the picture under a name not yet used
    let mut res: Dictionary = crate::annotate::inherited(doc, page_id, b"Resources").and_then(|o| o.as_dict().ok().cloned()).unwrap_or_default();
    let mut xo: Dictionary = res.get(b"XObject").ok().and_then(|o| doc.dereference(o).ok()).and_then(|(_, o)| o.as_dict().ok().cloned()).unwrap_or_default();
    let name = (1..).map(|i| format!("PpStamp{i}")).find(|n| !xo.has(n.as_bytes())).unwrap();
    xo.set(name.as_bytes().to_vec(), img);
    res.set("XObject", Object::Dictionary(xo));
    // the page's own drawing is wrapped in q / Q so whatever state it leaves doesn't move or tint the stamp
    let open = doc.add_object(Stream::new(dictionary! {}, b"q\n".to_vec()));
    let ops = format!("Q q {a:.4} {b:.4} {c:.4} {d:.4} {ox:.4} {oy:.4} cm /{name} Do Q\n");
    let close = doc.add_object(Stream::new(dictionary! {}, ops.into_bytes()));
    let page_dict = doc.get_dictionary(page_id).map_err(damaged)?;
    let mut contents: Vec<Object> = match page_dict.get(b"Contents") {
        Ok(Object::Array(a)) => a.clone(),
        Ok(Object::Reference(r)) => match doc.get_object(*r) {
            Ok(Object::Array(a)) => a.clone(),
            _ => vec![Object::Reference(*r)],
        },
        _ => vec![],
    };
    contents.insert(0, Object::Reference(open));
    contents.push(Object::Reference(close));
    let page_dict = doc.get_dictionary_mut(page_id).map_err(damaged)?;
    page_dict.set("Resources", Object::Dictionary(res));
    page_dict.set("Contents", Object::Array(contents));
    Ok(())
}

/// Longest side of a signature picture made from strokes.
pub const INK_SIDE: u32 = 1200;
/// Most points kept from a drawn signature (a hand-drawn one is a few hundred).
pub const MAX_INK_POINTS: usize = 20_000;

/// A drawn signature as a picture: `strokes` are lines of points in any units (as the finger or mouse drew them, y
/// down), `pen` the line width in the same units. Ink is a dark blue on a transparent background, cropped to the
/// drawing with a little room, so it can be laid on any paper.
pub fn ink_picture(strokes: &[Vec<(f32, f32)>], pen: f32) -> Result<Vec<u8>> {
    let pts: Vec<(f32, f32)> = strokes.iter().flatten().copied().filter(|(x, y)| x.is_finite() && y.is_finite()).collect();
    if pts.is_empty() || !(pen.is_finite() && pen > 0.0) {
        return Err(Error::Empty);
    }
    if pts.len() > MAX_INK_POINTS {
        return Err(Error::TooBig(format!("{} points", pts.len())));
    }
    let (mut x0, mut y0, mut x1, mut y1) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
    for &(x, y) in &pts {
        x0 = x0.min(x);
        y0 = y0.min(y);
        x1 = x1.max(x);
        y1 = y1.max(y);
    }
    let pad = pen * 1.5;
    let (x0, y0, x1, y1) = (x0 - pad, y0 - pad, x1 + pad, y1 + pad);
    let s = INK_SIDE as f32 / (x1 - x0).max(y1 - y0).max(1e-3);
    let (w, h) = (((x1 - x0) * s).ceil().max(1.0) as u32, ((y1 - y0) * s).ceil().max(1.0) as u32);
    let r = (pen * s / 2.0).max(1.0);
    let mut alpha = vec![0f32; (w * h) as usize];
    // a round pen pressed along every segment, edges softened by a pixel
    let mut dab = |cx: f32, cy: f32| {
        let (ax, bx) = ((cx - r - 1.0).floor().max(0.0) as u32, ((cx + r + 1.0).ceil() as u32).min(w - 1));
        let (ay, by) = ((cy - r - 1.0).floor().max(0.0) as u32, ((cy + r + 1.0).ceil() as u32).min(h - 1));
        for y in ay..=by {
            for x in ax..=bx {
                let d = ((x as f32 + 0.5 - cx).powi(2) + (y as f32 + 0.5 - cy).powi(2)).sqrt();
                let a = (r + 0.5 - d).clamp(0.0, 1.0);
                let i = (y * w + x) as usize;
                if a > alpha[i] {
                    alpha[i] = a;
                }
            }
        }
    };
    for line in strokes {
        let line: Vec<(f32, f32)> = line.iter().copied().filter(|(x, y)| x.is_finite() && y.is_finite())
            .map(|(x, y)| ((x - x0) * s, (y - y0) * s)).collect();
        if let Some(&(x, y)) = line.first() {
            dab(x, y);
        }
        for p in line.windows(2) {
            let ((ax, ay), (bx, by)) = (p[0], p[1]);
            let n = ((bx - ax).hypot(by - ay) / (r * 0.4).max(0.5)).ceil().min(4000.0) as u32;
            for k in 1..=n.max(1) {
                let t = k as f32 / n.max(1) as f32;
                dab(ax + (bx - ax) * t, ay + (by - ay) * t);
            }
        }
    }
    let img = image::RgbaImage::from_fn(w, h, |x, y| image::Rgba([0x1A, 0x23, 0x7E, (alpha[(y * w + x) as usize] * 255.0) as u8]));
    let mut out = std::io::Cursor::new(Vec::new());
    img.write_to(&mut out, image::ImageFormat::Png).map_err(damaged)?;
    Ok(out.into_inner())
}
