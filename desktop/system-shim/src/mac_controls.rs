//! macOS: the Now Playing centre (Control Centre, the menu bar's Now
//! Playing, the lock screen) and the remote commands behind the media keys,
//! the Touch Bar and AirPods, plus the workspace's sleep and wake news.
//!
//! Apple wants these touched on the main thread, which AWT keeps running,
//! so every call here is handed to the main queue and returns at once.

use std::cell::RefCell;
use std::ptr::NonNull;

use block2::RcBlock;
use dispatch2::DispatchQueue;
use objc2::AllocAnyThread;
use objc2::rc::Retained;
use objc2::runtime::{AnyObject, ProtocolObject};
use objc2_app_kit::{NSImage, NSWorkspace, NSWorkspaceDidWakeNotification, NSWorkspaceWillSleepNotification};
use objc2_core_foundation::CGSize;
use objc2_foundation::{NSData, NSMutableDictionary, NSNotification, NSNumber, NSObjectProtocol, NSString};
use objc2_media_player::{
    MPChangePlaybackPositionCommandEvent, MPMediaItemArtwork, MPMediaItemPropertyAlbumArtist,
    MPMediaItemPropertyAlbumTitle, MPMediaItemPropertyArtist, MPMediaItemPropertyArtwork,
    MPMediaItemPropertyPlaybackDuration, MPMediaItemPropertyTitle, MPNowPlayingInfoCenter,
    MPNowPlayingInfoPropertyElapsedPlaybackTime, MPNowPlayingInfoPropertyPlaybackRate,
    MPNowPlayingPlaybackState, MPRemoteCommand, MPRemoteCommandCenter, MPRemoteCommandEvent,
    MPRemoteCommandHandlerStatus,
};

use crate::{
    EVENT_NEXT, EVENT_PAUSE, EVENT_PLAY, EVENT_PREVIOUS, EVENT_SEEK, EVENT_SLEEP, EVENT_STOP, EVENT_TOGGLE,
    EVENT_WAKE, NOT_SUPPORTED, Status, Track, emit,
};

// What is shown and listened to. Only ever touched on the main thread.
#[derive(Default)]
struct Now {
    started: bool,
    info: Option<Retained<NSMutableDictionary<NSString, AnyObject>>>,
    targets: Vec<(Retained<MPRemoteCommand>, Retained<AnyObject>)>,
    observers: Vec<Retained<ProtocolObject<dyn NSObjectProtocol>>>,
}

thread_local! {
    static NOW: RefCell<Now> = RefCell::new(Now::default());
}

fn on_main(work: impl FnOnce(&mut Now) + Send + 'static) {
    DispatchQueue::main().exec_async(move || NOW.with(|now| work(&mut now.borrow_mut())));
}

pub fn start() -> Result<(), i32> {
    on_main(|now| unsafe {
        if now.started {
            return;
        }
        now.started = true;
        let center = MPRemoteCommandCenter::sharedCommandCenter();
        let simple = [
            (center.playCommand(), EVENT_PLAY),
            (center.pauseCommand(), EVENT_PAUSE),
            (center.togglePlayPauseCommand(), EVENT_TOGGLE),
            (center.stopCommand(), EVENT_STOP),
            (center.nextTrackCommand(), EVENT_NEXT),
            (center.previousTrackCommand(), EVENT_PREVIOUS),
        ];
        for (command, kind) in simple {
            let handler = RcBlock::new(move |_: NonNull<MPRemoteCommandEvent>| {
                emit(kind, 0);
                MPRemoteCommandHandlerStatus::Success
            });
            command.setEnabled(true);
            let target = command.addTargetWithHandler(&handler);
            now.targets.push((command, target));
        }
        let seek: Retained<MPRemoteCommand> = Retained::into_super(center.changePlaybackPositionCommand());
        let handler = RcBlock::new(|event: NonNull<MPRemoteCommandEvent>| {
            let event = event.cast::<MPChangePlaybackPositionCommandEvent>();
            let seconds = event.as_ref().positionTime();
            emit(EVENT_SEEK, (seconds * 1000.0).round() as i64);
            MPRemoteCommandHandlerStatus::Success
        });
        seek.setEnabled(true);
        let target = seek.addTargetWithHandler(&handler);
        now.targets.push((seek, target));

        let news = NSWorkspace::sharedWorkspace().notificationCenter();
        for (name, kind) in
            [(NSWorkspaceWillSleepNotification, EVENT_SLEEP), (NSWorkspaceDidWakeNotification, EVENT_WAKE)]
        {
            let block = RcBlock::new(move |_: NonNull<NSNotification>| emit(kind, 0));
            now.observers.push(news.addObserverForName_object_queue_usingBlock(
                Some(name),
                None,
                None,
                &block,
            ));
        }
    });
    Ok(())
}

pub fn set_track(track: &Track) -> Result<(), i32> {
    let track = track.clone();
    on_main(move |now| unsafe {
        let info = NSMutableDictionary::<NSString, AnyObject>::new();
        info.insert(MPMediaItemPropertyTitle, &*NSString::from_str(&track.title));
        info.insert(MPMediaItemPropertyArtist, &*NSString::from_str(&track.artist));
        info.insert(MPMediaItemPropertyAlbumTitle, &*NSString::from_str(&track.album));
        info.insert(MPMediaItemPropertyAlbumArtist, &*NSString::from_str(&track.album_artist));
        info.insert(
            MPMediaItemPropertyPlaybackDuration,
            &*NSNumber::new_f64(track.duration_ms as f64 / 1000.0),
        );
        info.insert(MPNowPlayingInfoPropertyElapsedPlaybackTime, &*NSNumber::new_f64(0.0));
        info.insert(MPNowPlayingInfoPropertyPlaybackRate, &*NSNumber::new_f64(0.0));
        if let Some(artwork) = track.art.as_deref().and_then(artwork) {
            info.insert(MPMediaItemPropertyArtwork, &*artwork);
        }
        MPNowPlayingInfoCenter::defaultCenter().setNowPlayingInfo(Some(&info));
        now.info = Some(info);
    });
    Ok(())
}

// The cover as the artwork object the Now Playing centre asks for, which
// hands back the one picture at whatever size is wanted.
fn artwork(bytes: &[u8]) -> Option<Retained<AnyObject>> {
    let image = NSImage::initWithData(NSImage::alloc(), &NSData::with_bytes(bytes))?;
    let size = image.size();
    let kept = image.clone();
    let handler = RcBlock::new(move |_: CGSize| NonNull::from(&*kept));
    let artwork = unsafe {
        MPMediaItemArtwork::initWithBoundsSize_requestHandler(MPMediaItemArtwork::alloc(), size, &handler)
    };
    Some(Retained::into_super(Retained::into_super(artwork)))
}

pub fn set_playback(status: Status, position_ms: i64, can_previous: bool, can_next: bool) -> Result<(), i32> {
    on_main(move |now| unsafe {
        let center = MPRemoteCommandCenter::sharedCommandCenter();
        center.previousTrackCommand().setEnabled(can_previous);
        center.nextTrackCommand().setEnabled(can_next);
        let playing_center = MPNowPlayingInfoCenter::defaultCenter();
        if let Some(info) = &now.info {
            info.insert(
                MPNowPlayingInfoPropertyElapsedPlaybackTime,
                &*NSNumber::new_f64(position_ms as f64 / 1000.0),
            );
            let rate = if status == Status::Playing { 1.0 } else { 0.0 };
            info.insert(MPNowPlayingInfoPropertyPlaybackRate, &*NSNumber::new_f64(rate));
            playing_center.setNowPlayingInfo(Some(info));
        }
        playing_center.setPlaybackState(match status {
            Status::Playing => MPNowPlayingPlaybackState::Playing,
            Status::Paused => MPNowPlayingPlaybackState::Paused,
            Status::Stopped => MPNowPlayingPlaybackState::Stopped,
        });
    });
    Ok(())
}

pub fn clear() -> Result<(), i32> {
    on_main(|now| unsafe {
        now.info = None;
        let center = MPNowPlayingInfoCenter::defaultCenter();
        center.setNowPlayingInfo(None);
        center.setPlaybackState(MPNowPlayingPlaybackState::Stopped);
    });
    Ok(())
}

pub fn stop() {
    on_main(|now| unsafe {
        for (command, target) in now.targets.drain(..) {
            command.removeTarget(Some(&target));
        }
        let news = NSWorkspace::sharedWorkspace().notificationCenter();
        for observer in now.observers.drain(..) {
            news.removeObserver(observer.as_ref());
        }
        now.info = None;
        now.started = false;
        let center = MPNowPlayingInfoCenter::defaultCenter();
        center.setNowPlayingInfo(None);
        center.setPlaybackState(MPNowPlayingPlaybackState::Stopped);
    });
}

pub fn describe_sessions() -> Result<String, i32> {
    Err(NOT_SUPPORTED)
}
