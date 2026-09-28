//! Prints what the system's media controls show as playing right now, one
//! line per app, for checking a running Octo by hand:
//!
//!     cargo run --example sessions

use octo_system::octo_system_describe_sessions;

fn main() {
    let mut buffer = vec![0u8; 16_384];
    let n = unsafe { octo_system_describe_sessions(buffer.as_mut_ptr(), buffer.len()) };
    if n < 0 {
        eprintln!("the system would not say ({n})");
        std::process::exit(1);
    }
    println!("{}", String::from_utf8_lossy(&buffer[..(n as usize).min(buffer.len())]));
}
