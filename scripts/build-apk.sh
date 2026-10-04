#!/usr/bin/env bash
set -euo pipefail
project=$(cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/direct-mogo/jdk-17/jdk-17.0.20.1+1}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/.cache/codex-direct-mogo/android-sdk}
export PATH="$JAVA_HOME/bin:$PATH"
command -v java >/dev/null || { echo 'JDK 17 경로를 JAVA_HOME에 지정하세요.' >&2; exit 1; }
[[ -f "$ANDROID_HOME/platforms/android-36/android.jar" ]] || { echo 'Android SDK 36을 설치하고 ANDROID_HOME을 지정하세요.' >&2; exit 1; }
signing_dir="$HOME/.local/share/pc-hotspot-signing"
mkdir -p "$signing_dir"
chmod 700 "$signing_dir"
export HOTSPOT_KEYSTORE="$signing_dir/release.jks"
password_file="$signing_dir/password"
if [[ ! -f "$password_file" ]]; then
    umask 077
    head -c 32 /dev/urandom | base64 > "$password_file"
fi
export HOTSPOT_KEY_PASSWORD
HOTSPOT_KEY_PASSWORD=$(cat "$password_file")
if [[ ! -f "$HOTSPOT_KEYSTORE" ]]; then
    keytool -genkeypair -keystore "$HOTSPOT_KEYSTORE" -storepass:env HOTSPOT_KEY_PASSWORD \
        -keypass:env HOTSPOT_KEY_PASSWORD -alias pc-hotspot -keyalg RSA -keysize 3072 \
        -validity 10000 -dname 'CN=PC Hotspot Local Release' >/dev/null
fi
version=$(python3 - "$project/android/app/build.gradle.kts" <<'PYVERSION'
import re, sys
print(re.search(r'versionName\s*=\s*"([^"]+)"', open(sys.argv[1]).read()).group(1))
PYVERSION
)
cd "$project/android"
./gradlew --no-daemon assembleRelease lintRelease testReleaseUnitTest
mkdir -p "$project/artifacts"
cp app/build/outputs/apk/release/app-release.apk "$project/artifacts/pc-hotspot-${version}.apk"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$project/artifacts/pc-hotspot-${version}.apk"
cd "$project/artifacts"
sha256sum "pc-hotspot-${version}.apk" > "pc-hotspot-${version}.apk.sha256"
python3 "$project/scripts/create-update-manifest.py" "$project/artifacts/pc-hotspot-${version}.apk"
