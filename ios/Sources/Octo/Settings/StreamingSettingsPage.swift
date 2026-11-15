import OctoKit
import SwiftUI

/// How music from a server plays, the cache of songs played lately, fetching the next songs
/// ahead, and downloads. Each choice pops its answers up beside its row.
struct StreamingSettingsPage: View {
    var highlight: String?
    @State private var settings = AppSettings.shared
    @State private var feeds = SettingsFeeds.shared

    /// What the fetch ahead rows can show on their right: the fixed counts, as the other apps list them.
    private var aheadValues: [String] { [0, 1, 2, 3, 5, 10].map(SettingsWords.ahead) }

    var body: some View {
        let stream = settings.stream
        let kept = settings.offline
        let used = feeds.cacheUsed
        SettingsPageFrame(title: SettingsPage.streaming.title, highlight: highlight) {
            SettingsGroup(title: "Streaming", icon: SettingsIcon.wifi) {
                ChoiceRow(SettingsIndex.copies, value: stream.copies.label, question: SettingsQuestion(
                    SettingsIndex.copies.title, CopyPreference.allCases, current: stream.copies,
                    answer: { SettingsAnswer($0.label, $0.detail) }, pick: settings.setCopies))
                // Quality on Wi-Fi and mobile data, and a family account's own choice, are on Audio quality.
                LinkRow(nil, title: SettingsPage.audioQuality.title,
                        helper: "On Wi-Fi \(stream.wifi.label), on mobile data \(stream.mobile.label)", to: SettingsRoute(.audioQuality))
                ChoiceRow(SettingsIndex.startAfter, value: stream.startAfter.label, helper: StartAfter.help, question: SettingsQuestion(
                    SettingsIndex.startAfter.title, StartAfter.allCases, current: stream.startAfter,
                    answer: { SettingsAnswer($0.label) }, pick: settings.setStartAfter))
            }
            SettingsGroup(title: "Cache", icon: SettingsIcon.storage) {
                ChoiceRow(SettingsIndex.cacheSize,
                          value: kept.cacheSize == .off ? "Off" : "\(kept.cacheSize.label), \(SettingsWords.sizeLabel(used)) used",
                          values: [],
                          question: SettingsQuestion(
                              SettingsIndex.cacheSize.title, CacheSize.allCases, current: kept.cacheSize,
                              answer: { SettingsAnswer($0.label, $0 == .off ? "Songs stream every time, and nothing is fetched ahead" : nil) },
                              // The cache takes the new size, then measures itself again.
                              pick: settings.setCacheSize))
                if kept.cacheSize != .off {
                    ChoiceRow(SettingsIndex.fetchWifi, value: SettingsWords.ahead(kept.prefetchWifi), values: aheadValues,
                              question: aheadQuestion(SettingsIndex.fetchWifi.title, kept.prefetchWifi, settings.setPrefetchWifi))
                    ChoiceRow(SettingsIndex.fetchMobile, value: SettingsWords.ahead(kept.prefetchMobile), values: aheadValues,
                              question: aheadQuestion(SettingsIndex.fetchMobile.title, kept.prefetchMobile, settings.setPrefetchMobile))
                    ActionRow(SettingsIndex.clearCache, chevron: false, enabled: used > 0) {
                        Task { await feeds.clearCache() }
                    }
                }
            }
            SettingsGroup(title: "Downloads", icon: SettingsIcon.download) {
                ChoiceRow(SettingsIndex.downloadQuality, value: kept.downloadQuality.downloadLabel(kept.downloadFormat),
                          values: StreamQuality.allCases.map { $0.downloadLabel(kept.downloadFormat) }, question: SettingsQuestion(
                    SettingsIndex.downloadQuality.title, StreamQuality.allCases, current: kept.downloadQuality,
                    answer: { quality in
                        quality == .original ? SettingsAnswer(StreamQuality.originalLabel)
                            : SettingsAnswer(quality.downloadLabel(kept.downloadFormat), SettingsWords.downloadDetail(quality))
                    }, pick: settings.setDownloadQuality))
                // Always shown, so the page never moves when the quality changes; it says when it applies.
                ChoiceRow(SettingsIndex.downloadFormat, value: kept.downloadFormat.label, helper: DownloadFormat.help,
                          question: SettingsQuestion(
                    SettingsIndex.downloadFormat.title, DownloadFormat.allCases, current: kept.downloadFormat,
                    answer: { SettingsAnswer($0.label, $0.detail) }, pick: settings.setDownloadFormat))
                ToggleRow(SettingsIndex.wifiOnly, isOn: kept.wifiOnly, onChange: settings.setWifiOnly)
                ToggleRow(SettingsIndex.keepLiked, isOn: kept.keepLiked, helper: "Liked songs from your server, kept in step with your likes.",
                          onChange: settings.setKeepLiked)
                ToggleRow(SettingsIndex.streamOnWifi, isOn: kept.streamOnWifi, helper: "Even songs that are downloaded.",
                          onChange: settings.setStreamOnWifi)
                LinkRow(SettingsIndex.downloadedMusic, to: SettingsSideRoute.downloads)
            }
        }
        // How much the cache holds, measured again as the page opens.
        .task { await feeds.measureCache() }
    }

    /// The counts on offer, with a stored count not among them put in its place so it shows chosen.
    private func aheadQuestion(_ title: String, _ current: Int, _ pick: @escaping (Int) -> Void) -> SettingsQuestion {
        SettingsQuestion(title, SettingsWords.aheadCounts(including: current), current: current,
                         answer: { SettingsAnswer(SettingsWords.ahead($0), $0 == 0 ? "Only the playing song is saved" : nil) }, pick: pick)
    }
}
