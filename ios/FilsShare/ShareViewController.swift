import UIKit
import UniformTypeIdentifiers

/// Share → Fils from Mail, WhatsApp, Files or Photos: saves the shared text, screenshots, PDFs or backups for the app,
/// which adds them the next time it opens.
final class ShareViewController: UIViewController {
    private let label = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        label.text = "Saving…"
        label.font = .preferredFont(forTextStyle: .headline)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
        ])
        Task { await save() }
    }

    private var inbox: URL? {
        guard let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.com.filsspend.tracker") else { return nil }
        let f = base.appendingPathComponent("Inbox", isDirectory: true)
        try? FileManager.default.createDirectory(at: f, withIntermediateDirectories: true)
        return f
    }

    private func save() async {
        guard let inbox else {
            finish("Couldn't reach Fils. Open the app once, then try again.")
            return
        }
        var saved = 0
        var texts: [String] = []
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        for item in items {
            for provider in item.attachments ?? [] {
                let stamp = "\(Int(Date().timeIntervalSince1970 * 1000))-\(saved)"
                if provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier) {
                    if let url = await fileURL(provider, UTType.pdf) { saved += copy(url, to: inbox.appendingPathComponent("\(stamp).pdf")) }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.zip.identifier) {
                    if let url = await fileURL(provider, UTType.zip) { saved += copy(url, to: inbox.appendingPathComponent("\(stamp).zip")) }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    if let data = await imageData(provider) {
                        saved += ((try? data.write(to: inbox.appendingPathComponent("\(stamp).png"))) != nil) ? 1 : 0
                    }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.xml.identifier) {
                    if let url = await fileURL(provider, UTType.xml) { saved += copy(url, to: inbox.appendingPathComponent("\(stamp).xml")) }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    if let t = await text(provider) { texts.append(t) }
                }
            }
            if let t = item.attributedContentText?.string, !t.isEmpty, !texts.contains(t) { texts.append(t) }
        }
        if !texts.isEmpty {
            let body = texts.joined(separator: "\n\n")
            if (try? body.write(to: inbox.appendingPathComponent("\(Int(Date().timeIntervalSince1970 * 1000)).txt"), atomically: true, encoding: .utf8)) != nil { saved += 1 }
        }
        finish(saved > 0 ? "Saved for Fils.\nOpen Fils to add it." : "Nothing Fils can use was shared.")
    }

    private func copy(_ from: URL, to: URL) -> Int {
        (try? FileManager.default.copyItem(at: from, to: to)) != nil ? 1 : 0
    }

    private func fileURL(_ p: NSItemProvider, _ type: UTType) async -> URL? {
        await withCheckedContinuation { cont in
            _ = p.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                guard let url else { cont.resume(returning: nil); return }
                // The file is deleted when this returns: keep a copy.
                let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + (type.preferredFilenameExtension ?? "bin"))
                cont.resume(returning: (try? FileManager.default.copyItem(at: url, to: tmp)) != nil ? tmp : nil)
            }
        }
    }

    private func imageData(_ p: NSItemProvider) async -> Data? {
        await withCheckedContinuation { cont in
            _ = p.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                if let data, let img = UIImage(data: data) { cont.resume(returning: img.pngData()) } else { cont.resume(returning: nil) }
            }
        }
    }

    private func text(_ p: NSItemProvider) async -> String? {
        await withCheckedContinuation { cont in
            p.loadItem(forTypeIdentifier: UTType.plainText.identifier) { item, _ in
                if let s = item as? String { cont.resume(returning: s) }
                else if let d = item as? Data { cont.resume(returning: String(data: d, encoding: .utf8)) }
                else if let u = item as? URL, u.isFileURL, let s = try? String(contentsOf: u) { cont.resume(returning: s) }
                else { cont.resume(returning: nil) }
            }
        }
    }

    @MainActor
    private func finish(_ message: String) {
        label.text = message
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil)
        }
    }
}
