#!/usr/bin/env bash
# Build from the checked-in sources using a stock Android SDK, without Gradle.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
: "${NODEPORT_KEYSTORE:?Set NODEPORT_KEYSTORE to a private keystore outside the checkout}"
: "${NODEPORT_KEY_ALIAS:?Set NODEPORT_KEY_ALIAS to its signing alias}"
bt="$ANDROID_HOME/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}"
platform="$ANDROID_HOME/platforms/android-35/android.jar"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work"/{assets,classes,dex} dist
case "${1:-link}" in
  link)
    cp apps/android/vendor/NOTICE.txt "$work/assets/NOTICE.txt"
    cp apps/android/vendor/zxing/LICENSE "$work/assets/ZXING-LICENSE.txt"
    manifest=apps/android/LinkManifest.xml
    sources=(apps/android/src/io/nodeport/link/*.java apps/android/vendor/io/nayuki/qrcodegen/*.java)
    while IFS= read -r -d '' f; do sources+=("$f"); done < <(find apps/android/vendor/zxing -name '*.java' -print0)
    artifact=dist/Nodeport-Link-Android.apk ;;
  demo)
    python3 packaging/bundle.py "$work/assets/workspace.html"
    manifest=apps/android/AndroidManifest.xml
    sources=(apps/android/src/io/nodeport/preview/*.java)
    artifact=dist/Nodeport-SIMULATED-Dashboard.apk ;;
  *) echo "Usage: $0 [link|demo]" >&2; exit 2 ;;
esac
javac -source 8 -target 8 -classpath "$platform" -d "$work/classes" "${sources[@]}"
(cd "$work/classes" && jar cf "$work/classes.jar" .)
"$bt/d8" --lib "$platform" --min-api 28 --output "$work/dex" "$work/classes.jar"
"$bt/aapt2" link -I "$platform" --manifest "$manifest" -A "$work/assets" -o "$work/unsigned.apk"
python3 - "$work" <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import sys
p = Path(sys.argv[1])
with ZipFile(p/'unsigned.apk', 'a', ZIP_DEFLATED) as apk:
    apk.write(p/'dex/classes.dex', 'classes.dex')
PY
"$bt/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
# Prompt for passwords; never put a key or password in the repository.
"$bt/apksigner" sign --ks "$NODEPORT_KEYSTORE" --ks-key-alias "$NODEPORT_KEY_ALIAS" \
    --v4-signing-enabled false --out "$artifact" "$work/aligned.apk"
"$bt/apksigner" verify --verbose --print-certs "$artifact"
