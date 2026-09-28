//! The real sound devices, through cpal: WASAPI on Windows, Core Audio on
//! macOS, ALSA on Linux (which reaches PipeWire and PulseAudio through
//! their ALSA plugins, and follows their default device).

use std::str::FromStr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};

use super::{DeviceEvent, Driver, OpenedOutput, OutputDevice, Renderer};
use crate::error::{ErrorKind, Failure};

pub struct CpalDriver {
    host: cpal::Host,
    stream: Option<cpal::Stream>,
    events: Arc<Mutex<Vec<DeviceEvent>>>,
    running: bool,
}

impl Default for CpalDriver {
    fn default() -> Self {
        Self::new()
    }
}

impl CpalDriver {
    pub fn new() -> Self {
        Self {
            host: cpal::default_host(),
            stream: None,
            events: Arc::new(Mutex::new(Vec::new())),
            running: false,
        }
    }

    fn describe(device: &cpal::Device, default_id: Option<&str>) -> Option<OutputDevice> {
        let id = device.id().ok()?.to_string();
        let name = device.description().map(|d| d.name().to_string()).unwrap_or_else(|_| device.to_string());
        let is_default = default_id == Some(id.as_str());
        Some(OutputDevice { id, name, is_default })
    }

    fn find(&self, id: &str) -> Option<cpal::Device> {
        if let Ok(parsed) = cpal::DeviceId::from_str(id)
            && let Some(device) = self.host.device_by_id(&parsed)
        {
            return Some(device);
        }
        let mut all = self.host.output_devices().ok()?;
        all.find(|d| d.id().map(|i| i.to_string() == id).unwrap_or(false))
    }
}

fn device_failure(e: impl std::fmt::Display) -> Failure {
    Failure::new(ErrorKind::Device, e.to_string())
}

impl Driver for CpalDriver {
    fn devices(&mut self) -> Vec<OutputDevice> {
        let default_id = self.host.default_output_device().and_then(|d| d.id().ok()).map(|i| i.to_string());
        match self.host.output_devices() {
            Ok(devices) => devices.filter_map(|d| Self::describe(&d, default_id.as_deref())).collect(),
            Err(e) => {
                log::warn!("could not list devices: {e}");
                Vec::new()
            }
        }
    }

    fn default_device(&mut self) -> Option<OutputDevice> {
        let device = self.host.default_output_device()?;
        let id = device.id().ok()?.to_string();
        Self::describe(&device, Some(&id))
    }

    fn open(
        &mut self,
        id: Option<&str>,
        make: &mut dyn FnMut(u32, u16) -> Renderer,
    ) -> Result<OpenedOutput, Failure> {
        self.close();
        let device = match id {
            Some(id) => {
                self.find(id).ok_or_else(|| Failure::new(ErrorKind::Device, format!("no device {id}")))?
            }
            None => self
                .host
                .default_output_device()
                .ok_or_else(|| Failure::new(ErrorKind::Device, "no sound device"))?,
        };
        let default_id = self.host.default_output_device().and_then(|d| d.id().ok()).map(|i| i.to_string());
        let info = Self::describe(&device, default_id.as_deref()).unwrap_or(OutputDevice {
            id: String::new(),
            name: device.to_string(),
            is_default: id.is_none(),
        });
        // The device's own rate and format, so the system does not convert again.
        let supported = device.default_output_config().map_err(device_failure)?;
        let config = supported.config();
        let (rate, channels) = (config.sample_rate, config.channels);
        let renderer = make(rate, channels);
        let events = self.events.clone();
        let on_error = move |e: cpal::Error| {
            let Some(event) = device_event(e.kind(), || e.to_string()) else {
                log::debug!("sound device hiccup: {e}");
                return;
            };
            if let Ok(mut list) = events.lock() {
                list.push(event);
            }
        };
        let stream = match supported.sample_format() {
            cpal::SampleFormat::F32 => build::<f32>(&device, &config, renderer, on_error),
            cpal::SampleFormat::I16 => build::<i16>(&device, &config, renderer, on_error),
            cpal::SampleFormat::I32 => build::<i32>(&device, &config, renderer, on_error),
            cpal::SampleFormat::U16 => build::<u16>(&device, &config, renderer, on_error),
            cpal::SampleFormat::F64 => build::<f64>(&device, &config, renderer, on_error),
            other => {
                return Err(Failure::new(ErrorKind::Device, format!("unsupported sample format {other:?}")));
            }
        }
        .map_err(device_failure)?;
        stream.play().map_err(device_failure)?;
        self.stream = Some(stream);
        self.running = true;
        Ok(OpenedOutput { rate, channels, device: info })
    }

    fn close(&mut self) {
        self.stream = None;
        self.running = false;
    }

    fn set_running(&mut self, running: bool) {
        if running == self.running {
            return;
        }
        if let Some(stream) = &self.stream {
            let result = if running { stream.play() } else { stream.pause() };
            match result {
                Ok(()) => self.running = running,
                // Some devices cannot pause; then they keep playing silence.
                Err(e) => log::debug!("could not change the stream: {e}"),
            }
        }
    }

    fn take_events(&mut self) -> Vec<DeviceEvent> {
        self.events.lock().map(|mut list| std::mem::take(&mut *list)).unwrap_or_default()
    }
}

fn build<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    mut renderer: Renderer,
    on_error: impl FnMut(cpal::Error) + Send + 'static,
) -> Result<cpal::Stream, cpal::Error>
where
    T: cpal::SizedSample + cpal::FromSample<f32>,
{
    device.build_output_stream::<T, _, _>(
        *config,
        move |data: &mut [T], info: &cpal::OutputCallbackInfo| {
            let ts = info.timestamp();
            let latency = ts.playback.saturating_duration_since(ts.callback);
            renderer.render(data, latency);
        },
        on_error,
        Some(Duration::from_secs(5)),
    )
}

// What a stream error means for the device. Only a device or host that is
// gone, or a stream that must be rebuilt, loses it; anything else is a
// hiccup the stream carries on through. (The PulseAudio bridge on Linux
// fails a timing call until its first timing report; taking that as a lost
// device reopened it forever, with no sound.)
fn device_event(kind: cpal::ErrorKind, why: impl FnOnce() -> String) -> Option<DeviceEvent> {
    match kind {
        cpal::ErrorKind::DeviceChanged => Some(DeviceEvent::Rerouted),
        cpal::ErrorKind::DeviceNotAvailable
        | cpal::ErrorKind::HostUnavailable
        | cpal::ErrorKind::StreamInvalidated => Some(DeviceEvent::Lost(why())),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_gone_device_is_lost() {
        assert!(matches!(
            device_event(cpal::ErrorKind::DeviceNotAvailable, || "gone".into()),
            Some(DeviceEvent::Lost(_))
        ));
        assert!(matches!(
            device_event(cpal::ErrorKind::StreamInvalidated, String::new),
            Some(DeviceEvent::Lost(_))
        ));
        assert!(matches!(
            device_event(cpal::ErrorKind::DeviceChanged, String::new),
            Some(DeviceEvent::Rerouted)
        ));
        // An underrun, a busy moment or a backend's own error is carried on through.
        assert!(device_event(cpal::ErrorKind::Xrun, String::new).is_none());
        assert!(device_event(cpal::ErrorKind::DeviceBusy, String::new).is_none());
    }
}
