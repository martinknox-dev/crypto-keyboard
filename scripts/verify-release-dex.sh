#!/usr/bin/env bash
# Test: a release APK must keep ML Kit's reflection surface.
#
# R8 full mode (default with AGP 9) strips the BarcodeScanning entry class and
# the no-arg constructors of the firebase-components DI registrars that ML Kit
# instantiates reflectively — the app then NPEs the moment the QR scanner calls
# BarcodeScanning.getClient(). Debug builds are unaffected, so this is the only
# check that catches a regression (AGP/R8/ML Kit upgrades can reintroduce it).
#
# Known issue: https://github.com/googlesamples/mlkit/issues/1018
#
# usage: scripts/verify-release-dex.sh <apk>
set -u
APK="${1:?usage: $0 <apk>}"

# Locate dexdump: explicit env, newest build-tools, or PATH.
DEXDUMP="${DEXDUMP:-}"
if [ -z "$DEXDUMP" ] && [ -n "${ANDROID_HOME:-}" ]; then
  DEXDUMP=$(find "$ANDROID_HOME/build-tools" -name dexdump -type f 2>/dev/null | sort -V | tail -1)
fi
: "${DEXDUMP:=dexdump}"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

unzip -qo "$APK" 'classes*.dex' -d "$WORK" || { echo "FAIL: cannot extract dex from $APK"; exit 1; }

: > "$WORK/hdr.txt"
for d in "$WORK"/classes*.dex; do
  "$DEXDUMP" "$d" >> "$WORK/hdr.txt" 2>/dev/null
done
[ -s "$WORK/hdr.txt" ] || { echo "FAIL: dexdump produced no output (DEXDUMP=$DEXDUMP)"; exit 1; }

fail=0

CLASSES="
com.google.mlkit.vision.barcode.BarcodeScanning
com.google.mlkit.vision.barcode.internal.BarcodeRegistrar
com.google.mlkit.vision.common.internal.VisionCommonRegistrar
com.google.mlkit.common.internal.CommonComponentRegistrar
com.google.mlkit.common.internal.MlKitComponentDiscoveryService
"
# 1. Entry class, DI registrars, and the manifest discovery service must exist as classes.
for cls in $CLASSES; do
  desc="L${cls//./\/};"
  if grep -qF "Class descriptor  : '$desc'" "$WORK/hdr.txt"; then
    echo "OK   class exists: $cls"
  else
    echo "FAIL class stripped: $cls"
    fail=1
  fi
done

# 2. Registrars must keep a constructor (firebase-components instantiates them via reflection).
for cls in com.google.mlkit.vision.barcode.internal.BarcodeRegistrar \
           com.google.mlkit.vision.common.internal.VisionCommonRegistrar \
           com.google.mlkit.common.internal.CommonComponentRegistrar; do
  desc="L${cls//./\/};"
  section=$(awk -v d="Class descriptor  : '$desc'" '
    $0 ~ d {insec=1}
    insec && /Class descriptor/ && $0 !~ d {exit}
    insec {print}
  ' "$WORK/hdr.txt")
  if [ -z "$section" ]; then
    echo "FAIL no section: $cls"
    fail=1
  elif printf '%s\n' "$section" | grep -qF "name          : '<init>'"; then
    echo "OK   constructor kept: $cls.<init>"
  else
    echo "FAIL constructor stripped: $cls.<init>"
    fail=1
  fi
done

if [ "$fail" = 0 ]; then
  echo "PASS: ML Kit reflection surface intact in $(basename "$APK")"
else
  echo "DEFECT: $APK lacks ML Kit reflective surface"
fi
exit "$fail"
