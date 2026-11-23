//! Papeles core: PDF work that runs on the device (plan: SelfSustainment/research/pdf-app-plan-2026-11-23.md).
//! Every file opened here may be hostile: loads are bounded (stream inflation, page count) and errors are values,
//! never panics across the app boundary.
pub mod forms;
pub mod make;
pub mod pages;
pub mod view;

pub use lopdf;
use lopdf::{Document, LoadOptions};

/// More pages than any real document we expect; a file claiming more is refused before work starts.
pub const MAX_PAGES: usize = 10_000;
/// One stream may inflate to at most this while loading (lopdf's default is 256 MB; phones have less to spare).
pub const MAX_STREAM_BYTES: usize = 64 * 1024 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    /// The file is not a PDF or is broken beyond repair.
    Damaged(String),
    /// The file asks for a password (or the one given is wrong).
    Password,
    /// Too many pages or too much data to handle safely.
    TooBig(String),
    /// A pick names a source or page that isn't there.
    NoSuchPage(usize, u32),
    /// Nothing to make: no pages picked.
    Empty,
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Damaged(m) => write!(f, "damaged PDF: {m}"),
            Error::Password => write!(f, "this PDF needs its password"),
            Error::TooBig(m) => write!(f, "too big to open safely: {m}"),
            Error::NoSuchPage(s, p) => write!(f, "file {} has no page {p}", s + 1),
            Error::Empty => write!(f, "no pages picked"),
        }
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

/// Open a PDF from bytes, with an optional password.
pub fn open(bytes: &[u8], password: Option<&str>) -> Result<Document> {
    let opts = LoadOptions {
        password: password.map(str::to_owned),
        max_decompressed_size: Some(MAX_STREAM_BYTES),
        ..LoadOptions::default()
    };
    let doc = Document::load_mem_with_options(bytes, opts).map_err(|e| {
        let m = e.to_string();
        if m.to_lowercase().contains("password") || m.to_lowercase().contains("decrypt") {
            Error::Password
        } else {
            Error::Damaged(m)
        }
    })?;
    if doc.is_encrypted() && password.is_none() {
        return Err(Error::Password);
    }
    pages::page_count(&doc)?;
    Ok(doc)
}

/// The PDF as bytes, ready to write to a new file.
pub fn save(doc: &mut Document) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    doc.save_to(&mut out).map_err(|e| Error::Damaged(e.to_string()))?;
    Ok(out)
}
