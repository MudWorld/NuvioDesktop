import AVFoundation
import UIKit

/// Sends the current stream to an AirPlay receiver through AVPlayer.
///
/// MPV renders frames itself, so it cannot stream to an Apple TV. When the user picks an
/// AirPlay video route, MPV pauses and an AVPlayer with external playback continues at the
/// same position; the TV then fetches the stream directly. Formats AVPlayer cannot open
/// (e.g. MKV) or audio-only receivers fall back to MPV.
final class AirPlayHandoff: NSObject {
    private weak var owner: MPVPlayerViewController?
    private var player: AVPlayer?
    private var statusObservation: NSKeyValueObservation?
    private var externalCheck: DispatchWorkItem?
    private(set) var isActive = false

    init(owner: MPVPlayerViewController) {
        self.owner = owner
        super.init()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(routeChanged),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    private var airPlayRouteActive: Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .airPlay }
    }

    @objc private func routeChanged(_ notification: Notification) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if self.airPlayRouteActive {
                self.start()
            } else if self.isActive {
                self.stop(resumeLocally: true)
            }
        }
    }

    private func start() {
        guard !isActive, let owner, let stream = owner.currentStreamForAirPlay() else { return }
        let options: [String: Any]? = stream.headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": stream.headers]
        let item = AVPlayerItem(asset: AVURLAsset(url: stream.url, options: options))
        let player = AVPlayer(playerItem: item)
        player.allowsExternalPlayback = true
        player.usesExternalPlaybackWhileExternalScreenIsActive = true
        self.player = player
        isActive = true
        owner.pauseLocalForAirPlay()

        let startTime = CMTime(value: stream.positionMs, timescale: 1000)
        statusObservation = item.observe(\.status) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, self.player?.currentItem === item else { return }
                switch item.status {
                case .readyToPlay:
                    self.player?.seek(to: startTime) { [weak self] _ in self?.player?.play() }
                    self.scheduleExternalCheck()
                case .failed:
                    self.stop(resumeLocally: true)
                    self.owner?.showAirPlayUnsupported()
                default:
                    break
                }
            }
        }
    }

    /// Audio-only receivers (HomePod, speakers) never activate external video: go back to MPV.
    private func scheduleExternalCheck() {
        externalCheck?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.isActive, self.player?.isExternalPlaybackActive != true else { return }
            self.stop(resumeLocally: true)
        }
        externalCheck = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: work)
    }

    func stop(resumeLocally: Bool) {
        guard isActive else { return }
        let position = positionMs
        let wasPlaying = isPlaying
        externalCheck?.cancel()
        externalCheck = nil
        statusObservation = nil
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        isActive = false
        if resumeLocally {
            owner?.resumeLocalAfterAirPlay(atMs: position, playing: wasPlaying)
        }
    }

    // MARK: - Remote controls while the TV plays

    func play() { player?.play() }
    func pause() { player?.pause() }
    func seek(toMs ms: Int64) { player?.seek(to: CMTime(value: max(0, ms), timescale: 1000)) }
    func seek(byMs ms: Int64) { seek(toMs: positionMs + ms) }

    var positionMs: Int64 {
        guard let seconds = player?.currentTime().seconds, seconds.isFinite else { return 0 }
        return Int64(seconds * 1000)
    }

    var durationMs: Int64 {
        guard let seconds = player?.currentItem?.duration.seconds, seconds.isFinite else { return 0 }
        return Int64(seconds * 1000)
    }

    var isPlaying: Bool { (player?.rate ?? 0) > 0 }

    var isLoading: Bool {
        guard let player else { return false }
        return player.currentItem?.status == .unknown || player.timeControlStatus == .waitingToPlayAtSpecifiedRate
    }

    var isEnded: Bool {
        let duration = durationMs
        return duration > 0 && positionMs >= duration - 500 && !isPlaying
    }
}
