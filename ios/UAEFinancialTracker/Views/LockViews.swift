import SwiftUI

/// Covers the app until you enter your PIN or use Face ID / Touch ID.
struct LockView: View {
    let onUnlock: () -> Void
    @State private var pin = ""
    @State private var wrong = false
    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            Rectangle().fill(.background).ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: "lock.fill").font(.system(size: 44)).foregroundStyle(Palette.brand)
                Text("\(AppInfo.name) is locked").font(.title2.bold())
                SecureField("PIN", text: $pin)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .multilineTextAlignment(.center)
                    .font(.title3.monospaced())
                    .padding(12)
                    .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    .frame(maxWidth: 220)
                    .focused($focused)
                    .onSubmit(check)
                if wrong { Text("Wrong PIN").foregroundStyle(Palette.red).font(.subheadline) }
                Button("Unlock", action: check).buttonStyle(.borderedProminent).disabled(pin.count < 4)
                if Settings.biometricEnabled, let name = AppLock.biometryName {
                    Button { Task { await biometric() } } label: { Label("Use \(name)", systemImage: name == "Touch ID" ? "touchid" : "faceid") }
                }
            }
            .padding()
        }
        .task {
            if Settings.biometricEnabled, AppLock.biometryName != nil { await biometric() } else { focused = true }
        }
    }

    private func check() {
        if AppLock.check(pin) { onUnlock() } else { wrong = true; pin = "" }
    }

    private func biometric() async {
        if await AppLock.unlockWithBiometrics() { onUnlock() } else { focused = true }
    }
}

struct SecurityView: View {
    @State private var enabled = Settings.lockEnabled
    @State private var biometric = Settings.biometricEnabled
    @State private var timeout = Settings.lockTimeout
    @State private var settingPin = false

    var body: some View {
        Form {
            Section {
                Toggle("App lock", isOn: Binding(get: { enabled }, set: { on in
                    if on { settingPin = true } else { AppLock.disable(); enabled = false }
                }))
            } footer: {
                Text("Asks for a PIN (or Face ID / Touch ID) when you open the app.")
            }
            if enabled {
                Section("Lock again after") {
                    Picker("Lock again after", selection: $timeout) {
                        Text("Immediately").tag(0)
                        Text("1 min").tag(60)
                        Text("5 min").tag(300)
                        Text("15 min").tag(900)
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: timeout) { _, v in Settings.lockTimeout = v }
                }
                Section {
                    if let name = AppLock.biometryName {
                        Toggle("Use \(name)", isOn: $biometric).onChange(of: biometric) { _, v in Settings.biometricEnabled = v }
                    }
                    Button("Change PIN") { settingPin = true }
                }
            }
            Section {
                Text("Your PIN is stored only as a salted hash on this iPhone. If you forget it, deleting and reinstalling the app removes the lock and the data, so keep a backup.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .themedScreen()
        .navigationTitle("App lock")
        .sheet(isPresented: $settingPin) {
            SetPinView { pin in
                AppLock.setPin(pin)
                enabled = true
            }
        }
    }
}

struct SetPinView: View {
    let onSet: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pin = ""
    @State private var again = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                SecureField("New PIN (4–8 digits)", text: $pin).keyboardType(.numberPad)
                SecureField("Same PIN again", text: $again).keyboardType(.numberPad)
                if let error { Text(error).foregroundStyle(Palette.red) }
            }
            .navigationTitle("Set PIN")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let digits = pin.filter(\.isNumber)
                        if digits.count < 4 || digits.count > 8 || digits != pin { error = "Use 4 to 8 digits"; return }
                        if pin != again { error = "The PINs don't match"; return }
                        onSet(pin)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
