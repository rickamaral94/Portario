#!/usr/bin/env bash
set -euo pipefail

apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
out=${2:-screenshots}
mkdir -p "$out"
out=$(cd "$out" && pwd)
package=br.com.amaral.portario
activity="$package/.MainActivity"
snapshot="$out/.view.xml"

uia() {
  adb shell uiautomator dump /sdcard/portario-view.xml >/dev/null
  adb shell cat /sdcard/portario-view.xml > "$snapshot"
}
visible() {
  local label=$1 mode=${2:-visible}
  python3 - "$snapshot" "$label" "$mode" <<'PY'
import re,sys
from xml.etree import ElementTree
tree, label, mode = sys.argv[1:]
root = ElementTree.parse(tree).getroot()
for node in root.iter("node"):
    fields = [node.get("text", "").casefold(), node.get("content-desc", "").casefold()]
    matches = label.casefold() in fields if mode == "exact" else any(label.casefold() in field for field in fields)
    if not matches:
        continue
    if mode in ("tap", "exact"):
        x, y, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        print((x + x2)//2, (y + y2)//2)
    break
else:
    sys.exit(1)
PY
}
wait_for() {
  for attempt in $(seq 1 15); do
    if uia && visible "$1"; then return; fi
    sleep 2
  done
  printf 'Did not find %s\n' "$1" >&2
  if [[ -s "$snapshot" ]]; then
    python3 - "$snapshot" <<'PY'
import sys
import xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).iter("node"):
    label = node.get("text") or node.get("content-desc")
    if label:
        print(node.get("package"), label)
PY
  fi
  adb logcat -d -s AndroidRuntime:E | tail -40 | grep -E 'FATAL|Portario|portario|Exception|Error' || true
  return 1
}
tap() {
  wait_for "$1"
  local point
  point=$(visible "$1" tap)
  adb shell input tap $point
}
tap_exact() {
  local point
  for attempt in $(seq 1 12); do
    if uia && point=$(visible "$1" exact); then
      adb shell input tap $point
      return
    fi
    sleep 1
  done
  printf 'Did not find exact control %s\n' "$1" >&2
  return 1
}
tap_in_detail() {
  local point
  for attempt in $(seq 1 6); do
    if uia && point=$(visible "$1" exact); then
      adb shell input tap $point
      return
    fi
    adb shell input swipe 360 1210 360 680 360
  done
  printf 'Did not find detail control %s\n' "$1" >&2
  return 1
}
capture() {
  adb exec-out screencap -p > "$out/$1.png"
}
launch() {
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  adb shell am start -W -n "$activity"
}
share() {
  adb shell am start -W -n "$activity" -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "$1" >/dev/null
}
restore() {
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
  adb shell wm user-rotation free >/dev/null 2>&1 || true
}
trap restore EXIT

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell wm user-rotation lock 0
adb shell wm size 720x1520
adb shell wm density 300

adb install -r "$apk"
adb shell pm clear "$package" >/dev/null
adb shell cmd locale set-app-locales "$package" --user 0 --locales pt-BR >/dev/null
adb shell svc wifi disable
adb shell svc data disable >/dev/null 2>&1 || true
echo 'Opening offline catalog'
launch
wait_for 'Breath of the Wild'
capture 01-explorar
tap 'Buscar jogo ou projeto'
adb shell input text NFSU2
adb shell input keyevent 4
wait_for 'Need for Speed: Underground 2'
tap 'Need for Speed: Underground 2'
wait_for 'Arquivos do jogo'
capture 02-ficha
adb shell input keyevent 4
tap 'Adicionar'
wait_for 'Link do GitHub'
capture 03-adicionar
echo 'Preparing landscape capture from a fresh catalog'
adb shell pm clear "$package" >/dev/null
adb shell cmd locale set-app-locales "$package" --user 0 --locales pt-BR >/dev/null
launch
wait_for 'Breath of the Wild'
tap 'Tears of the Kingdom'
wait_for 'Voltar'
adb shell wm user-rotation lock 1
wait_for 'Tears of the Kingdom'
adb shell input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
sleep 1
capture 04-paisagem
adb shell wm user-rotation lock 0
adb shell svc wifi enable
adb shell svc data enable >/dev/null 2>&1 || true

if [[ ${PORTARIO_NETWORK_SMOKE:-0} == 1 ]]; then
  share https://github.com/octocat/Hello-World/releases
  wait_for 'Sem APK publicado'
  tap 'Acompanhar'
  wait_for 'Hello-World'
  share https://github.com/octocat/Hello-World
  tap 'Acompanhar'
  wait_for '1 acompanhados'

  share https://github.com/diasurgical/devilutionx/releases
  wait_for 'devilutionx-android.apk'
  tap 'Acompanhar'
  wait_for '2 acompanhados'
  tap 'Buscar atualizações'
  wait_for 'Verificação da biblioteca concluída'

  tap 'DevilutionX'
  tap_in_detail 'Baixar APK'
  tap_exact 'Baixar'
  wait_for 'devilutionx-android.apk'
  if point=$(visible 'Cancelar' exact); then
    adb shell input tap $point
    echo 'Download cancellation exercised while active'
  else
    tap_in_detail 'Remover download'
    echo 'Download finished before cancellation and was removed from the library'
  fi
  tap_in_detail 'Baixar APK'
  tap_exact 'Baixar'
  wait_for 'Instalar'

  # Install a disposable fixture bearing the same package ID and a different signer.
  fixture=$(mktemp -d)
  cat > "$fixture/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="org.diasurgical.devilutionx" android:versionCode="1">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
    <application android:hasCode="false" android:label="Signature test fixture">
        <activity android:name="android.app.Activity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
XML
  build_tools=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
  "$build_tools/aapt2" link -I "$ANDROID_HOME/platforms/android-36/android.jar" \
    --manifest "$fixture/AndroidManifest.xml" -o "$fixture/unsigned.apk"
  keytool -genkeypair -keystore "$fixture/fixture.jks" -storepass android -keypass android \
    -alias fixture -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Portario Test Fixture' -noprompt
  "$build_tools/apksigner" sign --ks "$fixture/fixture.jks" --ks-pass pass:android \
    --out "$fixture/signed.apk" "$fixture/unsigned.apk"
  adb install "$fixture/signed.apk"
  adb shell appops set "$package" REQUEST_INSTALL_PACKAGES allow
  tap_in_detail 'Instalar'
  wait_for 'A assinatura difere'
  adb shell dumpsys package org.diasurgical.devilutionx | grep 'versionCode=1 '
  echo 'Signature mismatch rejected and the installed fixture was preserved'
  adb uninstall org.diasurgical.devilutionx
  rm -rf "$fixture"

  tap_in_detail 'Instalar'
  tap_exact 'Install'
  wait_for 'Instalado'
  adb shell pm path org.diasurgical.devilutionx
  tap_in_detail 'Abrir'
  launched=false
  for attempt in $(seq 1 12); do
    if adb shell dumpsys activity activities | grep -Ei '(topResumedActivity|mResumedActivity).*org.diasurgical.devilutionx' ||
      adb shell dumpsys window | grep -E 'mCurrentFocus=.*org.diasurgical.devilutionx'; then
      launched=true
      break
    fi
    sleep 1
  done
  if [[ "$launched" != true ]]; then
    adb shell dumpsys activity activities | grep -Ei '(resumedActivity|org.diasurgical.devilutionx)' | head -12 || true
    printf 'Portário requested launch but the target app did not reach the foreground\n' >&2
    exit 1
  fi
fi

adb shell wm size reset
adb shell wm density reset
rm -f "$snapshot"
echo "Android smoke checks complete"
