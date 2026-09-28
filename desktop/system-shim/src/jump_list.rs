//! Windows: the jump list, the menu on the app's taskbar button and Start
//! entry. Octo fills it with albums and lists to play; each is a link that
//! starts Octo with an octo:// address, which a running Octo is handed.
//!
//! Every call runs on a short-lived thread of its own with a
//! single-threaded apartment, which the shell's list objects are made for.

use std::thread;

use windows::Win32::Foundation::PROPERTYKEY;
use windows::Win32::System::Com::StructuredStorage::{
    PROPVARIANT, PROPVARIANT_0, PROPVARIANT_0_0, PROPVARIANT_0_0_0, PropVariantClear,
};
use windows::Win32::System::Com::{
    CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, COINIT_DISABLE_OLE1DDE, CoCreateInstance, CoInitializeEx,
    CoTaskMemAlloc, CoUninitialize,
};
use windows::Win32::System::Variant::VT_LPWSTR;
use windows::Win32::UI::Shell::Common::{IObjectArray, IObjectCollection};
use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;
use windows::Win32::UI::Shell::{
    DestinationList, EnumerableObjectCollection, ICustomDestinationList, IShellLinkW, ShellLink,
};
use windows::core::{GUID, HSTRING, Interface, PWSTR};

use crate::{BAD_ARGUMENT, PANICKED};

/// One entry: the heading it goes under, its name, the command line Octo
/// is started with, the tip shown over it, and its icon.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JumpItem {
    pub category: String,
    pub title: String,
    pub arguments: String,
    pub tip: String,
    pub icon: String,
    pub icon_index: i32,
}

/// The entries from the app's text: one per line, the fields split by
/// tabs in the order of JumpItem. A line without a title or a command line
/// is skipped.
pub fn parse_items(text: &str) -> Vec<JumpItem> {
    text.lines()
        .filter_map(|line| {
            let mut fields = line.split('\t');
            let category = fields.next()?.trim().to_string();
            let title = fields.next()?.trim().to_string();
            let arguments = fields.next()?.trim().to_string();
            let tip = fields.next().unwrap_or("").trim().to_string();
            let icon = fields.next().unwrap_or("").trim().to_string();
            let icon_index = fields.next().and_then(|n| n.trim().parse().ok()).unwrap_or(0);
            if category.is_empty() || title.is_empty() || arguments.is_empty() {
                return None;
            }
            Some(JumpItem { category, title, arguments, tip, icon, icon_index })
        })
        .collect()
}

/// The entries that go in: none the listener took out of the list (the
/// system refuses a heading holding one), and no more than there is room
/// for, the first ones kept.
pub fn fitting(items: &[JumpItem], removed: &[String], slots: usize) -> Vec<JumpItem> {
    items.iter().filter(|item| !removed.contains(&item.arguments)).take(slots).cloned().collect()
}

/// The headings in the order they first come, each with its entries.
pub fn by_category(items: &[JumpItem]) -> Vec<(String, Vec<JumpItem>)> {
    let mut groups: Vec<(String, Vec<JumpItem>)> = Vec::new();
    for item in items {
        match groups.iter_mut().find(|(name, _)| *name == item.category) {
            Some((_, list)) => list.push(item.clone()),
            None => groups.push((item.category.clone(), vec![item.clone()])),
        }
    }
    groups
}

// The name a shell link shows as, which a jump list uses for its entry.
const PKEY_TITLE: PROPERTYKEY =
    PROPERTYKEY { fmtid: GUID::from_u128(0xf29f85e0_4ff9_1068_ab91_08002b27b3d9), pid: 2 };

// Runs `work` on a new thread in a single-threaded apartment.
fn on_own_thread<T: Send + 'static>(
    work: impl FnOnce() -> Result<T, i32> + Send + 'static,
) -> Result<T, i32> {
    thread::Builder::new()
        .name("octo-jump-list".into())
        .spawn(move || unsafe {
            let apartment = CoInitializeEx(None, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
            let result =
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(work)).unwrap_or(Err(PANICKED));
            if apartment.is_ok() {
                CoUninitialize();
            }
            result
        })
        .map_err(|_| PANICKED)?
        .join()
        .unwrap_or(Err(PANICKED))
}

fn code(error: windows::core::Error) -> i32 {
    error.code().0
}

fn list_for(app_id: Option<&str>) -> windows::core::Result<ICustomDestinationList> {
    let list: ICustomDestinationList =
        unsafe { CoCreateInstance(&DestinationList, None, CLSCTX_INPROC_SERVER)? };
    if let Some(id) = app_id {
        unsafe { list.SetAppID(&HSTRING::from(id))? };
    }
    Ok(list)
}

/// Replaces the jump list with `items` for the program at `program`.
/// Answers the command lines of the entries the listener took out of the
/// list since it was last set, so the app can forget them too. `app_id` is
/// null for the app's own list (the id Windows gives the program); the
/// tests pass one of their own.
pub fn set(app_id: Option<String>, program: String, items: Vec<JumpItem>) -> Result<Vec<String>, i32> {
    if program.is_empty() {
        return Err(BAD_ARGUMENT);
    }
    on_own_thread(move || fill(app_id.as_deref(), &program, &items).map_err(code))
}

fn fill(app_id: Option<&str>, program: &str, items: &[JumpItem]) -> windows::core::Result<Vec<String>> {
    let list = list_for(app_id)?;
    let mut slots = 0u32;
    let removed_now: IObjectArray = unsafe { list.BeginList(&mut slots)? };
    let removed = arguments_of(&removed_now);
    let result = (|| -> windows::core::Result<()> {
        for (category, entries) in by_category(&fitting(items, &removed, slots.max(1) as usize)) {
            let collection: IObjectCollection =
                unsafe { CoCreateInstance(&EnumerableObjectCollection, None, CLSCTX_INPROC_SERVER)? };
            for item in &entries {
                unsafe { collection.AddObject(&link(program, item)?)? };
            }
            let array: IObjectArray = collection.cast()?;
            unsafe { list.AppendCategory(&HSTRING::from(category.as_str()), &array)? };
        }
        unsafe { list.CommitList() }
    })();
    if result.is_err() {
        unsafe {
            let _ = list.AbortList();
        }
    }
    result.map(|_| removed)
}

/// The entries the listener took out of the list, by command line.
pub fn removed(app_id: Option<String>) -> Result<Vec<String>, i32> {
    on_own_thread(move || {
        let list = list_for(app_id.as_deref()).map_err(code)?;
        let array: IObjectArray = unsafe { list.GetRemovedDestinations() }.map_err(code)?;
        Ok(arguments_of(&array))
    })
}

/// Empties the jump list.
pub fn clear(app_id: Option<String>) -> Result<(), i32> {
    on_own_thread(move || {
        let list: ICustomDestinationList =
            unsafe { CoCreateInstance(&DestinationList, None, CLSCTX_INPROC_SERVER) }.map_err(code)?;
        match app_id {
            Some(id) => unsafe { list.DeleteList(&HSTRING::from(id)) },
            None => unsafe { list.DeleteList(None) },
        }
        .map_err(code)
    })
}

fn arguments_of(array: &IObjectArray) -> Vec<String> {
    let count = unsafe { array.GetCount() }.unwrap_or(0);
    (0..count)
        .filter_map(|i| {
            let link: IShellLinkW = unsafe { array.GetAt(i) }.ok()?;
            let mut buffer = [0u16; 1024];
            unsafe { link.GetArguments(&mut buffer) }.ok()?;
            let end = buffer.iter().position(|c| *c == 0).unwrap_or(buffer.len());
            Some(String::from_utf16_lossy(&buffer[..end]))
        })
        .collect()
}

fn link(program: &str, item: &JumpItem) -> windows::core::Result<IShellLinkW> {
    let link: IShellLinkW = unsafe { CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)? };
    unsafe {
        link.SetPath(&HSTRING::from(program))?;
        link.SetArguments(&HSTRING::from(item.arguments.as_str()))?;
        if !item.tip.is_empty() {
            link.SetDescription(&HSTRING::from(item.tip.as_str()))?;
        }
        let icon = if item.icon.is_empty() { program } else { item.icon.as_str() };
        link.SetIconLocation(&HSTRING::from(icon), item.icon_index)?;
        let store: IPropertyStore = link.cast()?;
        let mut title = text_value(&item.title)?;
        let set = store.SetValue(&PKEY_TITLE, &title).and_then(|_| store.Commit());
        let _ = PropVariantClear(&mut title);
        set?;
    }
    Ok(link)
}

// A text value the way property stores want it: a string of the system's
// own memory, which PropVariantClear frees.
fn text_value(text: &str) -> windows::core::Result<PROPVARIANT> {
    let wide: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
    let bytes = wide.len() * std::mem::size_of::<u16>();
    let memory = unsafe { CoTaskMemAlloc(bytes) } as *mut u16;
    if memory.is_null() {
        return Err(windows::core::Error::from_hresult(windows::Win32::Foundation::E_OUTOFMEMORY));
    }
    unsafe { std::ptr::copy_nonoverlapping(wide.as_ptr(), memory, wide.len()) };
    Ok(PROPVARIANT {
        Anonymous: PROPVARIANT_0 {
            Anonymous: std::mem::ManuallyDrop::new(PROPVARIANT_0_0 {
                vt: VT_LPWSTR,
                wReserved1: 0,
                wReserved2: 0,
                wReserved3: 0,
                Anonymous: PROPVARIANT_0_0_0 { pwszVal: PWSTR(memory) },
            }),
        },
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(category: &str, title: &str, args: &str) -> JumpItem {
        JumpItem {
            category: category.into(),
            title: title.into(),
            arguments: args.into(),
            tip: String::new(),
            icon: String::new(),
            icon_index: 0,
        }
    }

    #[test]
    fn items_are_read_from_lines_of_tabs() {
        let text = "Pinned albums\tOK Computer\tocto://play/album/1\tPlay OK Computer\tC:\\o.ico\t2\n\
                    \n\
                    Recently played\tKid A\tocto://play/album/2\n\
                    Recently played\t\tocto://play/album/3\n\
                    Recently played\tNo command\n";
        let items = parse_items(text);
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].title, "OK Computer");
        assert_eq!(items[0].tip, "Play OK Computer");
        assert_eq!(items[0].icon, "C:\\o.ico");
        assert_eq!(items[0].icon_index, 2);
        assert_eq!(items[1], item("Recently played", "Kid A", "octo://play/album/2"));
    }

    #[test]
    fn removed_entries_stay_out_and_the_first_ones_fit() {
        let items = vec![
            item("Pinned", "A", "octo://play/album/a"),
            item("Recent", "B", "octo://play/album/b"),
            item("Recent", "C", "octo://play/album/c"),
            item("Recent", "D", "octo://play/album/d"),
        ];
        let kept = fitting(&items, &["octo://play/album/b".to_string()], 2);
        assert_eq!(kept.iter().map(|i| i.title.as_str()).collect::<Vec<_>>(), vec!["A", "C"]);
    }

    #[test]
    fn headings_keep_the_order_they_first_come_in() {
        let items = vec![item("Pinned", "A", "a"), item("Recent", "B", "b"), item("Pinned", "C", "c")];
        let groups = by_category(&items);
        assert_eq!(groups.len(), 2);
        assert_eq!(groups[0].0, "Pinned");
        assert_eq!(groups[0].1.len(), 2);
        assert_eq!(groups[1].0, "Recent");
    }

    // A list of the test's own, under an id no app uses, removed after.
    #[test]
    fn a_list_of_our_own_is_set_read_and_removed() {
        let id = format!("Octo.JumpListTest.{}", std::process::id());
        let program = std::env::current_exe().unwrap().to_string_lossy().into_owned();
        let items = vec![
            item("Pinned albums", "Test album", "octo://play/album/test-1"),
            item("Recently played", "Test list", "octo://play/playlist/test-2"),
        ];
        let removed = set(Some(id.clone()), program, items).expect("set");
        assert!(removed.is_empty(), "nothing was taken out of a new list: {removed:?}");
        assert_eq!(removed_now(&id), Vec::<String>::new());
        clear(Some(id)).expect("clear");
        assert_eq!(set(None, String::new(), vec![]), Err(BAD_ARGUMENT));
    }

    fn removed_now(id: &str) -> Vec<String> {
        removed(Some(id.to_string())).expect("removed")
    }
}
