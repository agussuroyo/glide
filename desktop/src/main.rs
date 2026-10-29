mod app;
mod clip;
mod files;
mod mac;
mod net;
mod proto;

use proto::Edge;
use std::thread;
use std::time::Duration;

const USAGE: &str = "usage: glide [phone]

Moves this Mac's mouse and keyboard to your phone when the cursor crosses the screen edge.
Where the phone sits (which side, how high) is set by dragging it in the Glide app on the phone.
[phone] is optional: a name (\"pixel\"), an IP, or ip:port. Without it, Glide finds the phone on your WiFi.
Press Left Ctrl + Left Option + Left Cmd to force control back.";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let want = match args.as_slice() {
        [] => None,
        [w] if !w.starts_with('-') => Some(w.clone()),
        _ => exit_usage(),
    };
    let id = std::sync::Arc::new(net::identity().unwrap_or_else(|e| {
        eprintln!("cannot create this Mac's key in ~/.config/glide: {e}");
        std::process::exit(1);
    }));

    let host = mac::Host::new(Edge::Right); // until the phone sends its arrangement
    let net_host = host.clone();
    thread::spawn(move || {
        let mut last_err = String::new();
        loop {
            app::set_status("Looking for your phone on this WiFi…");
            let result = net::find_target(want.as_deref()).and_then(|addr| net::session(&net_host, &id, addr));
            if let Err(e) = result {
                app::set_status(e.to_string());
                // Retrying every 2s; only print when the reason changes.
                if e.to_string() != last_err {
                    eprintln!("{e}. Retrying…");
                    last_err = e.to_string();
                }
            }
            let mut h = net_host.lock().unwrap();
            h.tx = None;
            h.go_local(32768, false);
            drop(h);
            thread::sleep(Duration::from_secs(2));
        }
    });

    if app::in_bundle() {
        app::run(host);
    }
    if let Err(e) = mac::run(host) {
        eprintln!("{e}");
        std::process::exit(1);
    }
}

fn exit_usage() -> ! {
    eprintln!("{USAGE}");
    std::process::exit(2);
}
