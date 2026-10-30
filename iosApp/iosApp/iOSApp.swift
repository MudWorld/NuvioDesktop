import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationLockAppDelegate.self) private var appDelegate

    init() {
        // Apply account data fetched on the last run before any store reads UserDefaults.
        AccountService.shared.prepareOnLaunch()
        AirPlayButton_iosKt.installPlayerPlatformSlots()
        RemoteControlBridge.shared.host = RemoteHost.shared
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    AppUrlBridgeKt.handleAppUrl(url: url.absoluteString)
                }
        }
    }
}
