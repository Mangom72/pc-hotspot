# Auto connect and One UI layout (1.0.4)

Design references checked on 2026-10-10:
- https://developer.samsung.com/one-ui/index.html
- https://developer.samsung.com/one-ui/layout/grid.html
- https://news.samsung.com/global/samsung-launches-one-ui-9-beta-for-galaxy-s26-series-users
- https://developer.android.com/design/ui/mobile/guides/layout-and-content/edge-to-edge

One UI 9 is based on Android 17. Samsung's public component guidance is version-independent; this app follows that guidance rather than claiming to use Samsung's private One UI widgets. Native Views use the system font, large viewing header, reachable interaction sections, scalable text, system dark mode, inset handling, 48dp minimum controls and a bounded content width on larger screens.

## Automatic Wi-Fi suggestion

The user enables monitoring and supplies the hotspot SSID and WPA2 passphrase once. Credentials stay in app-private preferences and the Android Wi-Fi framework; backup is disabled. No Wi-Fi password is compiled into the APK or sent through Bluetooth.

An optional connectedDevice foreground service keeps an authenticated BLE state subscription open. The PC watches the actual NetworkManager state every two seconds, so changes made by F9, nmcli or the desktop UI are detected. Reconnection reads state and never replays a control command. A visible notification provides Wi-Fi settings and stop actions. Reconnection backs off from 10 to 60 seconds; Android power management may delay it.

On hotspot-on, the app calls the official WifiManager.addNetworkSuggestions() API. Android's first app approval is required. The framework chooses the access point; another working Wi-Fi can be retained. Network suggestion registration is not proof of successful association. The notification offers the system Wi-Fi picker as fallback. Existing manually saved networks are untouched. Disabling removes only this app's suggestions, with linger on Android 13+ to avoid forcing an existing connection down.

The current PC protocol enrolls one phone. Additional tablets require a separate multi-device enrollment change. This release does not silently extend the existing authorization boundary.

Automatic monitoring is opt-in; opening the app resumes it if enabled. After a phone reboot, open the app to resume monitoring. It does not turn Wi-Fi on or bypass system network selection. It does not create a VPN or modify AdGuard.

## Optional Shizuku direct switching (1.0.6)

Official SDK: https://github.com/RikkaApps/Shizuku-API (api/provider 13.1.5).
Official setup: https://shizuku.rikka.app/guide/setup/.
The user explicitly grants Shizuku access and selects direct switching. A non-daemon UserService runs as shell, exposing only connect/configured-SSID status methods. Commands use structured ProcessBuilder arguments, never a shell string. Credentials and raw command output are not logged or returned through Binder. The service saves the network via Android's `cmd wifi connect-network` command and checks `cmd wifi status` until association is reported; command acceptance alone does not count as connection. A connection attempt has bounded timeouts and no repeated network switching loop. BLE loss/off or opt-out cancels pending polling; a command already handed to Android cannot be withdrawn. This mode adds/saves the target network, but does not delete other saved networks or change a VPN. Without root, Shizuku must be restarted after a reboot. Suggestions remain available as a separate mode.

## App updates

Existing signed GitHub-release updates are preserved. The scheduler checks approximately every six hours when enabled; timing is controlled by Android. A new toggle cancels/resumes periodic checks. Manual checking is always available. Downloaded APK size, hash, package, version and signing certificate are verified. Installation requires the user to approve the Android installer. Disabling automatic checks does not remove a downloaded valid update.
