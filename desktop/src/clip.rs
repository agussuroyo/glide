//! macOS pasteboards: the general one (copy/paste) and the drag one (what is being dragged right now).

use objc2::runtime::AnyObject;
use objc2::ClassType;
use objc2_app_kit::{NSPasteboard, NSPasteboardNameDrag, NSPasteboardTypeString};
use objc2_foundation::{NSArray, NSString, NSURL};
use std::path::PathBuf;
use std::sync::atomic::{AtomicIsize, Ordering};

/// Change count of the general pasteboard right after we wrote the phone's text to it,
/// so that text is not sent straight back to the phone.
static WRITTEN_BY_US: AtomicIsize = AtomicIsize::new(-1);

fn board(drag: bool) -> objc2::rc::Retained<NSPasteboard> {
    if drag {
        NSPasteboard::pasteboardWithName(unsafe { NSPasteboardNameDrag })
    } else {
        NSPasteboard::generalPasteboard()
    }
}

pub fn change_count(drag: bool) -> isize {
    board(drag).changeCount()
}

/// Files and folders on the pasteboard, in order.
pub fn files(drag: bool) -> Vec<PathBuf> {
    let classes = NSArray::from_slice(&[NSURL::class()]);
    let Some(objs) = (unsafe { board(drag).readObjectsForClasses_options(&classes, None) }) else {
        return Vec::new();
    };
    objs.iter()
        .filter_map(|o: objc2::rc::Retained<AnyObject>| o.downcast::<NSURL>().ok())
        .filter(|u| u.isFileURL())
        .filter_map(|u| u.path().map(|p| PathBuf::from(p.to_string())))
        .collect()
}

pub fn text() -> Option<String> {
    board(false).stringForType(unsafe { NSPasteboardTypeString }).map(|s| s.to_string())
}

pub fn set_text(text: &str) {
    let pb = board(false);
    pb.clearContents();
    pb.setString_forType(&NSString::from_str(text), unsafe { NSPasteboardTypeString });
    WRITTEN_BY_US.store(pb.changeCount(), Ordering::Relaxed);
}

/// True when the general pasteboard changed since `seen` and the change was not our own write.
pub fn changed_since(seen: isize) -> Option<isize> {
    let now = change_count(false);
    (now != seen && now != WRITTEN_BY_US.load(Ordering::Relaxed)).then_some(now)
}
