// Guards on the test files themselves (2026-11-23: view_test loaded PDFium once per test; tests run in parallel
// threads and PDFium binds once per process, so one of them failed at random with "already initialized").
use std::fs;
use std::path::PathBuf;

#[test]
fn every_test_file_shares_one_pdfium_engine() {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests");
    let mut bad = Vec::new();
    for e in fs::read_dir(&dir).unwrap() {
        let p = e.unwrap().path();
        if p.extension().is_some_and(|x| x == "rs") && p.file_name().is_some_and(|n| n != "guard_test.rs") {
            let s = fs::read_to_string(&p).unwrap();
            if s.contains("Engine::load") && !s.contains("OnceLock<Engine>") {
                bad.push(p.file_name().unwrap().to_string_lossy().into_owned());
            }
        }
    }
    assert!(bad.is_empty(), "load PDFium through one OnceLock<Engine> per file: {bad:?}");
}
