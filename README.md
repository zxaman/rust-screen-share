# Nodeport Link — attended LAN pairing

**Install Link 0.4 on both devices.** This is the native client, not the web dashboard. The Windows host can now share its primary display with an Android viewer. The owner can approve viewing alone or explicitly approve viewing **and** left clicks. Volume remains a separate capability.

| Platform | Download | Supported roles |
| --- | --- | --- |
| Android 9+ | [Download Nodeport Link 0.4 APK](https://github.com/zxaman/rust-screen-share/raw/refs/tags/v0.4.0-lan.1/downloads/Nodeport-Link-Android.apk) | QR scanner, LAN discovery, Windows screen viewer, optional tap-to-click; volume controller or volume host |
| Windows 10/11 x64 | [Download Nodeport Link 0.4 EXE](https://github.com/zxaman/rust-screen-share/raw/refs/tags/v0.4.0-lan.1/downloads/Nodeport-Link-Windows-x64.exe) | QR invitation window, screen/click host; volume host or volume controller |

[SHA-256 checksums](https://github.com/zxaman/rust-screen-share/raw/refs/tags/v0.4.0-lan.1/downloads/LINK-SHA256SUMS.txt) · [Build from source](packaging/README.md) · [Protocol and device test checklist](packaging/link.md)

Android package: `io.nodeport.link`, version `0.4.0-lan.1`. If Android rejects an update because the preview signer differs, uninstall the old **Nodeport Link** before installing. **Nodeport Preview** (`io.nodeport.preview`) is the separate simulated dashboard. The Windows executable is unsigned. Run it as a **normal user**, not as administrator.

### View a Windows PC from a phone

1. Put both devices on the same trusted private LAN. On the **PC**, run the EXE, choose **1. Host**, choose the correct interface if prompted and leave the console open. A separate window shows the one-time QR; the console also prints its `NP1-` key.
2. On **Android**, tap **Scan host QR with camera**, allow camera access and point at the **live QR on the PC**. Scanning fills the key field; it does not connect or grant access. Frames from the scanner are processed locally, not uploaded or recorded. Pasting the key works without camera permission.
3. Choose **View Windows screen (no input)** or **View Windows screen + tap to click**. Compare the six digits on the two devices. The PC owner must choose **Yes** to the specific request. The whole primary display is visible to the viewer, so close sensitive content before approving.
4. The phone displays low-rate JPEG frames (up to 960 × 720, approximately two per second, not video-rate streaming). Only an approved click session maps a tap on the displayed image to a single left click. To stop, use **Stop viewing** on Android, choose **OK** on the PC’s sharing indicator, or type `STOP` and press Enter in the host console.

**Android as the host:** Tap **Host this phone · volume only** and keep Link in the foreground. The other phone can scan its live QR or paste its key, then request volume. Android **screen capture and remote touch control are not implemented**. Camera permission is only for scanning QR codes; it is not a screen-capture permission. Windows-to-Windows screen viewing, keyboard injection, drag, recording, file transfer and internet access are also not implemented.

### If pairing or discovery fails

- **Blank QR:** scan the actual host display, not a screenshot. Android protects the invitation window against screenshots. Confirm an unexpired key appears below the code (or in the Windows console). Start a fresh invitation if it expired.
- **Key rejected:** the entire 55-character `NP1-` key is case-sensitive. The six digits are for comparing the incoming request, **not** for entering as the key. Leaving the host app on Android, denying a request or stopping the host invalidates that invitation. A scanned list row must match the key’s IP and port.
- **Empty nearby list:** first open a host invitation. Scan on the correct Wi-Fi/Ethernet interface. Link listens for **six seconds** for live advertisements: subnet broadcast and, when supported, link-local multicast (`239.255.43.73`, UDP 48173). Discovery never lists arbitrary phones, Bluetooth devices or sample devices. Camera/key pairing does **not** depend on seeing a row in discovery.
- **Cannot reach the host even by QR:** guest/client isolation, firewall rules, VPN routing or different subnets can block connections. Windows must allow this app on the **private** network. Do not disable the firewall or forward ports. For support, give the host/controller OS versions, direction, app version and exact error message; **do not share the key or QR**.

**Validation:** QR pixel round trips for both encoders through the actual Android decoder; Java/Go discovery and pinned-TLS interop; JPEG test frames, scoped click dispatch, denial and revocation. Physical camera autofocus, Windows desktop capture/input and device-specific Wi-Fi behavior still require real-device testing.

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
