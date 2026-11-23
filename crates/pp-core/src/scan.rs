//! Scanner (plan M5): a photo of a paper becomes a straight, clean page. Three steps, each its own call so the app
//! can show and correct them: find the paper's four corners (the person can drag them), straighten the paper to a
//! rectangle (perspective), and clean it up (document: white paper and dark ink; grey; colour). No outside library:
//! the photo never leaves the device, and the same code runs on the phone and the computer.
use image::{GrayImage, Luma, Rgb, RgbImage};

use crate::{Error, Result};

/// The longest side a straightened page gets (about 300 dpi on A4).
pub const MAX_PAGE_SIDE: u32 = 3508;
/// The side corner finding works at: enough for a sheet on a table, fast on a phone.
const FIND_SIDE: u32 = 400;

/// A point in the photo's pixels, x right, y down.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Pt {
    pub x: f32,
    pub y: f32,
}

/// How a page is cleaned up.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Look {
    /// White paper, dark ink: shadows and the paper's tint taken out.
    Document,
    /// Shades of grey, contrast stretched.
    Grey,
    /// The photo's colours, levels stretched.
    Colour,
}

fn luma(p: &Rgb<u8>) -> u8 {
    ((p[0] as u32 * 299 + p[1] as u32 * 587 + p[2] as u32 * 114) / 1000) as u8
}

/// The threshold that best splits a grey image in two (Otsu).
fn otsu(g: &GrayImage) -> u8 {
    let mut hist = [0u64; 256];
    for p in g.pixels() {
        hist[p[0] as usize] += 1;
    }
    let total: u64 = hist.iter().sum();
    let sum: f64 = hist.iter().enumerate().map(|(i, &h)| i as f64 * h as f64).sum();
    let (mut wb, mut sb, mut best, mut at) = (0u64, 0f64, -1f64, 128u8);
    for (t, &h) in hist.iter().enumerate() {
        wb += h;
        if wb == 0 || wb == total {
            continue;
        }
        sb += t as f64 * h as f64;
        let wf = total - wb;
        let (mb, mf) = (sb / wb as f64, (sum - sb) / wf as f64);
        let between = wb as f64 * wf as f64 * (mb - mf).powi(2);
        if between > best {
            best = between;
            at = t as u8;
        }
    }
    at
}

/// The paper's corners in `photo`: top left, top right, bottom right, bottom left (as the photo shows). The paper is
/// taken as the biggest bright patch; when none stands out, the whole photo (the person drags the corners then).
pub fn find_corners(photo: &RgbImage) -> [Pt; 4] {
    let (w, h) = photo.dimensions();
    let whole = [Pt { x: 0.0, y: 0.0 }, Pt { x: w as f32, y: 0.0 }, Pt { x: w as f32, y: h as f32 }, Pt { x: 0.0, y: h as f32 }];
    if w < 8 || h < 8 {
        return whole;
    }
    let k = FIND_SIDE as f32 / w.max(h) as f32;
    let (sw, sh) = (((w as f32 * k).round() as u32).max(8), ((h as f32 * k).round() as u32).max(8));
    let small = image::imageops::resize(photo, sw, sh, image::imageops::FilterType::Triangle);
    let grey = GrayImage::from_fn(sw, sh, |x, y| Luma([luma(small.get_pixel(x, y))]));
    let grey = image::imageops::blur(&grey, 2.0);
    let t = otsu(&grey);
    // the biggest connected bright patch (4-neighbours), found with a stack, no recursion
    let n = (sw * sh) as usize;
    let mut seen = vec![false; n];
    let mut best: Vec<u32> = Vec::new();
    for start in 0..n {
        if seen[start] || grey.as_raw()[start] <= t {
            continue;
        }
        let mut patch = Vec::new();
        let mut stack = vec![start as u32];
        seen[start] = true;
        while let Some(i) = stack.pop() {
            patch.push(i);
            let (x, y) = (i % sw, i / sw);
            let mut go = |j: u32| {
                let j = j as usize;
                if !seen[j] && grey.as_raw()[j] > t {
                    seen[j] = true;
                    stack.push(j as u32);
                }
            };
            if x > 0 { go(i - 1); }
            if x + 1 < sw { go(i + 1); }
            if y > 0 { go(i - sw); }
            if y + 1 < sh { go(i + sw); }
        }
        if patch.len() > best.len() {
            best = patch;
        }
    }
    // a paper fills a fair part of the photo but isn't all of it
    let share = best.len() as f32 / n as f32;
    if !(0.08..=0.97).contains(&share) {
        return whole;
    }
    // its corners: the points furthest along the four diagonals
    let mut c = [(f32::MAX, 0u32), (f32::MIN, 0u32), (f32::MIN, 0u32), (f32::MIN, 0u32)];
    for &i in &best {
        let (x, y) = ((i % sw) as f32, (i / sw) as f32);
        if x + y < c[0].0 { c[0] = (x + y, i); }
        if x - y > c[1].0 { c[1] = (x - y, i); }
        if x + y > c[2].0 { c[2] = (x + y, i); }
        if y - x > c[3].0 { c[3] = (y - x, i); }
    }
    c.map(|(_, i)| Pt { x: ((i % sw) as f32 + 0.5) / k, y: ((i / sw) as f32 + 0.5) / k })
}

/// Solves the 8 x 8 system of a perspective map (Gauss with pivoting).
fn solve(mut a: [[f64; 9]; 8]) -> Option<[f64; 8]> {
    for col in 0..8 {
        let piv = (col..8).max_by(|&i, &j| a[i][col].abs().total_cmp(&a[j][col].abs()))?;
        if a[piv][col].abs() < 1e-12 {
            return None;
        }
        a.swap(col, piv);
        for row in 0..8 {
            if row != col {
                let f = a[row][col] / a[col][col];
                for k in col..9 {
                    a[row][k] -= f * a[col][k];
                }
            }
        }
    }
    let mut x = [0f64; 8];
    for i in 0..8 {
        x[i] = a[i][8] / a[i][i];
    }
    Some(x)
}

/// The map from the output page (u, v) to the photo (x, y) taking the rectangle's corners onto `c`.
fn homography(c: &[Pt; 4], w: f64, h: f64) -> Option<[f64; 8]> {
    let src = [(0.0, 0.0), (w, 0.0), (w, h), (0.0, h)];
    let mut a = [[0f64; 9]; 8];
    for i in 0..4 {
        let (u, v) = src[i];
        let (x, y) = (c[i].x as f64, c[i].y as f64);
        a[2 * i] = [u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x];
        a[2 * i + 1] = [0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y];
    }
    solve(a)
}

fn dist(a: Pt, b: Pt) -> f32 {
    (a.x - b.x).hypot(a.y - b.y)
}

/// The paper inside `corners` (top left, top right, bottom right, bottom left) straightened into a rectangle as big
/// as its longest edges (capped at MAX_PAGE_SIDE).
pub fn straighten(photo: &RgbImage, corners: &[Pt; 4]) -> Result<RgbImage> {
    if corners.iter().any(|p| !(p.x.is_finite() && p.y.is_finite())) {
        return Err(Error::Damaged("the corners are not points".into()));
    }
    let [tl, tr, br, bl] = *corners;
    let mut w = dist(tl, tr).max(dist(bl, br));
    let mut h = dist(tl, bl).max(dist(tr, br));
    if w < 8.0 || h < 8.0 {
        return Err(Error::Empty);
    }
    let k = (MAX_PAGE_SIDE as f32 / w.max(h)).min(1.0);
    w *= k;
    h *= k;
    let (ow, oh) = (w.round() as u32, h.round() as u32);
    let m = homography(corners, ow as f64, oh as f64).ok_or_else(|| Error::Damaged("the corners are in a line".into()))?;
    let (pw, ph) = photo.dimensions();
    let sample = |x: f64, y: f64| -> Rgb<u8> {
        // bilinear, edges clamped
        let x = x.clamp(0.0, pw as f64 - 1.0);
        let y = y.clamp(0.0, ph as f64 - 1.0);
        let (x0, y0) = (x.floor() as u32, y.floor() as u32);
        let (x1, y1) = ((x0 + 1).min(pw - 1), (y0 + 1).min(ph - 1));
        let (fx, fy) = (x - x0 as f64, y - y0 as f64);
        let p = |a: u32, b: u32| photo.get_pixel(a, b);
        let mut o = [0u8; 3];
        for (c, v) in o.iter_mut().enumerate() {
            let top = p(x0, y0)[c] as f64 * (1.0 - fx) + p(x1, y0)[c] as f64 * fx;
            let bot = p(x0, y1)[c] as f64 * (1.0 - fx) + p(x1, y1)[c] as f64 * fx;
            *v = (top * (1.0 - fy) + bot * fy).round() as u8;
        }
        Rgb(o)
    };
    Ok(RgbImage::from_fn(ow, oh, |u, v| {
        let (u, v) = (u as f64 + 0.5, v as f64 + 0.5);
        let d = m[6] * u + m[7] * v + 1.0;
        sample((m[0] * u + m[1] * v + m[2]) / d - 0.5, (m[3] * u + m[4] * v + m[5]) / d - 0.5)
    }))
}

/// The values at the low and high `cut` share of a channel's histogram, for stretching it.
fn levels(values: impl Iterator<Item = u8>, cut: f64) -> (u8, u8) {
    let mut hist = [0u64; 256];
    let mut n = 0u64;
    for v in values {
        hist[v as usize] += 1;
        n += 1;
    }
    let lim = (n as f64 * cut) as u64;
    let (mut acc, mut lo) = (0u64, 0u8);
    for (i, &c) in hist.iter().enumerate() {
        acc += c;
        if acc > lim {
            lo = i as u8;
            break;
        }
    }
    let (mut acc, mut hi) = (0u64, 255u8);
    for (i, &c) in hist.iter().enumerate().rev() {
        acc += c;
        if acc > lim {
            hi = i as u8;
            break;
        }
    }
    if hi <= lo { (0, 255) } else { (lo, hi) }
}

fn stretch(v: u8, lo: u8, hi: u8) -> u8 {
    (((v as f32 - lo as f32) / (hi as f32 - lo as f32)).clamp(0.0, 1.0) * 255.0).round() as u8
}

/// A straightened page cleaned up as `look`.
pub fn clean(page: &RgbImage, look: Look) -> RgbImage {
    let (w, h) = page.dimensions();
    match look {
        Look::Colour => {
            let ch: Vec<(u8, u8)> = (0..3).map(|c| levels(page.pixels().map(|p| p[c]), 0.005)).collect();
            RgbImage::from_fn(w, h, |x, y| {
                let p = page.get_pixel(x, y);
                Rgb([stretch(p[0], ch[0].0, ch[0].1), stretch(p[1], ch[1].0, ch[1].1), stretch(p[2], ch[2].0, ch[2].1)])
            })
        }
        Look::Grey => {
            let (lo, hi) = levels(page.pixels().map(luma), 0.005);
            RgbImage::from_fn(w, h, |x, y| {
                let v = stretch(luma(page.get_pixel(x, y)), lo, hi);
                Rgb([v, v, v])
            })
        }
        Look::Document => {
            // the paper's own brightness, place by place (a wide blur of a small copy): dividing by it takes out
            // shadows and tint, then the ink is darkened and the paper made white
            let grey = GrayImage::from_fn(w, h, |x, y| Luma([luma(page.get_pixel(x, y))]));
            let k = (256.0 / w.max(h).max(1) as f32).min(1.0);
            let (sw, sh) = (((w as f32 * k).round() as u32).max(1), ((h as f32 * k).round() as u32).max(1));
            let small = image::imageops::resize(&grey, sw, sh, image::imageops::FilterType::Triangle);
            // the paper is the bright part: a max filter before the blur keeps ink out of the background
            let r = 3i32;
            let maxed = GrayImage::from_fn(sw, sh, |x, y| {
                let mut m = 0u8;
                for dy in -r..=r {
                    for dx in -r..=r {
                        let (a, b) = ((x as i32 + dx).clamp(0, sw as i32 - 1) as u32, (y as i32 + dy).clamp(0, sh as i32 - 1) as u32);
                        m = m.max(small.get_pixel(a, b)[0]);
                    }
                }
                Luma([m])
            });
            let bg = image::imageops::resize(&image::imageops::blur(&maxed, 4.0), w, h, image::imageops::FilterType::Triangle);
            RgbImage::from_fn(w, h, |x, y| {
                let g = grey.get_pixel(x, y)[0] as f32;
                let b = (bg.get_pixel(x, y)[0] as f32).max(1.0);
                let r = (g / b).min(1.0);
                // paper (r near 1) white; ink pushed down with a curve so thin strokes stay
                let v = if r > 0.88 { 255.0 } else { (r / 0.88).powf(2.2) * 255.0 };
                let v = v.round() as u8;
                Rgb([v, v, v])
            })
        }
    }
}

/// A camera photo (JPEG or PNG) as the person saw it: turned as its EXIF says, within the app's size limits.
pub fn open_photo(bytes: &[u8]) -> Result<RgbImage> {
    use image::ImageDecoder;
    let mut r = image::ImageReader::new(std::io::Cursor::new(bytes)).with_guessed_format().map_err(|e| Error::Damaged(e.to_string()))?;
    if r.format().is_none() {
        return Err(Error::Damaged("not a picture this app reads (JPEG or PNG)".into()));
    }
    r.limits(crate::make::limits());
    let bad = |e: image::ImageError| match e {
        image::ImageError::Limits(_) => Error::TooBig("picture".into()),
        e => Error::Damaged(e.to_string()),
    };
    let mut dec = r.into_decoder().map_err(bad)?;
    let turn = dec.orientation().unwrap_or(image::metadata::Orientation::NoTransforms);
    let mut img = image::DynamicImage::from_decoder(dec).map_err(bad)?;
    img.apply_orientation(turn);
    Ok(img.to_rgb8())
}

/// A cleaned page as a JPEG for the PDF (document pages compress well; quality kept high enough for small print).
pub fn page_jpeg(page: &RgbImage) -> Result<Vec<u8>> {
    let mut out = std::io::Cursor::new(Vec::new());
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 85).encode_image(page).map_err(|e| Error::Damaged(e.to_string()))?;
    Ok(out.into_inner())
}

/// A photo at most `max_side` pixels on its longest side (shape kept), as RGBA, to show on screen.
pub fn preview(photo: &RgbImage, max_side: u32) -> image::RgbaImage {
    let d = image::DynamicImage::ImageRgb8(photo.clone());
    let d = if photo.width().max(photo.height()) > max_side { d.resize(max_side, max_side, image::imageops::FilterType::Triangle) } else { d };
    d.to_rgba8()
}
