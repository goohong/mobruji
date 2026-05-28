#!/usr/bin/env bash
# flock-fallback.sh — flock 의존 shell 스크립트를 macOS 환경에서 자동으로 NCP runtime 으로 위임
#
# 배경 (이슈 #1192):
#   macOS rev 환경에 flock 명령이 없어 lock 의존 shell test 가 false-fail.
#   rev #1175 보고: macOS false-fail 14건 → NCP 재실행으로 정확한 수치 확보.
#   본 헬퍼는 rev sub-agent 가 매번 수동 ssh NCP 하던 작업을 자동화.
#
# 사용:
#   flock-fallback.sh detect <script-path>
#       <script-path> 가 flock 명령에 의존하는지 검사.
#       의존 → exit 0, 비의존 → exit 1, 파일 없음 → exit 2.
#
#   flock-fallback.sh exec <script-path> [args...]
#       1. 로컬에 flock 있으면 <script-path> 를 직접 실행 (인자 그대로 forward).
#       2. 로컬 flock 부재 + 스크립트가 flock 의존 → MOBRUJI_NCP_HOST 로 ssh 후 재실행.
#       3. NCP host 미설정 시 graceful warning + 수동 안내 + exit 3.
#       4. ssh 결과 stdout/stderr 그대로 출력 + 원본 exit code 보존.
#
# 환경변수:
#   MOBRUJI_NCP_HOST=<user@host>     # NCP ssh 대상. 미설정 시 fallback 비활성화
#   MOBRUJI_NCP_REPO_PATH=<path>     # NCP 측 mobruji 저장소 경로 (default: /home/mobruji/mobruji)
#   SSH_BIN=<path>                    # ssh 명령 경로 (default: ssh, 테스트 mock 주입용)
#   FLOCK_FALLBACK_FORCE_REMOTE=1    # 강제 NCP fallback (로컬 flock 무시, 테스트용)
#
# Exit code:
#   0    정상 (또는 원본 스크립트 exit code 그대로)
#   1    detect 모드에서 flock 비의존
#   2    파일 없음 / 인자 오류
#   3    NCP host 미설정으로 fallback 불가
#   기타 원본 스크립트 exit code

set -uo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage:
  flock-fallback.sh detect <script-path>
  flock-fallback.sh exec   <script-path> [args...]

See header comment for environment variables.
USAGE
}

# flock 명령 사용 여부 검사. ($script 가 flock 호출 라인 포함하는지)
# 주의: 주석 안 flock 단어는 false-positive — 단순 word match 충분 (rev 가 위양성 < 위음성 우선).
script_uses_flock() {
  local script="$1"
  if [[ ! -f "$script" ]]; then
    return 2
  fi
  # 라인 시작부터 첫 # 이전 영역에 flock 단어 등장 시 의존으로 분류.
  # → 주석 라인 전체 제외 + 코드 후 trailing 주석 안 flock 도 제외.
  if grep -qE '^[^#]*\bflock\b' "$script"; then
    return 0
  fi
  return 1
}

cmd_detect() {
  local script="${1:-}"
  if [[ -z "$script" ]]; then
    usage
    return 2
  fi
  if [[ ! -f "$script" ]]; then
    echo "flock-fallback: script not found: $script" >&2
    return 2
  fi
  if script_uses_flock "$script"; then
    echo "flock-fallback: $script depends on flock"
    return 0
  fi
  echo "flock-fallback: $script does NOT depend on flock"
  return 1
}

# 로컬 실행 — flock 있고 강제 remote 아님.
local_exec() {
  local script="$1"
  shift
  "$script" "$@"
}

# NCP fallback 실행.
remote_exec() {
  local script="$1"
  shift
  local ncp_host="${MOBRUJI_NCP_HOST:-}"
  local ncp_repo="${MOBRUJI_NCP_REPO_PATH:-/home/mobruji/mobruji}"
  local ssh_bin="${SSH_BIN:-ssh}"

  if [[ -z "$ncp_host" ]]; then
    cat >&2 <<EOF
flock-fallback: 로컬 flock 부재 + MOBRUJI_NCP_HOST 미설정 → 자동 fallback 불가.

수동 우회:
  1. NCP host 에 ssh 후 동일 스크립트 재실행:
     ssh <user@ncp-host> 'cd $ncp_repo && bash <상대경로>'
  2. 또는 환경변수 설정 후 재호출:
     MOBRUJI_NCP_HOST=<user@host> bash tools/rev-queue/flock-fallback.sh exec $script $*
  3. 또는 macOS 에 flock shim 설치:
     brew install util-linux && brew link --force util-linux

(이슈 #1192 참조)
EOF
    return 3
  fi

  # 스크립트 절대경로를 NCP repo 상대경로로 변환.
  local repo_root
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  local rel_path="${script#$repo_root/}"
  if [[ "$rel_path" == "$script" ]]; then
    # 절대경로 아닌 일반 경로 — repo root 기준 그대로 사용.
    rel_path="$script"
  fi

  # 인자 quote escape — 단순 quote 처리 (특수문자는 호출자 책임).
  local quoted_args=""
  local arg
  for arg in "$@"; do
    # bash 안전 quote: 작은따옴표 안 escape.
    quoted_args+=" '${arg//\'/\'\\\'\'}'"
  done

  echo "flock-fallback: 로컬 flock 부재 → NCP ($ncp_host) fallback 실행" >&2
  echo "flock-fallback: ssh $ncp_host 'cd $ncp_repo && bash $rel_path$quoted_args'" >&2

  "$ssh_bin" "$ncp_host" "cd '$ncp_repo' && bash '$rel_path'$quoted_args"
  return $?
}

cmd_exec() {
  local script="${1:-}"
  if [[ -z "$script" ]]; then
    usage
    return 2
  fi
  if [[ ! -f "$script" ]]; then
    echo "flock-fallback: script not found: $script" >&2
    return 2
  fi
  shift

  # 강제 remote 모드 (테스트 / 디버그용).
  if [[ "${FLOCK_FALLBACK_FORCE_REMOTE:-0}" == "1" ]]; then
    remote_exec "$script" "$@"
    return $?
  fi

  # 로컬 flock 가용 → 로컬 실행.
  if command -v flock >/dev/null 2>&1; then
    local_exec "$script" "$@"
    return $?
  fi

  # 로컬 flock 부재 + 스크립트가 flock 의존하지 않으면 그냥 로컬 실행 (불필요한 ssh 회피).
  if ! script_uses_flock "$script"; then
    local_exec "$script" "$@"
    return $?
  fi

  # 로컬 flock 부재 + flock 의존 → NCP fallback.
  remote_exec "$script" "$@"
  return $?
}

main() {
  local mode="${1:-}"
  if [[ -z "$mode" ]]; then
    usage
    exit 2
  fi
  shift

  case "$mode" in
    detect)
      cmd_detect "$@"
      exit $?
      ;;
    exec)
      cmd_exec "$@"
      exit $?
      ;;
    -h|--help|help)
      usage
      exit 0
      ;;
    *)
      echo "flock-fallback: unknown mode '$mode'" >&2
      usage
      exit 2
      ;;
  esac
}

main "$@"
