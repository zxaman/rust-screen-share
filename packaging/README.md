# Build the native Nodeport Link apps

Run these commands from the repository root. The current downloadable clients do not embed HTML.

## Windows x64

Install Go 1.22 or later. In a POSIX shell:

```sh
(cd apps/pairing/windows && CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o ../../../downloads/Nodeport-Link-Windows-x64.exe .)
```

On Windows x64 with Go installed, run `go build -trimpath -o Nodeport-Link-Windows-x64.exe .` inside `apps/pairing/windows`. No Rust or browser is required for this executable.

## Android (Linux/macOS)

Install a JDK (17 recommended), Python 3, Android SDK Platform 35 and SDK Build Tools 35.0.0. Set `ANDROID_HOME`, add your JDK's `bin` to `PATH`, and supply a keystore that **you** control. If you need a new key, generate one outside the checkout and keep its password and backup private:

```sh
keytool -genkeypair -keystore "$HOME/nodeport-preview.p12" -storetype PKCS12 \
  -alias preview -keyalg RSA -keysize 3072 -validity 3650
export NODEPORT_KEYSTORE="$HOME/nodeport-preview.p12" NODEPORT_KEY_ALIAS=preview
./packaging/build-android.sh
```

The default output is `dist/Nodeport-Link-Android.apk` (native app ID `io.nodeport.link`). Keep your signing key outside Git and back it up for compatible updates. `./packaging/build-android.sh demo` is an explicit developer-only build of the archived web prototype; it must not be distributed as Nodeport Link.

## Verification

Run `tests/pairing/run.sh` to exercise the transports. Before distribution, check the native APK signature with `apksigner verify --verbose --print-certs`, publish SHA-256 sums, then run `python3 packaging/verify-downloads.py`. See the [physical-device acceptance checks](link.md#tests-and-physical-device-acceptance).
