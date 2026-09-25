import SwiftUI

/// First run: what the app does, and how bank messages get in on an iPhone.
struct OnboardingView: View {
    let done: () -> Void
    @State private var page = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TabView(selection: $page) {
                    welcome.tag(0)
                    messages.tag(1)
                    statements.tag(2)
                }
                .tabViewStyle(.page(indexDisplayMode: .always))
                .indexViewStyle(.page(backgroundDisplayMode: .always))

                VStack(spacing: 10) {
                    if page < 2 {
                        Button { withAnimation { page += 1 } } label: {
                            Text("Next").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                    } else {
                        NavigationLink { AutomationGuideView().toolbar { ToolbarItem(placement: .confirmationAction) { Button("Finish", action: done) } } } label: {
                            Text("Set up automatic import").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                    }
                    Button(page < 2 ? "Skip" : "Later", action: done)
                        .controlSize(.large)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 20)
            }
        }
    }

    private var welcome: some View {
        OnboardingPage(
            symbol: "chart.pie.fill",
            title: "UAE Financial Tracker",
            text: "Turns the SMS your UAE banks send you into a clear picture of your spending: by category, by card and over time, with statements and due dates.",
            points: [
                ("building.columns", "Works with any UAE bank"),
                ("lock.shield", "Private: everything stays on this iPhone, no account, no ads"),
                ("key.slash", "One-time passwords are never stored"),
            ]
        )
    }

    private var messages: some View {
        OnboardingPage(
            symbol: "text.bubble.fill",
            title: "Your bank messages",
            text: "iPhone apps can't read SMS on their own, so there are a few easy ways in:",
            points: [
                ("wand.and.stars", "Automatic: a one-minute Shortcuts automation adds each bank SMS as it arrives"),
                ("doc.on.clipboard", "Paste messages you already have"),
                ("square.and.arrow.down", "Import a backup file from an Android phone"),
            ]
        )
    }

    private var statements: some View {
        OnboardingPage(
            symbol: "doc.text.magnifyingglass",
            title: "Statements",
            text: "Open a card statement PDF from Mail or Files with this app. It asks for the password, reads the amounts and every transaction, checks they add up, and adds what's missing.",
            points: [
                ("checkmark.seal", "Reads statements from any bank"),
                ("calendar.badge.clock", "Shows what's due and when"),
            ]
        )
    }
}

private struct OnboardingPage: View {
    let symbol: String
    let title: String
    let text: String
    let points: [(String, String)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Image(systemName: symbol)
                    .font(.system(size: 54))
                    .foregroundStyle(
                        LinearGradient(colors: [Color(red: 0.43, green: 0.16, blue: 0.85), Color(red: 0.86, green: 0.15, blue: 0.47)],
                                       startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    .padding(.top, 50)
                Text(title).font(.largeTitle.bold())
                Text(text).font(.body).foregroundStyle(.secondary)
                ForEach(Array(points.enumerated()), id: \.offset) { item in
                    Label {
                        Text(item.element.1)
                    } icon: {
                        Image(systemName: item.element.0).foregroundStyle(Color.accentColor)
                    }
                    .font(.callout)
                }
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 60)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
