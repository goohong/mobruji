#!/usr/bin/env bash
# test_rev_queue.sh — rev-queue.sh 스모크 테스트 (gh CLI mock)
#
# 사용: bash tools/rev-queue/tests/test_rev_queue.sh
# 종료 코드: 0 = 전체 통과, 1 = 하나라도 실패

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REV_QUEUE="$SCRIPT_DIR/../rev-queue.sh"

if [[ ! -x "$REV_QUEUE" ]]; then
  echo "FAIL: $REV_QUEUE not executable" >&2
  exit 1
fi

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

PASS=0
FAIL=0

# gh CLI mock 생성 헬퍼.
# 각 테스트는 mock_gh 파일을 정의 → PATH 앞에 두고 GH_BIN=mock_gh 로 호출.
make_mock_gh() {
  local mock_path="$TMP_DIR/gh"
  cat > "$mock_path" <<'MOCK_HEAD'
#!/usr/bin/env bash
# gh CLI mock — env var MOCK_* 로 응답 조작.
# rev-queue.sh 는 'gh ... --jq <expr>' 형태로 호출하므로,
# mock 은 raw JSON 응답을 jq 에 파이프해서 실제 gh --jq 동작을 모사한다.
set -u

cmd="$*"

# --jq <expr> 추출 (있으면 jq 적용, 없으면 raw 출력)
jq_expr=""
prev=""
for arg in "$@"; do
  if [[ "$prev" == "--jq" ]]; then
    jq_expr="$arg"
    break
  fi
  prev="$arg"
done

emit() {
  local raw="$1"
  if [[ -n "$jq_expr" ]]; then
    echo "$raw" | jq -r "$jq_expr" 2>/dev/null || true
  else
    echo "$raw"
  fi
}

if [[ "$cmd" == *"pr list"*"--state open"* ]]; then
  emit "${MOCK_PR_LIST_OPEN:-[]}"
elif [[ "$cmd" == *"pr list"*"--state merged"* ]]; then
  emit "${MOCK_PR_LIST_MERGED:-[]}"
elif [[ "$cmd" == *"release list"* ]]; then
  emit "${MOCK_RELEASE_LIST:-[]}"
elif [[ "$cmd" == *"release view"* ]]; then
  emit "${MOCK_RELEASE_VIEW:-{\"body\":\"\"}}"
elif [[ "$cmd" == *"pr view"* ]]; then
  # mock 은 단순 응답 — 라벨/제목 둘 다 동일 dummy
  emit "${MOCK_PR_VIEW:-{\"labels\":[],\"title\":\"mock-title\"}}"
else
  emit "[]"
fi
MOCK_HEAD
  chmod +x "$mock_path"
  echo "$mock_path"
}

run_test() {
  local name="$1"
  local expected_pattern="$2"
  local actual="$3"

  if echo "$actual" | grep -qE "$expected_pattern"; then
    echo "PASS: $name"
    PASS=$((PASS + 1))
  else
    echo "FAIL: $name"
    echo "  expected pattern: $expected_pattern"
    echo "  actual:"
    echo "$actual" | sed 's/^/    /'
    FAIL=$((FAIL + 1))
  fi
}

MOCK_GH=$(make_mock_gh)

# 호출 헬퍼 — env var 가 $() subshell 에 정확히 전파되도록 env 명시.
call_rev_queue() {
  # 사용: call_rev_queue <stage> <env1=val> [<env2=val> ...]
  local stage="$1"
  shift
  env GH_BIN="$MOCK_GH" "$@" "$REV_QUEUE" "$stage" 2>&1
}

# ─────────────────────────────────────────────────────────
# Test 1: stage1 — 빈 큐 (모든 PR 이 reviewed:claude 라벨)
# ─────────────────────────────────────────────────────────
actual=$(call_rev_queue stage1 \
  MOCK_PR_LIST_OPEN='[{"number":100,"title":"already reviewed","labels":[{"name":"reviewed:claude"}],"createdAt":"2026-05-23T10:00:00Z"}]')
run_test "stage1 - 빈 큐" "\(없음\)" "$actual"

# ─────────────────────────────────────────────────────────
# Test 2: stage1 — 1건 (reviewed:claude 라벨 없음)
# ─────────────────────────────────────────────────────────
actual=$(call_rev_queue stage1 \
  MOCK_PR_LIST_OPEN='[{"number":200,"title":"needs review","labels":[{"name":"type:feat"}],"createdAt":"2026-05-24T01:00:00Z"}]')
run_test "stage1 - 1건" "#200.*needs review" "$actual"

# ─────────────────────────────────────────────────────────
# Test 3: stage1 — 여러건 (정렬 확인)
# ─────────────────────────────────────────────────────────
actual=$(call_rev_queue stage1 \
  MOCK_PR_LIST_OPEN='[
    {"number":300,"title":"PR three","labels":[{"name":"type:feat"}],"createdAt":"2026-05-24T01:00:00Z"},
    {"number":301,"title":"PR three-one","labels":[{"name":"type:fix"}],"createdAt":"2026-05-23T10:00:00Z"},
    {"number":302,"title":"PR three-two reviewed","labels":[{"name":"reviewed:claude"}],"createdAt":"2026-05-22T12:00:00Z"}
  ]')
run_test "stage1 - 여러건 중 reviewed 제외 (#300)" "#300" "$actual"
run_test "stage1 - 여러건 중 reviewed 제외 (#301)" "#301" "$actual"
if echo "$actual" | grep -q "#302"; then
  echo "FAIL: stage1 - reviewed:claude PR #302 이 출력에 포함됨 (제외돼야 함)"
  FAIL=$((FAIL + 1))
else
  echo "PASS: stage1 - reviewed:claude #302 정상 제외"
  PASS=$((PASS + 1))
fi

# ─────────────────────────────────────────────────────────
# Test 4: stage2 — 정상 (1h+ merged, label 없음)
# 1h 전 cutoff 보다 더 과거의 mergedAt 사용
# ─────────────────────────────────────────────────────────
actual=$(call_rev_queue stage2 \
  MOCK_PR_LIST_MERGED='[
    {"number":400,"title":"merged needs post-merge","labels":[{"name":"type:feat"}],"mergedAt":"2026-05-23T00:00:00Z"},
    {"number":401,"title":"already post-merge","labels":[{"name":"rev-post-merge-pass"}],"mergedAt":"2026-05-22T00:00:00Z"}
  ]')
run_test "stage2 - rev-post-merge-pass 없는 PR 출력" "#400" "$actual"
if echo "$actual" | grep -q "#401"; then
  echo "FAIL: stage2 - rev-post-merge-pass 라벨 PR #401 출력에 포함됨 (제외돼야 함)"
  FAIL=$((FAIL + 1))
else
  echo "PASS: stage2 - rev-post-merge-pass #401 정상 제외"
  PASS=$((PASS + 1))
fi

# ─────────────────────────────────────────────────────────
# Test 5: stage2 — 빈 큐
# ─────────────────────────────────────────────────────────
actual=$(call_rev_queue stage2 MOCK_PR_LIST_MERGED='[]')
run_test "stage2 - 빈 큐" "\(없음\)" "$actual"

# ─────────────────────────────────────────────────────────
# Test 6: stage3 — release 없을 때
# ─────────────────────────────────────────────────────────
actual=$(call_rev_queue stage3 MOCK_RELEASE_LIST='[]')
run_test "stage3 - release 없음" "release 없음" "$actual"

# ─────────────────────────────────────────────────────────
# Test 7: 잘못된 stage 인자
# ─────────────────────────────────────────────────────────
actual=$(env GH_BIN="$MOCK_GH" "$REV_QUEUE" garbage 2>&1 || true)
run_test "잘못된 stage 인자 에러" "Unknown stage" "$actual"

# ─────────────────────────────────────────────────────────
# Test 8: 인자 없으면 에러
# ─────────────────────────────────────────────────────────
actual=$(env GH_BIN="$MOCK_GH" "$REV_QUEUE" 2>&1 || true)
run_test "인자 없음 에러" "stage required" "$actual"

# ─────────────────────────────────────────────────────────
# 결과 요약
# ─────────────────────────────────────────────────────────
echo
echo "─────────────────────────────────"
echo "Total: $((PASS + FAIL)) / Pass: $PASS / Fail: $FAIL"
if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
