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
#           Forum adapter (PR #1155, 2026-05-27): cycle channel 이 Discord forum
#           (type=15) 으로 전환된 경우 자동 감지해 forum_create_thread 경로로
#           fallback. title 은 본문 첫 줄 80자 truncate, tag 는 forum 의
#           available_tags 에서 fallback chain 자동 선택. text channel (type=0/5)
#           은 기존 동작 유지. 학습 부담 없이 cycle channel 이 forum 으로 전환되어도
#           기존 호출 패턴 (`--cycle-channel <ws> "<본문>"`) 그대로 동작.
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
#       discord-reply.sh --forum-post-auto-tag <forum_env> "<title>" "<body>"   (#1106)
#       discord-reply.sh --forum-comment <thread_id> "<body>"
#       discord-reply.sh --forum-edit <thread_id> "<new_body>"
#       discord-reply.sh --forum-retag <thread_id> <forum_env> "<new_tag_name>"
#         → forum_env: directive | be | fe | rev | plan → 해당 *_FORUM_ID env lookup.
#         → tag_name: 해당 forum 의 available_tags name (예: "대기" / "진행" / "완료").
#           GET /channels/{forum_id} 응답의 available_tags 에서 name → id 변환.
#         → --forum-post: POST /channels/{forum_id}/threads (name + applied_tags +
#           message.content). 생성된 thread_id 를 stdout 으로 출력 (호출자가
#           후속 --forum-comment / --forum-edit / --forum-retag 에 사용).
#         → --forum-post-auto-tag (#1106, 2026-05-26): tag 인자 없이 forum 의
#           available_tags 에서 fallback chain 자동 선택 후 forum_create_thread
#           호출. fallback 우선순위:
#             1) "PR 진행 중"  2) "진행"  3) "spec"  4) "stage 1"  5) "대기"
#             6) 그 외 첫 번째 available_tags 항목
#           available_tags 가 비어 있으면 applied_tags 미포함으로 thread 생성
#           (Discord 가 default tag 없이 post 허용 — forum 설정에 따름).
#           의도: agent-launch-wrapper.sh 가 cycle 별 tag 매핑을 hardcode 하지
#           않고 forum 운영자가 tag 를 자유롭게 rename 해도 작동 (사용자 P0
#           영구 fix — cycle channel silence, 사고: BE/FE/REV/PLAN cycle forum
#           4개 모두 0 메시지였던 root cause 가 wrapper 의 text-channel API
#           400 reject).
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
#   7) writing marker — 답 작성 시작 시점 가시화 (#1095, 2026-05-26):
#       discord-reply.sh --writing-marker <user_msg_id>
#         → 사용자 메시지에 ✍️ reaction PUT (bot self) + 채널에 POST /typing
#           (Discord "입력 중" indicator, 10초 동안 표시).
#         → helper 본체가 답 작성 시작 시점에 호출 (또는 bare body 자동 hook 이
#           대행 — 아래 자동화 항목 참고).
#       discord-reply.sh --writing-done <user_msg_id>
#         → 사용자 메시지에서 ✍️ reaction DELETE (bot self). typing 은 자동 종료
#           (Discord 가 10초 후 또는 다음 메시지 push 시 정리).
#         → helper 본체가 본답 push 직후 호출 (또는 bare body 자동 hook 대행).
#
#       자동화 (default):
#         → bare body 본답 push 호출 시 자동으로 (a) writing-marker 동등 동작
#           수행 → (b) 메시지 push → (c) ✍️ remove. helper 본체가 명시 호출
#           안 해도 "답 작성 시작/완료" 가시화가 강제 강화 (학습 의존 ↓).
#         → 토글: `BOT_WRITING_REACTION_ENABLED=0` 시 reaction add/remove skip.
#                `BOT_TYPING_INDICATOR_ENABLED=0` 시 POST /typing skip.
#                자동화 자체 disable 시 둘 다 0.
#         → emoji override: `BOT_WRITING_REACTION_EMOJI` env (default=✍️ —
#           URL-encoded `%E2%9C%8D%EF%B8%8F`).
#         → target msg id 는 `resolve_reply_to_id` (#987) 우선순위 체인 재사용 —
#           --reply-to / HELPER_TURN_TARGET_MSG_ID / helper-current-target.txt /
#           helper-queue.jsonl / last-user-msg-id.txt. 모든 fallback 실패 시
#           graceful skip (helper turn 안 깨짐).
#         → --status-channel / --channel / --cycle-channel / --no-reply / thread
#           모드 등 reply 가 명시적으로 disable 된 경우 자동 hook 도 skip
#           (target msg 가 없거나 다른 채널이므로).
#
#       관련 룰: CLAUDE.md §12-3 (helper 본답 push 직전/직후 자동 hook 강제 강화)
#
#   4b) choices — 사용자에게 선택지 prompt + reaction tap 응답
#       (spec: docs/features/discord-reaction-choice-input.md):
#       discord-reply.sh --choices "<질문>" "<opt1>" "<opt2>" [<opt3> ... <opt10>]
#         → 동작:
#             1) 본문 build: 질문 + \n\n + "1️⃣ opt1\n2️⃣ opt2\n..." 형태.
#             2) post_channel_message (사용자 마지막 메시지 reply 형태로 — 시각적 연결).
#             3) 응답 message_id 에 1️⃣–🔟 keycap reaction pre-attach.
#             4) ~/.mobruji/choice-prompts.jsonl 에 register row append (event log).
#             5) stdout = bot message_id (호출자 trace 가능).
#         → 사용자가 reaction tap → bot.py on_raw_reaction_add 가 lookup → synthetic
#           user msg ("[choice N/total] label") 로 helper 에 forwarding. 폴링 X —
#           Discord Gateway native event push.
#         → 최대 옵션 = 10 (1️⃣–🔟 keycap 한계). 옵션 < 2 시 dispatch error.
#         → mode toggle: helper 가 USER_MODE=ASK 인 경우에만 호출 권장 (AUTO 기본).
#           helper-turn-start.sh 가 ===USER_MODE:AUTO|ASK=== marker emit.
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

# spec: docs/features/discord-reaction-choice-input.md — `--choices` mode.
# 사용자가 1️⃣–🔟 keycap reaction 으로 선택지 응답. bot.py on_raw_reaction_add 가
# choice-prompts.jsonl 의 register entry 를 lookup → synthetic msg 로 helper 전달.
CHOICE_PROMPTS_FILE="${CHOICE_PROMPTS_FILE:-${HOME:-/tmp}/.mobruji/choice-prompts.jsonl}"
# Unicode keycap display (사람 가독성, 메시지 본문 build 시 사용).
CHOICE_KEYCAPS_DISPLAY=(
  "1️⃣" "2️⃣" "3️⃣" "4️⃣" "5️⃣" "6️⃣" "7️⃣" "8️⃣" "9️⃣" "🔟"
)
# URL-encoded UTF-8 byte sequences (Discord REST `PUT /reactions/{emoji}/@me`).
# 1-9: digit + VS16 (U+FE0F=EF B8 8F) + keycap (U+20E3=E2 83 A3). 🔟 = U+1F51F.
CHOICE_KEYCAPS_URLENC=(
  "1%EF%B8%8F%E2%83%A3"
  "2%EF%B8%8F%E2%83%A3"
  "3%EF%B8%8F%E2%83%A3"
  "4%EF%B8%8F%E2%83%A3"
  "5%EF%B8%8F%E2%83%A3"
  "6%EF%B8%8F%E2%83%A3"
  "7%EF%B8%8F%E2%83%A3"
  "8%EF%B8%8F%E2%83%A3"
  "9%EF%B8%8F%E2%83%A3"
  "%F0%9F%94%9F"
)

# Writing marker / typing indicator 설정 (#1095, 2026-05-26).
#
# 두 가지 호출 경로:
#   A) 명시 호출 — helper 본체가 `--writing-marker <user_msg_id>` / `--writing-done
#      <user_msg_id>` 직접 호출. 이 경우 본 env 의 _ENABLED 토글은 reaction/typing
#      세부 부분만 disable (default 둘 다 ENABLED=1).
#   B) bare body 자동 hook — bare body 본답 push 호출 시 자동으로 (a) ✍️ reaction +
#      typing → push → (c) ✍️ remove 수행. helper 본체가 명시 호출을 까먹어도 강제
#      가시화. 2026-05-29 PR #1252 §10-1 결정에 따라 default ON 전환 →
#      사용자 directive 2026-05-29 (#1294) 로 즉시 OFF 재전환. opt-in 시
#      `BOT_WRITING_AUTO_HOOK_ENABLED=1` 명시 부여 (systemd / launchd unit env).
#
# default emoji: ✍️ (U+270D + U+FE0F variation selector) — URL-encoded
# `%E2%9C%8D%EF%B8%8F`. Discord API 는 unicode emoji 를 URL-encoded 형태로 받음.
# `:name:id` (custom emoji) 도 지원 가능하지만 본 PR scope 외.
BOT_WRITING_REACTION_EMOJI="${BOT_WRITING_REACTION_EMOJI:-%E2%9C%8D%EF%B8%8F}"
BOT_WRITING_REACTION_ENABLED="${BOT_WRITING_REACTION_ENABLED:-1}"
BOT_TYPING_INDICATOR_ENABLED="${BOT_TYPING_INDICATOR_ENABLED:-1}"
# bare body 자동 hook — default OFF (사용자 directive 2026-05-29 #1294, PR #1252
# default ON 결정 1일 만에 revert. 노이즈 판단).
# 명시 호출 (--writing-marker / --writing-done) 은 본 env 와 무관하게 항상 동작.
# helper 본체가 명시 호출을 까먹어도 fallback hook 으로 ✍️ 잔존 방지하던 경로 제거.
# opt-in 시 `BOT_WRITING_AUTO_HOOK_ENABLED=1` 명시 (systemd/launchd unit env 또는 ad-hoc).
BOT_WRITING_AUTO_HOOK_ENABLED="${BOT_WRITING_AUTO_HOOK_ENABLED:-0}"

# Discord API retry 설정 (#911 G-6).
# 429 (Rate Limited) / 5xx (Server Error) 응답을 곧이곧대로 무시하지 않고
# Discord 가 권장하는 retry_after 또는 exponential backoff 로 재시도한다.
# 환경변수로 외부화 — 테스트에서 max=1, base=0 으로 강제해 fast fail 가능.
DISCORD_RETRY_MAX="${DISCORD_RETRY_MAX:-3}"
DISCORD_RETRY_BASE_SEC="${DISCORD_RETRY_BASE_SEC:-1}"

# ─── 본문 길이 자동 chunk split (#1121, 2026-05-26) ───────────────────────────
#
# 배경 (사용자 P1 박제):
#   Discord REST API 가 message ``content`` 길이 2000자 (UTF-16 code unit) 를
#   초과하면 50035 (Invalid Form Body) 로 reject. 기존 discord-reply.sh 는
#   split/retry 없이 그대로 PUSH → helper 본답이 silent fail (사용자 채널에 안
#   나타남). 사용자 가시성 ↓ + 본답 유실.
#
# 본 PR 보정:
#   1. `split_long_message` 함수가 MSG 를 `DISCORD_CHUNK_LEN` 단위로 자른다.
#      - newline 경계 우선 (해당 chunk 안 마지막 newline 위치).
#      - newline 없으면 word boundary (space 마지막) 시도.
#      - 그것도 없으면 hard cut.
#      - 각 chunk 앞에 `[N/M]` 마커 prepend (M ≥ 2 일 때만).
#   2. `apply_chunked_push <send-func> <ctx>` — 1+개 chunk 를 순차 전송. 마지막
#      chunk 의 REST 응답 (JSON) 을 stdout 으로 반환 (기존 호출자 호환 — msg_id
#      lookup 가 마지막 chunk 의 id 로 동작).
#   3. `discord_curl_with_retry` 가 50035 (Invalid Form Body) 응답 감지 시
#      stderr 명시 ERROR log — 기존엔 4xx 일반 처리로 묻혀 silent.
#
# 한도:
#   - Discord content 최대 2000. 안전 cap 1900 (chunk 마커 `[N/M]` 자리 + UTF-16
#     scale 여유 — 한글은 UTF-16 1 code unit 이지만 surrogate pair 도 1 char 로
#     count, 안전 여유 100).
DISCORD_CHUNK_LEN="${DISCORD_CHUNK_LEN:-1900}"

# split_long_message <body> — chunked array 를 줄 단위 stdout 출력.
#
# 출력 형식: 각 chunk 를 NUL (0x00) 으로 구분된 string sequence 로 stdout.
# 호출자: `while IFS= read -r -d '' chunk; do ...; done < <(split_long_message "$body")`.
#
# 단일 chunk 면 마커 prepend 없이 본문 그대로 1개 emit.
# 다중 chunk 면 각 chunk 앞에 `[1/N] ` ~ `[N/N] ` prepend.
split_long_message() {
  local body="$1"
  local cap="$DISCORD_CHUNK_LEN"
  local total_len=${#body}

  # short path — cap 이하면 그대로 1 chunk.
  if (( total_len <= cap )); then
    printf '%s\0' "$body"
    return 0
  fi

  # split 단계: greedy — cap 내 마지막 newline > 마지막 space > hard cut.
  local chunks=()
  local remaining="$body"
  while (( ${#remaining} > cap )); do
    local head="${remaining:0:cap}"
    local cut_at=$cap

    # newline 우선.
    local nl_pos="${head%$'\n'*}"
    if [[ "$nl_pos" != "$head" ]]; then
      cut_at=$(( ${#nl_pos} + 1 ))  # newline 자체 포함.
    else
      # space 차선.
      local sp_pos="${head% *}"
      if [[ "$sp_pos" != "$head" ]]; then
        cut_at=$(( ${#sp_pos} + 1 ))  # space 자체 포함.
      fi
      # 둘 다 없으면 hard cut (cut_at=cap 유지).
    fi

    chunks+=("${remaining:0:cut_at}")
    remaining="${remaining:cut_at}"
  done
  if [[ -n "$remaining" ]]; then
    chunks+=("$remaining")
  fi

  local total_chunks=${#chunks[@]}
  local i=0
  for chunk in "${chunks[@]}"; do
    i=$((i + 1))
    # 마커 자리는 cap 안에 이미 여유로 확보 (cap=1900, marker 길이 < 12).
    printf '[%d/%d] %s\0' "$i" "$total_chunks" "$chunk"
  done
  return 0
}

# detect_invalid_form_body <response_payload> — Discord 50035 응답을 stderr 에
# ERROR log. discord_curl_with_retry 가 4xx return 직전 호출 (silent 차단).
detect_invalid_form_body() {
  local payload="$1"
  local code
  code=$(printf '%s' "$payload" | jq -r '.code // empty' 2>/dev/null || true)
  if [[ "$code" == "50035" ]]; then
    local len_msg
    len_msg=$(printf '%s' "$payload" \
      | jq -r '.errors.content._errors[0].message // empty' 2>/dev/null \
      || true)
    echo "discord-reply.sh: ERROR Discord 50035 (Invalid Form Body) — content 길이 또는 형식 위반. ${len_msg:-(detail 없음)}" >&2
    echo "discord-reply.sh: hint — DISCORD_CHUNK_LEN=${DISCORD_CHUNK_LEN} 조정 또는 split_long_message 진입 path 확인." >&2
  fi
}

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
# forum adapter (PR #1155 spec impl, 2026-05-27):
#   --cycle-channel <name> 호출 시 resolve 한 CHANNEL 이 Discord forum (type=15)
#   채널이면 bare body push 가 50008 (Cannot send messages in a non-text channel)
#   로 silent fail. 본 플래그가 1 이면 mode 실행 단계에서 reply / auto-thread
#   대신 forum_create_thread 경로로 fallback.
#   설정: --cycle-channel 처리 직후 detect_cycle_channel_type 가 GET /channels/{id}
#   호출로 type 확인 → forum (15) 또는 media (16) 시 1.
CYCLE_FORUM_FALLBACK=0
# Forum mode 인자 (#17, 2026-05-24).
# --forum-post / --forum-retag 의 forum_env: directive | be | fe | rev | plan.
# --forum-post 의 title (thread name) + tag (available_tags name).
FORUM_ENV=""
FORUM_TITLE=""
FORUM_TAG=""

if [[ $# -eq 0 ]]; then
  echo "discord-reply.sh: 인자 부족 — 사용법:" >&2
  echo "  discord-reply.sh \"<메시지>\"" >&2
  echo "  discord-reply.sh [--no-reply] [--reply-to <id>] [--status-channel | --channel <id> | --cycle-channel <be|fe|rev|plan> (forum 자동 감지)] \"<메시지>\"" >&2
  echo "  discord-reply.sh --ack \"<ack 문구>\"" >&2
  echo "  discord-reply.sh --thread <id> \"<진행 줄>\"" >&2
  echo "  discord-reply.sh --auto-ack-thread \"<ack 문구>\"" >&2
  echo "  discord-reply.sh --auto-thread \"<진행 줄>\"" >&2
  echo "  discord-reply.sh --forum-post <directive|be|fe|rev|plan> \"<title>\" \"<tag>\" \"<body>\"" >&2
  echo "  discord-reply.sh --forum-post-auto-tag <directive|be|fe|rev|plan> \"<title>\" \"<body>\"" >&2
  echo "  discord-reply.sh --forum-comment <thread_id> \"<body>\"" >&2
  echo "  discord-reply.sh --forum-edit <thread_id> \"<new_body>\"" >&2
  echo "  discord-reply.sh --forum-retag <thread_id> <directive|be|fe|rev|plan> \"<new_tag>\"" >&2
  echo "  discord-reply.sh --update-status <thread_id> \"<status>\" [pr_url]" >&2
  echo "  discord-reply.sh --forum-state-dump <directive|be|fe|rev|plan>" >&2
  echo "  discord-reply.sh --writing-marker <user_msg_id>" >&2
  echo "  discord-reply.sh --writing-done <user_msg_id>" >&2
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
  --forum-post-auto-tag)
    # #1106 (2026-05-26) — cycle channel silence permanent fix.
    # --forum-post-auto-tag <forum_env> "<title>" "<body>"
    # tag 인자 없이 forum 의 available_tags 에서 fallback chain 자동 선택.
    # agent-launch-wrapper.sh 가 본 mode 를 호출 — wrapper 가 cycle 별 tag 매핑
    # 을 hardcode 하지 않고 forum 운영자가 tag rename 해도 작동.
    MODE="forum-post-auto-tag"
    if [[ $# -lt 4 ]]; then
      echo "discord-reply.sh: --forum-post-auto-tag <forum_env> \"<title>\" \"<body>\" 형태로 입력해주세요" >&2
      exit 1
    fi
    FORUM_ENV="$2"
    FORUM_TITLE="$3"
    MSG="$4"
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
  --update-status)
    # PR #1129 directive-board event-driven (impl PR 2) — directive forum thread
    # starter message 본문 atomic 갱신. directive_status.sh 가 in_progress /
    # completed 전이 시 호출.
    #
    # 형식: --update-status <thread_id> "<status>" [pr_url]
    # 동작 (#1419 내용 보존형): forum_get_starter 로 기존 본문을 읽어
    #       directive_starter_status.py 로 📋 진행 상태줄 + 갱신 줄 + (pr_url 시)
    #       🔖 관련 PR 줄만 수술 갱신 후 forum_edit_starter 로 PATCH.
    #       → 📌 제목·💬 요약·🔖 관련 등 기존 내용 보존.
    # 과거: 본문을 "상태/갱신" 2~3 줄로 통째 대체해 사용자/helper 작성 내용이
    #       매 전이마다 소멸하는 사고가 있었음 (2026-05-31 사용자 정정).
    MODE="update-status"
    if [[ $# -lt 3 ]]; then
      echo "discord-reply.sh: --update-status <thread_id> \"<status>\" [pr_url] 형태로 입력해주세요" >&2
      exit 1
    fi
    THREAD_ID="$2"
    FORUM_TAG="$3"  # FORUM_TAG 변수를 status 문자열 임시 저장 — 신규 변수 추가 없이 재사용.
    FORUM_TITLE="${4:-}"  # PR URL (선택). FORUM_TITLE 재사용.
    MSG="(update-status placeholder)"
    NO_REPLY=1
    ;;
  --forum-state-dump)
    # PR #1129 directive-board event-driven (impl PR 2) — debug helper.
    # forum 의 모든 active thread + applied tag name 을 jsonl 형식으로 stdout.
    # wrapper (helper-turn-start.sh / agent-launch-wrapper.sh) 가 jsonl entry status
    # 와 forum tag 비교해 mismatch detect.
    #
    # 형식: --forum-state-dump <directive|be|fe|rev|plan>
    # 출력 (jsonl, 1 줄 per thread):
    #   {"thread_id":"<id>","name":"<thread name>","tags":["<tag name>", ...]}
    # 한계: GET /guilds/{guild_id}/threads/active 가 active thread 만 반환 — archive
    #       된 thread 는 누락 (의도된 동작 — 누락 detect 는 최근 N=20 active entry 만 대상).
    MODE="forum-state-dump"
    if [[ $# -lt 2 ]]; then
      echo "discord-reply.sh: --forum-state-dump <directive|be|fe|rev|plan> 형태로 입력해주세요" >&2
      exit 1
    fi
    FORUM_ENV="$2"
    MSG="(forum-state-dump placeholder)"
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
  --writing-marker|--writing-done)
    # #1095 (2026-05-26) — 답 작성 시작/완료 가시화.
    # --writing-marker <user_msg_id>:
    #   1) ✍️ reaction PUT (bot self) — 사용자 메시지에 답 작성 시작 표시.
    #   2) POST /channels/{channel_id}/typing — Discord "입력 중" indicator 10초.
    # --writing-done <user_msg_id>:
    #   1) ✍️ reaction DELETE (bot self) — 본답 push 완료 시 정리.
    if [[ $# -lt 2 ]]; then
      echo "discord-reply.sh: $1 뒤에 user_msg_id 가 필요합니다" >&2
      exit 1
    fi
    if [[ "$1" == "--writing-marker" ]]; then
      MODE="writing-marker"
    else
      MODE="writing-done"
    fi
    # user_msg_id snowflake 검증 — 짧은 정수 / 비숫자 / 빈 값 거부.
    # validate_snowflake 가 함수 정의 이후라 line 호출 — set -e 회피 위해 `||`.
    # 본 검증은 dispatch 시점이 아닌 실행 시점에 다시 수행 (validate_snowflake
    # 가 헬퍼 섹션에 정의되기 때문).
    THREAD_ID="$2"  # user_msg_id 임시 저장. mode 실행에서 사용.
    MSG="(writing-marker placeholder)"  # MSG 빈값 가드 우회.
    NO_REPLY=1  # reaction/typing 호출은 message_reference 무관.
    ;;
  --choices)
    # spec: docs/features/discord-reaction-choice-input.md
    # --choices "<질문>" "<opt1>" "<opt2>" ... [<opt10>]
    #   1) 본문 build (질문 + 1️⃣ opt1 + 2️⃣ opt2 + ...) → post_channel_message.
    #   2) 응답 message_id 에 1️⃣–🔟 keycap reaction pre-attach (사용자 tap 대상).
    #   3) ~/.mobruji/choice-prompts.jsonl 에 register entry append.
    #   4) stdout = bot message_id (호출자 trace 가능).
    # bot.py on_raw_reaction_add 가 lookup → synthetic user msg 로 helper 에 전달.
    if [[ $# -lt 4 ]]; then
      echo "discord-reply.sh: --choices <질문> <opt1> <opt2> [<opt3> ... <opt10>] (최소 2 옵션) 형태로 입력해주세요" >&2
      exit 64
    fi
    MODE="choices"
    CHOICES_QUESTION="$2"
    shift 2
    CHOICES_OPTS=("$@")
    if [[ ${#CHOICES_OPTS[@]} -gt 10 ]]; then
      echo "discord-reply.sh: --choices — 옵션은 최대 10개 (Discord keycap 0️⃣–🔟)" >&2
      exit 64
    fi
    MSG="(choices placeholder)"  # MSG 빈값 가드 우회 — 실행은 CHOICES_QUESTION 사용.
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

    # 4xx 등 — retry 무의미. silent fail 차단을 위해 50035 명시 log (#1121).
    detect_invalid_form_body "$payload"
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

# 본문 길이 ≥ DISCORD_CHUNK_LEN 시 자동 chunk split + 순차 push (#1121).
#
# 인자:
#   $1 = MSG (raw text — payload 빌더가 후속 jq escape)
#   $2 = REPLY_TO_ID (빈 문자열 = standalone, 첫 chunk 에만 message_reference 적용)
#
# stdout: 마지막 chunk 의 REST 응답 (기존 단일 push 호출자가 `.id` lookup 하는
# 호환성 유지 — 마지막 message_id 가 다음 thread 생성의 anchor 등으로 쓰임).
# stderr: chunk count + 진행 1줄.
#
# 실패 정책:
#   - 임의 chunk push 실패 시 stderr 에 chunk index 명시 + 함수는 마지막
#     성공/실패 응답을 그대로 stdout. set -e 호환 위해 명시적 return.
post_channel_message_chunked() {
  local msg="$1"
  local reply_to_id="$2"
  local last_response=""
  local chunk_idx=0

  # split_long_message 가 NUL 구분으로 chunk emit.
  # 단일 chunk (cap 이하) 인 경우도 normalize — 1 chunk 만 emit.
  while IFS= read -r -d '' chunk; do
    chunk_idx=$((chunk_idx + 1))
    local payload
    if (( chunk_idx == 1 )); then
      # 첫 chunk 만 reply (message_reference). 후속 chunk 는 standalone — 같은
      # reference 를 재사용하면 Discord 가 각 chunk 마다 reply 표시 → 사용자
      # 채널 사이드바 가시성 ↓.
      payload=$(build_reply_payload "$chunk" "$reply_to_id")
    else
      payload=$(jq -nc --arg c "$chunk" '{content: $c}')
    fi
    last_response=$(post_channel_message "$payload") || true
  done < <(split_long_message "$msg")

  if (( chunk_idx > 1 )); then
    echo "discord-reply.sh: long body chunk split — ${chunk_idx} 메시지로 분할 push (#1121)" >&2
  fi
  printf '%s' "$last_response"
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

# 본문 길이 ≥ DISCORD_CHUNK_LEN 시 thread 안 chunk split + 순차 push (#1121).
#
# 인자: $1 = thread_id, $2 = MSG (raw text)
# stdout: 마지막 chunk 응답.
# return: 임의 chunk 실패 시 last call 의 return 그대로.
post_thread_message_chunked() {
  local thread_id="$1"
  local msg="$2"
  local last_response=""
  local chunk_idx=0
  local rc=0

  while IFS= read -r -d '' chunk; do
    chunk_idx=$((chunk_idx + 1))
    local payload
    payload=$(jq -nc --arg c "$chunk" '{content: $c}')
    if ! last_response=$(post_thread_message "$thread_id" "$payload"); then
      rc=1
    fi
  done < <(split_long_message "$msg")

  if (( chunk_idx > 1 )); then
    echo "discord-reply.sh: long body chunk split (thread) — ${chunk_idx} 메시지로 분할 push (#1121)" >&2
  fi
  printf '%s' "$last_response"
  return "$rc"
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

# ─── writing marker helpers (#1095, 2026-05-26) ──────────────────────────────

# Discord PUT reaction (bot self) — PUT /channels/{cid}/messages/{mid}/reactions/{emoji}/@me.
# 204 No Content 응답 → 성공. 4xx 면 stderr warning + exit 0 (graceful, helper turn
# 안 깨짐).
#
# 인자: user_msg_id (snowflake).
# discord_curl_with_retry 와 별도로 처리 — reaction endpoint 는 body 없음 (PUT with
# empty body). 직접 curl 호출.
#
# emoji 는 이미 URL-encoded 상태로 $BOT_WRITING_REACTION_EMOJI 에 들어있다.
reaction_add() {
  local message_id="$1"
  # 두 번째 인자 = URL-encoded emoji. 미지정 시 ✍️ default — #1095 writing-marker 호환.
  # --choices mode 가 1️⃣–🔟 keycap 을 같은 helper 로 부착 (spec #1126 후속).
  local emoji="${2:-$BOT_WRITING_REACTION_EMOJI}"
  local response status
  response=$(curl -sS -X PUT \
    "https://discord.com/api/v10/channels/${CHANNEL}/messages/${message_id}/reactions/${emoji}/@me" \
    -H "Authorization: Bot ${TOKEN}" \
    -H "Content-Length: 0" \
    -w $'\n%{http_code}' 2>/dev/null || true)
  status="${response##*$'\n'}"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "discord-reply.sh: reaction add 실패 (msg=${message_id}, status=${status}) — skip" >&2
    return 1
  fi
  return 0
}

# Discord DELETE reaction (bot self).
reaction_remove() {
  local message_id="$1"
  local emoji="$BOT_WRITING_REACTION_EMOJI"
  local response status
  response=$(curl -sS -X DELETE \
    "https://discord.com/api/v10/channels/${CHANNEL}/messages/${message_id}/reactions/${emoji}/@me" \
    -H "Authorization: Bot ${TOKEN}" \
    -w $'\n%{http_code}' 2>/dev/null || true)
  status="${response##*$'\n'}"
  # 404 (이미 제거됨) 도 graceful 처리.
  if [[ ! "$status" =~ ^2[0-9][0-9]$ && "$status" != "404" ]]; then
    echo "discord-reply.sh: reaction remove 실패 (msg=${message_id}, status=${status}) — skip" >&2
    return 1
  fi
  return 0
}

# Discord POST /typing — 10초 동안 채널에 "입력 중" indicator 표시.
# 응답: 204 No Content. body 없음. 본 호출은 retry 안 함 — 실패해도 본답 push 가
# 더 중요.
typing_indicator() {
  local response status
  response=$(curl -sS -X POST \
    "https://discord.com/api/v10/channels/${CHANNEL}/typing" \
    -H "Authorization: Bot ${TOKEN}" \
    -H "Content-Length: 0" \
    -w $'\n%{http_code}' 2>/dev/null || true)
  status="${response##*$'\n'}"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "discord-reply.sh: typing indicator 실패 (status=${status}) — skip" >&2
    return 1
  fi
  return 0
}

# bare body 본답 push 자동 hook — 본답 push 전 ✍️ + typing, push 후 ✍️ remove.
# 호출자 (helper 본체) 의 명시 호출 없이도 답 작성 시작/완료 가시화 강제.
#
# 동작:
#   - target msg id resolve (REPLY_TO_ID 가 이미 본답 mode 에서 계산됨).
#   - target 없으면 (NO_REPLY=1 / 없는 fallback) skip — reaction 걸 대상이 없음.
#   - BOT_WRITING_REACTION_ENABLED=0 시 reaction skip / TYPING_INDICATOR_ENABLED=0
#     시 typing skip. 둘 다 0 이면 자동 hook 자체 skip.
#   - 모든 호출은 graceful (return 1 무시) — 본답 push 자체는 항상 진행.
writing_hook_start() {
  local target_msg_id="$1"
  # bare body 본답 mode 의 자동 hook — BOT_WRITING_AUTO_HOOK_ENABLED=0 시 skip
  # (default OFF, 회귀 안전).
  if [[ "$BOT_WRITING_AUTO_HOOK_ENABLED" != "1" ]]; then
    return 0
  fi
  if [[ -z "$target_msg_id" ]]; then
    return 0
  fi
  if [[ "$BOT_WRITING_REACTION_ENABLED" == "1" ]]; then
    reaction_add "$target_msg_id" || true
  fi
  if [[ "$BOT_TYPING_INDICATOR_ENABLED" == "1" ]]; then
    typing_indicator || true
  fi
  return 0
}

writing_hook_end() {
  local target_msg_id="$1"
  if [[ "$BOT_WRITING_AUTO_HOOK_ENABLED" != "1" ]]; then
    return 0
  fi
  if [[ -z "$target_msg_id" ]]; then
    return 0
  fi
  if [[ "$BOT_WRITING_REACTION_ENABLED" == "1" ]]; then
    reaction_remove "$target_msg_id" || true
  fi
  return 0
}

# ─── cycle-channel forum adapter (PR #1155 spec impl, 2026-05-27) ────────────

# `--cycle-channel <name>` resolve 후 CHANNEL 의 Discord channel type 을 1회 조회.
# nmae 가 cycle channel 을 text → forum 으로 전환하면서 기존 cycle-channel 호출이
# 50008 (Cannot send messages in a non-text channel) 으로 silent fail 한 사고
# (2026-05-26 plan PR #1153 audit 박제) 의 영구 가드.
#
# 동작:
#   - GET /channels/{CHANNEL} 호출 → response.type 확인.
#   - text (type=0/5) → 기존 reply mode 유지.
#   - forum (15) / media (16) → CYCLE_FORUM_FALLBACK=1 설정 + stderr 1줄 log.
#   - fetch 실패 (4xx/5xx/네트워크) → best-effort 로 기존 path 유지 + stderr warning.
#
# 호출 조건: CYCLE_CHANNEL 비어 있지 않을 때만 (즉 --cycle-channel 명시적 사용).
# 명시적 --channel / --status-channel / forum mode 들은 호출자가 channel 형태를
# 알고 있다고 가정 — 자동 adapter 적용 안 함.
#
# cache 정책: per-process. 본 함수는 1회 invocation 동안 한 번만 호출되므로
# 별도 cache 변수 불필요.
detect_cycle_channel_type() {
  local channel_id="$1"
  local response status payload channel_type
  response=$(curl -sS -X GET \
    "https://discord.com/api/v10/channels/${channel_id}" \
    -H "Authorization: Bot ${TOKEN}" \
    -w $'\n%{http_code}' 2>/dev/null || true)
  status="${response##*$'\n'}"
  payload="${response%$'\n'*}"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "discord-reply.sh: --cycle-channel ${CYCLE_CHANNEL} — channel type 조회 실패 (status=$status), 기존 text channel 경로로 best-effort 시도" >&2
    return 0
  fi
  channel_type=$(printf '%s' "$payload" | jq -r '.type // empty' 2>/dev/null || true)
  case "$channel_type" in
    0|5)
      # text / announcement — 기존 동작 유지.
      return 0
      ;;
    15|16)
      # GUILD_FORUM / GUILD_MEDIA — bare body push 불가, forum thread 생성 경로로 fallback.
      CYCLE_FORUM_FALLBACK=1
      echo "discord-reply.sh: --cycle-channel ${CYCLE_CHANNEL} — forum (type=${channel_type}) detected, fallback to forum-post mode" >&2
      return 0
      ;;
    *)
      echo "discord-reply.sh: --cycle-channel ${CYCLE_CHANNEL} — 지원하지 않는 channel type=${channel_type}, 기존 text channel 경로로 best-effort 시도" >&2
      return 0
      ;;
  esac
}

# body 첫 줄 80자 truncate → forum thread title 추론 (spec §9 결정 로그).
# 호출자가 `--cycle-channel` 호출 시 title 인자를 추가하지 않게 — 기존 sub-agent /
# nmae prompt 광범위 변경 회피.
derive_forum_title_from_body() {
  local body="$1"
  local first_line truncated
  # 첫 줄 (newline 까지) 만 추출 + control char 제거.
  first_line=$(printf '%s' "$body" | head -1 | tr -d '\r\n')
  # 80 자 truncate (UTF-8 byte 단위 안 안전, character 단위는 cut -c 가 처리).
  truncated=$(printf '%s' "$first_line" | cut -c1-80)
  if [[ -z "$truncated" ]]; then
    # 본문 첫 줄이 비어 있으면 ISO timestamp 로 fallback.
    truncated="cycle-channel post $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  fi
  printf '%s' "$truncated"
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
# tag_id 가 빈 문자열이면 applied_tags 미포함 (#1106 — forum 의 default tag
# 설정에 따름 / available_tags 가 비어 있는 경우 graceful).
forum_create_thread() {
  local forum_id="$1"
  local name="$2"
  local tag_id="$3"
  local content="$4"
  local body
  if [[ -n "$tag_id" ]]; then
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
  else
    body=$(jq -nc \
      --arg n "$name" \
      --arg c "$content" \
      '{
        name: $n,
        message: { content: $c },
        auto_archive_duration: 1440
      }')
  fi
  discord_curl_with_retry POST \
    "https://discord.com/api/v10/channels/${forum_id}/threads" \
    "$body"
}

# forum 의 available_tags 에서 fallback chain 자동 선택 (#1106, 2026-05-26).
# 우선순위 (높음 → 낮음):
#   1) "PR 진행 중"  2) "진행"  3) "spec"  4) "stage 1"  5) "대기"
#   6) 그 외 첫 번째 available_tags 항목 (tag 가 존재하는 경우)
# available_tags 가 비어 있으면 빈 문자열 반환 (호출자가 tag 없이 thread 생성).
# stdout: tag_id (or empty), stderr: 선택된 tag name (운영자 가시).
resolve_forum_tag_id_auto() {
  local forum_id="$1"
  local response status payload
  response=$(curl -sS -X GET \
    "https://discord.com/api/v10/channels/${forum_id}" \
    -H "Authorization: Bot ${TOKEN}" \
    -w $'\n%{http_code}' 2>/dev/null || true)
  status="${response##*$'\n'}"
  payload="${response%$'\n'*}"
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    echo "discord-reply.sh: forum channel fetch 실패 (forum_id=$forum_id, status=$status)" >&2
    echo "$payload" >&2
    return 1
  fi
  # fallback chain — 첫 매칭 entry 의 id 반환.
  local picked_id picked_name
  picked_id=$(printf '%s' "$payload" | jq -r '
    (.available_tags // []) as $tags
    | (
        ($tags[]? | select(.name == "PR 진행 중") | .id),
        ($tags[]? | select(.name == "진행") | .id),
        ($tags[]? | select(.name == "spec") | .id),
        ($tags[]? | select(.name == "stage 1") | .id),
        ($tags[]? | select(.name == "대기") | .id),
        ($tags[0]?.id // empty)
      )
    | select(. != null and . != "")
  ' 2>/dev/null | head -1)
  if [[ -n "$picked_id" && "$picked_id" != "null" ]]; then
    picked_name=$(printf '%s' "$payload" | jq -r --arg i "$picked_id" \
      '(.available_tags // [])[] | select(.id == $i) | .name' \
      | head -1)
    echo "discord-reply.sh: --forum-post-auto-tag — tag \"${picked_name}\" 선택 (forum_id=$forum_id)" >&2
    printf '%s' "$picked_id"
  else
    echo "discord-reply.sh: --forum-post-auto-tag — forum (forum_id=$forum_id) 에 available_tags 가 비어 있음, tag 없이 thread 생성" >&2
    printf ''
  fi
  return 0
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

# (#1419) forum thread starter message 의 현재 본문(content) 만 stdout 으로 반환.
# forum thread 의 starter message id == thread id (Discord 사양).
# update-status 가 본문을 통째로 덮어쓰지 않고 "내용 보존형"으로 갱신하려면
# 먼저 기존 본문을 읽어야 한다 (과거엔 getter 부재로 통째 PATCH → 내용 소멸 사고).
# 실패 (4xx/5xx / thread 삭제) 시 빈 문자열 — 호출자가 fallback.
forum_get_starter() {
  local thread_id="$1"
  local resp status payload
  resp=$(curl -sS -X GET \
    "https://discord.com/api/v10/channels/${thread_id}/messages/${thread_id}" \
    -H "Authorization: Bot ${TOKEN}" \
    -w $'\n%{http_code}' 2>/dev/null || true)
  status="${resp##*$'\n'}"
  payload="${resp%$'\n'*}"
  if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
    printf '%s' "$payload" | jq -r '.content // ""'
  fi
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

# PR #1155 spec impl (2026-05-27) — `--cycle-channel` forum adapter.
# CYCLE_CHANNEL 가 설정돼 있으면 (즉 사용자가 --cycle-channel 명시) CHANNEL 의
# Discord type 1회 조회. forum (15) 또는 media (16) 채널이면 CYCLE_FORUM_FALLBACK
# 가 1 로 설정되어 아래 reply mode 분기에서 forum_create_thread 경로로 우회.
# 기존 text channel 은 detect 함수가 no-op (graceful).
if [[ -n "$CYCLE_CHANNEL" ]]; then
  detect_cycle_channel_type "$CHANNEL"
fi

case "$MODE" in
  reply)
    # PR #1155 spec impl (2026-05-27) — `--cycle-channel` forum adapter.
    # CYCLE_FORUM_FALLBACK=1 이면 cycle channel 이 Discord forum 으로 전환된 상태
    # → bare body POST messages 가 50008 fail. forum_create_thread 경로로 우회.
    # title 은 body 첫 줄 80자 truncate, tag 는 forum 의 available_tags 에서
    # fallback chain 자동 선택 (--forum-post-auto-tag 와 동일 로직 재사용).
    if [[ "$CYCLE_FORUM_FALLBACK" -eq 1 ]]; then
      FORUM_AUTO_TITLE=$(derive_forum_title_from_body "$MSG")
      # CHANNEL 은 이미 cycle forum id 로 resolve 됐음 — forum_create_thread 가 직접 사용.
      FORUM_AUTO_TAG_ID=$(resolve_forum_tag_id_auto "$CHANNEL")
      FORUM_AUTO_RESPONSE=$(forum_create_thread "$CHANNEL" "$FORUM_AUTO_TITLE" "$FORUM_AUTO_TAG_ID" "$MSG")
      FORUM_AUTO_THREAD_ID=$(echo "$FORUM_AUTO_RESPONSE" | jq -r '.id // empty')
      if [[ -z "$FORUM_AUTO_THREAD_ID" ]]; then
        echo "discord-reply.sh: --cycle-channel ${CYCLE_CHANNEL} forum fallback — thread 생성 실패" >&2
        echo "$FORUM_AUTO_RESPONSE" >&2
        exit 1
      fi
      # stdout 으로 thread id 출력 (호출자가 후속 forum-comment 등에서 재사용 가능).
      printf '%s\n' "$FORUM_AUTO_THREAD_ID"
      exit 0
    fi

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

    # #1095 (2026-05-26): 본답 push 직전 ✍️ reaction + typing — 답 작성 시작 가시화.
    # NO_REPLY=1 (--no-reply / --status-channel / --channel / --cycle-channel 등)
    # 시 REPLY_TO_ID 가 빈 문자열 → writing_hook_start 도 skip (대상 없음).
    writing_hook_start "$REPLY_TO_ID"

    # #1121 (2026-05-26): 본문 길이 ≥ DISCORD_CHUNK_LEN 자동 chunk split + 순차 push.
    # 단일 chunk (cap 이하) 인 경우 단일 push 동작과 동일 (split_long_message 가 1개
    # emit). 다중 chunk 시 첫 chunk 만 reply, 나머지는 standalone — 사이드바 가시성 ↑.
    MAIN_PUSH_RESPONSE=$(post_channel_message_chunked "$MSG" "$REPLY_TO_ID")
    printf '%s' "$MAIN_PUSH_RESPONSE"

    # 2026-05-29 (PR helper-control-emoji-auto-attach + reply-target fix): 본답
    # push 직후 ❓ control emoji 자동 부착. 사용자 정정 (2026-05-29):
    # "stop button은 내가 보낸 메세지에 붙는게 맞는거같은데" — ⏹ 는 사용자 명령
    # 메시지 (bot.py on_message 가 부착), ❓ 는 helper 답 메시지 (본 path).
    # bot.py on_raw_reaction_add 가 ❓ → 사유 설명 요청 처리.
    # 환경 변수 MOBRUJI_CONTROL_EMOJI=0 시 skip (디버깅).
    # graceful: reaction add 실패는 본답 push 자체 결과에 영향 없음.
    if [[ "${MOBRUJI_CONTROL_EMOJI:-1}" == "1" ]]; then
      MAIN_PUSH_MSG_ID=$(printf '%s' "$MAIN_PUSH_RESPONSE" | jq -r '.id // empty' 2>/dev/null || echo "")
      if [[ -n "$MAIN_PUSH_MSG_ID" ]]; then
        # ❓ U+2753 = E2 9D 93
        reaction_add "$MAIN_PUSH_MSG_ID" "%E2%9D%93" || true
      fi
    fi

    # #1095: 본답 push 직후 ✍️ remove — 답 작성 완료 가시화.
    # typing 은 Discord 자체 10초 timeout + 메시지 push 후 자동 종료.
    writing_hook_end "$REPLY_TO_ID"
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
    # #1121: 본문 길이 ≥ DISCORD_CHUNK_LEN 자동 split + 순차 push (단일 chunk 시 동일).
    post_thread_message_chunked "$THREAD_ID" "$MSG"
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

    # #1121: 본문 길이 ≥ DISCORD_CHUNK_LEN 자동 split. post_thread_message_chunked 가
    # 단일 chunk 시 단일 push 동작 동일. retry wrapper 가 4xx 면 1 반환 — graceful.
    if ! post_thread_message_chunked "$AUTO_THREAD_ID" "$MSG" >/dev/null; then
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

  forum-post-auto-tag)
    # #1106 (2026-05-26) — cycle channel silence permanent fix.
    # tag 인자 없이 forum 의 available_tags 에서 fallback chain 자동 선택 후
    # forum_create_thread 호출. agent-launch-wrapper.sh 가 본 mode 를 호출.
    FORUM_ID=$(resolve_forum_id "$FORUM_ENV")
    # resolve_forum_tag_id_auto 는 available_tags 비어 있으면 빈 문자열 반환 (graceful).
    TAG_ID=$(resolve_forum_tag_id_auto "$FORUM_ID")
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
    # POST /channels/{thread_id}/messages — post_thread_message_chunked 재사용.
    # #1121: 본문 길이 ≥ DISCORD_CHUNK_LEN 자동 split + 순차 push.
    post_thread_message_chunked "$THREAD_ID" "$MSG"
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

  writing-marker)
    # #1095 (2026-05-26) — 답 작성 시작 가시화: ✍️ reaction PUT + POST /typing.
    # THREAD_ID 변수에 user_msg_id 가 들어있음 (mode dispatch 단계 임시 저장).
    USER_MSG_ID="$THREAD_ID"
    # snowflake 검증 (실행 시점) — validate_snowflake 헬퍼 사용.
    if ! USER_MSG_ID=$(validate_snowflake "$USER_MSG_ID" "--writing-marker user_msg_id"); then
      echo "discord-reply.sh: --writing-marker — user_msg_id 가 snowflake 형식이 아닙니다" >&2
      exit 1
    fi
    if [[ "$BOT_WRITING_REACTION_ENABLED" == "1" ]]; then
      reaction_add "$USER_MSG_ID" || true
    fi
    if [[ "$BOT_TYPING_INDICATOR_ENABLED" == "1" ]]; then
      typing_indicator || true
    fi
    ;;

  writing-done)
    # #1095 (2026-05-26) — 답 작성 완료: ✍️ reaction DELETE.
    USER_MSG_ID="$THREAD_ID"
    if ! USER_MSG_ID=$(validate_snowflake "$USER_MSG_ID" "--writing-done user_msg_id"); then
      echo "discord-reply.sh: --writing-done — user_msg_id 가 snowflake 형식이 아닙니다" >&2
      exit 1
    fi
    if [[ "$BOT_WRITING_REACTION_ENABLED" == "1" ]]; then
      reaction_remove "$USER_MSG_ID" || true
    fi
    ;;

  choices)
    # spec: docs/features/discord-reaction-choice-input.md
    # helper / nmae 가 사용자에게 선택지 prompt 던질 때 호출. event-driven —
    # 사용자가 1️⃣–🔟 keycap reaction tap 하면 bot.py on_raw_reaction_add 가
    # synthetic msg 로 helper 에 전달 (폴링 X, Discord Gateway native push).
    # 1) 본문 build: 질문 + \n\n + "1️⃣ opt1\n2️⃣ opt2\n...".
    CHOICES_BODY="$CHOICES_QUESTION"$'\n'
    for i in "${!CHOICES_OPTS[@]}"; do
      CHOICES_BODY+=$'\n'"${CHOICE_KEYCAPS_DISPLAY[$i]} ${CHOICES_OPTS[$i]}"
    done

    # 2) 메시지 post — 사용자 마지막 메시지에 reply 형태 (일관성 + 시각적 연결).
    CHOICES_REPLY_TO=$(resolve_reply_to_id)
    CHOICES_PAYLOAD=$(build_reply_payload "$CHOICES_BODY" "$CHOICES_REPLY_TO")
    CHOICES_RESPONSE=$(post_channel_message "$CHOICES_PAYLOAD")
    CHOICES_MSG_ID=$(printf '%s' "$CHOICES_RESPONSE" | jq -r '.id // empty')
    if [[ -z "$CHOICES_MSG_ID" ]]; then
      echo "discord-reply.sh: --choices — message post 실패" >&2
      echo "$CHOICES_RESPONSE" >&2
      exit 1
    fi

    # 3) keycap reaction pre-attach (1 ~ N). 실패 한 건은 graceful (whole bail X).
    for i in "${!CHOICES_OPTS[@]}"; do
      reaction_add "$CHOICES_MSG_ID" "${CHOICE_KEYCAPS_URLENC[$i]}" || true
    done

    # 4) choice-prompts.jsonl register row append (append-only event log).
    #    bot.py lookup_choice_prompt 가 이 row 를 읽어 사용자 reaction 매칭.
    mkdir -p "$(dirname "$CHOICE_PROMPTS_FILE")"
    CHOICES_JSON=$(printf '%s\n' "${CHOICES_OPTS[@]}" | jq -R . | jq -sc .)
    CHOICES_TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    jq -nc \
      --arg mid "$CHOICES_MSG_ID" \
      --arg cid "$CHANNEL" \
      --argjson choices "$CHOICES_JSON" \
      --arg ts "$CHOICES_TS" \
      '{event:"register", message_id:$mid, channel_id:$cid, choices:$choices, ts:$ts}' \
      >> "$CHOICE_PROMPTS_FILE"

    # 5) stdout = bot message_id (호출자 trace 가능).
    printf '%s\n' "$CHOICES_MSG_ID"
    ;;

  update-status)
    # PR #1129 directive-board event-driven (impl PR 2).
    # forum thread starter message 본문을 status / KST timestamp / (선택) PR URL 로
    # atomic 갱신. directive_status.sh 가 in_progress / completed 전이 시 호출.
    # FORUM_TAG = status 문자열 / FORUM_TITLE = PR URL (선택).
    STATUS_TEXT="$FORUM_TAG"
    PR_URL_OPT="$FORUM_TITLE"
    # KST timestamp (jsonl entry 의 last_updated_kst 와 동일 형식).
    UPDATE_TS_KST="$(TZ='Asia/Seoul' date '+%Y-%m-%d %H:%M KST')"
    # (#1419) 내용 보존형 갱신 — 과거엔 본문을 "상태/갱신" 2~3 줄로 통째 PATCH 해
    # 📌 제목·💬 요약·🔖 관련 등 사용자/helper 작성 내용이 소멸하는 사고가 있었음
    # (2026-05-31 사용자 정정 "내용 다 죽이고 완료라고 하면 뭐해"). 이제 기존 starter
    # 본문을 읽어 📋 진행 상태줄 + 갱신 줄만 수술 갱신 (directive_starter_status.py).
    UPDATE_SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    CUR_STARTER_CONTENT="$(forum_get_starter "$THREAD_ID")"
    NEW_STARTER_BODY="$(STATUS="$STATUS_TEXT" TS="$UPDATE_TS_KST" PR="$PR_URL_OPT" \
      python3 "${UPDATE_SELF_DIR}/directive_starter_status.py" <<<"$CUR_STARTER_CONTENT")"
    if [[ -z "$NEW_STARTER_BODY" ]]; then
      # transform 실패 (python 부재 등) 시 최소 안전 fallback — 단, 기존 본문이
      # 있으면 보존(append), 없을 때만 상태 2~3 줄.
      if [[ -n "$CUR_STARTER_CONTENT" ]]; then
        NEW_STARTER_BODY="${CUR_STARTER_CONTENT}"$'\n\n'"**상태**: ${STATUS_TEXT} · ${UPDATE_TS_KST}"
        [[ -n "$PR_URL_OPT" ]] && NEW_STARTER_BODY="${NEW_STARTER_BODY}"$'\n'"**PR**: ${PR_URL_OPT}"
      else
        NEW_STARTER_BODY="$(printf '**상태**: %s\n**갱신**: %s' "$STATUS_TEXT" "$UPDATE_TS_KST")"
        [[ -n "$PR_URL_OPT" ]] && NEW_STARTER_BODY="${NEW_STARTER_BODY}"$'\n'"**PR**: ${PR_URL_OPT}"
      fi
    fi
    forum_edit_starter "$THREAD_ID" "$NEW_STARTER_BODY"
    ;;

  forum-state-dump)
    # PR #1129 directive-board event-driven (impl PR 2) — debug / mismatch detect.
    # forum 의 모든 active thread + tag 정보를 jsonl 형식으로 stdout.
    DUMP_FORUM_ID=$(resolve_forum_id "$FORUM_ENV")
    DUMP_GUILD_ID="$DISCORD_GUILD_ID_VALUE"
    if [[ -z "$DUMP_GUILD_ID" ]]; then
      # forum channel fetch 로 guild_id 추출 (forum_find_active_thread_by_name 패턴).
      CH_RESPONSE=$(curl -sS -X GET \
        "https://discord.com/api/v10/channels/${DUMP_FORUM_ID}" \
        -H "Authorization: Bot ${TOKEN}" \
        -w $'\n%{http_code}' 2>/dev/null || true)
      CH_STATUS="${CH_RESPONSE##*$'\n'}"
      CH_PAYLOAD="${CH_RESPONSE%$'\n'*}"
      if [[ "$CH_STATUS" =~ ^2[0-9][0-9]$ ]]; then
        DUMP_GUILD_ID=$(printf '%s' "$CH_PAYLOAD" | jq -r '.guild_id // empty')
      fi
    fi
    if [[ -z "$DUMP_GUILD_ID" || "$DUMP_GUILD_ID" == "null" ]]; then
      echo "discord-reply.sh: --forum-state-dump — guild_id 추출 실패 (forum=$FORUM_ENV)" >&2
      exit 1
    fi
    # forum 의 available_tags name → id 매핑을 먼저 가져와 thread.applied_tags
    # (id 배열) 를 name 배열로 변환. -w 로 status code separator 명시 — fake curl
    # 응답 형식 (body\nstatus) 과 일치.
    TAG_MAP_RESPONSE=$(curl -sS -X GET \
      "https://discord.com/api/v10/channels/${DUMP_FORUM_ID}" \
      -H "Authorization: Bot ${TOKEN}" \
      -w $'\n%{http_code}' 2>/dev/null || true)
    TAG_MAP_STATUS="${TAG_MAP_RESPONSE##*$'\n'}"
    TAG_MAP_PAYLOAD="${TAG_MAP_RESPONSE%$'\n'*}"
    if [[ "$TAG_MAP_STATUS" =~ ^2[0-9][0-9]$ ]]; then
      TAG_MAP_JSON=$(printf '%s' "$TAG_MAP_PAYLOAD" \
        | jq -c '(.available_tags // []) | map({(.id): .name}) | add // {}')
    else
      TAG_MAP_JSON='{}'
    fi
    # guild active threads 조회.
    DUMP_RESPONSE=$(curl -sS -X GET \
      "https://discord.com/api/v10/guilds/${DUMP_GUILD_ID}/threads/active" \
      -H "Authorization: Bot ${TOKEN}" \
      -w $'\n%{http_code}' 2>/dev/null || true)
    DUMP_STATUS="${DUMP_RESPONSE##*$'\n'}"
    DUMP_PAYLOAD="${DUMP_RESPONSE%$'\n'*}"
    if [[ ! "$DUMP_STATUS" =~ ^2[0-9][0-9]$ ]]; then
      echo "discord-reply.sh: --forum-state-dump — guild threads 조회 실패 (status=$DUMP_STATUS)" >&2
      echo "$DUMP_PAYLOAD" >&2
      exit 1
    fi
    # jsonl 출력: {thread_id, name, tags: [tag name array]}.
    printf '%s' "$DUMP_PAYLOAD" \
      | jq -c --arg pid "$DUMP_FORUM_ID" --argjson m "$TAG_MAP_JSON" \
          '.threads[]? | select(.parent_id == $pid) | {
             thread_id: .id,
             name: .name,
             tags: ((.applied_tags // []) | map($m[.] // null) | map(select(. != null)))
           }'
    ;;
esac
