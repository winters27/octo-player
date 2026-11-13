#if DEBUG
import Foundation
import OctoKit
import SwiftUI

/// Drive steps for the player's states (see `Drive`):
///
/// - `crossfade:on` picks Crossfade, `crossfade:SECONDS` Crossfade at that length,
///   `crossfade:off` Gapless
/// - `automix:on` picks Automix; `automix:off` picks Crossfade, or keeps Gapless;
///   `filters:on|off`, `matchtempo:on|off`
/// - `blend` jumps to 30 s before the end of the song, to hear the next one come in
/// - `radio` starts radio from the song on now; `radio:TEXT` from the first song a search finds
/// - `sleep:MINUTES`, `sleep:end`, `sleep:songs:N`, `sleep:extend:MINUTES`, `sleep:off`
/// - `resume-offer` shows the Resume card with a sample offer; `resume-check` asks the server
/// - `autoplay:end` leaves only the song on now and jumps near its end, so Autoplay adds songs
/// - `seek:SECONDS`, `next`, `previous`, `play`, `shuffle:on|off`, `repeat:off|all|one`
/// - `speed:VALUE`, `pitch:SEMITONES`, `keeppitch:on|off`, `skipsilence:on|off`
/// - `startafter:NAME` (Instant, Short, Steady, Safe, Long as kept)
/// - `shuffle-library` shuffles the whole library
/// - `station:URL` or `station:URL|NAME` plays a live station
/// - `castkeep:on|off` keeps playing on the phone, or pauses, when an AirPlay device goes away
/// - `queuesync:on|off` switches syncing the play queue with the server
/// - `prefetch:WIFI` or `prefetch:WIFI,MOBILE` sets how many songs are fetched ahead
/// - `savedstate` shows how many of the next songs are saved whole on the phone
/// - `playstate` shows a line with what the player is doing
/// - `mixtap:on`, `mixtap:on:PERIOD_MS` turns on the mix tap (click tracks with a click every
///   PERIOD_MS, 500 when left out), `mixtap:off`, `mixtap:dump` writes what it holds now
/// - `mixtap:holdmain:MS` holds the main thread that long (a late acknowledgement)
/// - `mixtap:stall:A:MS`, `mixtap:stall:B:MS` stops the song on now (A) or the spare (B) getting
///   sound for that long
@MainActor
enum PlaybackDrive {
    static func install(player: Player, router: Router, overlays: Overlays) {
        Drive.steps.append { name, value in
            await step(name, value, player: player, router: router, overlays: overlays)
        }
    }

    /// Blocks the main thread for `ms`, as a busy main thread would: an acknowledgement comes late.
    private static func holdMain(ms: Double) {
        Thread.sleep(forTimeInterval: ms / 1_000)
    }

    private static func step(_ name: String, _ value: String, player: Player, router: Router, overlays: Overlays) async -> Bool {
        let settings = AppSettings.shared
        switch name {
        case "crossfade":
            if value == "off" {
                settings.editBetweenSongs(.choose(.gapless))
            } else {
                settings.editBetweenSongs(.choose(.crossfade))
                if let seconds = Int(value) { settings.editBetweenSongs(.length(seconds)) }
            }
        case "automix":
            if value != "off" {
                settings.editBetweenSongs(.choose(.automix))
            } else if settings.player.betweenSongs != .gapless {
                settings.editBetweenSongs(.choose(.crossfade))
            }
        case "filters": settings.editBetweenSongs(.filterSweeps(value != "off"))
        case "matchtempo": settings.editBetweenSongs(.matchTempo(value != "off"))
        case "blend":
            if player.duration > 40 { player.seek(to: player.duration - 30) }
        case "radio":
            if value.isEmpty {
                if let song = player.current { await player.startRadio(from: song) }
            } else if let client = player.session.client, let found = try? await client.search(value), let first = found.song.first {
                await player.startRadio(from: first)
            }
        case "sleep":
            let parts = value.split(separator: ":").map(String.init)
            switch parts.first {
            case "off": player.cancelSleep()
            case "end": player.sleepAtEndOfSong()
            case "songs": player.sleepAfterSongs(Int(parts.last ?? "") ?? 2)
            case "extend": player.extendSleep(minutes: Int(parts.last ?? "") ?? 5)
            default: player.startSleep(minutes: Int(value) ?? 15)
            }
            router.show(sleepSummary(player.sleepState))
        case "resume-offer":
            player.serverQueue.showSample(ResumeOffer(
                device: "Feishin", title: player.current?.title ?? "Paranoid Android",
                artist: player.current?.byline ?? "Radiohead",
                remote: RemoteQueue(songs: player.queue, index: 0, positionMs: 30_000,
                                    changedAt: Int(Date().timeIntervalSince1970 * 1000), changedBy: "Feishin")))
            let id = UUID()
            overlays.showSheet(id, tall: false, scrolls: false, content: AnyView(
                VStack { ResumeCard().environment(player) }.padding(.vertical, 20)
            )) { overlays.hideSheet(id) }
        case "resume-check": player.serverQueue.arrived()
        case "autoplay":
            settings.setAutoplay(value != "off")
            if value == "end" {
                player.clearUpcoming()
                player.setRepeat(.off)
                if player.duration > 40 { player.seek(to: player.duration - 25) }
            }
        case "seek": player.seek(to: Double(value) ?? 0)
        case "next": player.next()
        case "previous": player.previous()
        case "play": player.resume()
        case "shuffle":
            if player.shuffled != (value != "off") { player.toggleShuffle() }
        case "repeat":
            player.setRepeat(value == "one" ? .one : value == "all" ? .all : .off)
        case "speed": settings.setSpeed(Float(value) ?? 1)
        case "pitch": settings.setPitchSemitones(Int(value.replacingOccurrences(of: "+", with: "")) ?? 0)
        case "keeppitch": settings.setKeepPitch(value != "off")
        case "skipsilence": settings.setSkipSilence(value != "off")
        case "startafter": settings.setStartAfter(StartAfter(rawValue: value) ?? .short)
        case "shuffle-library": await player.shuffleLibrary()
        case "station":
            // A live station by its address, with an optional name after a "|".
            let parts = value.split(separator: "|", maxSplits: 1).map(String.init)
            if let url = parts.first { player.playStations([radioId(streamUrl: url, name: parts.count > 1 ? parts[1] : "")], from: 0) }
        case "castkeep": settings.setCastKeepPlaying(value != "off")
        case "queuesync": player.setQueueSync(value != "off")
        case "prefetch":
            let counts = value.split(separator: ",").compactMap { Int($0) }
            if let wifi = counts.first { settings.setPrefetchWifi(wifi) }
            if counts.count > 1 { settings.setPrefetchMobile(counts[1]) }
        case "savedstate":
            // How many of the next songs are saved whole on the phone (fetched ahead or played).
            let context = StreamContext.now(player.session)
            var saved = 0, servers = 0
            for slot in player.upNextSlots.dropFirst().prefix(5) where player.items.indices.contains(slot.index) {
                guard let ref = parseStreamUri(player.items[slot.index].uri), let source = ref.sourceId ?? context.sourceId else { continue }
                servers += 1
                let key = await StreamCache.shared.pick(source, ref, context.quality).key
                if await StreamCache.shared.isFullySaved(key) { saved += 1 }
            }
            router.show("\(saved) of the next \(servers) server songs saved whole")
        case "mixtap":
            let parts = value.split(separator: ":").map(String.init)
            let output = player.engine.output
            switch parts.first {
            case "on":
                if parts.count > 1, let period = Double(parts[1]) { MixTapReport.shared.periodMs = period }
                output.tap.start()
            case "off": output.tap.stop()
            case "dump": MixTapReport.shared.dump(output)
            case "holdmain": holdMain(ms: Double(parts.last ?? "") ?? 300)
            case "stall":
                let ms = parts.count > 2 ? Int(parts[2]) ?? 4_000 : 4_000
                let deck = parts.count > 1 && parts[1] == "B" ? player.engine.crossfader.spare : player.engine.deck
                deck.current?.stallUntilMs.store(Int(ProcessInfo.processInfo.systemUptime * 1_000) + ms, ordering: .relaxed)
            default: break
            }
        case "playstate":
            let quality = player.quality.map { "\($0.label) \($0.full)" } ?? "no badge"
            // "automix", "crossfade 8 s" or "gapless".
            let between = SettingsSummaries.betweenSongs(settings.player).lowercased()
            router.show("\(player.isPlaying ? "Playing" : "Paused") \(player.index + 1) of \(player.queue.count), \(between), \(sleepSummary(player.sleepState)), \(quality)")
        default:
            return false
        }
        return true
    }
}
#endif
