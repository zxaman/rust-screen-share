# Nodeport Link · LAN pairing milestone

The `Link` downloads are native clients, separate from the original HTML dashboard. Android and Windows x64 can each act as a controller or host. Volume works in both directions. Windows also supports attended primary-display capture and explicitly scoped left clicks from an Android viewer. There is no Android MediaProjection or accessibility service, relay, remote shell, unattended access, or permanent trust list.

## Nearby devices

While waiting for a request, the host advertises its OS-reported hostname/model and current TCP port every 750 ms to the selected interface’s IPv4 broadcast address and (when available) the TTL-1 multicast group `239.255.43.73` on UDP 48173. No secret, certificate pin, approval code, or stored device ID goes into a beacon. Advertising ends on consumption, stop, or expiry. Scanning passively listens for six seconds (Android takes a temporary Wi-Fi multicast lock), filters malformed/off-subnet/self traffic, deduplicates by source IP and port, and returns up to 32 results. A rescan starts empty. This is a snapshot, not a permanent trusted-devices list.

Treat every label as unverified. When connecting from the list, both clients check the key’s endpoint against the selected row before opening the pinned TLS connection. Direct key pairing still works without broadcast. Do not send private keys in discovery packets.

## What the key does

1. The host owner explicitly opens an invitation on a selected private IPv4 interface and random TCP port. Nothing runs as a service. A fresh 128-bit secret, endpoint and 128-bit SHA-256 certificate pin are encoded in a copyable `NP1-` key. Both hosts display the same key as a locally generated QR code. Windows draws the QR in a temporary native window, not a PNG on disk. Android renders a fixed black/white raster with a four-module quiet zone. Share it out of band and privately. Neither the key nor a session grant is written to a log or pairing database.
2. The controller connects using TLS and verifies the certificate against the pin **before** sending the invite secret. The first authenticated request consumes the invite and closes the listener. An IP address or the six digits alone cannot request access. A key expires after two minutes or five invalid attempts.
3. The host displays a native approval prompt identifying the requesting peer by address and the requested capability. Compare the six digits on both devices before approving. This comparison helps distinguish simultaneous requests; it is not a password or a verified account identity. An unexpected request should be denied. Denial, dismissal, a 30-second approval timeout, and expired invitations grant nothing. Generate a new invite to retry.
4. Approval applies only to that connected TLS socket. `UP`/`DOWN` invoke Android’s `AudioManager` for media volume or Windows Core Audio’s default multimedia endpoint. Unsupported operations close the connection. Updates are rate-limited; fixed-volume devices or unavailable endpoints may reject them.
5. Stop, socket loss, one minute idle or ten minutes total ends the session. Backgrounding the Android activity ends it too. There is no automatic reconnection; a new key and decision are required. Android keeps a TLS identity in the app’s Android Keystore; Windows generates a new in-memory certificate per invite. Neither identity grants access by itself.

## Windows screen sessions (NP2)

The Android viewer pins the host certificate using the NP1 invitation exactly as it does for volume. Inside TLS it sends `NP2 <secret> <comparison-code> SCREEN` or `SCREEN_CLICK`. The host consumes the invitation and prompts for that exact scope. A volume grant cannot be upgraded; a second session requires a new invitation. Only after `APPROVED <scope>` may `FRAME` return `JPEG <length>` followed by the exact image bytes. No images are saved. The client bounds frame length, decode dimensions and in-flight frames. The PC uses GDI on the current interactive primary display, refuses non-default input desktops and shows a visible local stop indicator. Protected surfaces may render black or end the session.

`CLICK x y` is accepted only in `SCREEN_CLICK`, after a recent frame, in bounds (0–10000 per axis), and rate-limited. Win32 `SendInput` emits only a left click at that point, subject to Windows privilege restrictions. No raw input or command strings are dispatched. View-only approval cannot grant clicks. Windows-as-controller and Android-as-host remain volume-only. Android’s camera permission is optional, requested only when opening the foreground non-exported QR reader; successful scans require an explicit access selection.

## Builds

The Windows host uses the Go standard library for TLS and Win32/Core Audio APIs. It is a small standalone adapter, not a binding to the Rust policy crates. Build with Go 1.22 or newer:

```sh
(cd apps/pairing/windows && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o ../../../downloads/Nodeport-Link-Windows-x64.exe .)
```

The Android app uses native Java APIs. Set up the SDK/JDK and your **own** keystore as in [the Android packaging guide](README.md#android-linuxmacos), then run `./packaging/build-android.sh link`. Install `dist/Nodeport-Link-Android.apk`; its package ID is `io.nodeport.link`, distinct from the old demo. Use the in-app camera or paste the key. Android uses the ZXing core 3.4.1 QR-reader subset (Apache-2.0, `apps/android/vendor/zxing`). Windows uses skip2/go-qrcode at da1b6568686e (MIT, vendored under `apps/pairing/windows/internal/qrcode`). The QR encoder is [Nayuki’s QR Code generator v1.8.0](https://github.com/nayuki/QR-Code-generator/tree/v1.8.0/java) (MIT; notices in `apps/android/vendor/`). The downloadable APK is preview-signed, not Play-distributed; its signer SHA-256 is `7e29e96d5689d3ea17b73a0e147cfa3214aec4a26bad9325f6eaff5942dcbe2b`. To install a future build signed by another key, uninstall this one first. The Windows executable is not Authenticode-signed.

## Tests and physical-device acceptance

`tests/pairing/run.sh` exercises Java↔Java and Java→Go interoperability using actual TLS sockets on loopback, disposable certificates, and a test volume adapter. It checks pin and secret mismatches, allow/deny, single-use, revocation and a scoped operation. `go test` in `apps/pairing/windows` runs the standalone host tests without a JDK. These are not Android UI or Windows audio-driver tests.

For physical acceptance, exercise phone and PC as both controller and host:

- Join the same trusted non-guest LAN. Select the interface connected to it. If prompted by Windows Firewall, allow this app on **private networks only**. Do not disable the firewall or forward ports.
- Start a host, scan from the other device, and select the actual advertised endpoint. With the host stopped and no other hosts present, rescan and verify the list is empty.
- Scan the live invitation from both a Windows and an Android host using Link’s camera. Deny camera permission and verify paste still works. Try an unrelated QR (it must not navigate or connect).
- With Windows as host, approve view only; verify that a visible window moving on the PC moves in the Android image and tapping cannot click. Stop and pair afresh with the click scope. Verify tapping a benign button acts once, tapping letterboxing does nothing and local Stop ends sharing. Deny a separate request; no pixels or input should be available. Check a display lock ends capture or blocks protected content.
- Enter a fresh device key. Confirm the owner sees the request, denies it, and the controller cannot change the volume or reuse that key.
- Generate a new key; compare the six digits, approve, and use both volume buttons. Observe the actual host volume (not just the controller acknowledgement).
- Stop on the host; verify further changes are impossible. On Android, repeat by backgrounding the host. Let an invitation expire and confirm it cannot pair.

A guest network with client isolation, a VPN interface, a blocked private firewall rule or an expired key can prevent connections. A fixed-volume output or OS audio policy can prevent volume changes despite successful pairing. Testing on physical devices is still required before treating this as a production release.
