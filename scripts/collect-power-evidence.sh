#!/usr/bin/env bash
# Read-only, privacy-minimized Android power/display snapshot.
# Does not measure energy, keep devices awake, install or upload anything.
set -euo pipefail
umask 077
label="${1:-snapshot}"
folder="${2:-./power-evidence}"
case "$label" in
  *[!a-zA-Z0-9_-]* | "") echo 'Label must contain only letters, digits, _ or -.' >&2; exit 2 ;;
esac
command -v adb >/dev/null 2>&1 || { echo 'ADB not found; install Android platform-tools first.' >&2; exit 2; }
# Refuse multiple connections, including offline/unauthorized devices.
serials="$(adb devices | awk 'NR > 1 && NF >= 2 && $1 !~ /^\*/ {print $1 ":" $2}')"
count="$(printf '%s\n' "$serials" | grep -c . || true)"
if [ "$count" -ne 1 ] || [ "${serials##*:}" != 'device' ]; then
  echo "Exactly one authorized device required; detected $count entries." >&2
  exit 2
fi
serial="${serials%:device}"
mkdir -p "$folder"
file="$(mktemp "$folder/$(date '+%Y%m%d-%H%M%S')-${label}.XXXXXX")"
battery=''
power=''
display=''
# Raw dumpsys is held only in process memory, never copied into the report.
if battery="$(adb -s "$serial" shell dumpsys battery 2>/dev/null)"; then battery_ok=yes; else battery_ok=no; fi
if power="$(adb -s "$serial" shell dumpsys power 2>/dev/null)"; then power_ok=yes; else power_ok=no; fi
if display="$(adb -s "$serial" shell dumpsys display 2>/dev/null)"; then display_ok=yes; else display_ok=no; fi
{
  printf 'READ-ONLY POWER/DISPLAY SNAPSHOT (NOT A CONSUMPTION MEASUREMENT)\n'
  printf 'Local time: %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
  printf 'USB/ADB may change wakefulness; no persistent sampling or device modification.\n'
  printf '\n[BATTERY] status=%s\n' "$battery_ok"
  if [ "$battery_ok" = yes ]; then
    printf '%s\n' "$battery" | awk '
      /^[[:space:]]*(level|status|plugged|voltage|temperature|AC powered|USB powered|Wireless powered):[[:space:]]*([0-9]+|true|false)([[:space:]]*)$/ {
        split($0,a,":");key=a[1];val=a[2];gsub(/^[[:space:]]+|[[:space:]]+$/, "", key);gsub(/[[:space:]]/, "", val);
        if (val ~ /^([0-9]+|true|false)$/) print key "=" val
      }'
  fi
  printf '\n[POWER] status=%s\n' "$power_ok"
  if [ "$power_ok" = yes ]; then
    # Emit whitelisted scalar tokens only, never complete dumpsys lines.
    printf '%s\n' "$power" | awk '
      {
        s=$0
        if (match(s,/mWakefulness[[:space:]]*=[[:space:]]*[A-Za-z_]+/)) {
          t=substr(s,RSTART,RLENGTH);split(t,a,"=");gsub(/[[:space:]]/,"",a[2]);
          if (a[2] ~ /^(Awake|Asleep|Dozing|Dreaming|AWAKE|ASLEEP|DOZING|DREAMING|WAKEFULNESS_AWAKE|WAKEFULNESS_ASLEEP|WAKEFULNESS_DOZING|WAKEFULNESS_DREAMING)$/) print "wakefulness=" a[2]
        }
        if (match(s,/(PowerGroupId|displayGroupId)[[:space:]]*[=:][[:space:]]*[0-9]+/)) {
          t=substr(s,RSTART,RLENGTH);gsub(/.*[=:][[:space:]]*/,"",t);print "power_group_id=" t
        }
        if (match(s,/mWakeLockSummary[[:space:]]*=[[:space:]]*(0x[0-9a-fA-F]+|[0-9]+)/)) {
          t=substr(s,RSTART,RLENGTH);sub(/.*=/,"",t);gsub(/[[:space:]]/,"",t);print "wakelock_summary=" t
        }
        if (match(s,/mDeviceIdleMode[[:space:]]*=[[:space:]]*(true|false)/)) {
          t=substr(s,RSTART,RLENGTH);sub(/.*=/,"",t);gsub(/[[:space:]]/,"",t);print "device_idle=" t
        }
      }'
  fi
  printf '\n[DISPLAY] status=%s\n' "$display_ok"
  if [ "$display_ok" = yes ]; then
    # Dumpsys repeats devices in sections; count unique NeXtep slot names.
    printf '%s\n' "$display" | awk '
      {
        s=$0
        if (match(s,/NeXtep-slot-[0-3]/)) {
          slot=substr(s,RSTART,RLENGTH)
          if (!(slot in seen)) {seen[slot]=1;states[slot]="unavailable";count++}
          if (match(s,/state[ =:]+(OFF|ON|DOZE|DOZE_SUSPEND|UNKNOWN)/)) {
            q=substr(s,RSTART,RLENGTH);sub(/.*[ =:]+/,"",q);states[slot]=q
          }
        }
        if (match(s,/(groupId|mGroupId)[[:space:]]*=[[:space:]]*[0-9]+/)) {
          q=substr(s,RSTART,RLENGTH);sub(/.*=/,"",q);gsub(/[[:space:]]/,"",q);groups[q]=1
        }
      }
      END {
        for (i=0;i<4;i++) {slot="NeXtep-slot-" i;if (slot in seen) print "slot=" slot " state=" states[slot]}
        for (g in groups) print "unassociated_display_group_id=" g
        print "slot_record_count=" count+0
        if (count==0) print "slot_record_status=not_visible_or_unavailable"
      }'
  fi
  printf '\nSTATE_OFF is not proof of deep sleep; missing data must remain unknown.\n'
  printf 'Review before sharing; no raw logs, personal device IDs, package names or full dumpsys exported.\n'
} > "$file"
printf 'Local report: %s\n' "$file"
