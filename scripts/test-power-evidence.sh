#!/usr/bin/env bash
# Hermetic test: use fake adb, no actual device, network, or privileged commands.
set -euo pipefail
script="$(cd "$(dirname "$0")" && pwd)/collect-power-evidence.sh"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin" "$temp/out"
cat > "$temp/bin/adb" <<'MOCK'
#!/usr/bin/env bash
if [ "$1" = devices ]; then
  printf 'List of devices attached\nSERIAL-PRIVATE\tdevice\n'
  [ "${MOCK_EXTRA:-0}" = 1 ] && printf 'OTHER-PRIVATE\tunauthorized\n'
  exit 0
fi
[ "$1" = -s ] && [ "$2" = SERIAL-PRIVATE ] && [ "$3" = shell ] && [ "$4" = dumpsys ] || exit 9
case "$5" in
  battery) printf '  AC powered: false\n  USB powered: true\n  level: 76\n  temperature: 318\n  ownerPackageName=com.secret.bank\n';;
  power) printf 'mWakefulness=Asleep packageName=com.secret.bank\n displayGroupId=4 packageName=com.secret.bank\n mWakeLockSummary=0x0 deviceToken=PERSONAL\n mDeviceIdleMode=true\n';;
  display)
    if [ "${MOCK_EMPTY_DISPLAY:-0}" = 1 ]; then
      printf 'DisplayDeviceInfo{"default": state ON}\n'
    else
      printf 'DisplayDeviceInfo{"NeXtep-slot-2": uniqueId="virtual:PERSONAL", state OFF, ownerPackageName=com.secret.bank, groupId=4}\n'
      printf 'DisplayDeviceInfo{"NeXtep-slot-2": state OFF, groupId=4}\n'
      printf 'DisplayDeviceInfo{"Chat with PERSONAL": state ON}\n'
    fi
    ;;
  *) exit 1;;
esac
MOCK
chmod +x "$temp/bin/adb"
PATH="$temp/bin:$PATH" bash "$script" one "$temp/out" >/dev/null
report="$(find "$temp/out" -type f | head -n 1)"
[ -n "$report" ]
grep -q '^wakefulness=Asleep$' "$report"
grep -q '^slot=NeXtep-slot-2 state=OFF$' "$report"
grep -q '^AC powered=false$' "$report"
grep -q '^unassociated_display_group_id=4$' "$report"
grep -q '^slot_record_count=1$' "$report"
[ "$(grep -c '^slot=NeXtep-slot-2 ' "$report")" -eq 1 ] || { echo 'Duplicate slot' >&2; exit 1; }
if grep -Eq 'PERSONAL|com\.secret|SERIAL-PRIVATE|deviceToken=|uniqueId=|ownerPackageName=' "$report"; then
  echo 'Privacy regression: raw sensitive values appeared' >&2;exit 1
fi
[ "$(find "$temp/out" -type f | wc -l | tr -d ' ')" -eq 1 ]
PATH="$temp/bin:$PATH" bash "$script" one "$temp/out" >/dev/null
[ "$(find "$temp/out" -type f | wc -l | tr -d ' ')" -eq 2 ] || { echo 'Reports overwritten' >&2; exit 1; }
if PATH="$temp/bin:$PATH" MOCK_EXTRA=1 bash "$script" two "$temp/out" >/dev/null 2>&1; then
  echo 'Second unauthorized device not rejected' >&2;exit 1
fi
if PATH="$temp/bin:$PATH" bash "$script" '../injection' "$temp/out" >/dev/null 2>&1; then
  echo 'Unsafe label not rejected' >&2;exit 1
fi
[ "$(find "$temp/out" -type f | wc -l | tr -d ' ')" -eq 2 ]
empty_report="$(PATH="$temp/bin:$PATH" MOCK_EMPTY_DISPLAY=1 bash "$script" baseline "$temp/out" | sed 's/^Local report: //')"
grep -q '^slot_record_count=0$' "$empty_report"
grep -q '^slot_record_status=not_visible_or_unavailable$' "$empty_report"
if grep -q '^slot=NeXtep-slot-' "$empty_report"; then echo 'Invented slot' >&2; exit 1; fi
echo 'PASS: sanitized output, deduplicated slots, unknown baseline and safe reports'
