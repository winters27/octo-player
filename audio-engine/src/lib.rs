//! The audio engine for the Octo desktop app.

uniffi::setup_scaffolding!();

pub mod crossfade;
pub mod error;
pub mod fifo;
pub mod pace;
pub mod sound;
pub mod source;

#[cfg(test)]
mod testing;
