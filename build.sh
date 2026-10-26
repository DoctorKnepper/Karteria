#!/bin/bash
# Builds a signed Karteria APK into ./dist.
#
# Needs: JDK 17+, Android SDK (platform android-36, build-tools 36.0.0).
# Signing: set KARTERIA_KEYSTORE, KARTERIA_KS_PASS and KARTERIA_KS_ALIAS,
# or the script creates a throwaway debug key in ./build (fine for testing,
# but updates must always be signed with the same key).
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
BT="$SDK/build-tools/36.0.0"
PLAT="$SDK/platforms/android-36/android.jar"
[ -f "$PLAT" ] || { echo "Android platform 36 not found under $SDK (set ANDROID_HOME)"; exit 1; }
VER=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)

rm -rf build && mkdir -p build/res build/classes build/dex build/assets dist
cp assets/index.html build/assets/   # only the app page is packaged

"$BT/aapt2" compile --dir res -o build/res/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLAT" --manifest AndroidManifest.xml \
  -A build/assets build/res/res.zip --java build/gen --auto-add-overlay
javac -source 11 -target 11 -classpath "$PLAT" -d build/classes \
  build/gen/com/karteria/app/R.java src/com/karteria/app/*.java 2>&1 | grep -iv "warning\|note:\|JAVA_TOOL" || true
"$BT/d8" --lib "$PLAT" --min-api 26 --output build/dex $(find build/classes -name '*.class') 2>&1 | grep -v JAVA_TOOL || true
cp build/base.apk build/unsigned.apk && (cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

KS="${KARTERIA_KEYSTORE:-build/debug.keystore}"
PASS="${KARTERIA_KS_PASS:-android}"
ALIAS="${KARTERIA_KS_ALIAS:-karteria}"
if [ ! -f "$KS" ]; then
  echo "No KARTERIA_KEYSTORE set; creating a throwaway debug key."
  keytool -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Karteria Debug" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --ks-key-alias "$ALIAS" \
  --out "dist/Karteria-$VER.apk" build/aligned.apk 2>&1 | grep -v JAVA_TOOL || true
rm -f "dist/Karteria-$VER.apk.idsig"
"$BT/apksigner" verify "dist/Karteria-$VER.apk" 2>&1 | grep -v JAVA_TOOL || echo "Signed: dist/Karteria-$VER.apk"
