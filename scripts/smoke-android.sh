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
    if label.casefold() not in (node.get("text", "") + " " + node.get("content-desc", "")).casefold():
        continue
    if mode == "tap":
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
  return 1
}
tap() {
  wait_for "$1"
  local point
  point=$(visible "$1" tap)
  adb shell input tap $point
}
capture() {
  adb exec-out screencap -p > "$out/$1.png"
}
launch() {
  adb shell am start -W -n "$activity" >/dev/null
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
tap 'Explorar'
tap 'Buscar jogo ou projeto'
adb shell input keyevent 123 67 67 67 67 67
adb shell input text TOTK
adb shell input keyevent 4
tap 'Tears of the Kingdom'
adb shell wm user-rotation lock 1
wait_for 'Tears of the Kingdom'
adb shell input keyevent KEYCODE_TAB KEYCODE_DPAD_DOWN KEYCODE_DPAD_UP
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
fi

adb shell wm size reset
adb shell wm density reset
rm -f "$snapshot"
echo "Android smoke checks complete"
