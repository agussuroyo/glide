//! macOS host: capture mouse/keyboard with a CGEventTap, hand it to the target when the cursor hits `edge`.

use crate::net::{self, Link};
use crate::proto::{Edge, Msg};
use crate::{clip, files};
use core_foundation::base::TCFType;
use core_foundation::boolean::CFBoolean;
use core_foundation::mach_port::CFMachPortRef;
use core_foundation::runloop::{kCFRunLoopCommonModes, CFRunLoop};
use core_foundation::string::{CFString, CFStringRef};
use core_graphics::display::CGDisplay;
use core_graphics::event::{
    CGEvent, CGEventTap, CGEventTapLocation, CGEventTapOptions, CGEventTapPlacement, CGEventType,
    CallbackResult, EventField,
};
use core_graphics::event_source::{CGEventSource, CGEventSourceStateID};
use core_graphics::geometry::{CGPoint, CGRect};
use std::collections::HashSet;
use std::path::PathBuf;
use std::ffi::c_void;
use std::sync::atomic::{AtomicPtr, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};

pub struct Host {
    /// Side of the Mac's screen the phone sits on; set by the phone's arrangement screen.
    pub edge: Edge,
    /// Where the phone starts along that edge, in points from the edge's top/left end.
    offset: f64,
    /// How much of the edge the phone covers, in points. The cursor only crosses within that stretch.
    length: f64,
    pub remote: bool,
    pub tx: Option<Sender<Msg>>,
    /// Set while a phone is connected; used to open file transfers.
    pub link: Option<Link>,
    held_mods: HashSet<u16>,
    /// Drag pasteboard change count when the left button went down; a higher count means a real drag started.
    drag_base: isize,
    /// Files dragged across the edge, sent when the button is released on the phone.
    dragging: Option<Vec<PathBuf>>,
}

/// Marks events Glide posts itself, so its own tap lets them through.
const OWN_EVENT: i64 = 0x676c6964; // "glid"
const KEY_ESCAPE: u16 = 0x35;

pub type Shared = Arc<Mutex<Host>>;

impl Host {
    pub fn new(edge: Edge) -> Shared {
        Arc::new(Mutex::new(Host {
            edge,
            offset: 0.0,
            length: f64::MAX,
            remote: false,
            tx: None,
            link: None,
            held_mods: HashSet::new(),
            drag_base: 0,
            dragging: None,
        }))
    }

    fn send(&mut self, m: Msg) {
        if let Some(tx) = &self.tx {
            if tx.send(m).is_err() {
                self.tx = None;
            }
        }
    }

    fn go_remote(&mut self, loc: CGPoint) {
        let pos = to_phone(self.along(loc), self.offset, self.length).unwrap_or(32768);
        self.send(Msg::Enter { edge: self.edge.opposite(), pos });
        self.remote = true;
        self.held_mods.clear();
        let _ = CGDisplay::main().hide_cursor();
        let _ = CGDisplay::associate_mouse_and_mouse_cursor_position(false);
    }

    /// Control comes back to the Mac: the phone handed it back, the connection dropped, or the user forced it.
    /// `tell_phone` is true when the phone does not know yet (forced), so it can hide its cursor.
    pub fn go_local(&mut self, pos: u16, tell_phone: bool) {
        if !self.remote {
            return;
        }
        self.remote = false;
        self.dragging = None;
        // Release modifiers still held on the target so it does not get stuck with e.g. Shift down.
        for usage in std::mem::take(&mut self.held_mods) {
            self.send(Msg::Key { usage, down: false });
        }
        if tell_phone {
            self.send(Msg::Leave { edge: self.edge.opposite(), pos });
        }
        let b = screen_bounds();
        let (x0, y0, x1, y1) = (b.origin.x, b.origin.y, b.origin.x + b.size.width, b.origin.y + b.size.height);
        let mac_len = match self.edge {
            Edge::Left | Edge::Right => b.size.height,
            Edge::Top | Edge::Bottom => b.size.width,
        };
        let a = to_mac(pos, self.offset, self.length, mac_len);
        let p = match self.edge {
            Edge::Left => CGPoint::new(x0 + 2.0, y0 + a),
            Edge::Right => CGPoint::new(x1 - 3.0, y0 + a),
            Edge::Top => CGPoint::new(x0 + a, y0 + 2.0),
            Edge::Bottom => CGPoint::new(x0 + a, y1 - 3.0),
        };
        let _ = CGDisplay::warp_mouse_cursor_position(p);
        let _ = CGDisplay::associate_mouse_and_mouse_cursor_position(true);
        let _ = CGDisplay::main().show_cursor();
    }

    pub fn set_layout(&mut self, edge: Edge, offset: i32, length: u16) {
        if edge != self.edge {
            let side = match edge {
                Edge::Left => "left of",
                Edge::Right => "right of",
                Edge::Top => "above",
                Edge::Bottom => "below",
            };
            println!("Phone is {side} this screen.");
        }
        self.edge = edge;
        self.offset = offset as f64;
        self.length = length.max(1) as f64;
    }

    /// Distance of `loc` along the shared edge, from its top/left end.
    fn along(&self, loc: CGPoint) -> f64 {
        let b = screen_bounds();
        match self.edge {
            Edge::Left | Edge::Right => loc.y - b.origin.y,
            Edge::Top | Edge::Bottom => loc.x - b.origin.x,
        }
    }

    /// At the shared edge, and within the stretch the phone sits next to.
    fn at_edge(&self, loc: CGPoint) -> bool {
        let b = screen_bounds();
        let on_edge = match self.edge {
            Edge::Left => loc.x <= b.origin.x,
            Edge::Right => loc.x >= b.origin.x + b.size.width - 1.0,
            Edge::Top => loc.y <= b.origin.y,
            Edge::Bottom => loc.y >= b.origin.y + b.size.height - 1.0,
        };
        on_edge && to_phone(self.along(loc), self.offset, self.length).is_some()
    }

    fn on_event(&mut self, etype: CGEventType, e: &CGEvent) -> CallbackResult {
        if e.get_integer_value_field(EventField::EVENT_SOURCE_USER_DATA) == OWN_EVENT {
            return CallbackResult::Keep;
        }
        if !self.remote {
            match etype {
                CGEventType::LeftMouseDown => self.drag_base = clip::change_count(true),
                CGEventType::MouseMoved if self.tx.is_some() && self.at_edge(e.location()) => {
                    self.go_remote(e.location());
                    return CallbackResult::Drop;
                }
                CGEventType::LeftMouseDragged if self.tx.is_some() && self.at_edge(e.location()) => {
                    return self.drag_across(e.location());
                }
                _ => {}
            }
            return CallbackResult::Keep;
        }
        if matches!(etype, CGEventType::LeftMouseUp) {
            if let (Some(paths), Some(link)) = (self.dragging.take(), self.link.as_ref()) {
                // Dropped on the phone. The Mac's own drag was cancelled when it crossed, so let the
                // release through too: the window server then sees the button go up and nothing is stuck.
                net::spawn_transfer(link, move |l| files::push(l, &paths));
                self.send(Msg::Button { button: 0, down: false });
                return CallbackResult::Keep;
            }
        }
        let msg = match etype {
            CGEventType::MouseMoved
            | CGEventType::LeftMouseDragged
            | CGEventType::RightMouseDragged
            | CGEventType::OtherMouseDragged => Msg::Move {
                dx: e.get_integer_value_field(EventField::MOUSE_EVENT_DELTA_X) as i16,
                dy: e.get_integer_value_field(EventField::MOUSE_EVENT_DELTA_Y) as i16,
            },
            CGEventType::LeftMouseDown => Msg::Button { button: 0, down: true },
            CGEventType::LeftMouseUp => Msg::Button { button: 0, down: false },
            CGEventType::RightMouseDown => Msg::Button { button: 1, down: true },
            CGEventType::RightMouseUp => Msg::Button { button: 1, down: false },
            CGEventType::OtherMouseDown => Msg::Button { button: 2, down: true },
            CGEventType::OtherMouseUp => Msg::Button { button: 2, down: false },
            CGEventType::ScrollWheel => Msg::Scroll {
                dx: e.get_integer_value_field(EventField::SCROLL_WHEEL_EVENT_POINT_DELTA_AXIS_2) as i16,
                dy: e.get_integer_value_field(EventField::SCROLL_WHEEL_EVENT_POINT_DELTA_AXIS_1) as i16,
            },
            CGEventType::KeyDown | CGEventType::KeyUp => {
                let vk = e.get_integer_value_field(EventField::KEYBOARD_EVENT_KEYCODE) as u16;
                match mac_to_hid(vk) {
                    Some(usage) => Msg::Key { usage, down: matches!(etype, CGEventType::KeyDown) },
                    None => return CallbackResult::Drop,
                }
            }
            CGEventType::FlagsChanged => {
                let vk = e.get_integer_value_field(EventField::KEYBOARD_EVENT_KEYCODE) as u16;
                let Some(usage) = mac_to_hid(vk) else { return CallbackResult::Drop };
                if usage == 0x39 {
                    // Caps Lock reports one FlagsChanged per toggle, not per press/release.
                    self.send(Msg::Key { usage, down: true });
                    self.send(Msg::Key { usage, down: false });
                    return CallbackResult::Drop;
                }
                // FlagsChanged has no up/down; each event toggles that modifier key.
                let down = self.held_mods.insert(usage);
                if !down {
                    self.held_mods.remove(&usage);
                }
                // Escape hatch: Left Ctrl + Left Option + Left Cmd forces control back to this Mac.
                if [0xE0, 0xE2, 0xE3].iter().all(|u| self.held_mods.contains(u)) {
                    self.go_local(32768, true);
                    return CallbackResult::Drop;
                }
                Msg::Key { usage, down }
            }
            _ => return CallbackResult::Keep,
        };
        self.send(msg);
        if self.tx.is_none() {
            self.go_local(32768, false);
        }
        CallbackResult::Drop
    }

    /// The left button is held at the edge. If Finder (or any app) is dragging files, carry them to the phone:
    /// cancel the drag on the Mac with Escape, then cross as usual with the files riding on the cursor.
    /// A drag of anything else (text, a window) stays on the Mac, as before.
    fn drag_across(&mut self, loc: CGPoint) -> CallbackResult {
        if clip::change_count(true) == self.drag_base {
            return CallbackResult::Keep;
        }
        let paths = clip::files(true);
        if paths.is_empty() {
            return CallbackResult::Keep;
        }
        post_own_key(KEY_ESCAPE);
        let (count, total) = files::summary(&paths);
        self.send(Msg::DragFiles { count, total });
        self.go_remote(loc);
        self.dragging = Some(paths);
        CallbackResult::Drop
    }
}

/// Posts a key press that Glide's own tap will not capture.
fn post_own_key(keycode: u16) {
    let Ok(src) = CGEventSource::new(CGEventSourceStateID::HIDSystemState) else { return };
    for down in [true, false] {
        if let Ok(ev) = CGEvent::new_keyboard_event(src.clone(), keycode, down) {
            ev.set_integer_value_field(EventField::EVENT_SOURCE_USER_DATA, OWN_EVENT);
            ev.post(CGEventTapLocation::HID);
        }
    }
}

/// Point along the Mac edge → position along the phone's edge (0..=65535), if the phone is beside that point.
/// Unscaled: one Mac point is one phone dp, so the cursor keeps its height when it crosses, like a second display.
fn to_phone(along: f64, offset: f64, length: f64) -> Option<u16> {
    let into = along - offset;
    (0.0..=length).contains(&into).then(|| (into / length * 65535.0).round() as u16)
}

/// Position along the phone's edge → point along the Mac edge, kept on the Mac's screen.
fn to_mac(pos: u16, offset: f64, length: f64, mac_len: f64) -> f64 {
    let length = if length.is_finite() { length } else { mac_len };
    (offset + pos as f64 / 65535.0 * length).clamp(0.0, (mac_len - 1.0).max(0.0))
}

/// Whole desktop size in points, for the phone's arrangement screen.
pub fn screen_size() -> (u16, u16) {
    let b = screen_bounds();
    (b.size.width.round() as u16, b.size.height.round() as u16)
}

/// Union of all active displays.
// ponytail: only the outer edge of the whole desktop counts; a display corner that is not on that edge never crosses.
fn screen_bounds() -> CGRect {
    let ids = CGDisplay::active_displays().unwrap_or_default();
    ids.iter()
        .map(|&id| CGDisplay::new(id).bounds())
        .reduce(|a, b| {
            let (x0, y0) = (a.origin.x.min(b.origin.x), a.origin.y.min(b.origin.y));
            let x1 = (a.origin.x + a.size.width).max(b.origin.x + b.size.width);
            let y1 = (a.origin.y + a.size.height).max(b.origin.y + b.size.height);
            CGRect::new(&CGPoint::new(x0, y0), &core_graphics::geometry::CGSize::new(x1 - x0, y1 - y0))
        })
        .unwrap_or_else(|| CGDisplay::main().bounds())
}

static TAP_PORT: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());

#[link(name = "CoreGraphics", kind = "framework")]
unsafe extern "C" {
    fn CGEventTapEnable(tap: CFMachPortRef, enable: bool);
    fn CGSMainConnectionID() -> i32;
    fn CGSSetConnectionProperty(cid: i32, target: i32, key: CFStringRef, value: *const c_void) -> i32;
}

/// Installs the event tap on the current (main) run loop. Fails until the user grants Accessibility and
/// Input Monitoring. The tap lives as long as the returned value.
pub fn install_tap(host: &Shared) -> Result<CGEventTap<'static>, String> {
    // A background process cannot hide the cursor unless it opts in (private API; Barrier/Deskflow do the same).
    unsafe {
        let cid = CGSMainConnectionID();
        let key = CFString::new("SetsCursorInBackground");
        CGSSetConnectionProperty(cid, cid, key.as_concrete_TypeRef(), CFBoolean::true_value().as_CFTypeRef());
    }

    use CGEventType::*;
    let events = vec![
        MouseMoved, LeftMouseDown, LeftMouseUp, RightMouseDown, RightMouseUp, OtherMouseDown, OtherMouseUp,
        LeftMouseDragged, RightMouseDragged, OtherMouseDragged, ScrollWheel, KeyDown, KeyUp, FlagsChanged,
    ];
    let cb_host = host.clone();
    let tap = CGEventTap::new(
        CGEventTapLocation::HID,
        CGEventTapPlacement::HeadInsertEventTap,
        CGEventTapOptions::Default,
        events,
        move |_, etype, e| {
            if matches!(etype, CGEventType::TapDisabledByTimeout | CGEventType::TapDisabledByUserInput) {
                unsafe { CGEventTapEnable(TAP_PORT.load(Ordering::Relaxed) as CFMachPortRef, true) };
                return CallbackResult::Keep;
            }
            cb_host.lock().unwrap().on_event(etype, e)
        },
    )
    .map_err(|_| "no permission for the event tap".to_string())?;
    TAP_PORT.store(tap.mach_port().as_concrete_TypeRef() as *mut c_void, Ordering::Relaxed);
    let source = tap.mach_port().create_runloop_source(0).map_err(|_| "runloop source")?;
    CFRunLoop::get_current().add_source(&source, unsafe { kCFRunLoopCommonModes });
    tap.enable();
    Ok(tap)
}

/// Terminal mode: install the tap and run the main run loop forever.
pub fn run(host: Shared) -> Result<(), String> {
    let _tap = install_tap(&host).map_err(|_| {
        "cannot create event tap. Grant Accessibility and Input Monitoring to your terminal in \
         System Settings > Privacy & Security, then run again."
            .to_string()
    })?;
    CFRunLoop::run_current();
    Ok(())
}

/// Name of the connected phone, if any (for the menu bar).
pub fn phone_name(host: &Shared) -> Option<(String, bool)> {
    let h = host.lock().unwrap();
    h.link.as_ref().map(|l| (l.name.clone(), h.remote))
}

/// macOS virtual keycode → USB HID usage (ANSI layout). Keypad and media keys are not mapped yet.
fn mac_to_hid(vk: u16) -> Option<u16> {
    Some(match vk {
        0x00 => 0x04, 0x0B => 0x05, 0x08 => 0x06, 0x02 => 0x07, 0x0E => 0x08, 0x03 => 0x09, 0x05 => 0x0A,
        0x04 => 0x0B, 0x22 => 0x0C, 0x26 => 0x0D, 0x28 => 0x0E, 0x25 => 0x0F, 0x2E => 0x10, 0x2D => 0x11,
        0x1F => 0x12, 0x23 => 0x13, 0x0C => 0x14, 0x0F => 0x15, 0x01 => 0x16, 0x11 => 0x17, 0x20 => 0x18,
        0x09 => 0x19, 0x0D => 0x1A, 0x07 => 0x1B, 0x10 => 0x1C, 0x06 => 0x1D,
        0x12 => 0x1E, 0x13 => 0x1F, 0x14 => 0x20, 0x15 => 0x21, 0x17 => 0x22, 0x16 => 0x23, 0x1A => 0x24,
        0x1C => 0x25, 0x19 => 0x26, 0x1D => 0x27,
        0x24 => 0x28, 0x35 => 0x29, 0x33 => 0x2A, 0x30 => 0x2B, 0x31 => 0x2C, 0x1B => 0x2D, 0x18 => 0x2E,
        0x21 => 0x2F, 0x1E => 0x30, 0x2A => 0x31, 0x29 => 0x33, 0x27 => 0x34, 0x32 => 0x35, 0x2B => 0x36,
        0x2F => 0x37, 0x2C => 0x38, 0x39 => 0x39, 0x0A => 0x64,
        0x7A => 0x3A, 0x78 => 0x3B, 0x63 => 0x3C, 0x76 => 0x3D, 0x60 => 0x3E, 0x61 => 0x3F, 0x62 => 0x40,
        0x64 => 0x41, 0x65 => 0x42, 0x6D => 0x43, 0x67 => 0x44, 0x6F => 0x45,
        0x73 => 0x4A, 0x74 => 0x4B, 0x75 => 0x4C, 0x77 => 0x4D, 0x79 => 0x4E,
        0x7C => 0x4F, 0x7B => 0x50, 0x7D => 0x51, 0x7E => 0x52,
        0x3B => 0xE0, 0x38 => 0xE1, 0x3A => 0xE2, 0x37 => 0xE3, 0x3E => 0xE4, 0x3C => 0xE5, 0x3D => 0xE6,
        0x36 => 0xE7,
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crossing_follows_the_arrangement() {
        // Phone 900pt tall, its top 100pt below the top of a 982pt-high Mac edge.
        assert_eq!(to_phone(99.0, 100.0, 900.0), None); // above the phone: stays on the Mac
        assert_eq!(to_phone(100.0, 100.0, 900.0), Some(0));
        assert_eq!(to_phone(550.0, 100.0, 900.0), Some(32768));
        assert_eq!(to_phone(1001.0, 100.0, 900.0), None); // below it
        // Coming back lands at the same height it left from.
        assert!((to_mac(32768, 100.0, 900.0, 982.0) - 550.0).abs() < 0.1);
        // Phone hanging past the bottom of the Mac: the return point stays on screen.
        assert_eq!(to_mac(65535, 500.0, 900.0, 982.0), 981.0);
        // Before any arrangement arrives the whole edge is used.
        assert_eq!(to_mac(65535, 0.0, f64::MAX, 982.0), 981.0);
    }
}
