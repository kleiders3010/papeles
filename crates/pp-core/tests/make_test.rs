// Pictures to PDF and shrinking: right page count and shape, JPEGs kept as they are, shrink never grows a file.
use std::io::Cursor;

use image::codecs::jpeg::JpegEncoder;
use image::{DynamicImage, ImageFormat, Rgb, RgbImage, Rgba, RgbaImage};
use lopdf::Object;
use pp_core::make::{self, PageSize, Shrink};
use pp_core::Error;

/// A noisy picture (noise does not compress, like a photo) of w x h.
fn photo(w: u32, h: u32) -> RgbImage {
    let mut s: u32 = 12345;
    RgbImage::from_fn(w, h, |x, y| {
        s = s.wrapping_mul(1103515245).wrapping_add(12345);
        Rgb([(x % 256) as u8, (y % 256) as u8, (s >> 16) as u8])
    })
}

fn jpeg(img: &RgbImage, q: u8) -> Vec<u8> {
    let mut out = Vec::new();
    JpegEncoder::new_with_quality(&mut out, q).encode_image(img).unwrap();
    out
}

fn png(img: &DynamicImage) -> Vec<u8> {
    let mut out = Cursor::new(Vec::new());
    img.write_to(&mut out, ImageFormat::Png).unwrap();
    out.into_inner()
}

fn media_box(d: &lopdf::Document, page: u32) -> Vec<f32> {
    let id = d.get_pages()[&page];
    d.get_dictionary(id).unwrap().get(b"MediaBox").unwrap().as_array().unwrap().iter().map(|o| o.as_float().unwrap()).collect()
}

fn images(d: &lopdf::Document) -> Vec<&lopdf::Stream> {
    d.objects
        .values()
        .filter_map(|o| match o {
            Object::Stream(s) if s.dict.get(b"Subtype").and_then(Object::as_name).is_ok_and(|n| n == b"Image") => Some(s),
            _ => None,
        })
        .collect()
}

#[test]
fn one_page_per_picture_turned_to_fit() {
    let wide = jpeg(&photo(400, 300), 80);
    let tall = png(&DynamicImage::ImageRgb8(photo(300, 400)));
    let mut d = make::images_to_pdf(&[wide.clone(), tall], PageSize::A4).unwrap();
    let bytes = pp_core::save(&mut d).unwrap();
    let d = pp_core::open(&bytes, None).unwrap();
    assert_eq!(d.get_pages().len(), 2); // pinned: two pictures in
    let a = media_box(&d, 1);
    assert!(a[2] > a[3], "a wide picture gets a landscape page: {a:?}");
    let b = media_box(&d, 2);
    assert!(b[3] > b[2], "a tall picture gets a portrait page: {b:?}");
    // the JPEG went in byte for byte (no second compression)
    assert!(images(&d).iter().any(|s| s.content == wide));
}

#[test]
fn fit_makes_the_page_the_picture_at_150_dpi() {
    let d = make::images_to_pdf(&[jpeg(&photo(300, 150), 80)], PageSize::Fit).unwrap();
    let m = media_box(&d, 1);
    assert!((m[2] - 144.0).abs() < 0.01 && (m[3] - 72.0).abs() < 0.01, "{m:?}"); // pinned: 300 px at 150 dpi = 2 in = 144 pt
}

#[test]
fn transparency_is_laid_on_white() {
    let clear = DynamicImage::ImageRgba8(RgbaImage::from_pixel(4, 4, Rgba([0, 0, 0, 0])));
    let d = make::images_to_pdf(&[png(&clear)], PageSize::Fit).unwrap();
    let raw = images(&d)[0].decompressed_content().unwrap();
    assert!(raw.iter().all(|b| *b == 255), "a see-through picture prints white");
}

#[test]
fn shrink_makes_big_photos_smaller_and_never_bigger() {
    let big = jpeg(&photo(3000, 2000), 95);
    let mut d = make::images_to_pdf(&[big.clone()], PageSize::A4).unwrap();
    let before = pp_core::save(&mut d).unwrap().len();
    let rep = make::shrink(&mut d, Shrink::Small).unwrap();
    let after = pp_core::save(&mut d).unwrap().len();
    assert_eq!((rep.pictures_seen, rep.pictures_redone), (1, 1)); // pinned: one photo, redone
    assert!(after < before / 2, "{before} -> {after}");
    let img = images(&d)[0];
    assert_eq!(img.dict.get(b"Width").unwrap().as_i64().unwrap(), 1600); // pinned: Small keeps 1600 px on the long side

    // an already small picture is left as it is
    let small = jpeg(&photo(200, 100), 40);
    let mut d = make::images_to_pdf(&[small.clone()], PageSize::A4).unwrap();
    let rep = make::shrink(&mut d, Shrink::Light).unwrap();
    assert_eq!(rep.pictures_redone, 0); // pinned: nothing gained, nothing changed
    assert_eq!(images(&d)[0].content, small);
}

#[test]
fn bad_pictures_are_errors() {
    assert_eq!(make::images_to_pdf(&[], PageSize::A4).unwrap_err(), Error::Empty);
    assert!(matches!(make::images_to_pdf(&[b"hello".to_vec()], PageSize::A4), Err(Error::Damaged(_))));
    // a PNG header claiming 100000 x 100000 is refused before decoding
    let mut huge = png(&DynamicImage::ImageRgb8(photo(2, 2)));
    huge[16..20].copy_from_slice(&100_000u32.to_be_bytes());
    huge[20..24].copy_from_slice(&100_000u32.to_be_bytes());
    assert!(matches!(make::images_to_pdf(&[huge], PageSize::A4), Err(Error::TooBig(_)) | Err(Error::Damaged(_))));
}
