//! Making PDFs (plan M3): pictures to a PDF, and making a PDF smaller by re-encoding its pictures.
use std::io::Cursor;

use image::codecs::jpeg::JpegEncoder;
use image::{DynamicImage, ImageFormat, ImageReader, Limits};
use lopdf::{dictionary, Document, Object, ObjectId, Stream};

use crate::{Error, Result};

/// The biggest picture decoded: 60 megapixels (a 50 MP phone photo fits), at most 512 MB while decoding.
const MAX_SIDE: u32 = 12_000;
const MAX_DECODE_BYTES: u64 = 512 * 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PageSize {
    A4,
    Letter,
    /// The page is the picture's own size, at 150 dots per inch.
    Fit,
}

impl PageSize {
    /// Width and height in points (1/72 inch), portrait.
    fn points(self) -> Option<(f32, f32)> {
        match self {
            PageSize::A4 => Some((595.28, 841.89)),
            PageSize::Letter => Some((612.0, 792.0)),
            PageSize::Fit => None,
        }
    }
}

fn limits() -> Limits {
    let mut l = Limits::default();
    l.max_image_width = Some(MAX_SIDE);
    l.max_image_height = Some(MAX_SIDE);
    l.max_alloc = Some(MAX_DECODE_BYTES);
    l
}

fn decode(bytes: &[u8]) -> Result<(DynamicImage, Option<ImageFormat>)> {
    let mut r = ImageReader::new(Cursor::new(bytes)).with_guessed_format().map_err(|e| Error::Damaged(e.to_string()))?;
    let format = r.format();
    if format.is_none() {
        return Err(Error::Damaged("not a picture this app reads (JPEG or PNG)".into()));
    }
    r.limits(limits());
    let img = r.decode().map_err(|e| match e {
        image::ImageError::Limits(_) => Error::TooBig("picture".into()),
        e => Error::Damaged(e.to_string()),
    })?;
    Ok((img, format))
}

/// A picture as a PDF image XObject: a JPEG travels as it is (no quality lost); anything else is stored losslessly
/// (Flate). Transparency is laid on white, as it would print.
fn image_object(bytes: &[u8]) -> Result<(Stream, u32, u32)> {
    let (img, format) = decode(bytes)?;
    let (w, h) = (img.width(), img.height());
    let gray = matches!(img.color(), image::ColorType::L8 | image::ColorType::L16 | image::ColorType::La8 | image::ColorType::La16);
    let space = if gray { "DeviceGray" } else { "DeviceRGB" };
    // a CMYK JPEG decodes to RGB here, but its raw bytes would need DeviceCMYK: only plain 1- or 3-channel JPEGs pass through
    if format == Some(ImageFormat::Jpeg) && !img.color().has_alpha() && jpeg_components(bytes).is_some_and(|c| c == 1 || c == 3) {
        let dict = dictionary! {
            "Type" => "XObject", "Subtype" => "Image", "Width" => w as i64, "Height" => h as i64,
            "ColorSpace" => space, "BitsPerComponent" => 8, "Filter" => "DCTDecode",
        };
        return Ok((Stream::new(dict, bytes.to_vec()), w, h));
    }
    let flat = flatten_on_white(&img);
    let raw = if gray { flat.to_luma8().into_raw() } else { flat.to_rgb8().into_raw() };
    let dict = dictionary! {
        "Type" => "XObject", "Subtype" => "Image", "Width" => w as i64, "Height" => h as i64,
        "ColorSpace" => space, "BitsPerComponent" => 8,
    };
    let mut s = Stream::new(dict, raw);
    s.compress().map_err(|e| Error::Damaged(e.to_string()))?;
    Ok((s, w, h))
}

/// Number of colour components in a JPEG's frame header (SOF0..SOF15 except DHT/JPG/DAC), None if not found.
fn jpeg_components(b: &[u8]) -> Option<u8> {
    let mut i = 2;
    while i + 9 < b.len() {
        if b[i] != 0xFF {
            return None;
        }
        let marker = b[i + 1];
        let len = u16::from_be_bytes([b[i + 2], b[i + 3]]) as usize;
        if (0xC0..=0xCF).contains(&marker) && ![0xC4, 0xC8, 0xCC].contains(&marker) {
            return Some(b[i + 9]);
        }
        i += 2 + len;
    }
    None
}

fn flatten_on_white(img: &DynamicImage) -> DynamicImage {
    if !img.color().has_alpha() {
        return img.clone();
    }
    let mut rgba = img.to_rgba8();
    for p in rgba.pixels_mut() {
        let a = p[3] as u32;
        for c in 0..3 {
            p[c] = ((p[c] as u32 * a + 255 * (255 - a)) / 255) as u8;
        }
        p[3] = 255;
    }
    DynamicImage::ImageRgba8(rgba)
}

/// One page per picture, in order. On A4 / Letter the page turns to match the picture and the picture is fitted
/// inside a 1/4 inch margin, centred; with Fit the page is the picture.
pub fn images_to_pdf(pictures: &[Vec<u8>], size: PageSize) -> Result<Document> {
    if pictures.is_empty() {
        return Err(Error::Empty);
    }
    if pictures.len() > crate::MAX_PAGES {
        return Err(Error::TooBig(format!("{} pictures", pictures.len())));
    }
    let mut doc = Document::with_version("1.7");
    let pages_id = doc.new_object_id();
    let mut kids = Vec::new();
    for bytes in pictures {
        let (stream, w, h) = image_object(bytes)?;
        let img_id = doc.add_object(stream);
        let (pw, ph, x, y, dw, dh) = match size.points() {
            None => {
                let (dw, dh) = (w as f32 * 72.0 / 150.0, h as f32 * 72.0 / 150.0);
                (dw, dh, 0.0, 0.0, dw, dh)
            }
            Some((a, b)) => {
                let (pw, ph) = if w > h { (b, a) } else { (a, b) };
                let m = 18.0;
                let k = ((pw - 2.0 * m) / w as f32).min((ph - 2.0 * m) / h as f32);
                let (dw, dh) = (w as f32 * k, h as f32 * k);
                (pw, ph, (pw - dw) / 2.0, (ph - dh) / 2.0, dw, dh)
            }
        };
        let content = format!("q {dw:.2} 0 0 {dh:.2} {x:.2} {y:.2} cm /Im0 Do Q");
        let content_id = doc.add_object(Stream::new(dictionary! {}, content.into_bytes()));
        let page = doc.add_object(dictionary! {
            "Type" => "Page", "Parent" => pages_id, "Contents" => content_id,
            "MediaBox" => vec![0.into(), 0.into(), Object::Real(pw), Object::Real(ph)],
            "Resources" => dictionary! { "XObject" => dictionary! { "Im0" => img_id } },
        });
        kids.push(Object::Reference(page));
    }
    let count = kids.len() as i64;
    doc.objects.insert(pages_id, Object::Dictionary(dictionary! { "Type" => "Pages", "Kids" => kids, "Count" => count }));
    let cat = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", cat);
    Ok(doc)
}

/// How hard `shrink` works: the longest side a picture keeps, and the JPEG quality.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Shrink {
    /// Screen reading: 1600 px, quality 60.
    Small,
    /// Good for printing at home: 2500 px, quality 75.
    Medium,
    /// Light touch: 3500 px, quality 85.
    Light,
}

impl Shrink {
    fn params(self) -> (u32, u8) {
        match self {
            Shrink::Small => (1600, 60),
            Shrink::Medium => (2500, 75),
            Shrink::Light => (3500, 85),
        }
    }
}

/// What `shrink` did, for the app to show.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct ShrinkReport {
    pub pictures_seen: u32,
    pub pictures_redone: u32,
}

/// Re-encodes plain RGB / grey pictures (JPEG or Flate, 8 bits) as smaller JPEGs, each kept only if it got smaller;
/// masks, CMYK, indexed and other pictures stay as they are. Then packs the remaining streams.
pub fn shrink(doc: &mut Document, how: Shrink) -> Result<ShrinkReport> {
    let (max_side, quality) = how.params();
    let mut rep = ShrinkReport::default();
    let ids: Vec<ObjectId> = doc
        .objects
        .iter()
        .filter(|(_, o)| matches!(o, Object::Stream(s) if s.dict.get(b"Subtype").and_then(Object::as_name).is_ok_and(|n| n == b"Image")))
        .map(|(id, _)| *id)
        .collect();
    for id in ids {
        rep.pictures_seen += 1;
        let Ok(Object::Stream(s)) = doc.get_object(id) else { continue };
        let Some(new) = redo_picture(s, max_side, quality) else { continue };
        if new.content.len() < s.content.len() {
            doc.objects.insert(id, Object::Stream(new));
            rep.pictures_redone += 1;
        }
    }
    doc.compress();
    Ok(rep)
}

fn redo_picture(s: &Stream, max_side: u32, quality: u8) -> Option<Stream> {
    let d = &s.dict;
    if d.get(b"SMask").is_ok() || d.get(b"Mask").is_ok() || d.get(b"ImageMask").and_then(Object::as_bool).unwrap_or(false) {
        return None;
    }
    if d.get(b"BitsPerComponent").and_then(Object::as_i64).ok()? != 8 {
        return None;
    }
    let space = d.get(b"ColorSpace").and_then(Object::as_name).ok()?;
    let gray = match space {
        b"DeviceRGB" => false,
        b"DeviceGray" => true,
        _ => return None,
    };
    let w = d.get(b"Width").and_then(Object::as_i64).ok()?;
    let h = d.get(b"Height").and_then(Object::as_i64).ok()?;
    if w <= 0 || h <= 0 || w > MAX_SIDE as i64 || h > MAX_SIDE as i64 {
        return None;
    }
    let (w, h) = (w as u32, h as u32);
    let filter = match d.get(b"Filter") {
        Ok(Object::Name(n)) => Some(n.clone()),
        Ok(Object::Array(a)) if a.len() == 1 => a[0].as_name().ok().map(|n| n.to_vec()),
        Ok(_) => return None,
        Err(_) => None,
    };
    let img = match filter.as_deref() {
        Some(b"DCTDecode") => decode(&s.content).ok()?.0,
        Some(b"FlateDecode") | None => {
            if d.get(b"DecodeParms").is_ok() {
                return None; // predictors: left alone
            }
            let ch = if gray { 1 } else { 3 };
            // never inflate past the size the picture says it has (a small stream can claim gigabytes)
            let want = (w as usize) * (h as usize) * ch;
            let raw = if filter.is_some() { s.decompressed_content_with_limit(want + 1).ok()? } else { s.content.clone() };
            if raw.len() != want {
                return None;
            }
            if gray {
                DynamicImage::ImageLuma8(image::GrayImage::from_raw(w, h, raw)?)
            } else {
                DynamicImage::ImageRgb8(image::RgbImage::from_raw(w, h, raw)?)
            }
        }
        _ => return None,
    };
    let img = if w.max(h) > max_side { img.thumbnail(max_side, max_side) } else { img };
    let mut out = Vec::new();
    let mut enc = JpegEncoder::new_with_quality(&mut out, quality);
    if gray {
        enc.encode_image(&img.to_luma8()).ok()?;
    } else {
        enc.encode_image(&img.to_rgb8()).ok()?;
    }
    let mut nd = d.clone();
    nd.set("Width", img.width() as i64);
    nd.set("Height", img.height() as i64);
    nd.set("Filter", "DCTDecode");
    nd.remove(b"DecodeParms");
    nd.remove(b"Length");
    Some(Stream::new(nd, out))
}
