# Nodeport Link · LAN pairing milestone

The `Link` downloads are native clients, separate from the original HTML dashboard. Android is both a controller and a host. Windows x64 is a host. Only media/playback volume is implemented. There is no relay server, device directory, remote shell, screen capture, accessibility service, unattended mode or permanent trust list.

## What the key does

1. The host owner explicitly opens an invitation on a selected private IPv4 interface and random TCP port. Nothing runs as a service. A fresh 128-bit secret, endpoint and 128-bit SHA-256 certificate pin are encoded in a copyable `NP1-` key. Android can also display the same key as a QR code. Share it out of band and privately. Neither the key nor a session grant is written to a log or pairing database.
2. The controller connects using TLS and verifies the certificate against the pin **before** sending the invite secret. The first authenticated request consumes the invite and closes the listener. An IP address or the six digits alone cannot request access. A key expires after two minutes or five invalid attempts.
3. The host displays a native approval prompt identifying the requesting peer by address and the requested capability. Compare the six digits on both devices before approving. This comparison helps distinguish simultaneous requests; it is not a password or a verified account identity. An unexpected request should be denied. Denial, dismissal, a 30-second approval timeout, and expired invitations grant nothing. Generate a new invite to retry.
4. Approval applies only to that connected TLS socket. `UP`/`DOWN` invoke Android’s `AudioManager` for media volume or Windows Core Audio’s default multimedia endpoint. Unsupported operations close the connection. Updates are rate-limited; fixed-volume devices or unavailable endpoints may reject them.
5. Stop, socket loss, one minute idle or ten minutes total ends the session. Backgrounding the Android activity ends it too. There is no automatic reconnection; a new key and decision are required. Android keeps a TLS identity in the app’s Android Keystore; Windows generates a new in-memory certificate per invite. Neither identity grants access by itself.

## Builds

The Windows host uses the Go standard library for TLS and Win32/Core Audio APIs. It is a small standalone adapter, not a binding to the Rust policy crates. Build with Go 1.22 or newer:

```sh
(cd apps/pairing/windows && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o ../../../downloads/Nodeport-Link-Windows-x64.exe .)
```

The Android app uses native Java APIs. Set up the SDK/JDK and your **own** keystore as in [the Android packaging guide](README.md#android-linuxmacos), then run `./packaging/build-android.sh link`. Install `dist/Nodeport-Link-Android.apk`; its package ID is `io.nodeport.link`, distinct from the old demo. No camera permission is needed: scan a host QR with the controller phone’s system camera or enter the key manually. The QR encoder is [Nayuki’s QR Code generator v1.8.0](https://github.com/nayuki/QR-Code-generator/tree/v1.8.0/java) (MIT; notices in `apps/android/vendor/`). The downloadable APK is preview-signed, not Play-distributed; its signer SHA-256 is `3c994fd8b7c33310efb1cf81512d75c91aba5265300915ee13321c4f935aa99a`. To install a future build signed by another key, uninstall this one first. The Windows executable is not Authenticode-signed.

## Tests and physical-device acceptance

`tests/pairing/run.sh` exercises Java↔Java and Java→Go interoperability using actual TLS sockets on loopback, disposable certificates, and a test volume adapter. It checks pin and secret mismatches, allow/deny, single-use, revocation and a scoped operation. `go test` in `apps/pairing/windows` runs the standalone host tests without a JDK. These are not Android UI or Windows audio-driver tests.

For physical acceptance, run the following twice: Android controller → Android host, then Android controller → Windows host:

- Join the same trusted non-guest LAN. Select the interface connected to it. If prompted by Windows Firewall, allow this app on **private networks only**. Do not disable the firewall or forward ports.
- Enter a fresh device key. Confirm the owner sees the request, denies it, and the controller cannot change the volume or reuse that key.
- Generate a new key; compare the six digits, approve, and use both volume buttons. Observe the actual host volume (not just the controller acknowledgement).
- Stop on the host; verify further changes are impossible. On Android, repeat by backgrounding the host. Let an invitation expire and confirm it cannot pair.

A guest network with client isolation, a VPN interface, a blocked private firewall rule or an expired key can prevent connections. A fixed-volume output or OS audio policy can prevent volume changes despite successful pairing. Testing on physical devices is still required before treating this as a production release.
