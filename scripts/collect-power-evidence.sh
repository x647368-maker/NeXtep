#!/usr/bin/env bash
# Read-only local power/display evidence. Never root, install, upload or reboot.
set -euo pipefail
label="${1:-snapshot}"
folder="${2:-./power-evidence}"
case "$label" in *[!a-zA-Z0-9_-]* | "") echo "Invalid label" >&2; exit 2;; esac
command -v adb >/dev/null 2>&1 || { echo "adb not found" >&2; exit 2; }
n="$(adb devices | awk '$2=="device" {n++} END {print n+0}')"
[ "$n" -eq 1 ] || { echo "Connect exactly one authorized Android device" >&2; exit 2; }
mkdir -p "$folder"
file="$folder/$(date '+%Y%m%d-%H%M%S')-${label}.txt"
{
  printf "Read-only wakefulness snapshot. USB/ADB may change idle state.\n"
  date '+%Y-%m-%d %H:%M:%S %z'
  printf "\n=== Battery ===\n"
  adb shell dumpsys battery | grep -Ei "^[[:space:]]*(level|status|plugged|voltage|temperature|AC powered|USB powered):" || true
  printf "\n=== Power groups / wakefulness ===\n"
  adb shell dumpsys power | grep -Ei "mWakefulness=|Wakefulness=|PowerGroup|mWakeLockSummary=|mUserActivitySummary=|mLastSleepTime=|mLastWakeTime=|mHolding(WakeLock|Display)SuspendBlocker=|mDeviceIdleMode=" | sed -E 's/(packageName=|ownerPackage=|uniqueId=)[^ ,}]+/\1[REDACTED]/g' || true
  printf "\n=== Display groups / states ===\n"
  adb shell dumpsys display | grep -Ei "DisplayGroup|groupId=|displayId=|DisplayDeviceInfo|state=OFF|state=ON|OWN_DISPLAY_GROUP|mDisplayGroups" | sed -E 's/(packageName=|ownerPackage=|uniqueId=)[^ ,}]+/\1[REDACTED]/g' || true
  printf "\n=== Module flags candidates (not proof of selected flag) ===\n"
  adb logcat -d -v brief -s NeXtep:I "*:S" | grep -E "feature=virtual_display_flags .*message=Resolved candidates" || true
  printf "\nEND. Review/redact before sending; no upload performed.\n"
} > "$file"
echo "Saved $file"
