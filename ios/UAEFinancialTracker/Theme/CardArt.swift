import SwiftUI
import UIKit

/// Card looks: the same presets as the Android app, drawn as a gradient and a pattern.
struct CardArt: Identifiable, Hashable {
    enum Pattern { case none, curves, stripe, shards, blob, ring, waves, triangles }

    let key: String
    let name: String
    let colors: [UInt32]
    let lightText: Bool
    let pattern: Pattern
    let patternColor: UInt32
    var id: String { key }

    init(_ key: String, _ name: String, _ colors: [UInt32], _ lightText: Bool, _ pattern: Pattern, _ patternColor: UInt32 = 0xFFFFFF) {
        self.key = key
        self.name = name
        self.colors = colors
        self.lightText = lightText
        self.pattern = pattern
        self.patternColor = patternColor
    }

    var textColor: Color { lightText ? .white : Color(hex: 0x161616) }
    var mutedText: Color { lightText ? .white.opacity(0.78) : Color(hex: 0x161616).opacity(0.72) }

    static let presets: [CardArt] = [
        CardArt("graphite", "Graphite", [0x5E6166, 0x9EA1A6, 0x6A6D72], true, .curves),
        CardArt("onyx-red", "Onyx red", [0x2E2F32, 0x4A4C50], true, .stripe, 0xE53935),
        CardArt("silver", "Silver", [0xF1F2F4, 0xC3C6CB], false, .shards, 0x2B2B2E),
        CardArt("lime", "Lime", [0xE6F06A, 0xC5D92E], false, .blob, 0x6B4A2A),
        CardArt("jet", "Jet black", [0x0B0B0C, 0x2A2A2D], true, .none),
        CardArt("tangerine", "Tangerine", [0xE85A12, 0xF08A2C, 0xEFB04A], true, .waves, 0x7A2E0B),
        CardArt("amber", "Amber", [0xF6A51C, 0xEE6B1F], true, .curves),
        CardArt("slate-ring", "Slate", [0x343538, 0x55575B], true, .ring, 0x6E7075),
        CardArt("lagoon", "Lagoon", [0x0E8C9E, 0x43B9C8, 0x8ED9E0], true, .triangles),
        CardArt("royal-blue", "Royal blue", [0x082552, 0x0C3B80], true, .ring, 0x1FA2F0),
        CardArt("midnight", "Midnight", [0x0F1226, 0x2B2F6B], true, .curves),
        CardArt("emerald", "Emerald", [0x064E3B, 0x10B981], true, .waves, 0x022C22),
        CardArt("rose-gold", "Rose gold", [0xB76E79, 0xEBC1B7], false, .curves),
        CardArt("carbon", "Carbon", [0x111111, 0x3A3A3A], true, .triangles),
        CardArt("sunset", "Sunset", [0x7C3AED, 0xEC4899, 0xF59E0B], true, .blob, 0xFFFFFF),
        CardArt("gold", "Gold", [0x8A6A1F, 0xE9C46A], false, .curves),
        CardArt("violet", "Violet", [0x3F2A9C, 0x9B7BFF], true, .curves),
        CardArt("bank-blue", "Blue", [0x1B3FA8, 0x4C7DF0], true, .curves),
        CardArt("bank-navy", "Navy", [0x0B2E5C, 0x2A73D8], true, .curves),
        CardArt("bank-red", "Red", [0x8C1022, 0xE0483E], true, .curves),
        CardArt("bank-teal", "Teal", [0x0B5E4A, 0x2BB38A], true, .ring, 0xFFFFFF),
        CardArt("bank-charcoal-red", "Charcoal red", [0x2B2E33, 0xC9102A], true, .shards, 0xFFFFFF),
        CardArt("bank-orange", "Orange", [0xB2410B, 0xF59E0B], true, .curves),
        CardArt("bank-green", "Green", [0x14532D, 0x22A45D], true, .waves, 0x052E16),
        CardArt("bank-maroon", "Maroon", [0x4A0D1E, 0x9F1239], true, .curves),
    ]

    static func byKey(_ key: String?) -> CardArt? { presets.first { $0.key == key } }

    /// Your pick, or the bank's colours.
    static func forCard(bank: String, themeKey: String?) -> CardArt {
        byKey(themeKey) ?? bankDefault(bank)
    }

    static func bankDefault(_ bank: String) -> CardArt {
        func has(_ s: String) -> Bool { bank.range(of: s, options: .caseInsensitive) != nil }
        func starts(_ s: String) -> Bool { bank.lowercased().hasPrefix(s.lowercased()) }
        let key: String
        if starts("FAB") || has("Citi") || has("Wio") { key = "bank-blue" }
        else if has("NBD") || bank.caseInsensitiveCompare("Liv") == .orderedSame || has("Standard Chartered") { key = "bank-navy" }
        else if starts("ADCB") || has("RAK") || has("Ajman") { key = "bank-red" }
        else if has("Hilal") || has("Sharjah Islamic") { key = "bank-teal" }
        else if starts("HSBC") { key = "bank-charcoal-red" }
        else if starts("Mashreq") { key = "bank-orange" }
        else if has("Dubai Islamic") || starts("ADIB") || has("Emirates Islamic") || starts("NBF") { key = "bank-green" }
        else if starts("CBD") || has("Arab Bank") { key = "bank-maroon" }
        else {
            let fallback = ["violet", "midnight", "emerald", "royal-blue", "lagoon", "graphite"]
            let h = bank.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0x7FFF_FFFF }
            key = fallback[h % fallback.count]
        }
        return byKey(key)!
    }

    /// Short bank label and colour for the badge on a card.
    static func badge(_ bank: String) -> (String, Color) {
        func has(_ s: String) -> Bool { bank.range(of: s, options: .caseInsensitive) != nil }
        func starts(_ s: String) -> Bool { bank.lowercased().hasPrefix(s.lowercased()) }
        if starts("FAB") { return ("FAB", Color(hex: 0x1B3FA8)) }
        if has("NBD") || has("Emirates") && !has("Islamic") { return ("Emirates NBD", Color(hex: 0x0B2E5C)) }
        if starts("ADCB") { return ("ADCB", Color(hex: 0xB3122E)) }
        if has("Hilal") { return ("Al Hilal", Color(hex: 0x0B6B53)) }
        if starts("HSBC") { return ("HSBC", Color(hex: 0xC9102A)) }
        if starts("Mashreq") { return ("mashreq", Color(hex: 0xD9540B)) }
        if has("RAK") { return ("RAKBANK", Color(hex: 0xC62828)) }
        if has("Dubai Islamic") { return ("DIB", Color(hex: 0x14532D)) }
        if starts("ADIB") { return ("ADIB", Color(hex: 0x14532D)) }
        if starts("CBD") { return ("CBD", Color(hex: 0x7F1D1D)) }
        return (String(CardLabels.shortBank(bank).prefix(14)), Color(hex: 0x3F2A9C))
    }
}

/// The card background: gradient plus pattern, or your own picture.
struct CardBackground: View {
    let art: CardArt
    var picture: UIImage? = nil

    var body: some View {
        if let picture {
            Image(uiImage: picture)
                .resizable()
                .scaledToFill()
                .overlay(LinearGradient(stops: [.init(color: .black.opacity(0.35), location: 0), .init(color: .black.opacity(0.1), location: 0.45), .init(color: .black.opacity(0.7), location: 1)],
                                        startPoint: .top, endPoint: .bottom))
        } else {
            ZStack {
                LinearGradient(colors: art.colors.map { Color(hex: $0) }, startPoint: .topLeading, endPoint: .bottomTrailing)
                Canvas { ctx, size in draw(ctx: ctx, size: size) }
            }
        }
    }

    private func draw(ctx: GraphicsContext, size: CGSize) {
        let w = size.width, h = size.height
        let pc = Color(hex: art.patternColor)
        func circle(_ c: CGPoint, _ r: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)) }
        switch art.pattern {
        case .none: break
        case .curves:
            ctx.stroke(circle(CGPoint(x: w * 1.05, y: -h * 0.1), w * 0.55), with: .color(.white.opacity(0.10)), lineWidth: w * 0.08)
            ctx.stroke(circle(CGPoint(x: w * 1.1, y: h * 0.2), w * 0.75), with: .color(.white.opacity(0.07)), lineWidth: w * 0.04)
            ctx.fill(circle(CGPoint(x: w * 0.1, y: h * 1.2), w * 0.35), with: .color(.black.opacity(0.06)))
        case .stripe:
            ctx.fill(Path(CGRect(x: 0, y: h * 0.12, width: w * 0.012 + 4, height: h * 0.18)), with: .color(pc))
            ctx.fill(circle(CGPoint(x: w, y: h), w * 0.5), with: .color(.white.opacity(0.05)))
        case .shards:
            let shards: [[(CGFloat, CGFloat)]] = [
                [(0.45, 0.25), (0.62, 0.50), (0.45, 0.75)], [(0.62, 0.50), (0.80, 0.25), (0.80, 0.75)],
                [(0.52, 0.20), (0.70, 0.20), (0.61, 0.42)], [(0.52, 0.80), (0.70, 0.80), (0.61, 0.58)],
            ]
            for (i, pts) in shards.enumerated() {
                var p = Path()
                p.move(to: CGPoint(x: w * pts[0].0, y: h * pts[0].1))
                for pt in pts.dropFirst() { p.addLine(to: CGPoint(x: w * pt.0, y: h * pt.1)) }
                p.closeSubpath()
                ctx.fill(p, with: .color(pc.opacity(i % 2 == 0 ? 0.55 : 0.25)))
            }
        case .blob:
            ctx.fill(circle(CGPoint(x: w * 0.30, y: h * 0.55), h * 0.42), with: .color(pc.opacity(0.85)))
            ctx.stroke(circle(CGPoint(x: w * 0.30, y: h * 0.55), h * 0.52), with: .color(Color(hex: 0x8DB43A).opacity(0.55)), lineWidth: h * 0.08)
        case .ring:
            ctx.stroke(circle(CGPoint(x: w * 0.78, y: h * 0.62), h * 0.55), with: .color(pc.opacity(0.55)), lineWidth: h * 0.12)
        case .waves:
            for i in 0...3 {
                var p = Path()
                let y = h * (0.35 + CGFloat(i) * 0.18)
                p.move(to: CGPoint(x: 0, y: y))
                p.addCurve(to: CGPoint(x: w, y: y - h * 0.05), control1: CGPoint(x: w * 0.3, y: y - h * 0.25), control2: CGPoint(x: w * 0.6, y: y + h * 0.25))
                ctx.stroke(p, with: .color(pc.opacity(0.18 + 0.06 * Double(i))), lineWidth: h * 0.07)
            }
        case .triangles:
            let cols = 9, rows = 5
            let cw = w / CGFloat(cols), rh = h / CGFloat(rows)
            for r in 0..<rows {
                for c in 0..<cols {
                    let a = Double((r * 7 + c * 3) % 5) / 5
                    var p = Path()
                    let x = CGFloat(c) * cw, y = CGFloat(r) * rh
                    if (r + c) % 2 == 0 {
                        p.move(to: CGPoint(x: x, y: y)); p.addLine(to: CGPoint(x: x + cw, y: y)); p.addLine(to: CGPoint(x: x + cw / 2, y: y + rh))
                    } else {
                        p.move(to: CGPoint(x: x + cw / 2, y: y)); p.addLine(to: CGPoint(x: x + cw, y: y + rh)); p.addLine(to: CGPoint(x: x, y: y + rh))
                    }
                    p.closeSubpath()
                    ctx.fill(p, with: .color(.white.opacity(0.04 + a * 0.12)))
                }
            }
        }
    }
}

/// Your own card pictures, kept in the app's folder (not in backups).
enum CardPictures {
    static var folder: URL {
        let f = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("card_images", isDirectory: true)
        try? FileManager.default.createDirectory(at: f, withIntermediateDirectories: true)
        return f
    }

    /// Saves a picture for a card (at most 1400 px wide). Returns its theme key ("img:<file>").
    static func save(_ image: UIImage, cardKey: String) -> String? {
        let scale = min(1, 1400 / max(image.size.width, 1))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let resized = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = resized.jpegData(compressionQuality: 0.92) else { return nil }
        let safe = cardKey.map { $0.isLetter || $0.isNumber ? String($0) : "_" }.joined()
        let name = "\(safe)-\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
        do {
            try data.write(to: folder.appendingPathComponent(name))
            return "img:" + name
        } catch {
            return nil
        }
    }

    static func load(_ themeKey: String?) -> UIImage? {
        guard let k = themeKey, k.hasPrefix("img:") else { return nil }
        return UIImage(contentsOfFile: folder.appendingPathComponent(String(k.dropFirst(4))).path)
    }

    static func delete(_ themeKey: String) {
        guard themeKey.hasPrefix("img:") else { return }
        try? FileManager.default.removeItem(at: folder.appendingPathComponent(String(themeKey.dropFirst(4))))
    }
}
