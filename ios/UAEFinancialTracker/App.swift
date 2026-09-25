import SwiftUI
import SwiftData
import UserNotifications

/// The one store for the app and its Shortcuts action (which runs in the background, without opening the app).
@MainActor
enum AppData {
    static let container: ModelContainer = {
        // SwiftData keeps its store in Application Support, which doesn't exist on a fresh install.
        if let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        let schema = Schema(AppModels.all)
        // Financial data stays on this phone: no iCloud sync.
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: DemoData.isOn, cloudKitDatabase: .none)
        do {
            return try ModelContainer(for: schema, configurations: [config])
        } catch {
            fatalError("Couldn't open the app's data: \(error)")
        }
    }()

    static let engine: Engine = {
        let e = Engine(context: container.mainContext)
        if DemoData.isOn { DemoData.load(into: e) }
        e.onChange = { [weak e] in
            guard let e else { return }
            Refresher.schedule(e)
        }
        return e
    }()
}

/// Recalculates reminders and the widget shortly after changes (once for a burst of changes).
@MainActor
enum Refresher {
    private static var task: Task<Void, Never>?

    static func schedule(_ engine: Engine) {
        task?.cancel()
        task = Task {
            try? await Task.sleep(for: .milliseconds(400))
            if Task.isCancelled { return }
            Notifier.alerts(for: engine.takeFresh(), engine: engine)
            Notifier.refresh(engine: engine)
        }
    }
}

final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationDelegate()
    /// Show reminders and alerts while the app is open too.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }
}

@main
struct FilsApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("themeId") private var themeId = "system"

    init() {
        UNUserNotificationCenter.current().delegate = NotificationDelegate.shared
    }

    var body: some Scene {
        WindowGroup {
            let theme = AppTheme.byId(themeId)
            RootView()
                .environment(model)
                .tint(theme.accent)
                .preferredColorScheme(theme.scheme)
                .onOpenURL { url in model.open(url) }
                .onChange(of: scenePhase) { _, phase in model.scenePhaseChanged(phase) }
        }
        .modelContainer(AppData.container)
    }
}

/// App-wide state: the engine, the period shown on Home, Activity and Cards, filters, files opened from other apps,
/// and the app lock.
@MainActor
@Observable
final class AppModel {
    let engine = AppData.engine
    var incomingPdf: URL?
    var incomingBackup: URL?
    var toast: String?
    var tab: Tab = Tab(rawValue: UserDefaults.standard.string(forKey: "tab") ?? "") ?? .home
    var showOnboarding = DemoData.isOn ? UserDefaults.standard.bool(forKey: "onboarding") : !Settings.onboarded
    /// Screenshots only: a screen to open on launch ("automation", "review").
    var screen = UserDefaults.standard.string(forKey: "screen")

    /// The period chips' choice, shared by Home, Activity and Cards.
    var period = Period.thisMonth()
    /// Activity filters. [categoryFilter]: nil = all; .some(nil) = uncategorised.
    var cardFilter: String?
    var categoryFilter: Int64??

    var locked = Settings.lockEnabled && !DemoData.isOn
    private var backgroundedAt: Date?

    enum Tab: String, Hashable { case home, activity, cards, more }

    init() {
        if DemoData.isOn, UserDefaults.standard.bool(forKey: "locked") { locked = true }
        Notifier.refresh(engine: engine)
        processInbox()
    }

    func showActivity(card: String? = nil, category: Int64?? = nil) {
        cardFilter = card
        categoryFilter = category
        tab = .activity
    }

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .background:
            backgroundedAt = Date()
        case .active:
            if Settings.lockEnabled, let b = backgroundedAt, Date().timeIntervalSince(b) >= Double(Settings.lockTimeout) { locked = true }
            backgroundedAt = nil
            processInbox()
            Notifier.refresh(engine: engine)
        default:
            break
        }
    }

    /// Files shared to the app from other apps (Share → Fils) wait in the inbox until the app opens.
    func processInbox() {
        for url in Inbox.pending() {
            let copy = FileManager.default.temporaryDirectory.appendingPathComponent(url.lastPathComponent)
            try? FileManager.default.removeItem(at: copy)
            do {
                try FileManager.default.moveItem(at: url, to: copy)
            } catch {
                try? FileManager.default.removeItem(at: url)
                continue
            }
            handle(copy)
        }
    }

    /// A statement PDF, a backup, screenshots or a messages file opened with the app ("Open in…", Share, Files).
    func open(_ url: URL) {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        // Keep our own copy: the original may go away when the other app closes.
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
        do {
            try FileManager.default.copyItem(at: url, to: copy)
        } catch {
            toast = "Couldn't open \(url.lastPathComponent)"
            return
        }
        handle(copy)
    }

    private func handle(_ file: URL) {
        switch file.pathExtension.lowercased() {
        case "pdf":
            incomingPdf = file
        case "zip":
            incomingBackup = file
        case "png", "jpg", "jpeg", "heic", "heif":
            guard let data = try? Data(contentsOf: file), let image = UIImage(data: data) else { return }
            Task {
                let r = await MessageFiles.importScreenshots([image], engine: engine)
                toast = r.summary
                tab = .activity
            }
        default:
            let tally = MessageFiles.importFile(file, engine: engine)
            toast = tally.summary
            tab = .activity
        }
    }

    func restore(_ url: URL) {
        do {
            let data = try Data(contentsOf: url)
            toast = try Backup.restore(data, engine: engine).summary
        } catch {
            toast = error.localizedDescription
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Query(filter: #Predicate<SmsRecord> { $0.status == "failed" }) private var toReview: [SmsRecord]

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.tab) {
            HomeView()
                .tabItem { Label("Home", systemImage: "chart.pie.fill") }
                .tag(AppModel.Tab.home)
            ActivityView()
                .tabItem { Label("Activity", systemImage: "list.bullet.rectangle.fill") }
                .tag(AppModel.Tab.activity)
            CardsView()
                .tabItem { Label("Cards", systemImage: "creditcard.fill") }
                .tag(AppModel.Tab.cards)
            MoreView()
                .tabItem { Label("More", systemImage: "ellipsis.circle.fill") }
                .tag(AppModel.Tab.more)
                .badge(toReview.count)
        }
        .sheet(item: Binding(get: { model.incomingPdf.map(IdentifiedURL.init) }, set: { model.incomingPdf = $0?.url })) { item in
            NavigationStack { StatementView(url: item.url) }
        }
        .alert("Restore this backup?", isPresented: Binding(get: { model.incomingBackup != nil }, set: { if !$0 { model.incomingBackup = nil } })) {
            Button("Restore") {
                if let u = model.incomingBackup { model.restore(u) }
                model.incomingBackup = nil
            }
            Button("Cancel", role: .cancel) { model.incomingBackup = nil }
        } message: {
            Text("Messages, typed entries and settings from the backup are added. Nothing is deleted.")
        }
        .sheet(item: Binding(get: { model.screen.map { IdentifiedURL(url: URL(string: "screen:" + $0)!) } }, set: { model.screen = $0 == nil ? nil : model.screen })) { item in
            NavigationStack {
                switch item.url.absoluteString {
                case "screen:automation": AutomationGuideView()
                case "screen:review": ReviewView()
                case "screen:fixed": FixedPaymentsView()
                case "screen:statement": StatementView(url: nil)
                case "screen:lock": SecurityView()
                case "screen:budgets": BudgetsEditor(budgets: [])
                case "screen:notifications": NotificationsView()
                case "screen:card": FirstCardDetail()
                default: PasteView()
                }
            }
        }
        .fullScreenCover(isPresented: $model.showOnboarding) {
            OnboardingView { Settings.onboarded = true; model.showOnboarding = false }
        }
        .overlay {
            if model.locked { LockView { model.locked = false } }
        }
        .overlay(alignment: .bottom) {
            if let t = model.toast {
                Text(t)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.thickMaterial, in: RoundedRectangle(cornerRadius: 18))
                    .padding(.horizontal, 20)
                    .padding(.bottom, 70)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: t) {
                        try? await Task.sleep(for: .seconds(5))
                        withAnimation { model.toast = nil }
                    }
                    .onTapGesture { withAnimation { model.toast = nil } }
            }
        }
        .animation(.default, value: model.toast)
    }
}

/// Screenshots only: the first card's details.
struct FirstCardDetail: View {
    @Query(sort: \Card.order) private var cards: [Card]
    var body: some View {
        if let c = cards.first { CardDetailView(card: c) } else { Text("No cards") }
    }
}

struct IdentifiedURL: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}
