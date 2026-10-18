#!/usr/bin/env bash
# ASEM Mods APK build (javac -> d8 -> aapt2 -> zipalign -> apksigner). Usage: bash build.sh
# Signing: put your keystore + passwords in signing.env (not in git), e.g.
#   KS=asem.keystore
#   KS_PASS=...
# Paths below are for Windows + Git Bash; set ANDROID_SDK / JAVA_HOME to match your PC.
set -e
M=$(cd "$(dirname "$0")" && pwd -W); cd "$M"
[ -f signing.env ] && . ./signing.env
: "${KS:?set KS (keystore path) in signing.env}" "${KS_PASS:?set KS_PASS in signing.env}"
SDK=${ANDROID_SDK:-C:/Users/$USERNAME/Android/Sdk}
AJ="$SDK/platforms/android-35/android.jar"; BT="$SDK/build-tools/35.0.0"
rm -rf build && mkdir -p build/stubs build/classes build/dex build/res
javac -nowarn -source 17 -target 17 -cp "$AJ" -d build/stubs $(find stubs -name "*.java") 2>&1 | { grep -E "error" || true; }
javac -nowarn -source 17 -target 17 -cp "$AJ;$M/build/stubs" -d build/classes src/com/asem/mods/*.java 2>&1 | { grep -E "error|\^" || true; }
ls build/classes/com/asem/mods/Hook.class >/dev/null
cmd //c "$BT/d8.bat" --release --min-api 34 --lib "$AJ" --output build/dex $(find build/classes -name "*.class" | tr '\n' ' ')
"$BT/aapt2.exe" compile --dir res -o build/res/compiled.zip
"$BT/aapt2.exe" link -I "$AJ" --manifest AndroidManifest.xml -A assets -o build/base.apk build/res/compiled.zip
python -I -c "
import sys, zipfile
src, dex, out = sys.argv[1:]
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(out, 'w') as zo:
    [zo.writestr(it, zi.read(it.filename)) for it in zi.infolist()]
    zo.write(dex, 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)" build/base.apk build/dex/classes.dex build/unsigned.apk
"$BT/zipalign.exe" -f -p 4 build/unsigned.apk build/aligned.apk
cmd //c "$BT/apksigner.bat" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:${KEY_PASS:-$KS_PASS}" --out build/ASEM_Mods.apk build/aligned.apk 2>/dev/null
cmd //c "$BT/apksigner.bat" verify build/ASEM_Mods.apk 2>/dev/null && echo "BUILD OK: $("$BT/aapt2.exe" dump badging build/ASEM_Mods.apk 2>/dev/null | head -1 | cut -d' ' -f2-4)"
