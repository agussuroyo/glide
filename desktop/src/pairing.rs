//! QR pairing. The Mac shows its key fingerprint and a one-time secret; the phone pins the fingerprint and
//! proves it scanned the code with HMAC(secret, host cert ‖ phone cert). Only someone who saw this Mac's
//! screen knows the secret, so no code comparison is needed.

use hmac::{Hmac, KeyInit, Mac};
use qrcode::{Color, QrCode};
use sha2::Sha256;
use std::sync::Mutex;
use std::time::{Duration, Instant};

const VALID_FOR: Duration = Duration::from_secs(10 * 60);

static SECRET: Mutex<Option<([u8; 16], Instant)>> = Mutex::new(None);

/// Starts a pairing window and returns the QR payload: `glide1:<fingerprint>:<secret>:<mac name>`.
pub fn new_payload(fingerprint: &str, name: &str) -> String {
    let mut secret = [0u8; 16];
    // ponytail: /dev/urandom instead of a rand crate; macOS always has it.
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| std::io::Read::read_exact(&mut f, &mut secret))
        .expect("system random source");
    *SECRET.lock().unwrap() = Some((secret, Instant::now()));
    let hex: String = secret.iter().map(|b| format!("{b:02x}")).collect();
    format!("glide1:{fingerprint}:{hex}:{name}")
}

/// What the phone computes; the Mac side checks it with `verify` instead (constant-time).
#[cfg(test)]
fn proof(secret: &[u8], host_der: &[u8], phone_der: &[u8]) -> [u8; 32] {
    let mut m = <Hmac<Sha256> as KeyInit>::new_from_slice(secret).unwrap();
    m.update(host_der);
    m.update(phone_der);
    m.finalize().into_bytes().into()
}

/// True while a pairing QR is showing (or was shown in the last 10 minutes and not used yet).
pub fn active() -> bool {
    SECRET.lock().unwrap().is_some_and(|(_, at)| at.elapsed() <= VALID_FOR)
}

/// Checks a phone's proof against the open pairing window (constant-time). A match closes the window.
pub fn verify(proof: &[u8; 32], host_der: &[u8], phone_der: &[u8]) -> bool {
    let mut guard = SECRET.lock().unwrap();
    let Some((secret, at)) = *guard else { return false };
    if at.elapsed() > VALID_FOR {
        *guard = None;
        return false;
    }
    let mut m = <Hmac<Sha256> as KeyInit>::new_from_slice(&secret).unwrap();
    m.update(host_der);
    m.update(phone_der);
    let ok = m.verify_slice(proof).is_ok();
    if ok {
        *guard = None;
    }
    ok
}

/// QR as a 24-bit BMP, `scale` pixels per module with the standard 4-module quiet zone.
/// BMP because NSImage reads it natively and it needs no image library.
pub fn bmp(payload: &str, scale: usize) -> Vec<u8> {
    let code = QrCode::new(payload.as_bytes()).expect("payload fits in a QR code");
    let n = code.width();
    let size = (n + 8) * scale;
    let row = (size * 3).div_ceil(4) * 4;
    let mut out = Vec::with_capacity(54 + row * size);
    let le32 = |v: usize| (v as u32).to_le_bytes();
    out.extend(b"BM");
    out.extend(le32(54 + row * size));
    out.extend([0; 4]);
    out.extend(le32(54));
    out.extend(le32(40));
    out.extend(le32(size));
    out.extend(le32(size));
    out.extend([1, 0, 24, 0]);
    out.extend([0; 24]);
    for y in (0..size).rev() {
        // BMP rows go bottom-up.
        let start = out.len();
        for x in 0..size {
            let (mx, my) = ((x / scale) as isize - 4, (y / scale) as isize - 4);
            let dark = mx >= 0 && my >= 0 && (mx as usize) < n && (my as usize) < n
                && code[(mx as usize, my as usize)] == Color::Dark;
            let v = if dark { 0 } else { 255 };
            out.extend([v, v, v]);
        }
        out.resize(start + row, 0);
    }
    out
}

/// QR drawn with half-block characters for `glide pair` in a terminal. Colours are forced to black on
/// white so it scans whether the terminal theme is light or dark.
pub fn terminal(payload: &str) -> String {
    let code = QrCode::new(payload.as_bytes()).expect("payload fits in a QR code");
    let n = code.width() as isize;
    let dark = |x: isize, y: isize| x >= 0 && y >= 0 && x < n && y < n && code[(x as usize, y as usize)] == Color::Dark;
    let mut s = String::new();
    for y in (-2..n + 2).step_by(2) {
        s.push_str("\x1b[30;107m");
        for x in -2..n + 2 {
            s.push(match (dark(x, y), dark(x, y + 1)) {
                (true, true) => '█',
                (true, false) => '▀',
                (false, true) => '▄',
                (false, false) => ' ',
            });
        }
        s.push_str("\x1b[0m\n");
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    // Same vector is checked on Android (Pairing.proof); both sides must agree.
    #[test]
    fn proof_vector() {
        let secret: Vec<u8> = (0..16).collect();
        let hex: String = proof(&secret, b"host", b"phone").iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hex, "e66dae59d30729efea2bd911c0ff8fad8e2ac754d95d29c2f4b1314ff5cfecdb");
    }

    #[test]
    fn verify_is_one_shot() {
        let payload = new_payload("ab", "Mac");
        let secret: Vec<u8> = (0..16).map(|i| u8::from_str_radix(&payload.split(':').nth(2).unwrap()[i * 2..i * 2 + 2], 16).unwrap()).collect();
        let p = proof(&secret, b"h", b"p");
        assert!(!verify(&[0; 32], b"h", b"p"));
        assert!(verify(&p, b"h", b"p"));
        assert!(!verify(&p, b"h", b"p")); // window closed after use
    }

    #[test]
    fn bmp_has_the_right_shape() {
        let b = bmp("glide1:x", 4);
        assert_eq!(&b[..2], b"BM");
        assert_eq!(u32::from_le_bytes(b[2..6].try_into().unwrap()) as usize, b.len());
    }
}
