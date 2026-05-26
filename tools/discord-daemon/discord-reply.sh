#!/usr/bin/env bash
# discord-reply.sh — helper 가 직접 bot REST API 로 응답 push.
#
# 사용 (이슈 #807 단순화본 + #880 thread stream 확장 + #946 reply mode + #960 ack reply 확장 + #963 ack 단순화 + #987 race condition fix):
#
#   1) 본답 (메인 채널 push, 기존 호환):
#       discord-reply.sh "<응답 메시지>"
#         → 본답 모드는 자동으로 leading ZWSP(U+200B) + \n 를 메시지 앞에
#           prepend 한다 (#921, 2026-05-24). 이유: jq escape 가 leading
#           newline 을 strip 해서 ack 와 본답이 Discord 채널에서 시각적으로
#           붙어 보이는 문제 영구 해결.
#         → 본답 모드는 또한 `~/.mobruji/last-user-msg-id.txt` (bot.py 가
#           on_message 시 atomic write — #946) 를 읽어 Discord REST API
#           `message_reference` 를 payload 에 포함시켜 자동으로 사용자
#           메시지에 reply (답장) 형태로 push 한다. 파일 부재 / 빈 값
#           (cron digest 등) 은 graceful standalone fallback.
#           --no-reply 플래그 또는 LAST_USER_MSG_ID_FILE=/dev/null 로 disable.
#
#   2) [DEPRECATED #963] ack + 새 thread 생성 (#880 thread stream + #960 ack reply):
#       THREAD_ID=$(discord-reply.sh --ack "<ack 문구>")
#         → helper 본체 ack push 는 폐지됐다 (bot.py auto-ack 가 1초 ack 역할).
#           본 mode 호출 시 stderr 에 deprecation warning 을 출력하되 동작 자체는
#           유지 (운영 호환성). 신규 helper 흐름은 ack 없이 본답으로 직행 — 채널에
#           messages 가 2건 (auto-ack 1 + 본답 1) 만 남아 가독성 ↑.
#           장시간 작업 thread 가 필요하면 `--auto-ack-thread` 사용 (의미가 ack 가
#           아니라 "작업 시작 메시지 + thread" 로 redefine — #963).
#         → 메인 채널에 ack 메시지 push + 그 메시지에 thread 생성.
#         → stdout 으로 thread_id 만 출력 (캐치하기 쉽게).
#         → thread 이름 = ack 문구 첫 30자 + ISO timestamp 짧은 형식.
#         → 본 호출 결과는 ~/.mobruji/helper-current-thread.txt 에도 1줄로 저장
#           (helper 가 다음 turn 에 환경변수 잃어도 복구 가능).
#         → ack push 자체도 본답 모드와 동일하게 `message_reference` 자동
#           적용 — 사용자 메시지에 답장 형태로 ack 가 붙어 어떤 메시지에 대한
#           ack 인지 시각적으로 식별 가능 (#960, 2026-05-24).
#           --no-reply flag 로 disable 가능.
#
#   3) thread 안 진행 stream (#880 thread stream):
#       discord-reply.sh --thread <id> "<진행 줄>"
#         → 해당 thread 에만 push (메인 채널 잡음 없음).
#         → thread 자체는 사용자 메시지에 붙은 컨텍스트 안에서 흐르므로
#           message_reference 미적용 (회귀 가드 테스트 존재).
#
#   4) auto-ack + thread (#947 helper 자동 활용 + #960 ack reply + #963 thread 시작 용도):
#       discord-reply.sh --auto-ack-thread "<작업 시작 문구>"
#         → 동작은 --ack 와 동일. helper 본체 룰 (CLAUDE.md §11) 직설 명명.
#         → #963 이후 본 mode 는 ack 가 아니라 **작업 thread 시작** 의미.
#           장시간 작업 (위임/조사/PR) 일 때만 호출해 진행 thread 를 만든다.
#           단순 즉답 turn 에선 호출 금지 — bot auto-ack 만으로 충분.
#         → ack push + thread 생성 + thread_id 를 ~/.mobruji/helper-current-thread.txt
#           에 atomic 저장 + stdout 으로 thread_id 출력.
#         → helper 가 stdout 캡쳐를 잊어도 다음 --auto-thread 호출이 파일에서 복구.
#         → ack push 도 `message_reference` 자동 적용 (#960).
#
#   5a) status channel routing (#1036, 2026-05-24 — 채널 분리 leak fix):
#       discord-reply.sh --status-channel "<status 본문>"
#       discord-reply.sh --channel <id> "<본문>"
#       discord-reply.sh --cycle-channel <be|fe|rev|plan> "<본문>"   (P12, 2026-05-24)
#         → 채널을 DIGEST_CHANNEL_ID (또는 임의 id, 또는 per-cycle 채널) 로 강제 override.
#           기본 채널은 MOBRUJI_CHANNEL_ID = 사용자 응답 #모부르지 — nmae sub-agent
#           launch/완료/cycle alert push 가 이 채널로 leak 되는 사고가 있어,
#           nmae status push 는 본 flag 또는 `nmae-discord-push.sh` wrapper 를
#           반드시 사용한다.
#         → --status-channel: .env 의 DIGEST_CHANNEL_ID (없으면 NOTIFY_CHANNEL_ID
#           backward-compat) 로 자동 override. 둘 다 미설정이면 명시적 에러
#           (silent fallback 으로 MOBRUJI 로 회귀하면 leak 룰 본질이 무력화 — 채널
#           분리가 안 됐다는 사실을 호출자에게 알린다).
#         → --channel <id>: 임의 채널 id 직접 지정. wrapper 작성 / 신설 status
#           채널용. id snowflake 검증은 하지 않음 (호출자 책임).
#         → --cycle-channel <be|fe|rev|plan> (P12, 2026-05-24): per-cycle 라우팅.
#           cycle 이름에 따라 BE/FE/REV/PLAN_CHANNEL_ID env 를 읽어 override.
#           미설정 시 DIGEST_CHANNEL_ID 로 graceful fallback + stderr deprecation
#           warning. 의도: 사이클 alert (launch/완료/오류) 가 사이클 별 채널로
#           분기 → 사용자가 워크트리 별 진행을 분리해 follow.
#         → 모든 flag 자동으로 `--no-reply` 와 동등한 효과 — status push 는
#           사용자 메시지에 답장 형태로 매달 필요가 없고, message_reference 가
#           원본 메시지 (다른 채널) 를 참조하면 Discord 가 404 처리.
#         → bare body / --ack / --auto-ack-thread / --auto-thread 등 모든 mode 와
#           조합 가능. thread 생성도 override 된 채널 안에서 발생.
#         → 관련 룰: CLAUDE.md §11-8 [[feedback-nmae-status-channel]]
#                   [[feedback-helper-relay-scope]] 거울 룰.
#
#   6) forum modes (#17 사용자 forum 전환 wave, 2026-05-24):
#       discord-reply.sh --forum-post <forum_env> "<title>" "<tag_name>" "<body>"
#       discord-reply.sh --forum-comment <thread_id> "<body>"
#       discord-reply.sh --forum-edit <thread_id> "<new_body>"
#       discord-reply.sh --forum-retag <thread_id> <forum_env> "<new_tag_name>"
#         → forum_env: directive | be | fe | rev | plan → 해당 *_FORUM_ID env lookup.
#         → tag_name: 해당 forum 의 available_tags name (예: "대기" / "진행" / "완료").
#           GET /channels/{forum_id} 응답의 available_tags 에서 name → id 변환.
#         → --forum-post: POST /channels/{forum_id}/threads (name + applied_tags +
#           message.content). 생성된 thread_id 를 stdout 으로 출력 (호출자가
#           후속 --forum-comment / --forum-edit / --forum-retag 에 사용).
#         → --forum-comment: POST /channels/{thread_id}/messages (forum thread 안
#           일반 댓글).
#         → --forum-edit: PATCH /channels/{thread_id}/messages/{thread_id} 로
#           starter message 본문 갱신. Discord forum 사양: forum thread 의
#           starter message id == thread id.
#         → --forum-retag: PATCH /channels/{thread_id} 의 applied_tags 만 갱신.
#         → 미지원 forum_env 또는 미지원 tag_name → 명시 에러 (silent skip 금지 —
#           forum 라우팅이 실패한 사실을 호출자에게 알린다).
#         → 모든 forum 모드 자동 NO_REPLY=1 (forum thread 는 사용자 메시지에
#           reply 가 의미 없음).
#         → 관련 룰: CLAUDE.md §11-9 [[feedback-nmae-forum-channel-enforce]]
#                   [[feedback-nmae-per-cycle-channel]] [[feedback-nmae-directive-board-update-flow]]
#
#   5) auto-thread (#947 helper 자동 활용 + #1021 launch thread fallback):
#       discord-reply.sh --auto-thread "<진행 줄>"
#         → thread_id resolve 우선순위 (높음 → 낮음):
#             1. `--thread <id>` 명시 (해당 모드는 별도 dispatch — 본 chain 우회)
#             2. `LAUNCH_THREAD_ID` 환경변수 (sub-agent 가 부모 launch prompt 에서 inherit)
#             3. `~/.mobruji/last-launch-thread.txt` (helper 본체가 sub-agent launch
#                직전 `--auto-ack-thread` 호출 시 자동 write — #1021)
#             4. `~/.mobruji/helper-current-thread.txt` (helper turn-level thread)
#         → 각 소스 snowflake 검증 (17–20 digit) → 실패 시 다음 fallback.
#         → 모두 실패면 graceful skip (exit 0, stderr warning).
#         → thread 만료 (24h archive) / 삭제 시 Discord 404 → stderr warning + exit 0
#           (helper turn 깨지지 않게).
#         → #1021 의도: helper LLM 이 launch prompt 안에 thread_id 값을 hallucinate
#           해서 invalid ID 를 박는 사고 우회. sub-agent 는 prompt 안 hardcoded
#           ID 대신 env / file 자동 read 로 정확한 thread 에 push.
#
# 배포 위치 권장:
#   - 워크트리: tools/discord-daemon/discord-reply.sh (소스 진실)
#   - 운영: ~/.mobruji/discord-reply.sh (helper PATH 진입점). 심볼릭 링크 또는 복사.
#
# 동작:
#   - .env 에서 DISCORD_BOT_TOKEN 과 채널 id (MOBRUJI_CHANNEL_ID 우선 — 사용자
#     응답은 #모부르지 채널, DIGEST_CHANNEL_ID 는 digest cron 전용 fallback —
#     기존 NOTIFY_CHANNEL_ID 도 #1019 backward-compat 으로 인식) 를 읽어 Discord
#     REST API 호출. bot.py 데몬과 동일한 .env 파일을 공유합니다.
#   - 메시지 본문은 jq 로 JSON-escape. 멀티라인 / 따옴표 안전.
#
# 종속:
#   - curl, jq, grep, cut, date. bot 호스트에 기본 설치되어 있는 도구만 사용.

set -euo pipefail

# 종속 도구 가드 — bot 호스트 외 환경(예: 신규 NCP 인스턴스)에서 조용히 실패하지
# 않도록 명시적으로 점검. set -e 와 별개로 사람 친화 메시지 제공.
command -v curl >/dev/null 2>&1 || { echo "discord-reply.sh: curl 미설치" >&2; exit 1; }
command -v jq   >/dev/null 2>&1 || { echo "discord-reply.sh: jq 미설치"   >&2; exit 1; }

ENV_PATH="${DISCORD_DAEMON_ENV_PATH:-/home/mobruji/mobruji/tools/discord-daemon/.env}"
if [[ ! -f "$ENV_PATH" ]]; then
  echo "discord-reply.sh: .env 파일 없음: $ENV_PATH" >&2
  exit 1
fi

# env 값 추출 헬퍼 — KEY 받아 값 1줄 echo. 따옴표 / CRLF 모두 strip 해서
# Windows 에서 편집된 .env (CRLF) 도 안전하게 받는다. head -1 로 같은 키
# 중복 정의 시 첫 줄만.
#
# 사고 가드 (#1025 후속, 2026-05-24):
#   PR #1025 (NOTIFY → DIGEST rename) 머지 후 production .env 에 신규 키
#   DIGEST_CHANNEL_ID 가 미설정 → `grep -E "^DIGEST_CHANNEL_ID="` 가 no-match
#   → exit 1 → set -euo pipefail + command substitution 안에서 즉시 스크립트
#   전체 종료. helper 본답 push 자체가 silent fail. 사용자 "정신 없니" 사고
#   root cause.
#
#   PR #1036 (status-channel) 은 호출자 측에 `|| true` 외부 가드를 도입했으나,
#   호출자마다 일일이 붙여야 하고 누락 시 동일 사고 재발 (예: TOKEN 호출은
#   원래 `|| true` 없었음). 본 PR 은 함수 안으로 가드를 이동해 영구 안전화.
#   기존 호출자 `|| true` 패턴은 멱등하게 호환 (중복 graceful — 무해).
#
#   if-then: grep no-match 일 때 pipeline 전체가 fail 로 평가되지 않게 if
#   조건 안에서 평가. line 변수가 빈 채로 남으면 빈 문자열 출력 후 종료 0.
#   호출자는 비-빈 문자열 검사만 하면 됨.
read_env_value() {
  local key="$1"
  local line=""
  # grep -E "^KEY=" 가 no-match 면 exit 1 — `if` 조건 안이라 set -e 무력화.
  # 2>/dev/null: ENV_PATH 가 갑자기 사라지는 race / 권한 문제도 graceful.
  if line=$(grep -E "^${key}=" "$ENV_PATH" 2>/dev/null | head -1); then
    printf '%s' "$line" \
      | cut -d= -f2- \
      | tr -d '\r' \
      | tr -d '"' \
      | tr -d "'"
  fi
  # 함수 종료 시 implicit return 0 — 호출자는 비-빈 문자열 검사만 하면 됨.
}

# TOKEN 호출 — 함수 자체가 graceful 이라 `|| true` 불필요. 빈 값 zero-check 만.
# (기존 TOKEN 호출은 `|| true` 가 없었음 — DISCORD_BOT_TOKEN 키 부재 시 set -e
#  로 즉시 죽고 line 아래 명시 에러 메시지가 출력되지 않는 latent bug. 본 fix
#  로 영구 해결.)
TOKEN=$(read_env_value DISCORD_BOT_TOKEN)
if [[ -z "$TOKEN" ]]; then
  echo "discord-reply.sh: DISCORD_BOT_TOKEN 비어 있음 ($ENV_PATH 확인)" >&2
  exit 1
fi

# 채널 resolve — MOBRUJI_CHANNEL_ID 우선 (helper raw 응답 = 메인 #모부르지),
# DIGEST_CHANNEL_ID (또는 backward-compat NOTIFY_CHANNEL_ID) 는 digest/status 전용 fallback.
# 본 변수는 default 경로용. `--status-channel` flag 가 명시되면 아래 mode dispatch
# 단계 후 channel 을 DIGEST 로 강제 override 한다 (사용자 응답 채널 leak 방지 — 이슈
# #1036, 2026-05-24 채널 분리 사고).
# read_env_value 는 grep 으로 키 검색 — 부재 키는 grep 이 1 종료 → set -e 와
# 충돌. `|| true` 로 graceful empty 보장 (변수 자체는 empty string 가짐).
MOBRUJI_CHANNEL_VALUE=$(read_env_value MOBRUJI_CHANNEL_ID || true)
DIGEST_CHANNEL_VALUE=$(read_env_value DIGEST_CHANNEL_ID || true)
NOTIFY_CHANNEL_VALUE=$(read_env_value NOTIFY_CHANNEL_ID || true)

# Per-cycle 채널 env (P12, 2026-05-24) — `--cycle-channel <be|fe|rev|plan>` flag
# 가 참조. 미설정 (빈 값) 시 DIGEST 로 graceful fallback (호출 시점에 결정).
# read_env_value 는 grep no-match graceful → 빈 문자열.
BE_CHANNEL_VALUE=$(read_env_value BE_CHANNEL_ID || true)
FE_CHANNEL_VALUE=$(read_env_value FE_CHANNEL_ID || true)
REV_CHANNEL_VALUE=$(read_env_value REV_CHANNEL_ID || true)
PLAN_CHANNEL_VALUE=$(read_env_value PLAN_CHANNEL_ID || true)

# Forum 채널 env (#17 사용자 forum 전환 wave, 2026-05-24) — `--forum-post`,
# `--forum-comment`, `--forum-edit`, `--forum-retag` mode 의 forum_env 인자가
# 참조. directive-board / be / fe / rev / plan 5 forum 채널.
# 미설정 시 명시 에러 (silent fallback 금지 — forum 전환 의도 무력화 방지).
# Discord forum 채널 = GUILD_FORUM type (15). 각 forum 안에 thread 가 post 단위로
# 생성되며 thread 안에 messages + applied_tags 가 붙는다.
DIRECTIVE_BOARD_FORUM_VALUE=$(read_env_value DIRECTIVE_BOARD_FORUM_ID || true)
BE_FORUM_VALUE=$(read_env_value BE_FORUM_ID || true)
FE_FORUM_VALUE=$(read_env_value FE_FORUM_ID || true)
REV_FORUM_VALUE=$(read_env_value REV_FORUM_ID || true)
PLAN_FORUM_VALUE=$(read_env_value PLAN_FORUM_ID || true)

# guild_id — forum thread 검색 시 GET /guilds/{guild_id}/threads/active 호출에 필요.
# DISCORD_GUILD_ID env 우선, 없으면 forum_find_active_thread_by_name 함수가 forum
# channel fetch 로 추출 (graceful).
DISCORD_GUILD_ID_VALUE=$(read_env_value DISCORD_GUILD_ID || true)

CHANNEL="$MOBRUJI_CHANNEL_VALUE"
if [[ -z "$CHANNEL" ]]; then
  CHANNEL="$DIGEST_CHANNEL_VALUE"
fi
if [[ -z "$CHANNEL" ]]; then
  # #1019 backward-compat — 기존 NOTIFY_CHANNEL_ID 도 fallback.
  CHANNEL="$NOTIFY_CHANNEL_VALUE"
  if [[ -n "$CHANNEL" ]]; then
    echo "discord-reply.sh: NOTIFY_CHANNEL_ID 는 deprecated — DIGEST_CHANNEL_ID 로 rename 됐습니다 (#1019). .env 갱신 권장." >&2
  fi
fi
if [[ -z "$CHANNEL" ]]; then
  echo "discord-reply.sh: MOBRUJI_CHANNEL_ID / DIGEST_CHANNEL_ID / NOTIFY_CHANNEL_ID 모두 비어 있음" >&2
  exit 1
fi

# $HOME 이 unbound 환경 (예: 테스트 / systemd unit 일부) 에서 set -u 로 죽지
# 않도록 명시적 default. 운영에서 $HOME 은 항상 존재 — 이 default 는 안전망.
HELPER_THREAD_FILE="${HELPER_THREAD_FILE:-${HOME:-/tmp}/.mobruji/helper-current-thread.txt}"

# #1021 (2026-05-24) — sub-agent launch 별 thread ID file passthrough.
# helper 본체가 `--auto-ack-thread` 호출 시 atomic write 하는 별도 파일.
# `--auto-thread` resolve chain 에서 LAUNCH_THREAD_ID env 다음으로 우선.
# 의도: helper LLM 이 launch prompt 작성 시 thread_id 값을 hallucinate 해서
# invalid ID 를 박는 사고 우회. sub-agent 가 env 없어도 파일에서 read.
LAUNCH_THREAD_FILE="${LAUNCH_THREAD_FILE:-${HOME:-/tmp}/.mobruji/last-launch-thread.txt}"

THREAD_NAME_MAX_LEN=30

# helper 본답/ack → 사용자 메시지 reply (#946 본답, #960 ack 확장, 2026-05-24).
# bot.py on_message 가 사용자 메시지 forward 시 이 파일에 message_id 를
# atomic write. 본답/ack 모드가 읽어 Discord `message_reference` payload 에 포함.
# 파일 부재 / 빈 값 / 비숫자 → graceful standalone (REST API 가 그래도 본답/ack
# 메시지는 push 되도록).
LAST_USER_MSG_ID_FILE="${LAST_USER_MSG_ID_FILE:-${HOME:-/tmp}/.mobruji/last-user-msg-id.txt}"

# #987 (2026-05-24) — reply race condition fix.
# 문제: helper turn 진행 중 새 user msg 도착 → bot.py 가
# last-user-msg-id.txt 덮어쓰기 → helper 답 push 시 reply 가 엉뚱한 msg 에 걸림.
# 해결: helper turn 시작 시점에 last-user-msg-id 를 별 파일에 freeze. turn 안
# 새 user msg 가 와도 helper-current-target.txt 는 안 바뀜.
#
# resolve 우선순위 (높음 → 낮음):
#   1. `--reply-to <id>` CLI flag (명시적 override)
#   2. `HELPER_TURN_TARGET_MSG_ID` env (sub-agent 가 부모 turn target 받을 때)
#   3. `~/.mobruji/helper-current-target.txt` (turn-start freeze)
#   4. `~/.mobruji/helper-queue.jsonl` 마지막 pending entry message_id
#   5. `~/.mobruji/last-user-msg-id.txt` (기존 fallback — turn-start freeze 안 했을 때)
HELPER_TARGET_FILE="${HELPER_TARGET_FILE:-${HOME:-/tmp}/.mobruji/helper-current-target.txt}"
HELPER_QUEUE_FILE="${HELPER_QUEUE_FILE:-${HOME:-/tmp}/.mobruji/helper-queue.jsonl}"

# Discord API retry 설정 (#911 G-6).
# 429 (Rate Limited) / 5xx (Server Error) 응답을 곧이곧대로 무시하지 않고
# Discord 가 권장하는 retry_after 또는 exponential backoff 로 재시도한다.
# 환경변수로 외부화 — 테스트에서 max=1, base=0 으로 강제해 fast fail 가능.
DISCORD_RETRY_MAX="${DISCORD_RETRY_MAX:-3}"
DISCORD_RETRY_BASE_SEC="${DISCORD_RETRY_BASE_SEC:-1}"

# ─── mode dispatch ────────────────────────────────────────────────────────────

MODE="reply"
THREAD_ID=""
MSG=""
# --no-reply: 본답 모드에서 message_reference 비활성화. 운영 환경에서
# last-user-msg-id 가 있어도 standalone push 하고 싶을 때 (예: cron 직접 호출).
NO_REPLY=0
# --reply-to <id>: target msg id 명시적 override (#987). resolve 우선순위 최상위.
REPLY_TO_OVERRIDE=""
# --status-channel: 채널을 DIGEST_CHANNEL_ID 로 강제 override (#1036, 2026-05-24).
# 의도: nmae sub-agent launch/완료/cycle alert push 가 사용자 응답 채널
# (#모부르지 = MOBRUJI_CHANNEL_ID) 으로 leak 되는 사고 방지. nmae 가 status
# 알림을 송신할 때 본 flag 또는 nmae-discord-push.sh wrapper 를 사용한다.
# --status-channel 사용 시 자동으로 NO_REPLY=1 가 강제된다 — status push 는
# 사용자 메시지에 답장 형태로 매달 필요가 없고, 그 reply 시도가 다시 leak
# 의심 패턴을 만들 수 있다.
STATUS_CHANNEL=0
# --channel <id>: 임의 채널 id 로 직접 override. 일반 wrapper 작성용.
# --status-channel 보다 우선 (명시 > 의미). reply 자동 disable 동일.
CHANNEL_OVERRIDE=""
# --cycle-channel <be|fe|rev|plan> (P12, 2026-05-24): per-cycle 라우팅.
# cycle 이름에 따라 BE/FE/REV/PLAN_CHANNEL_ID env 로 override. 미설정 시
# DIGEST_CHANNEL_ID 로 fallback + stderr deprecation warning. NO_REPLY 자동 1.
# 우선순위: --channel <id> > --cycle-channel <name> > --status-channel > default.
CYCLE_CHANNEL=""
# Forum mode 인자 (#17, 2026-05-24).
# --forum-post / --forum-retag 의 forum_env: directive | be | fe | rev | plan.
# --forum-post 의 title (thread name) + tag (available_tags name).
FORUM_ENV=""
FORUM_TITLE=""
FORUM_TAG=""

if [[ $# -eq 0 ]]; then
  echo "discord-reply.sh: 인자 부족 — 사용법:" >&2
  echo "  discord-reply.sh \"<메시지>\"" >&2
  echo "  discord-reply.sh [--no-reply] [--reply-to <id>] [--status-channel | --channel <id> | --cycle-channel <be|fe|rev|plan>] \"<메시지>\"" >&2
  echo "  discord-reply.sh --ack \"<ack 문구>\"" >&2
  echo "  discord-reply.sh --thread <id> \"<진행 줄>\"" >&2
  echo "  discord-reply.sh --auto-ack-thread \"<ack 문구>\"" >&2
  echo "  discord-reply.sh --auto-thread \"<진행 줄>\"" >&2
  echo "  discord-reply.sh --forum-post <directive|be|fe|rev|plan> \"<title>\" \"<tag>\" \"<body>\"" >&2
  echo "  discord-reply.sh --forum-comment <thread_id> \"<body>\"" >&2
  echo "  discord-reply.sh --forum-edit <thread_id> \"<new_body>\"" >&2
  echo "  discord-reply.sh --forum-retag <thread_id> <directive|be|fe|rev|plan> \"<new_tag>\"" >&2
  exit 1
fi

# --no-reply / --reply-to / --status-channel / --channel 은 선택적 prefix —
# 다른 mode flag 보다 먼저 consume. 순서 자유 (CLI 친화).
# --no-reply 가 --reply-to 와 동시 지정 시 standalone (안전 fail-safe).
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-reply)
      NO_REPLY=1
      shift
      ;;
    --reply-to)
      if [[ $# -lt 2 ]]; then
        echo "discord-reply.sh: --reply-to 뒤에 message_id 가 필요합니다" >&2
        exit 1
      fi
      REPLY_TO_OVERRIDE="$2"
      shift 2
      ;;
    --status-channel)
      STATUS_CHANNEL=1
      # status 채널 push 는 사용자 메시지 reply 가 의미 없음 + leak 의심
      # 패턴 회피 — 자동 NO_REPLY 강제.
      NO_REPLY=1
      shift
      ;;
    --channel)
      if [[ $# -lt 2 ]]; then
        echo "discord-reply.sh: --channel 뒤에 channel_id 가 필요합니다" >&2
        exit 1
      fi
      CHANNEL_OVERRIDE="$2"
      NO_REPLY=1
      shift 2
      ;;
    --cycle-channel)
      # P12 (2026-05-24) — per-cycle 채널 라우팅. cycle 이름 (be/fe/rev/plan)
      # 받아 해당 cycle 의 *_CHANNEL_ID env 로 override. 미설정 시 DIGEST 로
      # graceful fallback + stderr warning. status 와 동일하게 NO_REPLY 강제.
      if [[ $# -lt 2 ]]; then
        echo "discord-reply.sh: --cycle-channel 뒤에 cycle 이름 (be|fe|rev|plan) 이 필요합니다" >&2
        exit 1
      fi
      CYCLE_CHANNEL="$2"
      # cycle 이름 검증 — 잘못된 이름이면 명시 에러 (silent fallback 금지).
      case "$CYCLE_CHANNEL" in
        be|fe|rev|plan) ;;
        *)
          echo "discord-reply.sh: --cycle-channel 값은 be|fe|rev|plan 중 하나여야 합니다 (받은 값: $CYCLE_CHANNEL)" >&2
          exit 1
          ;;
      esac
      NO_REPLY=1
      shift 2
      ;;
    *)
      break
      ;;
  esac
done

# 채널 override 적용 (mode dispatch 이전 — start_thread_from_message 등 모든
# 헬퍼가 동일 $CHANNEL 을 보고 호출하기 때문).
# 우선순위: --channel <id> > --cycle-channel <name> > --status-channel > default
# ($CHANNEL 위에서 resolve).
if [[ -n "$CHANNEL_OVERRIDE" ]]; then
  CHANNEL="$CHANNEL_OVERRIDE"
elif [[ -n "$CYCLE_CHANNEL" ]]; then
  # P12 (2026-05-24) — per-cycle 채널 라우팅.
  # cycle 이름 → 해당 *_CHANNEL_ID env 변수 값. 미설정 시 DIGEST 로 fallback +
  # stderr deprecation warning (운영자에게 채널 추가 권장 신호).
  case "$CYCLE_CHANNEL" in
    be)   CYCLE_RESOLVED_VALUE="$BE_CHANNEL_VALUE" ;;
    fe)   CYCLE_RESOLVED_VALUE="$FE_CHANNEL_VALUE" ;;
    rev)  CYCLE_RESOLVED_VALUE="$REV_CHANNEL_VALUE" ;;
    plan) CYCLE_RESOLVED_VALUE="$PLAN_CHANNEL_VALUE" ;;
  esac
  if [[ -n "$CYCLE_RESOLVED_VALUE" ]]; then
    CHANNEL="$CYCLE_RESOLVED_VALUE"
  elif [[ -n "$DIGEST_CHANNEL_VALUE" ]]; then
    CHANNEL="$DIGEST_CHANNEL_VALUE"
    echo "discord-reply.sh: --cycle-channel $CYCLE_CHANNEL — $(echo "$CYCLE_CHANNEL" | tr '[:lower:]' '[:upper:]')_CHANNEL_ID 미설정, DIGEST_CHANNEL_ID 로 fallback. .env 에 채널 id 추가 권장." >&2
  elif [[ -n "$NOTIFY_CHANNEL_VALUE" ]]; then
    CHANNEL="$NOTIFY_CHANNEL_VALUE"
    echo "discord-reply.sh: --cycle-channel $CYCLE_CHANNEL — cycle/DIGEST 채널 모두 미설정, NOTIFY_CHANNEL_ID 로 fallback (deprecated)." >&2
  else
    echo "discord-reply.sh: --cycle-channel $CYCLE_CHANNEL 지정됐으나 $(echo "$CYCLE_CHANNEL" | tr '[:lower:]' '[:upper:]')_CHANNEL_ID / DIGEST_CHANNEL_ID / NOTIFY_CHANNEL_ID 모두 비어 있음" >&2
    echo "  .env 에 $(echo "$CYCLE_CHANNEL" | tr '[:lower:]' '[:upper:]')_CHANNEL_ID=<id> 추가 후 재시도하세요." >&2
    exit 1
  fi
elif [[ "$STATUS_CHANNEL" -eq 1 ]]; then
  # DIGEST_CHANNEL_ID 또는 backward-compat NOTIFY_CHANNEL_ID 로 강제.
  # MOBRUJI 만 설정돼 있어 DIGEST 가 비어 있다면 명시적 에러 (silent leak
  # 방지 — 채널 분리 의도가 명백한데 fallback 으로 다시 MOBRUJI 로 가면 룰
  # 위반의 본질이 무력화됨).
  if [[ -n "$DIGEST_CHANNEL_VALUE" ]]; then
    CHANNEL="$DIGEST_CHANNEL_VALUE"
  elif [[ -n "$NOTIFY_CHANNEL_VALUE" ]]; then
    CHANNEL="$NOTIFY_CHANNEL_VALUE"
    echo "discord-reply.sh: --status-channel — NOTIFY_CHANNEL_ID 사용 (DIGEST_CHANNEL_ID 로 rename 권장, #1019)." >&2
  else
    echo "discord-reply.sh: --status-channel 지정됐으나 DIGEST_CHANNEL_ID / NOTIFY_CHANNEL_ID 미설정 — 채널 분리 불가" >&2
    echo "  .env 에 DIGEST_CHANNEL_ID=<id> 추가 후 재시도하세요." >&2
    exit 1
  fi
fi

if [[ $# -eq 0 ]]; then
  echo "discord-reply.sh: prefix flag 뒤에 메시지가 필요합니다" >&2
  exit 1
fi

case "$1" in
  --ack|--auto-ack-thread)
    # --auto-ack-thread 는 --ack 와 동일 동작 — helper 본체 룰 가독성용 alias (#947).
    # #963: helper 본체 ack push 폐지. `--ack` (bare) 호출은 deprecated — stderr warning.
    #       `--auto-ack-thread` 는 thread 시작 용도로 redefine 됐기에 warning 없음.
    if [[ "$1" == "--ack" ]]; then
      echo "discord-reply.sh: --ack mode 는 deprecated (#963) — helper 본체 ack push 폐지. bot.py auto-ack 가 1초 ack 역할. 작업 thread 가 필요하면 --auto-ack-thread 사용." >&2
    fi
    MODE="ack"
    if [[ $# -lt 2 ]]; then
      echo "discord-reply.sh: $1 뒤에 문구가 필요합니다" >&2
      exit 1
    fi
    MSG="$2"
    ;;
  --thread)
    MODE="thread"
    if [[ $# -lt 3 ]]; then
      echo "discord-reply.sh: --thread <id> <메시지> 형태로 입력해주세요" >&2
      exit 1
    fi
    THREAD_ID="$2"
    MSG="$3"
    ;;
  --auto-thread)
    # helper-current-thread.txt 자동 읽어 thread push (#947).
    MODE="auto-thread"
    if [[ $# -lt 2 ]]; then
      echo "discord-reply.sh: --auto-thread 뒤에 진행 줄이 필요합니다" >&2
      exit 1
    fi
    MSG="$2"
    ;;
  --forum-post)
    # #17 forum 전환 wave (2026-05-24).
    # --forum-post <forum_env> "<title>" "<tag_name>" "<body>"
    # forum_env: directive | be | fe | rev | plan → *_FORUM_ID lookup.
    # tag_name: 해당 forum 의 available_tags name → tag_id 변환.
    MODE="forum-post"
    if [[ $# -lt 5 ]]; then
      echo "discord-reply.sh: --forum-post <forum_env> \"<title>\" \"<tag>\" \"<body>\" 형태로 입력해주세요" >&2
      exit 1
    fi
    FORUM_ENV="$2"
    FORUM_TITLE="$3"
    FORUM_TAG="$4"
    MSG="$5"
    NO_REPLY=1
    ;;
  --forum-comment)
    # #17 — forum thread 안 댓글 (POST /channels/{thread_id}/messages).
    MODE="forum-comment"
    if [[ $# -lt 3 ]]; then
      echo "discord-reply.sh: --forum-comment <thread_id> \"<body>\" 형태로 입력해주세요" >&2
      exit 1
    fi
    THREAD_ID="$2"
    MSG="$3"
    NO_REPLY=1
    ;;
  --forum-edit)
    # #17 — forum thread starter message 본문 PATCH.
    # Discord 사양: forum thread 의 starter message id == thread id.
    # PATCH /channels/{thread_id}/messages/{thread_id}.
    MODE="forum-edit"
    if [[ $# -lt 3 ]]; then
      echo "discord-reply.sh: --forum-edit <thread_id> \"<new_body>\" 형태로 입력해주세요" >&2
      exit 1
    fi
    THREAD_ID="$2"
    MSG="$3"
    NO_REPLY=1
    ;;
  --forum-retag)
    # #17 — forum thread applied_tags 갱신.
    # PATCH /channels/{thread_id} with body { applied_tags: [<tag_id>] }.
    MODE="forum-retag"
    if [[ $# -lt 4 ]]; then
      echo "discord-reply.sh: --forum-retag <thread_id> <forum_env> \"<new_tag>\" 형태로 입력해주세요" >&2
      exit 1
    fi
    THREAD_ID="$2"
    FORUM_ENV="$3"
    FORUM_TAG="$4"
    # body 인자는 retag 에선 사용하지 않으나 MSG 빈값 가드 우회용 placeholder.
    MSG="(retag)"
    NO_REPLY=1
    ;;
  --cycle-backlog-upsert)
    # 2026-05-26 — cycle (be/fe/rev/plan) 백로그 forum thread upsert.
    # 형식: --cycle-backlog-upsert <be|fe|rev|plan> "<markdown body>"
    #
    # 동작:
    #   1) 해당 cycle 의 *_FORUM_ID 에서 active thread 검색 — name == "[BACKLOG] <cycle>".
    #   2) 있으면 starter message PATCH (forum_edit_starter) — 본문 갱신.
    #   3) 없으면 신규 thread post (forum_create_thread, tag="대기" — 없으면 첫 available tag).
    #   4) stdout 으로 thread_id 출력 (호출자가 wrapper 에서 cache 가능).
    #
    # 의도:
    #   각 cycle sub-agent 가 자기 forum 안 단일 [BACKLOG] thread 를 보고 자기
    #   계획 (작업 안 까먹기) — 사용자 정정 (2026-05-26): "각 agent 가 자기 계획이
    #   있어야지. 백로그 보면서 작업 안 까먹고 다 진행하고."
    #
    # 본문 컨벤션 (권장):
    #   - markdown checkbox: `- [ ] 작업 1 (#N)` / `- [x] 완료된 작업 (#M)`.
    #   - 끝줄에 "_갱신: 2026-05-26T12:00Z_" 형태 timestamp.
    #   - 호출자 (tools/cycle-backlog/upsert.sh wrapper) 가 markdown 빌드 책임.
    MODE="cycle-backlog-upsert"
    if [[ $# -lt 3 ]]; then
      echo "discord-reply.sh: --cycle-backlog-upsert <be|fe|rev|plan> \"<markdown body>\" 형태로 입력해주세요" >&2
      exit 1
    fi
    FORUM_ENV="$2"
    case "$FORUM_ENV" in
      be|fe|rev|plan) ;;
      *)
        echo "discord-reply.sh: --cycle-backlog-upsert <forum_env> 값은 be|fe|rev|plan 중 하나여야 합니다 (받은 값: $FORUM_ENV)" >&2
        exit 1
        ;;
    esac
    MSG="$3"
    NO_REPLY=1
    ;;
  --*)
    echo "discord-reply.sh: 알 수 없는 옵션 $1" >&2
    exit 1
    ;;
  *)
    MODE="reply"
    MSG="$1"
    ;;
esac

if [[ -z "$MSG" ]]; then
  echo "discord-reply.sh: 빈 메시지 — 호출 의도 확인 필요" >&2
  exit 1
fi

# ─── helpers ──────────────────────────────────────────────────────────────────

# JSON escape via jq -Rn (raw input + null입). 단일 인자에 멀티라인/따옴표 안전.
json_escape() {
  jq -Rn --arg s "$1" '$s'
}

# Discord REST 호출 + 429/5xx retry (#911 G-6).
#
# 인자: METHOD URL BODY
# stdout: 마지막 시도의 응답 본문 (JSON 가정). 호출부는 기존처럼 jq 로 파싱.
# 종료코드: 마지막 시도가 2xx 면 0, 그 외면 1.
#
# 정책:
#   - 2xx → 즉시 반환 (0).
#   - 429 → JSON `retry_after` (Discord 권고) 가 있으면 그 초만큼 sleep, 없으면
#           DISCORD_RETRY_BASE_SEC 사용. retry_after 는 정수/소수 모두 허용.
#   - 5xx → exponential backoff (base * 2^(i-1)).
#   - 그 외 (4xx 등) → retry 무의미 → 즉시 종료 (1) + 응답 그대로 반환.
#   - DISCORD_RETRY_MAX 회 시도 후 실패 → stderr warning + 1 반환.
discord_curl_with_retry() {
  local method="$1"
  local url="$2"
  local body="$3"
  local response status payload retry_after sleep_sec i

  for ((i = 1; i <= DISCORD_RETRY_MAX; i++)); do
    # -w '\n%{http_code}' 로 마지막 줄에 status code append.
    response=$(curl -sS -X "$method" "$url" \
      -H "Authorization: Bot ${TOKEN}" \
      -H "Content-Type: application/json" \
      -w $'\n%{http_code}' \
      -d "$body" 2>/dev/null || true)

    status="${response##*$'\n'}"
    payload="${response%$'\n'*}"

    if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
      printf '%s' "$payload"
      return 0
    fi

    if [[ "$status" == "429" ]]; then
      # Discord 권고 retry_after — JSON 본문에 초 단위로 옴.
      retry_after=$(printf '%s' "$payload" \
        | jq -r '.retry_after // empty' 2>/dev/null || true)
      if [[ -z "$retry_after" || "$retry_after" == "null" ]]; then
        sleep_sec="$DISCORD_RETRY_BASE_SEC"
      else
        sleep_sec="$retry_after"
      fi
      echo "discord-reply.sh: 429 rate limit — retry ${i}/${DISCORD_RETRY_MAX} after ${sleep_sec}s" >&2
      sleep "$sleep_sec"
      continue
    fi

    if [[ "$status" =~ ^5[0-9][0-9]$ ]]; then
      sleep_sec=$(awk -v base="$DISCORD_RETRY_BASE_SEC" -v exp="$((i - 1))" \
        'BEGIN { printf "%.2f", base * (2 ^ exp) }')
      echo "discord-reply.sh: ${status} server error — retry ${i}/${DISCORD_RETRY_MAX} after ${sleep_sec}s" >&2
      sleep "$sleep_sec"
      continue
    fi

    # 4xx 등 — retry 무의미.
    printf '%s' "$payload"
    return 1
  done

  echo "discord-reply.sh: ${DISCORD_RETRY_MAX} 회 retry 실패 (last status=${status})" >&2
  printf '%s' "$payload"
  return 1
}

# 메인 채널에 메시지 push. stdout = REST 응답 raw (JSON).
post_channel_message() {
  local body="$1"
  discord_curl_with_retry POST \
    "https://discord.com/api/v10/channels/${CHANNEL}/messages" \
    "$body"
}

# 메시지에서 thread 시작 (해당 메시지 아래에 붙는 thread).
# Discord REST: POST /channels/{channel_id}/messages/{message_id}/threads
# response.id = thread id (TEXTUAL_THREAD type 11).
start_thread_from_message() {
  local message_id="$1"
  local thread_name="$2"
  local body
  body=$(jq -nc --arg n "$thread_name" '{name: $n, auto_archive_duration: 1440}')
  discord_curl_with_retry POST \
    "https://discord.com/api/v10/channels/${CHANNEL}/messages/${message_id}/threads" \
    "$body"
}

# thread 안 push. thread 자체가 channel snowflake 처럼 동작 (Discord API spec).
post_thread_message() {
  local thread_id="$1"
  local body="$2"
  discord_curl_with_retry POST \
    "https://discord.com/api/v10/channels/${thread_id}/messages" \
    "$body"
}

# snowflake 유효성 검사 — 17~20 digit 정수. invalid 면 stderr warning + 빈 출력.
# Discord snowflake = 64bit unsigned = 2015 epoch 이후 항상 17~19 자리 (보수적으로
# 20 까지 허용). 짧은 정수 ("4" 등) / 비숫자 / 빈 값 → Discord API 10008
# (Unknown Message). source 인자는 stderr warning 용 label (어디서 온 값인지).
validate_snowflake() {
  local raw="$1"
  local source_label="$2"
  if [[ "$raw" =~ ^[0-9]{17,20}$ ]]; then
    printf '%s' "$raw"
    return 0
  fi
  if [[ -n "$raw" ]]; then
    echo "discord-reply.sh: ${source_label} 비-snowflake (\"$raw\") — 다음 fallback 으로" >&2
  fi
  printf ''
  return 1
}

# helper-queue.jsonl 마지막 pending entry 의 message_id 추출 (#987).
# jq 가 있으면 ndjson 파싱 (안전), 없으면 grep + sed 폴백.
# 빈 출력 = pending entry 없음 또는 파일 부재.
read_last_pending_queue_msg_id() {
  if [[ ! -r "$HELPER_QUEUE_FILE" ]]; then
    return 0
  fi
  # jq: status=="pending" 인 entry 만 필터, 마지막 1개의 message_id.
  # 파일이 빈 jsonl 이거나 entry 가 모두 done 이면 빈 문자열.
  local last_id
  last_id=$(jq -r 'select(.status == "pending") | .message_id // empty' \
    "$HELPER_QUEUE_FILE" 2>/dev/null \
    | tail -1 \
    | tr -d '[:space:]' || true)
  printf '%s' "${last_id:-}"
}

# reply 대상 message_id 해석 — #946 / #960 / #987 통합 우선순위 체인.
#
# 출력: stdout 으로 message_id (정수 문자열) 또는 빈 문자열.
# 정책:
#   - $NO_REPLY=1 → 빈 문자열 (명시적 disable).
#   - 우선순위 (높음 → 낮음):
#       1. $REPLY_TO_OVERRIDE (--reply-to flag)
#       2. $HELPER_TURN_TARGET_MSG_ID env (sub-agent 가 부모 turn target inherit)
#       3. $HELPER_TARGET_FILE (turn-start freeze)
#       4. $HELPER_QUEUE_FILE 마지막 pending entry
#       5. $LAST_USER_MSG_ID_FILE (기존 fallback)
#   - 각 소스마다 snowflake 검증 → 실패 시 다음 fallback. 마지막까지 실패면 빈 문자열.
#
# bare body + ack 모드 모두 동일 로직을 공유하기 위해 함수로 분리.
resolve_reply_to_id() {
  if [[ "$NO_REPLY" -eq 1 ]]; then
    printf ''
    return 0
  fi

  local candidate=""

  # 1) --reply-to override (최우선).
  if [[ -n "$REPLY_TO_OVERRIDE" ]]; then
    if candidate=$(validate_snowflake "$REPLY_TO_OVERRIDE" "--reply-to"); then
      printf '%s' "$candidate"
      return 0
    fi
  fi

  # 2) HELPER_TURN_TARGET_MSG_ID env.
  if [[ -n "${HELPER_TURN_TARGET_MSG_ID:-}" ]]; then
    if candidate=$(validate_snowflake \
        "$HELPER_TURN_TARGET_MSG_ID" \
        "HELPER_TURN_TARGET_MSG_ID env"); then
      printf '%s' "$candidate"
      return 0
    fi
  fi

  # 3) helper-current-target.txt (turn-start freeze).
  if [[ -r "$HELPER_TARGET_FILE" ]]; then
    local target_raw
    target_raw=$(head -1 "$HELPER_TARGET_FILE" 2>/dev/null \
      | tr -d '[:space:]' || true)
    if [[ -n "$target_raw" ]]; then
      if candidate=$(validate_snowflake "$target_raw" "helper-current-target.txt"); then
        printf '%s' "$candidate"
        return 0
      fi
    fi
  fi

  # 4) helper-queue.jsonl 마지막 pending entry.
  local queue_id
  queue_id=$(read_last_pending_queue_msg_id)
  if [[ -n "$queue_id" ]]; then
    if candidate=$(validate_snowflake "$queue_id" "helper-queue.jsonl pending entry"); then
      printf '%s' "$candidate"
      return 0
    fi
  fi

  # 5) last-user-msg-id.txt (기존 fallback).
  if [[ -r "$LAST_USER_MSG_ID_FILE" ]]; then
    local last_raw
    last_raw=$(head -1 "$LAST_USER_MSG_ID_FILE" 2>/dev/null \
      | tr -d '[:space:]' || true)
    if [[ -n "$last_raw" ]]; then
      if candidate=$(validate_snowflake "$last_raw" "last-user-msg-id.txt"); then
        printf '%s' "$candidate"
        return 0
      fi
    fi
  fi

  printf ''
}

# reply 적용 payload 빌더 — message_id 있으면 message_reference 포함, 없으면 단순 content (#946, #960).
#
# 인자: CONTENT REPLY_TO_ID
# 출력: jq -nc 로 빌드한 JSON payload (stdout 1줄).
#
# fail_if_not_exists: false — referenced message 가 삭제됐어도 본 메시지
# 자체는 정상 push (standalone 으로 표시). Discord 권장 패턴.
build_reply_payload() {
  local content="$1"
  local reply_to_id="$2"
  if [[ -n "$reply_to_id" ]]; then
    jq -nc \
      --arg c "$content" \
      --arg mid "$reply_to_id" \
      --arg cid "$CHANNEL" \
      '{
        content: $c,
        message_reference: {
          message_id: $mid,
          channel_id: $cid,
          fail_if_not_exists: false
        }
      }'
  else
    jq -nc --arg c "$content" '{content: $c}'
  fi
}

# helper-current-thread.txt atomic write (#911 G-5).
#
# 동시에 두 helper turn 이 --ack 를 호출하면 read/write 순서가 어긋나 마지막
# write 가 부분 파일을 남길 수 있다 (또는 두 writer 가 동일 path 에 동시에
# write 하면 reader 가 일부만 본 채로 thread id 를 잘못 파싱).
# `mktemp + mv` 패턴으로 같은 파일시스템 안에서 atomic rename 을 보장한다.
# (POSIX rename(2) atomicity — 같은 디렉터리/파일시스템 내부 필수).
atomic_write_thread_file() {
  local thread_id="$1"
  local thread_file="$2"
  local dir tmp
  dir="$(dirname "$thread_file")"
  mkdir -p "$dir"
  # mktemp 를 동일 디렉터리에 만들어 cross-fs rename 회피.
  tmp=$(mktemp "${thread_file}.XXXXXX")
  printf '%s\n' "$thread_id" > "$tmp"
  mv "$tmp" "$thread_file"
}

# ─── forum helpers (#17, 2026-05-24) ─────────────────────────────────────────

# forum_env 이름 (directive | be | fe | rev | plan) → *_FORUM_ID env 값 resolve.
# 미설정 또는 미지원 이름 → 명시 에러 (silent fallback 금지).
resolve_forum_id() {
  local forum_env="$1"
  local forum_id=""
  case "$forum_env" in
    directive) forum_id="$DIRECTIVE_BOARD_FORUM_VALUE" ;;
    be)        forum_id="$BE_FORUM_VALUE" ;;
    fe)        forum_id="$FE_FORUM_VALUE" ;;
    rev)       forum_id="$REV_FORUM_VALUE" ;;
    plan)      forum_id="$PLAN_FORUM_VALUE" ;;
    *)
      echo "discord-reply.sh: 알 수 없는 forum_env \"$forum_env\" — directive|be|fe|rev|plan 중 하나여야 합니다" >&2
      return 1
      ;;
  esac
  if [[ -z "$forum_id" ]]; then
    local env_key
    env_key=$(echo "$forum_env" | tr '[:lower:]' '[:upper:]')
    if [[ "$forum_env" == "directive" ]]; then
      env_key="DIRECTIVE_BOARD"
    fi
    echo "discord-reply.sh: ${env_key}_FORUM_ID 미설정 — .env 에 forum channel id 추가 후 재시도하세요" >&2
    return 1
  fi
  printf '%s' "$forum_id"
}

# forum 의 available_tags name → id lookup.
# GET /channels/{forum_id} 응답의 available_tags 배열에서 name 일치 entry 의 id 반환.
# 미지원 tag_name → 명시 에러 (silent skip 금지).
resolve_forum_tag_id() {
  local forum_id="$1"
  local tag_name="$2"
  local response tag_id
  response=$(curl -sS -X GET \
    "https://discord.com/api/v10/channels/${forum_id}" \
    -H "Authorization: Bot ${TOKEN}" \
    -w $'\n%{http_code}' 2>/dev/null || true)
  local status payload
  status="${response##*$'\n'}"
  payload="${response%$'\n'*}"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "discord-reply.sh: forum channel fetch 실패 (forum_id=$forum_id, status=$status)" >&2
    echo "$payload" >&2
    return 1
  fi
  tag_id=$(printf '%s' "$payload" \
    | jq -r --arg n "$tag_name" \
        '.available_tags[]? | select(.name == $n) | .id' \
    | head -1)
  if [[ -z "$tag_id" || "$tag_id" == "null" ]]; then
    local available
    available=$(printf '%s' "$payload" \
      | jq -r '[.available_tags[]?.name] | join(", ")')
    echo "discord-reply.sh: forum tag \"$tag_name\" 미지원 (forum_id=$forum_id, available: $available)" >&2
    return 1
  fi
  printf '%s' "$tag_id"
}

# forum thread 생성 — POST /channels/{forum_id}/threads.
# body: { name, applied_tags: [<tag_id>], message: { content } }.
forum_create_thread() {
  local forum_id="$1"
  local name="$2"
  local tag_id="$3"
  local content="$4"
  local body
  body=$(jq -nc \
    --arg n "$name" \
    --arg t "$tag_id" \
    --arg c "$content" \
    '{
      name: $n,
      applied_tags: [$t],
      message: { content: $c },
      auto_archive_duration: 1440
    }')
  discord_curl_with_retry POST \
    "https://discord.com/api/v10/channels/${forum_id}/threads" \
    "$body"
}

# forum thread starter message 본문 PATCH.
# Discord 사양: forum thread 의 starter message id == thread id.
# PATCH /channels/{thread_id}/messages/{thread_id}.
forum_edit_starter() {
  local thread_id="$1"
  local content="$2"
  local body
  body=$(jq -nc --arg c "$content" '{content: $c}')
  discord_curl_with_retry PATCH \
    "https://discord.com/api/v10/channels/${thread_id}/messages/${thread_id}" \
    "$body"
}

# forum thread applied_tags 만 PATCH — PATCH /channels/{thread_id}.
# 다른 thread 속성 (name 등) 은 건드리지 않음.
forum_retag_thread() {
  local thread_id="$1"
  local tag_id="$2"
  local body
  body=$(jq -nc --arg t "$tag_id" '{applied_tags: [$t]}')
  discord_curl_with_retry PATCH \
    "https://discord.com/api/v10/channels/${thread_id}" \
    "$body"
}

# forum 안 active thread 검색 — 정확한 이름 match.
# GET /guilds/{guild_id}/threads/active 응답에서 parent_id == forum_id + name == query 인
# thread 의 id 반환. archive 된 thread 는 검색 대상 아님 (archive 시 백로그
# 의도와 어긋남 — 새로 만들면 OK).
#
# Discord REST: GET /guilds/{guild_id}/threads/active — 호출자가 guild_id 알아야 함.
# guild_id 는 .env DISCORD_GUILD_ID 또는 GET /channels/{forum_id} 응답의 guild_id.
# 본 함수는 guild_id 가 비어 있으면 forum_id 채널 fetch 로 추출.
#
# 인자: forum_id thread_name
# stdout: thread_id (active, name match) 또는 빈 문자열.
# 종료코드: 항상 0 (caller 가 빈 문자열로 판정).
forum_find_active_thread_by_name() {
  local forum_id="$1"
  local target_name="$2"
  local guild_id="$DISCORD_GUILD_ID_VALUE"

  if [[ -z "$guild_id" ]]; then
    # forum channel fetch 로 guild_id 추출.
    local ch_response ch_status ch_payload
    ch_response=$(curl -sS -X GET \
      "https://discord.com/api/v10/channels/${forum_id}" \
      -H "Authorization: Bot ${TOKEN}" \
      -w $'\n%{http_code}' 2>/dev/null || true)
    ch_status="${ch_response##*$'\n'}"
    ch_payload="${ch_response%$'\n'*}"
    if [[ "$ch_status" =~ ^2[0-9][0-9]$ ]]; then
      guild_id=$(printf '%s' "$ch_payload" | jq -r '.guild_id // empty')
    fi
  fi

  if [[ -z "$guild_id" || "$guild_id" == "null" ]]; then
    echo "discord-reply.sh: forum_find_active_thread_by_name — guild_id 추출 실패 (forum=$forum_id)" >&2
    printf ''
    return 0
  fi

  local response status payload
  response=$(curl -sS -X GET \
    "https://discord.com/api/v10/guilds/${guild_id}/threads/active" \
    -H "Authorization: Bot ${TOKEN}" \
    -w $'\n%{http_code}' 2>/dev/null || true)
  status="${response##*$'\n'}"
  payload="${response%$'\n'*}"

  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "discord-reply.sh: forum_find_active_thread_by_name — guild threads 조회 실패 (status=$status)" >&2
    printf ''
    return 0
  fi

  printf '%s' "$payload" \
    | jq -r --arg pid "$forum_id" --arg n "$target_name" \
        '.threads[]? | select(.parent_id == $pid and .name == $n) | .id' \
    | head -1
}

# ─── mode 실행 ────────────────────────────────────────────────────────────────

case "$MODE" in
  reply)
    # 본답 모드: 자동 leading ZWSP(U+200B) + \n prepend (#921, 2026-05-24).
    # 이유: jq escape 가 leading/trailing \n strip 해서 ack 메시지와 본답
    # 메시지가 Discord 채널에서 시각적으로 붙어 보이는 문제 영구 해결.
    # ZWSP 는 invisible character — visual padding 없이 빈 줄 효과를 보장.
    # ack / thread 모드는 짧은 단발성 push 라 미적용.
    MSG=$'​\n'"$MSG"

    # #946: 사용자 메시지에 reply (답장) 형태로 push.
    # bot.py 가 on_message 시 atomic write 한 LAST_USER_MSG_ID_FILE 에서
    # message_id 를 읽어 Discord REST `message_reference` 에 포함.
    # 파일 부재 / 빈 값 / 비숫자 → graceful standalone push (legacy 호환).
    # --no-reply flag 시에도 standalone.
    REPLY_TO_ID=$(resolve_reply_to_id)
    PAYLOAD=$(build_reply_payload "$MSG" "$REPLY_TO_ID")
    post_channel_message "$PAYLOAD"
    ;;

  ack)
    # #960: ack 도 사용자 메시지에 reply (답장) 형태로 push — 어떤 메시지에
    # 대한 ack 인지 시각적 식별. bare body 모드와 동일한 헬퍼 공유.
    REPLY_TO_ID=$(resolve_reply_to_id)
    # 1) ack 메시지 push.
    PAYLOAD=$(build_reply_payload "$MSG" "$REPLY_TO_ID")
    ACK_RESPONSE=$(post_channel_message "$PAYLOAD")
    MSG_ID=$(echo "$ACK_RESPONSE" | jq -r '.id // empty')
    if [[ -z "$MSG_ID" ]]; then
      echo "discord-reply.sh: ack push 실패 (message_id 누락)" >&2
      echo "$ACK_RESPONSE" >&2
      exit 1
    fi

    # 2) thread 이름 = ack 첫 30자 + ISO timestamp 짧은 형식 (HHMMSS).
    #    멀티라인 ack 는 첫 줄만, 너무 길면 30자에서 잘라 ellipsis.
    SHORT=$(printf '%s' "$MSG" | tr '\n' ' ' | cut -c1-${THREAD_NAME_MAX_LEN})
    TS_SHORT=$(date -u +%H%M%S)
    THREAD_NAME="${SHORT} ${TS_SHORT}"

    # 3) thread 생성.
    THREAD_RESPONSE=$(start_thread_from_message "$MSG_ID" "$THREAD_NAME")
    NEW_THREAD_ID=$(echo "$THREAD_RESPONSE" | jq -r '.id // empty')
    if [[ -z "$NEW_THREAD_ID" ]]; then
      # thread 생성 실패해도 ack push 자체는 성공. stdout 빈 줄 + stderr 에 사유.
      echo "discord-reply.sh: thread 생성 실패 (ack 자체는 push 됨, msg_id=${MSG_ID})" >&2
      echo "$THREAD_RESPONSE" >&2
      exit 0
    fi

    # 4) 현재 thread 파일에 1줄 저장 (helper 가 다음 turn 에 환경변수 잃어도 복구).
    #    동시 --ack 호출 race 회피를 위해 mktemp+mv atomic write (#911 G-5).
    atomic_write_thread_file "$NEW_THREAD_ID" "$HELPER_THREAD_FILE"

    # 4b) #1021 — sub-agent launch passthrough 파일도 함께 atomic write.
    #     helper 본체가 sub-agent launch 직전 본 mode 를 호출하므로 결과 thread
    #     를 launch 전용 파일에도 저장 → sub-agent 의 `--auto-thread` 가 env
    #     없어도 파일에서 read. helper LLM hallucination 우회의 핵심 단계.
    atomic_write_thread_file "$NEW_THREAD_ID" "$LAUNCH_THREAD_FILE"

    # 5) stdout 으로 thread id 만 출력.
    printf '%s\n' "$NEW_THREAD_ID"
    ;;

  thread)
    PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')
    post_thread_message "$THREAD_ID" "$PAYLOAD"
    ;;

  auto-thread)
    # thread_id resolve chain (#947 helper 자동 활용 + #1021 launch passthrough).
    # 우선순위 (높음 → 낮음):
    #   1. LAUNCH_THREAD_ID env (sub-agent 가 부모 launch prompt 에서 inherit)
    #   2. LAUNCH_THREAD_FILE (~/.mobruji/last-launch-thread.txt — #1021 helper
    #      본체가 sub-agent launch 직전 atomic write)
    #   3. HELPER_THREAD_FILE (~/.mobruji/helper-current-thread.txt — helper
    #      turn-level thread, 기존 fallback)
    # 각 소스 snowflake 검증 → 실패 시 다음 fallback. 모두 실패면 graceful
    # skip (exit 0, stderr warning — helper turn 안 깨지게).
    AUTO_THREAD_ID=""
    AUTO_THREAD_SOURCE=""

    # 1) LAUNCH_THREAD_ID env (sub-agent inherit).
    if [[ -n "${LAUNCH_THREAD_ID:-}" ]]; then
      if AUTO_THREAD_ID=$(validate_snowflake "$LAUNCH_THREAD_ID" "LAUNCH_THREAD_ID env"); then
        AUTO_THREAD_SOURCE="LAUNCH_THREAD_ID env"
      else
        AUTO_THREAD_ID=""
      fi
    fi

    # 2) LAUNCH_THREAD_FILE (helper 본체가 launch 직전 write — #1021).
    if [[ -z "$AUTO_THREAD_ID" && -r "$LAUNCH_THREAD_FILE" ]]; then
      LAUNCH_RAW=$(head -1 "$LAUNCH_THREAD_FILE" 2>/dev/null | tr -d '\r\n' | tr -d ' ')
      if [[ -n "$LAUNCH_RAW" ]]; then
        if AUTO_THREAD_ID=$(validate_snowflake "$LAUNCH_RAW" "$LAUNCH_THREAD_FILE"); then
          AUTO_THREAD_SOURCE="$LAUNCH_THREAD_FILE"
        else
          AUTO_THREAD_ID=""
        fi
      fi
    fi

    # 3) HELPER_THREAD_FILE (helper turn-level fallback).
    #    기존 호환을 위해 snowflake 검증을 적용하지 않고 raw 값을 그대로 사용.
    #    (회귀 가드: test_helper_ux.test_auto_thread_mode_does_not_include_message_reference)
    if [[ -z "$AUTO_THREAD_ID" ]]; then
      if [[ ! -f "$HELPER_THREAD_FILE" ]]; then
        echo "discord-reply.sh: $HELPER_THREAD_FILE 없음 — auto-thread skip" >&2
        exit 0
      fi
      HELPER_RAW=$(head -1 "$HELPER_THREAD_FILE" | tr -d '\r\n' | tr -d ' ')
      if [[ -z "$HELPER_RAW" ]]; then
        echo "discord-reply.sh: $HELPER_THREAD_FILE 비어 있음 — auto-thread skip" >&2
        exit 0
      fi
      AUTO_THREAD_ID="$HELPER_RAW"
      AUTO_THREAD_SOURCE="$HELPER_THREAD_FILE"
    fi

    PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')
    # post_thread_message 의 retry wrapper 가 4xx 면 1 반환. thread 만료 / 삭제
    # 시 Discord 가 404 — helper turn 깨지지 않게 stderr warning + exit 0 으로
    # graceful 처리.
    if ! post_thread_message "$AUTO_THREAD_ID" "$PAYLOAD" >/dev/null; then
      echo "discord-reply.sh: auto-thread push 실패 (thread_id=$AUTO_THREAD_ID, source=$AUTO_THREAD_SOURCE, 만료/삭제 추정) — skip" >&2
      exit 0
    fi
    ;;

  forum-post)
    # #17 forum 전환 (2026-05-24).
    # forum_env → *_FORUM_ID resolve → forum 의 available_tags 에서 tag_id lookup →
    # POST /channels/{forum_id}/threads. stdout 으로 생성된 thread_id 출력.
    FORUM_ID=$(resolve_forum_id "$FORUM_ENV")
    TAG_ID=$(resolve_forum_tag_id "$FORUM_ID" "$FORUM_TAG")
    THREAD_RESPONSE=$(forum_create_thread "$FORUM_ID" "$FORUM_TITLE" "$TAG_ID" "$MSG")
    NEW_THREAD_ID=$(echo "$THREAD_RESPONSE" | jq -r '.id // empty')
    if [[ -z "$NEW_THREAD_ID" ]]; then
      echo "discord-reply.sh: forum thread 생성 실패 (forum=$FORUM_ENV, title=$FORUM_TITLE)" >&2
      echo "$THREAD_RESPONSE" >&2
      exit 1
    fi
    printf '%s\n' "$NEW_THREAD_ID"
    ;;

  forum-comment)
    # #17 — forum thread 안 일반 댓글.
    # POST /channels/{thread_id}/messages — post_thread_message 재사용 가능.
    PAYLOAD=$(jq -nc --arg c "$MSG" '{content: $c}')
    post_thread_message "$THREAD_ID" "$PAYLOAD"
    ;;

  forum-edit)
    # #17 — forum thread starter message 본문 PATCH.
    forum_edit_starter "$THREAD_ID" "$MSG"
    ;;

  forum-retag)
    # #17 — forum thread applied_tags 갱신.
    # FORUM_ENV (forum 이름) 로 forum_id resolve → tag_name → tag_id → PATCH thread.
    FORUM_ID=$(resolve_forum_id "$FORUM_ENV")
    TAG_ID=$(resolve_forum_tag_id "$FORUM_ID" "$FORUM_TAG")
    forum_retag_thread "$THREAD_ID" "$TAG_ID"
    ;;

  cycle-backlog-upsert)
    # 2026-05-26 — cycle (be/fe/rev/plan) 백로그 forum thread upsert.
    #
    # 절차:
    #   1) cycle forum id resolve (FORUM_ENV → *_FORUM_ID).
    #   2) thread name = "[BACKLOG] <cycle>" 검색.
    #   3) 있으면 starter message PATCH (forum_edit_starter).
    #      없으면 forum_create_thread 호출 — tag 는 "대기" / "백로그" / 첫 available
    #      순으로 graceful fallback.
    #   4) stdout 으로 thread_id 출력.
    BACKLOG_FORUM_ID=$(resolve_forum_id "$FORUM_ENV")
    BACKLOG_THREAD_NAME="[BACKLOG] ${FORUM_ENV}"

    EXISTING_THREAD_ID=$(forum_find_active_thread_by_name "$BACKLOG_FORUM_ID" "$BACKLOG_THREAD_NAME")

    if [[ -n "$EXISTING_THREAD_ID" ]]; then
      # 기존 thread starter PATCH.
      forum_edit_starter "$EXISTING_THREAD_ID" "$MSG" >/dev/null
      printf '%s\n' "$EXISTING_THREAD_ID"
    else
      # 신규 thread 생성. tag 후보: "대기" → "백로그" → 첫 available.
      BACKLOG_TAG_ID=""
      for candidate_tag in "대기" "백로그" "todo" "open"; do
        if BACKLOG_TAG_ID=$(resolve_forum_tag_id "$BACKLOG_FORUM_ID" "$candidate_tag" 2>/dev/null); then
          break
        fi
        BACKLOG_TAG_ID=""
      done
      if [[ -z "$BACKLOG_TAG_ID" ]]; then
        # 첫 available tag 로 fallback — GET /channels/{forum_id} 응답 첫 entry.
        FORUM_INFO=$(curl -sS -X GET \
          "https://discord.com/api/v10/channels/${BACKLOG_FORUM_ID}" \
          -H "Authorization: Bot ${TOKEN}" 2>/dev/null || true)
        BACKLOG_TAG_ID=$(printf '%s' "$FORUM_INFO" \
          | jq -r '.available_tags[0]?.id // empty')
        if [[ -z "$BACKLOG_TAG_ID" ]]; then
          echo "discord-reply.sh: forum $FORUM_ENV 에 available_tags 가 없음 — thread 생성 시 tag 미부착 시도" >&2
        fi
      fi
      # forum_create_thread 는 tag_id 필수 — 빈 값이면 별 페이로드 (tag 미부착).
      if [[ -n "$BACKLOG_TAG_ID" ]]; then
        NEW_BACKLOG_RESP=$(forum_create_thread "$BACKLOG_FORUM_ID" "$BACKLOG_THREAD_NAME" "$BACKLOG_TAG_ID" "$MSG")
      else
        # tag 없이 — body 에서 applied_tags 제외.
        BODY=$(jq -nc --arg n "$BACKLOG_THREAD_NAME" --arg c "$MSG" \
          '{ name: $n, message: { content: $c }, auto_archive_duration: 10080 }')
        NEW_BACKLOG_RESP=$(discord_curl_with_retry POST \
          "https://discord.com/api/v10/channels/${BACKLOG_FORUM_ID}/threads" \
          "$BODY")
      fi
      NEW_BACKLOG_ID=$(echo "$NEW_BACKLOG_RESP" | jq -r '.id // empty')
      if [[ -z "$NEW_BACKLOG_ID" ]]; then
        echo "discord-reply.sh: cycle-backlog-upsert — thread 생성 실패 (forum=$FORUM_ENV)" >&2
        echo "$NEW_BACKLOG_RESP" >&2
        exit 1
      fi
      printf '%s\n' "$NEW_BACKLOG_ID"
    fi
    ;;
esac
