//! File transfer over its own TLS connection, so a multi-GB copy never delays the mouse.
//! Files are streamed with a fixed buffer: memory use is the same for 1 KB or 50 GB.

use crate::net::{self, Link};
use crate::proto::Msg;
use std::fs::File;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

const BUF: usize = 256 * 1024;
/// The phone may need a while to finish writing a huge file to storage before it answers.
const DONE_TIMEOUT: Duration = Duration::from_secs(120);

/// Top-level file sizes, for the "3 files, 2.1 GB" hint. Folders count as one item of unknown size (0).
pub fn summary(paths: &[PathBuf]) -> (u16, u64) {
    let total = paths.iter().filter_map(|p| std::fs::metadata(p).ok()).filter(|m| m.is_file()).map(|m| m.len()).sum();
    (paths.len().min(u16::MAX as usize) as u16, total)
}

/// Every regular file under `paths`, with its path relative to the item the user picked.
/// Symlinks are skipped so a link cannot pull in files from elsewhere on the disk.
fn collect(paths: &[PathBuf]) -> io::Result<Vec<(PathBuf, String, u64)>> {
    fn walk(path: &Path, rel: String, out: &mut Vec<(PathBuf, String, u64)>) -> io::Result<()> {
        let meta = std::fs::symlink_metadata(path)?;
        if meta.is_file() {
            out.push((path.to_path_buf(), rel, meta.len()));
        } else if meta.is_dir() {
            let mut entries: Vec<_> = std::fs::read_dir(path)?.filter_map(Result::ok).collect();
            entries.sort_by_key(|e| e.file_name());
            for e in entries {
                let name = e.file_name().to_string_lossy().into_owned();
                if name == ".DS_Store" {
                    continue;
                }
                walk(&e.path(), format!("{rel}/{name}"), out)?;
            }
        }
        Ok(())
    }
    let mut out = Vec::new();
    for p in paths {
        let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
        walk(p, name, &mut out)?;
    }
    Ok(out)
}

/// Sends files and folders to the phone. Blocks until the phone confirms it saved them.
pub fn push(link: &Link, paths: &[PathBuf]) -> io::Result<()> {
    let items = collect(paths)?;
    if items.is_empty() {
        return Err(io::Error::other("nothing to send (only regular files and folders are sent)"));
    }
    if items.len() > u16::MAX as usize {
        return Err(io::Error::other("more than 65535 files; send the folder in parts"));
    }
    let total: u64 = items.iter().map(|i| i.2).sum();
    let mut tls = net::connect(link)?;
    Msg::Push { count: items.len() as u16, total }.write_to(&mut tls)?;
    let mut progress = Progress::new("Sending to phone", total);
    let mut buf = vec![0u8; BUF];
    for (path, rel, size) in &items {
        Msg::FileHeader { size: *size, name: rel.clone() }.write_to(&mut tls)?;
        let mut f = File::open(path)?;
        copy_exact(&mut f, &mut tls, *size, &mut buf, &mut progress)?;
    }
    tls.flush()?;
    tls.sock.set_read_timeout(Some(DONE_TIMEOUT))?;
    match Msg::read_from(&mut tls)? {
        Msg::FileDone => {}
        _ => return Err(io::Error::other("phone did not confirm the files")),
    }
    progress.finish();
    let what = if items.len() == 1 { items[0].1.clone() } else { format!("{} files", items.len()) };
    net::notify(&format!("Sent {what} to {}", link.name));
    Ok(())
}

/// Fetches files the user shared from the phone into ~/Downloads.
pub fn pull(link: &Link, offer: u32) -> io::Result<()> {
    let mut tls = net::connect(link)?;
    Msg::Pull { id: offer }.write_to(&mut tls)?;
    tls.flush()?;
    let (count, total) = match Msg::read_from(&mut tls)? {
        Msg::Push { count, total } => (count, total),
        _ => return Err(io::Error::other("phone sent no files")),
    };
    let downloads = PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join("Downloads");
    let mut progress = Progress::new("Receiving from phone", total);
    let mut buf = vec![0u8; BUF];
    let mut saved = Vec::new();
    for _ in 0..count {
        let Msg::FileHeader { size, name } = Msg::read_from(&mut tls)? else {
            return Err(io::Error::other("expected a file"));
        };
        let dest = unique(&downloads, &safe_name(&name));
        let part = dest.with_file_name(format!(".{}.glidepart", dest.file_name().unwrap().to_string_lossy()));
        let result = File::create(&part)
            .and_then(|mut f| copy_exact(&mut tls, &mut f, size, &mut buf, &mut progress).and_then(|_| f.sync_all()))
            .and_then(|_| std::fs::rename(&part, &dest));
        if let Err(e) = result {
            let _ = std::fs::remove_file(&part); // never leave half a file behind
            return Err(e);
        }
        saved.push(dest);
    }
    Msg::FileDone.write_to(&mut tls)?;
    tls.flush()?;
    progress.finish();
    let what = match saved.as_slice() {
        [one] => one.file_name().unwrap().to_string_lossy().into_owned(),
        many => format!("{} files", many.len()),
    };
    net::notify(&format!("Saved {what} from {} to Downloads", link.name));
    Ok(())
}

/// Copies exactly `size` bytes. A source that ends early is an error, not a silently short file.
fn copy_exact(from: &mut impl Read, to: &mut impl Write, size: u64, buf: &mut [u8], p: &mut Progress) -> io::Result<()> {
    let mut left = size;
    while left > 0 {
        let want = buf.len().min(left as usize);
        let n = from.read(&mut buf[..want])?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "file ended early (changed while sending?)"));
        }
        to.write_all(&buf[..n])?;
        left -= n as u64;
        p.add(n as u64);
    }
    Ok(())
}

/// A file name from the other side, reduced to a plain name: no folders, no "..", nothing hidden.
fn safe_name(name: &str) -> String {
    let last = name.rsplit(['/', '\\']).next().unwrap_or("");
    let clean: String = last.chars().filter(|c| !c.is_control() && *c != ':').collect();
    let clean = clean.trim().trim_start_matches('.').to_string();
    if clean.is_empty() { "file".into() } else { clean }
}

/// `dir/name`, or `dir/name (2).ext` etc. if taken. Never overwrites.
fn unique(dir: &Path, name: &str) -> PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let p = Path::new(name);
    let stem = p.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let ext = p.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    (2..).map(|i| dir.join(format!("{stem} ({i}){ext}"))).find(|p| !p.exists()).unwrap()
}

struct Progress {
    label: &'static str,
    total: u64,
    done: u64,
    start: Instant,
    shown: Instant,
}

impl Progress {
    fn new(label: &'static str, total: u64) -> Self {
        let now = Instant::now();
        Progress { label, total, done: 0, start: now, shown: now }
    }

    fn add(&mut self, n: u64) {
        self.done += n;
        if self.shown.elapsed() >= Duration::from_millis(250) {
            self.shown = Instant::now();
            self.print();
        }
    }

    fn print(&self) {
        let secs = self.start.elapsed().as_secs_f64().max(0.001);
        let pct = if self.total == 0 { 100 } else { self.done * 100 / self.total };
        print!("\r{} {pct:>3}%  {} of {}  {}/s   ", self.label, size(self.done), size(self.total), size((self.done as f64 / secs) as u64));
        let _ = io::stdout().flush();
    }

    fn finish(&self) {
        self.print();
        println!();
    }
}

fn size(b: u64) -> String {
    match b {
        b if b >= 1 << 30 => format!("{:.2} GB", b as f64 / (1u64 << 30) as f64),
        b if b >= 1 << 20 => format!("{:.1} MB", b as f64 / (1u64 << 20) as f64),
        b if b >= 1 << 10 => format!("{:.0} KB", b as f64 / 1024.0),
        b => format!("{b} B"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_from_the_phone_cannot_escape_downloads() {
        assert_eq!(safe_name("../../.ssh/authorized_keys"), "authorized_keys");
        assert_eq!(safe_name("..\\..\\x.txt"), "x.txt");
        assert_eq!(safe_name(".."), "file");
        assert_eq!(safe_name(".hidden"), "hidden");
        assert_eq!(safe_name("IMG 1.jpg"), "IMG 1.jpg");
    }

    #[test]
    fn unique_never_overwrites() {
        let dir = std::env::temp_dir().join(format!("glide-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), "x").unwrap();
        std::fs::write(dir.join("a (2).txt"), "x").unwrap();
        assert_eq!(unique(&dir, "a.txt"), dir.join("a (3).txt"));
        assert_eq!(unique(&dir, "b"), dir.join("b"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    /// Real push to a paired phone, e.g. the emulator through `adb forward tcp:24242 tcp:24242`:
    /// GLIDE_E2E_ADDR=127.0.0.1:24242 GLIDE_E2E_FILES=/path/a,/path/dir cargo test e2e_push -- --ignored --nocapture
    #[test]
    #[ignore = "needs a paired phone"]
    fn e2e_push() {
        let addr = std::env::var("GLIDE_E2E_ADDR").unwrap().parse().unwrap();
        let paths: Vec<PathBuf> = std::env::var("GLIDE_E2E_FILES").unwrap().split(',').map(PathBuf::from).collect();
        let (fingerprint, name) = net::known().pop().expect("pair a phone first");
        let link = Link { addr, fingerprint, name, id: std::sync::Arc::new(net::identity().unwrap()) };
        push(&link, &paths).unwrap();
    }

    #[test]
    fn collect_walks_folders_and_skips_links() {
        let dir = std::env::temp_dir().join(format!("glide-walk-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("Trip/day 2")).unwrap();
        std::fs::write(dir.join("Trip/a.mov"), "12345").unwrap();
        std::fs::write(dir.join("Trip/day 2/b.jpg"), "1").unwrap();
        std::fs::write(dir.join("Trip/.DS_Store"), "x").unwrap();
        std::os::unix::fs::symlink("/etc/hosts", dir.join("Trip/link")).unwrap();
        let got: Vec<_> = collect(&[dir.join("Trip")]).unwrap().into_iter().map(|(_, r, s)| (r, s)).collect();
        assert_eq!(got, vec![("Trip/a.mov".into(), 5), ("Trip/day 2/b.jpg".into(), 1)]);
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
