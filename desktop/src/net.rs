//! Host networking: identity, pairing, discovery, and one TLS session to the target.

use crate::{clip, files, mac};
use crate::proto::{Msg, MAX_CLIPBOARD, PING_EVERY, PORT, TIMEOUT, VERSION};
use mdns_sd::{ServiceDaemon, ServiceEvent};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime};
use rustls::{ClientConfig, ClientConnection, DigitallySignedStruct, SignatureScheme, StreamOwned};
use sha2::{Digest, Sha256};
use std::io::{self, BufRead, Read, Write};
use std::net::{IpAddr, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

pub const SERVICE: &str = "_glide._tcp.local.";
const PAIRING_TIMEOUT: Duration = Duration::from_secs(90);

fn config_dir() -> PathBuf {
    let home = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_default();
    home.join(".config/glide")
}

pub fn fingerprint(der: &[u8]) -> String {
    Sha256::digest(der).iter().map(|b| format!("{b:02x}")).collect()
}

/// Short code both screens show during pairing. Same inputs, same order as Android's `Pairing.sas`.
pub fn sas(host_der: &[u8], target_der: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(host_der);
    h.update(target_der);
    let d = h.finalize();
    let n = u32::from_be_bytes([d[0], d[1], d[2], d[3]]) % 1_000_000;
    format!("{:03} {:03}", n / 1000, n % 1000)
}

pub struct Identity {
    pub cert: CertificateDer<'static>,
    key: PrivateKeyDer<'static>,
}

/// This Mac's long-lived certificate, created on first run.
pub fn identity() -> io::Result<Identity> {
    let dir = config_dir();
    let (cert_path, key_path) = (dir.join("host.der"), dir.join("host.key"));
    if let (Ok(cert), Ok(key_pem)) = (std::fs::read(&cert_path), std::fs::read_to_string(&key_path)) {
        let key = rcgen::KeyPair::from_pem(&key_pem).map_err(io::Error::other)?;
        return Ok(Identity { cert: cert.into(), key: PrivateKeyDer::try_from(key.serialize_der()).map_err(io::Error::other)? });
    }
    let ck = rcgen::generate_simple_self_signed(vec!["glide-host".to_string()]).map_err(io::Error::other)?;
    std::fs::create_dir_all(&dir)?;
    std::fs::write(&cert_path, ck.cert.der())?;
    write_private(&key_path, ck.signing_key.serialize_pem().as_bytes())?;
    Ok(Identity {
        cert: ck.cert.der().clone(),
        key: PrivateKeyDer::try_from(ck.signing_key.serialize_der()).map_err(io::Error::other)?,
    })
}

fn write_private(path: &PathBuf, data: &[u8]) -> io::Result<()> {
    use std::os::unix::fs::OpenOptionsExt;
    std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(path)?.write_all(data)
}

/// Paired targets: one "fingerprint name" per line.
pub(crate) fn known() -> Vec<(String, String)> {
    std::fs::read_to_string(config_dir().join("known"))
        .unwrap_or_default()
        .lines()
        .filter_map(|l| l.split_once(' ').map(|(f, n)| (f.to_string(), n.to_string())))
        .collect()
}

fn remember(fp: &str, name: &str) -> io::Result<()> {
    let mut all: Vec<_> = known().into_iter().filter(|(f, _)| f != fp).collect();
    all.push((fp.to_string(), name.replace('\n', " ")));
    let text: String = all.iter().map(|(f, n)| format!("{f} {n}\n")).collect();
    write_private(&config_dir().join("known"), text.as_bytes())
}

/// Resolves `want` (an IP, ip:port, a device name, or nothing) to a target address.
/// Without an IP, browses mDNS; with nothing given, prefers a paired device, else the only one found.
pub fn find_target(want: Option<&str>) -> io::Result<SocketAddr> {
    if let Some(w) = want {
        if let Ok(ip) = w.parse::<IpAddr>() {
            return Ok(SocketAddr::new(ip, PORT));
        }
        if let Ok(addr) = w.parse::<SocketAddr>() {
            return Ok(addr);
        }
    }
    let mdns = ServiceDaemon::new().map_err(io::Error::other)?;
    let rx = mdns.browse(SERVICE).map_err(io::Error::other)?;
    let paired: Vec<String> = known().into_iter().map(|(f, _)| f).collect();
    let deadline = Instant::now() + Duration::from_secs(3);
    let mut found = Vec::new();
    while let Some(left) = deadline.checked_duration_since(Instant::now()) {
        let Ok(ev) = rx.recv_timeout(left) else { break };
        let ServiceEvent::ServiceResolved(s) = ev else { continue };
        let name = s.fullname.trim_end_matches(SERVICE).trim_end_matches('.').to_string();
        let id = s.txt_properties.get_property_val_str("id").unwrap_or("").to_string();
        let Some(ip) = s.addresses.iter().map(|a| a.to_ip_addr()).find(|a| a.is_ipv4()) else { continue };
        let addr = SocketAddr::new(ip, s.port);
        let is_paired = !id.is_empty() && paired.iter().any(|f| f.starts_with(&id));
        let matches = want.is_some_and(|w| name.to_lowercase().contains(&w.to_lowercase()));
        if matches || (want.is_none() && is_paired) {
            let _ = mdns.shutdown();
            return Ok(addr);
        }
        if !found.iter().any(|(n, _)| n == &name) {
            found.push((name, addr));
        }
    }
    let _ = mdns.shutdown();
    match (want, found.as_slice()) {
        (None, [(_, addr)]) => Ok(*addr),
        (_, []) => Err(io::Error::other("no phone found. Open Glide on the phone and check it is on the same WiFi")),
        (_, many) => {
            let names: Vec<&str> = many.iter().map(|(n, _)| n.as_str()).collect();
            Err(io::Error::other(format!("pick one by name: {}", names.join(", "))))
        }
    }
}

/// We pin the target's certificate by fingerprint after the handshake, so the CA check is skipped here.
/// Signatures are still verified, which proves the target holds the key for the certificate it shows.
#[derive(Debug)]
struct PinLater(Arc<CryptoProvider>);

impl ServerCertVerifier for PinLater {
    fn verify_server_cert(
        &self, _: &CertificateDer, _: &[CertificateDer], _: &ServerName, _: &[u8], _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(
        &self, m: &[u8], c: &CertificateDer, d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(m, c, d, &self.0.signature_verification_algorithms)
    }
    fn verify_tls13_signature(
        &self, m: &[u8], c: &CertificateDer, d: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(m, c, d, &self.0.signature_verification_algorithms)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

fn tls_config(id: &Identity) -> io::Result<Arc<ClientConfig>> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let cfg = ClientConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(io::Error::other)?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinLater(provider)))
        .with_client_auth_cert(vec![id.cert.clone()], id.key.clone_key())
        .map_err(io::Error::other)?;
    Ok(Arc::new(cfg))
}

fn ask(question: &str) -> bool {
    print!("{question} [y/N] ");
    let _ = io::stdout().flush();
    let mut line = String::new();
    io::stdin().lock().read_line(&mut line).is_ok() && line.trim().eq_ignore_ascii_case("y")
}

/// Where a paired phone is, and who we are to it. Enough to open more connections for file transfers.
#[derive(Clone)]
pub struct Link {
    pub addr: SocketAddr,
    pub fingerprint: String,
    pub name: String,
    pub id: Arc<Identity>,
}

fn handshake(id: &Identity, addr: SocketAddr) -> io::Result<StreamOwned<ClientConnection, TcpStream>> {
    let tcp = TcpStream::connect_timeout(&addr, Duration::from_secs(3))?;
    tcp.set_nodelay(true)?;
    tcp.set_read_timeout(Some(PAIRING_TIMEOUT))?;
    let conn = ClientConnection::new(tls_config(id)?, ServerName::try_from("glide").unwrap()).map_err(io::Error::other)?;
    let mut tls = StreamOwned::new(conn, tcp);
    while tls.conn.is_handshaking() {
        tls.conn.complete_io(&mut tls.sock)?;
    }
    Ok(tls)
}

fn peer_cert(tls: &StreamOwned<ClientConnection, TcpStream>) -> io::Result<CertificateDer<'static>> {
    tls.conn.peer_certificates().and_then(|c| c.first()).map(|c| c.clone().into_owned()).ok_or_else(|| io::Error::other("no cert"))
}

/// A second connection to an already paired phone (file transfers). Refuses anything but the pinned phone.
pub fn connect(link: &Link) -> io::Result<StreamOwned<ClientConnection, TcpStream>> {
    let tls = handshake(&link.id, link.addr)?;
    if fingerprint(&peer_cert(&tls)?) != link.fingerprint {
        return Err(io::Error::other("a different device answered at the phone's address"));
    }
    Ok(tls)
}

/// One connection lifetime: TLS, pairing if needed, Hello, then pump events until the target goes away.
pub fn session(host: &mac::Shared, id: &Arc<Identity>, addr: SocketAddr) -> io::Result<()> {
    let mut tls = handshake(id, addr)?;
    let target_cert = peer_cert(&tls)?;
    let fp = fingerprint(&target_cert);
    let paired = known().iter().any(|(f, _)| f == &fp);
    if !paired {
        println!("\nNew phone at {addr}. Pairing code: {}", sas(&id.cert, &target_cert));
        if !ask("Does the phone show the same code?") {
            return Err(io::Error::other("pairing declined"));
        }
        println!("Now tap Pair on the phone.");
    }

    let screen = mac::screen_size();
    Msg::Hello { version: VERSION, width: screen.0, height: screen.1, name: computer_name() }.write_to(&mut tls)?;
    tls.flush()?;
    let name = match Msg::read_from(&mut tls)? {
        Msg::Hello { version, name, .. } if version == VERSION => name,
        Msg::Hello { version, .. } => {
            return Err(io::Error::other(format!("phone speaks protocol v{version}, this Mac speaks v{VERSION}. Update both")));
        }
        _ => return Err(io::Error::other("expected Hello")),
    };
    if !paired {
        remember(&fp, &name)?;
    }
    // The phone says where it sits (its arrangement screen); it always follows Hello.
    match Msg::read_from(&mut tls)? {
        Msg::Layout { edge, offset, length } => host.lock().unwrap().set_layout(edge, offset, length),
        _ => return Err(io::Error::other("expected the phone's layout")),
    }
    println!("Connected to {name}. Move the cursor off the screen edge to control it.");
    let link = Link { addr, fingerprint: fp, name, id: id.clone() };

    let StreamOwned { conn, sock } = tls;
    sock.set_read_timeout(Some(TIMEOUT))?;
    let conn = Arc::new(Mutex::new(conn));
    let (tx, rx) = mpsc::channel::<Msg>();
    {
        let mut h = host.lock().unwrap();
        h.tx = Some(tx);
        h.link = Some(link.clone());
    }

    let (w_conn, mut w_sock) = (conn.clone(), sock.try_clone()?);
    let writer = thread::spawn(move || {
        let mut seen = -1; // pasteboard change count last announced to the phone
        loop {
            let m = match rx.recv_timeout(PING_EVERY) {
                Ok(m) => m,
                Err(RecvTimeoutError::Timeout) => Msg::Ping,
                Err(RecvTimeoutError::Disconnected) => break,
            };
            let mut out = Vec::new();
            if matches!(m, Msg::Enter { .. }) {
                if let Some(now) = clip::changed_since(seen) {
                    seen = now;
                    let _ = clipboard_msg().map(|c| c.write_to(&mut out));
                }
            }
            let _ = m.write_to(&mut out);
            let mut c = w_conn.lock().unwrap();
            if c.writer().write_all(&out).is_err() || c.write_tls(&mut w_sock).is_err() {
                break;
            }
        }
        let _ = w_sock.shutdown(std::net::Shutdown::Both);
    });

    let result = read_loop(host, &link, &conn, sock);
    {
        let mut h = host.lock().unwrap();
        h.tx = None; // ends the writer
        h.link = None;
    }
    let _ = writer.join();
    result
}

/// What the phone should know about the Mac clipboard: files if Finder copied some, else text.
fn clipboard_msg() -> Option<Msg> {
    let files = clip::files(false);
    if !files.is_empty() {
        let (count, total) = files::summary(&files);
        return Some(Msg::ClipFiles { count, total });
    }
    clip::text().filter(|t| !t.is_empty() && t.len() <= MAX_CLIPBOARD).map(Msg::Clipboard)
}

fn read_loop(host: &mac::Shared, link: &Link, conn: &Mutex<ClientConnection>, mut sock: TcpStream) -> io::Result<()> {
    let mut raw = [0u8; 4096];
    let mut plain = Vec::new();
    loop {
        let n = sock.read(&mut raw)?; // times out after TIMEOUT without even a Ping
        if n == 0 {
            return Err(io::Error::other("phone closed the connection"));
        }
        {
            let mut c = conn.lock().unwrap();
            c.read_tls(&mut &raw[..n])?;
            c.process_new_packets().map_err(io::Error::other)?;
            let _ = c.reader().read_to_end(&mut plain);
            if c.wants_write() {
                c.write_tls(&mut sock)?;
            }
        }
        while plain.len() >= 2 && plain.len() >= 2 + u16::from_be_bytes([plain[0], plain[1]]) as usize {
            let mut r = &plain[..];
            let m = Msg::read_from(&mut r)?;
            let used = plain.len() - r.len();
            plain.drain(..used);
            match m {
                Msg::Leave { pos, .. } => host.lock().unwrap().go_local(pos, false),
                Msg::Layout { edge, offset, length } => host.lock().unwrap().set_layout(edge, offset, length),
                Msg::Clipboard(text) => clip::set_text(&text),
                Msg::PasteFiles => {
                    let paths = clip::files(false);
                    spawn_transfer(link, move |l| files::push(l, &paths));
                }
                Msg::Offer { id, .. } => spawn_transfer(link, move |l| files::pull(l, id)),
                _ => {}
            }
        }
    }
}

/// Transfers run on their own thread and connection; a failure is reported, never fatal to the session.
pub fn spawn_transfer(link: &Link, job: impl FnOnce(&Link) -> io::Result<()> + Send + 'static) {
    let link = link.clone();
    thread::spawn(move || {
        if let Err(mut e) = job(&link) {
            if e.kind() == io::ErrorKind::UnexpectedEof {
                e = io::Error::other("the phone stopped mid-transfer (it may not be allowed to read that file, or it went offline)");
            }
            println!();
            eprintln!("File transfer failed: {e}");
            notify(&format!("File transfer with {} failed: {e}", link.name));
        }
    });
}

/// macOS notification banner. Text is passed as an argument, never spliced into the script.
pub fn notify(text: &str) {
    let _ = std::process::Command::new("osascript")
        .args(["-e", "on run argv", "-e", "display notification (item 1 of argv) with title \"Glide\"", "-e", "end run", text])
        .output();
}

fn computer_name() -> String {
    std::process::Command::new("scutil")
        .args(["--get", "ComputerName"])
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    // Same vector is checked on Android (Pairing.sas); both screens must agree.
    #[test]
    fn sas_vector() {
        assert_eq!(super::sas(b"host", b"target"), "348 117");
    }
}
