import SwiftUI

/// The launch moment: a brief, polished cover over the real content while the app comes up, showing the full name
/// once ("Fils" alone is what the app icon and the tab bar say every other time). Purely presentational — no
/// loading happens here, it's timed to feel deliberate rather than instant.
struct LaunchView: View {
    /// Called once the view should be dismissed (the caller fades it out).
    var onFinished: () -> Void = {}

    @State private var markVisible = false
    @State private var markGlow = false
    @State private var wordmarkVisible = false
    @State private var taglineVisible = false

    var body: some View {
        ZStack {
            LinearGradient(
                stops: [
                    .init(color: Color(hex: 0x0A0712), location: 0),
                    .init(color: Color(hex: 0x120A1A), location: 0.55),
                    .init(color: Color(hex: 0x1A0E1E), location: 1),
                ],
                startPoint: .top, endPoint: .bottom
            )
            .ignoresSafeArea()

            // A soft, slowly-breathing glow behind the mark — the only motion besides the entrance itself.
            Circle()
                .fill(Palette.brand)
                .frame(width: 260, height: 260)
                .blur(radius: 90)
                .opacity(markGlow ? 0.55 : 0.28)
                .scaleEffect(markGlow ? 1.08 : 0.92)

            VStack(spacing: 22) {
                mark
                VStack(spacing: 6) {
                    Text(AppInfo.name)
                        .font(.system(size: 46, weight: .bold, design: .rounded))
                        .foregroundStyle(Palette.brand)
                        .opacity(wordmarkVisible ? 1 : 0)
                        .offset(y: wordmarkVisible ? 0 : 8)
                    Text("Cards and Expense tracker")
                        .font(.system(size: 15, weight: .medium, design: .rounded))
                        .tracking(2.2)
                        .textCase(.uppercase)
                        .foregroundStyle(.white.opacity(0.55))
                        .opacity(taglineVisible ? 1 : 0)
                }
            }
        }
        .onAppear { animate() }
    }

    private var mark: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 26, style: .continuous)
                .fill(Palette.brand)
                .frame(width: 92, height: 92)
                .shadow(color: Palette.pink.opacity(0.35), radius: 22, y: 10)
            // A simple card-with-chip glyph, drawn rather than imported, so the mark scales crisply.
            VStack(alignment: .leading, spacing: 7) {
                RoundedRectangle(cornerRadius: 3).fill(.white.opacity(0.9)).frame(width: 16, height: 12)
                RoundedRectangle(cornerRadius: 2).fill(.white.opacity(0.75)).frame(width: 34, height: 5)
                RoundedRectangle(cornerRadius: 2).fill(.white.opacity(0.5)).frame(width: 24, height: 5)
            }
        }
        .scaleEffect(markVisible ? 1 : 0.7)
        .opacity(markVisible ? 1 : 0)
    }

    private func animate() {
        withAnimation(.spring(response: 0.55, dampingFraction: 0.7)) { markVisible = true }
        withAnimation(.easeOut(duration: 0.4).delay(0.18)) { wordmarkVisible = true }
        withAnimation(.easeOut(duration: 0.4).delay(0.32)) { taglineVisible = true }
        withAnimation(.easeInOut(duration: 1.6).repeatForever(autoreverses: true).delay(0.2)) { markGlow = true }
    }
}

#Preview {
    LaunchView()
}
