import BackgroundTasks
import Foundation
import OctoKit
import UIKit
import UserNotifications

/// What the system shows of a copy while the app is away. A copy the listener asked for, begun
/// with the app in front, asks the system to keep it going (a continued processing task, which
/// shows its title, line and progress); a notice says how a first copy ended when the app is not
/// active. One copy at a time.
@MainActor
enum CopyNotices {
    /// The closing notice's id: a new one replaces the last.
    static let closingId = "library-copy"
    /// Where a copy's own task id starts; the rest is the copy's token.
    static let taskPrefix = "app.winters.octo.copy."

    /// The system's task keeping the copy `token` going, once the system started it, and how far
    /// it showed the copy last.
    private static var kept: (token: Int, task: BGContinuedProcessingTask)?
    private static var shown = 0.0
    /// The copy whose task was asked for and not started yet.
    private static var asked: Int?

    /// Asks the system to keep the copy `token` going. Its id is registered right before it is
    /// asked for, since a task id must be known before it can start.
    static func keepGoing(_ token: Int, sync: ServerSync) {
        let id = taskPrefix + String(token)
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: .main) { task in
            MainActor.assumeIsolated {
                guard let task = task as? BGContinuedProcessingTask else { return }
                guard sync.copyRun?.token == token else {
                    // The copy ended before the system started its task.
                    task.setTaskCompleted(success: true)
                    return
                }
                kept = (token, task)
                shown = 0
                // Fine enough that every page moves it.
                task.progress.totalUnitCount = 1_000_000
                // Time's up, or the listener stopped it: the task ends at once, as the system asks,
                // without waiting for the copy to wind down, and the copy stops after it.
                task.expirationHandler = {
                    Task { @MainActor in
                        if let (keptToken, keptTask) = kept, keptToken == token {
                            keptTask.setTaskCompleted(success: false)
                            kept = nil
                        }
                        sync.cutOff(token)
                    }
                }
                progressed(sync.copyRun)
            }
        }
        guard registered else { return }
        let run = sync.copyRun
        let request = BGContinuedProcessingTaskRequest(identifier: id, title: CopyWords.title(arriving: run?.arriving ?? true),
                                                      subtitle: run.map(CopyWords.description) ?? "")
        // Started now or not at all: a copy waits for nobody.
        request.strategy = .fail
        do {
            try BGTaskScheduler.shared.submit(request)
            asked = token
        } catch {
            // The copy goes on while the app is open.
        }
    }

    /// The copy's state changed: the system's task says the same, and always moves forward, below
    /// its end until the copy ends.
    static func progressed(_ run: CopyRunState?) {
        guard let (token, task) = kept, let run, run.token == token else { return }
        task.updateTitle(CopyWords.title(arriving: run.arriving), subtitle: CopyWords.description(run))
        shown = systemProgress(run, shown: shown)
        task.progress.completedUnitCount = Int64(shown * Double(task.progress.totalUnitCount))
    }

    /// The copy `token` ended: its task ends with it, and a first copy says how it ended when
    /// the app is not active.
    static func ended(_ token: Int, outcome: CopyOutcome?) {
        if let (kept, task) = kept, kept == token {
            task.progress.completedUnitCount = task.progress.totalUnitCount
            if case .failed = outcome { task.setTaskCompleted(success: false) } else { task.setTaskCompleted(success: true) }
            self.kept = nil
        }
        if asked == token { asked = nil }
        guard let outcome, outcome.token == token, UIApplication.shared.applicationState == .background else { return }
        let content = UNMutableNotificationContent()
        switch outcome {
        case let .arrived(_, _, server, songs, albums, _):
            content.title = CopyWords.doneTitle
            content.body = CopyWords.doneText(songs: songs, albums: albums, server: server)
        case let .failed(_, _, _, reason, _, _):
            content.title = CopyWords.failedTitle
            content.body = reason
        }
        Task {
            guard await allowed() else { return }
            try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: closingId, content: content, trigger: nil))
        }
    }

    /// How the last copy ended is old news once another starts, or the app is back.
    static func cancelClosing() {
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [closingId])
        center.removePendingNotificationRequests(withIdentifiers: [closingId])
    }

    /// Whether notices may be posted now.
    static func allowed() async -> Bool {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional || status == .ephemeral
    }
}

/// The one ask to allow a notice for how a copy ends: the system's ask while it was never
/// answered, the app's notification settings once it was refused. Asked once, in context.
@MainActor
enum CopyNoticeAsk {
    private static let prefs = Prefs("copy_notice")

    /// Whether to offer it: notices do not show yet and the listener was not asked.
    static func due() async -> Bool {
        guard prefs.bool("asked") != true else { return false }
        return await !CopyNotices.allowed()
    }

    static func ask() async {
        let center = UNUserNotificationCenter.current()
        switch await center.notificationSettings().authorizationStatus {
        case .notDetermined:
            _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
        default:
            if let url = URL(string: UIApplication.openNotificationSettingsURLString) { await UIApplication.shared.open(url) }
        }
        prefs.set(true, "asked")
    }
}
