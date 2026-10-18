#!/usr/bin/env bash
# Flashable zip (recovery or Magisk app) from build/ASEM_Mods.apk. Usage: bash make_zip.sh
set -e
cd "$(dirname "$0")"
V=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
C=$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
OUT=build/ASEM_Mods_v${V}_X6855.zip
python -I - "$OUT" "$V" "$C" <<'PY'
import sys, re, zipfile
out, ver, code = sys.argv[1:]
prop = open('flashable/module.prop', encoding='utf-8').read()
prop = re.sub(r'(?m)^version=.*$', 'version=v' + ver, prop)
prop = re.sub(r'(?m)^versionCode=.*$', 'versionCode=' + code, prop)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in ['META-INF/com/google/android/update-binary', 'META-INF/com/google/android/updater-script']:
        z.write('flashable/' + f, f)
    z.writestr('module.prop', prop)            # root copy: the Magisk app checks it
    z.writestr('module/module.prop', prop)
    z.write('build/ASEM_Mods.apk', 'module/system/app/ASEMMods/ASEMMods.apk')
print('zip:', out)
PY
