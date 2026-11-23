//! Page operations (plan M2): one primitive, `assemble`, makes a new PDF from picked pages of one or more source PDFs.
//! Merge, extract, split, reorder, delete and rotate are all pick lists over it, so they share one tested path.
use std::collections::{BTreeMap, HashSet};

use lopdf::{dictionary, Dictionary, Document, Object, ObjectId};

use crate::{Error, Result, MAX_PAGES};

/// One page of the result: which source, which page (1-based, as the reader shows it), extra turn in degrees.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Pick {
    pub source: usize,
    pub page: u32,
    pub turn: i64,
}

impl Pick {
    pub fn new(source: usize, page: u32) -> Self {
        Pick { source, page, turn: 0 }
    }
    pub fn turned(source: usize, page: u32, turn: i64) -> Self {
        Pick { source, page, turn }
    }
}

/// A page attribute that may sit on the page or on any parent Pages node (PDF 1.7, 7.7.3.4).
fn inherited(doc: &Document, page_id: ObjectId, key: &[u8]) -> Result<Option<Object>> {
    let mut current = page_id;
    let mut seen = HashSet::new();
    loop {
        if !seen.insert(current) || seen.len() > 64 {
            return Err(Error::Damaged("page tree loops or is too deep".into()));
        }
        let node = doc.get_dictionary(current).map_err(|e| Error::Damaged(e.to_string()))?;
        if let Ok(value) = node.get(key) {
            match doc.dereference(value) {
                Ok((_, Object::Null)) | Err(lopdf::Error::ObjectNotFound(_)) => {}
                Ok(_) => return Ok(Some(value.clone())),
                Err(e) => return Err(Error::Damaged(e.to_string())),
            }
        }
        match node.get(b"Parent") {
            Err(_) | Ok(Object::Null) => return Ok(None),
            Ok(parent) => current = parent.as_reference().map_err(|e| Error::Damaged(e.to_string()))?,
        }
    }
}

/// The page dictionary with its inherited attributes written on it, so it stands alone under a new parent.
fn standalone_page(doc: &Document, page_id: ObjectId) -> Result<Dictionary> {
    let mut page = doc.get_dictionary(page_id).map_err(|e| Error::Damaged(e.to_string()))?.clone();
    let media = inherited(doc, page_id, b"MediaBox")?
        .unwrap_or_else(|| Object::Array(vec![0.into(), 0.into(), 612.into(), 792.into()]));
    if let Some(crop) = inherited(doc, page_id, b"CropBox")? {
        page.set("CropBox", crop);
    }
    page.set("MediaBox", media);
    page.set("Resources", inherited(doc, page_id, b"Resources")?.unwrap_or_else(|| Object::Dictionary(Dictionary::new())));
    let rotate = inherited(doc, page_id, b"Rotate")?.and_then(|o| o.as_i64().ok()).unwrap_or(0);
    page.set("Rotate", rotate);
    Ok(page)
}

/// Rotation kept to the four values PDF allows.
fn norm_turn(deg: i64) -> i64 {
    (((deg / 90) % 4 + 4) % 4) * 90
}

/// Page count of a document, refusing absurd ones before any work is done.
pub fn page_count(doc: &Document) -> Result<u32> {
    let n = doc.get_pages().len();
    if n > MAX_PAGES {
        return Err(Error::TooBig(format!("{n} pages")));
    }
    Ok(n as u32)
}

/// A new PDF with the picked pages in the given order. Sources are not changed. A page picked twice appears twice.
/// Form fields, bookmarks and links between pages are not carried yet (said in the app where it matters).
pub fn assemble(sources: &[Document], picks: &[Pick]) -> Result<Document> {
    if picks.is_empty() {
        return Err(Error::Empty);
    }
    if picks.len() > MAX_PAGES {
        return Err(Error::TooBig(format!("{} pages", picks.len())));
    }
    let mut out = Document::with_version("1.7");
    let pages_id = out.new_object_id();
    let mut next_id = out.max_id + 1;
    // each used source's objects, renumbered so they can't collide, and its page ids by page number
    let mut page_maps: BTreeMap<usize, BTreeMap<u32, ObjectId>> = BTreeMap::new();
    let mut standalone: BTreeMap<ObjectId, Dictionary> = BTreeMap::new();
    for idx in picks.iter().map(|p| p.source).collect::<std::collections::BTreeSet<_>>() {
        let src = sources.get(idx).ok_or(Error::NoSuchPage(idx, 0))?;
        page_count(src)?;
        let mut doc = src.clone();
        // write inherited attributes onto each page before renumbering (the parents are left behind)
        for page_id in doc.get_pages().into_values() {
            let page = standalone_page(&doc, page_id)?;
            doc.objects.insert(page_id, Object::Dictionary(page));
        }
        doc.renumber_objects_with(next_id);
        next_id = doc.max_id + 1;
        let map = doc.get_pages();
        for id in map.values() {
            if let Ok(d) = doc.get_dictionary(*id) {
                standalone.insert(*id, d.clone());
            }
        }
        page_maps.insert(idx, map);
        // every object except the page tree and catalog; unreachable ones are pruned at the end
        for (id, obj) in doc.objects {
            out.objects.insert(id, obj);
        }
    }
    out.max_id = next_id.saturating_sub(1).max(out.max_id);
    let mut kids = Vec::with_capacity(picks.len());
    let mut used: HashSet<ObjectId> = HashSet::new();
    for pick in picks {
        let id = *page_maps
            .get(&pick.source)
            .and_then(|m| m.get(&pick.page))
            .ok_or(Error::NoSuchPage(pick.source, pick.page))?;
        let mut page = standalone.get(&id).cloned().ok_or(Error::NoSuchPage(pick.source, pick.page))?;
        let rotate = page.get(b"Rotate").and_then(|o| o.as_i64()).unwrap_or(0);
        page.set("Rotate", norm_turn(rotate + pick.turn));
        page.set("Parent", pages_id);
        // links and form widgets point at their old page and document: dropped until they are carried properly
        page.remove(b"Annots");
        page.remove(b"StructParents");
        page.remove(b"B");
        let new_id = if used.insert(id) { id } else { out.new_object_id() };
        out.objects.insert(new_id, Object::Dictionary(page));
        kids.push(Object::Reference(new_id));
    }
    let count = kids.len() as i64;
    out.objects.insert(pages_id, Object::Dictionary(dictionary! { "Type" => "Pages", "Kids" => kids, "Count" => count }));
    let catalog_id = out.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    out.trailer.set("Root", catalog_id);
    out.prune_objects();
    out.renumber_objects();
    Ok(out)
}

/// All pages of each source, one after the other.
pub fn merge(sources: &[Document]) -> Result<Document> {
    let mut picks = Vec::new();
    for (i, d) in sources.iter().enumerate() {
        for p in 1..=page_count(d)? {
            picks.push(Pick::new(i, p));
        }
    }
    assemble(sources, &picks)
}

/// The listed pages (1-based) in the listed order.
pub fn extract(doc: &Document, pages: &[u32]) -> Result<Document> {
    assemble(std::slice::from_ref(doc), &pages.iter().map(|p| Pick::new(0, *p)).collect::<Vec<_>>())
}

/// Every page except the listed ones.
pub fn delete(doc: &Document, pages: &[u32]) -> Result<Document> {
    let drop: HashSet<u32> = pages.iter().copied().collect();
    let keep: Vec<u32> = (1..=page_count(doc)?).filter(|p| !drop.contains(p)).collect();
    extract(doc, &keep)
}

/// The listed pages turned by `turn` degrees (a multiple of 90), the rest as they were.
pub fn rotate(doc: &Document, pages: &[u32], turn: i64) -> Result<Document> {
    let set: HashSet<u32> = pages.iter().copied().collect();
    let picks: Vec<Pick> =
        (1..=page_count(doc)?).map(|p| Pick::turned(0, p, if set.contains(&p) { turn } else { 0 })).collect();
    assemble(std::slice::from_ref(doc), &picks)
}

/// Split into parts at the given first pages: `split(doc, &[4, 8])` on 10 pages gives 1-3, 4-7, 8-10.
pub fn split(doc: &Document, starts: &[u32]) -> Result<Vec<Document>> {
    let n = page_count(doc)?;
    let mut cuts: Vec<u32> = starts.iter().copied().filter(|s| *s > 1 && *s <= n).collect();
    cuts.sort_unstable();
    cuts.dedup();
    let mut parts = Vec::new();
    let mut from = 1;
    for c in cuts.into_iter().chain(std::iter::once(n + 1)) {
        parts.push(extract(doc, &(from..c).collect::<Vec<_>>())?);
        from = c;
    }
    Ok(parts)
}
