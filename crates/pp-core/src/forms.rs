//! Fill (plan M4): the form fields of a PDF, listed per page and filled in, with lopdf.
//!
//! Why lopdf and not PDFium's setters (pdfium-render 0.9.6, read 2026-11-23): its checkbox setter writes "/Yes"
//! whatever the box's own on-state is called, choice fields have no setter, and none of them redraws the field's
//! appearance. Here a button gets its own on-state name, a changed text or choice field loses its old appearance
//! and the form asks readers to draw it again (/NeedAppearances), which PDFium does when the app shows it.
//! Signature fields are listed but never written: a filled copy is a full rewrite, which a digital signature's proof
//! does not survive, so the app says so before saving ([is_signed]).
use std::collections::{BTreeMap, HashSet};

use lopdf::{Dictionary, Document, Object, ObjectId};

use crate::view::Area;
use crate::{Error, Result};

/// Most fields a form may have before it is refused as hostile.
pub const MAX_FIELDS: usize = 5_000;
/// Longest value written into one field.
pub const MAX_VALUE: usize = 10_000;

const FF_READ_ONLY: i64 = 1;
const FF_MULTILINE: i64 = 1 << 12;
const FF_RADIO: i64 = 1 << 15;
const FF_PUSH: i64 = 1 << 16;
const FF_COMBO: i64 = 1 << 17;
const FF_EDIT: i64 = 1 << 18;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Text,
    Check,
    Radio,
    /// A list or drop-down; `editable` ones take any text.
    Choice,
    Signature,
    /// Push buttons and anything else: shown, not filled.
    Other,
}

/// One place to fill on a page (a widget). A radio group has one per choice, all with the same `name`.
#[derive(Clone, Debug, PartialEq)]
pub struct Field {
    /// 1-based page number.
    pub page: u32,
    /// The full field name ("parent.child"), the key for [fill].
    pub name: String,
    /// What the form calls it for people (/TU, the "tooltip"), or empty: a better label than the name.
    pub label: String,
    pub kind: Kind,
    /// Text and choices: the text. Check and radio: the chosen on-state name, or "Off".
    pub value: String,
    /// Check and radio: the on-state name of this widget (what [fill] takes to tick it).
    pub on: String,
    /// Choices: the offered values. Radio: empty (each widget is one choice).
    pub options: Vec<String>,
    pub read_only: bool,
    pub multiline: bool,
    pub editable: bool,
    /// Where it is, in page points from the top left.
    pub area: Area,
}

fn deref<'a>(doc: &'a Document, o: &'a Object) -> Option<&'a Object> {
    doc.dereference(o).ok().map(|(_, o)| o)
}

fn dict<'a>(doc: &'a Document, o: &'a Object) -> Option<&'a Dictionary> {
    deref(doc, o).and_then(|o| o.as_dict().ok())
}

/// A PDF text string (UTF-16BE with its mark, or PDFDocEncoding read as Latin-1).
fn text_of(o: &Object) -> String {
    let b = match o {
        Object::String(b, _) => b.as_slice(),
        Object::Name(b) => b.as_slice(),
        _ => return String::new(),
    };
    if b.len() >= 2 && b[0] == 0xFE && b[1] == 0xFF {
        let u: Vec<u16> = b[2..].chunks_exact(2).map(|c| u16::from_be_bytes([c[0], c[1]])).collect();
        String::from_utf16_lossy(&u)
    } else if b.len() >= 3 && b[..3] == [0xEF, 0xBB, 0xBF] {
        String::from_utf8_lossy(&b[3..]).into_owned()
    } else {
        b.iter().map(|&c| c as char).collect()
    }
}

/// A text string as PDF writes it: plain when it is ASCII, UTF-16BE with its mark otherwise.
fn text_obj(s: &str) -> Object {
    if s.is_ascii() {
        Object::string_literal(s)
    } else {
        let mut b = vec![0xFE, 0xFF];
        for u in s.encode_utf16() {
            b.extend_from_slice(&u.to_be_bytes());
        }
        Object::String(b, lopdf::StringFormat::Hexadecimal)
    }
}

/// A field attribute from the field or any parent (PDF 1.7, 12.7.3.1: FT, Ff, V, DA... are inheritable).
fn inherited<'a>(doc: &'a Document, start: &'a Dictionary, key: &[u8]) -> Option<&'a Object> {
    let mut d = start;
    for _ in 0..32 {
        if let Ok(v) = d.get(key) {
            return deref(doc, v);
        }
        d = dict(doc, d.get(b"Parent").ok()?)?;
    }
    None
}

/// The full name: every /T from the top down, joined with dots.
fn full_name(doc: &Document, start: &Dictionary) -> String {
    let mut parts = vec![];
    let mut d = Some(start);
    for _ in 0..32 {
        let Some(cur) = d else { break };
        if let Some(t) = cur.get(b"T").ok().and_then(|t| deref(doc, t)) {
            parts.push(text_of(t));
        }
        d = cur.get(b"Parent").ok().and_then(|p| dict(doc, p));
    }
    parts.reverse();
    parts.join(".")
}

/// The on-state of a button widget: its normal appearance's name that isn't Off.
fn on_state(doc: &Document, w: &Dictionary) -> String {
    w.get(b"AP").ok().and_then(|a| dict(doc, a)).and_then(|a| a.get(b"N").ok()).and_then(|n| dict(doc, n))
        .and_then(|n| n.iter().map(|(k, _)| String::from_utf8_lossy(k).into_owned()).find(|k| k != "Off"))
        .unwrap_or_default()
}

fn ff(doc: &Document, d: &Dictionary) -> i64 {
    inherited(doc, d, b"Ff").and_then(|o| o.as_i64().ok()).unwrap_or(0)
}

fn kind_of(doc: &Document, d: &Dictionary) -> Kind {
    let flags = ff(doc, d);
    match inherited(doc, d, b"FT").and_then(|o| o.as_name().ok()) {
        Some(b"Tx") => Kind::Text,
        Some(b"Ch") => Kind::Choice,
        Some(b"Sig") => Kind::Signature,
        Some(b"Btn") if flags & FF_PUSH != 0 => Kind::Other,
        Some(b"Btn") if flags & FF_RADIO != 0 => Kind::Radio,
        Some(b"Btn") => Kind::Check,
        _ => Kind::Other,
    }
}

fn options(doc: &Document, d: &Dictionary) -> Vec<String> {
    let Some(Ok(arr)) = inherited(doc, d, b"Opt").map(|o| o.as_array()) else { return vec![] };
    arr.iter()
        .filter_map(|o| match deref(doc, o)? {
            // [export, shown]: the export value is what /V holds
            Object::Array(pair) => pair.first().and_then(|e| deref(doc, e)).map(text_of),
            other => Some(text_of(other)),
        })
        .collect()
}

fn page_height(doc: &Document, page: ObjectId) -> f32 {
    let mut d = doc.get_dictionary(page).ok();
    for _ in 0..32 {
        let Some(cur) = d else { break };
        if let Some(mb) = cur.get(b"MediaBox").ok().and_then(|m| deref(doc, m)).and_then(|m| m.as_array().ok()) {
            let v: Vec<f32> = mb.iter().filter_map(|o| deref(doc, o)?.as_float().ok()).collect();
            if v.len() == 4 {
                return (v[3] - v[1]).abs();
            }
        }
        d = cur.get(b"Parent").ok().and_then(|p| dict(doc, p));
    }
    792.0
}

/// Every fillable place, page by page, in the order the page lists them.
pub fn fields(doc: &Document) -> Result<Vec<Field>> {
    let mut out = vec![];
    for (n, page_id) in doc.get_pages() {
        let page = doc.get_dictionary(page_id).map_err(|e| Error::Damaged(e.to_string()))?;
        let Some(annots) = page.get(b"Annots").ok().and_then(|a| deref(doc, a)).and_then(|a| a.as_array().ok()) else { continue };
        let h = page_height(doc, page_id);
        for a in annots {
            let Some(w) = dict(doc, a) else { continue };
            if w.get(b"Subtype").ok().and_then(|s| s.as_name().ok()) != Some(b"Widget") {
                continue;
            }
            if out.len() >= MAX_FIELDS {
                return Err(Error::TooBig(format!("more than {MAX_FIELDS} form fields")));
            }
            let kind = kind_of(doc, w);
            let flags = ff(doc, w);
            let v = inherited(doc, w, b"V");
            let value = match kind {
                Kind::Check | Kind::Radio => v.and_then(|v| v.as_name().ok()).map(|b| String::from_utf8_lossy(b).into_owned()).unwrap_or_else(|| "Off".into()),
                _ => v.map(text_of).unwrap_or_default(),
            };
            let r: Vec<f32> = w.get(b"Rect").ok().and_then(|r| deref(doc, r)).and_then(|r| r.as_array().ok())
                .map(|r| r.iter().filter_map(|o| deref(doc, o)?.as_float().ok()).collect()).unwrap_or_default();
            let area = if r.len() == 4 {
                let (x0, x1, y0, y1) = (r[0].min(r[2]), r[0].max(r[2]), r[1].min(r[3]), r[1].max(r[3]));
                Area { x: x0, y: h - y1, w: x1 - x0, h: y1 - y0 }
            } else {
                Area { x: 0.0, y: 0.0, w: 0.0, h: 0.0 }
            };
            out.push(Field {
                page: n,
                name: full_name(doc, w),
                label: inherited(doc, w, b"TU").map(text_of).map(|t| t.trim().to_string()).unwrap_or_default(),
                kind,
                value,
                on: if matches!(kind, Kind::Check | Kind::Radio) { on_state(doc, w) } else { String::new() },
                options: if kind == Kind::Choice { options(doc, w) } else { vec![] },
                read_only: flags & FF_READ_ONLY != 0,
                multiline: kind == Kind::Text && flags & FF_MULTILINE != 0,
                editable: kind == Kind::Text || (kind == Kind::Choice && flags & FF_COMBO != 0 && flags & FF_EDIT != 0),
                area,
            });
        }
    }
    Ok(out)
}

/// True when the PDF carries a digital signature (a signature field with a value): a filled copy loses its proof.
pub fn is_signed(doc: &Document) -> bool {
    terminal_fields(doc).iter().any(|(_, id)| {
        doc.get_dictionary(*id).map(|d| kind_of(doc, d) == Kind::Signature && d.get(b"V").is_ok()).unwrap_or(false)
    })
}

/// Every terminal field (one that holds a value) by full name. Widgets that are the field themselves count once.
fn terminal_fields(doc: &Document) -> Vec<(String, ObjectId)> {
    let mut out = vec![];
    let mut seen = HashSet::new();
    let mut stack: Vec<ObjectId> = vec![];
    let root = doc.trailer.get(b"Root").ok().and_then(|r| dict(doc, r));
    let form = root.and_then(|r| r.get(b"AcroForm").ok()).and_then(|f| dict(doc, f));
    if let Some(Ok(arr)) = form.and_then(|f| f.get(b"Fields").ok()).and_then(|a| deref(doc, a)).map(|a| a.as_array()) {
        stack.extend(arr.iter().filter_map(|o| o.as_reference().ok()));
    }
    while let Some(id) = stack.pop() {
        if !seen.insert(id) || seen.len() > MAX_FIELDS * 4 {
            continue;
        }
        let Ok(d) = doc.get_dictionary(id) else { continue };
        // kids that have names are fields; kids without /T are only its widgets
        let named_kids: Vec<ObjectId> = d.get(b"Kids").ok().and_then(|k| deref(doc, k)).and_then(|k| k.as_array().ok())
            .map(|k| k.iter().filter_map(|o| o.as_reference().ok())
                .filter(|kid| doc.get_dictionary(*kid).map(|kd| kd.has(b"T")).unwrap_or(false)).collect())
            .unwrap_or_default();
        if named_kids.is_empty() {
            out.push((full_name(doc, d), id));
        } else {
            stack.extend(named_kids);
        }
    }
    out
}

/// The widgets of a terminal field: its unnamed kids, or the field itself when it is its own widget.
fn widgets(doc: &Document, field: ObjectId) -> Vec<ObjectId> {
    let kids: Vec<ObjectId> = doc.get_dictionary(field).ok().and_then(|d| d.get(b"Kids").ok()).and_then(|k| deref(doc, k))
        .and_then(|k| k.as_array().ok()).map(|k| k.iter().filter_map(|o| o.as_reference().ok()).collect()).unwrap_or_default();
    if kids.is_empty() { vec![field] } else { kids }
}

/// Fills the named fields. Check / radio take an on-state name (or "Off"); text and choices take text. A name that
/// isn't there, a read-only or signature field, or a choice that isn't offered is an error (nothing is changed).
pub fn fill(doc: &mut Document, values: &BTreeMap<String, String>) -> Result<()> {
    let fields: BTreeMap<String, ObjectId> = terminal_fields(doc).into_iter().collect();
    // check everything first, so a bad value changes nothing
    let mut plan = vec![];
    for (name, value) in values {
        let id = *fields.get(name).ok_or_else(|| Error::Damaged(format!("no form field named {name}")))?;
        let d = doc.get_dictionary(id).map_err(|e| Error::Damaged(e.to_string()))?;
        let kind = kind_of(doc, d);
        let flags = ff(doc, d);
        if flags & FF_READ_ONLY != 0 || matches!(kind, Kind::Signature | Kind::Other) {
            return Err(Error::Damaged(format!("the field {name} can't be filled here")));
        }
        if value.chars().count() > MAX_VALUE {
            return Err(Error::TooBig(format!("{} characters in {name}", value.chars().count())));
        }
        match kind {
            Kind::Check | Kind::Radio => {
                let ons: Vec<String> = widgets(doc, id).iter().filter_map(|w| doc.get_dictionary(*w).ok()).map(|w| on_state(doc, w)).collect();
                if value != "Off" && !ons.iter().any(|o| o == value) {
                    return Err(Error::Damaged(format!("{name} has no choice {value}")));
                }
            }
            Kind::Choice => {
                let editable = flags & FF_COMBO != 0 && flags & FF_EDIT != 0;
                if !editable && !value.is_empty() && !options(doc, d).iter().any(|o| o == value) {
                    return Err(Error::Damaged(format!("{name} has no choice {value}")));
                }
            }
            _ => {}
        }
        let max_len = inherited(doc, d, b"MaxLen").and_then(|o| o.as_i64().ok()).filter(|m| *m > 0);
        let value = match (kind, max_len) {
            (Kind::Text, Some(m)) => value.chars().take(m as usize).collect(),
            _ => value.clone(),
        };
        plan.push((id, kind, value));
    }
    for (id, kind, value) in plan {
        match kind {
            Kind::Check | Kind::Radio => {
                let state = value.as_bytes().to_vec();
                if let Ok(d) = doc.get_dictionary_mut(id) {
                    d.set("V", Object::Name(state.clone()));
                }
                for w in widgets(doc, id) {
                    let on = doc.get_dictionary(w).map(|wd| on_state(doc, wd)).unwrap_or_default();
                    let as_ = if !on.is_empty() && on.as_bytes() == state.as_slice() { state.clone() } else { b"Off".to_vec() };
                    if let Ok(wd) = doc.get_dictionary_mut(w) {
                        wd.set("AS", Object::Name(as_));
                    }
                }
            }
            _ => {
                if let Ok(d) = doc.get_dictionary_mut(id) {
                    d.set("V", text_obj(&value));
                }
                // the old picture of the field would show the old text: readers draw it again from /V
                for w in widgets(doc, id) {
                    if let Ok(wd) = doc.get_dictionary_mut(w) {
                        wd.remove(b"AP");
                    }
                }
            }
        }
    }
    // ask readers to draw changed fields again
    let form_ref = doc.trailer.get(b"Root").ok().and_then(|r| r.as_reference().ok())
        .and_then(|r| doc.get_dictionary(r).ok()).and_then(|c| c.get(b"AcroForm").ok()).cloned();
    match form_ref {
        Some(Object::Reference(id)) => {
            if let Ok(f) = doc.get_dictionary_mut(id) {
                f.set("NeedAppearances", true);
            }
        }
        Some(Object::Dictionary(_)) => {
            let root = doc.trailer.get(b"Root").and_then(|r| r.as_reference()).map_err(|e| Error::Damaged(e.to_string()))?;
            if let Ok(Object::Dictionary(f)) = doc.get_dictionary_mut(root).and_then(|c| c.get_mut(b"AcroForm")) {
                f.set("NeedAppearances", true);
            }
        }
        _ => return Err(Error::Damaged("this PDF has no form".into())),
    }
    Ok(())
}
