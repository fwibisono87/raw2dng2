use raw2dng::{JpegSource, probe_bytes, preview_bytes, to_dng_bytes, to_jpeg_bytes};
use rawler::decoders::RawDecodeParams;
use rawler::rawsource::RawSource;

fn assert_minimal_exif(jpeg: &[u8]) {
    assert_eq!(&jpeg[..4], &[0xff, 0xd8, 0xff, 0xe1]);
    let app1_len = u16::from_be_bytes([jpeg[4], jpeg[5]]) as usize;
    let payload = &jpeg[6..4 + app1_len];
    assert_eq!(&payload[..6], b"Exif\0\0");
    let tiff = &payload[6..];
    assert_eq!(&tiff[..2], b"II");
    let ifd0 = u32::from_le_bytes(tiff[4..8].try_into().unwrap()) as usize;
    let count = u16::from_le_bytes(tiff[ifd0..ifd0 + 2].try_into().unwrap()) as usize;
    let entries = &tiff[ifd0 + 2..ifd0 + 2 + count * 12];
    let orientation = entries
        .chunks_exact(12)
        .find(|entry| u16::from_le_bytes([entry[0], entry[1]]) == 274)
        .expect("Orientation tag");
    assert_eq!(u16::from_le_bytes([orientation[8], orientation[9]]), 1);
    assert!(tiff.windows(b"Raw2DNG 0.1.0\0".len()).any(|window| window == b"Raw2DNG 0.1.0\0"));
}

#[test]
fn raw_sample_conversion_contract() {
    let Some(path) = std::env::var_os("RAW2DNG_SAMPLE") else {
        eprintln!("RAW2DNG_SAMPLE is not set; skipping RAW integration coverage");
        return;
    };
    let bytes = std::fs::read(path).expect("read RAW2DNG_SAMPLE");
    let probe = probe_bytes(bytes.clone()).expect("probe sample");

    let dng = to_dng_bytes(bytes.clone(), false).expect("convert sample to DNG");
    let dng_source = RawSource::new_from_slice(&dng);
    let decoder = rawler::get_decoder(&dng_source).expect("parse generated DNG");
    let raw = decoder
        .raw_image(&dng_source, &RawDecodeParams::default(), false)
        .expect("decode generated DNG");
    assert_eq!((raw.width, raw.height), (probe.width, probe.height));

    let embedded = to_jpeg_bytes(bytes.clone(), JpegSource::Auto, 1080, 0, 92)
        .expect("embedded AUTO JPEG");
    assert_eq!(embedded.info.source, "embedded");
    assert_eq!(embedded.info.width.max(embedded.info.height), 1080);
    assert_minimal_exif(&embedded.data);

    let developed = to_jpeg_bytes(bytes.clone(), JpegSource::Develop, 1080, 0, 92)
        .expect("developed JPEG");
    assert_eq!(developed.info.source, "developed");
    assert_eq!(developed.info.width.max(developed.info.height), 1080);

    let limited = to_jpeg_bytes(bytes, JpegSource::Auto, 1080, 300_000, 92)
        .expect("size-limited JPEG");
    assert!(limited.info.bytes <= 300_000, "{} bytes", limited.info.bytes);
}

#[test]
fn portrait_sample_is_rotated() {
    let Some(path) = std::env::var_os("RAW2DNG_PORTRAIT_SAMPLE") else {
        eprintln!("RAW2DNG_PORTRAIT_SAMPLE is not set; skipping portrait orientation coverage");
        return;
    };
    let bytes = std::fs::read(path).expect("read RAW2DNG_PORTRAIT_SAMPLE");
    let probe = probe_bytes(bytes.clone()).expect("probe portrait sample");
    assert!(probe.orientation >= 5, "sample orientation is {}", probe.orientation);
    let jpeg = preview_bytes(bytes.clone(), 256)
        .expect("create portrait preview")
        .expect("portrait camera JPEG");
    let image = image::load_from_memory_with_format(&jpeg, image::ImageFormat::Jpeg)
        .expect("decode preview JPEG");
    assert!(image.height() > image.width());

    let output = to_jpeg_bytes(bytes, JpegSource::Auto, 256, 0, 92).expect("convert portrait JPEG");
    assert!(output.info.height > output.info.width);
}
