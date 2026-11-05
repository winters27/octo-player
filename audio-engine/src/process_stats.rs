//! This process's own memory and handle counters, for the app's memory log.

#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct ProcessStats {
    /// Private bytes (Task Manager's "Commit size"): memory only this process holds.
    pub private_bytes: Option<u64>,
    /// The working set: the process's memory that is in RAM now.
    pub working_set: Option<u64>,
    /// Open handles (Task Manager's "Handles").
    pub handles: Option<u32>,
    /// Running threads (Task Manager's "Threads").
    pub threads: Option<u32>,
    /// "GPU dedicated memory": video memory used by this process.
    pub gpu_dedicated: Option<u64>,
    /// "GPU shared memory": system memory the graphics card uses for this process.
    pub gpu_shared: Option<u64>,
    /// "I/O other bytes" (Process Explorer): where socket reads land.
    pub io_other_bytes: Option<u64>,
    /// Graphics memory the driver committed for this process, in video
    /// memory or moved out of it; part of the private bytes.
    pub gpu_committed: Option<u64>,
    /// Memory of this process moved to swap (Linux).
    pub swap: Option<u64>,
}

/// The counters now; each is `None` when it cannot be read.
#[uniffi::export]
pub fn process_stats() -> ProcessStats {
    #[cfg(windows)]
    return windows_stats();
    #[cfg(target_os = "linux")]
    return linux_stats();
    #[cfg(not(any(windows, target_os = "linux")))]
    return ProcessStats::default();
}

#[cfg(windows)]
fn windows_stats() -> ProcessStats {
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, TH32CS_SNAPTHREAD, THREADENTRY32, Thread32First, Thread32Next,
    };
    use windows::Win32::System::ProcessStatus::{GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS_EX};
    use windows::Win32::System::Threading::{
        GetCurrentProcess, GetCurrentProcessId, GetProcessHandleCount, GetProcessIoCounters, IO_COUNTERS,
    };

    let mut stats = ProcessStats::default();
    unsafe {
        let process = GetCurrentProcess();

        let mut counters = PROCESS_MEMORY_COUNTERS_EX::default();
        let size = std::mem::size_of::<PROCESS_MEMORY_COUNTERS_EX>() as u32;
        if GetProcessMemoryInfo(process, &mut counters as *mut _ as *mut _, size).is_ok() {
            stats.private_bytes = Some(counters.PrivateUsage as u64);
            stats.working_set = Some(counters.WorkingSetSize as u64);
        }

        let mut handles = 0u32;
        if GetProcessHandleCount(process, &mut handles).is_ok() {
            stats.handles = Some(handles);
        }

        let mut io = IO_COUNTERS::default();
        if GetProcessIoCounters(process, &mut io).is_ok() {
            stats.io_other_bytes = Some(io.OtherTransferCount);
        }

        if let Ok(snapshot) = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0) {
            let me = GetCurrentProcessId();
            let mut entry =
                THREADENTRY32 { dwSize: std::mem::size_of::<THREADENTRY32>() as u32, ..Default::default() };
            let mut count = 0u32;
            let mut more = Thread32First(snapshot, &mut entry).is_ok();
            let listed = more;
            while more {
                if entry.th32OwnerProcessID == me {
                    count += 1;
                }
                more = Thread32Next(snapshot, &mut entry).is_ok();
            }
            if listed {
                stats.threads = Some(count);
            }
            let _ = CloseHandle(snapshot);
        }
    }
    if let Some((dedicated, shared)) = windows_gpu() {
        stats.gpu_dedicated = Some(dedicated);
        stats.gpu_shared = Some(shared);
    }
    // In its own query, so a system without this counter still reads the two above.
    stats.gpu_committed = windows_gpu_counters(&["Total Committed"]).map(|v| v[0]);
    stats
}

// This process's graphics memory counters by name, from one query.
#[cfg(windows)]
fn windows_gpu() -> Option<(u64, u64)> {
    windows_gpu_counters(&["Dedicated Usage", "Shared Usage"]).map(|v| (v[0], v[1]))
}

// This process's graphics memory from the performance counters named, in
// that order, opened and closed on each call. None when the counter set is
// missing or any step fails.
#[cfg(windows)]
fn windows_gpu_counters(names: &[&str]) -> Option<Vec<u64>> {
    use windows::Win32::System::Performance::{
        PDH_FMT_COUNTERVALUE_ITEM_W, PDH_FMT_LARGE, PDH_HCOUNTER, PDH_HQUERY, PdhAddEnglishCounterW,
        PdhCloseQuery, PdhCollectQueryData, PdhGetFormattedCounterArrayW, PdhOpenQueryW,
    };
    use windows::Win32::System::Threading::GetCurrentProcessId;
    use windows::core::PCWSTR;

    // Zero from the counter calls is ERROR_SUCCESS.
    unsafe fn sum(counter: PDH_HCOUNTER) -> Option<u64> {
        let (mut size, mut count) = (0u32, 0u32);
        unsafe {
            // The first call only says how big a buffer is needed (it answers "more data").
            PdhGetFormattedCounterArrayW(counter, PDH_FMT_LARGE, &mut size, &mut count, None);
            if size == 0 {
                return None;
            }
            // 8-byte units, so the items are aligned.
            let mut buffer = vec![0u64; (size as usize).div_ceil(8)];
            let items = buffer.as_mut_ptr() as *mut PDH_FMT_COUNTERVALUE_ITEM_W;
            if PdhGetFormattedCounterArrayW(counter, PDH_FMT_LARGE, &mut size, &mut count, Some(items)) != 0 {
                return None;
            }
            let mut total = 0u64;
            for i in 0..count as usize {
                total += (*items.add(i)).FmtValue.Anonymous.largeValue.max(0) as u64;
            }
            Some(total)
        }
    }

    unsafe {
        let mut query = PDH_HQUERY::default();
        if PdhOpenQueryW(PCWSTR::null(), 0, &mut query) != 0 {
            return None;
        }
        let pid = GetCurrentProcessId();
        let add = |name: &str| -> Option<PDH_HCOUNTER> {
            let path: Vec<u16> =
                format!("\\GPU Process Memory(pid_{pid}_*)\\{name}\0").encode_utf16().collect();
            let mut counter = PDH_HCOUNTER::default();
            (PdhAddEnglishCounterW(query, PCWSTR(path.as_ptr()), 0, &mut counter) == 0).then_some(counter)
        };
        let found = (|| {
            let counters = names.iter().map(|name| add(name)).collect::<Option<Vec<_>>>()?;
            // "No data" here means this process has no graphics memory yet.
            // PDH_NO_DATA (0x800007D5): the process has no GPU instances, so it holds none.
            let status = PdhCollectQueryData(query);
            if status == 0x800007D5 {
                return Some(vec![0; names.len()]);
            }
            if status != 0 {
                return None;
            }
            counters.into_iter().map(|counter| sum(counter)).collect::<Option<Vec<_>>>()
        })();
        PdhCloseQuery(query);
        found
    }
}

#[cfg(target_os = "linux")]
fn linux_stats() -> ProcessStats {
    let mut stats = ProcessStats::default();
    if let Ok(status) = std::fs::read_to_string("/proc/self/status") {
        let kb = |line: &str| line.split_whitespace().nth(1).and_then(|n| n.parse::<u64>().ok());
        for line in status.lines() {
            if line.starts_with("RssAnon:") {
                stats.private_bytes = kb(line).map(|k| k * 1024);
            } else if line.starts_with("VmRSS:") {
                stats.working_set = kb(line).map(|k| k * 1024);
            } else if line.starts_with("Threads:") {
                stats.threads = kb(line).map(|n| n as u32);
            } else if line.starts_with("VmSwap:") {
                stats.swap = kb(line).map(|k| k * 1024);
            }
        }
    }
    stats.handles = std::fs::read_dir("/proc/self/fd").ok().map(|dir| dir.count() as u32);
    stats
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(any(windows, target_os = "linux"))]
    #[test]
    fn reads_this_process() {
        let stats = process_stats();
        assert!(stats.private_bytes.is_some_and(|n| n > 0), "{stats:?}");
        assert!(stats.working_set.is_some_and(|n| n > 0), "{stats:?}");
        assert!(stats.threads.is_some_and(|n| n > 0), "{stats:?}");
        #[cfg(windows)]
        {
            assert!(stats.handles.is_some(), "{stats:?}");
            assert!(stats.io_other_bytes.is_some(), "{stats:?}");
        }
        println!(
            "gpu dedicated {:?}, gpu shared {:?}, gpu committed {:?}",
            stats.gpu_dedicated, stats.gpu_shared, stats.gpu_committed
        );
    }

    #[cfg(any(windows, target_os = "linux"))]
    #[test]
    fn counts_memory_that_is_touched() {
        // A block so large that other threads' allocations and frees cannot
        // hide its rise.
        const BLOCK: u64 = 256 << 20;
        let before = process_stats().private_bytes.unwrap();
        let held = std::hint::black_box(vec![1u8; BLOCK as usize]);
        let after = process_stats().private_bytes.unwrap();
        std::hint::black_box(&held);
        let rise = after.saturating_sub(before);
        assert!(rise >= BLOCK / 2, "rose by {rise} of {BLOCK}: {before} to {after}");
    }

    // Run alone: `cargo test --release --lib many_calls_keep_nothing -- --ignored --test-threads=1`.
    #[cfg(windows)]
    #[test]
    #[ignore]
    fn many_calls_keep_nothing() {
        for _ in 0..200 {
            std::hint::black_box(process_stats());
        }
        let before = process_stats();
        for _ in 0..5000 {
            std::hint::black_box(process_stats());
        }
        let after = process_stats();
        let bytes = after.private_bytes.unwrap().saturating_sub(before.private_bytes.unwrap());
        let handles = after.handles.unwrap().saturating_sub(before.handles.unwrap());
        println!("private bytes +{bytes}, handles +{handles}");
        assert!(handles <= 5, "handles grew by {handles}");
        assert!(bytes <= 4 << 20, "private bytes grew by {bytes}");
    }
}
