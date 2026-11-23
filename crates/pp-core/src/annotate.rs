//! Marks (plan M4, after sign): typed text, highlighter and pen laid on a page as standard PDF annotations
//! (FreeText, Highlight, Ink), each with its own drawn appearance so every reader shows them the same, and other
//! readers can still move or delete them. Places are given as the reader shows the page: points from the top left
//! of the page as turned on screen (/Rotate), and the marks come out upright as shown.
use lopdf::{dictionary, Dictionary, Document, Object, ObjectId, Stream, StringFormat};

use crate::view::Area;
use crate::{Error, Result};

/// Most marks laid in one go.
pub const MAX_MARKS: usize = 1000;
/// Longest typed text in one mark.
pub const MAX_TEXT_CHARS: usize = 2000;
/// Most pen points in one go (a page of handwriting is a few thousand).
pub const MAX_PEN_POINTS: usize = 50_000;
/// Text sizes allowed, in points.
pub const TEXT_SIZES: std::ops::RangeInclusive<f32> = 4.0..=144.0;
/// The highlighter's yellow and the pen's dark blue (the signature's ink), as PDF colours 0..1.
pub const HIGHLIGHT: [f32; 3] = [1.0, 0.92, 0.23];
pub const PEN: [f32; 3] = [0x1A as f32 / 255.0, 0x23 as f32 / 255.0, 0x7E as f32 / 255.0];

/// One mark, in points as the page shows on screen (top left origin).
#[derive(Debug, Clone)]
pub enum Mark {
    /// `text` (lines split on '\n') with its top left corner at `at`, `size` points tall, black.
    Text { at: (f32, f32), text: String, size: f32 },
    /// Yellow highlighter over each area (one per line of text marked).
    Highlight { areas: Vec<Area> },
    /// Pen lines, `pen` points wide.
    Ink { lines: Vec<Vec<(f32, f32)>>, pen: f32 },
}

fn damaged(e: impl std::fmt::Display) -> Error {
    Error::Damaged(e.to_string())
}

/// A page attribute from the page or a parent (MediaBox, Rotate, Resources are inheritable).
pub(crate) fn inherited(doc: &Document, page: ObjectId, key: &[u8]) -> Option<Object> {
    let mut id = page;
    for _ in 0..32 {
        let d = doc.get_dictionary(id).ok()?;
        if let Ok(v) = d.get(key) {
            return doc.dereference(v).ok().map(|(_, o)| o.clone());
        }
        id = d.get(b"Parent").ok()?.as_reference().ok()?;
    }
    None
}

/// How the page shows on screen: its box and turn, to go from shown points to the page's own.
pub(crate) struct Frame {
    x0: f32,
    y0: f32,
    w0: f32,
    h0: f32,
    rotate: i64,
}

impl Frame {
    pub(crate) fn of(doc: &Document, page: ObjectId) -> Result<Frame> {
        let mb: Vec<f32> = inherited(doc, page, b"MediaBox").and_then(|o| o.as_array().ok().cloned())
            .map(|a| a.iter().filter_map(|o| doc.dereference(o).ok()?.1.as_float().ok()).collect())
            .unwrap_or_else(|| vec![0.0, 0.0, 612.0, 792.0]);
        if mb.len() != 4 || mb.iter().any(|v| !v.is_finite()) {
            return Err(Error::Damaged("page has no size".into()));
        }
        let rotate = (inherited(doc, page, b"Rotate").and_then(|o| o.as_i64().ok()).unwrap_or(0).rem_euclid(360) / 90) * 90;
        Ok(Frame { x0: mb[0].min(mb[2]), y0: mb[1].min(mb[3]), w0: (mb[2] - mb[0]).abs(), h0: (mb[3] - mb[1]).abs(), rotate })
    }

    /// A shown point (top left origin, turned) in the page's own space.
    pub(crate) fn to_user(&self, dx: f32, dy: f32) -> (f32, f32) {
        let (x0, y0, w0, h0) = (self.x0, self.y0, self.w0, self.h0);
        match self.rotate {
            90 => (x0 + dy, y0 + dx),
            180 => (x0 + w0 - dx, y0 + dy),
            270 => (x0 + w0 - dy, y0 + h0 - dx),
            _ => (x0 + dx, y0 + h0 - dy),
        }
    }

    /// The screen's right and up, as directions in the page's own space.
    pub(crate) fn right_up(&self) -> ((f32, f32), (f32, f32)) {
        match self.rotate {
            90 => ((0.0, 1.0), (-1.0, 0.0)),
            180 => ((-1.0, 0.0), (0.0, -1.0)),
            270 => ((0.0, -1.0), (1.0, 0.0)),
            _ => ((1.0, 0.0), (0.0, 1.0)),
        }
    }

    /// The page's own rectangle [x0 y0 x1 y1] covering a shown area.
    fn rect(&self, a: &Area) -> [f32; 4] {
        let c = [self.to_user(a.x, a.y), self.to_user(a.x + a.w, a.y), self.to_user(a.x, a.y + a.h), self.to_user(a.x + a.w, a.y + a.h)];
        let (mut r0, mut s0, mut r1, mut s1) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
        for (x, y) in c {
            r0 = r0.min(x);
            s0 = s0.min(y);
            r1 = r1.max(x);
            s1 = s1.max(y);
        }
        [r0, s0, r1, s1]
    }
}

fn nums(v: &[f32]) -> Object {
    Object::Array(v.iter().map(|&f| Object::Real(f)).collect())
}

/// An appearance drawn upright as shown: the form's own space is the area's, x right and y up from its bottom left,
/// turned with the page so it lands on the annotation's rectangle unscaled.
fn appearance(doc: &mut Document, f: &Frame, area: &Area, ops: String, resources: Dictionary) -> ObjectId {
    let ((rx, ry), (ux, uy)) = f.right_up();
    let form = Stream::new(dictionary! {
        "Type" => "XObject", "Subtype" => "Form", "BBox" => nums(&[0.0, 0.0, area.w, area.h]),
        "Matrix" => nums(&[rx, ry, ux, uy, 0.0, 0.0]), "Resources" => resources,
    }, ops.into_bytes());
    let mut form = form;
    let _ = form.compress();
    doc.add_object(form)
}

/// Helvetica's widths (thousandths of the size) for the printable ASCII letters, from its standard metrics.
const HELV: [u16; 95] = [
    278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556,
    556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833,
    722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556,
    556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334,
    260, 334, 584,
];

/// A letter in the built-in font's encoding (WinAnsi), '?' when it has none.
fn win_ansi(c: char) -> u8 {
    match c {
        ' '..='~' => c as u8,
        '\u{A0}'..='\u{FF}' => c as u32 as u8,
        '€' => 0x80, '‚' => 0x82, '„' => 0x84, '…' => 0x85, '‘' => 0x91, '’' => 0x92, '“' => 0x93, '”' => 0x94,
        '•' => 0x95, '–' => 0x96, '—' => 0x97, '™' => 0x99,
        _ => b'?',
    }
}

/// A letter's width in thousandths: ASCII from the metrics, accented letters as their plain letter, others wide.
fn width(b: u8) -> f32 {
    let plain = |s: &str, of: &str| s.find(b as char).map(|i| of.as_bytes()[i / 2]);
    let w = match b {
        32..=126 => HELV[(b - 32) as usize],
        _ => match plain("ÀÁÂÃÄÅàáâãäåÈÉÊËèéêëÌÍÎÏìíîïÒÓÔÕÖòóôõöÙÚÛÜùúûüÑñÇç", "AAAAAAaaaaaaEEEEeeeeIIIIiiiiOOOOOoooooUUUUuuuuNnCc") {
            Some(p) => HELV[(p - 32) as usize],
            None => 1000,
        },
    };
    w as f32
}

/// Typed text as the font's bytes, line by line, and the box it needs at `size` (points wide, tall).
pub fn text_box(text: &str, size: f32) -> (Vec<Vec<u8>>, f32, f32) {
    let lines: Vec<Vec<u8>> = text.split('\n').map(|l| l.trim_end_matches('\r').chars().map(win_ansi).collect()).collect();
    let pad = size * 0.25;
    let widest = lines.iter().map(|l| l.iter().map(|&b| width(b)).sum::<f32>()).fold(0.0, f32::max);
    (lines.clone(), widest * size / 1000.0 + 2.0 * pad, lines.len() as f32 * size * 1.2 + 2.0 * pad)
}

/// What a person typed as a PDF text string (plain bytes when ASCII, else UTF-16 with its mark).
fn text_string(s: &str) -> Object {
    if s.is_ascii() {
        Object::String(s.as_bytes().to_vec(), StringFormat::Literal)
    } else {
        let mut b = vec![0xFE, 0xFF];
        for u in s.encode_utf16() {
            b.extend(u.to_be_bytes());
        }
        Object::String(b, StringFormat::Hexadecimal)
    }
}

fn finite(v: &[f32]) -> bool {
    v.iter().all(|f| f.is_finite())
}

/// Lays `marks` on `page` (from 1) as annotations.
pub fn annotate(doc: &mut Document, page: u32, marks: &[Mark]) -> Result<()> {
    let page_id = *doc.get_pages().get(&page).ok_or(Error::NoSuchPage(0, page))?;
    if marks.is_empty() {
        return Err(Error::Empty);
    }
    if marks.len() > MAX_MARKS {
        return Err(Error::TooBig(format!("{} marks", marks.len())));
    }
    let points: usize = marks.iter().map(|m| match m { Mark::Ink { lines, .. } => lines.iter().map(Vec::len).sum(), _ => 0 }).sum();
    if points > MAX_PEN_POINTS {
        return Err(Error::TooBig(format!("{points} pen points")));
    }
    let f = Frame::of(doc, page_id)?;
    let mut made = Vec::new();
    for m in marks {
        let (sub, area, ops, res, mut extra): (&str, Area, String, Dictionary, Dictionary) = match m {
            Mark::Text { at, text, size } => {
                let text: String = text.chars().take(MAX_TEXT_CHARS).collect();
                if text.trim().is_empty() || !TEXT_SIZES.contains(size) || !finite(&[at.0, at.1]) {
                    return Err(Error::Empty);
                }
                let (lines, w, h) = text_box(&text, *size);
                let pad = size * 0.25;
                let mut ops = format!("BT /Helv {size:.2} Tf 0 g {:.2} TL {pad:.2} {:.2} Td\n", size * 1.2, h - pad - size * 0.93);
                for (i, l) in lines.iter().enumerate() {
                    let hex: String = l.iter().map(|b| format!("{b:02X}")).collect();
                    ops.push_str(&format!("{}<{hex}> Tj\n", if i > 0 { "T* " } else { "" }));
                }
                ops.push_str("ET\n");
                let helv = dictionary! { "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica", "Encoding" => "WinAnsiEncoding" };
                let res = dictionary! { "Font" => dictionary! { "Helv" => helv } };
                let extra = dictionary! {
                    "Contents" => text_string(&text),
                    "DA" => Object::String(format!("/Helv {size:.2} Tf 0 g").into_bytes(), StringFormat::Literal),
                    "Q" => 0,
                };
                ("FreeText", Area { x: at.0, y: at.1, w, h }, ops, res, extra)
            }
            Mark::Highlight { areas } => {
                let areas: Vec<&Area> = areas.iter().filter(|a| finite(&[a.x, a.y, a.w, a.h]) && a.w > 0.1 && a.h > 0.1).collect();
                if areas.is_empty() {
                    return Err(Error::Empty);
                }
                let (x0, y0) = areas.iter().fold((f32::MAX, f32::MAX), |(x, y), a| (x.min(a.x), y.min(a.y)));
                let (x1, y1) = areas.iter().fold((f32::MIN, f32::MIN), |(x, y), a| (x.max(a.x + a.w), y.max(a.y + a.h)));
                let all = Area { x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
                let [r, g, b] = HIGHLIGHT;
                let mut ops = format!("/Mul gs {r} {g} {b} rg\n");
                let mut quads = Vec::new();
                for a in &areas {
                    ops.push_str(&format!("{:.2} {:.2} {:.2} {:.2} re f\n", a.x - x0, y1 - a.y - a.h, a.w, a.h));
                    // corners as the text runs: top left, top right, bottom left, bottom right
                    for (px, py) in [(a.x, a.y), (a.x + a.w, a.y), (a.x, a.y + a.h), (a.x + a.w, a.y + a.h)] {
                        let (ux, uy) = f.to_user(px, py);
                        quads.extend([ux, uy]);
                    }
                }
                let res = dictionary! { "ExtGState" => dictionary! { "Mul" => dictionary! { "Type" => "ExtGState", "BM" => "Multiply" } } };
                ("Highlight", all, ops, res, dictionary! { "QuadPoints" => nums(&quads) })
            }
            Mark::Ink { lines, pen } => {
                let lines: Vec<Vec<(f32, f32)>> = lines.iter()
                    .map(|l| l.iter().copied().filter(|(x, y)| x.is_finite() && y.is_finite()).collect::<Vec<_>>())
                    .filter(|l| !l.is_empty()).collect();
                if lines.is_empty() || !(pen.is_finite() && (0.1..=100.0).contains(pen)) {
                    return Err(Error::Empty);
                }
                let pts = lines.iter().flatten();
                let (x0, y0) = pts.clone().fold((f32::MAX, f32::MAX), |(x, y), p| (x.min(p.0), y.min(p.1)));
                let (x1, y1) = pts.fold((f32::MIN, f32::MIN), |(x, y), p| (x.max(p.0), y.max(p.1)));
                let half = pen / 2.0 + 1.0;
                let all = Area { x: x0 - half, y: y0 - half, w: x1 - x0 + 2.0 * half, h: y1 - y0 + 2.0 * half };
                let [r, g, b] = PEN;
                let mut ops = format!("{pen:.2} w 1 J 1 j {r:.4} {g:.4} {b:.4} RG\n");
                let mut list = Vec::new();
                for l in &lines {
                    let at = |p: &(f32, f32)| (p.0 - all.x, all.y + all.h - p.1);
                    let (sx, sy) = at(&l[0]);
                    ops.push_str(&format!("{sx:.2} {sy:.2} m\n"));
                    // a single touch still leaves a dot
                    for p in if l.len() == 1 { &l[..] } else { &l[1..] } {
                        let (x, y) = at(p);
                        ops.push_str(&format!("{x:.2} {y:.2} l\n"));
                    }
                    ops.push_str("S\n");
                    let user: Vec<f32> = l.iter().flat_map(|p| { let (x, y) = f.to_user(p.0, p.1); [x, y] }).collect();
                    list.push(nums(&user));
                }
                let extra = dictionary! { "InkList" => Object::Array(list), "BS" => dictionary! { "W" => Object::Real(*pen) } };
                ("Ink", all, ops, Dictionary::new(), extra)
            }
        };
        let ap = appearance(doc, &f, &area, ops, res);
        let colour = match m { Mark::Text { .. } => vec![], Mark::Highlight { .. } => HIGHLIGHT.to_vec(), Mark::Ink { .. } => PEN.to_vec() };
        extra.extend(&dictionary! {
            "Type" => "Annot", "Subtype" => sub, "Rect" => nums(&f.rect(&area)), "P" => page_id, "F" => 4,
            "NM" => Object::String(format!("pp-{}-{}", page, made.len() + 1).into_bytes(), StringFormat::Literal),
            "C" => nums(&colour), "AP" => dictionary! { "N" => ap },
        });
        made.push(Object::Reference(doc.add_object(extra)));
    }
    // onto the page's list of annotations, wherever it is kept
    let page_dict = doc.get_dictionary(page_id).map_err(damaged)?;
    match page_dict.get(b"Annots") {
        Ok(Object::Reference(r)) if matches!(doc.get_object(*r), Ok(Object::Array(_))) => {
            let r = *r;
            if let Ok(Object::Array(a)) = doc.get_object_mut(r) {
                a.extend(made);
            }
        }
        Ok(Object::Array(a)) => {
            let mut a = a.clone();
            a.extend(made);
            doc.get_dictionary_mut(page_id).map_err(damaged)?.set("Annots", Object::Array(a));
        }
        _ => {
            doc.get_dictionary_mut(page_id).map_err(damaged)?.set("Annots", Object::Array(made));
        }
    }
    Ok(())
}
