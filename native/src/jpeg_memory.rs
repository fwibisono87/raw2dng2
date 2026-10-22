use image::{DynamicImage, ImageBuffer};
use rawler::imgop::develop::{ProcessingStep, RawDevelop};
use rawler::imgop::sensor::SensorType;
use rawler::imgop::{Dim2, Point, Rect};
use rawler::rawimage::{RawImage, RawImageData, RawPhotometricInterpretation};

const BAND_ROWS: usize = 256;
// PPG's edge pass spans three rows; its interpolation kernels reach two rows.
const DEMOSAIC_HALO_ROWS: usize = 6;

pub(super) fn supports_banded_development(raw: &RawImage) -> bool {
    if raw.cpp != 1 || raw.fuji_rotation_width.is_some() || raw.width < 7 || raw.height <= 6 {
        return false;
    }

    let RawPhotometricInterpretation::Cfa(config) = &raw.photometric else {
        return false;
    };
    if config.sensor != SensorType::Bayer
        || !config.cfa.is_rgb()
        || config.cfa.width != 2
        || config.cfa.height != 2
        || config.colors.plane_count() != 3
    {
        return false;
    }

    let active = raw
        .active_area
        .unwrap_or_else(|| Rect::new(Point::zero(), Dim2::new(raw.width, raw.height)));
    let crop = match (raw.active_area, raw.crop_area) {
        (Some(active), Some(crop)) => {
            if !rect_is_within(crop, active) {
                return false;
            }
            crop.adapt(&active)
        }
        (None, Some(crop)) => crop,
        (Some(active), None) => Rect::new(Point::zero(), active.d),
        (None, None) => Rect::new(Point::zero(), Dim2::new(raw.width, raw.height)),
    };

    active.d.w >= 7
        && active.d.h >= 7
        && rect_is_within(
            active,
            Rect::new(Point::zero(), Dim2::new(raw.width, raw.height)),
        )
        && rect_is_within(crop, Rect::new(Point::zero(), active.d))
        && crop.d.w > 0
        && crop.d.h > 0
}

fn rect_is_within(rect: Rect, bounds: Rect) -> bool {
    rect.p.x >= bounds.p.x
        && rect.p.y >= bounds.p.y
        && rect
            .p
            .x
            .checked_add(rect.d.w)
            .is_some_and(|right| right <= bounds.p.x + bounds.d.w)
        && rect
            .p
            .y
            .checked_add(rect.d.h)
            .is_some_and(|bottom| bottom <= bounds.p.y + bounds.d.h)
}

pub(super) fn develop_bayer_in_bands(mut raw: RawImage) -> Result<DynamicImage, String> {
    let config = match &raw.photometric {
        RawPhotometricInterpretation::Cfa(config) => config.clone(),
        _ => return Err("Banded development requires a Bayer CFA image.".to_owned()),
    };
    let width = raw.width;
    let height = raw.height;
    let sensor_rect = Rect::new(Point::zero(), Dim2::new(width, height));
    let develop_rect = raw.active_area.unwrap_or(sensor_rect);
    let crop = match (raw.active_area, raw.crop_area) {
        (Some(active), Some(crop)) => crop.adapt(&active),
        (Some(active), None) => Rect::new(Point::zero(), active.d),
        (None, Some(crop)) => crop,
        (None, None) => sensor_rect,
    };

    raw.apply_scaling().map_err(|error| error.to_string())?;
    let scaled_pixels = match std::mem::replace(&mut raw.data, RawImageData::Float(Vec::new())) {
        RawImageData::Float(pixels) => pixels,
        RawImageData::Integer(_) => {
            return Err("Could not scale Bayer pixels for development.".to_owned());
        }
    };

    let developer = RawDevelop::new_with(&[
        ProcessingStep::Demosaic,
        ProcessingStep::CropActiveArea,
        ProcessingStep::WhiteBalance,
        ProcessingStep::Calibrate,
        ProcessingStep::SRgb,
    ]);
    let mut output = vec![0_u8; crop.d.w * crop.d.h * 3];
    let output_y_end = crop.p.y + crop.d.h;

    for output_y in (crop.p.y..output_y_end).step_by(BAND_ROWS) {
        let output_end = (output_y + BAND_ROWS).min(output_y_end);
        let sensor_y = develop_rect.p.y + output_y;
        let sensor_end = develop_rect.p.y + output_end;
        let window_start = sensor_y
            .saturating_sub(DEMOSAIC_HALO_ROWS)
            .max(develop_rect.p.y);
        let window_end = (sensor_end + DEMOSAIC_HALO_ROWS).min(develop_rect.p.y + develop_rect.d.h);
        let window_height = window_end - window_start;
        let window_start_index = window_start * width;
        let window_end_index = window_end * width;

        let mut band = raw.clone();
        band.height = window_height;
        band.data =
            RawImageData::Float(scaled_pixels[window_start_index..window_end_index].to_vec());
        band.crop_area = None;
        band.fuji_rotation_width = None;
        band.photometric = RawPhotometricInterpretation::Cfa(rawler::rawimage::CFAConfig {
            cfa: config.cfa.shift(0, window_start),
            colors: config.colors.clone(),
            sensor: config.sensor,
        });

        let band_develop_rect = Rect::new(
            Point::new(develop_rect.p.x, 0),
            Dim2::new(develop_rect.d.w, window_height),
        );
        band.active_area = Some(band_develop_rect);
        let intermediate = developer
            .develop_intermediate(&band)
            .map_err(|error| error.to_string())?;
        let DynamicImage::ImageRgb8(band_image) = super::intermediate_to_rgb8(intermediate)? else {
            return Err("Banded Bayer development did not produce RGB pixels.".to_owned());
        };
        let band_pixels = band_image.into_raw();
        let band_origin_y = window_start;

        for source_y in sensor_y..sensor_end {
            let band_y = source_y - band_origin_y;
            let output_row = source_y - (develop_rect.p.y + crop.p.y);
            let source_start = (band_y * develop_rect.d.w + crop.p.x) * 3;
            let source_end = source_start + crop.d.w * 3;
            let output_start = output_row * crop.d.w * 3;
            output[output_start..output_start + crop.d.w * 3]
                .copy_from_slice(&band_pixels[source_start..source_end]);
        }
    }

    ImageBuffer::from_raw(crop.d.w as u32, crop.d.h as u32, output)
        .map(DynamicImage::ImageRgb8)
        .ok_or_else(|| "Could not create band-developed image.".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rawler::imgop::develop::RawDevelop;

    fn decode(path: &std::path::Path) -> RawImage {
        let context = super::super::open_raw(std::fs::read(path).expect("read RAW sample"))
            .expect("open RAW sample");
        let raw = context.raw_image(false).expect("decode RAW sample");
        drop(context);
        raw
    }

    fn full_frame_reference(raw: &RawImage) -> DynamicImage {
        let intermediate = RawDevelop::default()
            .develop_intermediate(raw)
            .expect("full-frame development");
        super::super::intermediate_to_rgb8(intermediate)
            .expect("convert full-frame development to RGB8")
    }

    fn assert_same_pixels(raw: RawImage) {
        assert!(supports_banded_development(&raw));
        let reference = full_frame_reference(&raw).to_rgb8();
        let actual = develop_bayer_in_bands(raw)
            .expect("banded development")
            .to_rgb8();
        assert_eq!(
            (actual.width(), actual.height()),
            (reference.width(), reference.height())
        );
        if let Some((offset, (found, expected))) = actual
            .as_raw()
            .iter()
            .zip(reference.as_raw())
            .enumerate()
            .find(|(_, (found, expected))| found != expected)
        {
            let pixel = offset / 3;
            panic!(
                "RGB8 mismatch at ({}, {}) channel {}: expected {}, found {}",
                pixel % actual.width() as usize,
                pixel / actual.width() as usize,
                offset % 3,
                expected,
                found
            );
        }
    }

    #[test]
    fn banded_development_matches_canon_landscape_and_portrait() {
        for variable in ["RAW2DNG_SAMPLE", "RAW2DNG_PORTRAIT_SAMPLE"] {
            let Some(path) = std::env::var_os(variable) else {
                continue;
            };
            assert_same_pixels(decode(std::path::Path::new(&path)));
        }
    }

    #[test]
    fn banded_development_preserves_odd_active_area_and_crop_phase() {
        let Some(path) = std::env::var_os("RAW2DNG_SAMPLE") else {
            return;
        };
        let mut raw = decode(std::path::Path::new(&path));
        let active = Rect::new(Point::new(3, 5), Dim2::new(raw.width - 8, raw.height - 12));
        raw.active_area = Some(active);
        raw.crop_area = Some(Rect::new(
            Point::new(active.p.x + 5, active.p.y + 7),
            Dim2::new(active.d.w - 12, active.d.h - 14),
        ));
        assert_same_pixels(raw);
    }

    #[test]
    fn unsupported_paths_fall_back_to_generic_development() {
        let Some(path) = std::env::var_os("RAW2DNG_SAMPLE") else {
            return;
        };
        let mut raw = decode(std::path::Path::new(&path));
        assert!(supports_banded_development(&raw));
        raw.cpp = 3;
        assert!(!supports_banded_development(&raw));
        raw.cpp = 1;
        raw.fuji_rotation_width = Some(100);
        assert!(!supports_banded_development(&raw));
    }
}
