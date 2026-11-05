//! What other programs put into this process (overlays and capture hooks),
//! and the memory of the overlay programs running beside it, for the app's
//! memory log.

/// Overlay and capture modules known by name, and whose they are.
const KNOWN: &[(&str, &str)] = &[
    ("nvspcap64.dll", "NVIDIA overlay"),
    ("gameoverlayrenderer64.dll", "Steam overlay"),
    ("discordhook64.dll", "Discord overlay"),
    ("rtsshooks64.dll", "RivaTuner"),
    ("graphics-hook64.dll", "OBS capture"),
    ("eosoverlayrenderer-win64-shipping.dll", "Epic overlay"),
    ("libmangohud.so", "MangoHud"),
    ("libmangohud_shim.so", "MangoHud"),
    ("libmangohud_dlsym.so", "MangoHud"),
    ("gameoverlayrenderer.so", "Steam overlay"),
    ("libobs_glcapture.so", "OBS capture"),
    ("libvklayer_obs_vkcapture.so", "OBS capture"),
];

/// The most unknown module names kept.
const MAX_FOREIGN: usize = 20;

/// The modules loaded in this process that came from elsewhere.
#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct HookReport {
    /// Known overlay and capture modules, by file name.
    pub hooks: Vec<String>,
    /// How many other modules come from outside the system and the app.
    pub foreign: u32,
    /// Their file names (at most 20), never their folders.
    pub foreign_names: Vec<String>,
}

/// One overlay program's processes beside this one.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct OverlayProcess {
    /// A short name without spaces, like `nvidia-overlay`.
    pub key: String,
    /// How many processes it runs.
    pub count: u32,
    /// Their private bytes together; None when any could not be read.
    pub private_bytes: Option<u64>,
}

/// The overlay and capture modules loaded now, and how many others came
/// from outside the system and the app. None when the list cannot be read,
/// so a failed read never looks like no hooks.
#[uniffi::export]
pub fn loaded_hooks() -> Option<HookReport> {
    let exe = std::env::current_exe().ok()?;
    let own_root = root_of(&exe, cfg!(windows))?;
    Some(classify(&module_paths()?, &system_dirs(), &own_root))
}

/// The overlay programs running now, each with its processes' memory. None
/// when the processes cannot be listed, so a failed read never looks like
/// none running.
#[uniffi::export]
pub fn overlay_processes() -> Option<Vec<OverlayProcess>> {
    process_snapshot().map(sum_by_key)
}

/// The file name of a path with either kind of separator.
fn base_name(path: &str) -> &str {
    path.rsplit(['/', '\\']).next().unwrap_or(path)
}

/// A folder in one form for comparing: lower case, forward slashes, ending
/// in a slash.
fn folder_key(dir: &str) -> String {
    let mut key = dir.replace('\\', "/").to_lowercase();
    if !key.ends_with('/') {
        key.push('/');
    }
    key
}

/// Sorts module paths into known hooks and modules from outside the system
/// folders and `own_root`. Names compare without case; only file names
/// come out.
pub(crate) fn classify(paths: &[String], system_dirs: &[String], own_root: &str) -> HookReport {
    let system: Vec<String> = system_dirs.iter().map(|d| folder_key(d)).collect();
    let own = folder_key(own_root);
    let mut hooks = Vec::new();
    let mut foreign = Vec::new();
    for path in paths {
        let name = base_name(path).to_lowercase();
        if name.is_empty() {
            continue;
        }
        if KNOWN.iter().any(|(known, _)| *known == name) {
            hooks.push(name);
            continue;
        }
        let key = path.replace('\\', "/").to_lowercase();
        if key.starts_with(&own) || system.iter().any(|dir| key.starts_with(dir)) {
            continue;
        }
        foreign.push(name);
    }
    hooks.sort();
    hooks.dedup();
    foreign.sort();
    foreign.dedup();
    let count = foreign.len() as u32;
    foreign.truncate(MAX_FOREIGN);
    HookReport { hooks, foreign: count, foreign_names: foreign }
}

/// Whether a file name is a shared library: ends in `.so` or `.so.N...`.
#[cfg(any(target_os = "linux", test))]
fn is_shared_object(name: &str) -> bool {
    let mut from = 0;
    while let Some(at) = name[from..].find(".so") {
        let rest = &name[from + at + 3..];
        let numbered = rest.starts_with('.')
            && rest[1..].split('.').all(|part| !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit()));
        if rest.is_empty() || numbered {
            return true;
        }
        from += at + 3;
    }
    false
}

/// The shared library paths in a `/proc/<pid>/maps` text. The path is the
/// rest of the line after the fifth field, so paths with spaces stay whole.
#[cfg(any(target_os = "linux", test))]
pub(crate) fn maps_paths(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    for line in text.lines() {
        let mut rest = line;
        for _ in 0..5 {
            rest = rest.trim_start();
            rest = match rest.find(char::is_whitespace) {
                Some(end) => &rest[end..],
                None => "",
            };
        }
        let mut path = rest.trim();
        if let Some(kept) = path.strip_suffix(" (deleted)") {
            path = kept;
        }
        if path.is_empty()
            || path.starts_with('[')
            || path.starts_with("/memfd:")
            || path.starts_with("anon_inode:")
        {
            continue;
        }
        if is_shared_object(base_name(path)) && !out.iter().any(|p: &String| p == path) {
            out.push(path.to_string());
        }
    }
    out
}

/// Totals processes by overlay key, in the order the keys first appear. A
/// process whose bytes are None makes its key's total None.
pub(crate) fn sum_by_key(processes: Vec<(&'static str, Option<u64>)>) -> Vec<OverlayProcess> {
    let mut out: Vec<OverlayProcess> = Vec::new();
    for (key, bytes) in processes {
        match out.iter_mut().find(|o| o.key == key) {
            Some(o) => {
                o.count += 1;
                o.private_bytes = o.private_bytes.zip(bytes).map(|(a, b)| a + b);
            }
            None => out.push(OverlayProcess { key: key.to_string(), count: 1, private_bytes: bytes }),
        }
    }
    out
}

/// The folder Octo is installed in, from its launcher: on Windows the
/// launcher sits in it, on Linux it sits in its bin folder.
pub(crate) fn root_of(exe: &std::path::Path, windows: bool) -> Option<String> {
    let dir = exe.parent()?;
    let root = if windows { dir } else { dir.parent().unwrap_or(dir) };
    Some(root.to_string_lossy().into_owned())
}

#[cfg(windows)]
fn system_dirs() -> Vec<String> {
    use windows::Win32::System::SystemInformation::GetWindowsDirectoryW;
    let mut buffer = [0u16; 260];
    let len = unsafe { GetWindowsDirectoryW(Some(&mut buffer)) } as usize;
    if len == 0 || len > buffer.len() {
        return vec![std::env::var("SystemRoot").unwrap_or_else(|_| "C:\\Windows".to_string())];
    }
    vec![String::from_utf16_lossy(&buffer[..len])]
}

#[cfg(not(windows))]
fn system_dirs() -> Vec<String> {
    ["/usr/lib", "/usr/lib64", "/lib", "/lib64", "/usr/local/lib"].iter().map(|d| d.to_string()).collect()
}

// Every module's path, from a module snapshot. The list can change while it
// is taken (a module loading), which the snapshot reports as a bad length;
// it is taken again, a few times at most.
#[cfg(windows)]
fn module_paths() -> Option<Vec<String>> {
    use windows::Win32::Foundation::{CloseHandle, ERROR_BAD_LENGTH};
    use windows::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, MODULEENTRY32W, Module32FirstW, Module32NextW, TH32CS_SNAPMODULE,
    };

    for _ in 0..5 {
        let snapshot = match unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, 0) } {
            Ok(handle) => handle,
            Err(e) if e.code() == ERROR_BAD_LENGTH.to_hresult() => continue,
            Err(_) => return None,
        };
        let mut paths = Vec::new();
        let mut entry =
            MODULEENTRY32W { dwSize: std::mem::size_of::<MODULEENTRY32W>() as u32, ..Default::default() };
        let mut more = unsafe { Module32FirstW(snapshot, &mut entry) }.is_ok();
        while more {
            let len = entry.szExePath.iter().position(|&c| c == 0).unwrap_or(entry.szExePath.len());
            paths.push(String::from_utf16_lossy(&entry.szExePath[..len]));
            more = unsafe { Module32NextW(snapshot, &mut entry) }.is_ok();
        }
        let _ = unsafe { CloseHandle(snapshot) };
        return Some(paths);
    }
    None
}

// Read as bytes: a path that is not UTF-8 must not lose the whole list.
#[cfg(target_os = "linux")]
fn module_paths() -> Option<Vec<String>> {
    std::fs::read("/proc/self/maps").ok().map(|bytes| maps_paths(&String::from_utf8_lossy(&bytes)))
}

#[cfg(not(any(windows, target_os = "linux")))]
fn module_paths() -> Option<Vec<String>> {
    None
}

// Overlay programs by process file name.
#[cfg(windows)]
const OVERLAY_EXES: &[(&str, &str)] = &[
    ("nvidia overlay.exe", "nvidia-overlay"),
    ("nvidia share.exe", "nvidia-share"),
    ("gameoverlayui.exe", "steam-overlay"),
    ("rtss.exe", "rtss"),
];

#[cfg(windows)]
fn process_snapshot() -> Option<Vec<(&'static str, Option<u64>)>> {
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, PROCESSENTRY32W, Process32FirstW, Process32NextW, TH32CS_SNAPPROCESS,
    };
    use windows::Win32::System::ProcessStatus::{GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS_EX};
    use windows::Win32::System::Threading::{OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};

    let mut out = Vec::new();
    let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) }.ok()?;
    let mut entry =
        PROCESSENTRY32W { dwSize: std::mem::size_of::<PROCESSENTRY32W>() as u32, ..Default::default() };
    let mut more = unsafe { Process32FirstW(snapshot, &mut entry) }.is_ok();
    while more {
        let len = entry.szExeFile.iter().position(|&c| c == 0).unwrap_or(entry.szExeFile.len());
        let name = String::from_utf16_lossy(&entry.szExeFile[..len]).to_lowercase();
        if let Some((_, key)) = OVERLAY_EXES.iter().find(|(exe, _)| *exe == name) {
            let bytes = unsafe {
                OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, entry.th32ProcessID).ok().and_then(
                    |process| {
                        let mut counters = PROCESS_MEMORY_COUNTERS_EX::default();
                        let size = std::mem::size_of::<PROCESS_MEMORY_COUNTERS_EX>() as u32;
                        let read = GetProcessMemoryInfo(process, &mut counters as *mut _ as *mut _, size)
                            .ok()
                            .map(|_| counters.PrivateUsage as u64);
                        let _ = CloseHandle(process);
                        read
                    },
                )
            };
            out.push((*key, bytes));
        }
        more = unsafe { Process32NextW(snapshot, &mut entry) }.is_ok();
    }
    let _ = unsafe { CloseHandle(snapshot) };
    Some(out)
}

// Steam's overlay is the one overlay program with its own process on Linux.
#[cfg(target_os = "linux")]
fn process_snapshot() -> Option<Vec<(&'static str, Option<u64>)>> {
    let mut out = Vec::new();
    let dir = std::fs::read_dir("/proc").ok()?;
    for entry in dir.flatten() {
        let path = entry.path();
        let is_pid =
            path.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.bytes().all(|b| b.is_ascii_digit()));
        if !is_pid {
            continue;
        }
        let Ok(comm) = std::fs::read_to_string(path.join("comm")) else { continue };
        if comm.trim() != "gameoverlayui" {
            continue;
        }
        let bytes = std::fs::read_to_string(path.join("status")).ok().and_then(|status| {
            let kb = |key: &str| {
                status
                    .lines()
                    .find(|l| l.starts_with(key))
                    .and_then(|l| l.split_whitespace().nth(1)?.parse::<u64>().ok())
            };
            Some((kb("RssAnon:")? + kb("VmSwap:").unwrap_or(0)) * 1024)
        });
        out.push(("steam-overlay", bytes));
    }
    Some(out)
}

#[cfg(not(any(windows, target_os = "linux")))]
fn process_snapshot() -> Option<Vec<(&'static str, Option<u64>)>> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn paths(list: &[&str]) -> Vec<String> {
        list.iter().map(|p| p.to_string()).collect()
    }

    #[test]
    fn sorts_a_windows_process() {
        let report = classify(
            &paths(&[
                r"C:\Users\Sam\AppData\Local\OctoPlayer\Octo.exe",
                r"C:\Users\Sam\AppData\Local\OctoPlayer\runtime\bin\server\jvm.dll",
                r"C:\WINDOWS\SYSTEM32\ntdll.dll",
                r"C:\WINDOWS\System32\DriverStore\FileRepository\nv.inf_amd64\nvd3dumx.dll",
                r"C:\WINDOWS\system32\nvspcap64.dll",
                r"C:\Program Files (x86)\Steam\GameOverlayRenderer64.dll",
                r"C:\Program Files\Bonjour\mdnsNSP.dll",
                r"C:\Users\Sam\AppData\Local\Other\helper.dll",
            ]),
            &paths(&[r"C:\Windows"]),
            r"C:\Users\Sam\AppData\Local\OctoPlayer",
        );
        assert_eq!(report.hooks, vec!["gameoverlayrenderer64.dll", "nvspcap64.dll"]);
        assert_eq!(report.foreign, 2);
        assert_eq!(report.foreign_names, vec!["helper.dll", "mdnsnsp.dll"]);
    }

    #[test]
    fn a_sibling_folder_of_the_app_is_foreign() {
        // The Windows image root is the launcher's own folder, so a program
        // beside it in the same parent folder is not taken for Octo's.
        let report = classify(
            &paths(&[r"C:\Users\Sam\AppData\Local\Injector\inject.dll"]),
            &paths(&[r"C:\Windows"]),
            r"C:\Users\Sam\AppData\Local\OctoPlayer",
        );
        assert_eq!(report.foreign_names, vec!["inject.dll"]);
    }

    #[test]
    fn sorts_a_linux_process() {
        let report = classify(
            &paths(&[
                "/opt/octo/lib/runtime/lib/libjvm.so",
                "/usr/lib/x86_64-linux-gnu/libc.so.6",
                "/usr/lib/x86_64-linux-gnu/mangohud/libMangoHud.so",
                "/home/sam/.local/share/Steam/ubuntu12_64/gameoverlayrenderer.so",
                "/home/sam/tools/libhelper.so.1",
            ]),
            &system_dirs_linux(),
            "/opt/octo",
        );
        assert_eq!(report.hooks, vec!["gameoverlayrenderer.so", "libmangohud.so"]);
        assert_eq!(report.foreign_names, vec!["libhelper.so.1"]);
    }

    #[test]
    fn the_install_folder_is_the_launchers_on_windows_and_above_bin_on_linux() {
        // A backslash path only splits into folders on Windows.
        #[cfg(windows)]
        assert_eq!(
            root_of(std::path::Path::new(r"C:\Users\Sam\AppData\Local\OctoPlayer\Octo.exe"), true).unwrap(),
            r"C:\Users\Sam\AppData\Local\OctoPlayer"
        );
        assert_eq!(root_of(std::path::Path::new("/opt/octo/bin/Octo"), false).unwrap(), "/opt/octo");
    }

    fn system_dirs_linux() -> Vec<String> {
        paths(&["/usr/lib", "/usr/lib64", "/lib", "/lib64", "/usr/local/lib"])
    }

    #[test]
    fn keeps_at_most_twenty_names_and_counts_them_all() {
        let many: Vec<String> = (0..25).map(|i| format!(r"D:\tools\t{i:02}.dll")).collect();
        let report = classify(&many, &paths(&[r"C:\Windows"]), r"C:\Octo");
        assert_eq!(report.foreign, 25);
        assert_eq!(report.foreign_names.len(), 20);
        assert!(report.foreign_names.iter().all(|n| !n.contains('\\') && !n.contains('/')));
    }

    #[test]
    fn reads_shared_libraries_from_maps() {
        let text = "\
7f0000000000-7f0000001000 r--p 00000000 08:01 123 /usr/lib/x86_64-linux-gnu/libc.so.6
7f0000001000-7f0000002000 r-xp 00001000 08:01 123 /usr/lib/x86_64-linux-gnu/libc.so.6
7f0000002000-7f0000003000 r--p 00000000 08:01 456 /home/sam/My Apps/octo/lib/libskiko-linux-x64.so
7f0000003000-7f0000004000 r--p 00000000 08:01 789 /tmp/libgone.so (deleted)
7f0000004000-7f0000005000 rw-s 00000000 00:01 999 /memfd:wayland-shm (deleted)
7f0000005000-7f0000006000 rw-p 00000000 00:00 0 [heap]
7f0000006000-7f0000007000 r--p 00000000 08:01 321 /run/user/1000/app.sock
7f0000007000-7f0000008000 r--p 00000000 08:01 654 /usr/share/fonts/DejaVuSans.ttf
7f0000008000-7f0000009000 rw-p 00000000 00:00 0
7f0000009000-7f000000a000 r--p 00000000 08:01 777 /usr/lib/libVkLayer_obs_vkcapture.so
";
        assert_eq!(
            maps_paths(text),
            vec![
                "/usr/lib/x86_64-linux-gnu/libc.so.6",
                "/home/sam/My Apps/octo/lib/libskiko-linux-x64.so",
                "/tmp/libgone.so",
                "/usr/lib/libVkLayer_obs_vkcapture.so",
            ]
        );
    }

    #[test]
    fn knows_a_shared_library_name() {
        assert!(is_shared_object("libc.so"));
        assert!(is_shared_object("libc.so.6"));
        assert!(is_shared_object("libfoo.so.1.2.3"));
        assert!(!is_shared_object("app.sock"));
        assert!(!is_shared_object("libc.so.x"));
        assert!(!is_shared_object("readme.so."));
    }

    #[test]
    fn totals_overlay_processes_by_key() {
        let summed = sum_by_key(vec![
            ("nvidia-overlay", Some(100)),
            ("rtss", Some(5)),
            ("nvidia-overlay", Some(50)),
            ("rtss", None),
        ]);
        assert_eq!(
            summed,
            vec![
                OverlayProcess { key: "nvidia-overlay".into(), count: 2, private_bytes: Some(150) },
                OverlayProcess { key: "rtss".into(), count: 1 + 1, private_bytes: None },
            ]
        );
    }

    #[cfg(any(windows, target_os = "linux"))]
    #[test]
    fn this_process_lists_no_system_module_as_foreign() {
        let report = loaded_hooks().expect("the module list reads");
        assert!(overlay_processes().is_some());
        let system = system_dirs();
        let all = module_paths().unwrap();
        assert!(!all.is_empty());
        for name in &report.foreign_names {
            let from_system = all.iter().any(|p| {
                base_name(p).eq_ignore_ascii_case(name)
                    && system.iter().any(|d| folder_key(p).starts_with(&folder_key(d)))
            });
            assert!(!from_system, "{name} is a system module");
        }
    }

    // Run alone: `cargo test --release --lib snapshot_time -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn snapshot_time() {
        let start = std::time::Instant::now();
        for _ in 0..20 {
            std::hint::black_box(loaded_hooks());
            std::hint::black_box(overlay_processes());
        }
        println!("modules + processes: {:?} a call", start.elapsed() / 20);
    }
}
