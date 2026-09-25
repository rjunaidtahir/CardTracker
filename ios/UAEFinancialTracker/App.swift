import SwiftUI
import SwiftData

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
        return e
    }()
}

@main
struct UAEFinancialTrackerApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .onOpenURL { url in model.open(url) }
        }
        .modelContainer(AppData.container)
    }
}

/// App-wide state: the engine, and files opened from other apps.
@MainActor
@Observable
final class AppModel {
    let engine = AppData.engine
    var incomingPdf: URL?
    var toast: String?
    var tab: Tab = Tab(rawValue: UserDefaults.standard.string(forKey: "tab") ?? "") ?? .home
    var showOnboarding = DemoData.isOn ? UserDefaults.standard.bool(forKey: "onboarding") : !Settings.onboarded
    /// Screenshots only: a screen to open on launch ("automation", "review", "type").
    var screen = UserDefaults.standard.string(forKey: "screen")

    enum Tab: String, Hashable { case home, activity, cards, more }

    /// A statement PDF or a messages file opened with the app ("Open in…", Share, Files).
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
        if copy.pathExtension.lowercased() == "pdf" {
            incomingPdf = copy
        } else {
            let tally = MessageFiles.importFile(copy, engine: engine)
            toast = tally.summary
            tab = .activity
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.tab) {
            HomeView()
                .tabItem { Label("Home", systemImage: "chart.pie") }
                .tag(AppModel.Tab.home)
            ActivityView()
                .tabItem { Label("Activity", systemImage: "list.bullet.rectangle") }
                .tag(AppModel.Tab.activity)
            CardsView()
                .tabItem { Label("Cards", systemImage: "creditcard") }
                .tag(AppModel.Tab.cards)
            MoreView()
                .tabItem { Label("More", systemImage: "ellipsis.circle") }
                .tag(AppModel.Tab.more)
        }
        .sheet(item: Binding(get: { model.incomingPdf.map(IdentifiedURL.init) }, set: { model.incomingPdf = $0?.url })) { item in
            NavigationStack { StatementView(url: item.url) }
        }
        .sheet(item: Binding(get: { model.screen.map { IdentifiedURL(url: URL(string: "screen:" + $0)!) } }, set: { model.screen = $0 == nil ? nil : model.screen })) { item in
            NavigationStack {
                switch item.url.absoluteString {
                case "screen:automation": AutomationGuideView()
                case "screen:review": ReviewView()
                default: PasteView()
                }
            }
        }
        .fullScreenCover(isPresented: $model.showOnboarding) {
            OnboardingView { Settings.onboarded = true; model.showOnboarding = false }
        }
        .overlay(alignment: .bottom) {
            if let t = model.toast {
                Text(t)
                    .font(.callout)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.thickMaterial, in: Capsule())
                    .padding(.bottom, 70)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task {
                        try? await Task.sleep(for: .seconds(4))
                        withAnimation { model.toast = nil }
                    }
                    .onTapGesture { withAnimation { model.toast = nil } }
            }
        }
        .animation(.default, value: model.toast)
    }
}

struct IdentifiedURL: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}
