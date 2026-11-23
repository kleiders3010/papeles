//! Reading (plan M1): page sizes, page pictures and text search, through PDFium. PDFium is not thread-safe: the app
//! keeps one `Viewer` per open file on one worker thread.
use std::path::Path;

use image::RgbaImage;
use pdfium_render::prelude::*;

use crate::{Error, Result, MAX_PAGES};

/// The longest side of a page picture: enough for a 4K screen zoomed in, small enough for a phone's memory.
pub const MAX_RENDER_SIDE: i32 = 4096;

/// PDFium, loaded from a folder that holds the platform's library (pdfium.dll / libpdfium.so).
pub struct Engine {
    pdfium: Pdfium,
}

impl Engine {
    pub fn load(dir: &Path) -> Result<Engine> {
        let lib = Pdfium::pdfium_platform_library_name_at_path(dir);
        let bindings = Pdfium::bind_to_library(&lib).map_err(|e| Error::Damaged(format!("PDFium not loaded from {}: {e}", lib.display())))?;
        Ok(Engine { pdfium: Pdfium::new(bindings) })
    }

    /// Opens a PDF held in memory (the app reads the file; PDFium never touches the disk).
    pub fn open(&self, bytes: Vec<u8>, password: Option<&str>) -> Result<Viewer<'_>> {
        let doc = self.pdfium.load_pdf_from_byte_vec(bytes, password).map_err(|e| match e {
            PdfiumError::PdfiumLibraryInternalError(PdfiumInternalError::PasswordError) => Error::Password,
            e => Error::Damaged(e.to_string()),
        })?;
        let n = doc.pages().len();
        if n < 0 || n as usize > MAX_PAGES {
            return Err(Error::TooBig(format!("{n} pages")));
        }
        Ok(Viewer { doc })
    }
}

/// A rectangle on a page in page points, origin top-left (as the screen draws it).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Area {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

/// One place the searched words were found.
#[derive(Clone, Debug, PartialEq)]
pub struct Hit {
    /// 1-based page number.
    pub page: u32,
    /// One area per line the match runs over.
    pub areas: Vec<Area>,
}

pub struct Viewer<'a> {
    doc: PdfDocument<'a>,
}

impl<'a> Viewer<'a> {
    pub fn page_count(&self) -> u32 {
        self.doc.pages().len().max(0) as u32
    }

    fn page(&self, page: u32) -> Result<PdfPage<'_>> {
        if page == 0 || page > self.page_count() {
            return Err(Error::NoSuchPage(0, page));
        }
        self.doc.pages().get((page - 1) as PdfPageIndex).map_err(|e| Error::Damaged(e.to_string()))
    }

    /// Width and height of a page in points, as shown (its rotation applied).
    pub fn page_size(&self, page: u32) -> Result<(f32, f32)> {
        let p = self.page(page)?;
        Ok((p.width().value, p.height().value))
    }

    /// The page drawn `width` pixels wide (capped at MAX_RENDER_SIDE on either side), form fields included, on white.
    pub fn render(&self, page: u32, width: i32) -> Result<RgbaImage> {
        let p = self.page(page)?;
        let cfg = PdfRenderConfig::new()
            .set_target_width(width.clamp(16, MAX_RENDER_SIDE))
            .set_maximum_width(MAX_RENDER_SIDE)
            .set_maximum_height(MAX_RENDER_SIDE)
            .render_form_data(true)
            .set_clear_color(PdfColor::WHITE);
        let img = p.render_with_config(&cfg).and_then(|b| b.as_image()).map_err(|e| Error::Damaged(e.to_string()))?;
        Ok(img.to_rgba8())
    }

    /// All the text of a page, in reading order as PDFium finds it.
    pub fn text(&self, page: u32) -> Result<String> {
        let p = self.page(page)?;
        // not .all(): it reads inside the page's shown size, which a turned page swaps (a portrait page turned 90
        // degrees read as empty, 2026-11-23); text sits in the unturned space, so read a box that covers both ways
        let side = p.width().value.max(p.height().value) * 2.0 + 1000.0;
        let all = PdfRect::new_from_values(-side, -side, side, side);
        Ok(p.text().map_err(|e| Error::Damaged(e.to_string()))?.inside_rect(all))
    }

    /// Every place `words` appear (case-insensitive), page by page, up to `limit` hits.
    pub fn search(&self, words: &str, limit: usize) -> Result<Vec<Hit>> {
        let words = words.trim();
        if words.is_empty() {
            return Ok(vec![]);
        }
        let opts = PdfSearchOptions::new().match_case(false);
        let mut hits = Vec::new();
        for n in 1..=self.page_count() {
            let p = self.page(n)?;
            let h = p.height().value;
            let text = p.text().map_err(|e| Error::Damaged(e.to_string()))?;
            let search = text.search(words, &opts).map_err(|e| Error::Damaged(e.to_string()))?;
            while let Some(segments) = search.find_next() {
                let areas = segments
                    .iter()
                    .map(|s| {
                        let b = s.bounds();
                        Area { x: b.left().value, y: h - b.top().value, w: b.width().value, h: b.height().value }
                    })
                    .collect();
                hits.push(Hit { page: n, areas });
                if hits.len() >= limit {
                    return Ok(hits);
                }
            }
        }
        Ok(hits)
    }
}
