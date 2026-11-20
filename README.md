# Nodeport Link — discover and pair on your LAN

**Install Nodeport Link 0.3 on both devices.** This is a native Android app and a Windows console app, not the sample web dashboard. Both can host, list discoverable Nodeport hosts and request control. The **only** control currently implemented is media/playback volume up/down.

| Platform | Download | What opens |
| --- | --- | --- |
| Android 9+ | [Download native Nodeport Link 0.3 APK](https://github.com/zxaman/rust-screen-share/raw/2c2bad368c3c4f978cfa113c1ecf28b268754bc3/downloads/Nodeport-Link-Android.apk) | **Nearby devices**, with **Scan for nearby devices** and **Receive a request** |
| Windows 10/11 x64 | [Download native Nodeport Link 0.3 EXE](https://github.com/zxaman/rust-screen-share/raw/2c2bad368c3c4f978cfa113c1ecf28b268754bc3/downloads/Nodeport-Link-Windows-x64.exe) | **NODEPORT LINK 0.3**, with **Host**, **Scan** and **Connect directly** |

[SHA-256 checksums](https://github.com/zxaman/rust-screen-share/raw/2c2bad368c3c4f978cfa113c1ecf28b268754bc3/downloads/LINK-SHA256SUMS.txt) · [Build instructions](packaging/README.md) · [Pairing, security and test details](packaging/link.md)

**Check the Android install:** The installer should say **Nodeport Link** (version `0.3.0-lan.1`, package `io.nodeport.link`). Tap **Open** from the installer to launch the app you just installed. **Nodeport Preview** (`io.nodeport.preview`) is a separate app and can still be on your home screen.

**Already installed a preview?** If you see “Workspace overview” or pre-filled device cards, you opened **Nodeport Preview**, not Link. Remove that app/launcher. If Android refuses to update an older **Link** APK due to its different preview signer, uninstall the old Link app before installing this build. The Windows EXE is not Authenticode-signed.

### Try it with two devices

1. Put both on the same private IPv4 Wi-Fi/Ethernet LAN. On the **target**, tap **Receive a request** (Android) or choose **1. Host** (Windows). Choose the LAN interface if prompted. Keep the app open; the invitation lasts two minutes.
2. On the **controller**, choose **Scan**. After four seconds the list shows names and addresses received on the selected subnet. Select the target. An empty list means no matching announcements arrived; it is never populated with sample devices. Scan again after starting a host.
3. Enter the target’s private `NP1-` key. Compare the six digits on both screens. The target owner must approve the request. A discovered name is **not** proof of identity or permission.
4. Use **Volume up/down** (Android) or type `UP` / `DOWN` (Windows). To end the session tap **Stop / disconnect**, or type `STOP` in the Windows console. Another session requires another key and approval.

**If scan finds nothing:** Only actively inviting Link hosts advertise—not arbitrary phones, Bluetooth devices, or other PCs. Select the correct LAN rather than a VPN, and avoid guest networks with client isolation. Discovery uses local UDP broadcast on port 48173. If broadcasts are filtered, use **Connect directly** / enter the key without scanning. If direct pairing also fails, check that the key is fresh, both devices can reach each other and Windows allows the app on **private networks only**. Do not disable the firewall or forward ports.

Discovery and pairing have been exercised between the Java and Go implementations over real UDP and TLS sockets on loopback. Physical Android/Windows testing is still needed. Screen mirroring, desktop input, file transfer, and internet connectivity are not part of these builds.

## Development-only dashboard example

The original static dashboard in `apps/desktop` is a **simulation** with fixture data. It is not embedded in either download. To inspect the design locally, run `python3 -m http.server 8000 --bind 127.0.0.1 --directory apps/desktop`.

## Rust workspace

`crates/core-engine` is a dependency-free session policy library. Its checks bind each grant to a peer, capability and monotonic deadline, and support immediate revocation. Its workspace registry rejects unpaired peers, empty grants, and expired approvals; unpairing clears existing grants.

`crates/transport-layer` selects among candidate channels for an already authenticated peer. It checks bandwidth, prefers local routes, requires opt-in for internet routes, and excludes BLE for streaming or bulk files. It is a selection policy, not a network implementation. Run `cargo test --workspace` with a stable Rust toolchain. The prototype UI does not yet invoke the Rust crates.

## Next milestones

1. Add a Tauri host and use the Rust engine as the authority for all requests. Bind peers to authenticated device identities, implement owner-approved pairing, and store identity keys in the OS credential store.
2. Integrate the LAN pairing flow with the broader workspace; add separately reviewed capability adapters.
3. Deliver Windows-to-Windows, view-only screen sharing with visible consent indicators; then add Android MediaProjection with system consent.
4. Add individual adapters for scoped files, remote input and monitoring. Terminal, hardware and privileged administration remain disabled until each has platform-specific authorization and auditing.

## Assets

Device illustrations and network topology are local SVGs. Bundled DM Sans and Lucide icons include their licenses under `apps/desktop/assets`.
