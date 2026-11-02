#!/bin/sh
# Fake adb for tests. FAKE_FG = foreground package. Logs args to $FAKE_ADB_LOG.
echo "$*" >> "$FAKE_ADB_LOG"
case "$*" in
  "devices -l") printf 'List of devices attached\n10.0.0.5:5555 device product:x model:F1 device:f1\n' ;;
  "-s 10.0.0.5:5555 get-state") echo device ;;
  *"resolve-activity"*) printf 'priority=0\ncom.example.launcher/.Home\n' ;;
  *"dumpsys activity activities"*) echo "  topResumedActivity=ActivityRecord{1 u0 ${FAKE_FG:-com.android.settings}/.Main t1}" ;;
  *"input "*) : ;;
  *"monkey "*) echo "Events injected: 1" ;;
  *"pm list packages"*) printf 'package:com.bank\npackage:com.android.chrome\npackage:com.wadjinny.adbwarden\n' ;;
  *"shell ls"*) echo "Download" ;;
  *) echo "unexpected: $*" >&2; exit 1 ;;
esac
