//! The audio engine for the Octo desktop app.

uniffi::setup_scaffolding!();

pub mod crossfade;
pub mod decode;
pub mod error;
pub mod fifo;
#[cfg(feature = "opus")]
pub mod opus;
pub mod pace;
pub mod sound;
pub mod source;

#[cfg(test)]
mod testing;
#[cfg(test)]
mod tests;
