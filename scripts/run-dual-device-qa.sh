#!/usr/bin/env bash
set -euo pipefail

PUB_SERIAL="${PUB_SERIAL:-R5CR40E11PP}"
SUB_SERIAL="${SUB_SERIAL:-100.108.44.112:5555}"
APP_PACKAGE="${APP_PACKAGE:-com.mandonothing.ops.rnsqa}"
TEST_PACKAGE="${TEST_PACKAGE:-${APP_PACKAGE}.test}"
DISCOVERY_TIMEOUT_MS="${DISCOVERY_TIMEOUT_MS:-30000}"
EXCHANGE_TIMEOUT_MS="${EXCHANGE_TIMEOUT_MS:-35000}"
ENABLE_BLE_FALLBACK="${ENABLE_BLE_FALLBACK:-true}"

for serial in "$PUB_SERIAL" "$SUB_SERIAL"; do
  for perm in \
    android.permission.NEARBY_WIFI_DEVICES \
    android.permission.BLUETOOTH_SCAN \
    android.permission.BLUETOOTH_CONNECT \
    android.permission.BLUETOOTH_ADVERTISE \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.RECORD_AUDIO \
    android.permission.POST_NOTIFICATIONS; do
    adb -s "$serial" shell pm grant "$APP_PACKAGE" "$perm" 2>/dev/null || true
  done
  adb -s "$serial" logcat -c 2>/dev/null || true
done

PUB_LOG="$(mktemp -t dual_pub.XXXXXX.log)"
SUB_LOG="$(mktemp -t dual_sub.XXXXXX.log)"
trap 'rm -f "$PUB_LOG" "$SUB_LOG"' EXIT

set +e
adb -s "$PUB_SERIAL" shell am instrument -w \
  -e class com.mandonothing.ops.comm.rns.DualDeviceRnsTest \
  -e role publisher \
  -e discoveryTimeoutMs "$DISCOVERY_TIMEOUT_MS" \
  -e exchangeTimeoutMs "$EXCHANGE_TIMEOUT_MS" \
  -e enableBleFallback "$ENABLE_BLE_FALLBACK" \
  "${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner" >"$PUB_LOG" 2>&1 &
PUB_PID=$!

adb -s "$SUB_SERIAL" shell am instrument -w \
  -e class com.mandonothing.ops.comm.rns.DualDeviceRnsTest \
  -e role subscriber \
  -e discoveryTimeoutMs "$DISCOVERY_TIMEOUT_MS" \
  -e exchangeTimeoutMs "$EXCHANGE_TIMEOUT_MS" \
  -e enableBleFallback "$ENABLE_BLE_FALLBACK" \
  "${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner" >"$SUB_LOG" 2>&1 &
SUB_PID=$!

wait "$PUB_PID"
PUB_EXIT=$?
wait "$SUB_PID"
SUB_EXIT=$?
set -e

echo "=== PUBLISHER ($PUB_SERIAL) OUTPUT (exit=$PUB_EXIT) ==="
cat "$PUB_LOG"
echo "=== PUBLISHER LOGCAT (DualDeviceRnsTest) ==="
adb -s "$PUB_SERIAL" logcat -d -s DualDeviceRnsTest:I 2>/dev/null || true

echo "=== SUBSCRIBER ($SUB_SERIAL) OUTPUT (exit=$SUB_EXIT) ==="
cat "$SUB_LOG"
echo "=== SUBSCRIBER LOGCAT (DualDeviceRnsTest) ==="
adb -s "$SUB_SERIAL" logcat -d -s DualDeviceRnsTest:I 2>/dev/null || true

if [[ "$PUB_EXIT" -ne 0 || "$SUB_EXIT" -ne 0 ]] || ! grep -q '^OK (' "$PUB_LOG" || ! grep -q '^OK (' "$SUB_LOG"; then
  exit 1
fi
