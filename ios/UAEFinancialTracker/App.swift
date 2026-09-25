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
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false, cloudKitDatabase: .none)
        do {
            return try ModelContainer(for: schema, configurations: [config])
        } catch {
            fatalError("Couldn't open the app's data: \(error)")
        }
    }()

    static let engine = Engine(context: container.mainContext)
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
    var tab: Tab = .home
    var showOnboarding = !Settings.onboarded

    enum Tab: Hashable { case home, activity, cards, more }

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
