#!/usr/bin/env bash
# JDK 17+, Go 1.22+, C compiler (for -race). No hardware or LAN listener required.
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir "$work/classes"
find apps/android/vendor/{zxing,io} -name '*.java' > "$work/qr-sources"
javac --release 8 -d "$work/classes" @"$work/qr-sources" apps/android/src/io/nodeport/link/{PairLink,LanDiscovery,InviteQr}.java tests/pairing/{ProtocolSmoke,ScreenSmoke}.java
# A throwaway test identity, never used by the app or to sign an APK.
keytool -genkeypair -storetype PKCS12 -keystore "$work/test.p12" -alias host -keyalg EC \
    -dname CN=Test -validity 1 -storepass fixturepass -keypass fixturepass
java -cp "$work/classes" io.nodeport.link.ProtocolSmoke "$work/test.p12"
(cd apps/pairing/windows && NODEPORT_TEST_CLASSES="$work/classes" go test -race -count=1 -v ./...)
