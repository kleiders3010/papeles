//! Kotlin bridge (UniFFI) for Papeles: the app hands file bytes in and gets bytes or page pictures back; nothing here
//! touches the disk or the network. Every call is blocking: the app runs them off the main thread.
//!
//! PDFium (reading, page pictures, search) is loaded once with [`load_engine`]; page work (merge, split, rotate, ...)
//! and making PDFs run on lopdf and need no engine.
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};

use pp_core::make::{PageSize, Shrink};
use pp_core::pages::Pick;
use pp_core::view::{Engine, Viewer};

uniffi::setup_scaffolding!();

#[derive(Debug, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum PpError {
    /// Not a PDF, or broken beyond repair.
    #[error("damaged PDF: {why}")]
    Damaged { why: String },
    /// The file needs its password, or the one given is wrong.
    #[error("this PDF needs its password")]
    Password,
    /// Too many pages or too much data to handle safely.
    #[error("too big to open safely: {why}")]
    TooBig { why: String },
    /// A page that isn't there (`file` counts from 0, `page` from 1).
    #[error("file {file} has no page {page}")]
    NoSuchPage { file: u32, page: u32 },
    /// Nothing to make: no pages or no pictures.
    #[error("nothing to make")]
    Empty,
    /// [`load_engine`] has not run (or failed): reading needs PDFium.
    #[error("the PDF reader is not loaded: {why}")]
    NoEngine { why: String },
}

impl From<pp_core::Error> for PpError {
    fn from(e: pp_core::Error) -> Self {
        match e {
            pp_core::Error::Damaged(why) => PpError::Damaged { why },
            pp_core::Error::Password => PpError::Password,
            pp_core::Error::TooBig(why) => PpError::TooBig { why },
            pp_core::Error::NoSuchPage(file, page) => PpError::NoSuchPage { file: file as u32, page },
            pp_core::Error::Empty => PpError::Empty,
        }
    }
}

type Result<T> = std::result::Result<T, PpError>;

// ---------- reading (PDFium) ----------

static ENGINE: OnceLock<Engine> = OnceLock::new();

/// Loads PDFium from the folder holding pdfium.dll / libpdfium.so. Safe to call again: the first success stays.
#[uniffi::export]
pub fn load_engine(dir: String) -> Result<()> {
    if ENGINE.get().is_some() {
        return Ok(());
    }
    let e = Engine::load(Path::new(&dir)).map_err(|e| PpError::NoEngine { why: e.to_string() })?;
    let _ = ENGINE.set(e);
    Ok(())
}

fn engine() -> Result<&'static Engine> {
    ENGINE.get().ok_or(PpError::NoEngine { why: "load_engine was not called".into() })
}

/// A page's size in points, as shown (rotation applied).
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct PageBox {
    pub width: f32,
    pub height: f32,
}

/// A page drawn as RGBA pixels, row by row.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Picture {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

/// A rectangle on a page in points, origin top-left.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Area {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

/// One place the searched words were found: its page (from 1) and one area per line it runs over.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Hit {
    pub page: u32,
    pub areas: Vec<Area>,
}

// PDFium documents are not Send. Every call into one goes through this file's Mutex, and pdfium-render's
// `thread_safe` feature serialises all PDFium calls process-wide, so moving the handle between threads is sound.
struct Held(Viewer<'static>);
unsafe impl Send for Held {}

/// An open PDF for reading. Drop it (Kotlin: `close()`) when the file is closed.
#[derive(uniffi::Object)]
pub struct PdfFile {
    viewer: Mutex<Held>,
}

/// Opens a PDF for reading from its bytes.
#[uniffi::export]
pub fn open_pdf(bytes: Vec<u8>, password: Option<String>) -> Result<Arc<PdfFile>> {
    let v = engine()?.open(bytes, password.as_deref())?;
    Ok(Arc::new(PdfFile { viewer: Mutex::new(Held(v)) }))
}

impl PdfFile {
    fn with<T>(&self, f: impl FnOnce(&Viewer<'static>) -> pp_core::Result<T>) -> Result<T> {
        let g = self.viewer.lock().unwrap_or_else(|p| p.into_inner());
        Ok(f(&g.0)?)
    }
}

#[uniffi::export]
impl PdfFile {
    pub fn page_count(&self) -> u32 {
        self.with(|v| Ok(v.page_count())).unwrap_or(0)
    }

    pub fn page_size(&self, page: u32) -> Result<PageBox> {
        self.with(|v| v.page_size(page)).map(|(width, height)| PageBox { width, height })
    }

    /// The page drawn `width` pixels wide (at most 4096 on either side).
    pub fn render(&self, page: u32, width: i32) -> Result<Picture> {
        let img = self.with(|v| v.render(page, width))?;
        Ok(Picture { width: img.width(), height: img.height(), rgba: img.into_raw() })
    }

    pub fn text(&self, page: u32) -> Result<String> {
        self.with(|v| v.text(page))
    }

    /// Every place `words` appear (any case), up to `limit` hits.
    pub fn search(&self, words: String, limit: u32) -> Result<Vec<Hit>> {
        let hits = self.with(|v| v.search(&words, limit as usize))?;
        Ok(hits
            .into_iter()
            .map(|h| Hit { page: h.page, areas: h.areas.into_iter().map(|a| Area { x: a.x, y: a.y, w: a.w, h: a.h }).collect() })
            .collect())
    }
}

// ---------- page work (lopdf) ----------

/// A PDF given to page work: its bytes and the password if it has one.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PdfInput {
    pub bytes: Vec<u8>,
    pub password: Option<String>,
}

/// One page of a result: which input (from 0), which page (from 1) and an extra turn in degrees (0, 90, 180, 270).
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct PagePick {
    pub file: u32,
    pub page: u32,
    pub turn: i64,
}

fn load(input: &PdfInput) -> Result<pp_core::lopdf::Document> {
    Ok(pp_core::open(&input.bytes, input.password.as_deref())?)
}

fn save(mut d: pp_core::lopdf::Document) -> Result<Vec<u8>> {
    Ok(pp_core::save(&mut d)?)
}

/// Pages in a PDF, without the reader engine.
#[uniffi::export]
pub fn count_pages(input: PdfInput) -> Result<u32> {
    Ok(pp_core::pages::page_count(&load(&input)?)?)
}

/// A new PDF made of the picked pages, in the picked order: the page organiser's one operation.
#[uniffi::export]
pub fn arrange(inputs: Vec<PdfInput>, picks: Vec<PagePick>) -> Result<Vec<u8>> {
    let docs = inputs.iter().map(load).collect::<Result<Vec<_>>>()?;
    let picks: Vec<Pick> = picks.iter().map(|p| Pick::turned(p.file as usize, p.page, p.turn)).collect();
    save(pp_core::pages::assemble(&docs, &picks)?)
}

/// All pages of every input, one after another.
#[uniffi::export]
pub fn merge(inputs: Vec<PdfInput>) -> Result<Vec<u8>> {
    let docs = inputs.iter().map(load).collect::<Result<Vec<_>>>()?;
    save(pp_core::pages::merge(&docs)?)
}

/// Only the listed pages, in the listed order.
#[uniffi::export]
pub fn extract_pages(input: PdfInput, pages: Vec<u32>) -> Result<Vec<u8>> {
    save(pp_core::pages::extract(&load(&input)?, &pages)?)
}

/// Everything except the listed pages.
#[uniffi::export]
pub fn delete_pages(input: PdfInput, pages: Vec<u32>) -> Result<Vec<u8>> {
    save(pp_core::pages::delete(&load(&input)?, &pages)?)
}

/// The listed pages turned by `turn` degrees (multiples of 90).
#[uniffi::export]
pub fn rotate_pages(input: PdfInput, pages: Vec<u32>, turn: i64) -> Result<Vec<u8>> {
    save(pp_core::pages::rotate(&load(&input)?, &pages, turn)?)
}

/// One PDF per part; each listed page starts a new part.
#[uniffi::export]
pub fn split(input: PdfInput, starts: Vec<u32>) -> Result<Vec<Vec<u8>>> {
    pp_core::pages::split(&load(&input)?, &starts)?.into_iter().map(save).collect()
}

// ---------- making PDFs ----------

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Paper {
    A4,
    Letter,
    /// Each page the size of its picture.
    Fit,
}

/// One page per picture (JPEG or PNG), in order.
#[uniffi::export]
pub fn pictures_to_pdf(pictures: Vec<Vec<u8>>, paper: Paper) -> Result<Vec<u8>> {
    let size = match paper {
        Paper::A4 => PageSize::A4,
        Paper::Letter => PageSize::Letter,
        Paper::Fit => PageSize::Fit,
    };
    save(pp_core::make::images_to_pdf(&pictures, size)?)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ShrinkLevel {
    /// For reading on screens.
    Small,
    /// Good for printing at home.
    Medium,
    /// A light touch.
    Light,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Shrunk {
    pub bytes: Vec<u8>,
    pub pictures_seen: u32,
    pub pictures_redone: u32,
}

/// A smaller copy: pictures re-encoded (each kept only if it got smaller), streams packed.
#[uniffi::export]
pub fn shrink(input: PdfInput, level: ShrinkLevel) -> Result<Shrunk> {
    let how = match level {
        ShrinkLevel::Small => Shrink::Small,
        ShrinkLevel::Medium => Shrink::Medium,
        ShrinkLevel::Light => Shrink::Light,
    };
    let mut d = load(&input)?;
    let r = pp_core::make::shrink(&mut d, how)?;
    Ok(Shrunk { bytes: save(d)?, pictures_seen: r.pictures_seen, pictures_redone: r.pictures_redone })
}

/// What a form field is (plan M4).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FieldKind {
    Text,
    Check,
    Radio,
    Choice,
    Signature,
    Other,
}

/// One place to fill on a page; a radio group has one per choice, all with the same name.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FormField {
    /// From 1.
    pub page: u32,
    /// The key for [fill_form].
    pub name: String,
    pub kind: FieldKind,
    /// Text and choices: the text. Check and radio: the chosen on-state, or "Off".
    pub value: String,
    /// Check and radio: what [fill_form] takes to tick this one.
    pub on: String,
    pub options: Vec<String>,
    pub read_only: bool,
    pub multiline: bool,
    pub editable: bool,
    pub area: Area,
}

/// Every form field, page by page.
#[uniffi::export]
pub fn form_fields(input: PdfInput) -> Result<Vec<FormField>> {
    let d = load(&input)?;
    Ok(pp_core::forms::fields(&d)?
        .into_iter()
        .map(|f| FormField {
            page: f.page,
            name: f.name,
            kind: match f.kind {
                pp_core::forms::Kind::Text => FieldKind::Text,
                pp_core::forms::Kind::Check => FieldKind::Check,
                pp_core::forms::Kind::Radio => FieldKind::Radio,
                pp_core::forms::Kind::Choice => FieldKind::Choice,
                pp_core::forms::Kind::Signature => FieldKind::Signature,
                pp_core::forms::Kind::Other => FieldKind::Other,
            },
            value: f.value,
            on: f.on,
            options: f.options,
            read_only: f.read_only,
            multiline: f.multiline,
            editable: f.editable,
            area: Area { x: f.area.x, y: f.area.y, w: f.area.w, h: f.area.h },
        })
        .collect())
}

/// A filled copy (by field name). A bad value is an error and nothing is filled.
#[uniffi::export]
pub fn fill_form(input: PdfInput, values: std::collections::HashMap<String, String>) -> Result<Vec<u8>> {
    let mut d = load(&input)?;
    pp_core::forms::fill(&mut d, &values.into_iter().collect())?;
    save(d)
}

/// True when the PDF is digitally signed: a filled copy loses that signature's proof (the app says so first).
#[uniffi::export]
pub fn is_signed(input: PdfInput) -> Result<bool> {
    Ok(pp_core::forms::is_signed(&load(&input)?))
}
