import OctoKit
import SwiftUI

/// How songs play: what plays between them, speed and skipping silence; the queue, what a swipe
/// on a song adds to it and Autoplay when it ends; music coming back when headphones connect,
/// and what happens when casting ends. Radio has a page of its own.
struct PlaybackSettingsPage: View {
    var highlight: String?
    @State private var settings = AppSettings.shared
    @State private var navigator = SettingsNavigator.shared
    @State private var speedOpen = false
    @State private var curveOpen = false

    var body: some View {
        let prefs = settings.player
        // A search hit on a row the current choice hides lights Between songs instead.
        SettingsPageFrame(title: SettingsPage.playback.title, highlight: playbackHighlight(highlight, prefs.betweenSongs)) {
            SettingsGroup {
                betweenSongs(prefs.betweenSongs)
                ForEach(rowsUnder(prefs.betweenSongs), id: \.self) { row in
                    switch row {
                    case .length:
                        crossfadeLength(prefs.crossfadeSeconds)
                    case .curve:
                        PopoverRow(SettingsIndex.crossfadeCurve, value: prefs.crossfadeCurve.label, answers: FadeCurve.allCases.map(\.label),
                                   helpers: FadeCurve.allCases.map(\.detail), helper: prefs.crossfadeCurve.detail, isPresented: $curveOpen) {
                            FadeCurveCard { curveOpen = false }.frame(width: 340)
                        }
                    case .filterSweeps:
                        ToggleRow(SettingsIndex.filterSweeps, isOn: prefs.filterSweeps,
                                  helper: "The song ending loses its highs and bass as the next one comes in.") {
                            settings.editBetweenSongs(.filterSweeps($0))
                        }
                    case .matchTempo:
                        ToggleRow(SettingsIndex.matchTempo, isOn: prefs.matchTempo,
                                  helper: "Songs with a close tempo blend in step. The next song settles back to its own speed after.") {
                            settings.editBetweenSongs(.matchTempo($0))
                        }
                    }
                }
                PopoverRow(SettingsIndex.speed, value: SettingsWords.paceLabel(prefs), isPresented: $speedOpen) {
                    PaceCard().frame(width: 320)
                }
                ToggleRow(SettingsIndex.skipSilence, isOn: prefs.skipSilence, onChange: settings.setSkipSilence)
            }
            SettingsGroup(title: "Queue", icon: SettingsIcon.queue) {
                ChoiceRow(SettingsIndex.songSwipe, value: prefs.swipeAction.label, helper: prefs.swipeAction.detail,
                          question: SettingsQuestion(
                              SettingsIndex.songSwipe.title, SwipeAction.allCases, current: prefs.swipeAction,
                              answer: { SettingsAnswer($0.label, $0.detail) }, pick: settings.setSwipeAction))
                ToggleRow(SettingsIndex.autoplay, isOn: prefs.autoplay,
                          helper: "When the queue ends, similar songs keep playing, picked as Radio settings say.",
                          onChange: settings.setAutoplay)
            }
            SettingsGroup(title: "Headphones", icon: SettingsIcon.headphones,
                          footer: prefs.resumeWired || prefs.resumeBluetooth ? "Only while Octo is open or paused in the background." : nil) {
                ToggleRow(SettingsIndex.resumeWired, isOn: prefs.resumeWired, helper: "If unplugging paused it in the last 30 minutes.",
                          onChange: settings.setResumeWired)
                ToggleRow(SettingsIndex.resumeBluetooth, isOn: prefs.resumeBluetooth, helper: "If losing them paused it in the last 30 minutes.",
                          onChange: settings.setResumeBluetooth)
                if prefs.resumeWired || prefs.resumeBluetooth {
                    ToggleRow(SettingsIndex.resumeAlways, isOn: prefs.resumeAlways, helper: "However or whenever the music was paused.",
                              onChange: settings.setResumeAlways)
                }
            }
            SettingsGroup(title: "Casting", icon: SettingsIcon.cast) {
                ToggleRow(SettingsIndex.castKeepPlaying, isOn: prefs.castKeepPlaying,
                          helper: "When a TV or speaker drops out, the music carries on here.", onChange: settings.setCastKeepPlaying)
            }
        }
        .onChange(of: navigator.popup, initial: true) { _, popup in
            if popup == SettingsIndex.speed.id {
                navigator.popup = nil
                speedOpen = true
            } else if popup == SettingsIndex.crossfadeCurve.id, settings.player.betweenSongs == .crossfade {
                navigator.popup = nil
                curveOpen = true
            }
        }
    }

    /// What plays between two songs, picked from a menu that says what each does. Albums played
    /// in order stay gapless whatever is picked.
    private func betweenSongs(_ choice: BetweenSongs) -> some View {
        ChoiceRow(SettingsIndex.betweenSongs, value: choice.label, helper: "Albums played in order stay gapless.",
                  question: SettingsQuestion(
                      SettingsIndex.betweenSongs.title, BetweenSongs.allCases, current: choice,
                      answer: { SettingsAnswer($0.label, $0.detail) }, pick: { settings.editBetweenSongs(.choose($0)) }))
    }

    /// Crossfade's length, from 2 to 16 seconds, saved as it changes.
    private func crossfadeLength(_ seconds: Int) -> some View {
        let range = PlayerPrefs.crossfadeSecondsRange
        let span = Double(range.upperBound - range.lowerBound)
        return SliderRow(SettingsIndex.crossfadeLength, value: "\(seconds) s",
                         fraction: Double(seconds - range.lowerBound) / span) { fraction in
            let picked = range.lowerBound + Int((fraction * span + 0.5).rounded(.down))
            if picked != seconds { settings.editBetweenSongs(.length(picked)) }
        }
    }
}

/// A row like a choice row that opens its own card in a popover beside it instead of a list.
/// `answers` names the card's answers, so the value keeps the width of the widest, and `helpers` their lines.
struct PopoverRow<Card: View>: View {
    let entry: SettingEntry?
    let value: String
    var answers: [String] = []
    var helpers: [String] = []
    var helper: String?
    var enabled = true
    @Binding var isPresented: Bool
    let card: () -> Card

    init(_ entry: SettingEntry?, value: String, answers: [String] = [], helpers: [String] = [], helper: String? = nil, enabled: Bool = true, isPresented: Binding<Bool>,
         @ViewBuilder card: @escaping () -> Card) {
        self.entry = entry
        self.value = value
        self.answers = answers
        self.helpers = helpers
        self.helper = helper
        self.enabled = enabled
        _isPresented = isPresented
        self.card = card
    }

    var body: some View {
        Button { isPresented = true } label: {
            RowFrame(entry: entry) {
                TitleValueLayout {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry?.title ?? "")
                            .font(.octoBody)
                            .foregroundStyle(enabled ? OctoColor.textPrimary : OctoColor.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                        if let helper {
                            ZStack(alignment: .topLeading) {
                                ForEach(Array(helpers.enumerated()), id: \.offset) { _, line in
                                    Text(line).font(.octoCaption).fixedSize(horizontal: false, vertical: true).hidden().accessibilityHidden(true)
                                }
                                Text(helper).font(.octoCaption).foregroundStyle(OctoColor.textMuted).fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .layoutValue(key: RowShare.self, value: 1)
                    Reading(value: value, answers: answers, enabled: enabled)
                        .layoutValue(key: RowShare.self, value: 0.8)
                        .layoutValue(key: RowAnswers.self, value: answers.contains(value) ? answers : [])
                    Image(systemName: SettingsIcon.chevron)
                        .font(.system(size: 13, weight: .semibold))
                        .frame(width: 18, height: 18)
                        .foregroundStyle(OctoColor.textMuted)
                        .opacity(enabled ? 1 : 0.5)
                        .accessibilityHidden(true)
                }
            }
        }
        .buttonStyle(SettingsRowPress())
        .disabled(!enabled)
        .accessibilityLabel(entry?.title ?? "")
        .accessibilityValue(value)
        .accessibilityHint(helper ?? "")
        .glassPopover(isPresented: $isPresented, content: card)
    }
}

/// Speed and pitch, kept for every song: a slider from half to double speed that settles on the
/// common speeds, whether voices keep their pitch, and a pitch shift. Every change is heard at once.
struct PaceCard: View {
    @State private var settings = AppSettings.shared

    var body: some View {
        let prefs = settings.player
        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Speed").font(.octoBody).foregroundStyle(OctoColor.textPrimary).frame(maxWidth: .infinity, alignment: .leading)
                if !prefs.paceIsDefault {
                    Button("Reset") { settings.resetPace() }
                        .buttonStyle(.glass)
                        .font(.octoBodySmall)
                }
            }
            .frame(height: 48)
            Text(Pace.speedLabel(prefs.speed))
                .font(.octoDisplay.monospacedDigit())
                .tracking(-0.5)
                .foregroundStyle(OctoColor.textPrimary)
                .padding(.top, 8)
            GlowSlider(fraction: Double(Pace.speedFraction(prefs.speed)), label: "Playback speed",
                       valueText: Pace.speedLabel(prefs.speed), step: 0.05 / 1.5) { fraction in
                let picked = Pace.speedAt(Float(fraction))
                if picked != prefs.speed { settings.setSpeed(picked) }
            }
            DetentLabels(speed: prefs.speed) { settings.setSpeed($0) }

            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Keep pitch").font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary)
                    Text(prefs.keepPitch ? "Voices sound as usual at any speed." : "Pitch rises and falls with the speed, like a record.")
                        .font(.octoCaption)
                        .foregroundStyle(OctoColor.textMuted)
                }
                .padding(.trailing, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                Toggle("Keep pitch", isOn: Binding(get: { prefs.keepPitch }, set: { settings.setKeepPitch($0) }))
                    .labelsHidden()
                    .tint(OctoColor.accent)
            }
            .padding(.vertical, 4)
            .padding(.top, 16)

            HStack {
                Text("Pitch").font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary).frame(maxWidth: .infinity, alignment: .leading)
                Text(Pace.semitonesLabel(prefs.pitchSemitones))
                    .font(.octoBodySmall.monospacedDigit())
                    .foregroundStyle(prefs.pitchSemitones == 0 ? OctoColor.textMuted : OctoColor.textPrimary)
            }
            .padding(.top, 12)
            GlowSlider(fraction: Double(Pace.semitonesFraction(prefs.pitchSemitones)), label: "Pitch",
                       valueText: Pace.semitonesLabel(prefs.pitchSemitones), step: 1.0 / 12) { fraction in
                let picked = Pace.semitonesAt(Float(fraction))
                if picked != prefs.pitchSemitones { settings.setPitchSemitones(picked) }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 18)
    }
}

/// The crossfade curves to pick from, each with a small drawing of the two volumes: the song
/// ending in a quiet line, the one coming in in the accent. The chosen one sits on an accent
/// fill with a check.
struct FadeCurveCard: View {
    let picked: () -> Void
    @State private var settings = AppSettings.shared

    init(picked: @escaping () -> Void = {}) {
        self.picked = picked
    }

    var body: some View {
        let current = settings.player.crossfadeCurve
        VStack(alignment: .leading, spacing: 4) {
            Text("Crossfade curve")
                .font(.octoBody)
                .foregroundStyle(OctoColor.textPrimary)
                .frame(height: 40)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            ForEach(FadeCurve.allCases, id: \.self) { curve in
                let chosen = curve == current
                Button {
                    settings.editBetweenSongs(.curve(curve))
                    picked()
                } label: {
                    HStack(spacing: 12) {
                        FadeCurveDrawing(curve: curve, chosen: chosen)
                            .frame(width: 44, height: 30)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(curve.label).font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary)
                            Text(curve.detail)
                                .font(.octoCaption)
                                .foregroundStyle(OctoColor.textMuted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "checkmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(OctoColor.accent)
                            .opacity(chosen ? 1 : 0)
                            .frame(width: 18)
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background {
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(chosen ? AnyShapeStyle(OctoColor.accent.opacity(0.18)) : AnyShapeStyle(Color.clear))
                            .overlay {
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .strokeBorder(chosen ? AnyShapeStyle(OctoColor.accent.opacity(0.55)) : AnyShapeStyle(Color.clear), lineWidth: 1)
                            }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(SettingsRowPress())
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(chosen ? [.isSelected] : [])
            }
        }
        .padding(.horizontal, 10)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .sensoryFeedback(.selection, trigger: current)
    }
}

/// The two volumes of one crossfade curve across a blend, drawn small.
private struct FadeCurveDrawing: View {
    let curve: FadeCurve
    let chosen: Bool
    @Environment(\.octoPalette) private var palette

    var body: some View {
        let outColor = palette.color(.textSecondary).opacity(chosen ? 0.9 : 0.6)
        let intoColor = palette.color(.accent)
        Canvas { context, size in
            let points = 48
            var out = Path()
            var into = Path()
            for step in 0...points {
                let t = Double(step) / Double(points)
                let gains = fadeGains(curve, t, overlapMs: 8_000)
                let x = CGFloat(t) * size.width
                let outPoint = CGPoint(x: x, y: (1 - CGFloat(gains.out)) * (size.height - 2) + 1)
                let inPoint = CGPoint(x: x, y: (1 - CGFloat(gains.incoming)) * (size.height - 2) + 1)
                if step == 0 {
                    out.move(to: outPoint)
                    into.move(to: inPoint)
                } else {
                    out.addLine(to: outPoint)
                    into.addLine(to: inPoint)
                }
            }
            let line = StrokeStyle(lineWidth: 1.75, lineCap: .round, lineJoin: .round)
            context.stroke(out, with: .color(outColor), style: line)
            context.stroke(into, with: .color(intoColor), style: line)
        }
    }
}

/// The common speeds under the slider, each where it falls on the line. A tap goes straight to one.
private struct DetentLabels: View {
    let speed: Float
    let pick: (Float) -> Void

    var body: some View {
        GeometryReader { box in
            ForEach(Pace.speedDetents, id: \.self) { detent in
                let label = Pace.speedLabel(detent)
                Button { pick(detent) } label: {
                    Text(label)
                        .font(.octoCaption.monospacedDigit())
                        .foregroundStyle(detent == speed ? OctoColor.textPrimary : OctoColor.textMuted)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 4)
                        .fixedSize()
                }
                .buttonStyle(.plain)
                .position(x: center(detent, width: box.size.width, label: label), y: box.size.height / 2)
            }
        }
        .frame(height: 24)
    }

    /// Centered where the speed falls on the line, kept inside the width.
    private func center(_ detent: Float, width: CGFloat, label: String) -> CGFloat {
        let half = CGFloat(label.count) * 3.6 + 6
        let x = CGFloat(Pace.speedFraction(detent)) * width
        return min(max(x, half), width - half)
    }
}
