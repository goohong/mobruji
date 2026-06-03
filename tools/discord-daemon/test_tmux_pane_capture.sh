#!/usr/bin/env bash
# tmux-pane-capture.sh 검증 매트릭스 (discord-driven PR C, 이슈 #341).
# 사용: bash tools/discord-daemon/test_tmux_pane_capture.sh
# 모든 케이스 통과 시 exit 0, 실패 시 exit 1 + 어느 케이스인지 출력.
#
# tmux 는 stub (PATH 우선) 로 대체 — has-session / pipe-pane 모두 exit 0.
# 실제 pane stdout 흐름이 아니라 로테이션 / 권한 / GC 로직을 검증한다.
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SUT="$SCRIPT_DIR/tmux-pane-capture.sh"
if [ ! -x "$SUT" ]; then
  echo "FAIL: 대상 스크립트 비실행: $SUT" >&2
  exit 1
fi

PASS=0
FAIL=0
FAILED=""

ok() { PASS=$((PASS + 1)); echo "PASS: $1"; }
ng() { FAIL=$((FAIL + 1)); FAILED="$FAILED\n  - $1"; echo "FAIL: $1"; }

# ─── tmux stub: has-session / pipe-pane 모두 성공 처리 ───
STUB_DIR="$(mktemp -d)"
cat > "$STUB_DIR/tmux" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "$STUB_DIR/tmux"

# 파일 모드 (8진수 3자리) — GNU/mac 양쪽 대응.
file_mode() {
  stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null
}

# 격리 환경에서 SUT 실행. $1=subcommand, 나머지 env 는 호출 측에서 export.
run_sut() {
  PATH="$STUB_DIR:$PATH" bash "$SUT" "$1" >/dev/null 2>&1
}

# ─── T1: rotate — MAX 초과 시 roll + 새 LOG_FILE 600 ───
T1="$(mktemp -d)"
export MOBRUJI_DIR="$T1"
export TMUX_PANE_LOG="$T1/tmux-pane.log"
export TMUX_PANE_LOG_MAX_BYTES=1000
export TMUX_PANE_LOG_KEEP=3
head -c 2000 /dev/zero | tr '\0' 'x' > "$TMUX_PANE_LOG"
run_sut rotate
if [ -f "$TMUX_PANE_LOG.1" ] && [ -f "$TMUX_PANE_LOG" ]; then
  rolled_size=$(stat -c%s "$TMUX_PANE_LOG.1" 2>/dev/null || stat -f%z "$TMUX_PANE_LOG.1" 2>/dev/null)
  new_size=$(stat -c%s "$TMUX_PANE_LOG" 2>/dev/null || stat -f%z "$TMUX_PANE_LOG" 2>/dev/null)
  mode=$(file_mode "$TMUX_PANE_LOG")
  if [ "$rolled_size" = "2000" ] && [ "$new_size" = "0" ] && [ "$mode" = "600" ]; then
    ok "T1 rotate-over-max (.1=2000B, new=0B, mode=600)"
  else
    ng "T1 rotate-over-max (rolled=$rolled_size new=$new_size mode=$mode)"
  fi
else
  ng "T1 rotate-over-max (.1 또는 새 LOG_FILE 부재)"
fi
unset TMUX_PANE_LOG_MAX_BYTES TMUX_PANE_LOG_KEEP

# ─── T2: rotate — MAX 미만이면 no-op (.1 미생성) ───
T2="$(mktemp -d)"
export MOBRUJI_DIR="$T2"
export TMUX_PANE_LOG="$T2/tmux-pane.log"
export TMUX_PANE_LOG_MAX_BYTES=100000
echo "small" > "$TMUX_PANE_LOG"
run_sut rotate
if [ ! -e "$TMUX_PANE_LOG.1" ] && [ "$(cat "$TMUX_PANE_LOG")" = "small" ]; then
  ok "T2 rotate-under-max-noop"
else
  ng "T2 rotate-under-max-noop (.1 생성됐거나 원본 변형)"
fi
unset TMUX_PANE_LOG_MAX_BYTES

# ─── T3: KEEP 강제 — KEEP=2 시 .3 GC, .2 까지만 유지 ───
T3="$(mktemp -d)"
export MOBRUJI_DIR="$T3"
export TMUX_PANE_LOG="$T3/tmux-pane.log"
export TMUX_PANE_LOG_MAX_BYTES=1000
export TMUX_PANE_LOG_KEEP=2
echo "old1" > "$TMUX_PANE_LOG.1"
echo "old2" > "$TMUX_PANE_LOG.2"
head -c 2000 /dev/zero | tr '\0' 'x' > "$TMUX_PANE_LOG"
run_sut rotate
# 기대: .3 없음 / .2 = (구 .1 "old1") / .1 = roll 된 2000B
if [ ! -e "$TMUX_PANE_LOG.3" ] && [ "$(cat "$TMUX_PANE_LOG.2")" = "old1" ]; then
  ok "T3 keep-gc (.3 없음, .2=old1 시프트)"
else
  ng "T3 keep-gc (.3 잔존 또는 시프트 오류)"
fi
unset TMUX_PANE_LOG_MAX_BYTES TMUX_PANE_LOG_KEEP

# ─── T4: start — LOG_FILE 신규 생성 + mode 600 ───
T4="$(mktemp -d)"
export MOBRUJI_DIR="$T4"
export TMUX_PANE_LOG="$T4/sub/tmux-pane.log"   # 디렉토리도 생성돼야 함
run_sut start
if [ -f "$TMUX_PANE_LOG" ] && [ "$(file_mode "$TMUX_PANE_LOG")" = "600" ]; then
  ok "T4 start-creates-600"
else
  ng "T4 start-creates-600 (파일 부재 또는 mode != 600)"
fi

# ─── T5: stop — exit 0 ───
T5="$(mktemp -d)"
export MOBRUJI_DIR="$T5"
export TMUX_PANE_LOG="$T5/tmux-pane.log"
if run_sut stop; then
  ok "T5 stop-exit0"
else
  ng "T5 stop-exit0 (비정상 종료)"
fi

# ─── T6: 알 수 없는 subcommand → exit 2 ───
PATH="$STUB_DIR:$PATH" bash "$SUT" bogus >/dev/null 2>&1
if [ "$?" = "2" ]; then
  ok "T6 unknown-subcommand-exit2"
else
  ng "T6 unknown-subcommand-exit2"
fi

# ─── 정리 + 집계 ───
rm -rf "$STUB_DIR" "$T1" "$T2" "$T3" "$T4" "$T5" 2>/dev/null || true

echo "─────────────────────────────"
echo "PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -ne 0 ]; then
  echo -e "실패:$FAILED" >&2
  exit 1
fi
exit 0
