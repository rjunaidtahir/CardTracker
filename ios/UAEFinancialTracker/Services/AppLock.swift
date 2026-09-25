import Foundation
import LocalAuthentication
import CryptoKit
import Security

/// PIN and Face ID / Touch ID lock (the same PIN scheme as Android: SHA-256 of salt + PIN, 10,000 rounds).
@MainActor
enum AppLock {
    static func hash(pin: String, salt: String) -> String {
        var data = Data((salt + pin).utf8)
        for _ in 0..<10_000 { data = Data(SHA256.hash(data: data)) }
        return data.map { String(format: "%02x", $0) }.joined()
    }

    static func setPin(_ pin: String) {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let salt = bytes.map { String(format: "%02x", $0) }.joined()
        Settings.pinSalt = salt
        Settings.pinHash = hash(pin: pin, salt: salt)
        Settings.lockEnabled = true
    }

    static func check(_ pin: String) -> Bool {
        guard let salt = Settings.pinSalt, let h = Settings.pinHash else { return false }
        return hash(pin: pin, salt: salt) == h
    }

    static func disable() {
        Settings.lockEnabled = false
        Settings.pinSalt = nil
        Settings.pinHash = nil
    }

    /// "Face ID", "Touch ID" or nil when the phone has neither (or it isn't set up).
    static var biometryName: String? {
        let ctx = LAContext()
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return nil }
        switch ctx.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .opticID: return "Optic ID"
        default: return nil
        }
    }

    static func unlockWithBiometrics() async -> Bool {
        let ctx = LAContext()
        ctx.localizedFallbackTitle = "Use PIN"
        return (try? await ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Unlock \(AppInfo.name)")) ?? false
    }
}
