use raw2dng::{JpegSource, preview_bytes, probe_bytes, to_dng_bytes, to_jpeg_bytes};
use std::fs;

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
    let mode = args
        .next()
        .expect("mode: probe | preview256 | dng | embedded | develop1080 | developOriginal");
    let paths: Vec<_> = args.map(std::path::PathBuf::from).collect();
    assert_eq!(
        paths.len(),
        1,
        "bench modes accept one RAW path per process"
    );
    let path = &paths[0];
    let raw = fs::read(path).expect("read sample");
    let start = std::time::Instant::now();
    match mode.as_str() {
        "probe" => {
            let result = probe_bytes(raw).expect("probe sample");
            println!(
                "{} {} raw={}x{} orientation={} embedded={} jpeg={}x{}",
                result.make,
                result.model,
                result.width,
                result.height,
                result.orientation,
                result.has_jpeg,
                result.jpeg_width,
                result.jpeg_height
            );
        }
        "preview256" => {
            let result = preview_bytes(raw, 256)
                .expect("preview sample")
                .expect("embedded preview");
            println!("JPEG bytes={}", result.len());
        }
        "dng" => {
            let file_name = path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("sample.raw");
            let output = to_dng_bytes(raw, file_name, false).expect("convert DNG");
            println!("DNG bytes={}", output.len());
        }
        "embedded" => {
            let output = to_jpeg_bytes(raw, JpegSource::Auto, 1080, 0, 92).expect("embedded JPEG");
            println!(
                "JPEG source={} {}x{} bytes={}",
                output.info.source, output.info.width, output.info.height, output.info.bytes
            );
        }
        "develop1080" => {
            let output =
                to_jpeg_bytes(raw, JpegSource::Develop, 1080, 0, 92).expect("developed JPEG");
            println!(
                "JPEG source={} {}x{} bytes={}",
                output.info.source, output.info.width, output.info.height, output.info.bytes
            );
        }
        "developOriginal" => {
            let output = to_jpeg_bytes(raw, JpegSource::Develop, 0, 0, 92).expect("developed JPEG");
            println!(
                "JPEG source={} {}x{} bytes={}",
                output.info.source, output.info.width, output.info.height, output.info.bytes
            );
        }
        other => panic!("unknown mode {other}"),
    }
    println!("elapsed={} ms", start.elapsed().as_millis());
    println!("VmHWM={} KiB", hwm_kib().unwrap_or_default());
}
