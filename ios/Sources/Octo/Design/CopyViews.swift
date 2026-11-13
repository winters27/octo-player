import OctoKit
import SwiftUI

/// A ring around the cover of a song that just came, filling as the copy goes; it turns while how
/// far cannot be told and holds still while the library is built. With no copy running (`run`
/// nil) it is drawn still, and faint once the copy `stopped`. Motion reduced, it never turns.
struct CopyRing: View {
    let run: CopyRunState?
    var size: CGFloat
    var stopped = false
    @Environment(\.octoReduceMotion) private var still

    var body: some View {
        let stroke: CGFloat = size >= 96 ? 3 : 2
        let gap: CGFloat = size >= 96 ? 6 : (size >= 48 ? 4 : 3)
        let inner = size - (stroke + gap) * 2
        ZStack {
            Circle().stroke((stopped ? OctoColor.textMuted : OctoColor.accent).opacity(0.18), lineWidth: stroke)
            if let run {
                if let fraction = run.fraction {
                    Circle()
                        .trim(from: 0, to: fraction)
                        .stroke(OctoColor.accent.opacity(run.step == .build ? 0.6 : 1), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .animation(still ? nil : .octoSpring(0.9, 500), value: fraction)
                } else {
                    TurningArc(stroke: stroke, still: still)
                }
            }
            Group {
                if let cover = run?.artwork {
                    Cover(id: cover, size: inner, radius: inner / 2)
                } else {
                    Circle().fill(OctoColor.backgroundTertiary)
                        .overlay { Image(systemName: "music.note").font(.system(size: inner * 0.34)).foregroundStyle(OctoColor.textMuted) }
                }
            }
            .frame(width: inner, height: inner)
            .animation(.easeInOut(duration: 0.3), value: run?.artwork)
        }
        .frame(width: size, height: size)
        .accessibilityElement()
        .accessibilityLabel(run.map(CopyWords.description) ?? "")
        .accessibilityValue(run?.fraction.map { "\(CopyWords.percent($0))%" } ?? "")
    }
}

/// A short arc going round, for a copy that cannot tell how far it is yet.
private struct TurningArc: View {
    let stroke: CGFloat
    let still: Bool
    @State private var turned = false

    var body: some View {
        Circle()
            .trim(from: 0, to: 0.22)
            .stroke(OctoColor.accent, style: StrokeStyle(lineWidth: stroke, lineCap: .round))
            .rotationEffect(.degrees(turned ? 270 : -90))
            .animation(still ? nil : .linear(duration: 1.4).repeatForever(autoreverses: false), value: turned)
            .onAppear { if !still { turned = true } }
    }
}

/// The copy's steps, each done, under way or still to come, with the count of the one under way
/// at its end. Read as one line. `stopped` marks where a failed copy stopped.
struct CopySteps: View {
    let run: CopyRunState?
    let server: String
    var stopped: CopyStep?

    var body: some View {
        let at = run?.step ?? stopped ?? .connect
        VStack(alignment: .leading, spacing: 10) {
            ForEach(copySteps, id: \.self) { step in
                let state = stepState(step, at: at)
                HStack(spacing: 12) {
                    mark(state)
                        .frame(width: 18, height: 18)
                    ViewThatFits(in: .horizontal) {
                        HStack {
                            label(step, state)
                            Spacer(minLength: 12)
                            count(state)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            label(step, state)
                            count(state)
                        }
                    }
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(run.map(CopyWords.description) ?? CopyWords.doing(at, server: server))
    }

    @ViewBuilder
    private func mark(_ state: StepState) -> some View {
        switch state {
        case .done: Image(systemName: "checkmark.circle.fill").font(.system(size: 16)).foregroundStyle(OctoColor.accent)
        case .now:
            if stopped != nil {
                Circle().fill(OctoColor.textMuted).frame(width: 7, height: 7)
            } else if run != nil {
                ProgressView().controlSize(.mini).tint(OctoColor.accent)
            } else {
                Circle().fill(OctoColor.accent).frame(width: 7, height: 7)
            }
        case .later: Image(systemName: "circle").font(.system(size: 10)).foregroundStyle(OctoColor.textMuted.opacity(0.5))
        }
    }

    private func label(_ step: CopyStep, _ state: StepState) -> some View {
        Text(CopyWords.stepLabel(step, server: server))
            .font(.octoBodySmall)
            .foregroundStyle(state == .now ? OctoColor.textPrimary : (state == .done ? OctoColor.textSecondary : OctoColor.textMuted))
    }

    @ViewBuilder
    private func count(_ state: StepState) -> some View {
        if state == .now, let run, let count = CopyWords.count(run) {
            Text(count).font(.octoCaption.monospacedDigit()).foregroundStyle(OctoColor.textMuted).lineLimit(1)
        }
    }
}

/// A first copy on its way, filling an empty Home: the ring, what it is doing, and its steps.
/// `onAsk` offers a notice for how it ends, when notices would not show yet.
struct CopyHero: View {
    let run: CopyRunState?
    let server: String
    var onAsk: (() -> Void)?

    var body: some View {
        VStack(spacing: 0) {
            CopyRing(run: run, size: 112)
            Text(CopyWords.title(arriving: true))
                .font(.octoHeadline).foregroundStyle(OctoColor.textPrimary).multilineTextAlignment(.center)
                .padding(.top, 16)
                .accessibilityAddTraits(.updatesFrequently)
            Text(CopyWords.heroText(server))
                .font(.octoBodySmall).foregroundStyle(OctoColor.textMuted).multilineTextAlignment(.center)
                .padding(.top, 6)
            CopySteps(run: run, server: server)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 20)
            if let onAsk { NoticeAskLine(onAllow: onAsk).padding(.top, 14) }
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .octoGlass(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
    }
}

/// The same above the shelves, while the phone's own music already shows.
struct CopyBanner: View {
    let run: CopyRunState?
    let server: String

    var body: some View {
        HStack(spacing: 14) {
            CopyRing(run: run, size: 56)
            VStack(alignment: .leading, spacing: 2) {
                Text(CopyWords.title(arriving: true)).font(.octoBody).foregroundStyle(OctoColor.textPrimary)
                Text(run.map(CopyWords.description) ?? CopyWords.heroText(server))
                    .font(.octoBodySmall.monospacedDigit()).foregroundStyle(OctoColor.textMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(20)
        .octoGlass(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
    }
}

/// The music arrived: how much and from where, while likes and playlists finish. Dismiss, on the
/// title's line, puts it away. Once the steps after the copy end, only the card's foot closes.
struct CopyDone: View {
    let songs: Int
    let albums: Int
    let server: String
    let settling: Bool
    let stepsProblem: String?
    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: "checkmark")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(OctoColor.accent)
                .frame(width: 56, height: 56)
                .background(Circle().fill(OctoColor.accentTonal))
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline) {
                    Text(CopyWords.doneTitle).font(.octoBody).foregroundStyle(OctoColor.textPrimary)
                    Spacer(minLength: 8)
                    QuietButton("Dismiss", action: onDismiss)
                }
                Text(CopyWords.doneText(songs: songs, albums: albums, server: server)).font(.octoBodySmall).foregroundStyle(OctoColor.textMuted)
                if settling {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        ProgressView().controlSize(.mini).tint(OctoColor.textMuted)
                        Text(CopyWords.doing(.settle, server: server)).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
                    }
                    .padding(.top, 4)
                    .transition(.opacity)
                } else if let stepsProblem {
                    Text(stepsProblem).font(.octoCaption).foregroundStyle(OctoColor.textMuted).padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .animation(.octoSpring(0.9, 400), value: settling)
        }
        .padding(20)
        .octoGlass(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
    }
}

/// The first copy stopped before its music arrived: why, where, and Try again.
struct CopyFailed: View {
    let reason: String
    let step: CopyStep
    let done: Int
    let server: String
    let big: Bool
    let onTryAgain: () -> Void

    var body: some View {
        Group {
            if big {
                VStack(spacing: 0) {
                    CopyRing(run: nil, size: 112, stopped: true)
                    Text(CopyWords.failedTitle).font(.octoHeadline).foregroundStyle(OctoColor.textPrimary).multilineTextAlignment(.center).padding(.top, 16)
                    Text([reason, CopyWords.stoppedAt(step, done: done)].compactMap { $0 }.joined(separator: " "))
                        .font(.octoBodySmall).foregroundStyle(OctoColor.textMuted).multilineTextAlignment(.center).padding(.top, 6)
                    CopySteps(run: nil, server: server, stopped: step).frame(maxWidth: .infinity, alignment: .leading).padding(.top, 20)
                    AccentButton("Try again", action: onTryAgain).padding(.top, 18)
                }
                .padding(20)
                .frame(maxWidth: .infinity)
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 14) {
                        CopyRing(run: nil, size: 56, stopped: true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(CopyWords.failedTitle).font(.octoBody).foregroundStyle(OctoColor.textPrimary)
                            Text([reason, CopyWords.stoppedAt(step, done: done)].compactMap { $0 }.joined(separator: " "))
                                .font(.octoBodySmall).foregroundStyle(OctoColor.textMuted)
                        }
                    }
                    AccentButton("Try again", size: .medium, action: onTryAgain).padding(.leading, 70)
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .octoGlass(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
    }
}

/// A copy under way, in Sync now's place: what it is doing, its count, and a bar of how far it
/// is, there from the first step to the last so the row never changes height or width; a small
/// spinner on the words' line while how far cannot be told, and while the steps after the copy run.
struct CopyProgressRow: View {
    let run: CopyRunState

    var body: some View {
        let settling = run.step == .settle
        RowFrame(entry: SettingsIndex.syncNow) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Text(CopyWords.doing(run.step, server: run.server)).font(.octoBody).foregroundStyle(OctoColor.textPrimary)
                    Spacer(minLength: 12)
                    if let count = CopyWords.count(run) {
                        Text(count).font(.octoBodySmall.monospacedDigit()).foregroundStyle(OctoColor.textSecondary).lineLimit(1)
                    }
                    if run.fraction == nil || settling { ProgressView().controlSize(.mini).tint(OctoColor.accent) }
                }
                ProgressView(value: settling ? 1 : run.fraction ?? 0)
                    .tint(OctoColor.accent)
                    .opacity(settling ? 0.45 : 1)
                    .animation(.octoSpring(0.9, 500), value: run.fraction)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(CopyWords.description(run))
        .accessibilityValue(run.fraction.map { "\(CopyWords.percent($0))%" } ?? "")
    }
}

/// One quiet line offering a notice for how the copy ends.
struct NoticeAskLine: View {
    let onAllow: () -> Void

    var body: some View {
        HStack {
            Text(CopyWords.noticeAsk).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
            Spacer(minLength: 8)
            QuietButton("Allow", action: onAllow)
        }
    }
}

/// An empty list's note: while the server's music is still to come, that it is on its way (or
/// that its first copy stopped); `otherwise` the list's own note.
struct CopyAwareNote<Otherwise: View>: View {
    var likes = false
    @ViewBuilder let otherwise: () -> Otherwise
    @Environment(ServerSync.self) private var sync

    var body: some View {
        if sync.awaiting == true, let account = sync.account?.id {
            let run = sync.copyRun.flatMap { $0.account == account ? $0 : nil }
            let failed: Bool = {
                if case .failed = sync.copyOutcome, sync.copyOutcome?.account == account { return true }
                return false
            }()
            CopyNote(run: run, stopped: run == nil && failed, likes: likes)
        } else {
            otherwise()
        }
    }
}

/// An empty list's note while the server's music is still to come: a small ring, that it is on
/// its way (`likes` for the lists of likes), and how far the copy is.
struct CopyNote: View {
    let run: CopyRunState?
    var stopped = false
    var likes = false

    var body: some View {
        HStack(spacing: 14) {
            CopyRing(run: run, size: 40, stopped: stopped)
            VStack(alignment: .leading, spacing: 2) {
                Text(stopped ? CopyWords.failedTitle : (likes ? CopyWords.likesOnItsWay : CopyWords.onItsWay))
                    .font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary)
                if let run { Text(CopyWords.description(run)).font(.octoCaption.monospacedDigit()).foregroundStyle(OctoColor.textMuted) }
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }
}
