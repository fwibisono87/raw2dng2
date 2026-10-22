use std::ffi::c_int;
use std::io::{Cursor, Read, Write};
use std::os::fd::FromRawFd;
use std::sync::Arc;

use image::codecs::jpeg::JpegEncoder;
use image::{DynamicImage, ImageBuffer, ImageEncoder, Luma, Rgb, imageops::FilterType};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring};
use jpeg_decoder::{
    CodingProcess as JpegCodingProcess, Decoder as JpegDecoder, PixelFormat as JpegPixelFormat,
};
use rawler::decoders::{Decoder, FormatHint, Orientation, RawDecodeParams, RawMetadata};
use rawler::dng::convert::{ConvertParams, convert_raw_source};
use rawler::formats::tiff::IFD;
use rawler::formats::tiff::Rational;
use rawler::imgop::develop::{Intermediate, ProcessingStep, RawDevelop};
use rawler::imgop::sensor::Demosaic;
use rawler::imgop::sensor::SensorType;
use rawler::imgop::sensor::bayer::superpixel::Superpixel3Channel;
use rawler::imgop::{Dim2, Point, Rect};
use rawler::pixarray::{Color2D, PixF32};
use rawler::rawimage::{
    BlackLevel, RawImage, RawImageData, RawPhotometricInterpretation, WhiteLevel,
};
use rawler::rawsource::RawSource;
use rawler::tags::TiffCommonTag;

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

struct RawContext {
    source: RawSource,
    decoder: Box<dyn Decoder>,
    params: RawDecodeParams,
    metadata: RawMetadata,
}

fn raw_error(error: impl std::fmt::Display) -> String {
    let message = error.to_string();
    if message.starts_with("Error: ") {
        message.trim_start_matches("Error: ").to_owned()
    } else {
        message
    }
}

impl RawContext {
    fn raw_image(&self, dummy: bool) -> Result<RawImage, String> {
        self.decoder
            .raw_image(&self.source, &self.params, dummy)
            .map_err(raw_error)
    }

    fn orientation(&self, fallback: Option<Orientation>) -> Orientation {
        self.metadata_orientation()
            .or(fallback)
            .unwrap_or(Orientation::Normal)
    }

    fn metadata_orientation(&self) -> Option<Orientation> {
        self.metadata
            .exif
            .orientation
            .map(Orientation::from_u16)
            .filter(|orientation| *orientation != Orientation::Unknown)
    }

    fn orientation_with_dummy_fallback(&self) -> Result<Orientation, String> {
        if let Some(orientation) = self.metadata_orientation() {
            return Ok(orientation);
        }
        let raw = self.raw_image(true)?;
        Ok(self.orientation(Some(raw.orientation)))
    }
}

fn open_raw(bytes: Vec<u8>) -> Result<RawContext, String> {
    let source = RawSource::new_from_shared_vec(Arc::new(bytes));
    let decoder = rawler::get_decoder(&source).map_err(raw_error)?;
    let params = RawDecodeParams::default();
    let metadata = decoder.raw_metadata(&source, &params).map_err(raw_error)?;
    Ok(RawContext {
        source,
        decoder,
        params,
        metadata,
    })
}

#[derive(Clone, Copy)]
enum EmbeddedKind {
    Thumbnail,
    Preview,
    Full,
}

fn decode_embedded(
    context: &RawContext,
    kind: EmbeddedKind,
) -> rawler::Result<Option<DynamicImage>> {
    match kind {
        EmbeddedKind::Thumbnail => context
            .decoder
            .thumbnail_image(&context.source, &context.params),
        EmbeddedKind::Preview => context
            .decoder
            .preview_image(&context.source, &context.params),
        EmbeddedKind::Full => context.decoder.full_image(&context.source, &context.params),
    }
}

fn select_embedded(context: &RawContext, min_long_edge: u32) -> Option<DynamicImage> {
    let mut largest: Option<DynamicImage> = None;
    for kind in [
        EmbeddedKind::Thumbnail,
        EmbeddedKind::Preview,
        EmbeddedKind::Full,
    ] {
        let Ok(Some(image)) = decode_embedded(context, kind) else {
            continue;
        };
        if min_long_edge > 0 && image.width().max(image.height()) >= min_long_edge {
            return Some(image);
        }
        if largest.as_ref().is_none_or(|current| {
            image.width() as u64 * image.height() as u64
                > current.width() as u64 * current.height() as u64
        }) {
            largest = Some(image);
        }
    }
    largest
}

fn decode_largest_embedded(context: &RawContext) -> Option<DynamicImage> {
    // These accessors are optional per decoder. Prefer its full-resolution image,
    // then its preview, and stop as soon as the decoder supplies one.
    for kind in [
        EmbeddedKind::Full,
        EmbeddedKind::Preview,
        EmbeddedKind::Thumbnail,
    ] {
        if let Ok(Some(image)) = decode_embedded(context, kind) {
            return Some(image);
        }
    }
    None
}

fn largest_embedded_dimensions(context: &RawContext) -> Option<(u32, u32)> {
    let mut largest = None;
    for kind in [
        EmbeddedKind::Full,
        EmbeddedKind::Preview,
        EmbeddedKind::Thumbnail,
    ] {
        if let Ok(Some(image)) = decode_embedded(context, kind) {
            let dimensions = (image.width(), image.height());
            if largest.is_none_or(|(width, height)| {
                dimensions.0 as u64 * dimensions.1 as u64 > width as u64 * height as u64
            }) {
                largest = Some(dimensions);
            }
            // Drop the decoded pixels immediately; probe only needs the SOF dimensions.
            drop(image);
        }
    }
    largest
}

fn jpeg_sof_dimensions(bytes: &[u8], start: usize) -> Option<(u32, u32)> {
    if bytes.get(start..start + 2)? != [0xff, 0xd8] {
        return None;
    }
    let mut position = start + 2;
    while position + 4 <= bytes.len() {
        if bytes[position] != 0xff {
            return None;
        }
        while bytes.get(position) == Some(&0xff) {
            position += 1;
        }
        let marker = *bytes.get(position)?;
        position += 1;
        if marker == 0xd9 || marker == 0xda {
            return None;
        }
        if marker == 0xd8 || (0xd0..=0xd7).contains(&marker) || marker == 0x01 {
            continue;
        }
        let length =
            u16::from_be_bytes([*bytes.get(position)?, *bytes.get(position + 1)?]) as usize;
        if length < 2 || position.checked_add(length)? > bytes.len() {
            return None;
        }
        // Camera display JPEGs use DCT SOF0/1/2. Lossless SOF3 is common in raw
        // sensor streams (including CR2) and is not an embedded camera JPEG.
        if matches!(marker, 0xc0..=0xc2) && length >= 8 {
            let height = u16::from_be_bytes([*bytes.get(position + 3)?, *bytes.get(position + 4)?]);
            let width = u16::from_be_bytes([*bytes.get(position + 5)?, *bytes.get(position + 6)?]);
            if width > 0 && height > 0 {
                return Some((u32::from(width), u32::from(height)));
            }
            return None;
        }
        position += length;
    }
    None
}

fn largest_jpeg_sof_dimensions(bytes: &[u8]) -> Option<(u32, u32)> {
    let mut best = None;
    let mut search_from = 0;
    while let Some(offset) = bytes[search_from..]
        .windows(2)
        .position(|window| window == [0xff, 0xd8])
        .map(|relative| search_from + relative)
    {
        if let Some(dimensions) = jpeg_sof_dimensions(bytes, offset)
            && best.is_none_or(|(width, height)| {
                dimensions.0 as u64 * dimensions.1 as u64 > width as u64 * height as u64
            })
        {
            best = Some(dimensions);
        }
        search_from = offset + 2;
    }
    best
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
    let context = open_raw(bytes)?;
    // Rawler's dummy path parses image dimensions but uses uninitialized storage and skips
    // pixel decompression. Its embedded image methods expose only decoded DynamicImages.
    let raw = context.raw_image(true)?;
    let orientation = context.orientation(Some(raw.orientation));
    let width = raw.width;
    let height = raw.height;
    drop(raw);
    let dimensions = largest_jpeg_sof_dimensions(context.source.buf())
        .or_else(|| largest_embedded_dimensions(&context));
    let (jpeg_width, jpeg_height) = dimensions.unwrap_or((0, 0));
    Ok(Probe {
        make: context.metadata.make.clone(),
        model: context.metadata.model.clone(),
        width,
        height,
        orientation: orientation_number(orientation),
        has_jpeg: dimensions.is_some(),
        jpeg_width,
        jpeg_height,
        datetime: context.metadata.exif.date_time_original.clone(),
    })
}

pub fn preview_bytes(bytes: Vec<u8>, max_edge: u32) -> Result<Option<Vec<u8>>, String> {
    let context = open_raw(bytes)?;
    let image = if max_edge > 0 {
        decode_scaled_cr2_preview(&context, max_edge)
    } else {
        None
    }
    .or_else(|| select_embedded(&context, max_edge.max(1)));
    let Some(image) = image else {
        return Ok(None);
    };
    let orientation = context.orientation_with_dummy_fallback()?;
    drop(context);
    let mut image = orient(image, orientation);
    image = resize_long_edge(image, max_edge, FilterType::Triangle);
    encode_jpeg(&image, 82).map(Some)
}

const MAX_SCALED_JPEG_SAMPLES: usize = 16 * 1024 * 1024;
const MAX_CR2_JPEG_BYTES: usize = 32 * 1024 * 1024;
const MAX_CR2_JPEG_PIXELS: u64 = 100_000_000;

fn decode_scaled_cr2_preview(context: &RawContext, max_edge: u32) -> Option<DynamicImage> {
    if context.decoder.format_hint() != FormatHint::CR2 {
        return None;
    }

    // Canon CR2 files commonly store a 6240x4160 camera JPEG in IFD0. Read its
    // strip through rawler's TIFF parser, then let jpeg-decoder use its scaled
    // IDCT so we never allocate a full-resolution RGB image for a thumbnail.
    let mut tiff_reader = context.source.reader();
    let root = IFD::new_root_with_correction(&mut tiff_reader, 0, 0, 0, 1, &[]).ok()?;
    if root
        .get_entry(TiffCommonTag::Compression)?
        .value
        .get_u16(0)
        .ok()
        .flatten()?
        != 6
    {
        return None;
    }

    let strip_offsets = root.get_entry(TiffCommonTag::StripOffsets)?;
    let strip_byte_counts = root.get_entry(TiffCommonTag::StripByteCounts)?;
    if strip_offsets.count() != 1 || strip_byte_counts.count() != 1 {
        return None;
    }
    let jpeg_offset = strip_offsets.value.get_usize(0).ok().flatten()?;
    let jpeg_length = strip_byte_counts.value.get_usize(0).ok().flatten()?;
    if jpeg_length == 0 || jpeg_length > MAX_CR2_JPEG_BYTES {
        return None;
    }
    let jpeg_end = jpeg_offset.checked_add(jpeg_length)?;
    let jpeg = context.source.buf().get(jpeg_offset..jpeg_end)?;
    let (width, height) = jpeg_sof_dimensions(jpeg, 0)?;
    let tiff_width = u32::try_from(
        root.get_entry(TiffCommonTag::ImageWidth)?
            .value
            .get_usize(0)
            .ok()
            .flatten()?,
    )
    .ok()?;
    let tiff_height = u32::try_from(
        root.get_entry(TiffCommonTag::ImageLength)?
            .value
            .get_usize(0)
            .ok()
            .flatten()?,
    )
    .ok()?;
    if width != tiff_width || height != tiff_height {
        return None;
    }
    if width.max(height) < max_edge {
        return None;
    }
    if u64::from(width) * u64::from(height) > MAX_CR2_JPEG_PIXELS {
        // Bound work for unusually large JPEGs before asking the decoder to parse them.
        return None;
    }

    decode_scaled_jpeg(jpeg, max_edge)
}

fn decode_scaled_jpeg(jpeg: &[u8], max_edge: u32) -> Option<DynamicImage> {
    let (width, height) = jpeg_sof_dimensions(jpeg, 0)?;
    if u64::from(width) * u64::from(height) > MAX_CR2_JPEG_PIXELS {
        return None;
    }

    let requested = max_edge.min(u32::from(u16::MAX)) as u16;
    let mut decoder = JpegDecoder::new(Cursor::new(jpeg));
    decoder.set_max_decoding_buffer_size(MAX_SCALED_JPEG_SAMPLES);
    let (scaled_width, scaled_height) = decoder.scale(requested, requested).ok()?;
    let info = decoder.info()?;
    // Only sequential DCT stays within the output-buffer bound. Progressive
    // JPEGs retain coefficient planes, and lossless JPEGs build full planes.
    if info.coding_process != JpegCodingProcess::DctSequential {
        return None;
    }
    if !matches!(info.pixel_format, JpegPixelFormat::RGB24 | JpegPixelFormat::L8) {
        return None;
    }
    let samples = usize::from(scaled_width)
        .checked_mul(usize::from(scaled_height))?
        .checked_mul(info.pixel_format.pixel_bytes())?;
    if info.width != scaled_width
        || info.height != scaled_height
        || samples > MAX_SCALED_JPEG_SAMPLES
    {
        return None;
    }
    let pixels = decoder.decode().ok()?;
    if pixels.len() != samples {
        return None;
    }

    match info.pixel_format {
        JpegPixelFormat::RGB24 => ImageBuffer::<Rgb<u8>, Vec<u8>>::from_raw(
            u32::from(scaled_width),
            u32::from(scaled_height),
            pixels,
        )
        .map(DynamicImage::ImageRgb8),
        JpegPixelFormat::L8 => ImageBuffer::<Luma<u8>, Vec<u8>>::from_raw(
            u32::from(scaled_width),
            u32::from(scaled_height),
            pixels,
        )
        .map(DynamicImage::ImageLuma8),
        JpegPixelFormat::L16 | JpegPixelFormat::CMYK32 => None,
    }
}

pub fn to_dng_bytes(
    bytes: Vec<u8>,
    file_name: &str,
    embed_original: bool,
) -> Result<Vec<u8>, String> {
    let source = RawSource::new_from_shared_vec(Arc::new(bytes));
    let mut cursor = Cursor::new(Vec::new());
    let params = ConvertParams {
        embedded: embed_original,
        preview: true,
        thumbnail: true,
        software: SOFTWARE.to_owned(),
        ..ConvertParams::default()
    };
    convert_raw_source(&source, &mut cursor, file_name, &params).map_err(raw_error)?;
    Ok(cursor.into_inner())
}

pub fn to_jpeg_bytes(
    bytes: Vec<u8>,
    source_choice: JpegSource,
    long_edge: u32,
    max_bytes: usize,
    quality: u8,
) -> Result<JpegOutput, String> {
    let context = open_raw(bytes)?;
    let metadata = context.metadata.clone();
    let mut raw_dimensions = None;
    let threshold = if source_choice == JpegSource::Auto {
        if long_edge > 0 {
            long_edge
        } else {
            let raw = context.raw_image(true)?;
            raw_dimensions = Some((raw.width, raw.height, raw.orientation));
            (raw.width.max(raw.height) as f64 * 0.9).ceil() as u32
        }
    } else {
        0
    };

    // Use the JPEG SOF dimensions to decide whether an embedded image qualifies
    // before asking rawler to decode its pixels. This avoids decoding a full JPEG
    // when AUTO will reject it and develop from the raw sensor data instead.
    let embedded_dimensions = if source_choice == JpegSource::Develop {
        None
    } else {
        largest_jpeg_sof_dimensions(context.source.buf())
    };
    let embedded_qualifies = embedded_dimensions
        .map(|(width, height)| width.max(height) >= threshold)
        .unwrap_or(true);
    let embedded = match source_choice {
        JpegSource::Develop => None,
        JpegSource::EmbeddedOnly if embedded_qualifies => decode_largest_embedded(&context),
        JpegSource::EmbeddedOnly => None,
        JpegSource::Auto if embedded_qualifies => decode_largest_embedded(&context),
        JpegSource::Auto => None,
    };
    let embedded_edge = embedded
        .as_ref()
        .map(|image| image.width().max(image.height()))
        .unwrap_or(0);
    let use_embedded = match source_choice {
        JpegSource::Develop => false,
        JpegSource::EmbeddedOnly => embedded.is_some(),
        JpegSource::Auto => {
            embedded.is_some()
                && embedded_edge >= threshold
                && embedded_qualifies
        }
    };
    if source_choice == JpegSource::EmbeddedOnly && !use_embedded {
        return Err("This RAW file has no embedded camera JPEG.".to_owned());
    }

    let source_name = if use_embedded {
        "embedded"
    } else {
        "developed"
    };
    let (mut image, orientation) = if use_embedded {
        let orientation = match raw_dimensions {
            Some((_, _, fallback)) => context.orientation(Some(fallback)),
            None => context.orientation_with_dummy_fallback()?,
        };
        drop(context);
        (embedded.expect("selected embedded image"), orientation)
    } else {
        drop(embedded);
        let raw = context.raw_image(false)?;
        let orientation = context.orientation(Some(raw.orientation));
        drop(context);
        (develop_raw(raw, long_edge)?, orientation)
    };
    image = orient(image, orientation);
    if long_edge > 0 {
        image = resize_long_edge(image, long_edge, FilterType::Lanczos3);
    }

    encode_limited(image, source_name, &metadata, max_bytes, quality)
}

fn should_bin_bayer(raw: &RawImage, long_edge: u32) -> bool {
    if long_edge == 0 || (long_edge as u64 * 2) > raw.width.max(raw.height) as u64 || raw.cpp != 1 {
        return false;
    }
    if raw.fuji_rotation_width.is_some() {
        return false;
    }
    matches!(
        &raw.photometric,
        RawPhotometricInterpretation::Cfa(config)
            if config.sensor == SensorType::Bayer && config.cfa.is_rgb() && config.colors.plane_count() == 3
    )
}

fn downsample_rgb_2x2(input: Color2D<f32, 3>) -> Color2D<f32, 3> {
    let out_width = input.width / 2;
    let out_height = input.height / 2;
    let mut output = Vec::with_capacity(out_width * out_height);
    for y in 0..out_height {
        let row0 = (y * 2) * input.width;
        let row1 = row0 + input.width;
        for x in 0..out_width {
            let column = x * 2;
            let samples = [
                input.data[row0 + column],
                input.data[row0 + column + 1],
                input.data[row1 + column],
                input.data[row1 + column + 1],
            ];
            output.push([
                (samples[0][0] + samples[1][0] + samples[2][0] + samples[3][0]) * 0.25,
                (samples[0][1] + samples[1][1] + samples[2][1] + samples[3][1]) * 0.25,
                (samples[0][2] + samples[1][2] + samples[2][2] + samples[3][2]) * 0.25,
            ]);
        }
    }
    Color2D::new_with(output, out_width, out_height)
}

fn bin_bayer(raw: &mut RawImage, long_edge: u32) -> Result<(), String> {
    let config = match &raw.photometric {
        RawPhotometricInterpretation::Cfa(config) => config.clone(),
        _ => return Err("Binned development requires a Bayer CFA image.".to_owned()),
    };
    let input_width = raw.width;
    let input_height = raw.height;
    raw.apply_scaling().map_err(raw_error)?;
    let pixels = match std::mem::replace(&mut raw.data, RawImageData::Float(Vec::new())) {
        RawImageData::Float(pixels) => pixels,
        RawImageData::Integer(_) => {
            return Err("Could not scale Bayer pixels for binned development.".to_owned());
        }
    };
    let input = PixF32::new_with(pixels, input_width, input_height);
    let roi = Rect::new(Point::zero(), Dim2::new(input_width, input_height));
    let mut reduced = Superpixel3Channel::new().demosaic(&input, &config.cfa, &config.colors, roi);
    drop(input);

    let mut scale = 2usize;
    while long_edge > 0
        && reduced.width.max(reduced.height) / 2 >= long_edge as usize
        && reduced.width >= 2
        && reduced.height >= 2
    {
        reduced = downsample_rgb_2x2(reduced);
        scale *= 2;
    }

    let width = reduced.width;
    let height = reduced.height;
    raw.width = width;
    raw.height = height;
    raw.cpp = 3;
    raw.data = RawImageData::Float(reduced.into_flatten());
    raw.photometric = RawPhotometricInterpretation::LinearRaw;
    raw.blacklevel = BlackLevel::zero(1, 1, 3);
    raw.whitelevel = WhiteLevel::new(vec![1, 1, 1]);
    raw.active_area = raw.active_area.map(|mut area| {
        area.scale(1.0 / scale as f32);
        area
    });
    raw.crop_area = raw.crop_area.map(|mut area| {
        area.scale(1.0 / scale as f32);
        area
    });
    raw.blackareas.clear();
    Ok(())
}

fn develop_raw(mut raw: RawImage, long_edge: u32) -> Result<DynamicImage, String> {
    let binned = should_bin_bayer(&raw, long_edge);
    if binned {
        bin_bayer(&mut raw, long_edge)?;
    }
    let developer = if binned {
        RawDevelop::new_with(&[
            ProcessingStep::WhiteBalance,
            ProcessingStep::Calibrate,
            ProcessingStep::CropDefault,
            ProcessingStep::SRgb,
        ])
    } else {
        RawDevelop::default()
    };
    let intermediate = developer.develop_intermediate(&raw).map_err(raw_error)?;
    drop(raw);
    intermediate_to_rgb8(intermediate)
}

fn f32_to_u8(value: f32) -> u8 {
    let normalized = if value.is_nan() { 0.0 } else { value.clamp(0.0, 1.0) };
    let value16 = (normalized * u16::MAX as f32) as u16;
    ((u32::from(value16) + 128) / 257) as u8
}

fn intermediate_to_rgb8(intermediate: Intermediate) -> Result<DynamicImage, String> {
    let (width, height, data): (usize, usize, Vec<u8>) = match intermediate {
        Intermediate::Monochrome(pixels) => {
            let mut data = Vec::with_capacity(pixels.data.len().saturating_mul(3));
            for value in pixels.data {
                let gray = f32_to_u8(value);
                data.extend_from_slice(&[gray, gray, gray]);
            }
            (pixels.width, pixels.height, data)
        }
        Intermediate::ThreeColor(pixels) => {
            let mut data = Vec::with_capacity(pixels.data.len().saturating_mul(3));
            for [red, green, blue] in pixels.data {
                data.extend_from_slice(&[
                    f32_to_u8(red),
                    f32_to_u8(green),
                    f32_to_u8(blue),
                ]);
            }
            (pixels.width, pixels.height, data)
        }
        Intermediate::FourColor(pixels) => {
            let mut data = Vec::with_capacity(pixels.data.len().saturating_mul(3));
            for [red, green, blue, _] in pixels.data {
                data.extend_from_slice(&[
                    f32_to_u8(red),
                    f32_to_u8(green),
                    f32_to_u8(blue),
                ]);
            }
            (pixels.width, pixels.height, data)
        }
    };
    ImageBuffer::from_raw(width as u32, height as u32, data)
        .map(DynamicImage::ImageRgb8)
        .ok_or_else(|| "Could not create developed image.".to_owned())
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
            return Err(
                "Could not fit JPEG within the requested file size after resizing.".to_owned(),
            );
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
    TiffEntry {
        tag,
        kind: 2,
        count: data.len() as u32,
        data,
    }
}

fn short_entry(tag: u16, value: u16) -> TiffEntry {
    TiffEntry {
        tag,
        kind: 3,
        count: 1,
        data: value.to_le_bytes().to_vec(),
    }
}

fn long_entry(tag: u16, value: u32) -> TiffEntry {
    TiffEntry {
        tag,
        kind: 4,
        count: 1,
        data: value.to_le_bytes().to_vec(),
    }
}

fn rational_entry(tag: u16, value: Rational) -> TiffEntry {
    let mut data = Vec::with_capacity(8);
    data.extend_from_slice(&value.n.to_le_bytes());
    data.extend_from_slice(&value.d.to_le_bytes());
    TiffEntry {
        tag,
        kind: 5,
        count: 1,
        data,
    }
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
    if let Some(value) = exif
        .iso_speed
        .or_else(|| exif.iso_speed_ratings.map(u32::from))
        && value <= u16::MAX as u32
    {
        sub.push(short_entry(34855, value as u16));
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
    let root_external_size: usize = root
        .iter()
        .filter(|entry| entry.data.len() > 4)
        .map(|entry| entry.data.len())
        .sum();
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
            probe
                .datetime
                .as_deref()
                .map(|value| format!("\"{}\"", escaped_json(value)))
                .unwrap_or_else(|| "null".to_owned())
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
    file.read_to_end(&mut bytes)
        .map_err(|error| error.to_string())?;
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
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_raw2dng_Native_probe(
    mut env: JNIEnv,
    _class: JClass,
    input_fd: jint,
) -> jstring {
    let json = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        probe_json(call_catching(|| read_fd(input_fd).and_then(probe_bytes)))
    }))
    .unwrap_or_else(|_| {
        "{\"error\":\"Unexpected native panic while probing this RAW file.\"}".to_owned()
    });
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        java_string(&mut env, &json)
    }))
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
        match call_catching(|| {
            read_fd(input_fd).and_then(|bytes| preview_bytes(bytes, max_edge.max(1) as u32))
        }) {
            Ok(Some(bytes)) => env
                .byte_array_from_slice(&bytes)
                .map(JByteArray::into_raw)
                .unwrap_or(std::ptr::null_mut()),
            _ => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_raw2dng_Native_toDng(
    mut env: JNIEnv,
    _class: JClass,
    input_fd: jint,
    output_fd: jint,
    file_name: JString,
    embed_original: jboolean,
) -> jstring {
    let error = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        call_catching(|| {
            let file_name = env
                .get_string(&file_name)
                .map_err(|error| error.to_string())?
                .to_string_lossy()
                .into_owned();
            let bytes = read_fd(input_fd)?;
            let dng = to_dng_bytes(bytes, &file_name, embed_original != 0)?;
            write_fd(output_fd, &dng)
        })
        .err()
        .unwrap_or_default()
    }))
    .unwrap_or_else(|_| "Unexpected native panic while writing the DNG.".to_owned());
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        java_string(&mut env, &error)
    }))
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
    }))
    .unwrap_or_else(|_| {
        "{\"ok\":false,\"error\":\"Unexpected native panic while writing the JPEG.\"}".to_owned()
    });
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        java_string(&mut env, &json)
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::{ImageBuffer, ImageEncoder, JpegEncoder, Rgb, decode_scaled_jpeg};
    use image::GenericImageView;

    #[test]
    fn scaled_jpeg_decode_limits_output_to_thumbnail_scale() {
        let source = ImageBuffer::from_fn(2048, 1024, |_, _| Rgb([120, 80, 40]));
        let mut jpeg = Vec::new();
        JpegEncoder::new_with_quality(&mut jpeg, 90)
            .write_image(
                source.as_raw(),
                source.width(),
                source.height(),
                image::ExtendedColorType::Rgb8,
            )
            .expect("encode test JPEG");

        let scaled = decode_scaled_jpeg(&jpeg, 256).expect("scaled JPEG decode");
        assert_eq!(scaled.dimensions(), (256, 128));
        let pixel = scaled.to_rgb8().get_pixel(128, 64).0;
        for (actual, expected) in pixel.into_iter().zip([120, 80, 40]) {
            assert!(actual.abs_diff(expected) <= 5, "pixel={pixel:?}");
        }
    }

    #[test]
    fn scaled_jpeg_decode_rejects_malformed_input() {
        assert!(decode_scaled_jpeg(&[0xff, 0xd8, 0xff, 0xd9], 256).is_none());
    }

    #[test]
    fn canon_portrait_uses_scaled_cr2_preview_when_available() {
        let Some(path) = std::env::var_os("RAW2DNG_PORTRAIT_SAMPLE") else {
            eprintln!("RAW2DNG_PORTRAIT_SAMPLE is not set; skipping Canon fast-path coverage");
            return;
        };
        let context = super::open_raw(std::fs::read(path).expect("read portrait sample"))
            .expect("open portrait sample");
        assert!(super::decode_scaled_cr2_preview(&context, 256).is_some());
        assert!(super::decode_scaled_cr2_preview(&context, 7000).is_none());
    }

    #[test]
    fn progressive_jpeg_is_rejected_before_coefficient_decode() {
        let jpeg = [
            0xff, 0xd8, // SOI
            0xff, 0xc2, 0x00, 0x11, // Progressive SOF, 17-byte marker
            0x08, 0x0f, 0xa0, 0x4e, 0x20, 0x03, // 20000x4000, 3 components
            0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
        ];
        assert!(decode_scaled_jpeg(&jpeg, 256).is_none());
    }
}
