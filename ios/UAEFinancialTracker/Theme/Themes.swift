import SwiftUI

/// Colour themes: the iPhone's own look (follows light / dark mode), plus the six themes of the Android app.
struct AppTheme: Identifiable, Equatable {
    let id: String
    let name: String
    /// nil = follow the iPhone's light / dark setting.
    let scheme: ColorScheme?
    let accent: Color?
    let bgTop: Color?
    let bg: Color?
    let bgBottom: Color?

    static let all: [AppTheme] = [
        AppTheme(id: "system", name: "iPhone default", scheme: nil, accent: nil, bgTop: nil, bg: nil, bgBottom: nil),
        AppTheme(id: "neon", name: "Midnight Neon", scheme: .dark, accent: Color(hex: 0x3BE37F), bgTop: Color(hex: 0x0E1A14), bg: Color(hex: 0x07090C), bgBottom: Color(hex: 0x120D1F)),
        AppTheme(id: "ocean", name: "Deep Ocean", scheme: .dark, accent: Color(hex: 0x38D6F5), bgTop: Color(hex: 0x0A1B2E), bg: Color(hex: 0x050B14), bgBottom: Color(hex: 0x071523)),
        AppTheme(id: "royal", name: "Royal Violet", scheme: .dark, accent: Color(hex: 0xC6A6FF), bgTop: Color(hex: 0x1A1030), bg: Color(hex: 0x0A0712), bgBottom: Color(hex: 0x1C0C1B)),
        AppTheme(id: "amoled", name: "Pure Black", scheme: .dark, accent: Color(hex: 0x3BE37F), bgTop: .black, bg: .black, bgBottom: .black),
        AppTheme(id: "light", name: "Daylight", scheme: .light, accent: Color(hex: 0x0E8A4A), bgTop: Color(hex: 0xE6F4EC), bg: Color(hex: 0xF3F5F8), bgBottom: Color(hex: 0xEEEAF8)),
        AppTheme(id: "sand", name: "Warm Paper", scheme: .light, accent: Color(hex: 0xB4532A), bgTop: Color(hex: 0xF4E9D8), bg: Color(hex: 0xF7F3EC), bgBottom: Color(hex: 0xEFE7DA)),
    ]

    static func byId(_ id: String) -> AppTheme { all.first { $0.id == id } ?? all[0] }

    var background: LinearGradient? {
        guard let bgTop, let bg, let bgBottom else { return nil }
        return LinearGradient(stops: [.init(color: bgTop, location: 0), .init(color: bg, location: 0.35), .init(color: bg, location: 0.8), .init(color: bgBottom, location: 1)],
                              startPoint: .top, endPoint: .bottom)
    }
}

/// Gives a list or form screen the theme's background.
struct ThemedScreen: ViewModifier {
    @AppStorage("themeId") private var themeId = "system"

    @ViewBuilder
    func body(content: Content) -> some View {
        let theme = AppTheme.byId(themeId)
        if let bg = theme.background {
            content
                .scrollContentBackground(.hidden)
                .background(bg.ignoresSafeArea())
        } else {
            content
        }
    }
}

extension View {
    func themedScreen() -> some View { modifier(ThemedScreen()) }
}

/// Brand colours used across the app.
enum Palette {
    static let violet = Color(hex: 0x6D28D9)
    static let pink = Color(hex: 0xDB2777)
    static let green = Color(hex: 0x16A34A)
    static let red = Color(hex: 0xDC2626)
    static let amber = Color(hex: 0xD97706)
    static let brand = LinearGradient(colors: [Color(hex: 0x6D28D9), Color(hex: 0xDB2777), Color(hex: 0xF59E0B)], startPoint: .topLeading, endPoint: .bottomTrailing)
}
