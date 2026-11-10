//! JNI entry points for `com.wilinz.wxscanlive.core.NativeScanner`.
//!
//! Camera frames on Android reach the scanner from Kotlin without passing
//! through Dart. A caller on this path either creates its own scanner here or
//! is handed the handle of one the Dart side already holds — the handles are
//! the same numbers on both paths, since they name entries in one table inside
//! the library rather than anything belonging to either binding.
//!
//! Results are returned as a JSON document because that is the cheapest way to
//! move a small variable-length structure across JNI and then a method channel.
//! Serialization therefore lives here, in the binding layer, rather than in the
//! C ABI.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;

use wxscan::frame::upright_gray;
use wxscan_ffi::WxScanScannerId;

/// Frames above this many pixels are downscaled proportionally before
/// detection.
///
/// Huawei HALs commonly ignore the 16:9 request and deliver a 1:1 frame
/// (1728x1728 for a 1280x720 target). Passing that through costs ~100 ms
/// per frame in the CNN, and ~300–500 ms when the detector finds a candidate
/// that turns out to be false — which the camera side sees as `busy`, skips
/// ten frames in a row, and the UI freezes between marker updates.
///
/// 1 million pixels is roughly 1000x1000, plenty for a QR code and ~3x less
/// work than 1728x1728. If `scan_upright` inside the upstream `wxscan`
/// crate turns out to resize to its own input shape (e.g. 640x640 for a
/// YOLO-nano detector), then this value should be set to match, so the two
/// resizes collapse into one.
const MAX_INPUT_PIXELS: usize = 1_000_000;

/// Downscales a dense grayscale frame proportionally if it exceeds
/// [MAX_INPUT_PIXELS].
///
/// The buffer must be exactly `w * h` bytes — the shape [upright_gray] is
/// contracted to produce. Anything else comes back as an empty buffer with
/// zero dimensions, which [scan_json] turns into the empty document. That is
/// deliberately a "no result" rather than a fallback to the oversized frame:
/// falling back would re-introduce the freeze this function exists to
/// prevent.
fn maybe_downscale(gray: Vec<u8>, w: usize, h: usize) -> (Vec<u8>, usize, usize) {
    if w == 0 || h == 0 {
        return (Vec::new(), 0, 0);
    }
    if gray.len() != w * h {
        return (Vec::new(), 0, 0);
    }
    if w.saturating_mul(h) <= MAX_INPUT_PIXELS {
        return (gray, w, h);
    }

    let scale = (MAX_INPUT_PIXELS as f64 / (w * h) as f64).sqrt();
    let dst_w = ((w as f64 * scale).round() as usize).max(1);
    let dst_h = ((h as f64 * scale).round() as usize).max(1);

    let resized = bilinear_resize(&gray, w, h, dst_w, dst_h);
    (resized, dst_w, dst_h)
}

/// Bilinear downscale with center-aligned sampling.
///
/// Center-aligned: dst pixel `dx` samples src at `(dx + 0.5) * src_w/dst_w - 0.5`.
/// This avoids aliasing (skipping source rows, which corner-aligned mapping
/// introduces on downscale) and matches what `image::imageops::resize` does
/// with `FilterType::Triangle`.
///
/// f32 rather than f64: on ARM f64 arithmetic is slower, and f32 precision
/// (24 bits of mantissa) is more than enough for a 0–255 intensity range
/// with a 4-tap bilinear kernel.
///
/// No SIMD. A QR code is scanned at ≤ 8 fps (the camera side's throttle), so
/// 3–10 ms for a 3M-pixel frame is invisible — the CNN takes 60–80 ms for
/// the same frame regardless.
fn bilinear_resize(
    src: &[u8],
    src_w: usize,
    src_h: usize,
    dst_w: usize,
    dst_h: usize,
) -> Vec<u8> {
    let mut dst = vec![0u8; dst_w * dst_h];
    if src_w == 0 || src_h == 0 || dst_w == 0 || dst_h == 0 {
        return dst;
    }
    let x_ratio = src_w as f32 / dst_w as f32;
    let y_ratio = src_h as f32 / dst_h as f32;
    let last_x = src_w - 1;
    let last_y = src_h - 1;

    for dy in 0..dst_h {
        let sy = (dy as f32 + 0.5) * y_ratio - 0.5;
        let (y0, fy) = if sy <= 0.0 {
            (0usize, 0.0f32)
        } else if sy >= last_y as f32 {
            (last_y, 0.0f32)
        } else {
            let y = sy as usize;
            (y, sy - y as f32)
        };
        let y1 = (y0 + 1).min(last_y);
        let row0 = y0 * src_w;
        let row1 = y1 * src_w;

        for dx in 0..dst_w {
            let sx = (dx as f32 + 0.5) * x_ratio - 0.5;
            let (x0, fx) = if sx <= 0.0 {
                (0usize, 0.0f32)
            } else if sx >= last_x as f32 {
                (last_x, 0.0f32)
            } else {
                let x = sx as usize;
                (x, sx - x as f32)
            };
            let x1 = (x0 + 1).min(last_x);

            let v00 = src[row0 + x0] as f32;
            let v01 = src[row0 + x1] as f32;
            let v10 = src[row1 + x0] as f32;
            let v11 = src[row1 + x1] as f32;

            let v = v00 * (1.0 - fx) * (1.0 - fy)
                + v01 * fx * (1.0 - fy)
                + v10 * (1.0 - fx) * fy
                + v11 * fx * fy;

            dst[dy * dst_w + dx] = v.round() as u8;
        }
    }
    dst
}

/// A `jlong` as a handle, or 0 if it cannot be one.
///
/// `usize` is 32 bits on armeabi-v7a, so a plain `as` cast would truncate —
/// and truncation is worse than rejection here: `0x1_0000_0001` would come
/// down to `1` and hit a live scanner that the caller never named. Anything
/// that does not fit names nothing, which is what 0 means.
fn handle_of(v: jlong) -> WxScanScannerId {
    WxScanScannerId::try_from(v).unwrap_or(0)
}

/// Drops a Java exception this side has decided to handle itself.
///
/// jni-rs leaves a throwable pending when it reports `JavaException`, and every
/// entry point here answers with an empty result rather than propagating one.
/// Leaving it pending would be undefined behaviour at the next JNI call and an
/// abort under CheckJNI, and it would surface at some unrelated boundary later.
fn clear_pending(env: &mut JNIEnv) {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
}

/// Create a scanner from model buffers, returning an opaque handle, or 0 if a
/// model fails to load. Passing empty arrays selects the mode without models,
/// which still decodes but detects small or distant symbols less reliably.
///
/// The handle must be released with [`Java_com_wilinz_wxscanlive_core_NativeScanner_nativeRelease`].
#[no_mangle]
pub extern "system" fn Java_com_wilinz_wxscanlive_core_NativeScanner_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    detect: JByteArray,
    sr: JByteArray,
) -> jlong {
    // A copy that fails leaves a Java exception pending, and this side answers
    // with the mode without models rather than propagating one — so the
    // throwable has to go, or it surfaces at some unrelated JNI call later.
    let read = |env: &mut JNIEnv, a: &JByteArray| -> Vec<u8> {
        match env.convert_byte_array(a) {
            Ok(v) => v,
            Err(_) => {
                clear_pending(env);
                Vec::new()
            }
        }
    };
    let d = read(&mut env, &detect);
    let s = read(&mut env, &sr);
    let id = unsafe {
        wxscan_ffi::wxscan_scanner_new(
            if d.is_empty() { std::ptr::null() } else { d.as_ptr() },
            d.len(),
            if s.is_empty() { std::ptr::null() } else { s.as_ptr() },
            s.len(),
        )
    };
    id as jlong
}

/// Create a scanner from model files on disk, returning an opaque handle.
///
/// The weights are read by the library, so a megabyte of them never crosses
/// JNI — and Kotlin does not need a file API of its own to offer this. A null
/// path means that network is simply absent, as an empty array is to
/// [`Java_com_wilinz_wxscanlive_core_NativeScanner_nativeCreate`].
///
/// **A failure comes back as minus the `WxScanStatus` that explains it** — -1
/// for a path that is not UTF-8, -2 for a file that will not open, -4 for one
/// that reads but is not weights this build can load. Handles are positive, so
/// the sign is enough to tell them apart, and it saves an out-parameter across
/// a boundary that has no good way to carry one. Kotlin turns it back into an
/// exception at once; nothing outside this pair of functions sees the number.
///
/// The handle must be released with [`Java_com_wilinz_wxscanlive_core_NativeScanner_nativeRelease`].
#[no_mangle]
pub extern "system" fn Java_com_wilinz_wxscanlive_core_NativeScanner_nativeCreatePath(
    mut env: JNIEnv,
    _class: JClass,
    detect: JString,
    sr: JString,
) -> jlong {
    // A path that will not come across is treated as absent rather than
    // propagated: the throwable has to go either way, or it surfaces at some
    // unrelated JNI call later.
    let read = |env: &mut JNIEnv, p: &JString| -> Option<std::ffi::CString> {
        if p.is_null() {
            return None;
        }
        match env.get_string(p) {
            Ok(v) => std::ffi::CString::new(v.to_string_lossy().as_ref()).ok(),
            Err(_) => {
                clear_pending(env);
                None
            }
        }
    };
    let d = read(&mut env, &detect);
    let s = read(&mut env, &sr);

    let mut status = wxscan_ffi::WxScanStatus::Ok;
    let id = unsafe {
        wxscan_ffi::wxscan_scanner_new_path(
            d.as_ref().map_or(std::ptr::null(), |c| c.as_ptr()),
            s.as_ref().map_or(std::ptr::null(), |c| c.as_ptr()),
            &mut status,
        )
    };
    if id == 0 {
        return -(status as jlong);
    }
    id as jlong
}

/// Take a reference to a scanner the caller did not create, returning the same
/// handle, or 0 if it names no scanner.
///
/// This is how a camera binding borrows the scanner a Dart application already
/// holds: one scanner, one set of weights in memory, and it stays alive
/// whichever side lets go of it first. Match it with
/// [`Java_com_wilinz_wxscanlive_core_NativeScanner_nativeRelease`].
#[no_mangle]
pub extern "system" fn Java_com_wilinz_wxscanlive_core_NativeScanner_nativeRetain(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    wxscan_ffi::wxscan_scanner_retain(handle_of(handle)) as jlong
}

/// Whether the scanner a handle names has its detector network loaded.
///
/// A borrowed scanner was built by whoever lent it, so this side cannot know
/// from the weights it was passed — it was passed none. Rather than guess, it
/// asks. Returns false for a handle that names no scanner.
#[no_mangle]
pub extern "system" fn Java_com_wilinz_wxscanlive_core_NativeScanner_nativeHasDetector(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    u8::from(wxscan_ffi::wxscan_scanner_has_detector(handle_of(handle)) != 0)
}

/// Give up a reference. The scanner goes when its last holder does.
#[no_mangle]
pub extern "system" fn Java_com_wilinz_wxscanlive_core_NativeScanner_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        wxscan_ffi::wxscan_scanner_release(handle_of(handle));
    }
}

/// Scan one camera frame.
///
/// * `handle` comes from [`Java_com_wilinz_wxscanlive_core_NativeScanner_nativeCreate`].
/// * `y_plane` is the Y plane of the frame, `row_stride` bytes per row.
/// * `rotation` is the clockwise angle in degrees needed to bring the frame upright.
/// * `mirror` mirrors the returned x coordinates without mirroring the frame.
///
/// Returns the JSON described in [`scan_json`]. Invalid input yields the empty
/// document rather than an exception.
#[no_mangle]
pub extern "system" fn Java_com_wilinz_wxscanlive_core_NativeScanner_nativeScanFrame(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    y_plane: JByteArray,
    width: jint,
    height: jint,
    row_stride: jint,
    rotation: jint,
    mirror: jboolean,
) -> jstring {
    let json = match env.convert_byte_array(&y_plane) {
        Ok(bytes) => scan_json(
            handle_of(handle),
            &bytes,
            width,
            height,
            row_stride,
            rotation,
            mirror != 0,
        ),
        Err(_) => {
            // The copy failed, which for jni-rs means a Java exception is
            // pending — an OutOfMemoryError, most likely. Calling any further
            // JNI function with one pending is undefined behaviour, and
            // Android's CheckJNI aborts the process for it. `new_string`
            // below is exactly such a call.
            clear_pending(&mut env);
            empty_json()
        }
    };
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Confirm the library is loaded, called once after `System.loadLibrary`.
#[no_mangle]
pub extern "system" fn Java_com_wilinz_wxscanlive_core_NativeScanner_nativePing(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string("wxscan")
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Scan a frame and serialize the outcome.
///
/// ```text
/// {"w":720,"h":1280,
///  "results":[{"text":"...","charset":"UTF-8","version":3,"ecLevel":"L",
///              "charsetMode":"BYTE","binaryMethod":0,
///              "points":[x0,y0,x1,y1,x2,y2,x3,y3]}],
///  "candidates":[[x0,y0,x1,y1,x2,y2,x3,y3]]}
/// ```
///
/// A non-empty `candidates` with an empty `results` means a symbol was located
/// but not decoded, which the caller can use to zoom in.
///
/// A handle that names no scanner yields the empty document, as does a frame
/// whose dimensions do not match its buffer.
///
/// The reported `w` and `h` are the dimensions **after** any downscale — the
/// detector and decoder work in those coordinates, so the caller's markers
/// and hit-testing agree with them without any extra scaling on the caller's
/// side. Downscaling happens only when the input exceeds [MAX_INPUT_PIXELS];
/// see [maybe_downscale].
fn scan_json(
    handle: WxScanScannerId,
    bytes: &[u8],
    width: jint,
    height: jint,
    row_stride: jint,
    rotation: jint,
    mirror: bool,
) -> String {
    if width <= 0 || height <= 0 || row_stride < width {
        return empty_json();
    }
    // A handle that names nothing — released, or never valid — is an empty
    // document, not a dereference.
    let Some(scanner) = wxscan_ffi::lookup_scanner(handle) else {
        return empty_json();
    };
    let (w, h, stride) = (width as usize, height as usize, row_stride as usize);
    // Checked, because `usize` is 32 bits on armeabi-v7a: `stride * h` for a
    // frame claiming 65536 rows of 65536 bytes wraps to zero there, the length
    // check passes, and `upright_gray` reads past the end of a short array.
    // This is the only bounds check standing between Java and that slice.
    let Some(needed) = stride.checked_mul(h) else {
        return empty_json();
    };
    if bytes.len() < needed {
        return empty_json();
    }

    // upright_gray does rotation, packing (removes row padding) and, on
    // rotated frames, dimension swap. Its output is dense: `ow * oh` bytes,
    // one per pixel.
    let (gray, ow, oh) = upright_gray(bytes, w, h, stride, rotation);

    // Downscale if the resulting dense frame is bigger than MAX_INPUT_PIXELS.
    // This is the only scaling the scanner performs on the camera path; on
    // Huawei the HAL ignores the 16:9 request and delivers 1:1 frames, and a
    // raw 1728x1728 (3M px) is both slow (100–500 ms per frame) and needless
    // for a QR code. The JSON below reports the downscaled dimensions, so the
    // caller's geometry matches what the detector actually saw.
    let (gray, ow, oh) = maybe_downscale(gray, ow, oh);
    if ow == 0 || oh == 0 || gray.len() < ow * oh {
        return empty_json();
    }

    let (results, candidates) = scanner.scan_upright(&gray, ow, oh);

    let flip = |x: f32| if mirror { ow as f32 - x } else { x };
    let quad = |pts: &[(f32, f32); 4]| {
        pts.iter()
            .map(|(x, y)| format!("{:.2},{:.2}", flip(*x), y))
            .collect::<Vec<_>>()
            .join(",")
    };

    let items: Vec<String> = results
        .iter()
        .map(|r| {
            format!(
                concat!(
                    "{{\"text\":\"{}\",\"charset\":\"{}\",\"version\":{},\"ecLevel\":\"{}\",",
                    "\"charsetMode\":\"{}\",\"binaryMethod\":{},\"points\":[{}]}}"
                ),
                escape(&decode_text(&r.bytes, &r.charset)),
                escape(&r.charset),
                r.qrcode_version,
                escape(&r.ec_level),
                escape(&r.charset_mode),
                r.binary_method,
                quad(&r.points),
            )
        })
        .collect();
    let cands: Vec<String> = candidates.iter().map(|q| format!("[{}]", quad(q))).collect();

    format!(
        "{{\"w\":{ow},\"h\":{oh},\"results\":[{}],\"candidates\":[{}]}}",
        items.join(","),
        cands.join(",")
    )
}

/// The document for a frame with nothing in it, also used on invalid input.
fn empty_json() -> String {
    "{\"w\":0,\"h\":0,\"results\":[],\"candidates\":[]}".to_string()
}

/// Interpret payload bytes according to the reported charset. The decoder
/// reports the encoding without converting, so GB2312 is decoded here; GBK is a
/// superset of it and tolerates a few out-of-range bytes.
fn decode_text(bytes: &[u8], charset: &str) -> String {
    if charset.eq_ignore_ascii_case("GB2312") {
        let (cow, _, _) = encoding_rs::GBK.decode(bytes);
        return cow.into_owned();
    }
    String::from_utf8_lossy(bytes).into_owned()
}

fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}