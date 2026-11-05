//! Keeps NVIDIA's game overlay off an Octo exe: `octo-nvidia keep-off <exe>`.
//! Octo runs it as an administrator (NVIDIA lets only one change the
//! setting); the exit code says how it went (see nvidia_profile).

#![windows_subsystem = "windows"]

use octo_audio::nvidia_profile::{EXIT_BAD_PATH, keep_off};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let code = match (args.get(1).map(String::as_str), args.get(2)) {
        (Some("keep-off"), Some(exe)) => keep_off(exe),
        _ => EXIT_BAD_PATH,
    };
    std::process::exit(code);
}
