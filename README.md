# Nodeport — Universal Device Access

A first milestone for a Rust-powered, permission-based device management platform. This repository contains a runnable offline-first dashboard prototype and separate Rust session and transport policy crates, not a working remote-access agent.

## Downloads — v0.1.0 Preview 1

| Platform | Download | How it runs |
| --- | --- | --- |
| Windows 10/11, x64 | [Nodeport-Windows-x64.exe](https://github.com/zxaman/rust-screen-share/raw/refs/tags/v0.1.0-preview.1/downloads/Nodeport-Windows-x64.exe) | Run the portable launcher to open the offline dashboard in your default browser. No administrator access or installation required. |
| Android 9 or newer | [Nodeport-Android.apk](https://github.com/zxaman/rust-screen-share/raw/refs/tags/v0.1.0-preview.1/downloads/Nodeport-Android.apk) | Install the preview APK and open **Nodeport Preview**. Uses Android System WebView; no network or device-access permissions. |

[Release notes](https://github.com/zxaman/rust-screen-share/releases/tag/v0.1.0-preview.1) · [SHA-256 checksums](https://github.com/zxaman/rust-screen-share/raw/refs/tags/v0.1.0-preview.1/downloads/SHA256SUMS.txt) · [Build from source](packaging/README.md)

These packages contain the **simulated workspace**, not a remote-control agent. The Windows launcher is not Authenticode-signed and may show a publisher warning. The Android APK is sideloaded and signed with a preview-only certificate; a differently signed future build requires uninstalling this preview first. Verify your download against the checksums (PowerShell: `Get-FileHash .\Nodeport-Windows-x64.exe -Algorithm SHA256`; Linux: `sha256sum Nodeport-Android.apk`).

## Run the dashboard

```sh
python3 -m http.server 8000 --bind 127.0.0.1 --directory apps/desktop
```

Open http://127.0.0.1:8000. No frontend build or external services are required. The interface includes registered devices, simulated local discovery and pairing, explicit per-session capability selection, revocation, sample telemetry, transfer history, and an activity log. Demo changes persist in this browser's local storage (`nodeport-v1`); removing that key restores the fixtures. No real pairing, device discovery, file access, or network sessions take place. The UI is not a security enforcement boundary.

## Rust workspace

`crates/core-engine` is a dependency-free session policy library. Its checks bind each grant to a peer, capability and monotonic deadline, and support immediate revocation. Its workspace registry rejects unpaired peers, empty grants, and expired approvals; unpairing clears existing grants.

`crates/transport-layer` selects among candidate channels for an already authenticated peer. It checks bandwidth, prefers local routes, requires opt-in for internet routes, and excludes BLE for streaming or bulk files. It is a selection policy, not a network implementation. Run `cargo test --workspace` with a stable Rust toolchain. The prototype UI does not yet invoke the Rust crates.

## Next milestones

1. Add a Tauri host and use the Rust engine as the authority for all requests. Bind peers to authenticated device identities, implement owner-approved pairing, and store identity keys in the OS credential store.
2. Implement authenticated encrypted LAN transport, discovery, session termination, and durable device revocation before exposing native capabilities.
3. Deliver Windows-to-Windows, view-only screen sharing with visible consent indicators; then add Android MediaProjection with system consent.
4. Add individual adapters for scoped files, remote input and monitoring. Terminal, hardware and privileged administration remain disabled until each has platform-specific authorization and auditing.

## Assets

Device illustrations and network topology are local SVGs. Bundled DM Sans and Lucide icons include their licenses under `apps/desktop/assets`.
