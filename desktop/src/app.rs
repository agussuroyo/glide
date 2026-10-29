//! Glide.app: a menu bar app around the same host. Shows status, asks for permissions, confirms pairing
//! in a dialog and offers Open at Login. Run from a terminal, `glide` skips all of this.

use crate::mac::{self, Shared};
use crate::net::{self, Identity};
use crate::pairing;
use block2::RcBlock;
use core_foundation::base::TCFType;
use core_foundation::boolean::CFBoolean;
use core_foundation::dictionary::{CFDictionary, CFDictionaryRef};
use core_foundation::string::{CFString, CFStringRef};
use core_graphics::event::CGEventTap;
use objc2::rc::Retained;
use objc2::runtime::{AnyObject, NSObject};
use objc2::{define_class, msg_send, sel, AllocAnyThread, MainThreadMarker, MainThreadOnly};
use objc2_app_kit::{
    NSBackingStoreType, NSBox, NSBoxType, NSButton, NSColor, NSFloatingWindowLevel, NSFont, NSPanel,
    NSTextAlignment, NSTextField, NSTitlePosition, NSView, NSWindowStyleMask, NSWindowTitleVisibility,
    NSAlert, NSAlertFirstButtonReturn, NSApplication, NSApplicationActivationPolicy, NSControlStateValueOff,
    NSControlStateValueOn, NSImage, NSImageView, NSMenu, NSMenuItem, NSStatusBar, NSVariableStatusItemLength, NSWorkspace,
};
use objc2_foundation::{NSData, NSPoint, NSRect, NSSize, NSString, NSTimer, NSURL};
use objc2_service_management::{SMAppService, SMAppServiceStatus};
use std::cell::{Cell, RefCell};
use std::io::{BufRead, Write};
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

const PRIVACY_URL: &str = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";

/// What the network side wants the menu to show, and a pairing question waiting for the user.
struct UiState {
    status: String,
    pairing: Option<(String, Sender<bool>)>,
}

static IDENTITY: OnceLock<Arc<Identity>> = OnceLock::new();

/// This Mac's key, needed to put its fingerprint in the pairing QR.
pub fn init(id: Arc<Identity>) {
    let _ = IDENTITY.set(id);
}

static UI: Mutex<UiState> = Mutex::new(UiState { status: String::new(), pairing: None });

/// True when running inside Glide.app rather than from a terminal.
pub fn in_bundle() -> bool {
    std::env::current_exe().map(|p| p.to_string_lossy().contains(".app/Contents/MacOS/")).unwrap_or(false)
}

pub fn set_status(text: impl Into<String>) {
    UI.lock().unwrap().status = text.into();
}

/// Asks the user whether the phone shows the same pairing code: a dialog in the app, a prompt in a terminal.
pub fn confirm_pairing(code: &str) -> bool {
    if !in_bundle() {
        print!("Pairing code: {code}. Does the phone show the same code? [y/N] ");
        let _ = std::io::stdout().flush();
        let mut line = String::new();
        return std::io::stdin().lock().read_line(&mut line).is_ok() && line.trim().eq_ignore_ascii_case("y");
    }
    let (tx, rx) = mpsc::channel();
    UI.lock().unwrap().pairing = Some((code.to_string(), tx));
    rx.recv_timeout(Duration::from_secs(80)).unwrap_or(false)
}

#[link(name = "ApplicationServices", kind = "framework")]
unsafe extern "C" {
    fn AXIsProcessTrustedWithOptions(options: CFDictionaryRef) -> bool;
    static kAXTrustedCheckOptionPrompt: CFStringRef;
}

#[link(name = "CoreGraphics", kind = "framework")]
unsafe extern "C" {
    fn CGRequestListenEventAccess() -> bool;
}

/// Puts Glide in both privacy lists (with the system prompt) so the user only has to flip the switches.
fn request_permissions() {
    unsafe {
        let key = CFString::wrap_under_get_rule(kAXTrustedCheckOptionPrompt);
        let opts = CFDictionary::from_CFType_pairs(&[(key.as_CFType(), CFBoolean::true_value().as_CFType())]);
        AXIsProcessTrustedWithOptions(opts.as_concrete_TypeRef());
        CGRequestListenEventAccess();
    }
}

fn open_url(url: &str) {
    if let Some(u) = NSURL::URLWithString(&NSString::from_str(url)) {
        NSWorkspace::sharedWorkspace().openURL(&u);
    }
}

define_class!(
    // Receives menu clicks.
    #[unsafe(super(NSObject))]
    #[thread_kind = MainThreadOnly]
    #[name = "GlideMenuTarget"]
    struct MenuTarget;

    impl MenuTarget {
        #[unsafe(method(toggleLogin:))]
        fn toggle_login(&self, _sender: Option<&AnyObject>) {
            let service = unsafe { SMAppService::mainAppService() };
            let result = unsafe {
                if service.status() == SMAppServiceStatus::Enabled {
                    service.unregisterAndReturnError()
                } else {
                    service.registerAndReturnError()
                }
            };
            if let Err(e) = result {
                set_status(format!("Open at Login: {}", e.localizedDescription()));
            }
        }

        #[unsafe(method(pairPhone:))]
        fn pair_phone(&self, _sender: Option<&AnyObject>) {
            show_pairing_qr(self.mtm(), self);
        }

        #[unsafe(method(closeQr:))]
        fn close_qr_clicked(&self, _sender: Option<&AnyObject>) {
            close_qr();
        }

        #[unsafe(method(openPrivacy:))]
        fn open_privacy(&self, _sender: Option<&AnyObject>) {
            open_url(PRIVACY_URL);
        }
    }
);

/// The open "Pair a phone" window, if any. Main thread only.
struct QrWindow {
    window: Retained<NSPanel>,
    title: Retained<NSTextField>,
    subtitle: Retained<NSTextField>,
    footnote: Retained<NSTextField>,
    image: Retained<NSImageView>,
    /// Ticks left before closing itself after a successful pairing.
    closing_in: Cell<Option<u32>>,
}

thread_local! {
    static QR: RefCell<Option<QrWindow>> = const { RefCell::new(None) };
}

/// Set by the network side when a phone finishes pairing; the timer shows it in the QR window.
static PAIRED: Mutex<Option<String>> = Mutex::new(None);

pub fn paired(name: &str) {
    *PAIRED.lock().unwrap() = Some(name.to_string());
}

fn label(mtm: MainThreadMarker, text: &str, size: f64, bold: bool, frame: NSRect) -> Retained<NSTextField> {
    let l = NSTextField::wrappingLabelWithString(&NSString::from_str(text), mtm);
    let font = if bold { NSFont::boldSystemFontOfSize(size) } else { NSFont::systemFontOfSize(size) };
    l.setFont(Some(&font));
    l.setAlignment(NSTextAlignment::Center);
    l.setFrame(frame);
    l
}

/// A small centred window with this Mac's pairing QR. Scanning it in the phone app pairs without comparing
/// codes; the window then confirms and closes itself.
fn show_pairing_qr(mtm: MainThreadMarker, target: &MenuTarget) {
    let Some(id) = IDENTITY.get() else { return };
    close_qr();
    let payload = pairing::new_payload(&net::fingerprint(&id.cert), &net::computer_name());

    const W: f64 = 360.0;
    const H: f64 = 480.0;
    const SIDE: f64 = 248.0;
    let rect = |x: f64, y: f64, w: f64, h: f64| NSRect::new(NSPoint::new(x, y), NSSize::new(w, h));
    let style = NSWindowStyleMask::Titled | NSWindowStyleMask::Closable | NSWindowStyleMask::FullSizeContentView;
    let window = NSPanel::initWithContentRect_styleMask_backing_defer(
        NSPanel::alloc(mtm), rect(0.0, 0.0, W, H), style, NSBackingStoreType::Buffered, false,
    );
    unsafe { window.setReleasedWhenClosed(false) };
    window.setHidesOnDeactivate(false); // a menu bar app is rarely "active"; panels would otherwise vanish
    window.setTitlebarAppearsTransparent(true);
    window.setTitleVisibility(NSWindowTitleVisibility::Hidden);
    window.setLevel(NSFloatingWindowLevel);
    let Some(content) = window.contentView() else { return };

    // Laid out top to bottom, all centred on one axis (AppKit measures y from the bottom).
    let title = label(mtm, "Pair a phone", 17.0, true, rect(24.0, H - 74.0, W - 48.0, 24.0));
    let subtitle = label(mtm, "Open Glide on your phone and tap\nScan pairing code.", 13.0, false,
        rect(40.0, H - 116.0, W - 80.0, 36.0));
    subtitle.setTextColor(Some(&NSColor::secondaryLabelColor()));

    // White card so the code scans in dark mode too; the QR's own quiet zone is the inner margin.
    let card = NSBox::new(mtm);
    card.setBoxType(NSBoxType::Custom);
    card.setTitlePosition(NSTitlePosition::NoTitle);
    card.setFillColor(&NSColor::whiteColor());
    card.setBorderColor(&NSColor::separatorColor());
    card.setBorderWidth(1.0);
    card.setCornerRadius(16.0);
    card.setFrame(rect((W - SIDE) / 2.0, 108.0, SIDE, SIDE));

    let image = NSImageView::initWithFrame(NSImageView::alloc(mtm), rect((W - SIDE) / 2.0 + 8.0, 116.0, SIDE - 16.0, SIDE - 16.0));
    if let Some(qr) = NSImage::initWithData(NSImage::alloc(), &NSData::with_bytes(&pairing::bmp(&payload, 8))) {
        image.setImage(Some(&qr));
    }

    let footnote = label(mtm, "Works once, for the next 10 minutes.", 11.0, false, rect(24.0, 76.0, W - 48.0, 16.0));
    footnote.setTextColor(Some(&NSColor::tertiaryLabelColor()));

    let done = unsafe {
        NSButton::buttonWithTitle_target_action(&NSString::from_str("Done"), Some(target), Some(sel!(closeQr:)), mtm)
    };
    done.setKeyEquivalent(&NSString::from_str("\r"));
    done.setFrame(rect((W - 120.0) / 2.0, 24.0, 120.0, 32.0));

    for v in [&*title as &NSView, &subtitle, &card, &image, &footnote, &done] {
        content.addSubview(v);
    }
    window.center();
    window.makeKeyAndOrderFront(None);
    NSApplication::sharedApplication(mtm).activate();
    QR.with(|q| *q.borrow_mut() = Some(QrWindow { window, title, subtitle, footnote, image, closing_in: Cell::new(None) }));
}

fn close_qr() {
    if let Some(q) = QR.with(|q| q.borrow_mut().take()) {
        q.window.close();
    }
}

/// Called every timer tick: confirm a finished pairing in the QR window, then close it.
fn tick_qr() {
    let name = PAIRED.lock().unwrap().take();
    let close = QR.with(|q| {
        let q = q.borrow();
        let Some(q) = q.as_ref() else { return false };
        if !q.window.isVisible() {
            return true; // closed with the title bar button
        }
        if let Some(name) = name {
            q.title.setStringValue(&NSString::from_str(&format!("Paired with {name}")));
            q.subtitle.setStringValue(&NSString::from_str("You can move the cursor onto it now."));
            q.footnote.setStringValue(&NSString::from_str(""));
            if let Some(check) = NSImage::imageWithSystemSymbolName_accessibilityDescription(
                &NSString::from_str("checkmark.circle.fill"),
                Some(&NSString::from_str("Paired")),
            ) {
                q.image.setImage(Some(&check));
                q.image.setContentTintColor(Some(&NSColor::systemGreenColor()));
            }
            q.closing_in.set(Some(8)); // two seconds at four ticks per second
        }
        match q.closing_in.get() {
            Some(0) => true,
            Some(n) => {
                q.closing_in.set(Some(n - 1));
                false
            }
            None => false,
        }
    });
    if close {
        close_qr();
    }
}

impl MenuTarget {
    fn new(mtm: MainThreadMarker) -> Retained<Self> {
        let this = Self::alloc(mtm).set_ivars(());
        unsafe { msg_send![super(this), init] }
    }
}

fn item(mtm: MainThreadMarker, title: &str, action: Option<objc2::runtime::Sel>, key: &str) -> Retained<NSMenuItem> {
    unsafe {
        NSMenuItem::initWithTitle_action_keyEquivalent(
            NSMenuItem::alloc(mtm),
            &NSString::from_str(title),
            action,
            &NSString::from_str(key),
        )
    }
}

/// Runs the menu bar app on the main thread until Quit.
pub fn run(host: Shared) -> ! {
    let mtm = MainThreadMarker::new().expect("main thread");
    let app = NSApplication::sharedApplication(mtm);
    app.setActivationPolicy(NSApplicationActivationPolicy::Accessory); // menu bar only, no Dock icon

    let target = MenuTarget::new(mtm);
    let status_item = NSStatusBar::systemStatusBar().statusItemWithLength(NSVariableStatusItemLength);
    if let Some(button) = status_item.button(mtm) {
        let image = NSImage::imageWithSystemSymbolName_accessibilityDescription(
            &NSString::from_str("cursorarrow.motionlines"),
            Some(&NSString::from_str("Glide")),
        );
        if let Some(image) = image {
            image.setTemplate(true);
            button.setImage(Some(&image));
        } else {
            button.setTitle(&NSString::from_str("Glide"));
        }
    }

    let menu = NSMenu::new(mtm);
    let status_line = item(mtm, "Starting…", None, "");
    status_line.setEnabled(false);
    let privacy = item(mtm, "Open Privacy Settings…", Some(sel!(openPrivacy:)), "");
    let pair = item(mtm, "Pair New Phone…", Some(sel!(pairPhone:)), "");
    let login = item(mtm, "Open at Login", Some(sel!(toggleLogin:)), "");
    let quit = item(mtm, "Quit Glide", Some(sel!(terminate:)), "q");
    unsafe {
        privacy.setTarget(Some(&target));
        pair.setTarget(Some(&target));
        login.setTarget(Some(&target));
    }
    menu.addItem(&status_line);
    menu.addItem(&privacy);
    menu.addItem(&NSMenuItem::separatorItem(mtm));
    menu.addItem(&pair);
    menu.addItem(&login);
    menu.addItem(&NSMenuItem::separatorItem(mtm));
    menu.addItem(&quit);
    status_item.setMenu(Some(&menu));

    request_permissions();
    let tap: RefCell<Option<CGEventTap<'static>>> = RefCell::new(mac::install_tap(&host).ok());
    let ticks = Cell::new(0u32);

    // One timer on the main thread does all UI work: no cross-thread AppKit calls.
    let tick = RcBlock::new(move |_timer: std::ptr::NonNull<NSTimer>| {
        ticks.set(ticks.get().wrapping_add(1));
        let has_tap = tap.borrow().is_some();
        if !has_tap && ticks.get() % 8 == 0 {
            // Permissions take effect without a restart: keep trying every 2 seconds.
            *tap.borrow_mut() = mac::install_tap(&host).ok();
        }

        let text = if tap.borrow().is_none() {
            "Allow Glide in Accessibility and Input Monitoring".to_string()
        } else {
            match mac::phone_name(&host) {
                Some((name, true)) => format!("Using {name}"),
                Some((name, false)) => format!("Connected to {name}"),
                None => UI.lock().unwrap().status.clone(),
            }
        };
        status_line.setTitle(&NSString::from_str(&text));
        privacy.setHidden(tap.borrow().is_some());

        let enabled = unsafe { SMAppService::mainAppService().status() } == SMAppServiceStatus::Enabled;
        login.setState(if enabled { NSControlStateValueOn } else { NSControlStateValueOff });

        tick_qr();
        let pending = UI.lock().unwrap().pairing.take();
        if let Some((code, answer)) = pending {
            close_qr(); // the phone could not scan: compare codes instead
            let alert = NSAlert::new(mtm);
            alert.setMessageText(&NSString::from_str(&format!("Does your phone show {code}?")));
            alert.setInformativeText(&NSString::from_str(
                "A phone wants to pair with this Mac. Pair only if the phone shows the same code, then tap Pair on the phone too.",
            ));
            alert.addButtonWithTitle(&NSString::from_str("Pair"));
            alert.addButtonWithTitle(&NSString::from_str("Don't Pair"));
            NSApplication::sharedApplication(mtm).activate();
            let _ = answer.send(alert.runModal() == NSAlertFirstButtonReturn);
        }
    });
    let _timer = unsafe { NSTimer::scheduledTimerWithTimeInterval_repeats_block(0.25, true, &tick) };

    app.run();
    std::process::exit(0)
}
