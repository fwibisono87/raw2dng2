use raw2dng::{JpegSource, to_dng_bytes, to_jpeg_bytes};
use std::fs;
use rawler::decoders::RawDecodeParams;
use rawler::rawsource::RawSource;
use std::sync::Arc;

fn hwm_kib() -> Option<u64> {
    let status = fs::read_to_string("/proc/self/status").ok()?;
    status.lines().find_map(|line| {
        line.strip_prefix("VmHWM:")
            .and_then(|value| value.split_whitespace().next())
            .and_then(|value| value.parse().ok())
    })
}

fn main() {
    let mut args = std::env::args().skip(1);
    let mode = args.next().expect("mode: probe | dng | embedded | develop1080 | developOriginal");
    let paths: Vec<_> = args.map(std::path::PathBuf::from).collect();
    assert!(!paths.is_empty(), "provide at least one RAW path");
    if mode == "probe" {
        for path in paths {
            let raw = fs::read(&path).expect("read sample");
            let source = RawSource::new_from_shared_vec(Arc::new(raw));
            let decoder = rawler::get_decoder(&source).expect("create decoder");
            let metadata = decoder.raw_metadata(&source, &RawDecodeParams::default()).expect("read metadata");
            let orientation = metadata.exif.orientation.unwrap_or(1);
            if orientation != 1 {
                println!("{} orientation={} {} {}", path.display(), orientation, metadata.make, metadata.model);
            }
        }
        return;
    }
    assert_eq!(paths.len(), 1, "memory modes accept one RAW path per process");
    let path = &paths[0];
    let raw = fs::read(path).expect("read sample");
    match mode.as_str() {
        "dng" => {
            let output = to_dng_bytes(raw, false).expect("convert DNG");
            println!("DNG bytes={}", output.len());
        }
        "embedded" => {
            let output = to_jpeg_bytes(raw, JpegSource::Auto, 1080, 0, 92).expect("embedded JPEG");
            println!("JPEG source={} {}x{} bytes={}", output.info.source, output.info.width, output.info.height, output.info.bytes);
        }
        "develop1080" => {
            let output = to_jpeg_bytes(raw, JpegSource::Develop, 1080, 0, 92).expect("developed JPEG");
            println!("JPEG source={} {}x{} bytes={}", output.info.source, output.info.width, output.info.height, output.info.bytes);
        }
        "developOriginal" => {
            let output = to_jpeg_bytes(raw, JpegSource::Develop, 0, 0, 92).expect("developed JPEG");
            println!("JPEG source={} {}x{} bytes={}", output.info.source, output.info.width, output.info.height, output.info.bytes);
        }
        other => panic!("unknown mode {other}"),
    }
    println!("VmHWM={} KiB", hwm_kib().unwrap_or_default());
}
