import ComposeApp
import CryptoKit
import Network
import SwiftUI
import UIKit

// MARK: - Kotlin → Swift host

struct PendingChannel: Equatable {
    let name: String
    let url: String
    let logo: String?
    let group: String?
}

/// Compose screens (Live TV, player header) call this to open the remote or to start a channel
/// on the Apple TV.
final class RemoteHost: NSObject, RemoteControlHost {
    static let shared = RemoteHost()

    func openRemote() {
        Task { @MainActor in TVRemotePresenter.present(pending: nil) }
    }

    func playLiveChannel(name: String, url: String, logo: String?, group: String?) {
        Task { @MainActor in
            TVRemotePresenter.present(pending: PendingChannel(name: name, url: url, logo: logo, group: group))
        }
    }
}

enum TVRemotePresenter {
    @MainActor static let model = TVRemoteModel()

    @MainActor
    static func present(pending: PendingChannel?) {
        model.pending = pending
        guard let top = topViewController() else { return }
        if top is TVRemoteHostingController { return }
        let controller = TVRemoteHostingController(rootView: TVRemoteView(model: model))
        controller.modalPresentationStyle = .pageSheet
        if let sheet = controller.sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
        }
        top.present(controller, animated: true)
        model.open()
    }

    @MainActor
    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first(where: \.isKeyWindow)?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

final class TVRemoteHostingController: UIHostingController<TVRemoteView> {
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        Task { @MainActor in TVRemotePresenter.model.close() }
    }
}

// MARK: - Model

/// Finds the WatchParty Apple TVs of this account in the Wi-Fi and controls them. Commands are
/// authenticated with an HMAC over the connection nonce and a counter, using the secret that all
/// devices of the account share (server section `remote.key`).
@MainActor
final class TVRemoteModel: ObservableObject {
    struct TV: Identifiable, Hashable {
        let id: String
        let name: String
        let endpoint: NWEndpoint
    }

    struct PlaybackState {
        var hasPlayer = false
        var title = ""
        var subtitle = ""
        var position = 0.0
        var duration = 0.0
        var live = false
        var status = "idle"
    }

    @Published var tvs: [TV] = []
    @Published var connected: TV?
    @Published var connecting = false
    @Published var message: String?
    @Published var state = PlaybackState()
    var pending: PendingChannel? {
        didSet { sendPendingIfPossible() }
    }

    private static let serviceType = "_watchparty._tcp"
    private static let suite = "space.mudworld.watchparty.account"

    private var browser: NWBrowser?
    private var connection: NWConnection?
    private var key: SymmetricKey?
    private var accountID = ""
    private var nonce = ""
    private var counter = 0
    private var buffer = Data()
    private var receivedState = false
    private var retriedKey = false

    // MARK: Lifecycle

    func open() {
        message = nil
        Task { await prepare() }
    }

    func close() {
        browser?.cancel()
        browser = nil
        disconnect()
        tvs = []
        pending = nil
    }

    private func prepare() async {
        guard let secret = await loadSecret() else {
            message = "Bitte zuerst in WatchParty anmelden."
            return
        }
        key = SymmetricKey(data: Data(secret.utf8))
        accountID = Self.accountIdentifier(secret)
        startBrowsing()
    }

    private func startBrowsing() {
        browser?.cancel()
        let browser = NWBrowser(for: .bonjour(type: Self.serviceType, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in self?.update(results) }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        var found: [TV] = []
        for result in results {
            guard case let .service(name, _, _, _) = result.endpoint,
                  let range = name.range(of: " #", options: .backwards),
                  String(name[range.upperBound...]) == accountID else { continue }
            found.append(TV(id: name, name: String(name[..<range.lowerBound]), endpoint: result.endpoint))
        }
        tvs = found.sorted { $0.name < $1.name }
        if connected == nil, !connecting, tvs.count == 1, let tv = tvs.first {
            connect(tv)
        }
    }

    // MARK: Connection

    func connect(_ tv: TV) {
        disconnect()
        connecting = true
        message = nil
        buffer = Data()
        nonce = ""
        counter = 0
        receivedState = false
        let connection = NWConnection(to: tv.endpoint, using: .tcp)
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let connection else { return }
            Task { @MainActor in self?.handle(state, of: connection, tv: tv) }
        }
        self.connection = connection
        connection.start(queue: .main)
        receive(on: connection, tv: tv)
    }

    func disconnect() {
        connection?.cancel()
        connection = nil
        connected = nil
        connecting = false
        state = PlaybackState()
    }

    private func handle(_ state: NWConnection.State, of connection: NWConnection, tv: TV) {
        guard connection === self.connection else { return }
        switch state {
        case .failed, .cancelled:
            let authenticated = receivedState
            disconnect()
            if !authenticated { keyMayBeStale() }
        default:
            break
        }
    }

    private func receive(on connection: NWConnection, tv: TV) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self, weak connection] data, _, isComplete, error in
            Task { @MainActor in
                guard let self, let connection, connection === self.connection else { return }
                if let data { self.buffer.append(data) }
                self.drain(tv: tv)
                if isComplete || error != nil {
                    let authenticated = self.receivedState
                    self.disconnect()
                    if !authenticated { self.keyMayBeStale() }
                } else if connection === self.connection {
                    self.receive(on: connection, tv: tv)
                }
            }
        }
    }

    /// The TV hangs up on a wrong MAC: the key we cached may differ from the account's current one.
    private func keyMayBeStale() {
        guard !retriedKey else {
            message = "Verbindung abgelehnt. Melde dich auf iPad und Apple TV mit demselben Konto an."
            return
        }
        retriedKey = true
        UserDefaults(suiteName: Self.suite)?.removeObject(forKey: "remote_key")
        Task {
            await prepare()
        }
    }

    private func drain(tv: TV) {
        while let newline = buffer.firstIndex(of: 0x0A) {
            let line = Data(buffer[buffer.startIndex..<newline])
            buffer.removeSubrange(buffer.startIndex...newline)
            guard let object = (try? JSONSerialization.jsonObject(with: line)) as? [String: Any] else { continue }
            handle(object, tv: tv)
        }
    }

    private func handle(_ object: [String: Any], tv: TV) {
        if object["hello"] != nil, let nonce = object["nonce"] as? String {
            self.nonce = nonce
            connecting = false
            connected = tv
            send(["cmd": "state"])
            sendPendingIfPossible()
            return
        }
        if let snapshot = object["state"] as? [String: Any] {
            receivedState = true
            retriedKey = false
            apply(snapshot)
        }
        if object["re"] != nil, object["ok"] as? Bool == false {
            message = "Das hat der Apple TV nicht ausgeführt."
        }
    }

    private func apply(_ snapshot: [String: Any]) {
        var next = PlaybackState()
        next.hasPlayer = snapshot["hasPlayer"] as? Bool ?? false
        next.title = snapshot["title"] as? String ?? ""
        next.subtitle = snapshot["subtitle"] as? String ?? ""
        next.position = (snapshot["position"] as? NSNumber)?.doubleValue ?? 0
        next.duration = (snapshot["duration"] as? NSNumber)?.doubleValue ?? 0
        next.live = snapshot["live"] as? Bool ?? false
        next.status = snapshot["status"] as? String ?? "idle"
        state = next
    }

    // MARK: Commands

    func send(_ command: [String: Any]) {
        guard let key, !nonce.isEmpty, let connection,
              let bodyData = try? JSONSerialization.data(withJSONObject: command, options: [.sortedKeys]),
              let body = String(data: bodyData, encoding: .utf8) else { return }
        counter += 1
        let mac = HMAC<SHA256>.authenticationCode(for: Data("\(nonce)|\(counter)|\(body)".utf8), using: key)
        let packet: [String: Any] = [
            "c": counter,
            "body": body,
            "mac": mac.map { String(format: "%02x", $0) }.joined(),
        ]
        guard var data = try? JSONSerialization.data(withJSONObject: packet) else { return }
        data.append(0x0A)
        connection.send(content: data, completion: .contentProcessed { _ in })
    }

    func togglePlayback() { send(["cmd": "toggle"]) }
    func seek(by seconds: Double) { send(["cmd": "seekBy", "s": seconds]) }
    func seek(to seconds: Double) { send(["cmd": "seekTo", "s": seconds]) }

    private func sendPendingIfPossible() {
        guard connected != nil, !nonce.isEmpty, let pending else { return }
        send([
            "cmd": "playLive",
            "name": pending.name,
            "url": pending.url,
            "logo": pending.logo ?? "",
            "group": pending.group ?? "",
        ])
        message = "„\(pending.name)“ wird auf dem Apple TV gestartet …"
        self.pending = nil
    }

    // MARK: Shared secret

    private func loadSecret() async -> String? {
        let defaults = UserDefaults(suiteName: Self.suite)
        guard let token = defaults?.string(forKey: "token"), !token.isEmpty else { return nil }
        if let cached = defaults?.string(forKey: "remote_key"), cached.count >= 32 { return cached }
        let server = defaults?.string(forKey: "server_url").flatMap { $0.isEmpty ? nil : $0 } ?? "https://sync.mudworld.space"

        func fetch() async -> String? {
            guard let url = URL(string: server + "/v1/data/remote.key") else { return nil }
            var request = URLRequest(url: url, timeoutInterval: 20)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            guard let (data, response) = try? await URLSession.shared.data(for: request),
                  (response as? HTTPURLResponse)?.statusCode == 200,
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let value = object["data"] as? String, value.count >= 32 else { return nil }
            return value
        }

        var secret = await fetch()
        if secret == nil {
            let fresh = (0..<32).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
            if let url = URL(string: server + "/v1/data/remote.key") {
                var request = URLRequest(url: url, timeoutInterval: 20)
                request.httpMethod = "PUT"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                request.httpBody = try? JSONSerialization.data(withJSONObject: ["data": fresh])
                _ = try? await URLSession.shared.data(for: request)
            }
            secret = await fetch()
        }
        if let secret { defaults?.set(secret, forKey: "remote_key") }
        return secret
    }

    private static func accountIdentifier(_ text: String) -> String {
        let digest = SHA256.hash(data: Data(("watchparty-remote|" + text).utf8))
        return digest.prefix(4).map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - UI

struct TVRemoteView: View {
    @ObservedObject var model: TVRemoteModel
    @Environment(\.dismiss) private var dismiss
    @State private var scrubPosition: Double?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    if let message = model.message {
                        Text(message)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    if model.connected != nil {
                        controls
                    } else {
                        picker
                    }
                }
                .padding(20)
            }
            .navigationTitle("Apple TV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Fertig") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var picker: some View {
        VStack(spacing: 14) {
            if model.tvs.isEmpty {
                ProgressView()
                Text("Suche Apple TV im WLAN …\nWatchParty muss dort geöffnet und mit demselben Konto angemeldet sein.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            } else {
                ForEach(model.tvs) { tv in
                    Button {
                        model.connect(tv)
                    } label: {
                        HStack {
                            Image(systemName: "appletv.fill")
                            Text(tv.name)
                            Spacer()
                            if model.connecting { ProgressView() }
                        }
                        .padding(14)
                        .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var controls: some View {
        let state = model.state
        return VStack(spacing: 18) {
            if state.hasPlayer {
                VStack(spacing: 4) {
                    Text(state.title).font(.title3.weight(.semibold)).multilineTextAlignment(.center)
                    if !state.subtitle.isEmpty {
                        Text(state.subtitle).font(.callout).foregroundStyle(.secondary)
                    }
                }
                if state.live {
                    Text("Live").font(.caption.weight(.bold)).foregroundStyle(.red)
                } else if state.duration > 0 {
                    VStack(spacing: 4) {
                        Slider(
                            value: Binding(
                                get: { scrubPosition ?? state.position },
                                set: { scrubPosition = $0 }
                            ),
                            in: 0...max(state.duration, 1),
                            onEditingChanged: { editing in
                                if !editing, let target = scrubPosition {
                                    model.seek(to: target)
                                    scrubPosition = nil
                                }
                            }
                        )
                        HStack {
                            Text(Self.format(scrubPosition ?? state.position))
                            Spacer()
                            Text(Self.format(state.duration))
                        }
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                    }
                }
                HStack(spacing: 26) {
                    if !state.live {
                        button("gobackward.30") { model.seek(by: -30) }
                        button("gobackward.10") { model.seek(by: -10) }
                    }
                    button(state.status == "playing" ? "pause.fill" : "play.fill", large: true) { model.togglePlayback() }
                    if !state.live {
                        button("goforward.10") { model.seek(by: 10) }
                        button("goforward.30") { model.seek(by: 30) }
                    }
                }
            } else {
                Text("Auf dem Apple TV läuft gerade nichts.\nStarte dort einen Film oder wähle hier in Live TV „Auf Apple TV abspielen“.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private func button(_ symbol: String, large: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: large ? 34 : 24))
                .frame(width: large ? 76 : 54, height: large ? 76 : 54)
                .background(.white.opacity(large ? 0.18 : 0.1), in: Circle())
        }
        .buttonStyle(.plain)
    }

    private static func format(_ seconds: Double) -> String {
        let total = Int(max(0, seconds))
        let hours = total / 3600, minutes = (total % 3600) / 60, secs = total % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, secs)
            : String(format: "%d:%02d", minutes, secs)
    }
}
