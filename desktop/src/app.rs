//! Glide.app: a menu bar app around the same host. Shows status, asks for permissions, confirms pairing
//! in a dialog and offers Open at Login. Run from a terminal, `glide` skips all of this.

use crate::mac::{self, Shared};
use block2::RcBlock;
use core_foundation::base::TCFType;
use core_foundation::boolean::CFBoolean;
use core_foundation::dictionary::{CFDictionary, CFDictionaryRef};
use core_foundation::string::{CFString, CFStringRef};
use core_graphics::event::CGEventTap;
use objc2::rc::Retained;
use objc2::runtime::{AnyObject, NSObject};
use objc2::{define_class, msg_send, sel, MainThreadMarker, MainThreadOnly};
use objc2_app_kit::{
    NSAlert, NSAlertFirstButtonReturn, NSApplication, NSApplicationActivationPolicy, NSControlStateValueOff,
    NSControlStateValueOn, NSImage, NSMenu, NSMenuItem, NSStatusBar, NSVariableStatusItemLength, NSWorkspace,
};
use objc2_foundation::{NSString, NSTimer, NSURL};
use objc2_service_management::{SMAppService, SMAppServiceStatus};
use std::cell::{Cell, RefCell};
use std::io::{BufRead, Write};
use std::sync::mpsc::{self, Sender};
use std::sync::Mutex;
use std::time::Duration;

const PRIVACY_URL: &str = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility";

/// What the network side wants the menu to show, and a pairing question waiting for the user.
struct UiState {
    status: String,
    pairing: Option<(String, Sender<bool>)>,
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

        #[unsafe(method(openPrivacy:))]
        fn open_privacy(&self, _sender: Option<&AnyObject>) {
            open_url(PRIVACY_URL);
        }
    }
);

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
    let login = item(mtm, "Open at Login", Some(sel!(toggleLogin:)), "");
    let quit = item(mtm, "Quit Glide", Some(sel!(terminate:)), "q");
    unsafe {
        privacy.setTarget(Some(&target));
        login.setTarget(Some(&target));
    }
    menu.addItem(&status_line);
    menu.addItem(&privacy);
    menu.addItem(&NSMenuItem::separatorItem(mtm));
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

        let pending = UI.lock().unwrap().pairing.take();
        if let Some((code, answer)) = pending {
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
