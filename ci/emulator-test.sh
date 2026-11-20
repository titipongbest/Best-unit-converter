#!/usr/bin/env bash
# รันบน emulator: ส่งไฟล์ลับเข้าเครื่อง → ใช้แอปจริง (RealUseTest) → เก็บผล → ไล่หาข้อมูลลับทั้งเครื่อง
set -uo pipefail
OUT=ci-out; source $OUT/vars.sh
note() { local t="$1"; shift; local m; m=$(printf '%s' "$*" | sed ':a;N;$!ba;s/%/%25/g;s/\n/%0A/g'); echo "::notice title=$t::$m"; }
adb wait-for-device
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
# รอจน /sdcard เขียนได้จริง (ที่เก็บไฟล์อาจพร้อมช้ากว่าระบบบูตเสร็จ)
for i in $(seq 1 60); do adb shell "mkdir -p /sdcard/Download && touch /sdcard/Download/.probe && rm /sdcard/Download/.probe" 2>/dev/null && break; sleep 3; done
for f in $OUT/src/*; do
  n=$(basename "$f")
  for i in 1 2 3 4 5; do
    adb push "$f" /sdcard/Download/ && [ "$(adb shell stat -c %s "/sdcard/Download/$n" 2>/dev/null | tr -d '\r')" = "$(stat -c %s "$f")" ] && break
    echo "push $n ไม่สำเร็จ ลองใหม่ ($i)"; sleep 5
  done
done
sleep 5
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell "find /data/media/0 -type f 2>/dev/null" | sort > $OUT/files-before.txt
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# ให้สิทธิ์เข้าถึงไฟล์ทั้งหมดก่อนโปรเซสของแอปเริ่ม (เหมือนผู้ใช้กดอนุญาตในหน้าตั้งค่า)
adb shell appops set com.bestspace.unitconverter MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant com.bestspace.unitconverter android.permission.POST_NOTIFICATIONS 2>/dev/null
SEEN="$(adb shell ls -l /sdcard/Download/ | tr -d '\r')
--- มุมมองของแอป:
$(adb shell run-as com.bestspace.unitconverter ls -l /sdcard/Download/ 2>&1 | tr -d '\r')"
adb logcat -c
adb shell am instrument -w -e M "$M" -e img "$IMG" -e vid "$VID" -e txt "$TXT" -e hImg "$H_IMG" -e hVid "$H_VID" \
  com.bestspace.unitconverter.test/androidx.test.runner.AndroidJUnitRunner > $OUT/instrument.txt 2>&1
adb logcat -d > $OUT/logcat.txt
R=/sdcard/BSResults
adb pull $R/results.txt $OUT/ >/dev/null 2>&1; adb pull $R/mem.bin $OUT/ >/dev/null 2>&1; adb pull $R/keys.txt $OUT/ >/dev/null 2>&1
adb shell rm -rf $R
# ---- ผลทดสอบในแอป
[ -f $OUT/results.txt ] || echo "FAIL ไม่มีไฟล์ผลทดสอบ" > $OUT/results.txt
grep '^FAIL' $OUT/results.txt | head -20 | while IFS= read -r l; do echo "::error title=emulator::$l"; done
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
split -l 18 $OUT/results.txt $OUT/res-
i=1; for f in $OUT/res-*; do note "ผลทดสอบบน emulator API $API (ส่วน $i)" "$(cat $f)"; i=$((i+1)); done
if grep -q "INSTRUMENTATION_FAILED\|Process crashed\|shortMsg" $OUT/instrument.txt; then
  echo "::error title=instrument::$(grep -m5 -E 'shortMsg|longMsg|Process crashed|Exception' $OUT/instrument.txt | tr '\n' ' ' | cut -c1-900)"
fi
grep -q "OK (1 test)" $OUT/instrument.txt || note "instrument output" "$(head -c 2500 $OUT/instrument.txt)"
# ---- หน่วยความจำตอนล็อค
python3 ci/scan.py $OUT > /dev/null
rm -f $OUT/mem.bin
# ---- ไล่หารหัสลับทั้งเครื่อง (ไฟล์ทุกที่ที่อ่านได้ในฐานะ root)
adb shell "grep -rlaF '$M' /data/media /data/data /data/user_de /data/system /data/system_ce /data/system_de /data/misc /data/misc_ce /data/misc_de /data/local /data/anr /data/tombstones /data/vendor 2>/dev/null" \
  | tr -d '\r' | sort -u > $OUT/grep.txt
EXPECTED=$(grep -c "/BSExport/" $OUT/grep.txt)
grep -v "/BSExport/" $OUT/grep.txt > $OUT/found.txt
# แยกว่าเจออะไรในแต่ละไฟล์: ชื่อต้นฉบับที่ไม่ได้ส่งออก (clip/note) / เนื้อหา / ชื่อรูปที่ส่งออก (ตั้งใจ)
: > $OUT/leaks.txt; : > $OUT/system.txt
while IFS= read -r f; do
  w=""
  for k in "secret_clip_$M" "secret_note_$M" "secret_photo_$M" "CONTENT$M" "SECOND$M" "EDIT$M" "NEW$M" "JPGMARK$M" "VIDMETA$M"; do
    adb shell "grep -qaF '$k' '$f'" && w="$w ${k%%_$M}${k%%$M}"
  done
  w=$(echo "$w" | sed "s/$M//g")
  case "$f" in
    /data/data/com.google.android.providers.media.module/*|/data/user/0/com.google.android.providers.media.module/*) echo "$f :$w" >> $OUT/system.txt ;;
    *) echo "$f :$w" >> $OUT/leaks.txt ;;
  esac
done < $OUT/found.txt

[ -s $OUT/leaks.txt ] && echo "::error title=leak::พบข้อมูลลับนอกตู้ $(wc -l < $OUT/leaks.txt) ไฟล์"
[ -s $OUT/system.txt ] && echo "::warning title=system::ฐานข้อมูลสื่อของระบบยังมีร่องรอย: $(cat $OUT/system.txt | tr '\n' ' ')"
LC=$(grep -F "$M" $OUT/logcat.txt | grep -v "TestRunner\|MonitoringInstr" | cut -c1-220 | head -12)
note "logcat: ข้อผิดพลาดของแอป" "$(grep -E " E (AndroidRuntime|ExoPlayer|MediaCodec|ExoPlayerImpl|Compose|bestspace)|FATAL|PlaybackException" $OUT/logcat.txt | cut -c1-200 | head -25)"
adb shell "find /data/media/0 -type f 2>/dev/null" | sort > $OUT/files-after.txt
note "ข้อมูลหลงเหลือ" "== หน่วยความจำของแอปตอนล็อค
$(cat $OUT/memscan.txt)
== ไล่หารหัสลับทั้งเครื่อง: ส่งออกเอง(ตั้งใจ) $EXPECTED ไฟล์ • ไฟล์ของแอป/ที่อื่น $(wc -l < $OUT/leaks.txt) ไฟล์
$(head -30 $OUT/leaks.txt)
== ฐานข้อมูลสื่อของระบบ Android (MediaProvider – ต้องใช้ root ถึงอ่านได้)
$(cat $OUT/system.txt)
== logcat ที่มีรหัสลับ (ไม่นับตัวทดสอบ)
${LC:-ไม่มี}
== ไฟล์ใหม่ใน /sdcard ระหว่างใช้งาน
$(comm -13 $OUT/files-before.txt $OUT/files-after.txt | head -40)
== ไฟล์ต้นฉบับก่อนทดสอบ
$SEEN"
grep -q '^FAIL' $OUT/results.txt && exit 1
[ -s $OUT/leaks.txt ] && exit 1
grep -q "OK (1 test)" $OUT/instrument.txt || { echo "::error title=instrument::$(tail -c 900 $OUT/instrument.txt | tr '\n' ' ')"; exit 1; }
exit 0
