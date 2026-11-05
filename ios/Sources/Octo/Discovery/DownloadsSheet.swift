import OctoKit
import SwiftUI

private var chip: some ShapeStyle { OctoColor.textPrimary.opacity(0.08) }
private var thread: some ShapeStyle { OctoColor.textPrimary.opacity(0.10) }
private var track: some ShapeStyle { OctoColor.textPrimary.opacity(0.14) }
private var losslessChip: some ShapeStyle { OctoColor.textPrimary.opacity(0.16) }
private var card: some ShapeStyle { OctoColor.textPrimary.opacity(0.05) }
private var liveFill: some ShapeStyle { OctoColor.textPrimary.opacity(0.14) }

/// The server downloads sheet, over everything: every download the server is doing for this person
/// or did lately, each opening to its log, and Find songs.
struct ServerDownloadsSheet: View {
    @Environment(ServerDownloads.self) private var model
    /// The last question stays put while its alert goes away.
    @State private var asked: PickQuestion?
    /// The question answered with a button, which the alert going away then leaves alone.
    @State private var answered: PickQuestion?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            switch model.view {
            case .log(let key): DownloadLogPage(key: key)
            case .find(let id, let title, let from): FindSongsPage(id: id, title: title, from: from)
            default: DownloadListPage()
            }
        }
        .padding(.top, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .alert(replaceTitle, isPresented: replaceAnywayShown, presenting: asked) { question in
            Button(keepMineText, role: .cancel) {
                answered = question
                model.keepMine(question)
            }
            Button(replaceAnywayText, role: .destructive) {
                answered = question
                model.replaceAnyway()
            }
        } message: { question in
            Text(replaceAnywayWords(question))
        }
        .onChange(of: model.findWatch.question, initial: true) { _, question in
            if let question, question.kind == .confirm { asked = question }
        }
    }

    /// The question before a copy that needs the person's word replaces the library's. Keep
    /// mine, or the alert going away unanswered, keeps the library's; nothing is sent.
    private var replaceAnywayShown: Binding<Bool> {
        Binding(get: { model.findWatch.question.map { $0.kind == .confirm && $0 != answered } ?? false },
                set: { shown in
                    guard !shown else { return }
                    // A button's own answer comes with the alert going away; only an alert gone
                    // without one keeps mine.
                    DispatchQueue.main.async {
                        guard let asked = model.findWatch.question, asked.kind == .confirm, asked != answered else { return }
                        model.keepMine(asked)
                    }
                })
    }

    /// The song's title, the two copies side by side, then the server's sentence word for word.
    private func replaceAnywayWords(_ question: PickQuestion) -> String {
        let fallback: String = if case .find(_, let title, _) = model.view { title } else { "" }
        let title = question.title.isEmpty ? fallback : question.title
        let compared = compareLines(question.compare).joined(separator: "\n")
        return [title, compared, question.words].filter { !$0.isEmpty }.joined(separator: "\n\n")
    }
}

// MARK: The list

private struct DownloadListPage: View {
    @Environment(ServerDownloads.self) private var model

    var body: some View {
        let rows = model.watch.rows
        HStack(alignment: .center, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text(serverDownloadsTitle)
                    .font(.octoSection)
                    .foregroundStyle(OctoColor.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                let summary = drawerSummary(rows)
                if !summary.isEmpty {
                    Text(summary).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
                }
            }
            Spacer(minLength: 8)
            if rows.contains(where: \.finished) {
                QuietButton("Clear finished") { model.clear() }
            }
        }
        .padding(.leading, 20)
        .padding(.trailing, 10)
        if rows.isEmpty {
            Text(model.watch.loaded ? downloadsEmpty : "Asking the server")
                .font(.octoBodySmall)
                .foregroundStyle(OctoColor.textMuted)
                .padding(20)
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(rows) { row in
                        let keep = keepAnywayLabel(row)
                        VStack(alignment: .leading, spacing: 0) {
                            DownloadLine(row: row, keepBelow: keep != nil) {
                                // A higher quality copy still waiting its turn has no log yet; a
                                // finished one without its log can still be looked for again.
                                if let key = row.logKey {
                                    model.showLog(key)
                                } else if row.finished, let id = row.findId {
                                    model.find(id, title: row.title)
                                }
                            }
                            if let keep, let key = row.logKey {
                                KeepAnywayLine(label: keep, keeping: model.watch.keeping[key]) { model.keepAnyway(key) }
                            }
                        }
                    }
                }
                .padding(.vertical, 8)
            }
        }
    }
}

private struct DownloadLine: View {
    let row: DownloadRow
    /// A Keep anyway line follows, so the row's own bottom edge sits close above it.
    var keepBelow = false
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: lineGap) {
                Cover(id: row.coverArt, size: lineCover, radius: 8)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(row.title)
                            .font(.octoBody)
                            .foregroundStyle(OctoColor.textPrimary)
                            .lineLimit(1)
                        if let label = kindLabel(row.kind) { KindMark(text: label) }
                    }
                    if !row.artist.trimmingCharacters(in: .whitespaces).isEmpty {
                        Text(row.artist).font(.octoBodySmall).foregroundStyle(OctoColor.textSecondary).lineLimit(1)
                    }
                    Text(row.status)
                        .font(.octoCaption)
                        .foregroundStyle(row.phase == .failed ? AnyShapeStyle(DiscoveryStyle.orange) : AnyShapeStyle(OctoColor.textMuted))
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if waitsForYou(row) {
                    // Waiting for the person: the accent dot that says something needs you, centered
                    // in the ring's place.
                    Circle()
                        .fill(OctoColor.accent)
                        .frame(width: 8, height: 8)
                        .frame(width: lineMark, height: lineMark)
                } else if !row.finished {
                    DownloadProgressRing(fraction: row.fraction, size: lineMark)
                } else if row.phase == .done {
                    Image(systemName: "arrow.down.circle.fill").font(.system(size: 18)).foregroundStyle(OctoColor.textSecondary)
                } else {
                    Image(systemName: "info.circle").font(.system(size: 18)).foregroundStyle(DiscoveryStyle.orange)
                }
            }
            .padding(.horizontal, lineEdge)
            .padding(.top, 8)
            .padding(.bottom, keepBelow ? 2 : 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(SqueezeStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(row.title), \(row.artist)")
        .accessibilityValue(row.status)
        .accessibilityAddTraits(.isButton)
    }
}

/// A list row's edge, its cover, the gap before its words, and the ring or mark at its end.
private let lineEdge: CGFloat = 20
private let lineCover: CGFloat = 48
private let lineGap: CGFloat = 14
private let lineMark: CGFloat = 22

/// How far a QuietButton's words sit inside its edge.
private let quietInset: CGFloat = 10

/// A held download's Keep anyway, under its row's words and lined up with them, set like the
/// artist line above it: faint while it is sent, and the server's words under it when it said no,
/// wrapping where the row's words do, short of the ring. The row above never moves for it.
private struct KeepAnywayLine: View {
    let label: String
    let keeping: Keeping?
    let keep: () -> Void

    var body: some View {
        let sending = keeping?.sending == true
        VStack(alignment: .leading, spacing: 0) {
            QuietButton(label, font: .octoBodySmall, action: keep)
                .disabled(sending)
                .opacity(sending ? OctoInkColor.disabled : 1)
                // The button's own inset pulled back, so its words start where the row's do.
                .padding(.leading, -quietInset)
            if let error = keeping?.error {
                Text(error)
                    .font(.octoCaption)
                    .foregroundStyle(DiscoveryStyle.orange)
                    .lineLimit(2)
                    .padding(.bottom, 6)
            }
        }
        .padding(.leading, lineEdge + lineCover + lineGap)
        .padding(.trailing, lineGap + lineMark + lineEdge)
        .padding(.bottom, 2)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct KindMark: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.octoCaption)
            .foregroundStyle(OctoColor.textSecondary)
            .lineLimit(1)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(chip, in: RoundedRectangle(cornerRadius: 4))
    }
}

/// A ring filling as a download comes in; turning while nobody knows how far.
struct DownloadProgressRing: View {
    let fraction: Double?
    let size: CGFloat

    var body: some View {
        if let fraction {
            let stroke = size * 0.12
            ZStack {
                Circle().stroke(OctoColor.textPrimary.opacity(0.18), lineWidth: stroke)
                Circle()
                    .trim(from: 0, to: min(1, max(0, fraction)))
                    .stroke(OctoColor.textPrimary, style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            .padding(stroke / 2)
            .frame(width: size, height: size)
            .animation(.easeOut(duration: 0.3), value: fraction)
        } else {
            ProgressView()
                .controlSize(.small)
                .tint(OctoColor.textSecondary)
                .frame(width: size, height: size)
        }
    }
}

// MARK: One download's log

private struct DownloadLogPage: View {
    let key: String
    @Environment(ServerDownloads.self) private var model
    /// Whether the newest line is kept in view: nil until the row is known.
    @State private var follow: Bool?
    @State private var atEnd = true

    var body: some View {
        let log = model.logWatch.row
        let row = model.watch.rows.first { $0.logKey == key } ?? log.map { rowOf($0) }
        // Offered while the log's own reading of the download says so.
        let keep = log.flatMap { keepAnywayLabel(rowOf($0)) }
        let keeping = model.watch.keeping[key]
        let events = log?.event ?? []
        let running = row?.finished == false
        // Waiting for the person, its newest line is still.
        let busy = running && !(row.map(waitsForYou) ?? false)
        SheetBar(back: "Back to \(serverDownloadsTitle)", onBack: model.showList, title: "Download") {
            if let row, let id = logFindId(row) {
                QuietButton(findSongsTitle) { model.find(id, title: row.title, from: key) }
            }
            if let keep {
                let sending = keeping?.sending == true
                QuietButton(keep) { model.keepAnyway(key) }
                    .disabled(sending)
                    .opacity(sending ? OctoInkColor.disabled : 1)
            }
            if let row, row.finished {
                QuietButton("Clear") {
                    model.clear(row.key)
                    model.showList()
                }
            }
        }
        // The server's words right under the bar whose Keep anyway it said no to.
        if keep != nil, let refusal = keeping?.error {
            Text(refusal)
                .font(.octoCaption)
                .foregroundStyle(DiscoveryStyle.orange)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.bottom, 10)
        }
        // The song and where it is stay put; only the steps scroll.
        if let row { LogHead(row: row, events: events) }
        if let problem = model.logWatch.problem {
            Text(problem)
                .font(.octoCaption)
                .foregroundStyle(DiscoveryStyle.orange)
                .padding(.horizontal, 20)
                .padding(.vertical, 6)
        }
        Rectangle().fill(thread).frame(height: 1).padding(.top, 14)
        ZStack(alignment: .bottom) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if log == nil && model.logWatch.problem == nil {
                            DownloadProgressRing(fraction: nil, size: 22)
                        }
                        ForEach(Array(events.enumerated()), id: \.offset) { index, line in
                            LogLine(line: line, last: index == events.count - 1, live: busy && index == events.count - 1)
                                .id(index)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 14)
                    .padding(.bottom, 16)
                }
                .onScrollGeometryChange(for: Bool.self) { geometry in
                    geometry.contentOffset.y + geometry.containerSize.height >= geometry.contentSize.height - 4
                } action: { _, end in
                    atEnd = end
                }
                .onScrollPhaseChange { _, phase in
                    // Scrolling up lets go; back at the end follows again.
                    if phase == .idle, follow != nil { follow = atEnd }
                }
                .onChange(of: events.count, initial: true) { _, count in
                    if follow == true, count > 0 { withAnimation { proxy.scrollTo(count - 1, anchor: .bottom) } }
                }
                .onChange(of: follow) { _, now in
                    if now == true, !events.isEmpty { withAnimation { proxy.scrollTo(events.count - 1, anchor: .bottom) } }
                }
            }
            if follow == false && running && !atEnd {
                Button { follow = true } label: {
                    Label("Latest step", systemImage: "arrow.down.circle").font(DiscoveryStyle.label)
                }
                .buttonStyle(.glass)
                .controlSize(.small)
                .padding(.bottom, 12)
            }
        }
        .onChange(of: row != nil, initial: true) { _, known in
            // A running download opens on its newest step; a finished one opens at its start.
            if known, follow == nil, let row { follow = !row.finished }
        }
    }
}

/// The song, the steps it walks with the one it is on, and the copy being fetched.
private struct LogHead: View {
    let row: DownloadRow
    let events: [AcquisitionEvent]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                Cover(id: row.coverArt, size: 56, radius: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.title).font(.octoHeadline).foregroundStyle(OctoColor.textPrimary).lineLimit(2)
                    let by = [row.artist, row.album ?? ""].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                    if !by.isEmpty {
                        Text(by.joined(separator: " · ")).font(.octoBodySmall).foregroundStyle(OctoColor.textSecondary).lineLimit(2)
                    }
                }
            }
            StepsStrip(row: row, at: stepOf(row, events: events))
            VStack(alignment: .leading, spacing: 8) {
                Text(row.status)
                    .font(.octoBodySmall)
                    .foregroundStyle(row.phase == .failed ? AnyShapeStyle(DiscoveryStyle.orange) : AnyShapeStyle(OctoColor.textPrimary))
                let facts = [row.quality, row.source, kindLabel(row.kind)].compactMap { $0 }
                if !facts.isEmpty {
                    HStack(spacing: 6) {
                        ForEach(facts, id: \.self) { fact in
                            Text(fact)
                                .font(.octoCaption)
                                .foregroundStyle(OctoColor.textSecondary)
                                .lineLimit(1)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .background(chip, in: RoundedRectangle(cornerRadius: 6))
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 20)
    }
}

/// The four steps as a strip: done ones filled, the one it is on filling with the file while it
/// downloads (breathing while nobody can tell how far, still while it waits for the person), and
/// the ones still to come faint.
private struct StepsStrip: View {
    let row: DownloadRow
    let at: Int
    @Environment(\.octoReduceMotion) private var calm

    var body: some View {
        let failed = row.phase == .failed
        let breathes = !row.finished && row.fraction == nil && !waitsForYou(row) && !(calm)
        TimelineView(.animation(paused: !breathes)) { context in
            let t = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.8) / 0.9
            let pulse = breathes ? 0.45 + 0.55 * (t <= 1 ? t : 2 - t) : 1
            HStack(spacing: 4) {
                ForEach(DownloadStep.allCases, id: \.self) { step in
                    let index = step.rawValue
                    let target: Double = index < at ? 1 : index > at ? 0 : failed ? 1 : row.phase == .downloading ? (row.fraction ?? 0.5) : 0.5
                    let fill: AnyShapeStyle = failed && index == at ? AnyShapeStyle(DiscoveryStyle.orange)
                        : row.phase == .done ? AnyShapeStyle(DiscoveryStyle.green) : AnyShapeStyle(OctoColor.textPrimary)
                    let alpha = index == at && !row.finished ? pulse : 1
                    VStack(alignment: .leading, spacing: 5) {
                        GeometryReader { box in
                            ZStack(alignment: .leading) {
                                Capsule().fill(track)
                                Capsule().fill(fill.opacity(alpha)).frame(width: box.size.width * target)
                            }
                        }
                        .frame(height: 4)
                        .animation(.easeInOut(duration: 0.3), value: target)
                        Text(step.label)
                            .font(.octoCaption)
                            .lineLimit(1)
                            .foregroundStyle(failed && index == at ? AnyShapeStyle(DiscoveryStyle.orange)
                                : index == at && !row.finished ? AnyShapeStyle(OctoColor.textPrimary)
                                : index < at || row.phase == .done ? AnyShapeStyle(OctoColor.textSecondary) : AnyShapeStyle(OctoColor.textMuted))
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(at >= DownloadStep.allCases.count ? "Every step done"
            : "Step \(at + 1) of \(DownloadStep.allCases.count), \(DownloadStep(rawValue: at)?.label ?? "")")
    }
}

/// One line of the log, on the thread that joins them. The newest line of a running download turns
/// while it waits.
private struct LogLine: View {
    let line: AcquisitionEvent
    let last: Bool
    let live: Bool

    var body: some View {
        let mark = markOf(line.logKind)
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 0) {
                ZStack {
                    Circle().fill(live ? AnyShapeStyle(liveFill) : markFill(mark))
                    if live && mark != .done && mark != .failed {
                        ProgressView().controlSize(.mini).tint(OctoColor.textPrimary)
                    } else {
                        Image(systemName: markIcon(mark))
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(markTint(mark))
                    }
                }
                .frame(width: 24, height: 24)
                if !last { Rectangle().fill(thread).frame(width: 1).frame(maxHeight: .infinity) }
            }
            .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .top, spacing: 0) {
                    Text(line.text)
                        .font(.system(size: 14, weight: live ? .semibold : .regular))
                        .foregroundStyle(mark == .failed ? AnyShapeStyle(DiscoveryStyle.orange) : AnyShapeStyle(OctoColor.textPrimary))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 2)
                    Text(logTime(line.at))
                        .font(.octoCaption.monospacedDigit())
                        .foregroundStyle(OctoColor.textMuted)
                        .padding(.leading, 8)
                        .padding(.top, 3)
                }
                if let detail = line.detail {
                    Text(detail).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
                }
                if !line.candidate.isEmpty { LogCopies(copies: line.candidate) }
            }
            .padding(.bottom, 14)
        }
    }

    private func markIcon(_ mark: LogMark) -> String {
        switch mark {
        case .queued: "clock.arrow.circlepath"
        case .search: "magnifyingglass"
        case .found: "music.note.list"
        case .try: "arrow.down"
        case .transfer: "arrow.down.circle"
        case .check: "checkmark"
        case .tags: "pencil"
        case .cover: "square.stack"
        case .lyrics: "quote.bubble"
        case .library: "books.vertical"
        case .done: "arrow.down.circle.fill"
        case .failed: "xmark"
        case .note: "info"
        }
    }

    private func markTint(_ mark: LogMark) -> AnyShapeStyle {
        switch mark {
        case .done: AnyShapeStyle(DiscoveryStyle.green)
        case .failed: AnyShapeStyle(DiscoveryStyle.orange)
        default: AnyShapeStyle(OctoColor.textSecondary)
        }
    }

    private func markFill(_ mark: LogMark) -> AnyShapeStyle {
        switch mark {
        case .done: AnyShapeStyle(DiscoveryStyle.green.opacity(0.16))
        case .failed: AnyShapeStyle(DiscoveryStyle.orange.opacity(0.16))
        default: AnyShapeStyle(chip)
        }
    }
}

/// The copies a search offered, on one soft card: the first few, then the rest on asking.
private struct LogCopies: View {
    let copies: [FoundCandidate]
    @State private var all = false

    var body: some View {
        let shown = all ? copies : Array(copies.prefix(logCopiesShown))
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(shown.enumerated()), id: \.offset) { index, copy in
                if index > 0 { Rectangle().fill(thread).frame(height: 1).padding(.horizontal, 12) }
                CopyLine(copy: copy, compact: true)
            }
            if copies.count > logCopiesShown {
                QuietButton(all ? "Show fewer" : "Show all \(copies.count)") { all.toggle() }
            }
        }
        .padding(.vertical, 2)
        .background(card, in: RoundedRectangle(cornerRadius: 12))
        .padding(.top, 8)
    }
}

// MARK: Find songs

private struct FindSongsPage: View {
    let id: String
    let title: String
    let from: String?
    @Environment(ServerDownloads.self) private var model
    /// On an older server: a copy waiting for "Replace it", for a song already in the library, with
    /// the search it came from.
    @State private var asking: (search: String, copy: FoundCandidate)?

    var body: some View {
        let watch = model.findWatch
        let found = watch.found
        let searching = found?.searching != false && watch.problem == nil
        SheetBar(back: from != nil ? "Back to the log" : "Back to \(serverDownloadsTitle)", onBack: {
            if let from { model.showLog(from) } else { model.showList() }
        }, title: findSongsTitle) {
            if !searching {
                QuietButton("Search again") {
                    asking = nil
                    model.searchAgain()
                }
            }
        }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                FindHead(id: id, title: title, found: found, asksFirst: model.asksFirst)
                if let sources = found?.source, !sources.isEmpty { FindSources(sources: sources) }
                ForEach(Array([watch.problem, found?.error, watch.picked.flatMap { $0.queued ? nil : $0.detail }].compactMap { $0 }.enumerated()),
                        id: \.offset) { _, words in
                    Text(words).font(.octoCaption).foregroundStyle(DiscoveryStyle.orange).padding(.vertical, 6)
                }
                // The usual question for a better copy: asked here on an older server, or as the
                // server answered on one that asks first.
                let ready = watch.question.flatMap { $0.kind == .ready && $0.search == found?.id ? $0 : nil }
                let local = asking.flatMap { $0.search == found?.id ? $0 : nil }
                if let copy = ready?.copy ?? local?.copy {
                    ReplaceItCard(copy: copy, asksFirst: model.asksFirst, no: {
                        if let ready { model.keepMine(ready) } else { asking = nil }
                    }, yes: {
                        if ready != nil {
                            model.replaceAnyway()
                        } else if let local {
                            asking = nil
                            model.pick(local.search, local.copy)
                        }
                    })
                }
                Rectangle().fill(thread).frame(height: 1).padding(.vertical, 12)
                // The library's copy looks like another take: said over the copies.
                if let line = takeLine(found?.song.take) {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "info.circle").font(.system(size: 14)).foregroundStyle(OctoColor.textSecondary)
                        Text(line).font(.octoCaption).foregroundStyle(OctoColor.textPrimary)
                    }
                    .padding(.bottom, 8)
                }
                let copies = found?.candidate ?? []
                if copies.isEmpty && !searching {
                    Text("Nothing found on your sources.").font(.octoBodySmall).foregroundStyle(OctoColor.textMuted)
                }
                // The list still grows while sources answer, so a copy is picked only once it is done.
                if !copies.isEmpty && found?.searching == true {
                    Text(findPickWait).font(.octoCaption).foregroundStyle(OctoColor.textMuted).padding(.bottom, 6)
                }
                // While a copy replaces the library's, its row says how far it is, and no other copy
                // of the song can be picked.
                let busy = model.replacements.replacing(found?.song.libraryId)
                ForEach(copies, id: \.self) { copy in
                    let mine = model.replacements.of(search: found?.id, copyKey: copyKey(copy))
                    CopyLine(
                        copy: copy, compact: false,
                        pick: found.flatMap { shown in
                            guard !busy, canPick(shown, copy) else { return nil }
                            return {
                                // A server that asks first is asked at once; an older one is asked here.
                                if shown.song.libraryId != nil && !model.asksFirst {
                                    asking = (shown.id, copy)
                                } else {
                                    model.pick(shown.id, copy)
                                }
                            }
                        },
                        replacing: mine.map { replacingRow($0, model.watch.rows) } ?? nil,
                        replacingNow: mine != nil)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
        }
        .onChange(of: found?.id) { _, _ in asking = nil }
    }
}

private struct FindHead: View {
    let id: String
    let title: String
    let found: FoundSongs?
    let asksFirst: Bool

    var body: some View {
        let song = found?.song
        HStack(spacing: 14) {
            Cover(id: song?.coverArt ?? id, size: 64, radius: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.flatMap { $0.title.isEmpty ? nil : $0.title } ?? title).font(.octoHeadline).foregroundStyle(OctoColor.textPrimary)
                if let artist = song?.artist, !artist.isEmpty {
                    Text(artist).font(.octoBodySmall).foregroundStyle(OctoColor.textSecondary)
                }
            }
        }
        if let song {
            VStack(alignment: .leading, spacing: 2) {
                if let owned = ownedCopyText(quality: song.quality, size: song.size) {
                    Text(owned).font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary)
                }
                Text(song.libraryId != nil ? findReplaces(asksFirst: asksFirst) : findDownloadsText)
                    .font(.octoCaption)
                    .foregroundStyle(OctoColor.textMuted)
            }
            .padding(.top, 12)
        }
    }
}

private struct FindSources: View {
    let sources: [FindSourceState]

    /// Pills, a source each: its mark and its name, and why when it failed, wrapping onto a
    /// second line rather than scrolling sideways.
    var body: some View {
        Group {
            FlowRow(spacing: 6) {
                ForEach(sources, id: \.self) { source in
                    HStack(spacing: 6) {
                        switch source.state {
                        case findSearching:
                            ProgressView().controlSize(.mini).tint(OctoColor.textSecondary).frame(width: 14, height: 14)
                        case findOff:
                            Image(systemName: "xmark").font(.system(size: 11)).foregroundStyle(OctoColor.textMuted).frame(width: 14)
                        case findFailed:
                            Image(systemName: "info.circle").font(.system(size: 12)).foregroundStyle(DiscoveryStyle.orange).frame(width: 14)
                        default:
                            Image(systemName: "checkmark").font(.system(size: 11)).foregroundStyle(OctoColor.textSecondary).frame(width: 14)
                        }
                        Text(source.name)
                            .font(.octoCaption)
                            .foregroundStyle(source.state == findOff ? OctoColor.textMuted : OctoColor.textSecondary)
                            .lineLimit(1)
                        // Why it could not search, as a quiet word on its pill.
                        if source.state == findFailed {
                            Text(words(source))
                                .font(.octoCaption)
                                .foregroundStyle(OctoColor.textMuted)
                                .lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(chip, in: Capsule())
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(source.name): \(words(source))")
                }
            }
        }
        .padding(.top, 12)
    }

    private func words(_ source: FindSourceState) -> String {
        source.state == findSearching ? "Searching" : source.text ?? (source.state == findFailed ? "Could not search" : "Done")
    }
}

/// One copy: its kind on a badge, its title, where it sits, its facts, and whether Octo would take
/// it. On Find songs it can be got.
private struct CopyLine: View {
    let copy: FoundCandidate
    let compact: Bool
    var pick: (() -> Void)?
    var replacing: DownloadRow?
    var replacingNow = false

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    if let badge = copyBadge(copy) {
                        let lossless = isLosslessCopy(copy)
                        Text(badge)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(lossless ? AnyShapeStyle(OctoColor.textPrimary) : AnyShapeStyle(OctoColor.textSecondary))
                            .lineLimit(1)
                            .padding(.horizontal, 6)
                            .background(lossless ? AnyShapeStyle(losslessChip) : AnyShapeStyle(chip), in: RoundedRectangle(cornerRadius: 4))
                    }
                    if let tag = vsYoursTag(copy) {
                        Text(tag)
                            .font(.octoCaption)
                            .foregroundStyle(OctoColor.textSecondary)
                            .lineLimit(1)
                            .padding(.horizontal, 6)
                            .overlay(RoundedRectangle(cornerRadius: 4).stroke(thread, lineWidth: 1))
                            .accessibilityLabel(vsYoursSpoken(copy) ?? tag)
                    }
                    Text(candidateTitle(copy)).font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary).lineLimit(1)
                }
                if let place = copy.album.flatMap({ $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }) ?? copy.folder {
                    Text(place).font(.octoCaption).foregroundStyle(OctoColor.textMuted).lineLimit(1)
                }
                Text(badgeFacts(copy).joined(separator: " · ")).font(.octoCaption).foregroundStyle(OctoColor.textSecondary).lineLimit(2)
                // Octo's order for a copy it would try; why not, for one it would pass over.
                if let verdict = copy.rank.map(rankWords) ?? candidateVerdict(copy) {
                    Text(verdict)
                        .font(.octoCaption)
                        .foregroundStyle(copy.rank == 1 ? AnyShapeStyle(OctoColor.textPrimary) : AnyShapeStyle(OctoColor.textMuted))
                        .lineLimit(2)
                }
                if replacingNow {
                    HStack(spacing: 8) {
                        DownloadProgressRing(fraction: replacing?.fraction, size: 16)
                        Text(replacingWords(replacing)).font(.octoCaption).foregroundStyle(OctoColor.textPrimary)
                    }
                    .padding(.top, 4)
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.updatesFrequently)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let pick {
                Button(action: pick) {
                    Image(systemName: "arrow.down")
                        .font(.system(size: 19, weight: .medium))
                        .foregroundStyle(OctoColor.textSecondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Get this copy")
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, compact ? 12 : 0)
    }
}

/// The usual question before a better copy replaces the library's, as a card over the copies.
private struct ReplaceItCard: View {
    let copy: FoundCandidate
    let asksFirst: Bool
    let no: () -> Void
    let yes: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(replaceQuestion(copy)).font(.octoBodySmall).foregroundStyle(OctoColor.textPrimary)
            Text(findReplaces(asksFirst: asksFirst)).font(.octoCaption).foregroundStyle(OctoColor.textMuted)
            HStack(spacing: 10) {
                GlazeButton("Cancel", size: .small, action: no)
                GlazeButton("Replace it", icon: .lossless, size: .small, action: yes)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(chip, in: RoundedRectangle(cornerRadius: 14))
        .padding(.vertical, 8)
    }
}

/// The sheet's top line inside a log or Find songs: back, the view's title, then its actions.
/// The sheet's top line inside a log or Find songs: back, the page's name and its actions. The
/// name never gives way: when the actions do not fit beside it, they go on a line of their own
/// under it, their words lined up with the name's.
private struct SheetBar<Actions: View>: View {
    let back: String
    let onBack: () -> Void
    var title: String?
    @ViewBuilder let actions: Actions

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 0) {
                backButton
                name
                Spacer(minLength: 8)
                actions.fixedSize()
            }
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    backButton
                    name
                    Spacer(minLength: 0)
                }
                HStack(spacing: 0) { actions }
                    .fixedSize()
                    .padding(.leading, backSize - quietInset)
            }
        }
        .padding(.leading, 8)
        .padding(.trailing, 10)
    }

    private var backButton: some View {
        Button(action: onBack) {
            Image(systemName: "chevron.left")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(OctoColor.textSecondary)
                .frame(width: backSize, height: backSize)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(back)
    }

    private var name: some View {
        Text(title ?? "")
            .font(.octoSection)
            .foregroundStyle(OctoColor.textPrimary)
            .fixedSize()
            .accessibilityAddTraits(.isHeader)
    }
}

/// The sheet bar's back button.
private let backSize: CGFloat = 44
