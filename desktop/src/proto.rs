//! Wire format: [u16 len][u8 type][payload], big-endian. `len` covers type + payload.
//! Must stay in sync with android/app/src/main/java/dev/glide/Proto.kt.

use std::io::{self, Read, Write};

pub const VERSION: u8 = 3;
pub const PORT: u16 = 24242;
pub const MAX_CLIPBOARD: usize = 60_000;
pub const PING_EVERY: std::time::Duration = std::time::Duration::from_secs(1);
pub const TIMEOUT: std::time::Duration = std::time::Duration::from_secs(4);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Edge {
    Left = 0,
    Right = 1,
    Top = 2,
    Bottom = 3,
}

impl Edge {
    pub fn from_u8(v: u8) -> io::Result<Edge> {
        Ok(match v {
            0 => Edge::Left,
            1 => Edge::Right,
            2 => Edge::Top,
            3 => Edge::Bottom,
            _ => return Err(bad("edge")),
        })
    }

    pub fn opposite(self) -> Edge {
        match self {
            Edge::Left => Edge::Right,
            Edge::Right => Edge::Left,
            Edge::Top => Edge::Bottom,
            Edge::Bottom => Edge::Top,
        }
    }
}

#[derive(Debug, PartialEq)]
pub enum Msg {
    /// Screen size in Mac points / Android dp, which are close enough to treat as one unit.
    Hello { version: u8, width: u16, height: u16, name: String },
    /// Cursor enters target through `edge`, at `pos` (0..=65535) along that edge.
    Enter { edge: Edge, pos: u16 },
    Move { dx: i16, dy: i16 },
    /// button: 0 left, 1 right, 2 middle.
    Button { button: u8, down: bool },
    Scroll { dx: i16, dy: i16 },
    /// `usage` is a USB HID keyboard usage (page 0x07). Modifiers are sent as their own keys (0xE0..=0xE7).
    Key { usage: u16, down: bool },
    /// Cursor left target through `edge`, at `pos` along that edge.
    Leave { edge: Edge, pos: u16 },
    /// Heartbeat, sent by both sides every second; silence for `TIMEOUT` means the peer is gone.
    Ping,
    /// Clipboard text, either way: host sends it before `Enter` when it changed, phone sends it on Cmd+C/X.
    /// At most `MAX_CLIPBOARD` bytes.
    Clipboard(String),
    /// Host clipboard holds files instead of text (sent before `Enter`); Cmd+V on the phone asks for them.
    ClipFiles { count: u16, total: u64 },
    /// Phone → host: Cmd+V while `ClipFiles` was announced. Host pushes the files on a transfer connection.
    PasteFiles,
    /// Host → phone before `Enter`: these files are being dragged across; the phone shows them on the cursor.
    DragFiles { count: u16, total: u64 },
    /// Phone → host: user shared files to the Mac. Host pulls them with `Pull { id }`.
    Offer { id: u32, count: u16, total: u64 },
    /// Host → phone, before Hello, when the host does not know this phone yet.
    PairStart,
    /// Phone → host: it scanned this Mac's pairing QR. HMAC-SHA256(secret, host cert ‖ phone cert).
    PairProof([u8; 32]),
    /// Phone → host: no QR scanned; compare the 6-digit code on both screens instead.
    PairAsk,
    /// Phone → host, right after Hello and whenever the user rearranges: the phone sits on `edge` of the
    /// Mac's screen, starting `offset` points along that edge and covering `length` points of it.
    Layout { edge: Edge, offset: i32, length: u16 },

    // Transfer connection only (a separate TLS connection opened by the host). After each `FileHeader`
    // come exactly `size` raw bytes, outside framing. The receiver answers `FileDone` once all are saved.
    /// Host → phone: the files that follow are a push.
    Push { count: u16, total: u64 },
    /// Host → phone: send the files of offer `id`; the phone answers with `Push` and the files.
    Pull { id: u32 },
    /// `name` is a relative path with '/' separators; receivers must sanitize it.
    FileHeader { size: u64, name: String },
    FileDone,
}

fn bad(what: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, format!("bad {what}"))
}

impl Msg {
    pub fn write_to(&self, w: &mut impl Write) -> io::Result<()> {
        let mut b = Vec::with_capacity(16);
        match self {
            Msg::Hello { version, width, height, name } => {
                b.push(1);
                b.push(*version);
                b.extend(width.to_be_bytes());
                b.extend(height.to_be_bytes());
                b.extend(name.as_bytes());
            }
            Msg::Enter { edge, pos } => {
                b.push(2);
                b.push(*edge as u8);
                b.extend(pos.to_be_bytes());
            }
            Msg::Move { dx, dy } => {
                b.push(3);
                b.extend(dx.to_be_bytes());
                b.extend(dy.to_be_bytes());
            }
            Msg::Button { button, down } => {
                b.extend([4, *button, *down as u8]);
            }
            Msg::Scroll { dx, dy } => {
                b.push(5);
                b.extend(dx.to_be_bytes());
                b.extend(dy.to_be_bytes());
            }
            Msg::Key { usage, down } => {
                b.push(6);
                b.extend(usage.to_be_bytes());
                b.push(*down as u8);
            }
            Msg::Leave { edge, pos } => {
                b.push(7);
                b.push(*edge as u8);
                b.extend(pos.to_be_bytes());
            }
            Msg::Ping => b.push(8),
            Msg::Clipboard(text) => {
                b.push(9);
                b.extend(text.as_bytes());
            }
            Msg::ClipFiles { count, total } => {
                b.push(10);
                b.extend(count.to_be_bytes());
                b.extend(total.to_be_bytes());
            }
            Msg::PasteFiles => b.push(11),
            Msg::DragFiles { count, total } => {
                b.push(12);
                b.extend(count.to_be_bytes());
                b.extend(total.to_be_bytes());
            }
            Msg::Offer { id, count, total } => {
                b.push(13);
                b.extend(id.to_be_bytes());
                b.extend(count.to_be_bytes());
                b.extend(total.to_be_bytes());
            }
            Msg::Layout { edge, offset, length } => {
                b.push(14);
                b.push(*edge as u8);
                b.extend(offset.to_be_bytes());
                b.extend(length.to_be_bytes());
            }
            Msg::PairStart => b.push(15),
            Msg::PairProof(mac) => {
                b.push(16);
                b.extend(mac);
            }
            Msg::PairAsk => b.push(17),
            Msg::Push { count, total } => {
                b.push(20);
                b.extend(count.to_be_bytes());
                b.extend(total.to_be_bytes());
            }
            Msg::Pull { id } => {
                b.push(21);
                b.extend(id.to_be_bytes());
            }
            Msg::FileHeader { size, name } => {
                b.push(22);
                b.extend(size.to_be_bytes());
                b.extend(name.as_bytes());
            }
            Msg::FileDone => b.push(23),
        }
        if b.len() > u16::MAX as usize {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "message too large"));
        }
        let mut frame = (b.len() as u16).to_be_bytes().to_vec();
        frame.extend(b);
        w.write_all(&frame)
    }

    pub fn read_from(r: &mut impl Read) -> io::Result<Msg> {
        let mut len = [0u8; 2];
        r.read_exact(&mut len)?;
        let mut b = vec![0u8; u16::from_be_bytes(len) as usize];
        r.read_exact(&mut b)?;
        let (&t, p) = b.split_first().ok_or_else(|| bad("frame"))?;
        let u16_at = |i: usize| -> io::Result<u16> {
            p.get(i..i + 2).map(|s| u16::from_be_bytes([s[0], s[1]])).ok_or_else(|| bad("frame"))
        };
        let u8_at = |i: usize| -> io::Result<u8> { p.get(i).copied().ok_or_else(|| bad("frame")) };
        let u32_at = |i: usize| -> io::Result<u32> {
            p.get(i..i + 4).map(|s| u32::from_be_bytes(s.try_into().unwrap())).ok_or_else(|| bad("frame"))
        };
        let u64_at = |i: usize| -> io::Result<u64> {
            p.get(i..i + 8).map(|s| u64::from_be_bytes(s.try_into().unwrap())).ok_or_else(|| bad("frame"))
        };
        Ok(match t {
            1 => Msg::Hello {
                version: u8_at(0)?,
                width: u16_at(1)?,
                height: u16_at(3)?,
                name: String::from_utf8_lossy(p.get(5..).unwrap_or_default()).into_owned(),
            },
            2 => Msg::Enter { edge: Edge::from_u8(u8_at(0)?)?, pos: u16_at(1)? },
            3 => Msg::Move { dx: u16_at(0)? as i16, dy: u16_at(2)? as i16 },
            4 => Msg::Button { button: u8_at(0)?, down: u8_at(1)? != 0 },
            5 => Msg::Scroll { dx: u16_at(0)? as i16, dy: u16_at(2)? as i16 },
            6 => Msg::Key { usage: u16_at(0)?, down: u8_at(2)? != 0 },
            7 => Msg::Leave { edge: Edge::from_u8(u8_at(0)?)?, pos: u16_at(1)? },
            8 => Msg::Ping,
            9 => Msg::Clipboard(String::from_utf8_lossy(p).into_owned()),
            10 => Msg::ClipFiles { count: u16_at(0)?, total: u64_at(2)? },
            11 => Msg::PasteFiles,
            12 => Msg::DragFiles { count: u16_at(0)?, total: u64_at(2)? },
            13 => Msg::Offer { id: u32_at(0)?, count: u16_at(4)?, total: u64_at(6)? },
            14 => Msg::Layout { edge: Edge::from_u8(u8_at(0)?)?, offset: u32_at(1)? as i32, length: u16_at(5)? },
            15 => Msg::PairStart,
            16 => Msg::PairProof(p.get(..32).and_then(|s| s.try_into().ok()).ok_or_else(|| bad("frame"))?),
            17 => Msg::PairAsk,
            20 => Msg::Push { count: u16_at(0)?, total: u64_at(2)? },
            21 => Msg::Pull { id: u32_at(0)? },
            22 => Msg::FileHeader {
                size: u64_at(0)?,
                name: String::from_utf8_lossy(p.get(8..).unwrap_or_default()).into_owned(),
            },
            23 => Msg::FileDone,
            _ => return Err(bad("type")),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let msgs = [
            Msg::Hello { version: VERSION, width: 1080, height: 2400, name: "Pixel".into() },
            Msg::Enter { edge: Edge::Left, pos: 32768 },
            Msg::Move { dx: -5, dy: 300 },
            Msg::Button { button: 1, down: true },
            Msg::Scroll { dx: 0, dy: -12 },
            Msg::Key { usage: 0xE3, down: false },
            Msg::Leave { edge: Edge::Left, pos: 65535 },
            Msg::Ping,
            Msg::Clipboard("héllo\nworld".into()),
            Msg::ClipFiles { count: 3, total: 5_000_000_000 },
            Msg::PasteFiles,
            Msg::DragFiles { count: 1, total: 42 },
            Msg::Offer { id: 7, count: 2, total: u64::MAX },
            Msg::Layout { edge: Edge::Right, offset: -120, length: 915 },
            Msg::PairStart,
            Msg::PairProof([7; 32]),
            Msg::PairAsk,
            Msg::Push { count: 2, total: 9 },
            Msg::Pull { id: 0xDEADBEEF },
            Msg::FileHeader { size: 4_294_967_296, name: "Trip/clip 1.mov".into() },
            Msg::FileDone,
        ];
        let mut buf = Vec::new();
        for m in &msgs {
            m.write_to(&mut buf).unwrap();
        }
        let mut r = &buf[..];
        for m in &msgs {
            assert_eq!(&Msg::read_from(&mut r).unwrap(), m);
        }
        assert!(r.is_empty());
    }

    #[test]
    fn rejects_garbage() {
        assert!(Msg::read_from(&mut &[0u8, 1, 99][..]).is_err());
        assert!(Msg::read_from(&mut &[0u8, 2, 3, 0][..]).is_err());
    }
}
