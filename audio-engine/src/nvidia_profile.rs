//! Keeping NVIDIA's game overlay off Octo. NVIDIA's driver treats an app it
//! does not know that draws through Direct3D as a game and shows its overlay
//! on it. Its own profiles mark desktop and media apps (Spotify, Apple Music,
//! VLC) as not games with one setting; this puts Octo's exe in a profile of
//! its own with the same value. Reading works for anyone; writing needs an
//! administrator, so the write runs in a small helper Windows asks about.

/// NVIDIA's per-app setting that decides whether its overlay treats an app
/// as a game.
pub const OVERLAY_SETTING: u32 = 0x809D_5F60;
/// The value NVIDIA's own profiles give desktop and media apps.
pub const NOT_A_GAME: u32 = 0x1000_0000;
/// The profile Octo adds its exe to; Octo changes no other profile.
pub const OCTO_PROFILE: &str = "Octo (added by Octo)";

/// The helper's exit codes.
pub const EXIT_DONE: i32 = 0;
pub const EXIT_REFUSED: i32 = 2;
pub const EXIT_NO_NVIDIA: i32 = 3;
pub const EXIT_FAILED: i32 = 4;
pub const EXIT_ELSEWHERE: i32 = 5;
pub const EXIT_BAD_PATH: i32 = 6;

/// How NVIDIA's overlay stands with an exe.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum NvidiaOverlay {
    /// No NVIDIA driver to ask.
    NoNvidia,
    /// The overlay treats it as a game.
    On,
    /// It is marked as not a game.
    KeptOff,
    /// Listed in Octo's profile, but the setting there no longer keeps the
    /// overlay off (changed in NVIDIA's own settings).
    Undone,
}

/// What asking for the overlay to be kept off came to.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum NvidiaChange {
    Done,
    /// Said no at Windows' prompt.
    Cancelled,
    /// Ran without administrator rights.
    Refused,
    NoNvidia,
    /// The exe is in a profile Octo did not make; it was left alone.
    Elsewhere,
    Failed,
}

/// What the driver's settings say about an exe.
#[derive(Clone, Debug, PartialEq, Eq)]
#[cfg_attr(not(windows), allow(dead_code))]
pub(crate) enum Lookup {
    NoNvidia,
    /// In this profile, with this overlay value (None: not set there).
    Listed {
        profile: String,
        value: Option<u32>,
    },
    /// In no profile. Octo's own profile may still list other Octo exes (a
    /// portable copy, an older folder), which says nothing about this one.
    Unlisted,
}

pub(crate) fn classify(lookup: &Lookup) -> NvidiaOverlay {
    match lookup {
        Lookup::NoNvidia => NvidiaOverlay::NoNvidia,
        Lookup::Listed { value: Some(NOT_A_GAME), .. } => NvidiaOverlay::KeptOff,
        Lookup::Listed { profile, .. } if profile == OCTO_PROFILE => NvidiaOverlay::Undone,
        Lookup::Listed { .. } => NvidiaOverlay::On,
        Lookup::Unlisted => NvidiaOverlay::On,
    }
}

/// The change a helper's exit code stands for; any code it does not give
/// (a crash, say) is a failure.
pub fn change_of_exit(code: u32) -> NvidiaChange {
    match code as i32 {
        EXIT_DONE => NvidiaChange::Done,
        EXIT_REFUSED => NvidiaChange::Refused,
        EXIT_NO_NVIDIA => NvidiaChange::NoNvidia,
        EXIT_ELSEWHERE => NvidiaChange::Elsewhere,
        _ => NvidiaChange::Failed,
    }
}

/// The helper's command line for an exe: the path in quotes, so one with
/// spaces stays one argument (Windows paths cannot hold quotes).
pub fn helper_arguments(exe: &str) -> String {
    format!("keep-off \"{exe}\"")
}

/// Whether a path is one the helper may add: an existing exe, given in full.
#[cfg_attr(not(windows), allow(dead_code))]
pub(crate) fn valid_target(exe: &str) -> bool {
    let path = std::path::Path::new(exe);
    path.is_absolute() && path.extension().is_some_and(|e| e.eq_ignore_ascii_case("exe")) && path.is_file()
}

/// How NVIDIA's overlay stands with `exe` (a full path). Reads only.
#[uniffi::export]
pub fn nvidia_overlay(exe: String) -> NvidiaOverlay {
    classify(&lookup(&exe))
}

/// Asks Windows to run `helper` as an administrator to keep the overlay off
/// `exe`, and waits for it. Windows' own prompt comes first; saying no there
/// is Cancelled. Blocks, so it is called off the window's thread.
#[uniffi::export]
pub fn keep_nvidia_overlay_off(helper: String, exe: String) -> NvidiaChange {
    run_elevated(&helper, &exe)
}

#[cfg(not(windows))]
fn lookup(_exe: &str) -> Lookup {
    Lookup::NoNvidia
}

#[cfg(not(windows))]
fn run_elevated(_helper: &str, _exe: &str) -> NvidiaChange {
    NvidiaChange::NoNvidia
}

/// Keeps the overlay off `exe`, for the helper: returns its exit code. Needs
/// an administrator; without one it changes nothing.
#[cfg(not(windows))]
pub fn keep_off(_exe: &str) -> i32 {
    EXIT_NO_NVIDIA
}

#[cfg(windows)]
pub use windows_impl::keep_off;
#[cfg(windows)]
use windows_impl::{lookup, run_elevated};

#[cfg(windows)]
mod windows_impl {
    use super::*;
    use std::ffi::c_void;
    use windows::Win32::Foundation::{CloseHandle, ERROR_CANCELLED, FreeLibrary, HMODULE};
    use windows::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};
    use windows::Win32::System::Threading::{GetExitCodeProcess, INFINITE, WaitForSingleObject};
    use windows::Win32::UI::Shell::{
        IsUserAnAdmin, SEE_MASK_NOASYNC, SEE_MASK_NOCLOSEPROCESS, SEE_MASK_NOZONECHECKS, SHELLEXECUTEINFOW,
        ShellExecuteExW,
    };
    use windows::Win32::UI::WindowsAndMessaging::SW_HIDE;
    use windows::core::{PCSTR, PCWSTR};

    // NVAPI's interface ids (nvapi_QueryInterface).
    const INITIALIZE: u32 = 0x0150_E828;
    const UNLOAD: u32 = 0xD22B_DD7E;
    const CREATE_SESSION: u32 = 0x0694_D52E;
    const DESTROY_SESSION: u32 = 0xDAD9_CFF8;
    const LOAD_SETTINGS: u32 = 0x375D_BD6B;
    const SAVE_SETTINGS: u32 = 0xFCBC_7E14;
    const FIND_PROFILE: u32 = 0x7E4A_9A0B;
    const CREATE_PROFILE: u32 = 0xCC17_6068;
    const CREATE_APPLICATION: u32 = 0x4347_A9DE;
    const FIND_APPLICATION: u32 = 0xEEE5_66B2;
    const PROFILE_INFO: u32 = 0x61CD_6FD6;
    const GET_SETTING: u32 = 0x73BF_8338;
    const SET_SETTING: u32 = 0x577D_D202;

    // Version-1 structures: their size, with the version in the top half of
    // the first word.
    const PROFILE_SIZE: usize = 4116;
    const APPLICATION_SIZE: usize = 12296;
    const SETTING_SIZE: usize = 12320;
    // Inside a setting: its id, its type, its current value.
    const SETTING_ID_AT: usize = 4100;
    const SETTING_TYPE_AT: usize = 4104;
    const SETTING_VALUE_AT: usize = 8220;
    // A name: 2,048 UTF-16 units.
    const NAME_UNITS: usize = 2048;

    type Query = unsafe extern "C" fn(u32) -> *const c_void;
    type Session = *mut c_void;
    type Profile = *mut c_void;

    pub(crate) fn versioned(size: usize) -> Vec<u8> {
        let mut buffer = vec![0u8; size];
        buffer[..4].copy_from_slice(&((size as u32) | (1 << 16)).to_le_bytes());
        buffer
    }

    pub(crate) fn wide(text: &str) -> Vec<u16> {
        let mut units = vec![0u16; NAME_UNITS];
        for (slot, unit) in units.iter_mut().zip(text.encode_utf16().take(NAME_UNITS - 1)) {
            *slot = unit;
        }
        units
    }

    fn name_at(buffer: &[u8], at: usize) -> String {
        let units: Vec<u16> = buffer[at..at + NAME_UNITS * 2]
            .chunks_exact(2)
            .map(|pair| u16::from_le_bytes([pair[0], pair[1]]))
            .take_while(|&unit| unit != 0)
            .collect();
        String::from_utf16_lossy(&units)
    }

    pub(crate) fn overlay_setting(value: u32) -> Vec<u8> {
        let mut setting = versioned(SETTING_SIZE);
        setting[SETTING_ID_AT..SETTING_ID_AT + 4].copy_from_slice(&OVERLAY_SETTING.to_le_bytes());
        setting[SETTING_TYPE_AT..SETTING_TYPE_AT + 4].copy_from_slice(&0u32.to_le_bytes());
        setting[SETTING_VALUE_AT..SETTING_VALUE_AT + 4].copy_from_slice(&value.to_le_bytes());
        setting
    }

    // The driver's library, loaded and started; unloaded, then freed, when dropped.
    struct Nvapi {
        library: HMODULE,
        query: Query,
    }

    impl Nvapi {
        fn open() -> Option<Self> {
            let name: Vec<u16> = "nvapi64.dll\0".encode_utf16().collect();
            let library = unsafe { LoadLibraryW(PCWSTR(name.as_ptr())) }.ok()?;
            let Some(query) =
                (unsafe { GetProcAddress(library, PCSTR(c"nvapi_QueryInterface".as_ptr().cast())) })
            else {
                let _ = unsafe { FreeLibrary(library) };
                return None;
            };
            let query = unsafe { std::mem::transmute::<unsafe extern "system" fn() -> isize, Query>(query) };
            let api = Nvapi { library, query };
            // A library left behind with no NVIDIA card fails to start: no NVIDIA.
            let start: Option<unsafe extern "C" fn() -> i32> = api.function(INITIALIZE);
            if start.is_some_and(|start| unsafe { start() } == 0) { Some(api) } else { None }
        }

        fn function<F: Copy>(&self, id: u32) -> Option<F> {
            let pointer = unsafe { (self.query)(id) };
            if pointer.is_null() {
                return None;
            }
            Some(unsafe { std::mem::transmute_copy::<*const c_void, F>(&pointer) })
        }

        // A settings session with the database loaded; destroyed when dropped.
        fn session(&self) -> Option<Settings<'_>> {
            let create: unsafe extern "C" fn(*mut Session) -> i32 = self.function(CREATE_SESSION)?;
            let mut handle: Session = std::ptr::null_mut();
            if unsafe { create(&mut handle) } != 0 {
                return None;
            }
            let settings = Settings { api: self, handle };
            let load: unsafe extern "C" fn(Session) -> i32 = self.function(LOAD_SETTINGS)?;
            (unsafe { load(handle) } == 0).then_some(settings)
        }
    }

    impl Drop for Nvapi {
        fn drop(&mut self) {
            if let Some(unload) = self.function::<unsafe extern "C" fn() -> i32>(UNLOAD) {
                unsafe { unload() };
            }
            let _ = unsafe { FreeLibrary(self.library) };
        }
    }

    struct Settings<'a> {
        api: &'a Nvapi,
        handle: Session,
    }

    impl Settings<'_> {
        // The profile the exe is listed in, if any.
        fn application_profile(&self, exe: &str) -> Option<Profile> {
            let find: unsafe extern "C" fn(Session, *const u16, *mut Profile, *mut u8) -> i32 =
                self.api.function(FIND_APPLICATION)?;
            let name = wide(exe);
            let mut profile: Profile = std::ptr::null_mut();
            let mut application = versioned(APPLICATION_SIZE);
            let status = unsafe { find(self.handle, name.as_ptr(), &mut profile, application.as_mut_ptr()) };
            (status == 0 && !profile.is_null()).then_some(profile)
        }

        fn profile_named(&self, name: &str) -> Option<Profile> {
            let find: unsafe extern "C" fn(Session, *const u16, *mut Profile) -> i32 =
                self.api.function(FIND_PROFILE)?;
            let wide_name = wide(name);
            let mut profile: Profile = std::ptr::null_mut();
            (unsafe { find(self.handle, wide_name.as_ptr(), &mut profile) } == 0 && !profile.is_null())
                .then_some(profile)
        }

        fn profile_name(&self, profile: Profile) -> Option<String> {
            let info: unsafe extern "C" fn(Session, Profile, *mut u8) -> i32 =
                self.api.function(PROFILE_INFO)?;
            let mut buffer = versioned(PROFILE_SIZE);
            (unsafe { info(self.handle, profile, buffer.as_mut_ptr()) } == 0).then(|| name_at(&buffer, 4))
        }

        fn overlay_value(&self, profile: Profile) -> Option<u32> {
            let get: unsafe extern "C" fn(Session, Profile, u32, *mut u8) -> i32 =
                self.api.function(GET_SETTING)?;
            let mut setting = versioned(SETTING_SIZE);
            (unsafe { get(self.handle, profile, OVERLAY_SETTING, setting.as_mut_ptr()) } == 0).then(|| {
                u32::from_le_bytes(setting[SETTING_VALUE_AT..SETTING_VALUE_AT + 4].try_into().unwrap())
            })
        }

        fn create_profile(&self, name: &str) -> Option<Profile> {
            let create: unsafe extern "C" fn(Session, *const u8, *mut Profile) -> i32 =
                self.api.function(CREATE_PROFILE)?;
            let mut info = versioned(PROFILE_SIZE);
            for (i, unit) in name.encode_utf16().take(NAME_UNITS - 1).enumerate() {
                info[4 + i * 2..6 + i * 2].copy_from_slice(&unit.to_le_bytes());
            }
            let mut profile: Profile = std::ptr::null_mut();
            (unsafe { create(self.handle, info.as_ptr(), &mut profile) } == 0 && !profile.is_null())
                .then_some(profile)
        }

        fn add_application(&self, profile: Profile, exe: &str) -> bool {
            let Some(create): Option<unsafe extern "C" fn(Session, Profile, *const u8) -> i32> =
                self.api.function(CREATE_APPLICATION)
            else {
                return false;
            };
            let mut application = versioned(APPLICATION_SIZE);
            // appName right after the version and isPredefined words.
            for (i, unit) in exe.encode_utf16().take(NAME_UNITS - 1).enumerate() {
                application[8 + i * 2..10 + i * 2].copy_from_slice(&unit.to_le_bytes());
            }
            unsafe { create(self.handle, profile, application.as_ptr()) == 0 }
        }

        fn keep_overlay_off(&self, profile: Profile) -> bool {
            let Some(set): Option<unsafe extern "C" fn(Session, Profile, *const u8) -> i32> =
                self.api.function(SET_SETTING)
            else {
                return false;
            };
            let setting = overlay_setting(NOT_A_GAME);
            unsafe { set(self.handle, profile, setting.as_ptr()) == 0 }
        }

        fn save(&self) -> bool {
            let Some(save): Option<unsafe extern "C" fn(Session) -> i32> = self.api.function(SAVE_SETTINGS)
            else {
                return false;
            };
            unsafe { save(self.handle) == 0 }
        }
    }

    impl Drop for Settings<'_> {
        fn drop(&mut self) {
            if let Some(destroy) = self.api.function::<unsafe extern "C" fn(Session) -> i32>(DESTROY_SESSION)
            {
                unsafe { destroy(self.handle) };
            }
        }
    }

    pub(crate) fn lookup(exe: &str) -> Lookup {
        let Some(api) = Nvapi::open() else { return Lookup::NoNvidia };
        let Some(settings) = api.session() else { return Lookup::NoNvidia };
        match settings.application_profile(exe) {
            Some(profile) => Lookup::Listed {
                profile: settings.profile_name(profile).unwrap_or_default(),
                value: settings.overlay_value(profile),
            },
            None => Lookup::Unlisted,
        }
    }

    /// Keeps the overlay off `exe`, for the helper: returns its exit code.
    /// Without administrator rights it changes nothing; a step that fails
    /// leaves the session unsaved, so no half-made profile stays behind.
    pub fn keep_off(exe: &str) -> i32 {
        if !valid_target(exe) {
            return EXIT_BAD_PATH;
        }
        if !unsafe { IsUserAnAdmin() }.as_bool() {
            return EXIT_REFUSED;
        }
        let Some(api) = Nvapi::open() else { return EXIT_NO_NVIDIA };
        let written = {
            let Some(settings) = api.session() else { return EXIT_FAILED };
            let profile = match settings.application_profile(exe) {
                Some(profile) => {
                    if settings.overlay_value(profile) == Some(NOT_A_GAME) {
                        return EXIT_DONE;
                    }
                    if settings.profile_name(profile).as_deref() != Some(OCTO_PROFILE) {
                        return EXIT_ELSEWHERE;
                    }
                    profile
                }
                None => {
                    let Some(profile) = settings
                        .profile_named(OCTO_PROFILE)
                        .or_else(|| settings.create_profile(OCTO_PROFILE))
                    else {
                        return EXIT_FAILED;
                    };
                    if !settings.add_application(profile, exe) {
                        return EXIT_FAILED;
                    }
                    profile
                }
            };
            settings.keep_overlay_off(profile) && settings.save()
        };
        drop(api);
        if written && classify(&lookup(exe)) == NvidiaOverlay::KeptOff { EXIT_DONE } else { EXIT_FAILED }
    }

    pub(crate) fn run_elevated(helper: &str, exe: &str) -> NvidiaChange {
        let verb: Vec<u16> = "runas\0".encode_utf16().collect();
        let file: Vec<u16> = helper.encode_utf16().chain(Some(0)).collect();
        let arguments: Vec<u16> = helper_arguments(exe).encode_utf16().chain(Some(0)).collect();
        let mut info = SHELLEXECUTEINFOW {
            cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
            fMask: SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC | SEE_MASK_NOZONECHECKS,
            lpVerb: PCWSTR(verb.as_ptr()),
            lpFile: PCWSTR(file.as_ptr()),
            lpParameters: PCWSTR(arguments.as_ptr()),
            nShow: SW_HIDE.0,
            ..Default::default()
        };
        // Saying no at Windows' prompt fails the launch itself (ERROR_CANCELLED).
        if let Err(error) = unsafe { ShellExecuteExW(&mut info) } {
            return if error.code() == ERROR_CANCELLED.to_hresult() {
                NvidiaChange::Cancelled
            } else {
                NvidiaChange::Failed
            };
        }
        if info.hProcess.is_invalid() {
            return NvidiaChange::Failed;
        }
        // The helper's own work, after the prompt: waited for until it ends.
        unsafe { WaitForSingleObject(info.hProcess, INFINITE) };
        let mut code = 0u32;
        let read = unsafe { GetExitCodeProcess(info.hProcess, &mut code) }.is_ok();
        let _ = unsafe { CloseHandle(info.hProcess) };
        if read { change_of_exit(code) } else { NvidiaChange::Failed }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_listed_exe_with_the_not_a_game_value_is_kept_off() {
        let listed = |profile: &str, value| Lookup::Listed { profile: profile.into(), value };
        assert_eq!(classify(&listed(OCTO_PROFILE, Some(NOT_A_GAME))), NvidiaOverlay::KeptOff);
        assert_eq!(classify(&listed("Spotify", Some(NOT_A_GAME))), NvidiaOverlay::KeptOff);
        assert_eq!(classify(&listed(OCTO_PROFILE, Some(0))), NvidiaOverlay::Undone);
        assert_eq!(classify(&listed(OCTO_PROFILE, None)), NvidiaOverlay::Undone);
        assert_eq!(classify(&listed("Some game", Some(0x0800_0001))), NvidiaOverlay::On);
        assert_eq!(classify(&listed("Some game", None)), NvidiaOverlay::On);
    }

    #[test]
    fn an_unlisted_exe_is_simply_offered() {
        // Octo's profile may list other Octo exes; that says nothing about this one.
        assert_eq!(classify(&Lookup::Unlisted), NvidiaOverlay::On);
        assert_eq!(classify(&Lookup::NoNvidia), NvidiaOverlay::NoNvidia);
    }

    #[test]
    fn exit_codes_map_to_changes_and_anything_else_failed() {
        assert_eq!(change_of_exit(0), NvidiaChange::Done);
        assert_eq!(change_of_exit(2), NvidiaChange::Refused);
        assert_eq!(change_of_exit(3), NvidiaChange::NoNvidia);
        assert_eq!(change_of_exit(5), NvidiaChange::Elsewhere);
        assert_eq!(change_of_exit(4), NvidiaChange::Failed);
        assert_eq!(change_of_exit(6), NvidiaChange::Failed);
        // A Rust panic exits 101.
        assert_eq!(change_of_exit(101), NvidiaChange::Failed);
    }

    #[test]
    fn a_path_with_spaces_stays_one_argument() {
        assert_eq!(
            helper_arguments(r"C:\Users\Jane Doe\AppData\Local\OctoPlayer\Octo.exe"),
            r#"keep-off "C:\Users\Jane Doe\AppData\Local\OctoPlayer\Octo.exe""#
        );
    }

    #[test]
    fn only_an_existing_exe_given_in_full_is_a_target() {
        let dir = std::env::temp_dir().join(format!("octo-nv-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let exe = dir.join("Octo Test.exe");
        std::fs::write(&exe, b"x").unwrap();
        assert!(valid_target(exe.to_str().unwrap()));
        assert!(!valid_target("Octo.exe"));
        assert!(!valid_target(dir.join("missing.exe").to_str().unwrap()));
        let text = dir.join("notes.txt");
        std::fs::write(&text, b"x").unwrap();
        assert!(!valid_target(text.to_str().unwrap()));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(windows)]
    #[test]
    fn structures_carry_their_size_version_and_fields_where_nvapi_reads_them() {
        let setting = windows_impl::overlay_setting(NOT_A_GAME);
        assert_eq!(setting.len(), 12320);
        assert_eq!(u32::from_le_bytes(setting[0..4].try_into().unwrap()), 12320 | (1 << 16));
        assert_eq!(u32::from_le_bytes(setting[4100..4104].try_into().unwrap()), OVERLAY_SETTING);
        assert_eq!(u32::from_le_bytes(setting[8220..8224].try_into().unwrap()), NOT_A_GAME);
        let name = windows_impl::wide("Octo");
        assert_eq!(name.len(), 2048);
        assert_eq!(&name[..5], &[b'O' as u16, b'c' as u16, b't' as u16, b'o' as u16, 0]);
    }

    // Without administrator rights the helper changes nothing (this test
    // never runs elevated in the gates), and nothing is left behind.
    #[cfg(windows)]
    #[test]
    fn without_an_administrator_nothing_is_written() {
        if unsafe { windows::Win32::UI::Shell::IsUserAnAdmin() }.as_bool() {
            return;
        }
        let dir = std::env::temp_dir().join(format!("octo-nv-write-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let exe = dir.join("OctoProbe.exe");
        std::fs::write(&exe, b"x").unwrap();
        let path = exe.to_str().unwrap().to_string();
        assert_eq!(keep_off(&path), EXIT_REFUSED);
        assert!(matches!(lookup(&path), Lookup::NoNvidia | Lookup::Unlisted));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
