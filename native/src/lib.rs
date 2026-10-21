use std::ffi::c_int;
use std::io::{Cursor, Read, Write};
use std::os::fd::FromRawFd;
use std::sync::Arc;

use image::codecs::jpeg::JpegEncoder;
use image::{DynamicImage, ImageEncoder, imageops::FilterType};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring};
use rawler::decoders::{Decoder, Orientation, RawDecodeParams, RawMetadata};
use rawler::dng::convert::{ConvertParams, convert_raw_source};
use rawler::formats::tiff::Rational;
use rawler::imgop::develop::RawDevelop;
use rawler::rawimage::RawImage;
use rawler::rawsource::RawSource;

const SOFTWARE: &str = "Raw2DNG 0.1.0";

#[derive(Debug, Clone)]
pub struct Probe {
    pub make: String,
    pub model: String,
    pub width: usize,
    pub height: usize,
    pub orientation: u16,
    pub has_jpeg: bool,
    pub jpeg_width: u32,
    pub jpeg_height: u32,
    pub datetime: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JpegSource {
    Auto = 0,
    EmbeddedOnly = 1,
    Develop = 2,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JpegInfo {
    pub source: &'static str,
    pub width: u32,
    pub height: u32,
    pub bytes: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JpegOutput {
    pub data: Vec<u8>,
    pub info: JpegInfo,
}

struct LoadedRaw {
    raw: RawImage,
    metadata: RawMetadata,
    embedded: Option<DynamicImage>,
    orientation: Orientation,
}

fn raw_error(error: impl std::fmt::Display) -> String {
    let message = error.to_string();
    if message.starts_with("Error: ") {
        message.trim_start_matches("Error: ").to_owned()
    } else {
        message
    }
}

fn load_raw(bytes: Vec<u8>, include_embedded: bool) -> Result<LoadedRaw, String> {
    let source = RawSource::new_from_shared_vec(Arc::new(bytes));
    let decoder = rawler::get_decoder(&source).map_err(raw_error)?;
    let params = RawDecodeParams::default();
    let metadata = decoder.raw_metadata(&source, &params).map_err(raw_error)?;
    let raw = decoder.raw_image(&source, &params, false).map_err(raw_error)?;
    let orientation = metadata
        .exif
        .orientation
        .map(Orientation::from_u16)
        .filter(|orientation| *orientation != Orientation::Unknown)
        .unwrap_or(raw.orientation);
    let embedded = if include_embedded {
        largest_embedded(decoder.as_ref(), &source, &params).map_err(raw_error)?
    } else {
        None
    };
    Ok(LoadedRaw { raw, metadata, embedded, orientation })
}

fn largest_embedded(
    decoder: &dyn Decoder,
    source: &RawSource,
    params: &RawDecodeParams,
) -> rawler::Result<Option<DynamicImage>> {
    let mut largest = None;
    for candidate in [
        decoder.full_image(source, params)?,
        decoder.preview_image(source, params)?,
        decoder.thumbnail_image(source, params)?,
    ]
    .into_iter()
    .flatten()
    {
        if largest.as_ref().is_none_or(|current: &DynamicImage| {
            candidate.width() as u64 * candidate.height() as u64
                > current.width() as u64 * current.height() as u64
        }) {
            largest = Some(candidate);
        }
    }
    Ok(largest)
}

fn orientation_number(orientation: Orientation) -> u16 {
    match orientation {
        Orientation::Normal => 1,
        Orientation::HorizontalFlip => 2,
        Orientation::Rotate180 => 3,
        Orientation::VerticalFlip => 4,
        Orientation::Transpose => 5,
        Orientation::Rotate90 => 6,
        Orientation::Transverse => 7,
        Orientation::Rotate270 => 8,
        Orientation::Unknown => 1,
    }
}

fn orient(image: DynamicImage, orientation: Orientation) -> DynamicImage {
    match orientation {
        Orientation::Normal | Orientation::Unknown => image,
        Orientation::HorizontalFlip => image.fliph(),
        Orientation::Rotate180 => image.rotate180(),
        Orientation::VerticalFlip => image.flipv(),
        Orientation::Transpose => image.rotate90().fliph(),
        Orientation::Rotate90 => image.rotate90(),
        Orientation::Transverse => image.rotate90().flipv(),
        Orientation::Rotate270 => image.rotate270(),
    }
}

pub fn probe_bytes(bytes: Vec<u8>) -> Result<Probe, String> {
    let loaded = load_raw(bytes, true)?;
    let (jpeg_width, jpeg_height) = loaded
        .embedded
        .as_ref()
        .map(|image| (image.width(), image.height()))
        .unwrap_or((0, 0));
    Ok(Probe {
        make: loaded.metadata.make,
        model: loaded.metadata.model,
        width: loaded.raw.width,
        height: loaded.raw.height,
        orientation: orientation_number(loaded.orientation),
        has_jpeg: loaded.embedded.is_some(),
        jpeg_width,
        jpeg_height,
        datetime: loaded.metadata.exif.date_time_original,
    })
}

pub fn preview_bytes(bytes: Vec<u8>, max_edge: u32) -> Result<Option<Vec<u8>>, String> {
    let loaded = load_raw(bytes, true)?;
    let Some(image) = loaded.embedded else {
        return Ok(None);
    };
    let mut image = orient(image, loaded.orientation);
    image = resize_long_edge(image, max_edge, FilterType::Triangle);
    encode_jpeg(&image, 82).map(Some)
}

pub fn to_dng_bytes(bytes: Vec<u8>, embed_original: bool) -> Result<Vec<u8>, String> {
    let source = RawSource::new_from_shared_vec(Arc::new(bytes));
    let mut cursor = Cursor::new(Vec::new());
    let params = ConvertParams {
        embedded: embed_original,
        preview: true,
        thumbnail: true,
        software: SOFTWARE.to_owned(),
        ..ConvertParams::default()
    };
    convert_raw_source(&source, &mut cursor, "input.raw", &params).map_err(raw_error)?;
    Ok(cursor.into_inner())
}

pub fn to_jpeg_bytes(
    bytes: Vec<u8>,
    source_choice: JpegSource,
    long_edge: u32,
    max_bytes: usize,
    quality: u8,
) -> Result<JpegOutput, String> {
    let loaded = load_raw(bytes, true)?;
    let embedded_edge = loaded
        .embedded
        .as_ref()
        .map(|image| image.width().max(image.height()))
        .unwrap_or(0);
    let raw_edge = loaded.raw.width.max(loaded.raw.height) as u32;
    let use_embedded = match source_choice {
        JpegSource::EmbeddedOnly => loaded.embedded.is_some(),
        JpegSource::Develop => false,
        JpegSource::Auto => {
            let threshold = if long_edge > 0 {
                long_edge
            } else {
                (raw_edge as f64 * 0.9).ceil() as u32
            };
            loaded.embedded.is_some() && embedded_edge >= threshold
        }
    };
    if source_choice == JpegSource::EmbeddedOnly && !use_embedded {
        return Err("This RAW file has no embedded camera JPEG.".to_owned());
    }

    let LoadedRaw { raw, metadata, embedded, orientation } = loaded;
    let source_name = if use_embedded { "embedded" } else { "developed" };
    let mut image = if use_embedded {
        drop(raw);
        embedded.ok_or_else(|| "This RAW file has no embedded camera JPEG.".to_owned())?
    } else {
        // The preview can be a full-size camera JPEG. Release it before demosaicing.
        drop(embedded);
        let image = RawDevelop::default()
            .develop_intermediate(&raw)
            .map_err(raw_error)?
            .to_dynamic_image()
            .ok_or_else(|| "Could not create developed image.".to_owned())?
            .to_rgb8()
            .into();
        drop(raw);
        image
    };
    image = orient(image, orientation);
    if long_edge > 0 {
        image = resize_long_edge(image, long_edge, FilterType::Lanczos3);
    }

    encode_limited(image, source_name, &metadata, max_bytes, quality)
}

fn resize_long_edge(image: DynamicImage, edge: u32, filter: FilterType) -> DynamicImage {
    if edge == 0 {
        return image;
    }
    let long = image.width().max(image.height());
    if long <= edge {
        return image;
    }
    let scale = edge as f64 / long as f64;
    let width = ((image.width() as f64 * scale).round() as u32).max(1);
    let height = ((image.height() as f64 * scale).round() as u32).max(1);
    image.resize_exact(width, height, filter)
}

fn encode_jpeg(image: &DynamicImage, quality: u8) -> Result<Vec<u8>, String> {
    let rgb = image.to_rgb8();
    let mut output = Vec::new();
    JpegEncoder::new_with_quality(&mut output, quality)
        .write_image(
            rgb.as_raw(),
            rgb.width(),
            rgb.height(),
            image::ExtendedColorType::Rgb8,
        )
        .map_err(|error| error.to_string())?;
    Ok(output)
}

fn encode_limited(
    mut image: DynamicImage,
    source: &'static str,
    metadata: &RawMetadata,
    max_bytes: usize,
    quality: u8,
) -> Result<JpegOutput, String> {
    let max_quality = quality.clamp(40, 100);
    let mut shrink_count = 0;
    loop {
        let mut encoded = encode_quality_limited(&image, metadata, max_bytes, max_quality)?;
        if max_bytes == 0 || encoded.len() <= max_bytes {
            let width = image.width();
            let height = image.height();
            let size = encoded.len();
            return Ok(JpegOutput {
                data: std::mem::take(&mut encoded),
                info: JpegInfo {
                    source,
                    width,
                    height,
                    bytes: size,
                },
            });
        }
        if shrink_count >= 20 {
            return Err("Could not fit JPEG within the requested file size after resizing.".to_owned());
        }
        let width = ((image.width() as f64) * 0.85).floor() as u32;
        let height = ((image.height() as f64) * 0.85).floor() as u32;
        if width == 0 || height == 0 || (width == image.width() && height == image.height()) {
            return Err("Could not fit JPEG within the requested file size.".to_owned());
        }
        image = image.resize_exact(width.max(1), height.max(1), FilterType::Lanczos3);
        shrink_count += 1;
    }
}

fn encode_quality_limited(
    image: &DynamicImage,
    metadata: &RawMetadata,
    max_bytes: usize,
    max_quality: u8,
) -> Result<Vec<u8>, String> {
    let mut high = max_quality;
    let mut high_result = with_exif(encode_jpeg(image, high)?, metadata)?;
    if max_bytes == 0 || high_result.len() <= max_bytes {
        return Ok(high_result);
    }
    let mut low = 40u8;
    let low_result = with_exif(encode_jpeg(image, low)?, metadata)?;
    if low_result.len() > max_bytes {
        return Ok(low_result);
    }
    let mut best = low_result;
    while low < high {
        let mid = low + (high - low).div_ceil(2);
        let candidate = with_exif(encode_jpeg(image, mid)?, metadata)?;
        if candidate.len() <= max_bytes {
            low = mid;
            best = candidate;
        } else {
            high = mid - 1;
        }
    }
    high_result.clear();
    Ok(best)
}

#[derive(Clone)]
struct TiffEntry {
    tag: u16,
    kind: u16,
    count: u32,
    data: Vec<u8>,
}

fn ascii_entry(tag: u16, value: &str) -> TiffEntry {
    let mut data = value.as_bytes().to_vec();
    data.push(0);
    TiffEntry { tag, kind: 2, count: data.len() as u32, data }
}

fn short_entry(tag: u16, value: u16) -> TiffEntry {
    TiffEntry { tag, kind: 3, count: 1, data: value.to_le_bytes().to_vec() }
}

fn long_entry(tag: u16, value: u32) -> TiffEntry {
    TiffEntry { tag, kind: 4, count: 1, data: value.to_le_bytes().to_vec() }
}

fn rational_entry(tag: u16, value: Rational) -> TiffEntry {
    let mut data = Vec::with_capacity(8);
    data.extend_from_slice(&value.n.to_le_bytes());
    data.extend_from_slice(&value.d.to_le_bytes());
    TiffEntry { tag, kind: 5, count: 1, data }
}

fn write_ifd(out: &mut Vec<u8>, entries: &mut [TiffEntry], start: u32) -> Result<(), String> {
    entries.sort_by_key(|entry| entry.tag);
    out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
    let mut external_offset = start + 2 + entries.len() as u32 * 12 + 4;
    let mut external = Vec::new();
    for entry in entries.iter() {
        out.extend_from_slice(&entry.tag.to_le_bytes());
        out.extend_from_slice(&entry.kind.to_le_bytes());
        out.extend_from_slice(&entry.count.to_le_bytes());
        if entry.data.len() <= 4 {
            out.extend_from_slice(&entry.data);
            out.resize(out.len() + 4 - entry.data.len(), 0);
        } else {
            out.extend_from_slice(&external_offset.to_le_bytes());
            external_offset = external_offset
                .checked_add(entry.data.len() as u32)
                .ok_or_else(|| "EXIF metadata is too large.".to_owned())?;
            external.extend_from_slice(&entry.data);
        }
    }
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&external);
    Ok(())
}

fn exif_payload(metadata: &RawMetadata) -> Result<Vec<u8>, String> {
    let mut root = vec![
        ascii_entry(271, &metadata.make),
        ascii_entry(272, &metadata.model),
        short_entry(274, 1),
        ascii_entry(305, SOFTWARE),
    ];
    let exif = &metadata.exif;
    let mut sub = Vec::new();
    if let Some(date) = &exif.date_time_original {
        sub.push(ascii_entry(36867, date));
    }
    if let Some(value) = exif.exposure_time {
        sub.push(rational_entry(33434, value));
    }
    if let Some(value) = exif.fnumber {
        sub.push(rational_entry(33437, value));
    }
    if let Some(value) = exif.iso_speed.or_else(|| exif.iso_speed_ratings.map(u32::from)) {
        if value <= u16::MAX as u32 {
            sub.push(short_entry(34855, value as u16));
        }
    }
    if let Some(value) = exif.focal_length {
        sub.push(rational_entry(37386, value));
    }
    if let Some(value) = &exif.lens_model {
        sub.push(ascii_entry(42036, value));
    }

    if !sub.is_empty() {
        let root_size = 2 + ((root.len() + 1) * 12) + 4;
        let sub_offset = 8 + root_size as u32;
        root.push(long_entry(34665, sub_offset));
    }
    root.sort_by_key(|entry| entry.tag);

    let mut tiff = Vec::new();
    tiff.extend_from_slice(b"II");
    tiff.extend_from_slice(&42u16.to_le_bytes());
    tiff.extend_from_slice(&8u32.to_le_bytes());
    let root_end = 8 + 2 + root.len() * 12 + 4;
    let sub_size = 2 + sub.len() * 12 + 4;
    let root_external_size: usize = root.iter().filter(|entry| entry.data.len() > 4).map(|entry| entry.data.len()).sum();
    let mut root_ifd = Vec::with_capacity(root_end - 8 + root_external_size);
    write_ifd(&mut root_ifd, &mut root, 8)?;
    if !sub.is_empty() {
        // The EXIF IFD follows the root's external values. Correct the pointer after its exact end is known.
        let actual_sub_offset = 8 + root_ifd.len() as u32;
        for chunk in root_ifd[2..].chunks_exact_mut(12) {
            if u16::from_le_bytes([chunk[0], chunk[1]]) == 34665 {
                chunk[8..12].copy_from_slice(&actual_sub_offset.to_le_bytes());
                break;
            }
        }
    }
    tiff.extend_from_slice(&root_ifd);
    if !sub.is_empty() {
        let sub_start = 8 + root_ifd.len() as u32;
        let mut sub_ifd = Vec::with_capacity(sub_size + 128);
        write_ifd(&mut sub_ifd, &mut sub, sub_start)?;
        tiff.extend_from_slice(&sub_ifd);
    }
    if tiff.len() + 6 + 2 > u16::MAX as usize {
        return Err("EXIF metadata is too large for a JPEG APP1 segment.".to_owned());
    }
    let mut payload = b"Exif\0\0".to_vec();
    payload.extend_from_slice(&tiff);
    Ok(payload)
}

fn with_exif(mut jpeg: Vec<u8>, metadata: &RawMetadata) -> Result<Vec<u8>, String> {
    if jpeg.len() < 2 || jpeg[..2] != [0xff, 0xd8] {
        return Err("JPEG encoder returned invalid data.".to_owned());
    }
    let payload = exif_payload(metadata)?;
    let segment_len = payload.len() + 2;
    let mut segment = Vec::with_capacity(payload.len() + 4);
    segment.extend_from_slice(&[0xff, 0xe1]);
    segment.extend_from_slice(&(segment_len as u16).to_be_bytes());
    segment.extend_from_slice(&payload);
    jpeg.splice(2..2, segment);
    Ok(jpeg)
}

fn escaped_json(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 2);
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c < ' ' => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

fn probe_json(result: Result<Probe, String>) -> String {
    match result {
        Ok(probe) => format!(
            "{{\"make\":\"{}\",\"model\":\"{}\",\"width\":{},\"height\":{},\"orientation\":{},\"hasEmbeddedJpeg\":{},\"jpegWidth\":{},\"jpegHeight\":{},\"datetime\":{}}}",
            escaped_json(&probe.make),
            escaped_json(&probe.model),
            probe.width,
            probe.height,
            probe.orientation,
            probe.has_jpeg,
            probe.jpeg_width,
            probe.jpeg_height,
            probe.datetime.as_deref().map(|value| format!("\"{}\"", escaped_json(value))).unwrap_or_else(|| "null".to_owned())
        ),
        Err(error) => format!("{{\"error\":\"{}\"}}", escaped_json(&error)),
    }
}

fn jpeg_json(result: Result<JpegInfo, String>) -> String {
    match result {
        Ok(info) => format!(
            "{{\"ok\":true,\"source\":\"{}\",\"width\":{},\"height\":{},\"bytes\":{}}}",
            info.source, info.width, info.height, info.bytes
        ),
        Err(error) => format!("{{\"ok\":false,\"error\":\"{}\"}}", escaped_json(&error)),
    }
}

unsafe extern "C" {
    fn dup(fd: c_int) -> c_int;
}

fn read_fd(fd: jint) -> Result<Vec<u8>, String> {
    let duplicated = unsafe { dup(fd) };
    if duplicated < 0 {
        return Err("Could not duplicate input file descriptor.".to_owned());
    }
    let mut file = unsafe { std::fs::File::from_raw_fd(duplicated) };
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes).map_err(|error| error.to_string())?;
    Ok(bytes)
}

fn write_fd(fd: jint, bytes: &[u8]) -> Result<(), String> {
    let duplicated = unsafe { dup(fd) };
    if duplicated < 0 {
        return Err("Could not duplicate output file descriptor.".to_owned());
    }
    let mut file = unsafe { std::fs::File::from_raw_fd(duplicated) };
    file.write_all(bytes).map_err(|error| error.to_string())
}

fn call_catching<T>(operation: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(operation))
        .map_err(|_| "Unexpected native panic while processing this RAW file.".to_owned())?
}

fn java_string(env: &mut JNIEnv<'_>, value: &str) -> jstring {
    env.new_string(value).map(JString::into_raw).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_raw2dng_Native_probe(mut env: JNIEnv, _class: JClass, input_fd: jint) -> jstring {
    let json = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        probe_json(call_catching(|| read_fd(input_fd).and_then(probe_bytes)))
    })).unwrap_or_else(|_| "{\"error\":\"Unexpected native panic while probing this RAW file.\"}".to_owned());
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| java_string(&mut env, &json)))
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_raw2dng_Native_preview(
    env: JNIEnv,
    _class: JClass,
    input_fd: jint,
    max_edge: jint,
) -> jbyteArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match call_catching(|| read_fd(input_fd).and_then(|bytes| preview_bytes(bytes, max_edge.max(1) as u32))) {
            Ok(Some(bytes)) => env.byte_array_from_slice(&bytes).map(JByteArray::into_raw).unwrap_or(std::ptr::null_mut()),
            _ => std::ptr::null_mut(),
        }
    })).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_raw2dng_Native_toDng(
    mut env: JNIEnv,
    _class: JClass,
    input_fd: jint,
    output_fd: jint,
    embed_original: jboolean,
) -> jstring {
    let error = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        call_catching(|| {
            let bytes = read_fd(input_fd)?;
            let dng = to_dng_bytes(bytes, embed_original != 0)?;
            write_fd(output_fd, &dng)
        }).err().unwrap_or_default()
    })).unwrap_or_else(|_| "Unexpected native panic while writing the DNG.".to_owned());
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| java_string(&mut env, &error)))
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_raw2dng_Native_toJpeg(
    mut env: JNIEnv,
    _class: JClass,
    input_fd: jint,
    output_fd: jint,
    source: jint,
    long_edge: jint,
    max_bytes: jint,
    quality: jint,
) -> jstring {
    let json = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let result = call_catching(|| {
            let source = match source {
                1 => JpegSource::EmbeddedOnly,
                2 => JpegSource::Develop,
                _ => JpegSource::Auto,
            };
            let output = to_jpeg_bytes(
                read_fd(input_fd)?,
                source,
                long_edge.max(0) as u32,
                max_bytes.max(0) as usize,
                quality.clamp(40, 100) as u8,
            )?;
            let info = output.info;
            write_fd(output_fd, &output.data)?;
            Ok(info)
        });
        jpeg_json(result)
    })).unwrap_or_else(|_| "{\"ok\":false,\"error\":\"Unexpected native panic while writing the JPEG.\"}".to_owned());
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| java_string(&mut env, &json)))
        .unwrap_or(std::ptr::null_mut())
}

