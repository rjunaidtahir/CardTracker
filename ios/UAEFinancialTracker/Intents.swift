import AppIntents
import Foundation

/// The Shortcuts action behind the automatic import: a "Message" automation passes each bank SMS here, and it's added
/// in the background without opening the app.
struct AddBankMessageIntent: AppIntent {
    static var title: LocalizedStringResource = "Add Bank Message"
    static var description = IntentDescription("Adds a bank SMS to Fils. Other messages are ignored, and OTPs are never stored.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Message", description: "The text of the SMS (in an automation: Shortcut Input).")
    var message: String

    @Parameter(title: "Sender", description: "Who sent it (in an automation: Shortcut Input → Sender). Optional.")
    var sender: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Add \(\.$message) from \(\.$sender)")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> {
        let engine = AppData.engine
        let outcome = engine.ingest(
            body: message, sender: sender, receivedAt: Date(), timeKnown: true, source: "Shortcut", requireBankLike: true
        )
        Notifier.alerts(for: engine.takeFresh(), engine: engine)
        Notifier.refresh(engine: engine)
        let text: String
        switch outcome {
        case .transaction: text = "Added a transaction"
        case .statement: text = "Added a statement"
        case .failed: text = "Saved to Needs review"
        case .duplicate: text = "Already in the app"
        case .ignored: text = "Bank notice, nothing to add"
        case .otp: text = "OTP, not stored"
        case .notBank: text = "Not a bank message"
        }
        return .result(value: text)
    }
}

struct AppShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: AddBankMessageIntent(),
            phrases: ["Add a bank message to \(.applicationName)"],
            shortTitle: "Add Bank Message",
            systemImageName: "text.bubble"
        )
    }
}
