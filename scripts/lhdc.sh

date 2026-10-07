#!/bin/bash
# LHDC Probe CLI 封装：lhdc.sh <命令> [参数]
# 例: lhdc.sh status | lhdc.sh devices | lhdc.sh fix | lhdc.sh watch arm_acl
# 参数含分号等特殊字符时会被加引号保护
ADB=~/Library/Android/sdk/platform-tools/adb
CMD="${1:-help}"
ARG="$2"
if [ -n "$ARG" ]; then
  $ADB shell content call --uri content://com.lhdcprobe.cli --method "$CMD" --arg "'$ARG'"
else
  $ADB shell content call --uri content://com.lhdcprobe.cli --method "$CMD"
fi
