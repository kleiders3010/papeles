// Forms (plan M4): a form we make ourselves with one field of each kind is listed, filled, saved and read back by
// lopdf and by PDFium (which draws what readers will show); bad fills change nothing; a signed PDF is recognised.
use std::collections::BTreeMap;
use std::path::PathBuf;

use lopdf::{dictionary, Document, Object, Stream};
use pp_core::forms::{self, Kind};
use pp_core::view::Engine;

/// PDFium binds once per process: every check shares one engine (tests run in parallel threads).
fn engine() -> &'static Engine {
    static E: std::sync::OnceLock<Engine> = std::sync::OnceLock::new();
    E.get_or_init(|| {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../vendor/pdfium/win-x64/bin");
        Engine::load(&dir).expect("run scripts/fetch_pdfium.sh first")
    })
}

/// A one-page form: name (text), news (checkbox whose on-state is "Si"), size (radio s/m/l), country (drop-down),
/// a read-only text, and optionally a signed signature field.
fn form(signed: bool) -> Vec<u8> {
    let mut d = Document::with_version("1.7");
    let pages_id = d.new_object_id();
    let page_id = d.new_object_id();
    let font = d.add_object(dictionary! { "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica" });
    let ap = |d: &mut Document| d.add_object(Stream::new(dictionary! { "Type" => "XObject", "Subtype" => "Form", "BBox" => vec![0.into(), 0.into(), 12.into(), 12.into()] }, b"0 g 2 2 8 8 re f".to_vec()));
    let off = |d: &mut Document| d.add_object(Stream::new(dictionary! { "Type" => "XObject", "Subtype" => "Form", "BBox" => vec![0.into(), 0.into(), 12.into(), 12.into()] }, vec![]));
    let rect = |x: i64, y: i64, w: i64, h: i64| -> Object { vec![x.into(), y.into(), (x + w).into(), (y + h).into()].into() };
    let mut annots = vec![];
    let mut fields = vec![];
    let name = d.add_object(dictionary! { "Type" => "Annot", "Subtype" => "Widget", "FT" => "Tx", "T" => Object::string_literal("name"),
        "V" => Object::string_literal("old"), "Rect" => rect(72, 700, 200, 20), "P" => page_id, "DA" => Object::string_literal("/Helv 12 Tf 0 g") });
    let (on1, off1) = (ap(&mut d), off(&mut d));
    let news = d.add_object(dictionary! { "Type" => "Annot", "Subtype" => "Widget", "FT" => "Btn", "T" => Object::string_literal("news"),
        "V" => "Off", "AS" => "Off", "Rect" => rect(72, 660, 12, 12), "P" => page_id, "AP" => dictionary! { "N" => dictionary! { "Si" => on1, "Off" => off1 } } });
    let size = d.new_object_id();
    let mut kids = vec![];
    for (i, s) in ["s", "m", "l"].iter().enumerate() {
        let (on, of) = (ap(&mut d), off(&mut d));
        let k = d.add_object(dictionary! { "Type" => "Annot", "Subtype" => "Widget", "Parent" => size, "AS" => "Off",
            "Rect" => rect(72 + 30 * i as i64, 620, 12, 12), "P" => page_id, "AP" => dictionary! { "N" => dictionary! { s.as_bytes().to_vec() => on, "Off" => of } } });
        kids.push(Object::Reference(k));
        annots.push(Object::Reference(k));
    }
    d.objects.insert(size, Object::Dictionary(dictionary! { "FT" => "Btn", "Ff" => 1i64 << 15, "T" => Object::string_literal("size"), "V" => "Off", "Kids" => kids }));
    let country = d.add_object(dictionary! { "Type" => "Annot", "Subtype" => "Widget", "FT" => "Ch", "Ff" => 1i64 << 17, "T" => Object::string_literal("country"),
        "Opt" => vec![Object::string_literal("AR"), Object::string_literal("ES"), Object::string_literal("MX")], "V" => Object::string_literal("AR"),
        "Rect" => rect(72, 580, 100, 20), "P" => page_id, "DA" => Object::string_literal("/Helv 12 Tf 0 g") });
    let locked = d.add_object(dictionary! { "Type" => "Annot", "Subtype" => "Widget", "FT" => "Tx", "Ff" => 1i64, "T" => Object::string_literal("locked"),
        "V" => Object::string_literal("fixed"), "Rect" => rect(72, 540, 100, 20), "P" => page_id, "DA" => Object::string_literal("/Helv 12 Tf 0 g") });
    for id in [name, news, country, locked] {
        annots.push(Object::Reference(id));
    }
    fields.extend([name, news, size, country, locked].map(Object::Reference));
    if signed {
        let sig = d.add_object(dictionary! { "Type" => "Sig", "Filter" => "Adobe.PPKLite", "Contents" => Object::String(vec![0; 16], lopdf::StringFormat::Hexadecimal) });
        let w = d.add_object(dictionary! { "Type" => "Annot", "Subtype" => "Widget", "FT" => "Sig", "T" => Object::string_literal("signature"),
            "V" => sig, "Rect" => rect(72, 500, 100, 30), "P" => page_id });
        annots.push(Object::Reference(w));
        fields.push(Object::Reference(w));
    }
    let content = d.add_object(Stream::new(dictionary! {}, b"BT /F1 14 Tf 72 740 Td (A small form) Tj ET".to_vec()));
    d.objects.insert(page_id, Object::Dictionary(dictionary! { "Type" => "Page", "Parent" => pages_id, "MediaBox" => vec![0.into(), 0.into(), 595.into(), 842.into()],
        "Contents" => content, "Resources" => dictionary! { "Font" => dictionary! { "F1" => font } }, "Annots" => annots }));
    d.objects.insert(pages_id, Object::Dictionary(dictionary! { "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1 }));
    let helv = d.add_object(dictionary! { "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica" });
    let acro = d.add_object(dictionary! { "Fields" => fields, "DA" => Object::string_literal("/Helv 12 Tf 0 g"), "DR" => dictionary! { "Font" => dictionary! { "Helv" => helv } } });
    let cat = d.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id, "AcroForm" => acro });
    d.trailer.set("Root", cat);
    pp_core::save(&mut d).unwrap()
}

fn by_name<'a>(f: &'a [forms::Field], n: &str) -> Vec<&'a forms::Field> {
    f.iter().filter(|x| x.name == n).collect()
}

#[test]
fn every_kind_is_listed_where_it_is() {
    let doc = pp_core::open(&form(false), None).unwrap();
    let f = forms::fields(&doc).unwrap();
    assert_eq!(f.len(), 7, "{f:#?}"); // pinned: the form above has 7 widgets (3 of them radio)
    let name = by_name(&f, "name")[0];
    assert_eq!((name.kind, name.value.as_str(), name.page), (Kind::Text, "old", 1));
    // 842 high, the field's bottom at 700 and 20 tall: its top is 122 from the page's top
    assert!((name.area.y - 122.0).abs() < 0.5 && (name.area.w - 200.0).abs() < 0.5, "{:?}", name.area);
    assert_eq!(by_name(&f, "news")[0].on, "Si");
    let size = by_name(&f, "size");
    assert_eq!(size.iter().map(|s| s.on.as_str()).collect::<Vec<_>>(), ["s", "m", "l"]);
    assert!(size.iter().all(|s| s.kind == Kind::Radio && s.value == "Off"));
    assert_eq!(by_name(&f, "country")[0].options, ["AR", "ES", "MX"]);
    assert!(by_name(&f, "locked")[0].read_only);
    assert!(!forms::is_signed(&doc));
}

#[test]
fn filled_values_read_back_in_lopdf_and_pdfium() {
    let mut doc = pp_core::open(&form(false), None).unwrap();
    let v: BTreeMap<String, String> = [("name", "Señora Pérez"), ("news", "Si"), ("size", "m"), ("country", "MX")]
        .into_iter().map(|(a, b)| (a.to_string(), b.to_string())).collect();
    forms::fill(&mut doc, &v).unwrap();
    let bytes = pp_core::save(&mut doc).unwrap();
    let back = pp_core::open(&bytes, None).unwrap();
    let f = forms::fields(&back).unwrap();
    assert_eq!(by_name(&f, "name")[0].value, "Señora Pérez");
    assert_eq!(by_name(&f, "news")[0].value, "Si");
    assert!(by_name(&f, "size").iter().all(|s| s.value == "m"));
    assert_eq!(by_name(&f, "country")[0].value, "MX");
    // each radio widget shows on only for its own choice
    let annots: Vec<String> = back.get_pages().values().flat_map(|p| {
        let page = back.get_dictionary(*p).unwrap();
        page.get(b"Annots").unwrap().as_array().unwrap().iter()
            .filter_map(|a| back.get_dictionary(a.as_reference().ok()?).ok())
            .filter_map(|w| w.get(b"AS").ok().and_then(|s| s.as_name().ok()).map(|s| String::from_utf8_lossy(s).into_owned()))
            .collect::<Vec<_>>()
    }).collect();
    assert_eq!(annots, ["Off", "m", "Off", "Si"]); // pinned: s/m/l then news, in page order
    // PDFium opens the filled copy and draws it (what the app and other readers show)
    let e = engine();
    let v = e.open(bytes, None).unwrap();
    assert!(v.render(1, 400).is_ok());
}

#[test]
fn a_bad_fill_changes_nothing() {
    let original = form(false);
    for (k, val) in [("nobody", "x"), ("locked", "new"), ("country", "FR"), ("size", "xl"), ("news", "Yes")] {
        let mut doc = pp_core::open(&original, None).unwrap();
        let mut v = BTreeMap::new();
        v.insert("name".to_string(), "fine".to_string());
        v.insert(k.to_string(), val.to_string());
        assert!(forms::fill(&mut doc, &v).is_err(), "{k}={val} was accepted");
        let f = forms::fields(&doc).unwrap();
        assert_eq!(by_name(&f, "name")[0].value, "old", "{k}={val} changed another field first"); // pinned: unchanged
    }
}

#[test]
fn a_signed_pdf_is_recognised() {
    let doc = pp_core::open(&form(true), None).unwrap();
    assert!(forms::is_signed(&doc));
    let f = forms::fields(&doc).unwrap();
    assert_eq!(by_name(&f, "signature")[0].kind, Kind::Signature);
    let mut doc = doc;
    let mut v = BTreeMap::new();
    v.insert("signature".to_string(), "x".to_string());
    assert!(forms::fill(&mut doc, &v).is_err());
}
