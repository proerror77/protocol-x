#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVICE_SERIAL="${S21_SERIAL:-R5CR40E11PP}"
PYTHON_BIN="${PROTOCOLX_RNS_PYTHON:-/Users/proerror/Documents/toc/protocol-x-rns-01/.venv-rns-poc/bin/python}"
PORT="${S21_RNS_PORT:-17421}"
WRONG_PORT="${S21_RNS_WRONG_PORT:-17422}"
IFAC_NET="${S21_IFAC_NET:-protocolx.s21.qa}"
IFAC_PASS="${S21_IFAC_PASS:-s21-qa-secret-v1}"
TEST_PACKAGE="${S21_TEST_PACKAGE:-com.mandonothing.ops.rnsqa.test}"

PEER_LOG="$(mktemp -t s21_rns_peer.XXXXXX.log)"
PEER_PID=""

cleanup() {
  if [[ -n "$PEER_PID" ]] && kill -0 "$PEER_PID" 2>/dev/null; then
    kill "$PEER_PID" 2>/dev/null || true
    wait "$PEER_PID" 2>/dev/null || true
  fi
  adb -s "$DEVICE_SERIAL" reverse --remove "tcp:${PORT}" 2>/dev/null || true
  adb -s "$DEVICE_SERIAL" reverse --remove "tcp:${WRONG_PORT}" 2>/dev/null || true
}
trap cleanup EXIT

"$PYTHON_BIN" -u "$ROOT_DIR/scripts/s21_rns_peer.py" \
  --port "$PORT" \
  --wrong-port "$WRONG_PORT" \
  --ifac-netname "$IFAC_NET" \
  --ifac-passphrase "$IFAC_PASS" \
  --timeout-secs 65 >"$PEER_LOG" 2>&1 &
PEER_PID=$!

READY_LINE=""
for _ in $(seq 1 100); do
  if grep -q '"event": "ready"' "$PEER_LOG" 2>/dev/null; then
    READY_LINE="$(grep '"event": "ready"' "$PEER_LOG" | head -n 1)"
    break
  fi
  if ! kill -0 "$PEER_PID" 2>/dev/null; then
    echo "Python RNS peer exited early:"
    cat "$PEER_LOG"
    exit 1
  fi
  sleep 0.1
done

if [[ -z "$READY_LINE" ]]; then
  echo "Timed out waiting for Python RNS peer ready:"
  cat "$PEER_LOG"
  exit 1
fi

eval "$("$PYTHON_BIN" -c '
import json, sys
d = json.loads(sys.argv[1])
print("PY_LXMF_DEST=" + d["py_lxmf_dest_hex"])
print("PY_PTT_DEST=" + d["py_ptt_dest_hex"])
print("ROGUE_LXMF_DEST=" + d["rogue_lxmf_dest_hex"])
print("ROGUE_PRIMARY_DEST=" + d["rogue_primary_dest_hex"])
' "$READY_LINE")"

adb -s "$DEVICE_SERIAL" reverse "tcp:${PORT}" "tcp:${PORT}"
adb -s "$DEVICE_SERIAL" reverse "tcp:${WRONG_PORT}" "tcp:${WRONG_PORT}"

adb -s "$DEVICE_SERIAL" shell am instrument -w \
  -e class com.mandonothing.ops.comm.rns.S21RealRnsDeviceTest \
  -e rnsPeerHost 127.0.0.1 \
  -e rnsPeerPort "$PORT" \
  -e rnsWrongPort "$WRONG_PORT" \
  -e rnsIfacNet "$IFAC_NET" \
  -e rnsIfacPass "$IFAC_PASS" \
  -e pyLxmfDest "$PY_LXMF_DEST" \
  -e pyPttDest "$PY_PTT_DEST" \
  -e rogueLxmfDest "$ROGUE_LXMF_DEST" \
  -e roguePrimaryDest "$ROGUE_PRIMARY_DEST" \
  "${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"

wait "$PEER_PID"
PEER_EXIT=$?
PEER_PID=""
echo "=== Python Peer Log ==="
cat "$PEER_LOG"
rm -f "$PEER_LOG"
exit "$PEER_EXIT"
