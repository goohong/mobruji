"""Mobruji Discord daemon — 단순화본 (이슈 #807).

기능 (사용자 결정 2026-05-23 큰 단순화 위임):
1. Discord Gateway WebSocket 24/7 유지.
2. 지정 채널(MOBRUJI_CHANNEL_ID) + 화이트리스트(ALLOWED_USER_IDS) 사용자 메시지를
   `tmux send-keys -t helper:0.0 "<msg>" Enter` 로 helper pane 에 단순 routing.
3. cycle-status.json digest cron — `~/.mobruji/cycle-status.json` 을 일정 주기로
   읽어 4 워크트리(be/fe/rev/plan) 진행/최근 한 줄씩 Discord 알림 채널에 push.

helper 응답은 helper 측 신규 script `~/.mobruji/discord-reply.sh "<msg>"` 가
직접 Discord REST API 로 push 합니다 (bot.py 안에서 응답 watcher 가동 안 함).

운영 가이드와 셋업 절차는 같은 디렉토리의 README.md 참고.
spec: docs/features/discord-driven-mobruji.md
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import shlex
import sqlite3
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Final
from zoneinfo import ZoneInfo

import discord
from dotenv import load_dotenv

from claude_usage_tracker import (
    DEFAULT_DAILY_LIMIT as CLAUDE_DAILY_TOKEN_LIMIT_DEFAULT,
    DEFAULT_PROJECTS_ROOT as CLAUDE_USAGE_PROJECTS_ROOT_DEFAULT,
    DEFAULT_STATE_PATH as CLAUDE_USAGE_STATE_PATH_DEFAULT,
    DEFAULT_WEEKLY_LIMIT as CLAUDE_WEEKLY_TOKEN_LIMIT_DEFAULT,
    format_threshold_message,
    read_state as claude_usage_read_state,
    scan_usage as claude_usage_scan,
    update_and_detect_thresholds as claude_usage_update_and_detect,
    write_state as claude_usage_write_state,
)
from directive_board_sync import (
    DEFAULT_JSONL_PATH as DIRECTIVE_BOARD_JSONL_PATH_DEFAULT,
    DEFAULT_STATE_PATH as DIRECTIVE_BOARD_STATE_PATH_DEFAULT,
    directive_board_summary,
)
from directive_detect import (
    DIRECTIVE_CLASSES,
    append_detect_entry,
    detect_mismatch as directive_detect_mismatch,
    format_mismatch_push as directive_format_mismatch_push,
    make_detect_entry,
)

LOG_FORMAT: Final[str] = "%(asctime)s %(levelname)s %(name)s :: %(message)s"
INBOX_PATH: Final[Path] = Path(__file__).resolve().parent / "inbox.jsonl"
INBOX_FILE_MODE: Final[int] = 0o600

# spec: docs/features/discord-reaction-choice-input.md
# `--choices` mode 의 register/consume event log + user mode toggle.
CHOICE_PROMPTS_PATH: Final[Path] = Path.home() / ".mobruji" / "choice-prompts.jsonl"
USER_MODE_PATH: Final[Path] = Path.home() / ".mobruji" / "user-mode.txt"
USER_MODE_DEFAULT: Final[str] = "AUTO"
USER_MODE_VALID: Final[tuple[str, ...]] = ("AUTO", "ASK")
# spec: docs/features/directive-pushpin-registration.md
# 📌 = directive 등록 후보 marker (bot 자동 부착 + 사용자 tap = 등록 trigger).
# ✅ = directive 등록 완료 시각화.
PIN_REACTION_EMOJI: Final[str] = "📌"
PIN_REGISTERED_EMOJI: Final[str] = "✅"

# spec: helper-control + tool-visibility (2026-05-29)
# 사용자 control emoji — 사용자가 helper 답 작성 메시지에 tap 시 bot 가 동작 수행.
# 추천: minimal 2개 (자율 default 룰 보존, [[feedback-autonomous-default]]).
CONTROL_STOP_EMOJI: Final[str] = "⏹"  # SIGINT — helper claude tmux pane Ctrl-C
CONTROL_WHY_EMOJI: Final[str] = "❓"  # 다음 turn 에 helper 가 직전 작업/결정 사유 설명

# directive 적재 dialogue (dialogue_style="register") 의 3 button — 사용자
# 의제: 1️⃣/2️⃣ keycap 의 OX 가 모호 → ⭕ 등록 / ✏️ 수정 / 🗑️ 제거 로 swap.
# ✏️ click → agent 가 "어떤 점 수정?" 묻고 summary 정정 loop (max 3회).
# 🗑️ click → directive 폐기 (agent path).
REGISTER_DIALOGUE_EMOJIS: Final[list[str]] = ["⭕", "✏️", "🗑️"]
REGISTER_DIALOGUE_EMOJI_TO_IDX: Final[dict[str, int]] = {
    e: i for i, e in enumerate(REGISTER_DIALOGUE_EMOJIS)
}

# Discord keycap number emoji → 0-based index (1️⃣ = 0, 🔟 = 9).
# 1-9 = digit + VS16 + keycap (U+FE0F U+20E3). 🔟 = U+1F51F.
NUMBER_KEYCAP_TO_INDEX: Final[dict[str, int]] = {
    "1️⃣": 0,
    "2️⃣": 1,
    "3️⃣": 2,
    "4️⃣": 3,
    "5️⃣": 4,
    "6️⃣": 5,
    "7️⃣": 6,
    "8️⃣": 7,
    "9️⃣": 8,
    "\U0001f51f": 9,
}

MAX_TEXT_PREVIEW_LEN: Final[int] = 80
# inbox.jsonl 에 저장되는 사용자 메시지 본문 최대 길이 (#909 F-1).
# PII/민감 본문이 평문으로 디스크에 남는 위험을 완화 — 백업/디버깅 용도 한정.
INBOX_TEXT_MAX_LEN: Final[int] = 500
DEDUP_TTL_SECONDS: Final[int] = 24 * 60 * 60  # 24h
DEDUP_GC_INTERVAL_SECONDS: Final[int] = 60 * 60  # 1h

# bot.py 1초 generic auto-ack (#880, reaction-only #1175) — helper bash chain
# latency 시 사용자 깜깜이 해소. 사용자 메시지에 👀 emoji reaction 만 add.
# 2026-05-28 (#1175): text/both mode 제거. 별도 채팅 ack 1건이 채널 가독성을
# 떨어뜨려 reaction-only 로 단순화. 기존 BOT_AUTO_ACK_MODE / BOT_AUTO_ACK_TEXT
# 는 폐기 — `BOT_AUTO_ACK_MODE` env 가 set 돼 있어도 무시 (deprecation log 1회).
BOT_AUTO_ACK_DEFAULT_ENABLED: Final[str] = "1"
BOT_AUTO_ACK_EMOJI_DEFAULT: Final[str] = "👀"

# Secondary reaction (2026-05-26, #1080) — nmae 점유 상태를 사용자 메시지에
# emoji 로 즉시 시각화. cycle-status.json 의 4 워크트리(be/fe/rev/plan)
# in_progress 채워짐 개수로 분류합니다.
#   0 occupied → ⚡ (즉시 가능, idle)
#   1~3 occupied → ⏳ (작업 중, partial)
#   4 occupied → 🕐 (대기 큐잉, full)
# 파일 부재 / parse 실패 → silent skip (warning log).
BOT_SECONDARY_REACTION_DEFAULT_ENABLED: Final[str] = "1"
BOT_SECONDARY_REACTION_EMOJI_IDLE_DEFAULT: Final[str] = "⚡"
BOT_SECONDARY_REACTION_EMOJI_PARTIAL_DEFAULT: Final[str] = "⏳"
BOT_SECONDARY_REACTION_EMOJI_FULL_DEFAULT: Final[str] = "🕐"
NMAE_WORKTREES: Final[tuple[str, ...]] = ("be", "fe", "rev", "plan")

# reply.referenced_message forwarding (#880) — 사용자 Discord "답장" 으로 보낸 메시지가
# 어떤 메시지에 대한 답장인지 helper 가 알 수 있도록 prefix.
REPLY_CONTEXT_PREVIEW_LEN: Final[int] = 30
REPLY_CONTEXT_PREFIX_TEMPLATE: Final[str] = "[답장→ {preview}] {body}"

# helper 본답 → 사용자 메시지 reply (#946, 2026-05-24).
# bot.py 가 사용자 메시지를 helper 로 forward 할 때 그 message_id 를 file 에
# atomic 으로 기록. discord-reply.sh bare body 모드가 그 파일을 읽어
# Discord REST API payload 에 `message_reference` 를 포함시켜 자동 reply.
#
# Why: 사용자 입장에서 채널 누적 메시지 중 어떤 본문에 대한 helper 답인지
# 시각적으로 추적하기 위함. standalone 메시지는 짝짓기가 어려움.
LAST_USER_MSG_ID_PATH: Final[Path] = Path(
    "~/.mobruji/last-user-msg-id.txt"
).expanduser()
LAST_USER_MSG_ID_FILE_MODE: Final[int] = 0o600

# 2026-05-29 Phase 2.1 — agent dual write. bot.py 가 사용자 메시지 받을 때
# events 테이블 에 INSERT 추가 (legacy tmux send + inbox 도 유지). new agent
# (tools/agent/) 가 events 를 consume 해 처리. legacy 영향 0 (dual write).
# spec: tools/agent/README.md (Phase 2).
AGENT_EVENTS_DB_PATH: Final[Path] = Path("~/.mobruji/agent.sqlite").expanduser()

# 2026-05-29: helper-current-target.txt — helper-turn-start.sh 가 매 turn 시
# cp last-user-msg-id 로 freeze 하던 path. helper LLM 의 wrapper 호출 의존 →
# 호출 누락 시 옛 target 그대로 → helper 답이 옛 메시지에 reply 사고.
# bot.py 가 on_message 시 last-user-msg-id 와 동시 갱신해 학습 의존 폐기.
# spec: docs/features/helper-current-target-bot-side-write.md.
HELPER_CURRENT_TARGET_PATH: Final[Path] = Path(
    "~/.mobruji/helper-current-target.txt"
).expanduser()

# Discord snowflake 길이 가드 (#964, 2026-05-24).
# Discord snowflake = unix timestamp(42b) + worker(5b) + process(5b) + increment(12b)
# = 64bit. 2015 epoch 이후 항상 17~19 자리 양의 정수 (보수적으로 20 까지 허용).
# 짧은 정수 ("4" 등) 가 들어가면 Discord API 가 10008 (Unknown Message) 반환 →
# 채팅창에 "메시지를 불러올 수 없어요" 로 본답 reply 가 깨짐. 2026-05-24 실제
# 운영 사고 (root cause 미상 — 외부 오염 추정) 이후 write/read 양단 가드 도입.
# 정상 코드 경로 (`str(message.id)`) 로는 발생할 수 없지만 외부 오염 / 수동 echo /
# 디버깅 잔재로부터 사용자 UX 보호.
LAST_USER_MSG_ID_MIN_DIGITS: Final[int] = 17
LAST_USER_MSG_ID_MAX_DIGITS: Final[int] = 20

# digest cron 튜닝값 — cycle-status.json (사용자 룰 2026-05-23).
DEFAULT_DIGEST_INTERVAL_SECONDS: Final[int] = 300  # 5분 (#1017 사용자 — 최소 5분 1회 보장)
DIGEST_INITIAL_DELAY_SECONDS: Final[int] = 60  # boot 1분 warmup
DIGEST_HEARTBEAT_SECONDS: Final[int] = 60 * 5  # delta 없어도 5분 1회는 push (#1017 사용자 가시성)

# Discord API resilience (#911 G-6) — channel.send 시 429 / 5xx 명시적 retry.
# discord.py 가 라이브러리 차원 ratelimiter 를 가지지만 갑작스러운 5xx /
# transient HTTP error 는 그대로 raise. defense-in-depth 로 명시적 retry 추가.
DISCORD_SEND_RETRY_MAX: Final[int] = 3
DISCORD_SEND_RETRY_BASE_SEC: Final[float] = 1.0
DEFAULT_CYCLE_STATUS_PATH: Final[str] = os.path.expanduser("~/.mobruji/cycle-status.json")
# cycle counter — KST 자정 기준 sub-agent 별 누적 cycle 횟수 (사용자 요청 #996).
# update.py set-active 시 ++ 누적. digest embed 가 read 해서 한 줄 추가 push.
DEFAULT_CYCLE_COUNTER_PATH: Final[str] = os.path.expanduser(
    "~/.mobruji/cycle-counter.json"
)
CYCLE_DIGEST_WORKSPACES: Final[tuple[str, ...]] = ("be", "fe", "rev", "plan")
CYCLE_DIGEST_MAX_LINE_LEN: Final[int] = 200
# digest 본문 timestamp — 사용자 요청 #811. Discord 가 보여주는 시각이 클라이언트
# timezone 에 의존하므로 본문에 KST 명시로 한눈에 emit 시각 확인.
CYCLE_DIGEST_TZ: Final[ZoneInfo] = ZoneInfo("Asia/Seoul")
CYCLE_DIGEST_TIME_FORMAT: Final[str] = "%Y-%m-%d %H:%M KST"
# Embed 시각화 — UX 개선 #840.
# 워크트리별 역할 인지용 emoji prefix. field name 에 적용.
CYCLE_DIGEST_WORKSPACE_EMOJI: Final[dict[str, str]] = {
    "be": "🛠",
    "fe": "🎨",
    "rev": "🔍",
    "plan": "📋",
}
# Embed 색상: 모든 워크트리 idle 이면 gray (조용한 상태), 그 외 blue (활동 중).
CYCLE_DIGEST_COLOR_ACTIVE: Final[int] = 0x3498DB  # blue
CYCLE_DIGEST_COLOR_IDLE: Final[int] = 0x95A5A6  # gray
# Field value 한 줄 최대 길이 — 너무 길면 truncate (Discord embed field 1024 자
# 제한이 있지만 가독성 위해 더 엄격하게 적용).
CYCLE_DIGEST_FIELD_LINE_LEN: Final[int] = 180

# digest emoji prefix — spec: docs/features/discord-message-style.md §3.
# bot.py 단순화본은 digest 만 사용. 나머지 6종 (reply/cycle-start/cycle-end/
# alert/recovery/decision) 은 외부 import 없음 확인 후 #819 에서 제거.
MESSAGE_PREFIX: Final[dict[str, str]] = {
    "digest": "📊",
}

# Discord mention 토큰 차단 패턴 — digest 안 PR title 에 `@everyone` 등이
# 들어가도 실제 mention 알림이 발생하지 않도록 zero-width space 삽입.
MENTION_SANITIZE_PATTERNS: Final[tuple[tuple[re.Pattern[str], str], ...]] = (
    (re.compile(r"@everyone"), "@​everyone"),
    (re.compile(r"@here"), "@​here"),
    (re.compile(r"<@(?=[!&]?\d)"), "<​@"),
)

SENTINEL_PREFIX: Final[str] = "/system:"
SENTINEL_KEYS: Final[dict[str, list[str]]] = {
    "ctrl-c": ["C-c"],
    "ctrl-d": ["C-d"],
    "enter": ["Enter"],
    "esc": ["Escape"],
}

# context auto-clear (spec: docs/features/context-auto-clear.md §5).
# PR #807 단순화에서 누락된 loop 를 #809 에서 복구. opt-in (default off — dry-run).
# #855 multi-pane 확장: nmae(mobruji:0.0) + helper(helper:0.0) 둘 다 polling.
CONTEXT_AUTO_CLEAR_DEFAULT_ENABLED: Final[str] = "0"
CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT: Final[int] = 95
CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT: Final[int] = 80
CONTEXT_AUTO_CLEAR_POLL_INTERVAL_SECONDS: Final[int] = 5
# 복수 pane CSV. plural (`TMUX_PANE_TARGETS`) 우선, singular (`TMUX_PANE_TARGET`)
# 후방호환. 둘 다 부재 시 아래 default.
CONTEXT_AUTO_CLEAR_DEFAULT_PANES: Final[str] = "mobruji:0.0,helper:0.0"
CONTEXT_AUTO_CLEAR_DEFAULT_PANE: Final[str] = "mobruji:0.0"  # legacy 단일 default (env 후방호환용)
CONTEXT_AUTO_CLEAR_CAPTURE_LINES: Final[int] = 2000
CLEAR_READY_MARKER: Final[str] = "===CLEAR_READY==="
CONTEXT_PCT_PATTERN: Final[re.Pattern[str]] = re.compile(r"===CTX:(\d{1,3})%===")
AUTOCLEAR_PAUSED_FLAG: Final[Path] = Path("~/.mobruji/autoclear-paused").expanduser()
CONTEXT_CLEAR_LOG_PATH: Final[Path] = Path(
    "~/.claude/projects/-home-mobruji-mobruji/memory/project_context_clear_log.md"
).expanduser()
CONTEXT_CLEAR_LOG_HEADER: Final[str] = (
    "---\n"
    "name: project-context-clear-log\n"
    "description: maestro/helper context auto-clear 사이클 결과 누적 (trigger / marker / clear)\n"
    "metadata:\n"
    "  type: project\n"
    "---\n"
    "\n"
    "# context auto-clear log (역시간순)\n"
    "\n"
    "| timestamp | pane | event | context% | handoff file | duration |\n"
    "|---|---|---|---|---|---|\n"
)
CONTEXT_CLEANUP_PROMPT: Final[str] = (
    "🧠 컨텍스트 95% 도달. 다음 절차로 자율 정리하라: "
    "1) 진행 중 작업 모두 완료 대기 (sub-agent launch / 새 요청 추가 금지) "
    "2) 다음 핸드오프 메모리 project_session_handoff_<YYYY-MM-DD>_v<N+1>.md 작성 "
    "(사이클 카운트 / 진행 중 PR / 사용자 결정 대기 / 첫 액션) "
    "3) MEMORY.md 인덱스에 새 핸드오프 한 줄 추가 "
    "4) 정리 완료 후 stdout 에 marker 출력: ===CLEAR_READY=== "
    "5) 그 후 정지 (ScheduleWakeup 재호출 안 함 — /clear 후 다음 wake 가 처리)"
)

# nmae 사이클 watchdog (spec: docs/features/nmae-cycle-watchdog.md).
# 메모리 [[feedback-keep-4-cycles-active]] nmae 자기 점검 반복 누락 →
# 외부 데몬 안전망. 5분 polling cycle-status.json, idle 워크트리 발견 시
# 자동 nmae tmux inject + Discord push.
CYCLE_IDLE_WATCH_DEFAULT_ENABLED: Final[str] = "1"
CYCLE_IDLE_THRESHOLD_DEFAULT_MINUTES: Final[int] = 10
CYCLE_IDLE_WATCH_DEFAULT_INTERVAL_SECONDS: Final[int] = 300  # 5분
CYCLE_IDLE_WATCH_DEFAULT_INJECT_TARGET: Final[str] = "mobruji:0.0"
CYCLE_IDLE_WATCH_DEFAULT_WORKSPACES: Final[str] = "be,fe,rev,plan"
# 동일 워크트리 재알림 debounce — spam 방지.
CYCLE_IDLE_WATCH_DEBOUNCE_SECONDS: Final[int] = 15 * 60  # 15분
# tmux inject prompt 템플릿. {date}/{workspaces}/{summary} 치환.
CYCLE_IDLE_WATCH_INJECT_TEMPLATE: Final[str] = (
    "[watchdog {date}] cycle-status.json idle 발견 — {workspaces}. "
    "{summary}. keep-4-cycles 룰 위반. "
    "즉시 다음 백로그 launch 또는 root cause 보고."
)
# STRICT mode (#956) — idle 발견 + note 미명시 시 별 prompt. reason 의무 위반.
CYCLE_IDLE_WATCH_STRICT_TEMPLATE: Final[str] = (
    "[watchdog STRICT {date}] cycle-status.json idle 발견 — {workspaces}. "
    "{summary}. note 필드 미명시 — keep-4-cycles 룰 + reason 의무 룰 동시 위반. "
    "즉시 다음 백로그 launch + cycle-status.json note 필드 기록 의무 "
    "(tools/cycle-status/update.sh <ws> set-idle --note '...' 사용)."
)
# CYCLE_REASON_REQUIRED env default — note 미명시 idle 을 strict 로 처리할지.
CYCLE_REASON_REQUIRED_DEFAULT: Final[str] = "1"
# future timestamp ERROR push debounce (#971) — 동일 워크트리 1시간 1회.
CYCLE_FUTURE_TS_PUSH_DEBOUNCE_SECONDS: Final[int] = 60 * 60  # 1h
# escalation (#972, #981) — 같은 워크트리 inject N회 연속 후에도 in_progress NULL 이면
# MOBRUJI_CHANNEL_ID (사용자 채널) 에 직접 push. nmae 무응답 가시화 알림 (사용자 조치
# 무관, 자율 처리 진행 중 가시화 목적 — sub-agent-no-user-wait 룰).
CYCLE_INJECT_ESCALATION_THRESHOLD_DEFAULT: Final[int] = 3
CYCLE_INJECT_ESCALATION_DEBOUNCE_SECONDS_DEFAULT: Final[int] = 60 * 60  # 1h
CYCLE_INJECT_ESCALATION_MESSAGE_TEMPLATE: Final[str] = (
    "🚨 nmae 무응답 가시화 — {workspaces} 워크트리 watchdog inject {count}회 연속 후 "
    "in_progress 여전히 NULL. 자율 처리 진행 중 (조치 무관)"
)

# STALE_ACTIVE (P3a remediation, #1015 follow-up) — `in_progress` 가 set 됐는데
# `started_at` 이 임계치보다 오래된 경우. sub-agent freeze 또는 nmae 완료 통지
# 처리 누락 추정. 기존 idle 검출 (in_progress=null) 만으로는 detect 못 했음 —
# 9h stale incident root cause 의 Layer 2 fix.
# 임계치 default 60분 — sub-agent 평균 cycle 보다 길게 잡아 false-positive 회피.
STALE_ACTIVE_ENABLED_DEFAULT: Final[str] = "1"
STALE_ACTIVE_THRESHOLD_DEFAULT_MIN: Final[int] = 60
# tmux inject prompt 템플릿. {date}/{workspaces}/{summary} 치환.
CYCLE_STALE_ACTIVE_INJECT_TEMPLATE: Final[str] = (
    "[watchdog STALE_ACTIVE {date}] cycle-status.json in_progress 가 set 됐는데 "
    "started_at 이 임계치 초과 — {workspaces}. {summary}. "
    "sub-agent freeze 또는 nmae 완료 통지 처리 누락 추정. "
    "즉시 sub-agent 상태 확인 후 set-idle (완료 시) 또는 재 launch 결정."
)

# rev e2e 단계 2 (post-merge) 자동 trigger (#1008, spec: docs/features/rev-e2e-3-stages.md §3-2).
# 5분 polling — develop 머지된 PR 중 `rev-post-merge-pass` 라벨 없는 항목을
# gh CLI 로 발굴해 nmae tmux pane 에 audit launch 알림 inject + Discord push.
# 동일 PR 반복 inject 방지를 위해 in-process debounce (기본 15분).
REV_POST_MERGE_AUDIT_LOOP_DEFAULT_ENABLED: Final[str] = "1"
REV_POST_MERGE_AUDIT_DEFAULT_INTERVAL_SECONDS: Final[int] = 300  # 5분
REV_POST_MERGE_AUDIT_DEFAULT_INITIAL_DELAY_SECONDS: Final[int] = 90  # boot warmup
REV_POST_MERGE_AUDIT_DEFAULT_INJECT_TARGET: Final[str] = "mobruji:0.0"
# 동일 PR 재 inject 차단 — nmae 가 라벨 부여하기까지 polling 사이클 사이의
# 중복 방지. 15분이면 단계 2 audit 시작/완료를 기다리기엔 충분.
REV_POST_MERGE_AUDIT_DEBOUNCE_SECONDS: Final[int] = 15 * 60  # 15분
# debounce cache 사이즈 cap — 메모리 누수 방지 (LRU 비슷한 단순 cap).
REV_POST_MERGE_AUDIT_DEBOUNCE_MAX_ENTRIES: Final[int] = 256
# gh CLI search 윈도우 — 사용자 spec §3-2 "develop 머지 직후 ~5분 deploy 대기".
# 1h 윈도우면 deploy 끝난 PR 만 대상이고, 너무 오래된 머지는 retry 부담만 됨.
REV_POST_MERGE_AUDIT_SEARCH_WINDOW: Final[str] = "1h"
# label 조회 시 사용할 label 이름 — rev sub-agent 가 단계 2 통과 시 부여.
REV_POST_MERGE_PASS_LABEL: Final[str] = "rev-post-merge-pass"
# inject prompt template — {pr_numbers} 콤마 join.
REV_POST_MERGE_AUDIT_INJECT_TEMPLATE: Final[str] = (
    "[rev e2e post-merge] PR {pr_numbers} 단계 2 audit launch — "
    "develop deploy 후 시나리오 재실행 (spec docs/features/rev-e2e-3-stages.md §3-2). "
    "rev 단계 2 pass 시 라벨 `rev-post-merge-pass` 부여."
)
# Discord push template.
REV_POST_MERGE_AUDIT_DISCORD_TEMPLATE: Final[str] = (
    "🔍 rev post-merge audit trigger — PR {pr_numbers} (단계 2 nmae inject)"
)
# gh CLI 실행 timeout (#1008). 네트워크 hang 시 loop block 방어.
REV_POST_MERGE_AUDIT_GH_TIMEOUT_SECONDS: Final[int] = 30

# --- Claude API usage tracker (#1020) ---
# spec: 이슈 #1020. tracker 모듈은 `claude_usage_tracker.py` 분리.
CLAUDE_USAGE_LOOP_DEFAULT_ENABLED: Final[str] = "1"
CLAUDE_USAGE_DEFAULT_INTERVAL_SECONDS: Final[int] = 300  # 5분
# boot warmup — 다른 loop 와 stagger.
CLAUDE_USAGE_DEFAULT_INITIAL_DELAY_SECONDS: Final[int] = 120

# --- directive-board event-driven (#1129, PR #1140 spec) ---
# 폐기 (2026-05-27): polling 기반 `directive_board_sync_loop` 5분 주기 → mismatch
# 자동 PATCH 불완전 (사용자 정정 "directive_board_mismatch=106"). event-driven 전환:
# 트리거 3 시점 actor atomic 호출 (지시 발생 / 위임 / 완료) — `directive_append.sh`
# / `directive_status.sh`. bot.py = dumb conduit (sync_loop 폐기).
# spec: `docs/features/directive-board-event-driven-redesign.md`.

# Discord thread auto-cleanup (#1023, 2026-05-24 사용자 P0).
# helper sub-agent launch per-thread (#1011) + auto-ack thread 누적 → 채널 sidebar
# 가시성 ↓. 주기적으로 오래된 thread 를 archive (또는 delete) 해 시야 정리.
# default 값은 per-launch 페이스(분 단위 생성)에 맞춘 짧은 age + 짧은 interval.
THREAD_CLEANUP_DEFAULT_ENABLED: Final[str] = "1"
# interval default 300 → 120 (사용자 P1, #1062): 5분 → 2분 단축. per-launch
# thread 가 분 단위 페이스로 누적되는 운영 상황에서 5분 주기는 가시 효과 ↓.
THREAD_CLEANUP_DEFAULT_INTERVAL_SECONDS: Final[int] = 120  # 2분 (사용자 P1)
THREAD_CLEANUP_DEFAULT_INITIAL_DELAY_SECONDS: Final[int] = 90  # boot warmup
# age 기준 (분). 마지막 activity 가 이 시간보다 오래된 thread 만 archive 후보.
# 분 단위로 표현 — per-launch thread 페이스 (수~수십 분) 와 일치.
# 후방호환: THREAD_CLEANUP_AGE_HOURS (시간) 도 인식, MINUTES 가 우선.
THREAD_CLEANUP_DEFAULT_AGE_MINUTES: Final[int] = 15
# 최근 활동 thread 는 N 개 보존 (사용자가 진행 중인 작업 보호).
THREAD_CLEANUP_DEFAULT_KEEP_RECENT: Final[int] = 3
# 0 = archive (sidebar 가시성 ↓ 만, 데이터 보존). 1 = delete (DELETE channel API).
# default archive — 실수로 진행 중 thread 가 사라지지 않게.
THREAD_CLEANUP_DEFAULT_DELETE: Final[str] = "0"
# Discord API 호출 사이 짧은 휴식 — bulk archive/delete 시 429 ratelimit 회피.
THREAD_CLEANUP_PER_THREAD_SLEEP_SECONDS: Final[float] = 0.5
# Discord HTTP 요청 timeout.
THREAD_CLEANUP_HTTP_TIMEOUT_SECONDS: Final[int] = 10
# Discord snowflake epoch (ms): 2015-01-01T00:00:00Z.
DISCORD_EPOCH_MS: Final[int] = 1_420_070_400_000

# --- directive-detect register watchdog (#1071) ---
# spec: 이슈 #1071. 사용자 P0 frustration "내가 지시한 거 왜 지시 forum에 추가 안해".
# `on_message` 에서 메시지 분류 → directive-detect.jsonl 에 append.
# watchdog loop 가 10분 주기 mismatch (detect > board + grace) 시 MOBRUJI 채널 push.
DIRECTIVE_DETECT_PATH_DEFAULT: Final[Path] = Path(
    "~/.mobruji/directive-detect.jsonl"
).expanduser()
DIRECTIVE_DETECT_WATCH_DEFAULT_ENABLED: Final[str] = "1"
DIRECTIVE_DETECT_WATCH_DEFAULT_INTERVAL_SECONDS: Final[int] = 600  # 10분
DIRECTIVE_DETECT_WATCH_DEFAULT_WINDOW_MINUTES: Final[int] = 60
DIRECTIVE_DETECT_WATCH_DEFAULT_GRACE_COUNT: Final[int] = 5
# push debounce — 같은 mismatch alarm 이 매 iter 중복 push 안 되도록.
DIRECTIVE_DETECT_WATCH_DEBOUNCE_SECONDS: Final[int] = 60 * 60  # 1h

# ─────────────────────────────────────────────────────────────────────────────
# Loop heartbeat hook (#1087, 2026-05-26 사용자 P0 "사이클 절대 멈추면 안 됨")
# ─────────────────────────────────────────────────────────────────────────────
#
# 배경: bot.py 의 다수 watchdog loop 가 silent crash (asyncio exception 삼킴 /
# 무한 await / outer scope crash) 시 detect 불가. 발생 시 무한 idle → 사용자 P0.
#
# 구조:
#   1. 각 loop 가 매 iter 성공 path 마지막에 `record_loop_heartbeat(name)` 호출.
#      파일 `<heartbeat_dir>/<loop_name>.ts` 에 `<monotonic>\n<iso>\n` atomic write.
#   2. 신규 `heartbeat_watch_loop` 가 10분 polling — 모든 loop 의 heartbeat 파일이
#      `expected_interval × multiplier` 초과 stale 이면 DIGEST_CHANNEL_ID push +
#      logger.error.
#   3. heartbeat_watch_loop 자체도 자기 heartbeat 기록 — meta detect.
#
# graceful — heartbeat 디렉토리 자동 mkdir, write 실패 시 warning 만 (loop 본체
# 동작에 영향 없음 / 부재 시 stale 로 detect 되어 가시화).
HEARTBEAT_DIR_DEFAULT: Final[Path] = Path("~/.mobruji/heartbeat").expanduser()
HEARTBEAT_FILE_MODE: Final[int] = 0o600
# stale 임계 = expected_interval × multiplier (default 3).
HEARTBEAT_STALE_MULTIPLIER_DEFAULT: Final[int] = 3
HEARTBEAT_WATCH_DEFAULT_ENABLED: Final[str] = "1"
HEARTBEAT_WATCH_DEFAULT_INTERVAL_SECONDS: Final[int] = 600  # 10분
HEARTBEAT_WATCH_DEFAULT_INITIAL_DELAY_SECONDS: Final[int] = 180  # boot warmup
# 동일 loop stale push debounce (1h).
HEARTBEAT_WATCH_PUSH_DEBOUNCE_SECONDS: Final[int] = 60 * 60
# loop name → 정상 max iter interval (seconds). stale 임계 = value × multiplier.
# 본 매핑은 record_loop_heartbeat 호출 위치의 sleep interval 기준.
# directive_board_sync_loop (#P11) 는 PR #1140 event-driven 전환으로 폐기 (8 loop).
LOOP_HEARTBEAT_EXPECTED_INTERVALS: Final[dict[str, int]] = {
    "digest_loop": DEFAULT_DIGEST_INTERVAL_SECONDS,
    "context_auto_clear_loop": CONTEXT_AUTO_CLEAR_POLL_INTERVAL_SECONDS,
    "cycle_idle_watch_loop": CYCLE_IDLE_WATCH_DEFAULT_INTERVAL_SECONDS,
    "rev_post_merge_audit_loop": REV_POST_MERGE_AUDIT_DEFAULT_INTERVAL_SECONDS,
    "claude_usage_watch_loop": CLAUDE_USAGE_DEFAULT_INTERVAL_SECONDS,
    "thread_cleanup_loop": THREAD_CLEANUP_DEFAULT_INTERVAL_SECONDS,
    "directive_detect_register_watch_loop": (
        DIRECTIVE_DETECT_WATCH_DEFAULT_INTERVAL_SECONDS
    ),
    "heartbeat_watch_loop": HEARTBEAT_WATCH_DEFAULT_INTERVAL_SECONDS,
}

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT, stream=sys.stdout)
logger = logging.getLogger("mobruji-discord-daemon")


def _heartbeat_dir() -> Path:
    """env HEARTBEAT_DIR override 가능. 부재 시 default ~/.mobruji/heartbeat."""
    raw = os.environ.get("HEARTBEAT_DIR")
    if raw:
        return Path(os.path.expanduser(raw))
    return HEARTBEAT_DIR_DEFAULT


def record_loop_heartbeat(
    name: str,
    *,
    heartbeat_dir: Path | None = None,
    monotonic_source=time.monotonic,
    wall_source=lambda: datetime.now(timezone.utc),
) -> bool:
    """loop iter 성공 후 heartbeat 파일 atomic write.

    파일 경로: ``<heartbeat_dir>/<name>.ts``. 본문은 두 줄:
        ``<monotonic_seconds>\\n<wall_iso>\\n``

    monotonic 은 stale detect 용 (clock skew 안전), iso 는 사람 디버깅 용.

    실패 시 warning 로그 + ``False`` 반환 — loop 본체 동작에는 영향 없음.

    Args:
        name: loop name. ``[a-zA-Z0-9_-]`` 만 허용 (path traversal 가드).
        heartbeat_dir: override (테스트). None 이면 env / default.
        monotonic_source: monotonic 시각 source (테스트 stub).
        wall_source: aware datetime source (테스트 stub).

    Returns:
        write 성공 여부.
    """
    if not name or not re.fullmatch(r"[A-Za-z0-9_-]+", name):
        logger.warning("record_loop_heartbeat: 잘못된 name=%r — skip", name)
        return False
    target_dir = heartbeat_dir if heartbeat_dir is not None else _heartbeat_dir()
    try:
        target_dir.mkdir(parents=True, exist_ok=True)
        mono = float(monotonic_source())
        iso = wall_source().isoformat()
        path = target_dir / f"{name}.ts"
        tmp = path.with_suffix(".ts.tmp")
        body = f"{mono}\n{iso}\n"
        tmp.write_text(body, encoding="utf-8")
        try:
            os.chmod(tmp, HEARTBEAT_FILE_MODE)
        except OSError:
            # 권한 변경 실패는 치명적이지 않음 (write 자체는 성공).
            pass
        os.replace(tmp, path)
        return True
    except OSError as exc:
        logger.warning("record_loop_heartbeat write 실패 name=%s: %s", name, exc)
        return False


def read_loop_heartbeat(
    name: str,
    *,
    heartbeat_dir: Path | None = None,
) -> tuple[float, str] | None:
    """heartbeat 파일을 (monotonic, iso) 로 파싱. 부재 / parse fail → None."""
    if not name or not re.fullmatch(r"[A-Za-z0-9_-]+", name):
        return None
    target_dir = heartbeat_dir if heartbeat_dir is not None else _heartbeat_dir()
    path = target_dir / f"{name}.ts"
    if not path.exists():
        return None
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return None
    lines = text.strip().splitlines()
    if not lines:
        return None
    try:
        mono = float(lines[0])
    except ValueError:
        return None
    iso = lines[1] if len(lines) >= 2 else ""
    return mono, iso


def detect_stale_heartbeats(
    *,
    expected_intervals: dict[str, int] | None = None,
    multiplier: int = HEARTBEAT_STALE_MULTIPLIER_DEFAULT,
    heartbeat_dir: Path | None = None,
    monotonic_source=time.monotonic,
) -> list[dict]:
    """stale loop heartbeat 리스트를 산출합니다.

    각 entry 는 dict 로 다음 키를 포함:
        - ``name``: loop name
        - ``expected_interval``: 정상 max iter 초
        - ``threshold``: stale 판정 임계 초 (= expected × multiplier)
        - ``age``: 마지막 heartbeat 부터 경과 초 (heartbeat 부재 시 None)
        - ``last_iso``: 마지막 heartbeat 의 wall iso (부재 시 "")
        - ``reason``: "missing" (파일 없음) / "stale" (age > threshold)

    multiplier ≤ 0 이면 빈 리스트 (disabled, 테스트 용).
    """
    if multiplier <= 0:
        return []
    intervals = (
        expected_intervals
        if expected_intervals is not None
        else LOOP_HEARTBEAT_EXPECTED_INTERVALS
    )
    now_mono = float(monotonic_source())
    stale: list[dict] = []
    for name, expected in intervals.items():
        threshold = expected * multiplier
        hb = read_loop_heartbeat(name, heartbeat_dir=heartbeat_dir)
        if hb is None:
            stale.append({
                "name": name,
                "expected_interval": expected,
                "threshold": threshold,
                "age": None,
                "last_iso": "",
                "reason": "missing",
            })
            continue
        mono, iso = hb
        age = now_mono - mono
        if age > threshold:
            stale.append({
                "name": name,
                "expected_interval": expected,
                "threshold": threshold,
                "age": age,
                "last_iso": iso,
                "reason": "stale",
            })
    return stale


def format_heartbeat_stale_message(stale: list[dict]) -> str:
    """Discord push 본문 빌드 — 사용자 가시 한 줄 요약 + 워크트리 별 상세."""
    if not stale:
        return ""
    header = f"🚨 loop heartbeat stale — {len(stale)}개 loop 응답 없음 (#1087)"
    lines = [header]
    for entry in stale:
        name = entry["name"]
        reason = entry["reason"]
        threshold = entry["threshold"]
        if reason == "missing":
            lines.append(f"  - {name}: heartbeat 파일 부재 (임계 {threshold}s)")
        else:
            age = entry["age"]
            iso = entry["last_iso"] or "?"
            lines.append(
                f"  - {name}: {age:.0f}s 경과 (임계 {threshold}s, 마지막 {iso})"
            )
    lines.append("→ daemon 재시작 또는 journalctl 추적 필요")
    return "\n".join(lines)


def verify_deploy_dir(
    *,
    bot_file: Path | None = None,
    env_get=None,
    logger_override: logging.Logger | None = None,
) -> str:
    """bridge 배포 dir 격리 검증 (PR #1126 spec — bridge-deployment-dir-separation).

    bot.py 가 메인 repo (`/home/mobruji/mobruji`) 가 아닌 전용 dir
    (`/home/mobruji/mobruji-bridge`) 에서 실행 중인지 확인합니다.
    임의 브랜치 전환에 봇 코드가 오염되는 사고 (2026-05-26 14:30 KST,
    `feat/writing-marker-reaction-typing-#1095`) 의 재발을 막습니다.

    Mode (env `MOBRUJI_BRIDGE_DEPLOY_DIR_CHECK`, default ``warn``):

    - ``off`` — 검증 skip (rollback / 마이그레이션 중 임시 사용).
    - ``warn`` (default) — 잘못된 dir 일 때 WARNING 로그만 emit, 정상 진행.
    - ``strict`` — 잘못된 dir 일 때 ERROR 로그 + ``sys.exit(2)``.

    기대 dir 경로 (env `MOBRUJI_BRIDGE_DEPLOY_DIR`,
    default ``/home/mobruji/mobruji-bridge``) 와 ``Path(__file__).resolve()``
    의 ancestor 비교로 판정합니다. symlink resolve 포함.

    Args:
        bot_file: 검사 대상 파일 경로 (default ``Path(bot.__file__)``). 테스트용.
        env_get: env getter (default ``os.environ.get``). 테스트용.
        logger_override: logger (default 모듈 logger). 테스트용.

    Returns:
        판정 결과 문자열 — ``"ok"`` / ``"warn"`` / ``"strict-exit"`` / ``"off"``.
        ``"strict-exit"`` 는 실제 ``sys.exit`` 직전 반환 (테스트에서만 도달).
    """
    active_logger = logger_override if logger_override is not None else logger
    get = env_get if env_get is not None else os.environ.get

    mode = (get("MOBRUJI_BRIDGE_DEPLOY_DIR_CHECK") or "warn").strip().lower()
    if mode not in {"off", "warn", "strict"}:
        active_logger.warning(
            "MOBRUJI_BRIDGE_DEPLOY_DIR_CHECK 값(%r) 알 수 없음 — 'warn' 으로 fallback.",
            mode,
        )
        mode = "warn"

    if mode == "off":
        active_logger.info(
            "bridge deploy dir 검증 mode=off — skip (마이그레이션/rollback 중에만 사용)."
        )
        return "off"

    expected_raw = (
        get("MOBRUJI_BRIDGE_DEPLOY_DIR") or "/home/mobruji/mobruji-bridge"
    ).strip()
    expected_dir = Path(expected_raw).resolve()
    bot_path = (bot_file if bot_file is not None else Path(__file__)).resolve()

    try:
        bot_path.relative_to(expected_dir)
        ok = True
    except ValueError:
        ok = False

    if ok:
        active_logger.info(
            "bridge deploy dir OK — bot.py=%s expected=%s mode=%s",
            bot_path,
            expected_dir,
            mode,
        )
        return "ok"

    message = (
        "bridge deploy dir MISMATCH — bot.py=%s 가 expected=%s 의 자식이 아닙니다. "
        "PR #1126 spec (docs/features/bridge-deployment-dir-separation.md) 위반 — "
        "메인 repo working tree 에서 봇이 실행 중일 가능성 (임의 브랜치 전환 오염 위험). "
        "전용 dir 로 이전하거나 mode=off 로 임시 우회하십시오."
    )
    if mode == "strict":
        active_logger.error(message, bot_path, expected_dir)
        # 테스트에서 sys.exit monkeypatch 시 도달 가능.
        sys.exit(2)
        return "strict-exit"

    active_logger.warning(message, bot_path, expected_dir)
    return "warn"


def load_env() -> dict[str, str]:
    """필수 환경변수를 로드합니다. 누락 시 즉시 종료합니다.

    단순화본 (이슈 #807): repository_dispatch 경로를 폐기했으므로
    `GITHUB_PAT` / `GITHUB_REPO` 는 필수가 아닙니다.
    """
    load_dotenv(Path(__file__).resolve().parent / ".env")

    required = (
        "DISCORD_BOT_TOKEN",
        "ALLOWED_USER_IDS",
        "MOBRUJI_CHANNEL_ID",
    )
    missing = [key for key in required if not os.environ.get(key)]
    if missing:
        logger.error("필수 환경변수 누락: %s", ", ".join(missing))
        sys.exit(1)

    env: dict[str, str] = {key: os.environ[key] for key in required}
    env["TMUX_SESSION_NAME"] = os.environ.get("TMUX_SESSION_NAME", "helper")
    env["TMUX_TARGET_PANE"] = os.environ.get("TMUX_TARGET_PANE", "helper:0.0")
    env["CLAUDE_BIN"] = os.environ.get("CLAUDE_BIN", "claude")
    env["DEDUP_LEDGER_PATH"] = os.path.expanduser(
        os.environ.get("DEDUP_LEDGER_PATH", "~/.mobruji/discord-bridge.sqlite")
    )
    env["DIGEST_ENABLED"] = os.environ.get("DIGEST_ENABLED", "1")
    # DIGEST_CHANNEL_ID (rename, #1019) — cycle digest 송신 채널.
    # backward compat: 기존 NOTIFY_CHANNEL_ID 도 fallback 으로 인식 (deprecation
    # warning 1회). 미설정 시 MOBRUJI_CHANNEL_ID 로 fallback (단일 채널 운영).
    digest_channel_raw = os.environ.get("DIGEST_CHANNEL_ID")
    if digest_channel_raw is None:
        legacy_notify_raw = os.environ.get("NOTIFY_CHANNEL_ID")
        if legacy_notify_raw is not None:
            if not getattr(load_env, "_notify_deprecation_warned", False):
                logger.warning(
                    "NOTIFY_CHANNEL_ID 는 deprecated — DIGEST_CHANNEL_ID 로 rename 됐습니다 (#1019). "
                    "현재 값(%r) 을 fallback 으로 사용. .env 갱신 권장.",
                    legacy_notify_raw,
                )
                load_env._notify_deprecation_warned = True  # type: ignore[attr-defined]
            digest_channel_raw = legacy_notify_raw
        else:
            digest_channel_raw = env["MOBRUJI_CHANNEL_ID"]
    env["DIGEST_CHANNEL_ID"] = digest_channel_raw
    # ALERT_CHANNEL_ID (#1020) — cycle idle / future-ts ERROR / escalation 알림 1차 채널.
    # 부재 시 DIGEST_CHANNEL_ID → MOBRUJI_CHANNEL_ID 순 fallback (load 시 결정).
    # 빈 문자열(env 파일 자리만 있는 경우) 도 미설정으로 간주.
    alert_channel_raw = os.environ.get("ALERT_CHANNEL_ID")
    if alert_channel_raw is not None and alert_channel_raw.strip():
        env["ALERT_CHANNEL_ID"] = alert_channel_raw.strip()
    else:
        env["ALERT_CHANNEL_ID"] = env["DIGEST_CHANNEL_ID"]

    # Per-cycle 채널 (P12, 2026-05-24) — `discord-reply.sh --cycle-channel`
    # 및 `nmae-discord-push.sh --cycle` 가 라우팅 대상으로 읽는 4 채널 id.
    # 미설정 시 빈 문자열 (load_env 는 단순 load — fallback 은 호출 시점에 결정).
    # discord-reply.sh 측 fallback: <CYCLE>_CHANNEL_ID → DIGEST → NOTIFY → 에러.
    # bot.py 가 본 값을 직접 사용하진 않으나, on_ready 로그 / 향후 digest aggregate
    # 확장 (4 cycle 별 최근 N 메시지) 의 진입점으로 env dict 에 보존한다.
    for _cycle in ("BE", "FE", "REV", "PLAN"):
        _key = f"{_cycle}_CHANNEL_ID"
        _raw = os.environ.get(_key)
        if _raw is not None and _raw.strip():
            env[_key] = _raw.strip()
        else:
            env[_key] = ""

    # Forum 채널 (#17 사용자 forum 전환 wave, 2026-05-24) — directive-board /
    # per-cycle 채널이 GUILD_FORUM type 으로 신설. discord-reply.sh 의
    # `--forum-post|--forum-comment|--forum-edit|--forum-retag` mode 가 .env 에서
    # 직접 read 하지만, bot.py 도 on_ready 로그 / event-driven directive_append.sh
    # 호출 (#1140) 진입점으로 env dict 에 보존.
    # 미설정 시 빈 문자열 (라우팅 책임은 discord-reply.sh — silent fallback 금지).
    for _forum in (
        "DIRECTIVE_BOARD_FORUM_ID",
        "BE_FORUM_ID",
        "FE_FORUM_ID",
        "REV_FORUM_ID",
        "PLAN_FORUM_ID",
    ):
        _raw = os.environ.get(_forum)
        if _raw is not None and _raw.strip():
            env[_forum] = _raw.strip()
        else:
            env[_forum] = ""
    env["CONTEXT_AUTO_CLEAR_ENABLED"] = os.environ.get(
        "CONTEXT_AUTO_CLEAR_ENABLED", CONTEXT_AUTO_CLEAR_DEFAULT_ENABLED
    )
    env["CONTEXT_CLEAR_TRIGGER_PCT"] = os.environ.get(
        "CONTEXT_CLEAR_TRIGGER_PCT", str(CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT)
    )
    env["CONTEXT_CLEAR_HYSTERESIS_PCT"] = os.environ.get(
        "CONTEXT_CLEAR_HYSTERESIS_PCT", str(CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT)
    )
    # multi-pane: TMUX_PANE_TARGETS (plural, CSV) 우선. 부재 시 TMUX_PANE_TARGET
    # (singular) 후방호환. 둘 다 없으면 CONTEXT_AUTO_CLEAR_DEFAULT_PANES.
    plural_value = os.environ.get("TMUX_PANE_TARGETS")
    singular_value = os.environ.get("TMUX_PANE_TARGET")
    if plural_value is not None:
        env["TMUX_PANE_TARGETS"] = plural_value
    elif singular_value is not None:
        env["TMUX_PANE_TARGETS"] = singular_value
    else:
        env["TMUX_PANE_TARGETS"] = CONTEXT_AUTO_CLEAR_DEFAULT_PANES
    # singular 도 별도 키로 보존 — 디버깅/legacy import 용.
    env["TMUX_PANE_TARGET"] = singular_value or CONTEXT_AUTO_CLEAR_DEFAULT_PANE
    env["CYCLE_STATUS_PATH"] = os.path.expanduser(
        os.environ.get("CYCLE_STATUS_PATH", DEFAULT_CYCLE_STATUS_PATH)
    )
    # cycle counter (#996) — KST 자정 기준 sub-agent 별 누적 cycle 횟수.
    env["CYCLE_COUNTER_PATH"] = os.path.expanduser(
        os.environ.get("CYCLE_COUNTER_PATH", DEFAULT_CYCLE_COUNTER_PATH)
    )
    env["BOT_AUTO_ACK"] = os.environ.get("BOT_AUTO_ACK", BOT_AUTO_ACK_DEFAULT_ENABLED)
    # nmae cycle watchdog (#941, spec: docs/features/nmae-cycle-watchdog.md)
    env["CYCLE_IDLE_WATCH"] = os.environ.get(
        "CYCLE_IDLE_WATCH", CYCLE_IDLE_WATCH_DEFAULT_ENABLED
    )
    env["CYCLE_IDLE_THRESHOLD_MINUTES"] = os.environ.get(
        "CYCLE_IDLE_THRESHOLD_MINUTES", str(CYCLE_IDLE_THRESHOLD_DEFAULT_MINUTES)
    )
    env["CYCLE_IDLE_WATCH_INTERVAL_SECONDS"] = os.environ.get(
        "CYCLE_IDLE_WATCH_INTERVAL_SECONDS",
        str(CYCLE_IDLE_WATCH_DEFAULT_INTERVAL_SECONDS),
    )
    env["CYCLE_INJECT_TARGET"] = os.environ.get(
        "CYCLE_INJECT_TARGET", CYCLE_IDLE_WATCH_DEFAULT_INJECT_TARGET
    )
    env["CYCLE_WATCH_WORKSPACES"] = os.environ.get(
        "CYCLE_WATCH_WORKSPACES", CYCLE_IDLE_WATCH_DEFAULT_WORKSPACES
    )
    # CYCLE_NOTIFY_CHANNEL_ID 부재 시 DIGEST_CHANNEL_ID fallback.
    # (CYCLE_NOTIFY 는 watchdog idle 알림 — 의미상 'notify' 가 적합해 유지.
    #  default 만 digest 채널 공유 — 단일 채널 운영 시 일관 동작.)
    env["CYCLE_NOTIFY_CHANNEL_ID"] = os.environ.get(
        "CYCLE_NOTIFY_CHANNEL_ID", env["DIGEST_CHANNEL_ID"]
    )
    # escalation (#972) — env override.
    env["CYCLE_INJECT_ESCALATION_THRESHOLD"] = os.environ.get(
        "CYCLE_INJECT_ESCALATION_THRESHOLD",
        str(CYCLE_INJECT_ESCALATION_THRESHOLD_DEFAULT),
    )
    env["CYCLE_INJECT_ESCALATION_DEBOUNCE_SECONDS"] = os.environ.get(
        "CYCLE_INJECT_ESCALATION_DEBOUNCE_SECONDS",
        str(CYCLE_INJECT_ESCALATION_DEBOUNCE_SECONDS_DEFAULT),
    )
    # STALE_ACTIVE (P3a remediation, #1015 follow-up) — in_progress.started_at
    # 이 임계치보다 오래된 워크트리 검출. sub-agent freeze 또는 nmae 완료 통지
    # 처리 누락 추정.
    env["STALE_ACTIVE_ENABLED"] = os.environ.get(
        "STALE_ACTIVE_ENABLED", STALE_ACTIVE_ENABLED_DEFAULT
    )
    env["STALE_ACTIVE_THRESHOLD_MIN"] = os.environ.get(
        "STALE_ACTIVE_THRESHOLD_MIN", str(STALE_ACTIVE_THRESHOLD_DEFAULT_MIN)
    )
    # rev e2e 단계 2 post-merge audit loop (#1008)
    env["REV_POST_MERGE_AUDIT_LOOP"] = os.environ.get(
        "REV_POST_MERGE_AUDIT_LOOP", REV_POST_MERGE_AUDIT_LOOP_DEFAULT_ENABLED
    )
    env["REV_POST_MERGE_AUDIT_INTERVAL_SECONDS"] = os.environ.get(
        "REV_POST_MERGE_AUDIT_INTERVAL_SECONDS",
        str(REV_POST_MERGE_AUDIT_DEFAULT_INTERVAL_SECONDS),
    )
    # Discord thread auto-cleanup (#1023) — 사용자 P0. per-launch thread 누적 →
    # sidebar 가시성 ↓ → 주기 archive (default) 또는 delete.
    env["THREAD_CLEANUP_ENABLED"] = os.environ.get(
        "THREAD_CLEANUP_ENABLED", THREAD_CLEANUP_DEFAULT_ENABLED
    )
    env["THREAD_CLEANUP_INTERVAL_SECONDS"] = os.environ.get(
        "THREAD_CLEANUP_INTERVAL_SECONDS",
        str(THREAD_CLEANUP_DEFAULT_INTERVAL_SECONDS),
    )
    env["THREAD_CLEANUP_AGE_MINUTES"] = os.environ.get(
        "THREAD_CLEANUP_AGE_MINUTES",
        str(THREAD_CLEANUP_DEFAULT_AGE_MINUTES),
    )
    # 후방호환: 기존 HOURS env 도 인식 (별도 키로 보존, build_client 에서 minutes
    # 우선 적용).
    env["THREAD_CLEANUP_AGE_HOURS"] = os.environ.get(
        "THREAD_CLEANUP_AGE_HOURS", ""
    )
    env["THREAD_CLEANUP_KEEP_RECENT"] = os.environ.get(
        "THREAD_CLEANUP_KEEP_RECENT", str(THREAD_CLEANUP_DEFAULT_KEEP_RECENT)
    )
    env["THREAD_CLEANUP_DELETE"] = os.environ.get(
        "THREAD_CLEANUP_DELETE", THREAD_CLEANUP_DEFAULT_DELETE
    )
    # Loop heartbeat watchdog (#1087, 2026-05-26 사용자 P0).
    env["HEARTBEAT_WATCH_ENABLED"] = os.environ.get(
        "HEARTBEAT_WATCH_ENABLED", HEARTBEAT_WATCH_DEFAULT_ENABLED
    )
    env["HEARTBEAT_WATCH_INTERVAL_SECONDS"] = os.environ.get(
        "HEARTBEAT_WATCH_INTERVAL_SECONDS",
        str(HEARTBEAT_WATCH_DEFAULT_INTERVAL_SECONDS),
    )
    env["HEARTBEAT_STALE_MULTIPLIER"] = os.environ.get(
        "HEARTBEAT_STALE_MULTIPLIER", str(HEARTBEAT_STALE_MULTIPLIER_DEFAULT)
    )
    env["REV_POST_MERGE_AUDIT_INJECT_TARGET"] = os.environ.get(
        "REV_POST_MERGE_AUDIT_INJECT_TARGET",
        REV_POST_MERGE_AUDIT_DEFAULT_INJECT_TARGET,
    )
    # Claude API usage tracker (#1020).
    env["CLAUDE_USAGE_LOOP"] = os.environ.get(
        "CLAUDE_USAGE_LOOP", CLAUDE_USAGE_LOOP_DEFAULT_ENABLED
    )
    env["CLAUDE_USAGE_INTERVAL_SECONDS"] = os.environ.get(
        "CLAUDE_USAGE_INTERVAL_SECONDS",
        str(CLAUDE_USAGE_DEFAULT_INTERVAL_SECONDS),
    )
    env["CLAUDE_DAILY_TOKEN_LIMIT"] = os.environ.get(
        "CLAUDE_DAILY_TOKEN_LIMIT", str(CLAUDE_DAILY_TOKEN_LIMIT_DEFAULT)
    )
    env["CLAUDE_WEEKLY_TOKEN_LIMIT"] = os.environ.get(
        "CLAUDE_WEEKLY_TOKEN_LIMIT", str(CLAUDE_WEEKLY_TOKEN_LIMIT_DEFAULT)
    )
    env["CLAUDE_USAGE_PROJECTS_ROOT"] = os.path.expanduser(
        os.environ.get(
            "CLAUDE_USAGE_PROJECTS_ROOT", str(CLAUDE_USAGE_PROJECTS_ROOT_DEFAULT)
        )
    )
    env["CLAUDE_USAGE_STATE_PATH"] = os.path.expanduser(
        os.environ.get(
            "CLAUDE_USAGE_STATE_PATH", str(CLAUDE_USAGE_STATE_PATH_DEFAULT)
        )
    )
    # directive-board (#P11 → PR #1140 event-driven 전환).
    # polling sync (`DIRECTIVE_BOARD_SYNC_ENABLED` / `_INTERVAL` / `_CHANNEL_ID`)
    # env 변수는 폐기. JSONL/STATE path 는 digest 의 directive_board_summary
    # 호출 + 향후 event-driven `directive_append.sh` 가 사용하므로 유지.
    env["DIRECTIVE_BOARD_JSONL_PATH"] = os.path.expanduser(
        os.environ.get(
            "DIRECTIVE_BOARD_JSONL_PATH", str(DIRECTIVE_BOARD_JSONL_PATH_DEFAULT)
        )
    )
    env["DIRECTIVE_BOARD_STATE_PATH"] = os.path.expanduser(
        os.environ.get(
            "DIRECTIVE_BOARD_STATE_PATH", str(DIRECTIVE_BOARD_STATE_PATH_DEFAULT)
        )
    )
    return env


def parse_allowed_user_ids(raw: str) -> set[int]:
    """CSV user id 목록을 정수 집합으로 변환합니다."""
    ids: set[int] = set()
    for token in raw.split(","):
        token = token.strip()
        if not token:
            continue
        try:
            ids.add(int(token))
        except ValueError:
            logger.warning("ALLOWED_USER_IDS 토큰 무시 (정수 아님): %r", token)
    return ids


def truncate_for_log(text: str, max_len: int = MAX_TEXT_PREVIEW_LEN) -> str:
    """로그/inbox 에 메시지를 남길 때 너무 길지 않도록 자릅니다.

    Args:
        text: 원문.
        max_len: 자르기 기준 길이. 기본값은 로그 미리보기용 :data:`MAX_TEXT_PREVIEW_LEN`.
            inbox.jsonl append 시 (#909 F-1) :data:`INBOX_TEXT_MAX_LEN` 를 명시적으로
            전달하여 더 긴 본문도 capped 형태로 저장합니다.
    """
    if len(text) <= max_len:
        return text
    return text[:max_len] + "…"


def _ensure_inbox_secure() -> None:
    """inbox.jsonl 디렉토리 보장 + 0o600 권한 강제 (#909 F-1).

    - 부모 디렉토리가 없으면 생성.
    - 파일이 없으면 0o600 mode 로 touch.
    - 파일이 이미 있더라도 매 호출마다 chmod 600 으로 보정 (운영 중 권한 수정 방어).

    chmod / touch 실패는 warning 만 남기고 raise 하지 않습니다 — 백업/디버깅용
    파일이라 본 메시지 처리(send-keys) 흐름을 막아서는 안 됩니다.
    """
    try:
        INBOX_PATH.parent.mkdir(parents=True, exist_ok=True)
        if not INBOX_PATH.exists():
            INBOX_PATH.touch(mode=INBOX_FILE_MODE, exist_ok=True)
        os.chmod(INBOX_PATH, INBOX_FILE_MODE)
    except OSError as exc:
        logger.warning("inbox.jsonl 보안 준비 실패: %s", exc)


def append_inbox(payload: dict[str, str]) -> None:
    """inbox.jsonl 에 한 줄 JSON 으로 append 합니다 (백업/디버깅용).

    #909 F-1: PII 평문 노출 완화.
      - 매 호출마다 부모 디렉토리 + 0o600 권한 보장.
      - ``text`` 필드를 :data:`INBOX_TEXT_MAX_LEN` (500자) 로 truncate 저장.
        나머지 필드(author/ts/message_id 등) 는 ID 류라 그대로 보존.
    """
    _ensure_inbox_secure()
    truncated_payload = dict(payload)
    if isinstance(payload.get("text"), str):
        truncated_payload["text"] = truncate_for_log(
            payload["text"], max_len=INBOX_TEXT_MAX_LEN
        )
    try:
        with INBOX_PATH.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(truncated_payload, ensure_ascii=False) + "\n")
    except OSError as exc:
        logger.warning("inbox.jsonl write 실패: %s", exc)


# ─── reaction-choice + user mode helpers ──────────────────────────────────────
# spec: docs/features/discord-reaction-choice-input.md


def parse_choice_emoji(emoji_str: str) -> int | None:
    """Discord raw reaction emoji string → 0-based choice index.

    Args:
        emoji_str: ``str(discord.PartialEmoji)`` 또는 동등. 예 ``"1️⃣"``, ``"🔟"``.

    Returns:
        0-based index (1️⃣=0, 🔟=9) — 매칭되는 keycap 인 경우. None — 미매칭.
    """
    return NUMBER_KEYCAP_TO_INDEX.get(emoji_str)


def lookup_choice_prompt(
    message_id: str,
    path: Path = CHOICE_PROMPTS_PATH,
) -> dict | None:
    """choice-prompts.jsonl 에서 active register entry 조회.

    같은 ``message_id`` 의 ``event="consume"`` row 가 있으면 used → None 반환.
    file 부재 / OS 오류 / JSON 손상 line → graceful None.

    Args:
        message_id: bot 이 post 한 choice prompt 의 Discord message snowflake.
        path: choice-prompts.jsonl 경로 (테스트 override 용).
    """
    if not path.exists():
        return None

    register: dict | None = None
    consumed = False
    try:
        with path.open("r", encoding="utf-8") as handle:
            for raw_line in handle:
                line = raw_line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if entry.get("message_id") != message_id:
                    continue
                event = entry.get("event")
                if event == "register":
                    register = entry
                elif event == "consume":
                    consumed = True
    except OSError:
        return None

    if consumed or register is None:
        return None
    return register


def mark_choice_consumed(
    message_id: str,
    choice_idx: int,
    user_id: str,
    path: Path = CHOICE_PROMPTS_PATH,
) -> None:
    """choice-prompts.jsonl 에 ``event="consume"`` row append (append-only)."""
    entry = {
        "event": "consume",
        "message_id": message_id,
        "choice_idx": choice_idx,
        "user_id": user_id,
        "ts": datetime.now(timezone.utc).isoformat(),
    }
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except OSError as exc:
        logger.warning("choice-prompts.jsonl consume write 실패: %s", exc)


def read_user_mode(path: Path = USER_MODE_PATH) -> str:
    """user-mode.txt 읽어 ``AUTO`` 또는 ``ASK`` 반환. 부재/잘못된 값 → ``AUTO``."""
    if not path.exists():
        return USER_MODE_DEFAULT
    try:
        raw = path.read_text(encoding="utf-8").strip().upper()
    except OSError:
        return USER_MODE_DEFAULT
    return raw if raw in USER_MODE_VALID else USER_MODE_DEFAULT


def write_user_mode(mode: str, path: Path = USER_MODE_PATH) -> None:
    """user-mode.txt atomic write. mode 가 유효하지 않으면 raise ValueError."""
    normalized = mode.strip().upper()
    if normalized not in USER_MODE_VALID:
        raise ValueError(
            f"user mode {mode!r} 가 유효하지 않음 (허용: {USER_MODE_VALID})"
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(normalized + "\n", encoding="utf-8")
    os.replace(tmp, path)


def scan_assigned_directives_for_cycle(cycle: str, timeout: float = 5.0) -> str:
    """주어진 cycle 에 assigned + polished 된 🟡 대기 directive list 출력.

    spec: docs/features/directive-board-template-and-tags.md §5-6 + actors/nmae.md §11-8
    cycle idle inject 시점에 자동 호출 (PR C). nmae 가 학습 의존 없이 cycle 별
    assigned directive 우선 처리 가능.

    backlog-scan.sh --cycle <cycle> subprocess 호출. 실패는 graceful (빈 str 반환).
    """
    script_path = (
        Path(__file__).resolve().parent.parent / "directive-board" / "backlog-scan.sh"
    )
    if not script_path.exists():
        return ""
    try:
        result = subprocess.run(  # noqa: S603 — script path hardcoded sibling
            ["bash", str(script_path), "--cycle", cycle],
            check=False,
            timeout=timeout,
            capture_output=True,
            text=True,
        )
    except (subprocess.TimeoutExpired, OSError) as exc:
        logger.warning(
            "backlog-scan --cycle=%s 호출 실패 (graceful): %r", cycle, exc
        )
        return ""
    if result.returncode != 0:
        return ""
    # COUNT=0 이면 의미 없음 (assigned directive 없음).
    output = result.stdout.strip()
    if not output or "COUNT=0" in output:
        return ""
    return output


async def _post_control_ack(
    client: discord.Client,
    channel_id: int,
    message_id: str,
    thread_name: str,
    ack_text: str,
) -> str:
    """control emoji (⏹/❓) tap 시 그 메시지에 thread 생성 + ack 메시지 push.

    spec: helper-control + tool-visibility (2026-05-29) — 사용자 정정 "어쨌든 부가
    응답은 다 스레드로정리". 모든 control emoji 부가 응답을 그 메시지의 thread 안에
    모아 사용자 추적 용이.

    fail-soft: thread 생성 실패 시 ack 만 채널로 reply (graceful).

    Returns: thread_id (str) — helper ❓ 응답 시 inject text marker 로 사용. 실패 시 "".
    """
    channel = client.get_channel(channel_id)
    if channel is None:
        logger.warning("control ack: channel %d 미발견 — skip", channel_id)
        return ""
    try:
        message = await channel.fetch_message(int(message_id))
    except Exception as exc:  # noqa: BLE001
        logger.warning("control ack: fetch_message 실패 msg=%s exc=%r", message_id, exc)
        return ""

    thread = getattr(message, "thread", None)
    if thread is None:
        try:
            thread = await message.create_thread(name=thread_name[:100])
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "control ack: create_thread 실패 msg=%s exc=%r — reply fallback",
                message_id, exc,
            )
            try:
                await channel.send(ack_text, reference=message)
            except Exception as send_exc:  # noqa: BLE001
                logger.warning("control ack: reply fallback 실패 — %r", send_exc)
            return ""
    try:
        await thread.send(ack_text)
    except Exception as exc:  # noqa: BLE001
        logger.warning("control ack: thread.send 실패 thread=%s exc=%r",
                       getattr(thread, "id", "?"), exc)
        return ""
    return str(getattr(thread, "id", ""))


# ─── Discord buttons mode toggle (PR 2) ──────────────────────────────────────
# spec: docs/features/discord-reaction-choice-input.md §5-8 PR 2
# 사용자 결정 (2026-05-28): Discord buttons (interaction) — 모바일 typing 0 + 시각화.

# 2026-05-29 폐기: Mode toggle buttons UI (ModeToggleView / ensure_mode_toggle_message /
# find_mode_toggle_message / build_mode_toggle_content / MODE_TOGGLE_*).
#
# 사용자 정정: "버튼으로 모드설정하는 건 삭제해 그럼" — 핀 메시지 view 의 button
# interaction 작동 X (Discord native 한계, PR #1290 commit message 참조).
# 대체: `/mb auto` / `/mb ask` / `/mb status` slash command (PR #1290).
#
# 기존 채널 핀된 mode toggle 메시지는 사용자 manual 삭제 또는 unpin 필요.
# (코드에서 자동 unpin / delete 안 함 — Discord 의 user content 정리 보수적 정책.)


# ─── 📌 pin match search (2026-05-29 사용자 정정) ─────────────────────────────
# 사용자 정정: "이미 작업 중인 것에 핀꼽기 좀 그렇다 + following 목적 핀 = task 됨"
# 해결: 📌 시 active directive + cycle forum thread 매칭 검색 → 발견 시 사용자
# 명시 선택 (등록 / 매칭 thread 로 / 취소). 매칭 없으면 즉시 등록 (현재 path).
# 사용자 룰: "그냥 궁금한 것들은 질문, 작업에 적재하고 싶은 경우에만 이모지".

PIN_MATCH_LIMIT: Final[int] = 5
PIN_MATCH_KEYWORD_MAX: Final[int] = 10
PIN_MATCH_KEYWORD_MIN_LEN: Final[int] = 2
PIN_MATCH_STOPWORDS: Final[frozenset[str]] = frozenset({
    "그", "이", "그것", "그건", "이거", "저거", "이건", "저건",
    "있어", "있는", "하는", "되는", "관련", "관해", "대해",
    "그리고", "그러나", "그래서", "그런데", "근데", "하지만",
    "그래도", "그냥", "혹시", "어떻게", "뭐", "왜", "어디",
})


def _extract_pin_keywords(text: str) -> list[str]:
    """간단한 한국어 토큰화 — split + stopword 제거 + min length filter."""
    cleaned = re.sub(r"[^\w가-힣\s]", " ", text)
    tokens = cleaned.split()
    keywords: list[str] = []
    for tok in tokens:
        if len(tok) < PIN_MATCH_KEYWORD_MIN_LEN:
            continue
        if tok in PIN_MATCH_STOPWORDS:
            continue
        keywords.append(tok)
        if len(keywords) >= PIN_MATCH_KEYWORD_MAX:
            break
    return keywords


def _substring_match(keywords: list[str], target: str) -> bool:
    """의미 있는 단어 1개 이상 매칭."""
    if not keywords or not target:
        return False
    return any(kw in target for kw in keywords)


def _find_matching_directives(summary: str) -> list[dict]:
    """directive board jsonl 에서 active entries 와 매칭 검색."""
    board_path = Path.home() / ".mobruji" / "directive-board.jsonl"
    if not board_path.exists():
        return []
    keywords = _extract_pin_keywords(summary)
    if not keywords:
        return []
    matches: list[dict] = []
    try:
        for line in board_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue
            entry_status = entry.get("status", "")
            if entry_status in ("완료", "✅ 완료", "closed", "취소"):
                continue
            entry_summary = entry.get("summary") or ""
            if _substring_match(keywords, entry_summary):
                matches.append({
                    "kind": "directive",
                    "summary": entry_summary[:80],
                    "thread_id": entry.get("thread_id"),
                    "status": entry_status,
                })
                if len(matches) >= PIN_MATCH_LIMIT:
                    break
    except OSError as exc:
        logger.warning("📌 pin match: directive board read 실패: %r", exc)
    return matches


async def _find_matching_cycle_threads(
    client: discord.Client,
    summary: str,
    forum_ids: list[int],
) -> list[dict]:
    """4 cycle forum (be/fe/rev/plan) active threads 매칭 검색.

    discord.py 의 Forum channel.threads 속성 사용 (활성 thread list).
    """
    keywords = _extract_pin_keywords(summary)
    if not keywords:
        return []
    matches: list[dict] = []
    for forum_id in forum_ids:
        if not forum_id:
            continue
        forum = client.get_channel(forum_id)
        if forum is None or not hasattr(forum, "threads"):
            continue
        for thread in getattr(forum, "threads", []):
            if thread.archived:
                continue
            name = getattr(thread, "name", "") or ""
            if _substring_match(keywords, name):
                matches.append({
                    "kind": "cycle_thread",
                    "summary": name[:80],
                    "thread_id": str(thread.id),
                    "forum_name": getattr(forum, "name", ""),
                })
                if len(matches) >= PIN_MATCH_LIMIT:
                    return matches
    return matches


def _build_pin_match_followup(
    matches: list[dict], original_summary: str,
) -> str:
    """ephemeral followup 본문 build — 매칭 list + 사용자 선택 안내."""
    lines = [
        f"📌 매칭되는 기존 작업이 있습니다 (원본: `{original_summary[:60]}`):\n",
    ]
    for m in matches[:PIN_MATCH_LIMIT]:
        if m["kind"] == "directive":
            status = m.get("status", "")
            lines.append(f"• directive ({status}): `{m['summary']}`")
        else:
            forum_name = m.get("forum_name", "")
            lines.append(f"• {forum_name} forum: `{m['summary']}`")
    lines.append("\n그래도 새로 등록할까요?")
    return "\n".join(lines)


class PinConfirmView(discord.ui.View):
    """매칭 발견 시 사용자 선택 button. 3 button (등록 / redirect / 취소).

    timeout 시 default = 취소 (사고 path 차단 우선).
    """

    def __init__(
        self,
        *,
        target_message_id: str,
        target_user_id: int,
        original_summary: str,
        first_match_thread_id: str | None,
        guild_id: int,
    ) -> None:
        super().__init__(timeout=900.0)  # 15분
        self._target_message_id = target_message_id
        self._target_user_id = target_user_id
        self._original_summary = original_summary
        self._first_match_thread_id = first_match_thread_id
        self._guild_id = guild_id

    async def _verify_user(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id != self._target_user_id:
            await interaction.response.send_message(
                "버튼은 원래 사용자만 사용할 수 있습니다.", ephemeral=True,
            )
            return False
        return True

    @discord.ui.button(label="새로 등록", style=discord.ButtonStyle.success, emoji="✅")
    async def _register(
        self, interaction: discord.Interaction, _btn: discord.ui.Button,
    ) -> None:
        if not await self._verify_user(interaction):
            return
        await interaction.response.edit_message(
            content="📌 새 directive 로 등록합니다…", view=None,
        )
        await _do_register_directive(
            interaction.client, self._target_message_id, self._target_user_id,
        )
        await interaction.edit_original_response(
            content="✅ 새 directive 등록 완료.",
        )
        self.stop()

    @discord.ui.button(label="기존으로 이동", style=discord.ButtonStyle.primary, emoji="🔗")
    async def _redirect(
        self, interaction: discord.Interaction, _btn: discord.ui.Button,
    ) -> None:
        if not await self._verify_user(interaction):
            return
        if not self._first_match_thread_id:
            await interaction.response.edit_message(
                content="🔗 매칭 thread URL 없음 — 취소.", view=None,
            )
            self.stop()
            return
        thread_url = (
            f"https://discord.com/channels/{self._guild_id}/"
            f"{self._first_match_thread_id}"
        )
        await interaction.response.edit_message(
            content=f"🔗 기존 작업으로: {thread_url}", view=None,
        )
        self.stop()

    @discord.ui.button(label="취소", style=discord.ButtonStyle.secondary, emoji="🚫")
    async def _cancel(
        self, interaction: discord.Interaction, _btn: discord.ui.Button,
    ) -> None:
        if not await self._verify_user(interaction):
            return
        await interaction.response.edit_message(
            content="🚫 등록 취소.", view=None,
        )
        # 📌 reaction remove
        try:
            channel = interaction.client.get_channel(interaction.channel_id)
            if channel is not None:
                msg = await channel.fetch_message(int(self._target_message_id))
                bot_user = interaction.client.user
                if bot_user is not None:
                    await msg.remove_reaction(PIN_REACTION_EMOJI, bot_user)
        except Exception as exc:  # noqa: BLE001
            logger.warning("📌 pin cancel: reaction remove 실패: %r", exc)
        self.stop()

    async def on_timeout(self) -> None:
        # timeout default = 취소 (사고 path 차단 우선). 사용자가 인지 못 한 상태에서
        # 자동 등록되면 의도 불일치 risk.
        logger.info(
            "📌 pin confirm timeout: msg_id=%s — default 취소",
            self._target_message_id,
        )


# ─── Phase B+C — 등록 직전 정리 + O/X dialogue ────────────────────────────────
# 사용자 의도 (2026-05-29): "정리해서 추가할까요? O / X 이모지, O 면 적재, X 면 어떤 점을
# 수정할까요?". claude -p 호출 → 4 항목 markdown → 사용자 메시지 아래 thread → O/X.

PIN_DIALOGUE_MAX_REVISIONS: Final[int] = 3
PIN_DIALOGUE_TIMEOUT: Final[float] = 900.0  # 15분


async def _generate_directive_description(
    raw_summary: str, directive_id: str, user_feedback: str | None = None,
) -> str:
    """claude -p subprocess → 4 항목 markdown description.

    user_feedback 가 있으면 prompt 에 추가 — 사용자 X 후 수정 요청 반영.
    실패 시 raw_summary 그대로 반환 (graceful — dialogue 진행).
    """
    body = raw_summary
    if user_feedback:
        body = f"{raw_summary}\n\n[사용자 수정 요청]\n{user_feedback}"
    polished = await asyncio.to_thread(_run_claude_polish, body, directive_id)
    return polished or raw_summary


class PinDialogueView(discord.ui.View):
    """O/X dialogue — 정리된 description 확인 + cycle 명시 + 수정 loop.

    Discord ui.Select (cycle dropdown) + ⭕/❌ button.

    cycle 명시 (사용자 정정 2026-05-29): "내가 명시적으로 plan에게 지시 위임할 수 있지?"
    → dropdown 의 선택값이 events 'directive_approved' payload 의 cycle_hint 로 전달.
    agent.py 의 LLM 은 cycle_hint 명시 시 그 cycle 강제 사용 (판단 X).

    O = `_do_register_directive` 호출 + events INSERT + thread close.
    X = thread 안 메시지 수신 → 재정리 → 다시 O/X.
    max retry = 3, timeout = 15분 (default 취소).
    """

    def __init__(
        self,
        *,
        target_message_id: str,
        target_user_id: int,
        raw_summary: str,
        polished_description: str,
        revision_count: int,
        thread,  # noqa: ANN001 — discord thread duck-typed
        register_channel,  # noqa: ANN001
        initial_cycle_hint: str = "auto",
    ) -> None:
        super().__init__(timeout=PIN_DIALOGUE_TIMEOUT)
        self._target_message_id = target_message_id
        self._target_user_id = target_user_id
        self._raw_summary = raw_summary
        self._polished = polished_description
        self._revision_count = revision_count
        self._thread = thread
        self._register_channel = register_channel
        self._cycle_hint = initial_cycle_hint  # default "auto" — nmae LLM 판단

    async def _verify_user(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id != self._target_user_id:
            await interaction.response.send_message(
                "버튼은 원래 사용자만 사용할 수 있습니다.", ephemeral=True,
            )
            return False
        return True

    # ─── cycle 선택 dropdown — 사용자가 명시 위임 ────────────────────────────
    @discord.ui.select(
        placeholder="cycle 위임 (default: 자동)",
        min_values=1, max_values=1,
        options=[
            discord.SelectOption(label="자동 (nmae 판단)", value="auto", emoji="🤖"),
            discord.SelectOption(label="be (백엔드)", value="be", emoji="🔧"),
            discord.SelectOption(label="fe (프론트엔드)", value="fe", emoji="🎨"),
            discord.SelectOption(label="rev (코드 리뷰 / QA)", value="rev", emoji="🔍"),
            discord.SelectOption(label="plan (spec / ADR / 큰 분석)", value="plan", emoji="📋"),
        ],
    )
    async def _select_cycle(
        self, interaction: discord.Interaction, select: discord.ui.Select,
    ) -> None:
        if not await self._verify_user(interaction):
            return
        self._cycle_hint = select.values[0]
        await interaction.response.send_message(
            f"☑️ cycle = **{self._cycle_hint}** 로 설정. ⭕ 누르면 적용.",
            ephemeral=True,
        )

    @discord.ui.button(label="등록", style=discord.ButtonStyle.success, emoji="⭕")
    async def _approve(
        self, interaction: discord.Interaction, _btn: discord.ui.Button,
    ) -> None:
        if not await self._verify_user(interaction):
            return
        await interaction.response.edit_message(
            content=f"✅ 등록 진행 중…\n\n{self._polished}", view=None,
        )
        await _do_register_directive(
            interaction.client,
            self._target_message_id,
            self._target_user_id,
            channel=self._register_channel,
            summary=self._polished[:80],
        )
        # Phase F (2026-05-29) — events 'directive_approved' INSERT → agent.py
        # handle_directive_approved 가 consume → cycle 위임 결정 → launch_subagent.
        # nmae 의 자동 위임 path 폐기 (Phase D), event-driven 만 trigger.
        append_agent_event("directive_approved", {
            "directive_id": self._target_message_id,
            "summary": self._raw_summary,
            "description": self._polished,
            "user_id": str(self._target_user_id),
            "channel_id": str(getattr(self._register_channel, "id", "")),
            "thread_id": str(getattr(self._thread, "id", "")),
            "revision_count": self._revision_count,
            # 사용자 명시 cycle hint (dropdown 선택 — "auto" 면 nmae LLM 자동 판단)
            "cycle_hint": self._cycle_hint,
        })
        cycle_label = (
            f"cycle = **{self._cycle_hint}** (사용자 명시)"
            if self._cycle_hint != "auto"
            else "cycle = 자동 판단"
        )
        await interaction.edit_original_response(
            content=f"✅ 등록 완료 + nmae 분배 trigger ({cycle_label}).\n\n{self._polished}",
        )
        # thread 자동 archive
        try:
            await self._thread.edit(archived=True)
        except Exception as exc:  # noqa: BLE001
            logger.warning("📌 pin dialogue thread archive 실패: %r", exc)
        self.stop()

    @discord.ui.button(label="수정", style=discord.ButtonStyle.secondary, emoji="❌")
    async def _revise(
        self, interaction: discord.Interaction, _btn: discord.ui.Button,
    ) -> None:
        if not await self._verify_user(interaction):
            return
        if self._revision_count >= PIN_DIALOGUE_MAX_REVISIONS:
            await interaction.response.edit_message(
                content=(
                    f"🚫 수정 최대 {PIN_DIALOGUE_MAX_REVISIONS}회 도달 — 등록 취소.\n\n"
                    f"다시 시도하려면 메시지에 📌 reaction 재시도."
                ),
                view=None,
            )
            self.stop()
            return

        await interaction.response.edit_message(
            content=(
                f"❓ 어떤 점을 수정할까요? 이 thread 안에 메시지로 입력하세요. "
                f"(현재 시도 {self._revision_count + 1}/{PIN_DIALOGUE_MAX_REVISIONS})"
            ),
            view=None,
        )

        # 사용자 다음 메시지 wait (thread 안)
        def _check(m: discord.Message) -> bool:
            return (
                m.author.id == self._target_user_id
                and m.channel.id == self._thread.id
            )

        try:
            user_msg = await interaction.client.wait_for(
                "message", check=_check, timeout=PIN_DIALOGUE_TIMEOUT,
            )
        except asyncio.TimeoutError:
            await self._thread.send("⏱ 수정 입력 timeout — 등록 취소.")
            self.stop()
            return

        # 재정리
        await self._thread.send(f"🔄 재정리 중 (시도 {self._revision_count + 1})…")
        new_polished = await _generate_directive_description(
            self._raw_summary, self._target_message_id,
            user_feedback=user_msg.content,
        )

        # 새 PinDialogueView 으로 재게시
        new_view = PinDialogueView(
            target_message_id=self._target_message_id,
            target_user_id=self._target_user_id,
            raw_summary=self._raw_summary,
            polished_description=new_polished,
            revision_count=self._revision_count + 1,
            thread=self._thread,
            register_channel=self._register_channel,
        )
        await self._thread.send(
            content=(
                f"📝 재정리 (시도 {self._revision_count + 1}):\n\n"
                f"{new_polished}\n\n"
                f"등록할까요?"
            ),
            view=new_view,
        )
        self.stop()

    async def on_timeout(self) -> None:
        logger.info(
            "📌 pin dialogue timeout: msg_id=%s revision=%d — default 취소",
            self._target_message_id, self._revision_count,
        )
        try:
            await self._thread.send("⏱ 시간 만료 — 등록 취소.")
            await self._thread.edit(archived=True)
        except Exception as exc:  # noqa: BLE001
            logger.warning("📌 pin dialogue timeout 정리 실패: %r", exc)


async def _start_pin_dialogue(
    message,  # noqa: ANN001
    target_user_id: int,
    raw_summary: str,
    register_channel,  # noqa: ANN001
) -> None:
    """Phase B+C — message 아래 Discord thread 생성 + 정리 + O/X.

    message.create_thread → claude -p 정리 → PinDialogueView 게시.
    실패 시 _do_register_directive fallback (graceful).
    """
    try:
        thread = await message.create_thread(name=f"📌 등록 확인 — {raw_summary[:50]}")
    except Exception as exc:  # noqa: BLE001
        logger.warning("📌 pin dialogue thread 생성 실패 — 즉시 등록 fallback: %r", exc)
        await _do_register_directive(
            message.guild.me._state._get_client(), str(message.id), target_user_id,
            channel=register_channel, summary=raw_summary,
        )
        return

    await thread.send("📝 정리 중… (claude -p 호출, 5-10초)")

    polished = await _generate_directive_description(raw_summary, str(message.id))

    view = PinDialogueView(
        target_message_id=str(message.id),
        target_user_id=target_user_id,
        raw_summary=raw_summary,
        polished_description=polished,
        revision_count=0,
        thread=thread,
        register_channel=register_channel,
    )
    await thread.send(
        content=f"📝 다음 내용으로 정리해서 추가할까요?\n\n{polished}",
        view=view,
    )


async def _do_register_directive(
    client: discord.Client,
    message_id: str,
    user_id: int,
    *,
    channel=None,  # noqa: ANN001 — discord channel duck-typed
    summary: str | None = None,
) -> None:
    """실제 directive 등록 — directive_append.sh + helper-queue + ✅ reaction.

    caller 가 channel + summary 알면 인자로 전달 (cost 0). 미전달 시 client.guilds
    scan fallback (cold start 등 edge case).
    """
    append_script = Path(__file__).resolve().parent / "directive_append.sh"
    if not append_script.exists():
        logger.warning("📌 pin: directive_append.sh 부재 — skip: %s", append_script)
        return

    # 인자 미전달 시 client.guilds fallback fetch.
    if channel is None or summary is None:
        try:
            msg = None
            for guild in getattr(client, "guilds", []):
                for ch in getattr(guild, "text_channels", []):
                    try:
                        msg = await ch.fetch_message(int(message_id))
                        if msg is not None:
                            channel = ch
                            break
                    except Exception:  # noqa: BLE001
                        continue
                if msg is not None:
                    break
            if msg is not None and summary is None:
                summary = (getattr(msg, "content", "") or "").strip()[:80] or "(빈 본문)"
        except Exception as exc:  # noqa: BLE001
            logger.warning("📌 pin register fallback fetch 실패 msg_id=%s exc=%r",
                           message_id, exc)
    if summary is None or not summary:
        summary = "(빈 본문)"

    try:
        result = subprocess.run(  # noqa: S603 — script path hardcoded sibling
            ["bash", str(append_script), message_id, summary],
            check=False, timeout=10.0, capture_output=True,
        )
        if result.returncode != 0:
            logger.warning(
                "📌 pin: directive_append.sh rc=%d stderr=%r",
                result.returncode, result.stderr[:200] if result.stderr else b"",
            )
            return
    except Exception as exc:  # noqa: BLE001
        logger.warning("📌 pin: directive_append 호출 실패: %r", exc)
        return

    logger.info(
        "📌 pin registered: msg_id=%s user=%s summary=%r",
        message_id, user_id, summary,
    )

    # helper-queue polish task
    try:
        helper_queue_path = Path.home() / ".mobruji" / "helper-queue.jsonl"
        helper_queue_path.parent.mkdir(parents=True, exist_ok=True)
        polish_task = {
            "type": "directive_polish",
            "directive_id": message_id,
            "raw_body": summary,
            "ts": datetime.now(timezone.utc).isoformat(),
            "status": "pending",
        }
        with helper_queue_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(polish_task, ensure_ascii=False) + "\n")
    except OSError as exc:
        logger.warning("📌 pin: helper-queue append 실패: %r", exc)

    # ✅ reaction 부착
    if channel is not None:
        try:
            msg = await channel.fetch_message(int(message_id))
            await msg.add_reaction(PIN_REGISTERED_EMOJI)
        except Exception as exc:  # noqa: BLE001
            logger.warning("📌 pin: ✅ 부착 실패: %r", exc)


async def _handle_pin_reaction(
    client: discord.Client,
    channel_id: int,
    message_id: str,
    user_id: int,
    *,
    forum_ids: list[int] | None = None,
    guild_id: int | None = None,
) -> None:
    """📌 reaction tap → 매칭 검색 → 매칭 발견 시 사용자 confirm, 없으면 즉시 등록.

    2026-05-29 변경: 사용자 정정 path — 사전 매칭 검색 + ephemeral followup question.

    spec: docs/features/directive-pushpin-registration.md (2026-05-29 보강).
    """
    channel = client.get_channel(channel_id)
    if channel is None:
        logger.warning("📌 pin: channel %d 미발견 — skip", channel_id)
        return
    try:
        message = await channel.fetch_message(int(message_id))
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "📌 pin: fetch_message 실패 msg_id=%s exc=%r", message_id, exc
        )
        return

    summary = (getattr(message, "content", "") or "").strip()[:80] or "(빈 본문)"

    # ─── 매칭 검색 ──────────────────────────────────────────────────────────
    directive_matches = _find_matching_directives(summary)
    thread_matches = await _find_matching_cycle_threads(
        client, summary, forum_ids or [],
    )
    all_matches = directive_matches + thread_matches
    if all_matches:
        # 매칭 발견 — 사용자 confirm 요청 (채널 안 reply 메시지 + button)
        try:
            first_thread_id = next(
                (m["thread_id"] for m in all_matches if m.get("thread_id")), None,
            )
            view = PinConfirmView(
                target_message_id=message_id,
                target_user_id=user_id,
                original_summary=summary,
                first_match_thread_id=first_thread_id,
                guild_id=guild_id or 0,
            )
            content = _build_pin_match_followup(all_matches, summary)
            await channel.send(content=content, view=view, reference=message)
            logger.info(
                "📌 pin match: msg_id=%s matches=%d — confirm 요청",
                message_id, len(all_matches),
            )
            return
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "📌 pin match: confirm view 전송 실패 — 즉시 등록 fallback: %r", exc,
            )

    # 매칭 없음 — Phase B+C: 등록 직전 정리 + 사용자 O/X dialogue.
    # raw summary 가 빈 본문이면 dialogue 의미 없음 → 즉시 등록 (graceful).
    if not summary or summary == "(빈 본문)":
        await _do_register_directive(
            client, message_id, user_id, channel=channel, summary=summary,
        )
        return
    try:
        await _start_pin_dialogue(message, user_id, summary, channel)
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "📌 pin dialogue 시작 실패 — 즉시 등록 fallback: %r", exc,
        )
        await _do_register_directive(
            client, message_id, user_id, channel=channel, summary=summary,
        )


# 2026-05-29 — legacy _legacy_handle_pin_reaction 폐기 (dead code 정리).
# 새 path: _handle_pin_reaction → 매칭 검색 → confirm view 또는 _do_register_directive.


async def agent_outbox_loop(client: discord.Client) -> None:
    """Phase 2.3 — agent events 'agent_reply' / 'agent_forum_action' consume.

    1초 polling. tools/agent/ 가 events 에 INSERT 한 메시지를 Discord 로 push.
    graceful — 개별 push 실패 시 mark_consumed 만 (재시도는 agent 책임).

    2026-05-29 fix: client 인자 추가 — module-level 함수가 build_client closure
    의 client 참조 못 해 NameError 발생 사고 fix.
    """
    while True:
        try:
            rows = _agent_outbox_fetch_batch(limit=20)
            for row in rows:
                try:
                    await _agent_outbox_dispatch(client, row)
                except Exception as exc:  # noqa: BLE001
                    logger.warning(
                        "agent_outbox dispatch 실패 id=%s kind=%s exc=%r",
                        row["id"], row["kind"], exc,
                    )
                _agent_outbox_mark_consumed(row["id"])
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("agent_outbox_loop iter 실패: %r", exc)
        await asyncio.sleep(1.0)


def _agent_outbox_fetch_batch(limit: int = 20) -> list[dict]:
    """events 의 미처리 agent_reply / agent_forum_action SELECT (FIFO)."""
    try:
        conn = sqlite3.connect(str(AGENT_EVENTS_DB_PATH), isolation_level=None, timeout=5.0)
        try:
            conn.row_factory = sqlite3.Row
            rows = conn.execute(
                "SELECT id, kind, payload FROM events "
                "WHERE consumed_by IS NULL "
                "AND kind IN ('agent_reply', 'agent_forum_action') "
                "ORDER BY id ASC LIMIT ?",
                (limit,),
            ).fetchall()
        finally:
            conn.close()
    except (sqlite3.Error, OSError) as exc:
        logger.warning("agent_outbox fetch 실패: %r", exc)
        return []
    return [
        {"id": r["id"], "kind": r["kind"], "payload": json.loads(r["payload"])}
        for r in rows
    ]


def _agent_outbox_mark_consumed(event_id: int) -> None:
    """consume 완료 mark — 다음 polling skip."""
    ts_iso = datetime.now(timezone.utc).isoformat()
    try:
        conn = sqlite3.connect(str(AGENT_EVENTS_DB_PATH), isolation_level=None, timeout=5.0)
        try:
            conn.execute(
                "UPDATE events SET consumed_by = 'bot', consumed_at = ? "
                "WHERE id = ? AND consumed_by IS NULL",
                (ts_iso, event_id),
            )
        finally:
            conn.close()
    except (sqlite3.Error, OSError) as exc:
        logger.warning("agent_outbox mark_consumed 실패 id=%s: %r", event_id, exc)


async def _agent_outbox_dispatch(client: discord.Client, row: dict) -> None:
    """단일 event → Discord 실제 push (discord.py API 사용)."""
    kind = row["kind"]
    payload = row["payload"]
    if kind == "agent_reply":
        await _push_agent_reply(client, payload)
    elif kind == "agent_forum_action":
        await _dispatch_agent_forum_action(client, payload)


async def _push_agent_reply(client: discord.Client, payload: dict) -> None:
    """agent_reply → discord channel.send (또는 thread.send).

    2026-05-29 choices UI: payload.choices 가 있으면 push 후 keycap reaction
    1️⃣–🔟 미리 부착 + agent_choice_prompts ledger 에 저장. 사용자 tap 시
    on_raw_reaction_add keycap 분기가 choice value 를 user_message event 로 INSERT.
    """
    channel_id = int(payload.get("channel_id", 0))
    body = payload.get("body", "")
    thread_id = payload.get("thread_id")
    choices = payload.get("choices")
    dialogue_style = payload.get("dialogue_style")

    # 2026-05-30 — reply_to_msg_id path 폐기. 사용자 정정: "엉뚱한 메세지에 답글
    # 걸어서 답한다 — 제대로 못할 거 같으면 제거". 옛 last-user-msg-id.txt +
    # helper path 의 race + 사고. thread 안 메시지 자체가 컨텍스트 가시화 충분.
    # payload.reply_to_msg_id 는 받아도 무시 (backwards compat).

    target_id = int(thread_id) if thread_id else channel_id
    channel = client.get_channel(target_id)
    if channel is None:
        logger.warning("agent_reply: channel %s 미발견 — drop", target_id)
        return

    reference = None

    # choices 있으면 본문에 선택지 numbered list append.
    # dialogue_style="register" (directive 적재 dialogue) 면 ⭕/✏️/🗑️ 3 button,
    # 그 외 일반 N-choice 케이스 (사이클 결정 등) 는 keycap 1️⃣–🔟.
    if isinstance(choices, list) and choices:
        if dialogue_style == "register" and len(choices) <= len(REGISTER_DIALOGUE_EMOJIS):
            choice_emojis = REGISTER_DIALOGUE_EMOJIS[:len(choices)]
        else:
            choice_emojis = ["1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣",
                             "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"][:len(choices[:10])]
    else:
        choice_emojis = []

    final_body = body
    if isinstance(choices, list) and choices:
        choice_lines = "\n".join(
            f"{choice_emojis[i]} {c}" for i, c in enumerate(choices[:10])
        )
        final_body = f"{body}\n\n{choice_lines}"

    msg = await channel.send(content=final_body, reference=reference)
    logger.info(
        "agent_reply pushed: channel=%s len=%d style=%s",
        target_id, len(final_body), dialogue_style or "default",
    )

    # choices reaction 부착 + ledger
    if isinstance(choices, list) and choices:
        for i in range(min(len(choices), 10)):
            try:
                await msg.add_reaction(choice_emojis[i])
            except Exception as exc:  # noqa: BLE001
                logger.warning("agent_reply choice reaction 부착 실패 i=%d exc=%r", i, exc)
        # ledger — message_id → choices list 저장 (on_raw_reaction_add 가 lookup)
        try:
            append_agent_event("choice_prompt", {
                "message_id": str(msg.id),
                "channel_id": str(target_id),
                "choices": list(choices[:10]),
            })
        except Exception as exc:  # noqa: BLE001
            logger.warning("choice_prompt event INSERT 실패: %r", exc)


async def _dispatch_agent_forum_action(client: discord.Client, payload: dict) -> None:
    """agent_forum_action → action 별 dispatch."""
    action = payload.get("action")
    if action == "create_thread":
        await _forum_create_thread(client, payload)
    elif action == "comment":
        await _forum_comment(client, payload)
    elif action == "retag":
        await _forum_retag(client, payload)
    elif action == "edit_starter":
        await _forum_edit_starter(client, payload)
    else:
        logger.warning("agent_forum_action: unknown action=%r", action)


async def _forum_create_thread(client: discord.Client, payload: dict) -> None:
    forum_id = int(payload.get("forum_id", 0))
    title = payload.get("title", "")[:99]
    body = payload.get("body", "")
    forum = client.get_channel(forum_id)
    if forum is None or not hasattr(forum, "create_thread"):
        logger.warning("forum_create_thread: forum %s 미발견 / type 불일치 — drop", forum_id)
        return
    result = await forum.create_thread(name=title, content=body)
    logger.info("forum_create_thread: forum=%s thread=%s", forum_id, getattr(result.thread, "id", "?"))


async def _forum_comment(client: discord.Client, payload: dict) -> None:
    thread_id = int(payload.get("thread_id", 0))
    body = payload.get("body", "")
    thread = client.get_channel(thread_id)
    if thread is None:
        logger.warning("forum_comment: thread %s 미발견 — drop", thread_id)
        return
    await thread.send(content=body)
    logger.info("forum_comment: thread=%s len=%d", thread_id, len(body))


async def _forum_retag(client: discord.Client, payload: dict) -> None:
    thread_id = payload.get("thread_id")
    tag_name = payload.get("tag_name")
    if not thread_id or not tag_name:
        return
    thread = client.get_channel(int(thread_id))
    if thread is None or not hasattr(thread, "parent"):
        logger.warning("forum_retag: thread %s 미발견 — drop", thread_id)
        return
    forum = thread.parent
    target_tag = None
    for tag in getattr(forum, "available_tags", []):
        if tag.name == tag_name:
            target_tag = tag
            break
    if target_tag is None:
        logger.warning("forum_retag: tag=%r forum 의 available_tags 에 없음 — drop", tag_name)
        return
    await thread.edit(applied_tags=[target_tag])
    logger.info("forum_retag: thread=%s tag=%s", thread_id, tag_name)


async def _forum_edit_starter(client: discord.Client, payload: dict) -> None:
    """forum thread starter message body PATCH (starter message_id = thread_id)."""
    thread_id = int(payload.get("thread_id", 0))
    body = payload.get("body", "")
    thread = client.get_channel(thread_id)
    if thread is None:
        logger.warning("forum_edit_starter: thread %s 미발견 — drop", thread_id)
        return
    try:
        starter = await thread.fetch_message(thread_id)
        await starter.edit(content=body)
        logger.info("forum_edit_starter: thread=%s len=%d", thread_id, len(body))
    except Exception as exc:  # noqa: BLE001
        logger.warning("forum_edit_starter 실패 thread=%s exc=%r", thread_id, exc)


def _lookup_directive_by_thread_id(thread_id: str) -> str | None:
    """directive-board.jsonl 에서 thread_id 매칭 → directive_id 반환.

    E2 옵션 (2026-05-29) — forum thread 안 사용자 메시지 시 어떤 directive 의
    thread 인지 매핑. 미매칭 시 None — agent 가 forum_kind 만으로 답.
    """
    board_path = Path.home() / ".mobruji" / "directive-board.jsonl"
    if not board_path.exists():
        return None
    try:
        for line in board_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue
            if str(entry.get("thread_id") or "") == thread_id:
                return str(entry.get("message_id") or entry.get("directive_id") or "")
    except OSError:
        return None
    return None


def append_agent_event(kind: str, payload: dict) -> int:
    """Phase 2.1 — new agent (tools/agent/) 의 events 테이블 에 INSERT.

    legacy 영향 0 — dual write. agent SQLite schema 가 없으면 첫 호출 시 생성.

    spec: tools/agent/events.py (같은 schema). bot.py 가 events 를 직접 INSERT 함.
    agent 가 polling SELECT consumed_by IS NULL → 처리 → mark_consumed.

    graceful — SQLite 실패는 warning 만. legacy path (tmux send / inbox) 가 보장.
    """
    _SCHEMA = (
        "CREATE TABLE IF NOT EXISTS events ("
        "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
        "  kind TEXT NOT NULL,"
        "  payload TEXT NOT NULL,"
        "  ts_iso TEXT NOT NULL,"
        "  consumed_by TEXT,"
        "  consumed_at TEXT"
        ");"
        "CREATE INDEX IF NOT EXISTS idx_events_unconsumed "
        "  ON events (consumed_by, id) WHERE consumed_by IS NULL;"
        "CREATE TABLE IF NOT EXISTS agent_state ("
        "  key TEXT PRIMARY KEY,"
        "  value TEXT NOT NULL,"
        "  updated_at TEXT NOT NULL"
        ");"
    )
    try:
        AGENT_EVENTS_DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(str(AGENT_EVENTS_DB_PATH), isolation_level=None, timeout=10.0)
        try:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(_SCHEMA)
            ts_iso = datetime.now(timezone.utc).isoformat()
            cursor = conn.execute(
                "INSERT INTO events (kind, payload, ts_iso) VALUES (?, ?, ?)",
                (kind, json.dumps(payload, ensure_ascii=False), ts_iso),
            )
            return cursor.lastrowid or 0
        finally:
            conn.close()
    except (sqlite3.Error, OSError) as exc:
        logger.warning("agent dual write 실패 kind=%s exc=%r", kind, exc)
        return 0


def write_last_user_msg_id(message_id: str) -> None:
    """`~/.mobruji/last-user-msg-id.txt` 에 최신 사용자 message_id 를 atomic write (#946).

    discord-reply.sh bare body (본답) 모드가 이 파일을 읽어 Discord REST API
    payload 에 `message_reference: {message_id, channel_id, fail_if_not_exists: false}`
    를 포함시켜 자동으로 사용자 메시지에 reply 형태로 push.

    동작:
      - 부모 디렉토리 부재 시 생성.
      - mktemp + os.replace 로 same-fs atomic rename — 동시 on_message 가
        부분 파일을 읽지 않도록 보장 (POSIX rename(2) atomicity).
      - 파일 권한 0o600 강제 — message_id 자체는 민감 정보 아니지만 PII
        디렉토리 일관성 유지 (helper-queue.jsonl 등과 동일).
      - 실패는 warning 만 — reply 표시 누락은 quality-of-life 저하일 뿐
        본 forwarding 흐름을 막아서는 안 됨.

    Args:
        message_id: Discord message snowflake (정수 또는 문자열). 항상 str
            로 받아 그대로 저장. invalid snowflake (#964) 는 skip + warning.
    """
    # #964 (2026-05-24): invalid snowflake 거부 가드.
    # 정상 코드 경로 (str(message.id)) 로는 항상 18-19 digit snowflake 이므로
    # 이 가드에 걸리는 케이스는 외부 오염 / 수동 echo / 디버깅 잔재. 사용자 본답
    # reply 가 깨지면 채팅창에 "메시지를 불러올 수 없어요" 가 노출되므로
    # (사용자 UX 회귀) write 자체를 막아 파일이 valid snowflake 만 보유하도록 보장.
    if (
        not isinstance(message_id, str)
        or not message_id.isdigit()
        or not (
            LAST_USER_MSG_ID_MIN_DIGITS
            <= len(message_id)
            <= LAST_USER_MSG_ID_MAX_DIGITS
        )
    ):
        logger.warning(
            "write_last_user_msg_id: invalid snowflake %r — skip (#964 가드)",
            message_id,
        )
        return

    import tempfile as _tempfile  # local — module top scope 변경 회피.
    try:
        LAST_USER_MSG_ID_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmp_fd, tmp_path = _tempfile.mkstemp(
            prefix=".last-user-msg-id-",
            dir=str(LAST_USER_MSG_ID_PATH.parent),
        )
        try:
            with os.fdopen(tmp_fd, "w", encoding="utf-8") as handle:
                handle.write(message_id)
            os.chmod(tmp_path, LAST_USER_MSG_ID_FILE_MODE)
            os.replace(tmp_path, LAST_USER_MSG_ID_PATH)
        except OSError:
            if tmp_path and os.path.exists(tmp_path):
                try:
                    os.unlink(tmp_path)
                except OSError:
                    pass
            raise
    except OSError as exc:
        logger.warning("last-user-msg-id 기록 실패: %s", exc)

    # 2026-05-29: helper-current-target.txt 동시 갱신 — helper-turn-start.sh 호출
    # 누락 시에도 reply target 이 stale 되지 않도록 코드 강제. helper LLM wrapper
    # 호출 의존 폐기. spec: helper-current-target-bot-side-write.md.
    try:
        HELPER_CURRENT_TARGET_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmp_fd2, tmp_path2 = _tempfile.mkstemp(
            prefix=".helper-current-target-",
            dir=str(HELPER_CURRENT_TARGET_PATH.parent),
        )
        try:
            with os.fdopen(tmp_fd2, "w", encoding="utf-8") as handle:
                handle.write(message_id)
            os.chmod(tmp_path2, LAST_USER_MSG_ID_FILE_MODE)
            os.replace(tmp_path2, HELPER_CURRENT_TARGET_PATH)
        except OSError:
            if tmp_path2 and os.path.exists(tmp_path2):
                try:
                    os.unlink(tmp_path2)
                except OSError:
                    pass
            raise
    except OSError as exc:
        logger.warning("helper-current-target 기록 실패: %s", exc)


def build_reply_context_prefix(
    referenced_content: str | None,
    user_body: str,
    *,
    preview_len: int = REPLY_CONTEXT_PREVIEW_LEN,
) -> str:
    """사용자 Discord "답장" 메시지에 reference 본문 요약 prefix 를 붙입니다 (#880).

    Discord 의 reply 기능은 `Message.reference.message_id` + 서버에서 hydrate 한
    `Message.referenced_message` 객체로 제공됩니다. 이 함수는 referenced_message
    가 있을 때만 `[답장→ <preview>] <user 본문>` 형태로 helper 에게 전달할
    문자열을 만듭니다.

    Args:
        referenced_content: ``Message.referenced_message.content`` 원문, 또는
            None / 빈 문자열 (답장이 아닐 때).
        user_body: 사용자가 새로 작성한 메시지 본문.
        preview_len: 원문 미리보기 글자 수. 줄바꿈은 공백으로 치환.

    Returns:
        - referenced_content 가 None / 빈 문자열 → ``user_body`` 그대로
          (기존 호환).
        - 그 외 → ``[답장→ <preview>] <user 본문>``.
    """
    if not referenced_content:
        return user_body
    # 멀티라인 reference 는 한 줄로 만들고 N자 컷.
    flattened = " ".join(referenced_content.split())
    if not flattened:
        return user_body
    if len(flattened) > preview_len:
        preview = flattened[: preview_len - 1] + "…"
    else:
        preview = flattened
    return REPLY_CONTEXT_PREFIX_TEMPLATE.format(preview=preview, body=user_body)


def sanitize_mentions(text: str) -> str:
    """Discord mention 토큰을 zero-width space 로 무력화합니다.

    digest 안 PR title / 진행 메시지에 `@everyone` / `@here` / `<@USER_ID>` 가
    들어가도 실제 알림이 발생하지 않도록 prefix 직후에 U+200B 삽입.
    """
    sanitized = text
    for pattern, replacement in MENTION_SANITIZE_PATTERNS:
        sanitized = pattern.sub(replacement, sanitized)
    return sanitized


class DedupLedger:
    """SQLite 기반 dedup ledger — 사용자 메시지 중복 처리 방지.

    spec Q6 답: SQLite (JSONL 대비 TTL GC / 동시성 안전).
    """

    SCHEMA = (
        "CREATE TABLE IF NOT EXISTS processed_messages ("
        "  message_id TEXT PRIMARY KEY,"
        "  processed_at INTEGER NOT NULL"
        ")"
    )

    def __init__(self, db_path: str) -> None:
        self.db_path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(db_path, check_same_thread=False, timeout=5.0)
        self._lock = threading.Lock()
        with self._lock:
            self._conn.execute(self.SCHEMA)
            self._conn.commit()
        try:
            os.chmod(db_path, 0o600)
        except OSError:
            pass

    def is_processed(self, message_id: str) -> bool:
        with self._lock:
            row = self._conn.execute(
                "SELECT 1 FROM processed_messages WHERE message_id = ?",
                (message_id,),
            ).fetchone()
            return row is not None

    def mark_processed(self, message_id: str, now_epoch: int | None = None) -> None:
        ts = int(time.time()) if now_epoch is None else now_epoch
        with self._lock:
            self._conn.execute(
                "INSERT OR IGNORE INTO processed_messages (message_id, processed_at) VALUES (?, ?)",
                (message_id, ts),
            )
            self._conn.commit()

    def claim(self, message_id: str, now_epoch: int | None = None) -> bool:
        """is_processed + mark_processed 를 단일 SQLite 트랜잭션으로 원자화 (#909 F-3).

        Discord Gateway reconnect / on_message 콜백 동시성 race 방지:
        ``INSERT OR IGNORE`` 후 ``rowcount`` 검사로 신규 claim 여부 판단.

        Returns:
            True  — 신규 claim 성공 (호출자가 처리 진행).
            False — 이미 처리된 message_id (호출자가 즉시 return).
        """
        ts = int(time.time()) if now_epoch is None else now_epoch
        with self._lock:
            cursor = self._conn.execute(
                "INSERT OR IGNORE INTO processed_messages (message_id, processed_at) VALUES (?, ?)",
                (message_id, ts),
            )
            self._conn.commit()
            return cursor.rowcount > 0

    def gc(self, ttl_seconds: int = DEDUP_TTL_SECONDS) -> int:
        cutoff = int(time.time()) - ttl_seconds
        with self._lock:
            cursor = self._conn.execute(
                "DELETE FROM processed_messages WHERE processed_at < ?",
                (cutoff,),
            )
            self._conn.commit()
            return cursor.rowcount


def start_dedup_gc_thread(ledger: DedupLedger) -> None:
    """백그라운드에서 주기적으로 dedup ledger GC 를 수행합니다."""

    def loop() -> None:
        while True:
            time.sleep(DEDUP_GC_INTERVAL_SECONDS)
            try:
                deleted = ledger.gc()
                if deleted:
                    logger.info("dedup ledger GC: deleted=%d", deleted)
            except sqlite3.Error as exc:
                logger.warning("dedup ledger GC 실패: %s", exc)

    threading.Thread(target=loop, name="dedup-gc", daemon=True).start()


def tmux_has_session(session_name: str) -> bool:
    result = subprocess.run(
        ["tmux", "has-session", "-t", session_name],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    return result.returncode == 0


def tmux_create_session(session_name: str, claude_bin: str) -> bool:
    """tmux new-session -d 로 helper 세션을 만듭니다."""
    cmd = ["tmux", "new-session", "-d", "-s", session_name, claude_bin]
    result = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        logger.error(
            "tmux new-session 실패: rc=%d stderr=%s",
            result.returncode,
            truncate_for_log(result.stderr or ""),
        )
        return False
    logger.info("tmux 세션 생성: session=%s claude_bin=%s", session_name, claude_bin)
    return True


def ensure_tmux_session(session_name: str, claude_bin: str) -> bool:
    if tmux_has_session(session_name):
        return True
    return tmux_create_session(session_name, claude_bin)


def resolve_sentinel(text: str) -> list[str] | None:
    """`/system:<key>` 형태 sentinel 을 tmux send-keys 인자 리스트로 변환합니다.

    Q5 답(c): 명시 sentinel 만 종료/제어 입력으로 받습니다. `/exit` 같은
    일반 슬래시 명령은 helper Claude TUI 에 그대로 전달합니다.
    """
    if not text.startswith(SENTINEL_PREFIX):
        return None
    key = text[len(SENTINEL_PREFIX):].strip().lower()
    return SENTINEL_KEYS.get(key)


def tmux_send_payload(target_pane: str, text: str) -> bool:
    """일반 텍스트는 `-l` (literal) 로 보낸 뒤 Enter 키를 누릅니다.

    sentinel(/system:ctrl-c 등) 은 컨트롤 키 인자로 직접 전송합니다.
    multiline 은 줄마다 `-l` + Enter.
    """
    sentinel = resolve_sentinel(text)
    if sentinel is not None:
        cmd = ["tmux", "send-keys", "-t", target_pane, *sentinel]
        result = subprocess.run(cmd, check=False, capture_output=True, text=True)
        if result.returncode != 0:
            logger.error(
                "tmux send-keys (sentinel) 실패: rc=%d stderr=%s",
                result.returncode,
                truncate_for_log(result.stderr or ""),
            )
            return False
        logger.info("tmux send-keys sentinel: %s", sentinel)
        return True

    lines = text.split("\n")
    for line in lines:
        if line:
            literal_cmd = ["tmux", "send-keys", "-t", target_pane, "-l", line]
            result = subprocess.run(literal_cmd, check=False, capture_output=True, text=True)
            if result.returncode != 0:
                logger.error(
                    "tmux send-keys (-l) 실패: rc=%d stderr=%s",
                    result.returncode,
                    truncate_for_log(result.stderr or ""),
                )
                return False
        enter_cmd = ["tmux", "send-keys", "-t", target_pane, "Enter"]
        result = subprocess.run(enter_cmd, check=False, capture_output=True, text=True)
        if result.returncode != 0:
            logger.error(
                "tmux send-keys (Enter) 실패: rc=%d stderr=%s",
                result.returncode,
                truncate_for_log(result.stderr or ""),
            )
            return False
    return True


# ─────────────────────────────────────────────────────────────────────────────
# cycle-status digest (사용자 룰 2026-05-23)
# ─────────────────────────────────────────────────────────────────────────────


def read_cycle_status(path: str = DEFAULT_CYCLE_STATUS_PATH) -> dict | None:
    """`~/.mobruji/cycle-status.json` 을 읽어 dict 로 반환합니다.

    nmae(maestro 본진) 가 매 sub-agent launch/완료/머지 시 실시간 갱신하는
    상태 파일입니다. 파일이 없거나 JSON 파싱이 실패하면 None 을 반환하고,
    호출부 (`format_cycle_digest`) 가 graceful fallback 합니다.

    스키마:
        {
            "be":  {"in_progress": str|dict|null, "last_completed": {...}|null},
            "fe":  {...},
            "rev": {...},
            "plan": {...}
        }
        in_progress (dict 형식, nmae 현행):
            be/fe/plan:
                {"issue": "#NNN", "pr": "#MMM"|null,
                 "title": str, "started_at": ISO8601}
            rev:
                {"target": str, "title": str, "started_at": ISO8601}
            str 형식(legacy)도 계속 지원합니다 — trim 후 그대로 노출.
        last_completed = {"pr": "#NNN"|null, "title": str, "merged_at": ISO8601}
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        logger.warning("cycle-status.json 없음: path=%s", path)
        return None
    except json.JSONDecodeError as exc:
        logger.warning("cycle-status.json JSON 파싱 실패: path=%s err=%s", path, exc)
        return None
    except OSError as exc:
        logger.warning("cycle-status.json 읽기 실패: path=%s err=%s", path, exc)
        return None


def classify_nmae_status(
    cycle_status_path: str,
    *,
    emoji_idle: str = BOT_SECONDARY_REACTION_EMOJI_IDLE_DEFAULT,
    emoji_partial: str = BOT_SECONDARY_REACTION_EMOJI_PARTIAL_DEFAULT,
    emoji_full: str = BOT_SECONDARY_REACTION_EMOJI_FULL_DEFAULT,
) -> str | None:
    """nmae 4 워크트리(be/fe/rev/plan) 점유 상태를 emoji 로 분류합니다 (#1080).

    cycle-status.json 의 각 워크트리 ``in_progress`` 가 None (또는 워크트리 키
    자체 누락) 이면 idle, dict/str (truthy) 이면 occupied 로 본다.

    반환:
        - 0 occupied → ``emoji_idle`` (즉시 가능)
        - 1~3 occupied → ``emoji_partial`` (작업 중)
        - 4 occupied → ``emoji_full`` (대기 큐잉)
        - 파일 부재 / parse 실패 / 스키마 깨짐 → ``None`` (silent skip)

    secondary reaction (BOT_SECONDARY_REACTION_ENABLED) 호출부가 본 결과를
    그대로 message.add_reaction() 인자로 사용한다.
    """
    status = read_cycle_status(cycle_status_path)
    if status is None:
        return None
    if not isinstance(status, dict):
        logger.warning(
            "cycle-status.json 스키마 dict 아님: type=%s — secondary reaction skip",
            type(status).__name__,
        )
        return None
    occupied = 0
    for worktree in NMAE_WORKTREES:
        entry = status.get(worktree)
        if not isinstance(entry, dict):
            # 워크트리 키 자체 누락 또는 None — idle 로 간주.
            continue
        in_progress = entry.get("in_progress")
        if in_progress:
            occupied += 1
    if occupied == 0:
        return emoji_idle
    if occupied >= len(NMAE_WORKTREES):
        return emoji_full
    return emoji_partial


def read_cycle_counts(path: str = DEFAULT_CYCLE_COUNTER_PATH) -> dict | None:
    """`~/.mobruji/cycle-counter.json` 을 읽어 dict 로 반환합니다 (#996).

    update.py 가 set-active 호출마다 ``counts[ws] += 1`` 갱신, KST 자정 경계
    감지 시 0 reset. digest embed 가 read 해서 한 줄 추가.

    스키마:
        {"date": "YYYY-MM-DD", "counts": {"be": int, "fe": int, "rev": int, "plan": int}}

    동작:
        - 파일 부재 / JSON 깨짐 / dict 아님 → None (graceful skip).
        - date 가 오늘(KST) 와 다르면 카운트 무효로 보고 모두 0 반환 (lazy reset).
          (디스크 갱신은 update.py 의 set-active 시점에서 처리.)
    """
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (json.JSONDecodeError, OSError) as exc:
        logger.warning("cycle-counter.json 읽기 실패: path=%s err=%s", path, exc)
        return None
    if not isinstance(data, dict):
        return None

    today_str = datetime.now(CYCLE_DIGEST_TZ).strftime("%Y-%m-%d")
    counts_raw = data.get("counts")
    if data.get("date") == today_str and isinstance(counts_raw, dict):
        normalized: dict[str, int] = {}
        for ws in CYCLE_DIGEST_WORKSPACES:
            value = counts_raw.get(ws)
            normalized[ws] = value if isinstance(value, int) and value >= 0 else 0
        return {"date": today_str, "counts": normalized}
    # 날짜 바뀜 — 자정 경과 후 첫 read. 카운트는 모두 0 으로 표시.
    return {"date": today_str, "counts": {ws: 0 for ws in CYCLE_DIGEST_WORKSPACES}}


def _format_cycle_counter_line(counts: dict[str, int]) -> str:
    """digest embed field value 한 줄 — ``be=12 / fe=8 / rev=15 / plan=4``."""
    parts = [f"{ws}={counts.get(ws, 0)}" for ws in CYCLE_DIGEST_WORKSPACES]
    return " / ".join(parts)


def _format_in_progress(raw: object) -> str:
    """`in_progress` 필드를 한 줄 label 로 변환합니다.

    수용 형식:
        - None / 누락 / 빈 문자열 / 미지원 타입 → ``"idle"``
        - ``str`` (legacy) → trim 후 그대로 반환
        - ``dict`` → 우선순위:
            1. ``issue`` 또는 ``pr`` 있으면 ``"<id> <title>"``
               (둘 다 있으면 ``issue`` 우선 — nmae 가 issue 를 1차 식별자로 씀)
            2. ``target`` 있으면 ``"<target>: <title>"`` (rev 워크트리)
            3. ``title`` 만 있으면 ``"<title>"``
            4. 셋 다 없으면 ``"진행 중(스키마 미상)"`` — 이론상 도달 안 함.

    title 누락이거나 str 이 아니면 ``"제목 없음"`` 으로 대체.
    """
    if isinstance(raw, str):
        stripped = raw.strip()
        return stripped if stripped else "idle"
    if isinstance(raw, dict):
        title_raw = raw.get("title")
        title_text = (
            title_raw.strip()
            if isinstance(title_raw, str) and title_raw.strip()
            else "제목 없음"
        )

        def _str_or_none(value: object) -> str | None:
            if isinstance(value, str) and value.strip():
                return value.strip()
            return None

        issue_id = _str_or_none(raw.get("issue")) or _str_or_none(raw.get("pr"))
        if issue_id is not None:
            return f"{issue_id} {title_text}"
        target = _str_or_none(raw.get("target"))
        if target is not None:
            return f"{target}: {title_text}"
        if isinstance(title_raw, str) and title_raw.strip():
            return title_text
        return "진행 중(스키마 미상)"
    return "idle"


def _truncate_field_line(text: str, limit: int = CYCLE_DIGEST_FIELD_LINE_LEN) -> str:
    """Field value 한 줄 길이 가드. limit 초과 시 ``…`` suffix 로 표시."""
    if len(text) > limit:
        return text[: limit - 1] + "…"
    return text


def format_cycle_digest(
    status: dict | None,
    now: datetime | None = None,
    *,
    interval_seconds: int | None = None,
    cycle_counts: dict | None = None,
    directive_summary: dict | None = None,
) -> tuple["discord.Embed", str]:
    """4 워크트리(be/fe/rev/plan) digest 를 Discord Embed 로 빌드합니다.

    UX 개선 #840: 기존 plain markdown multiline 텍스트 → Discord native embed.
    스캔 친화적 시각 hierarchy + 워크트리별 emoji prefix + 활동 색상.

    Args:
        status: `read_cycle_status()` 반환 dict, 또는 None (파일 없음/깨짐).
        now: 헤더 timestamp 산출 기준 시각. 기본값 None → 호출 시점 KST.
            테스트 deterministic 용으로만 외부 주입.
        interval_seconds: footer 에 ``interval=Ns`` 명시. None 이면 footer 생략.
        cycle_counts: ``read_cycle_counts()`` 반환 dict (#996). None 이면 cycles
            field 생략 — 파일 부재 graceful skip. 스키마:
            ``{"date": "YYYY-MM-DD", "counts": {"be": N, "fe": N, "rev": N, "plan": N}}``.

    Returns:
        (embed, signature) 튜플.
          - embed: ``discord.Embed`` 인스턴스 (channel.send(embed=...) 로 push).
          - signature: delta 비교용 (시간 무관). 동일 signature 면 heartbeat 만 push.

    Embed 구조:
        title       : ``🔁 Cycle Digest``
        description : ``🕒 YYYY-MM-DD HH:MM KST``
        color       : 모두 idle → gray, 그 외 → blue
        fields      : be/fe/rev/plan 4 개 (inline=False), 각:
                        name  = "🛠 be" (워크트리별 emoji)
                        value = "진행: ...\\n최근: ..." (2 줄)
                       + (cycle_counts 주어진 경우) "⏱ Cycles (today KST)" 1 개:
                        value = "be=N / fe=N / rev=N / plan=N"
        footer.text : f"interval={N}s" (interval_seconds 주어진 경우)
        timestamp   : ``now`` (KST). Discord 클라이언트 locale 로 footer 옆에 렌더.

    스키마 누락/타입 이상 시 해당 필드만 "idle" / "없음" 으로 대체합니다.
    cycle-status.json 자체 읽기 실패 시 (status=None) description 에 fallback
    한 줄 추가, signature="unavailable" 반환.

    cycle_counts 는 signature 에 포함하지 않습니다 — 카운트 증가 마다 delta push
    가 발생하면 noise 증폭. heartbeat 호흡 (#기본 1h) 으로 최신 카운트 자연 갱신.
    단 ``date`` 는 signature 에 포함하여 자정 경계 (rollover) 시 즉시 push.
    """
    if now is None:
        now = datetime.now(CYCLE_DIGEST_TZ)
    elif now.tzinfo is not None:
        now = now.astimezone(CYCLE_DIGEST_TZ)
    timestamp_text = f"🕒 {now.strftime(CYCLE_DIGEST_TIME_FORMAT)}"

    embed = discord.Embed(
        title="🔁 Cycle Digest",
        description=timestamp_text,
        color=CYCLE_DIGEST_COLOR_ACTIVE,
    )
    embed.timestamp = now

    if status is None or not isinstance(status, dict):
        embed.description = (
            f"{timestamp_text}\n(cycle-status.json 읽기 실패 — 본진 갱신 대기)"
        )
        embed.color = CYCLE_DIGEST_COLOR_IDLE
        _maybe_add_cycle_counts_field(embed, cycle_counts)
        directive_sig = _maybe_add_directive_board_field(embed, directive_summary)
        if interval_seconds is not None:
            embed.set_footer(text=f"interval={interval_seconds}s")
        signature = "unavailable"
        if directive_sig:
            signature = f"{signature}||{directive_sig}"
        return embed, signature

    sig_parts: list[str] = []
    any_active = False

    for ws in CYCLE_DIGEST_WORKSPACES:
        entry = status.get(ws)
        if not isinstance(entry, dict):
            in_progress_text = "idle"
            recent_text = "없음"
            sig_parts.append(f"{ws}=missing")
        else:
            in_progress_text = _format_in_progress(entry.get("in_progress"))

            last_completed = entry.get("last_completed")
            if isinstance(last_completed, dict):
                pr_raw = last_completed.get("pr")
                title_raw = last_completed.get("title")
                title_text = (
                    title_raw.strip()
                    if isinstance(title_raw, str) and title_raw.strip()
                    else "제목 없음"
                )
                if isinstance(pr_raw, str) and pr_raw.strip():
                    recent_text = f"{pr_raw.strip()} ({title_text})"
                else:
                    recent_text = title_text
            else:
                recent_text = "없음"
            sig_parts.append(f"{ws}={in_progress_text}|{recent_text}")

        if in_progress_text != "idle":
            any_active = True

        in_progress_text = sanitize_mentions(in_progress_text)
        recent_text = sanitize_mentions(recent_text)

        in_progress_text = _truncate_field_line(in_progress_text)
        recent_text = _truncate_field_line(recent_text)

        emoji = CYCLE_DIGEST_WORKSPACE_EMOJI.get(ws, "")
        field_name = f"{emoji} {ws}".strip() if emoji else ws
        # 가독성 개선 (#1023 사용자 P0): 진행 vs 최근 구분이 모호하다는 피드백 반영.
        # 1) 두 줄 사이 빈 줄 — 시선 분리.
        # 2) ``🔄 진행`` / ``✅ 최근`` emoji + 라벨 — 첫 눈에 의미 인지.
        # 3) 들여쓰기 (전각 공백) — 라벨과 본문 시각 위계 분리.
        # 4) 후방호환: 본문에 ``진행:`` / ``최근:`` 문자열을 유지 (test fixture
        #    keyword 매칭 + 메모리/스크린샷 분석 도구가 라벨로 grep).
        field_value = (
            f"🔄 진행: {in_progress_text}\n"
            f"\n"
            f"✅ 최근: {recent_text}"
        )
        embed.add_field(name=field_name, value=field_value, inline=False)

    embed.color = CYCLE_DIGEST_COLOR_ACTIVE if any_active else CYCLE_DIGEST_COLOR_IDLE
    _maybe_add_cycle_counts_field(embed, cycle_counts)
    directive_sig = _maybe_add_directive_board_field(embed, directive_summary)
    if interval_seconds is not None:
        embed.set_footer(text=f"interval={interval_seconds}s")

    # cycle_counts 의 date 만 signature 에 포함 — 자정 경계에서 즉시 push 트리거.
    # counts 자체는 noise 회피 위해 제외 (heartbeat 호흡으로 자연 갱신).
    if isinstance(cycle_counts, dict):
        date_part = cycle_counts.get("date")
        if isinstance(date_part, str) and date_part:
            sig_parts.append(f"cycles-date={date_part}")

    # directive-board mismatch 카운트는 signature 에 포함 — mismatch 발생/회복
    # 시 즉시 사용자에게 가시화 push (P11 사용자 P0 사고 fix).
    if directive_sig:
        sig_parts.append(directive_sig)

    signature = "||".join(sig_parts)
    return embed, signature


def _maybe_add_cycle_counts_field(
    embed: "discord.Embed", cycle_counts: dict | None
) -> None:
    """``cycle_counts`` dict 가 valid 하면 embed 에 cycles field 1개 추가합니다 (#996).

    None / dict 아님 / counts 키 누락 / 모든 카운트 0 + counter 부재 → skip.
    counts 키만 있으면 0 값도 표시 (사용자가 활동 0 확인 가능).
    """
    if not isinstance(cycle_counts, dict):
        return
    counts = cycle_counts.get("counts")
    if not isinstance(counts, dict):
        return
    normalized: dict[str, int] = {}
    for ws in CYCLE_DIGEST_WORKSPACES:
        value = counts.get(ws)
        normalized[ws] = value if isinstance(value, int) and value >= 0 else 0
    line = _format_cycle_counter_line(normalized)
    embed.add_field(
        name="⏱ Cycles (today KST)",
        value=line,
        inline=False,
    )


def _maybe_add_directive_board_field(
    embed: "discord.Embed", directive_summary: dict | None
) -> str | None:
    """``directive_summary`` dict 가 valid 하면 embed 에 directive-board field 추가 (#P11).

    Args:
        embed: target embed.
        directive_summary: ``{"total": N, "ok": M, "mismatch": K, "mismatch_ids": [...]}``
            (``directive_board_summary()`` 반환). None/dict 아님 → skip.

    Returns:
        signature fragment 문자열 (``"directives=ok/mis/total"``) 또는 None.
        signature 에 포함하면 mismatch 발생/회복 시 즉시 delta push.
    """
    if not isinstance(directive_summary, dict):
        return None
    total = directive_summary.get("total")
    ok_count = directive_summary.get("ok")
    mismatch_count = directive_summary.get("mismatch")
    if not isinstance(total, int) or not isinstance(ok_count, int) or not isinstance(
        mismatch_count, int
    ):
        return None
    if total <= 0:
        return None
    if mismatch_count > 0:
        name = "📌 지시 보드 (mismatch)"
        value = f"동기화 OK {ok_count} / 누락 {mismatch_count} / 전체 {total}"
    else:
        name = "📌 지시 보드"
        value = f"동기화 OK {ok_count} / 전체 {total}"
    value = _truncate_field_line(value)
    embed.add_field(name=name, value=value, inline=False)
    return f"directives={ok_count}/{mismatch_count}/{total}"


def resolve_digest_interval(env_value: str | None) -> int:
    """DIGEST_INTERVAL_SECONDS env 값을 정수로 해석합니다. 부재/이상값이면 default."""
    if env_value is None:
        return DEFAULT_DIGEST_INTERVAL_SECONDS
    try:
        parsed = int(env_value)
    except ValueError:
        logger.warning(
            "DIGEST_INTERVAL_SECONDS 가 정수 아님(%r) — 기본값 사용: %d",
            env_value,
            DEFAULT_DIGEST_INTERVAL_SECONDS,
        )
        return DEFAULT_DIGEST_INTERVAL_SECONDS
    if parsed <= 0:
        logger.warning(
            "DIGEST_INTERVAL_SECONDS 가 양수 아님(%d) — 기본값 사용: %d",
            parsed,
            DEFAULT_DIGEST_INTERVAL_SECONDS,
        )
        return DEFAULT_DIGEST_INTERVAL_SECONDS
    return parsed


async def send_with_retry(
    channel: "discord.abc.Messageable",
    *,
    content: str | None = None,
    embed: "discord.Embed | None" = None,
    max_attempts: int = DISCORD_SEND_RETRY_MAX,
    base_sleep: float = DISCORD_SEND_RETRY_BASE_SEC,
    sleeper=asyncio.sleep,
) -> bool:
    """Discord ``channel.send`` 호출 + 429/5xx 재시도 (#911 G-6).

    - 429: ``HTTPException.retry_after`` (discord.py 가 응답에서 추출) 가 있으면
      그 초만큼 sleep, 없으면 ``base_sleep`` 사용.
    - 5xx: exponential backoff (``base_sleep * 2 ** (i-1)``).
    - 4xx 등 retry 불가 상태: 즉시 False 반환 + warning log.
    - ``max_attempts`` 회 시도 후에도 실패하면 False.

    호출부는 성공 여부만 알면 충분하므로 bool 반환. (raise 하지 않음 — 호출부는
    이미 broad ``except`` 안에서 호출되며, retry 후에도 실패하면 다음 iter 에서
    자연 회복하길 기대.)

    sleeper 인자는 테스트 용 — 실제 sleep 없이 path 만 검증할 때 stub.
    """
    last_exc: Exception | None = None
    for attempt in range(1, max_attempts + 1):
        try:
            if embed is not None:
                await channel.send(content=content, embed=embed)
            else:
                # content is required when no embed; assume caller guarantees this.
                await channel.send(content)
            return True
        except discord.HTTPException as exc:
            last_exc = exc
            status = getattr(exc, "status", None)
            if status == 429:
                retry_after = getattr(exc, "retry_after", None)
                sleep_sec = (
                    float(retry_after)
                    if retry_after is not None
                    else base_sleep
                )
                logger.warning(
                    "send_with_retry: 429 rate limit — retry %d/%d after %.2fs",
                    attempt,
                    max_attempts,
                    sleep_sec,
                )
            elif status is not None and 500 <= status < 600:
                sleep_sec = base_sleep * (2 ** (attempt - 1))
                logger.warning(
                    "send_with_retry: %d server error — retry %d/%d after %.2fs",
                    status,
                    attempt,
                    max_attempts,
                    sleep_sec,
                )
            else:
                # 4xx etc — retry 무의미.
                logger.warning(
                    "send_with_retry: non-retryable HTTPException status=%s err=%s",
                    status,
                    exc,
                )
                return False
        except (asyncio.CancelledError, KeyboardInterrupt):
            raise
        except Exception as exc:  # noqa: BLE001
            # 네트워크 layer 예외 (ConnectionError, TimeoutError 등) — backoff retry.
            last_exc = exc
            sleep_sec = base_sleep * (2 ** (attempt - 1))
            logger.warning(
                "send_with_retry: transient error — retry %d/%d after %.2fs err=%s",
                attempt,
                max_attempts,
                sleep_sec,
                exc,
            )
        # 마지막 attempt 직후엔 sleep 안 함 — 의미 없음 (어차피 더 시도 안 함).
        if attempt < max_attempts:
            await sleeper(sleep_sec)
    logger.warning(
        "send_with_retry: %d 회 시도 후 실패 last_err=%s",
        max_attempts,
        last_exc,
    )
    return False


async def digest_loop(
    client: "discord.Client",
    channel_id: int,
    *,
    interval: int = DEFAULT_DIGEST_INTERVAL_SECONDS,
    initial_delay: int = DIGEST_INITIAL_DELAY_SECONDS,
    heartbeat_seconds: int = DIGEST_HEARTBEAT_SECONDS,
    time_source=time.monotonic,
    cycle_status_path: str = DEFAULT_CYCLE_STATUS_PATH,
    cycle_counter_path: str = DEFAULT_CYCLE_COUNTER_PATH,
    directive_board_state_path: Path | None = None,
) -> None:
    """on_ready 직후 launch. interval 초 마다 cycle-status digest 를 push 합니다.

    사용자 룰 (2026-05-23 #모부르지): nmae 가 실시간 갱신하는
    `~/.mobruji/cycle-status.json` 의 4 워크트리(be/fe/rev/plan) 진행/최근을
    Discord embed (UX 개선 #840) 로 push 합니다.

    - 직전 push 와 signature(시간 제외) 가 동일하면 noise 라고 보고 skip.
    - signature 가 바뀌면 즉시 push (= delta push).
    - 동일해도 마지막 push 로부터 heartbeat_seconds 경과 시 한 번 push (생존 신호).
    - 첫 iter 는 last signature 가 없으므로 무조건 push (초기 baseline).

    bot 종료 시 cancel 됩니다. asyncio.CancelledError 는 외부로 전파.
    """
    await asyncio.sleep(initial_delay)
    last_signature: str | None = None
    last_pushed_at: float | None = None
    while True:
        try:
            channel = client.get_channel(channel_id)
            if channel is None:
                logger.warning("digest: channel_id=%s 찾을 수 없음 — skip 후 재시도", channel_id)
            else:
                status = read_cycle_status(cycle_status_path)
                cycle_counts = read_cycle_counts(cycle_counter_path)
                directive_summary: dict | None = None
                if directive_board_state_path is not None:
                    try:
                        directive_summary = directive_board_summary(
                            directive_board_state_path
                        )
                    except Exception as exc:  # noqa: BLE001
                        logger.warning(
                            "directive_board_summary 실패 (digest 진행 — fallback None): %s",
                            exc,
                        )
                        directive_summary = None
                embed, signature = format_cycle_digest(
                    status,
                    interval_seconds=interval,
                    cycle_counts=cycle_counts,
                    directive_summary=directive_summary,
                )
                now_ts = time_source()
                should_push = False
                reason = ""
                if last_signature is None:
                    should_push = True
                    reason = "initial"
                elif signature != last_signature:
                    should_push = True
                    reason = "delta"
                elif (
                    last_pushed_at is not None
                    and (now_ts - last_pushed_at) >= heartbeat_seconds
                ):
                    should_push = True
                    reason = "heartbeat"

                if should_push:
                    # #911 G-6: 429/5xx retry. 실패 시 last_signature 갱신 안 함
                    # → 다음 iter 에서 동일 signature 로 재시도 (delta 유지).
                    sent = await send_with_retry(channel, embed=embed)
                    if sent:
                        last_signature = signature
                        last_pushed_at = now_ts
                        logger.info("digest push: reason=%s signature=%s", reason, signature)
                    else:
                        logger.warning(
                            "digest push 실패 (retry 소진) reason=%s signature=%s — 다음 iter 에 재시도",
                            reason,
                            signature,
                        )
                else:
                    logger.debug(
                        "digest skip: signature unchanged (%s), since_last=%.0fs",
                        signature,
                        0.0 if last_pushed_at is None else now_ts - last_pushed_at,
                    )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("digest send 실패: %s", exc)
        record_loop_heartbeat("digest_loop")
        await asyncio.sleep(interval)


# ─────────────────────────────────────────────────────────────────────────────
# context auto-clear (spec: docs/features/context-auto-clear.md §5)
# ─────────────────────────────────────────────────────────────────────────────


def parse_context_pct(pane_text: str) -> int | None:
    """`===CTX:NN%===` marker 의 마지막 occurrence 를 정수로 파싱합니다.

    spec §5-6 옵션 A 확정: maestro 가 매 turn 끝에 self-emit. footer scrape
    아닌 단일 regex `r'===CTX:(\\d{1,3})%==='`. capture-pane 출력에 여러 turn
    이력이 누적되므로 **마지막** 매치를 사용합니다 (= 최신 turn). 매치 없거나
    숫자가 0~100 범위를 벗어나면 None.
    """
    if not pane_text:
        return None
    matches = CONTEXT_PCT_PATTERN.findall(pane_text)
    if not matches:
        return None
    try:
        pct = int(matches[-1])
    except (TypeError, ValueError):
        return None
    if pct < 0 or pct > 100:
        return None
    return pct


def capture_pane_text(pane_target: str) -> str | None:
    """`tmux capture-pane -t <pane> -p -S -<N>` 결과를 문자열로 반환합니다.

    실패 시 (세션 없음/명령 오류) None. CONTEXT_AUTO_CLEAR_CAPTURE_LINES 만큼
    스크롤백을 함께 가져와 marker 가 짧은 polling 사이에 화면 위로 밀려도
    감지되도록 합니다.
    """
    cmd = [
        "tmux",
        "capture-pane",
        "-t",
        pane_target,
        "-p",
        "-S",
        f"-{CONTEXT_AUTO_CLEAR_CAPTURE_LINES}",
    ]
    try:
        result = subprocess.run(cmd, check=False, capture_output=True, text=True)
    except OSError as exc:
        logger.warning("tmux capture-pane OSError: %s", exc)
        return None
    if result.returncode != 0:
        logger.warning(
            "tmux capture-pane 실패: rc=%d stderr=%s",
            result.returncode,
            truncate_for_log(result.stderr or ""),
        )
        return None
    return result.stdout or ""


def inject_cleanup_prompt(pane_target: str) -> bool:
    """tmux send-keys 로 정리 prompt 한 줄 inject + Enter."""
    return tmux_send_payload(pane_target, CONTEXT_CLEANUP_PROMPT)


def send_clear_command(pane_target: str) -> bool:
    """tmux send-keys '/clear' Enter — Claude TUI 컨텍스트 비우기 슬래시."""
    return tmux_send_payload(pane_target, "/clear")


def clear_pane_history(pane_target: str) -> bool:
    """`tmux clear-history -t <pane>` — 스크롤백 cleanup (G-1, #910 묶음 B).

    `/clear` 직후 호출해 직전 turn 의 `===CLEAR_READY===` marker 가 다음
    polling iter 의 `capture-pane -S -N` 결과에 잔존해서 false detect → 즉시
    `/clear` 재전송 → sub-agent 작업 손실 시나리오를 차단한다.

    실패해도 caller 흐름을 막지 않는다 (warning log 만 남기고 False 반환).
    """
    cmd = ["tmux", "clear-history", "-t", pane_target]
    try:
        result = subprocess.run(cmd, check=False, capture_output=True, text=True)
    except OSError as exc:
        logger.warning("tmux clear-history OSError: pane=%s err=%s", pane_target, exc)
        return False
    if result.returncode != 0:
        logger.warning(
            "tmux clear-history 실패: pane=%s rc=%d stderr=%s",
            pane_target,
            result.returncode,
            truncate_for_log(result.stderr or ""),
        )
        return False
    return True


def append_clear_log(pct: int, event: str, pane: str = "-") -> None:
    """`project_context_clear_log.md` 에 한 줄 append. 파일 없으면 header 생성.

    spec §5-3 포맷 (#855 갱신): `| timestamp | pane | event | context% | handoff file | duration |`.
    handoff file 과 duration 은 자동 채울 정보가 없으므로 `-` 로 비워둡니다.
    pane 인자 부재 시 (legacy 호출) `-` 로 표기.
    """
    timestamp = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    row = f"| {timestamp} | {pane} | {event} | {pct} | - | - |\n"
    try:
        CONTEXT_CLEAR_LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        if not CONTEXT_CLEAR_LOG_PATH.exists():
            with CONTEXT_CLEAR_LOG_PATH.open("w", encoding="utf-8") as handle:
                handle.write(CONTEXT_CLEAR_LOG_HEADER)
        with CONTEXT_CLEAR_LOG_PATH.open("a", encoding="utf-8") as handle:
            handle.write(row)
    except OSError as exc:
        logger.warning("context clear log append 실패: %s", exc)


def resolve_pane_targets(raw: str | None) -> list[str]:
    """`TMUX_PANE_TARGETS` (또는 singular `TMUX_PANE_TARGET`) CSV 를 list 로 변환.

    빈 문자열 토큰 / 공백 trim. raw 가 None 이거나 비어 있으면
    `CONTEXT_AUTO_CLEAR_DEFAULT_PANES` 를 fallback. 중복 제거 (순서 보존).
    """
    if raw is None or not raw.strip():
        raw = CONTEXT_AUTO_CLEAR_DEFAULT_PANES
    seen: set[str] = set()
    result: list[str] = []
    for token in raw.split(","):
        stripped = token.strip()
        if not stripped or stripped in seen:
            continue
        seen.add(stripped)
        result.append(stripped)
    return result


def resolve_context_pct_env(env_value: str | None, default: int) -> int:
    """CONTEXT_CLEAR_TRIGGER_PCT / CONTEXT_CLEAR_HYSTERESIS_PCT 정수 파싱."""
    if env_value is None:
        return default
    try:
        parsed = int(env_value)
    except ValueError:
        logger.warning(
            "context auto-clear pct env 정수 아님(%r) — 기본값 사용: %d",
            env_value,
            default,
        )
        return default
    if parsed < 0 or parsed > 100:
        logger.warning(
            "context auto-clear pct env 범위 이상(%d) — 기본값 사용: %d",
            parsed,
            default,
        )
        return default
    return parsed


async def context_auto_clear_loop(
    client: "discord.Client",
    channel_id: int,
    *,
    pane_targets: list[str] | tuple[str, ...] | None = None,
    pane_target: str | None = None,  # legacy 단일 인자 (#810 호환).
    trigger_pct: int = CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT,
    hysteresis_pct: int = CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT,
    poll_interval: int = CONTEXT_AUTO_CLEAR_POLL_INTERVAL_SECONDS,
) -> None:
    """pane 별 독립 state polling. trigger_pct 도달 → 정리 prompt inject, marker → /clear.

    #855: 다중 pane (nmae mobruji:0.0 + helper helper:0.0) polling. 각 pane 마다
    debounced / awaiting_marker 를 독립 추적해 한 pane 의 트리거가 다른 pane 에
    영향을 주지 않는다. pane 별 `tmux has-session` 확인 → missing pane 은 매 iter
    silently skip (helper 세션 없는 환경에서 crash 금지).

    spec §5-2 본문 흐름 (pane 별 반복):
      1. AUTOCLEAR_PAUSED_FLAG 존재하면 전체 polling skip (사용자 수동 중단).
      2. 각 pane 에 대해:
         a. tmux has-session 실패 → 해당 pane 만 skip.
         b. tmux capture-pane → parse_context_pct 로 최신 marker 추출.
         c. awaiting_marker(pane) == True 이고 CLEAR_READY_MARKER 가 보이면:
            Discord push → send_clear_command → log append → awaiting_marker 해제.
         d. debounced(pane) == True 이고 pct ≤ hysteresis_pct 면 debounce 해제.
         e. debounced(pane) == False 이고 pct ≥ trigger_pct 면:
            Discord push → inject_cleanup_prompt → log append → debounced/awaiting_marker SET.
      3. marker 도착까지 timeout 없음 — 진행 중 작업 보호 (spec §3 안전성).

    pane_target (singular) 가 지정되면 [pane_target] 단일 리스트로 처리 — legacy
    호출(#809/#810 테스트 호환) 보존. 둘 다 None 이면 default panes 사용.

    asyncio.CancelledError 는 외부로 전파해 bot 종료 시 깔끔히 정리되도록.
    """
    if pane_targets is None:
        if pane_target is not None:
            pane_targets = [pane_target]
        else:
            pane_targets = resolve_pane_targets(None)
    panes = list(pane_targets)
    state: dict[str, dict[str, bool]] = {
        p: {"debounced": False, "awaiting_marker": False} for p in panes
    }
    missing_warned: set[str] = set()
    while True:
        try:
            await asyncio.sleep(poll_interval)
            if AUTOCLEAR_PAUSED_FLAG.exists():
                continue
            for pane in panes:
                session = pane.split(":", 1)[0]
                if not tmux_has_session(session):
                    if pane not in missing_warned:
                        logger.warning(
                            "context auto-clear: pane=%s 세션 없음 — skip",
                            pane,
                        )
                        missing_warned.add(pane)
                    continue
                # 세션이 재생성되면 다시 warn 가능하도록 살아있을 땐 set 에서 제거.
                missing_warned.discard(pane)

                pane_text = capture_pane_text(pane)
                if pane_text is None:
                    continue
                pct = parse_context_pct(pane_text)
                st = state[pane]
                # marker 우선 — pct 가 None 이어도 정리 완료 신호는 살린다.
                if st["awaiting_marker"] and CLEAR_READY_MARKER in pane_text:
                    last_pct_text = "?" if pct is None else f"{pct}%"
                    channel = client.get_channel(channel_id)
                    if channel is not None:
                        # #911 G-6: 429/5xx retry.
                        await send_with_retry(
                            channel,
                            content=(
                                f"🧹 {pane} 정리 완료 → /clear 전송 "
                                f"(마지막 context {last_pct_text})"
                            ),
                        )
                    else:
                        logger.warning(
                            "context auto-clear: channel_id=%s 없음 — pane=%s marker push skip",
                            channel_id,
                            pane,
                        )
                    send_clear_command(pane)
                    # G-1 (#910): /clear 직후 스크롤백 cleanup. 다음 iter capture-pane
                    # 결과에서 직전 turn 의 CLEAR_READY marker 잔존을 차단.
                    clear_pane_history(pane)
                    append_clear_log(
                        pct if pct is not None else -1, "cleared", pane=pane
                    )
                    logger.info(
                        "context auto-clear: pane=%s cleared=%s",
                        pane,
                        last_pct_text,
                    )
                    st["awaiting_marker"] = False
                    # debounce 는 80% 이하 자연 falloff 까지 유지.
                    continue
                if pct is None:
                    continue
                if st["debounced"] and pct <= hysteresis_pct:
                    st["debounced"] = False
                if not st["debounced"] and pct >= trigger_pct:
                    channel = client.get_channel(channel_id)
                    if channel is not None:
                        # #911 G-6: 429/5xx retry.
                        await send_with_retry(
                            channel,
                            content=f"🧠 {pane} context {pct}% → 자율 정리 시작",
                        )
                    else:
                        logger.warning(
                            "context auto-clear: channel_id=%s 없음 — pane=%s trigger push skip",
                            channel_id,
                            pane,
                        )
                    inject_cleanup_prompt(pane)
                    append_clear_log(pct, "triggered", pane=pane)
                    logger.info(
                        "context auto-clear: pane=%s trigger=%d%%",
                        pane,
                        pct,
                    )
                    st["debounced"] = True
                    st["awaiting_marker"] = True
            record_loop_heartbeat("context_auto_clear_loop")
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("context_auto_clear_loop iter 실패: %s", exc)


# ─────────────────────────────────────────────────────────────────────────────
# nmae cycle watchdog (spec: docs/features/nmae-cycle-watchdog.md)
# ─────────────────────────────────────────────────────────────────────────────


def resolve_cycle_targets(raw: str | None) -> list[str]:
    """`CYCLE_WATCH_WORKSPACES` CSV (또는 default `be,fe,rev,plan`) 을 list 로 반환.

    공백 trim. 빈 토큰 무시. 중복 제거 (순서 보존). None / 빈 문자열 → default.
    """
    if raw is None or not raw.strip():
        raw = CYCLE_IDLE_WATCH_DEFAULT_WORKSPACES
    seen: set[str] = set()
    result: list[str] = []
    for token in raw.split(","):
        stripped = token.strip()
        if not stripped or stripped in seen:
            continue
        seen.add(stripped)
        result.append(stripped)
    return result


def parse_cycle_status(path: str) -> dict | None:
    """cycle-status.json 을 dict 로 읽거나, 부재/parse fail/OSError 시 None.

    `read_cycle_status` 와 의미상 동일하지만 watchdog 전용 thin wrapper —
    호출부에서 graceful skip 흐름을 일관되게 가져가기 위해 별도 함수로 둠.
    테스트는 이 함수 직접 검증 (정상 / null / missing / malformed 4건).
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        return None
    except json.JSONDecodeError:
        return None
    except OSError:
        return None


def _parse_iso_datetime(text: object) -> datetime | None:
    """`last_completed.completed_at` 등 ISO8601 문자열을 aware datetime 으로 파싱.

    str 가 아니거나 파싱 실패 → None. ``Z`` suffix 는 ``+00:00`` 로 치환.
    naive datetime 은 UTC 로 간주 (보수적 — naive 면 timezone 모름).
    """
    if not isinstance(text, str) or not text.strip():
        return None
    candidate = text.strip()
    if candidate.endswith("Z"):
        candidate = candidate[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(candidate)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def detect_idle_worktrees(
    status: dict | None,
    *,
    threshold_minutes: int,
    now: datetime,
    workspaces: list[str] | tuple[str, ...] = ("be", "fe", "rev", "plan"),
    stale_active_enabled: bool = False,
    stale_active_threshold_minutes: int = STALE_ACTIVE_THRESHOLD_DEFAULT_MIN,
) -> list[dict]:
    """4 워크트리(be/fe/rev/plan) idle 판정 결과 list 반환.

    idle 정의:
        ``in_progress`` 가 None / 누락 / 빈 문자열 AND
        ``last_completed.completed_at`` 이 ``now - threshold_minutes`` 보다
        오래됨 (또는 last_completed 부재 / completed_at 부재 / 파싱 실패).

    STALE_ACTIVE 정의 (P3a remediation, #1015 follow-up):
        ``stale_active_enabled=True`` 이고 ``in_progress`` 가 dict 형식이며
        ``in_progress.started_at`` 이 ``now - stale_active_threshold_minutes`` 보다
        오래된 경우. sub-agent freeze 또는 nmae 완료 통지 처리 누락 추정.
        이 경우 idle 와 동일하게 list 에 포함하되 ``is_stale_active=True``
        flag 와 ``started_at`` (datetime) / ``stale_title`` 을 함께 반환.

    Returns:
        idle 또는 stale_active 워크트리 dict 의 list. 각 dict 키:
            ``workspace`` (str) — be/fe/rev/plan 등
            ``last_completed_title`` (str) — last_completed.title 또는 "없음"
            ``last_completed_at`` (datetime|None) — 파싱된 시각 또는 None
            ``note`` (str|None) — entry.note (idle 분기만 유효)
            ``idle_since`` (str|None) — entry.idle_since 원본 문자열
            ``is_future`` (bool) — last_completed.completed_at 미래 timestamp
            ``is_stale_active`` (bool) — STALE_ACTIVE 분기 여부 (#1015 follow-up)
            ``started_at`` (datetime|None) — STALE_ACTIVE 일 때 in_progress.started_at
            ``stale_title`` (str|None) — STALE_ACTIVE 일 때 in_progress.title
        idle/stale 0건이면 빈 list.

    Args:
        status: cycle-status.json dict 또는 None. None 이면 빈 list (alert 안 함
            — 파일 부재는 별도 alert path).
        threshold_minutes: idle 임계 분. 0 이면 모든 비-in-progress 가 idle.
        now: 현재 시각 (aware datetime). 테스트 deterministic 용.
        workspaces: 검사할 워크트리 list.
        stale_active_enabled: STALE_ACTIVE 검출 활성화. False (default) 면 기존
            idle 만 검출 — 후방호환. True 면 in_progress.started_at 임계 검사
            추가 (#1015 follow-up P3a remediation).
        stale_active_threshold_minutes: STALE_ACTIVE 임계 분. 0 이면 모든 active
            워크트리가 stale 로 분류됨 (테스트 용).
    """
    if not isinstance(status, dict):
        return []

    cutoff_seconds = threshold_minutes * 60
    stale_cutoff_seconds = stale_active_threshold_minutes * 60
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)

    idle: list[dict] = []
    for ws in workspaces:
        entry = status.get(ws)
        if not isinstance(entry, dict):
            # 워크트리 자체 누락 — idle 로 간주 (cycle-status.json 미초기화).
            idle.append({
                "workspace": ws,
                "last_completed_title": "없음",
                "last_completed_at": None,
                "note": None,
                "idle_since": None,
                "is_future": False,
                "is_stale_active": False,
                "started_at": None,
                "stale_title": None,
            })
            continue
        in_progress_raw = entry.get("in_progress")
        is_active = False
        if isinstance(in_progress_raw, dict) and in_progress_raw:
            is_active = True
        elif isinstance(in_progress_raw, str) and in_progress_raw.strip():
            is_active = True
        if is_active:
            # STALE_ACTIVE 분기 (#1015 follow-up) — in_progress.started_at 이
            # 임계치보다 오래된 경우 sub-agent freeze 추정. dict 형식만 분석
            # 가능 (legacy str 형식엔 started_at 없음 — silent skip).
            if (
                stale_active_enabled
                and isinstance(in_progress_raw, dict)
            ):
                started_at = _parse_iso_datetime(
                    in_progress_raw.get("started_at")
                )
                if started_at is not None:
                    elapsed_started = (now - started_at).total_seconds()
                    # 미래 started_at (clock skew / KST hand-edit) — silent skip.
                    # is_future flag 와 동일 family 의 신뢰 못 함 케이스.
                    if elapsed_started > stale_cutoff_seconds:
                        title_raw = in_progress_raw.get("title")
                        stale_title = (
                            title_raw.strip()
                            if isinstance(title_raw, str) and title_raw.strip()
                            else "제목 없음"
                        )
                        idle.append({
                            "workspace": ws,
                            "last_completed_title": "없음",
                            "last_completed_at": None,
                            "note": None,
                            "idle_since": None,
                            "is_future": False,
                            "is_stale_active": True,
                            "started_at": started_at,
                            "stale_title": stale_title,
                        })
            continue

        last_completed = entry.get("last_completed")
        completed_at: datetime | None = None
        title_text = "없음"
        if isinstance(last_completed, dict):
            completed_at = _parse_iso_datetime(last_completed.get("completed_at"))
            title_raw = last_completed.get("title")
            if isinstance(title_raw, str) and title_raw.strip():
                title_text = title_raw.strip()

        # note / idle_since 추출 (#956). note 빈 string / whitespace → None.
        note_raw = entry.get("note")
        note_text: str | None = None
        if isinstance(note_raw, str) and note_raw.strip():
            note_text = note_raw.strip()
        idle_since_raw = entry.get("idle_since")
        idle_since_text: str | None = None
        if isinstance(idle_since_raw, str) and idle_since_raw.strip():
            idle_since_text = idle_since_raw.strip()

        if completed_at is None:
            # last_completed 부재 / completed_at 없음 → idle 로 간주.
            idle.append({
                "workspace": ws,
                "last_completed_title": title_text,
                "last_completed_at": None,
                "note": note_text,
                "idle_since": idle_since_text,
                "is_future": False,
                "is_stale_active": False,
                "started_at": None,
                "stale_title": None,
            })
            continue

        elapsed = (now - completed_at).total_seconds()
        # 미래 timestamp (negative elapsed) — clock skew 또는 작성자 timestamp
        # 형식 오류 (예: KST 시각을 Z suffix 로 적음) 방어. 보수적으로 idle 간주.
        # #969 root cause — detect 가 silently pass 해서 watchdog 침묵.
        # #971: warning → ERROR 격상 + idle entry 에 `is_future=True` flag.
        # 호출부 (`cycle_idle_watch_loop`) 가 별 Discord push 로 가시화.
        if elapsed < 0:
            logger.error(
                "detect_idle_worktrees: 미래 completed_at 감지 ws=%s completed_at=%s now=%s "
                "— hand-edit 의심 (KST 시각을 Z suffix 로?). update.sh 만 사용 (#971)",
                ws,
                completed_at.isoformat(),
                now.isoformat(),
            )
            idle.append({
                "workspace": ws,
                "last_completed_title": title_text,
                "last_completed_at": completed_at,
                "note": note_text,
                "idle_since": idle_since_text,
                "is_future": True,
                "is_stale_active": False,
                "started_at": None,
                "stale_title": None,
            })
            continue
        if elapsed > cutoff_seconds:
            idle.append({
                "workspace": ws,
                "last_completed_title": title_text,
                "last_completed_at": completed_at,
                "note": note_text,
                "idle_since": idle_since_text,
                "is_future": False,
                "is_stale_active": False,
                "started_at": None,
                "stale_title": None,
            })
    return idle


def _format_idle_summary(idle: list[dict]) -> str:
    """tmux inject prompt 의 ``{summary}`` 부분 빌드.

    각 워크트리 별 "<ws>: <title 앞 60자>" 콤마 join.
    """
    parts: list[str] = []
    for entry in idle:
        ws = entry.get("workspace", "?")
        title = entry.get("last_completed_title") or "없음"
        if len(title) > 60:
            title = title[:59] + "…"
        parts.append(f"last_completed[{ws}]={title}")
    return " / ".join(parts) if parts else "last_completed: 없음"


def tmux_inject_text(pane_target: str, text: str) -> bool:
    """tmux send-keys 로 한 줄 inject (watchdog 알림).

    `tmux_send_payload` 와 동일한 패턴 (-l literal + Enter). 단, watchdog 알림은
    sentinel 처리 불필요하므로 단순 wrapper.
    """
    return tmux_send_payload(pane_target, text)


def _format_workspace_reason_line(entry: dict) -> str:
    """Discord push 용 워크트리 1줄 — workspace + idle_since + reason 표시.

    spec: docs/features/nmae-cycle-watchdog.md §5-8 strict mode (#956).
    """
    ws = entry.get("workspace", "?")
    note = entry.get("note")
    idle_since = entry.get("idle_since")
    if note:
        reason_text = f"reason: {note}"
    else:
        reason_text = "reason 없음 — STRICT relaunch"
    if idle_since:
        return f"- {ws}: IDLE {idle_since}~ ({reason_text})"
    return f"- {ws}: IDLE ({reason_text})"


def _format_elapsed_h_m(seconds: float) -> str:
    """초 → "Xh Ym" 단축 표기. 1h 미만은 "Ym" (분 정수)."""
    total_minutes = int(seconds // 60)
    if total_minutes < 60:
        return f"{total_minutes}m"
    hours, minutes = divmod(total_minutes, 60)
    return f"{hours}h{minutes}m"


def _format_stale_active_reason_line(entry: dict, *, now: datetime) -> str:
    """Discord push 용 STALE_ACTIVE 워크트리 1줄.

    예: "- be: STALE_ACTIVE started_at=2026-05-23T18:52:00+00:00 (9h0m ago) — sub-agent freeze 또는 nmae 완료 통지 처리 누락 추정"
    """
    ws = entry.get("workspace", "?")
    started_at = entry.get("started_at")
    if started_at is None:
        return (
            f"- {ws}: STALE_ACTIVE started_at=? — sub-agent freeze 또는 nmae 완료 "
            "통지 처리 누락 추정"
        )
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)
    elapsed_seconds = max(0.0, (now - started_at).total_seconds())
    elapsed_label = _format_elapsed_h_m(elapsed_seconds)
    return (
        f"- {ws}: STALE_ACTIVE (started_at={started_at.isoformat()}, "
        f"{elapsed_label} ago) — sub-agent freeze 또는 nmae 완료 통지 처리 누락 추정"
    )


def _format_stale_active_summary(entries: list[dict], *, now: datetime) -> str:
    """tmux inject prompt 의 ``{summary}`` 부분 빌드 (STALE_ACTIVE 용).

    각 워크트리 별 "<ws>: started_at=<iso> (<elapsed> ago)" 콤마 join.
    """
    parts: list[str] = []
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)
    for entry in entries:
        ws = entry.get("workspace", "?")
        started_at = entry.get("started_at")
        if started_at is None:
            parts.append(f"started_at[{ws}]=?")
            continue
        elapsed_seconds = max(0.0, (now - started_at).total_seconds())
        elapsed_label = _format_elapsed_h_m(elapsed_seconds)
        parts.append(
            f"started_at[{ws}]={started_at.isoformat()} ({elapsed_label} ago)"
        )
    return " / ".join(parts) if parts else "started_at: 없음"


async def cycle_idle_watch_loop(
    client: "discord.Client",
    digest_channel_id: int,
    *,
    cycle_status_path: str,
    inject_target: str = CYCLE_IDLE_WATCH_DEFAULT_INJECT_TARGET,
    threshold_minutes: int = CYCLE_IDLE_THRESHOLD_DEFAULT_MINUTES,
    poll_interval: int = CYCLE_IDLE_WATCH_DEFAULT_INTERVAL_SECONDS,
    workspaces: list[str] | tuple[str, ...] = ("be", "fe", "rev", "plan"),
    debounce_seconds: int = CYCLE_IDLE_WATCH_DEBOUNCE_SECONDS,
    reason_required: bool = True,
    escalation_threshold: int = CYCLE_INJECT_ESCALATION_THRESHOLD_DEFAULT,
    escalation_debounce_seconds: int = CYCLE_INJECT_ESCALATION_DEBOUNCE_SECONDS_DEFAULT,
    escalation_channel_id: int | None = None,
    future_ts_debounce_seconds: int = CYCLE_FUTURE_TS_PUSH_DEBOUNCE_SECONDS,
    stale_active_enabled: bool = False,
    stale_active_threshold_minutes: int = STALE_ACTIVE_THRESHOLD_DEFAULT_MIN,
    time_source=time.monotonic,
    now_provider=lambda: datetime.now(timezone.utc),
) -> None:
    """5분 polling cycle-status.json — idle 워크트리 발견 시 nmae tmux inject + Discord push.

    spec: docs/features/nmae-cycle-watchdog.md.

    동작:
      1. ``poll_interval`` 초 마다 cycle-status.json read.
      2. ``detect_idle_worktrees`` 로 idle list 산출.
      3. debounce 적용 — 워크트리 별 마지막 alert 시각 cache. 같은 워크트리에
         ``debounce_seconds`` 내 재알림 안 함.
      4. debounce 통과한 idle ≥ 1 이면:
         - nmae tmux pane (``inject_target``) 에 `tmux_inject_text` 알림 inject.
         - Discord ``digest_channel_id`` 에 경고 push (cycle digest 채널 공유).
         - 워크트리별 inject counter += 1 (#972).
      5. (#972) escalation — counter 가 ``escalation_threshold`` 도달 + in_progress
         여전히 NULL 이면 ``escalation_channel_id`` (= MOBRUJI_CHANNEL_ID 권장) 에
         사용자 직접 push. escalation push 자체도 워크트리 별 1h debounce.
      6. 워크트리가 active 로 돌아오면 (idle list 에서 빠짐) counter 자동 0 리셋.
      7. graceful skip — cycle-status.json 부재/parse fail / tmux session 부재 /
         Discord channel 미발견 시 warn 1회 후 skip.
      8. ``threshold_minutes <= 0`` 이면 disabled — 즉시 return (테스트 용).

    asyncio.CancelledError 는 외부로 전파해 bot 종료 시 깔끔히 정리.

    Args:
        escalation_threshold: 같은 워크트리에 inject 가 N회 연속 발사 + in_progress
            NULL 유지 시 사용자 채널 push. 0 이면 escalation 비활성 (테스트 용).
        escalation_debounce_seconds: 같은 워크트리 escalate push 사이 최소 간격.
        escalation_channel_id: 사용자 직접 push 채널 (MOBRUJI_CHANNEL_ID).
            None 이면 escalation 비활성 (graceful — 환경 미설정 시 silent).
        stale_active_enabled: STALE_ACTIVE 검출 활성화 (#1015 follow-up P3a remediation).
            False (default) 면 기존 idle 만 검출 — 후방호환.
        stale_active_threshold_minutes: STALE_ACTIVE 임계 분 (default 60).
            ``in_progress.started_at`` 이 이 임계치보다 오래되면 sub-agent freeze
            추정으로 idle 와 동일 inject + Discord push + escalation 카운트 누적.
        time_source: debounce 비교용 monotonic 시각 source. 테스트 stub.
        now_provider: idle 판정용 wall-clock provider (aware datetime).
    """
    if threshold_minutes <= 0:
        logger.info("cycle_idle_watch_loop disabled (threshold_minutes<=0)")
        return

    inject_session = inject_target.split(":", 1)[0]
    last_alert_at: dict[str, float] = {}
    # escalation (#972) 상태 — 워크트리 별 누적 inject 횟수 + 마지막 escalate 시각.
    inject_count: dict[str, int] = {}
    last_escalate_at: dict[str, float] = {}
    # #971: future timestamp ERROR push debounce — 동일 워크트리 1h 1회.
    last_future_ts_push_at: dict[str, float] = {}
    missing_session_warned = False
    missing_channel_warned = False
    missing_status_warned = False
    missing_escalation_channel_warned = False

    while True:
        try:
            await asyncio.sleep(poll_interval)
            record_loop_heartbeat("cycle_idle_watch_loop")
            status = parse_cycle_status(cycle_status_path)
            if status is None:
                if not missing_status_warned:
                    logger.warning(
                        "cycle_idle_watch_loop: cycle-status.json 읽기 실패 — skip (path=%s)",
                        cycle_status_path,
                    )
                    missing_status_warned = True
                continue
            missing_status_warned = False

            idle_all = detect_idle_worktrees(
                status,
                threshold_minutes=threshold_minutes,
                now=now_provider(),
                workspaces=workspaces,
                stale_active_enabled=stale_active_enabled,
                stale_active_threshold_minutes=stale_active_threshold_minutes,
            )
            # #969 — 매 iter INFO log (observability). idle=0 이어도 loop alive 확인.
            # #1015 follow-up — stale_active 카운트 별도 expose.
            stale_count = sum(1 for e in idle_all if e.get("is_stale_active"))
            logger.info(
                "cycle_idle_watch_loop: detect summary checked=%d idle=%d stale_active=%d (workspaces=%s)",
                len(workspaces),
                len(idle_all) - stale_count,
                stale_count,
                ",".join(e["workspace"] for e in idle_all) or "none",
            )
            # (#972) escalation counter 리셋 — 이번 iter 에 idle 아닌 워크트리는
            # nmae 가 정상 launch 한 것으로 간주, inject_count 0 으로 reset.
            idle_workspaces_now = {e["workspace"] for e in idle_all}
            for ws_name in list(inject_count.keys()):
                if ws_name not in idle_workspaces_now:
                    inject_count[ws_name] = 0

            # #971 — future timestamp 발견 시 별 ERROR Discord push.
            # idle alert 와 독립 debounce (1h) — KST as Z hand-edit 사고 즉시
            # 사용자 가시화. tmux session 부재 / channel 부재 시 graceful skip.
            future_entries = [e for e in idle_all if e.get("is_future")]
            if future_entries:
                mono_now_future = time_source()
                fresh_future = [
                    e
                    for e in future_entries
                    if (
                        mono_now_future
                        - last_future_ts_push_at.get(e["workspace"], 0.0)
                    )
                    >= future_ts_debounce_seconds
                ]
                if fresh_future:
                    channel = client.get_channel(digest_channel_id)
                    if channel is not None:
                        ws_label = ", ".join(
                            e["workspace"] for e in fresh_future
                        )
                        details = []
                        for e in fresh_future:
                            ts_iso = (
                                e["last_completed_at"].isoformat()
                                if e.get("last_completed_at") is not None
                                else "?"
                            )
                            details.append(f"  - {e['workspace']}: completed_at={ts_iso}")
                        push_text = (
                            "🚨 ERROR cycle-status.json future timestamp 감지 "
                            f"({len(fresh_future)} 워크트리: {ws_label}). "
                            "원인: nmae hand-edit (KST 시각을 Z suffix 로?) 의심. "
                            "수동 JSON 편집 금지 — `tools/cycle-status/update.sh` 만 사용. (#971)\n"
                            + "\n".join(details)
                        )
                        await send_with_retry(channel, content=push_text)
                        for e in fresh_future:
                            last_future_ts_push_at[e["workspace"]] = mono_now_future
                        logger.error(
                            "cycle_idle_watch_loop: future timestamp Discord push workspaces=%s",
                            ws_label,
                        )

            if not idle_all:
                continue

            mono_now = time_source()
            fresh_idle = [
                entry
                for entry in idle_all
                if (mono_now - last_alert_at.get(entry["workspace"], 0.0))
                >= debounce_seconds
            ]
            if not fresh_idle:
                continue

            if not tmux_has_session(inject_session):
                if not missing_session_warned:
                    logger.warning(
                        "cycle_idle_watch_loop: tmux session 부재 — skip (target=%s)",
                        inject_target,
                    )
                    missing_session_warned = True
                continue
            missing_session_warned = False

            # #1015 follow-up — STALE_ACTIVE 분리. idle 와 동일 처리 경로지만
            # inject template 와 Discord push 라벨이 다름 (sub-agent freeze 추정
            # 메시지). escalation 카운트는 idle 와 합산 (같은 워크트리 무응답 가시화).
            fresh_stale_active = [
                entry for entry in fresh_idle if entry.get("is_stale_active")
            ]
            fresh_idle_regular = [
                entry for entry in fresh_idle if not entry.get("is_stale_active")
            ]

            # STRICT 분류 (#956) — reason_required=True 이고 note 미명시인 regular idle.
            # STRICT 이면 별 prompt (즉시 launch + note 기록 의무 명시) inject.
            # STALE_ACTIVE 는 STRICT 분류 대상 아님 — 별도 template 처리.
            strict_idle = [
                entry for entry in fresh_idle_regular
                if reason_required and not entry.get("note")
            ]
            soft_idle = [
                entry for entry in fresh_idle_regular if entry not in strict_idle
            ]

            workspaces_label = ", ".join(e["workspace"] for e in fresh_idle)
            today = now_provider().strftime("%Y-%m-%d")

            # spec: docs/features/directive-board-template-and-tags.md §5-6
            # cycle idle + 그 cycle 에 assigned + polished directive 있으면 backlog-scan
            # 결과를 inject text 에 부착 (PR C — cycle-specific auto-inject).
            # nmae 가 학습 의존 없이 cycle 별 directive 우선 launch 가능.
            def _build_assigned_directive_footer(entries: list[dict]) -> str:
                if not entries:
                    return ""
                lines: list[str] = []
                seen_cycles: set[str] = set()
                for _entry in entries:
                    _cycle = _entry.get("workspace")
                    if not _cycle or _cycle in seen_cycles:
                        continue
                    seen_cycles.add(_cycle)
                    _scan = scan_assigned_directives_for_cycle(_cycle)
                    if _scan:
                        lines.append(
                            f"[assigned directive — cycle={_cycle}]\n{_scan}"
                        )
                if not lines:
                    return ""
                return "\n\n" + "\n\n".join(lines)

            if strict_idle:
                strict_label = ", ".join(e["workspace"] for e in strict_idle)
                strict_summary = _format_idle_summary(strict_idle)
                strict_text = CYCLE_IDLE_WATCH_STRICT_TEMPLATE.format(
                    date=today,
                    workspaces=strict_label,
                    summary=strict_summary,
                )
                strict_text += _build_assigned_directive_footer(strict_idle)
                tmux_inject_text(inject_target, strict_text)
            if soft_idle:
                soft_label = ", ".join(e["workspace"] for e in soft_idle)
                soft_summary = _format_idle_summary(soft_idle)
                soft_text = CYCLE_IDLE_WATCH_INJECT_TEMPLATE.format(
                    date=today,
                    workspaces=soft_label,
                    summary=soft_summary,
                )
                soft_text += _build_assigned_directive_footer(soft_idle)
                tmux_inject_text(inject_target, soft_text)
            if fresh_stale_active:
                stale_label = ", ".join(e["workspace"] for e in fresh_stale_active)
                stale_summary = _format_stale_active_summary(
                    fresh_stale_active, now=now_provider()
                )
                stale_text = CYCLE_STALE_ACTIVE_INJECT_TEMPLATE.format(
                    date=today,
                    workspaces=stale_label,
                    summary=stale_summary,
                )
                tmux_inject_text(inject_target, stale_text)

            channel = client.get_channel(digest_channel_id)
            if channel is None:
                if not missing_channel_warned:
                    logger.warning(
                        "cycle_idle_watch_loop: Discord channel 부재 — push skip (channel_id=%s)",
                        digest_channel_id,
                    )
                    missing_channel_warned = True
            else:
                missing_channel_warned = False
                idle_regular_count = len(fresh_idle_regular)
                stale_count = len(fresh_stale_active)
                header_parts: list[str] = []
                if idle_regular_count:
                    header_parts.append(f"{idle_regular_count} 워크트리 idle")
                if stale_count:
                    header_parts.append(f"{stale_count} 워크트리 STALE_ACTIVE")
                lines: list[str] = [
                    "⚠️ nmae watchdog — " + " / ".join(header_parts)
                ]
                if strict_idle:
                    lines.append(
                        f"STRICT relaunch ({len(strict_idle)}): "
                        + ", ".join(e["workspace"] for e in strict_idle)
                    )
                for entry in fresh_idle_regular:
                    lines.append(_format_workspace_reason_line(entry))
                for entry in fresh_stale_active:
                    lines.append(
                        _format_stale_active_reason_line(entry, now=now_provider())
                    )
                lines.append("→ nmae 에 알림 inject 완료")
                discord_text = "\n".join(lines)
                await send_with_retry(channel, content=discord_text)

            now_mono = time_source()
            for entry in fresh_idle:
                last_alert_at[entry["workspace"]] = now_mono
                # (#972) escalation counter += 1 — fresh idle 발사한 워크트리만.
                ws_name = entry["workspace"]
                inject_count[ws_name] = inject_count.get(ws_name, 0) + 1
            logger.info(
                "cycle_idle_watch_loop: idle alert workspaces=%s strict=%s inject_counts=%s",
                workspaces_label,
                ", ".join(e["workspace"] for e in strict_idle) or "none",
                ",".join(f"{w}={inject_count.get(w, 0)}" for w in idle_workspaces_now),
            )

            # (#972) escalation — threshold 도달 워크트리 사용자 채널 직접 push.
            # escalation_channel_id None / threshold<=0 이면 graceful skip.
            if escalation_channel_id is None or escalation_threshold <= 0:
                continue
            escalate_workspaces = [
                entry["workspace"]
                for entry in fresh_idle
                if inject_count.get(entry["workspace"], 0) >= escalation_threshold
                and (now_mono - last_escalate_at.get(entry["workspace"], 0.0))
                >= escalation_debounce_seconds
            ]
            if not escalate_workspaces:
                continue
            escalation_channel = client.get_channel(escalation_channel_id)
            if escalation_channel is None:
                if not missing_escalation_channel_warned:
                    logger.warning(
                        "cycle_idle_watch_loop: escalation channel 부재 — skip (channel_id=%s)",
                        escalation_channel_id,
                    )
                    missing_escalation_channel_warned = True
                continue
            missing_escalation_channel_warned = False
            for ws_name in escalate_workspaces:
                escalate_text = CYCLE_INJECT_ESCALATION_MESSAGE_TEMPLATE.format(
                    workspaces=ws_name,
                    count=inject_count.get(ws_name, 0),
                )
                await send_with_retry(escalation_channel, content=escalate_text)
                last_escalate_at[ws_name] = now_mono
                logger.warning(
                    "cycle_idle_watch_loop: escalation push ws=%s count=%d",
                    ws_name,
                    inject_count.get(ws_name, 0),
                )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("cycle_idle_watch_loop iter 실패: %s", exc)


# ─────────────────────────────────────────────────────────────────────────────
# rev e2e 단계 2 (post-merge) 자동 trigger (#1008)
# spec: docs/features/rev-e2e-3-stages.md §3-2
# ─────────────────────────────────────────────────────────────────────────────


def fetch_rev_post_merge_candidates(
    *,
    pass_label: str = REV_POST_MERGE_PASS_LABEL,
    search_window: str = REV_POST_MERGE_AUDIT_SEARCH_WINDOW,
    timeout_seconds: int = REV_POST_MERGE_AUDIT_GH_TIMEOUT_SECONDS,
    runner=subprocess.run,
) -> list[int]:
    """develop 머지된 PR 중 단계 2 audit 필요한 PR 번호 리스트를 반환합니다.

    `gh pr list --state merged --base develop --search 'merged:>{window} ago -label:{pass_label}'`
    을 호출해 JSON 으로 결과를 받습니다. 호출 실패 / parse fail 은 빈 리스트로
    graceful fallback (호출부 loop 가 다음 iter 에서 재시도).

    Args:
        pass_label: 단계 2 통과 라벨 (rev sub-agent 가 부여). 이 라벨이 부재한
            PR 만 후보로 잡힙니다.
        search_window: gh CLI `merged:>${X} ago` 윈도우 — 너무 오래된 머지는
            polling 부담만 누적되므로 1시간만 본다 (단계 2 deploy 후 audit 끝났을
            시각). 외부 override 가능.
        timeout_seconds: gh CLI 호출 timeout. 네트워크 hang 시 loop block 방어.
        runner: ``subprocess.run`` 호환 콜러블. 테스트 stub 용.

    Returns:
        PR 번호 (int) 리스트. 호출 실패 시 빈 리스트.
    """
    search_expr = f"merged:>{search_window} ago -label:{pass_label}"
    cmd = [
        "gh",
        "pr",
        "list",
        "--state",
        "merged",
        "--base",
        "develop",
        "--search",
        search_expr,
        "--json",
        "number,title,mergedAt",
        "--limit",
        "30",
    ]
    try:
        result = runner(
            cmd,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        logger.warning("rev post-merge audit: gh pr list 실행 실패: %s", exc)
        return []
    if result.returncode != 0:
        logger.warning(
            "rev post-merge audit: gh pr list rc=%d stderr=%s",
            result.returncode,
            truncate_for_log(result.stderr or ""),
        )
        return []
    raw = (result.stdout or "").strip()
    if not raw:
        return []
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        logger.warning("rev post-merge audit: JSON 파싱 실패: %s", exc)
        return []
    if not isinstance(payload, list):
        return []
    pr_numbers: list[int] = []
    for entry in payload:
        if not isinstance(entry, dict):
            continue
        number = entry.get("number")
        if isinstance(number, int) and number > 0:
            pr_numbers.append(number)
    return pr_numbers


def filter_debounced_prs(
    candidates: list[int],
    last_inject_at: dict[int, float],
    *,
    now_monotonic: float,
    debounce_seconds: int = REV_POST_MERGE_AUDIT_DEBOUNCE_SECONDS,
    max_entries: int = REV_POST_MERGE_AUDIT_DEBOUNCE_MAX_ENTRIES,
) -> list[int]:
    """후보 리스트에서 debounce window 내 재진입 PR 을 제거합니다.

    `last_inject_at` 은 PR 번호 → 직전 inject monotonic 시각. ``debounce_seconds``
    이내면 재 inject 금지. 호출 측에서 fresh 결과로 ``last_inject_at`` 을 갱신합니다.

    cache 사이즈 cap (``max_entries``) — 오래된 entry 를 단순 truncate (FIFO).
    """
    fresh: list[int] = []
    for pr in candidates:
        last = last_inject_at.get(pr)
        if last is None or (now_monotonic - last) >= debounce_seconds:
            fresh.append(pr)
    # cache 사이즈 cap — 오래된 entry 제거 (단순 N개 유지).
    if len(last_inject_at) > max_entries:
        # 가장 오래된 N - max_entries 개 제거.
        sorted_items = sorted(last_inject_at.items(), key=lambda kv: kv[1])
        to_remove = len(last_inject_at) - max_entries
        for key, _ in sorted_items[:to_remove]:
            last_inject_at.pop(key, None)
    return fresh


def format_rev_post_merge_inject(pr_numbers: list[int]) -> str:
    """tmux inject prompt 빌드 — PR 번호 #N,#M 형식 콤마 join."""
    joined = ",".join(f"#{n}" for n in pr_numbers)
    return REV_POST_MERGE_AUDIT_INJECT_TEMPLATE.format(pr_numbers=joined)


def format_rev_post_merge_discord(pr_numbers: list[int]) -> str:
    """Discord push 메시지 빌드."""
    joined = ",".join(f"#{n}" for n in pr_numbers)
    return REV_POST_MERGE_AUDIT_DISCORD_TEMPLATE.format(pr_numbers=joined)


# spec: docs/features/directive-board-template-and-tags.md §5-6 완료 자동화
# PR B: PR 머지 webhook → directive_status.sh completed 자동 호출.
# 사용자 정정 (2026-05-28): sub-agent PR body 에 `directive: <id>` 명시 → 머지 시 자동 status 전이.
DIRECTIVE_PR_BODY_RE: Final = re.compile(
    r"(?:closes\s+)?directive[:\s]+\s*(\d{6,30})", re.IGNORECASE
)
DIRECTIVE_COMPLETE_POLL_INTERVAL_DEFAULT: Final[int] = 300  # 5분
DIRECTIVE_COMPLETE_SEARCH_WINDOW: Final[str] = "1h"
DIRECTIVE_COMPLETE_GH_TIMEOUT_SECONDS: Final[int] = 60

# spec: docs/features/cycle-forum-operation.md §5-5 (PR cf-3)
# PR 머지 시 cycle forum thread 자동 ✅ 전이. sub-agent 가 PR body 에 명시:
#   cycle-forum: <be|fe|rev|plan>:<thread_id>
# bot.py polling 이 grep + discord-reply.sh --forum-retag <id> <cycle> "완료" 호출.
CYCLE_FORUM_PR_BODY_RE: Final = re.compile(
    r"cycle-forum:\s*(be|fe|rev|plan):(\d{17,20})", re.IGNORECASE
)


def extract_directive_ids_from_body(body: str) -> list[str]:
    """PR body 에서 `directive: <id>` 또는 `Closes directive <id>` 매칭 list."""
    if not body:
        return []
    matches = DIRECTIVE_PR_BODY_RE.findall(body)
    # dedup 보존 순서.
    seen: set[str] = set()
    result: list[str] = []
    for match in matches:
        if match not in seen:
            seen.add(match)
            result.append(match)
    return result


def fetch_recent_merged_prs_with_body(
    *,
    search_window: str = DIRECTIVE_COMPLETE_SEARCH_WINDOW,
    timeout_seconds: int = DIRECTIVE_COMPLETE_GH_TIMEOUT_SECONDS,
    runner=subprocess.run,
) -> list[dict]:
    """develop base 최근 머지 PR + body 포함 list 반환."""
    search_expr = f"merged:>{search_window} ago"
    cmd = [
        "gh", "pr", "list",
        "--state", "merged",
        "--base", "develop",
        "--search", search_expr,
        "--json", "number,url,body,mergedAt",
        "--limit", "30",
    ]
    try:
        result = runner(
            cmd, check=False, capture_output=True, text=True, timeout=timeout_seconds,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        logger.warning("directive_complete_on_merge: gh pr list 실패: %r", exc)
        return []
    if result.returncode != 0:
        logger.warning(
            "directive_complete_on_merge: gh pr list rc=%d stderr=%s",
            result.returncode,
            truncate_for_log(result.stderr or ""),
        )
        return []
    raw = (result.stdout or "").strip()
    if not raw:
        return []
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        logger.warning("directive_complete_on_merge: JSON 파싱 실패: %r", exc)
        return []
    if not isinstance(payload, list):
        return []
    return [entry for entry in payload if isinstance(entry, dict)]


async def directive_complete_on_merge_loop(
    *,
    poll_interval: int = DIRECTIVE_COMPLETE_POLL_INTERVAL_DEFAULT,
    initial_delay: int = 30,
    fetcher=None,
) -> None:
    """5분 polling — develop 머지 PR body 의 directive: <id> 매칭 시 자동 completed 전이.

    spec: docs/features/directive-board-template-and-tags.md §5-6 완료 자동화 (PR B).

    동작:
      1. ``initial_delay`` 초 warmup.
      2. ``poll_interval`` 마다 `gh pr list --merged --base develop --search merged:>1h ago` 호출.
      3. 각 PR body grep `directive: <id>` regex → 매칭 id list.
      4. 매칭 id 마다 `directive_status.sh completed <id> <pr_url>` subprocess.
         sh 자체가 멱등 (이미 completed 면 no-op) — race / 중복 호출 안전.
      5. graceful — gh CLI 실패 / 부재 시 다음 iter 재시도.
      6. ``poll_interval <= 0`` → disabled.

    Args:
        fetcher: ``() -> list[dict]`` 콜러블. None 이면 기본 ``fetch_recent_merged_prs_with_body``.
    """
    if poll_interval <= 0:
        logger.info("directive_complete_on_merge_loop disabled (poll_interval<=0)")
        return

    fetch = fetcher or fetch_recent_merged_prs_with_body
    seen_prs: set[int] = set()  # 이번 process 내 처리 완료 PR 기억 (재호출 회피).

    if initial_delay > 0:
        await asyncio.sleep(initial_delay)

    status_script = (
        Path(__file__).resolve().parent / "directive_status.sh"
    )

    while True:
        try:
            record_loop_heartbeat("directive_complete_on_merge_loop")
            prs = fetch()
            for pr in prs:
                pr_number = pr.get("number")
                if not isinstance(pr_number, int) or pr_number in seen_prs:
                    continue
                body = pr.get("body") or ""
                directive_ids = extract_directive_ids_from_body(body)
                if not directive_ids:
                    continue
                pr_url = pr.get("url") or ""
                logger.info(
                    "directive_complete_on_merge: PR #%d body 에서 directive id 매칭: %s",
                    pr_number,
                    directive_ids,
                )
                for directive_id in directive_ids:
                    if not status_script.exists():
                        logger.warning(
                            "directive_complete_on_merge: directive_status.sh 부재 — skip"
                        )
                        break
                    try:
                        subprocess.run(  # noqa: S603 — sibling script
                            ["bash", str(status_script), directive_id, "completed", pr_url],
                            check=False,
                            timeout=15.0,
                            capture_output=True,
                        )
                        logger.info(
                            "directive_complete_on_merge: completed 호출: id=%s pr=#%d",
                            directive_id,
                            pr_number,
                        )
                    except (OSError, subprocess.TimeoutExpired) as exc:
                        logger.warning(
                            "directive_complete_on_merge: status.sh 호출 실패 id=%s: %r",
                            directive_id,
                            exc,
                        )
                seen_prs.add(pr_number)
                # set 크기 cap (메모리 부담 방어).
                if len(seen_prs) > 200:
                    # 가장 오래된 100개 제거 (단순 truncate).
                    seen_prs = set(list(seen_prs)[100:])
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("directive_complete_on_merge_loop iter 실패: %r", exc)

        await asyncio.sleep(poll_interval)


# ────────────────────────────────────────────────────────────────────────────
# 사후 polish loop 폐기 (2026-05-29): 새 design 의 등록 직전 dialogue 가 polish
# 역할 흡수 — 사용자가 정리된 description 보고 O/X 확인. 사후 자동 polish 중복.
#
# 보존: _polish_prompt + _run_claude_polish 는 Phase B (등록 직전 정리) 재활용.
# 폐기: directive_polish_loop + _process_directive_polish_queue + helper-queue
#       의 directive_polish task append.
# ────────────────────────────────────────────────────────────────────────────
DIRECTIVE_POLISH_CLAUDE_TIMEOUT: Final[int] = 180  # 3분 (Phase B 등록 직전 정리 timeout)
# env CLAUDE_BIN 은 multi-token (예: "claude --dangerously-skip-permissions") 가능 →
# shlex.split 으로 args list 화. 미설정 시 단일 path default. (2026-05-29: NCP
# /etc/.../discord-bridge.env 가 multi-token 값 사용하던 것 호환.)
CLAUDE_CLI_ARGV: Final[list[str]] = shlex.split(
    os.environ.get("CLAUDE_BIN") or "/usr/bin/claude"
)


def _polish_prompt(raw_body: str, directive_id: str) -> str:
    return (
        "mobruji 프로젝트의 directive forum thread 본문을 정제해 주세요.\n\n"
        f"원본 사용자 메시지: {raw_body}\n"
        f"directive_id: {directive_id}\n\n"
        "다음 4 항목 한국어 markdown 으로 정제 (각 항목 짧게):\n"
        "- **요약**: 1-2 줄 (사용자가 무엇을 원하는지)\n"
        "- **유형**: 신규 기능 / 버그 fix / 운영 개선 / 의견 / 질문 중 하나\n"
        "- **위임 권장**: be / fe / rev / plan / nmae 본진 중 하나 + 한 줄 사유\n"
        "- **상태**: 대기\n\n"
        "출력은 위 4 항목 markdown 만. 코드 펜스 / 부가 설명 / 메타코멘트 금지."
    )


def _run_claude_polish(raw_body: str, directive_id: str) -> str:
    """claude -p subprocess — polish 결과 stdout. 실패 시 빈 문자열."""
    try:
        result = subprocess.run(  # noqa: S603 — explicit argv from env
            [*CLAUDE_CLI_ARGV, "-p", _polish_prompt(raw_body, directive_id)],
            timeout=DIRECTIVE_POLISH_CLAUDE_TIMEOUT,
            capture_output=True, text=True, check=False,
        )
        return (result.stdout or "").strip()
    except (OSError, subprocess.TimeoutExpired) as exc:
        logger.warning("directive_polish: claude -p 실패 id=%s exc=%r",
                       directive_id, exc)
        return ""


def _directive_board_status_for(
    directive_board_path: Path, directive_id: str,
) -> str | None:
    """directive-board.jsonl 에서 매칭 entry status 한국어 정규화 후 반환.

    Issue #1248 — polish queue 처리 시 매칭 entry status='완료/실패' 면 polish
    자체 skip (false positive nmae inject 차단의 1차 가드, 2차 가드는
    mark-polished.sh 자체에 동일 status 필터).

    매칭 키: message_id / source_queue_msg_id / thread_id 셋 중 1매칭. 못 찾으면
    None — 호출자가 'unknown' 로 분기해 polish 진행 (jsonl 미반영 race 보호).
    """
    if not directive_board_path.exists():
        return None
    try:
        with directive_board_path.open("r", encoding="utf-8") as handle:
            for raw_line in handle:
                line = raw_line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue
                ids = {
                    str(entry.get("message_id") or ""),
                    str(entry.get("source_queue_msg_id") or ""),
                    str(entry.get("thread_id") or ""),
                }
                if directive_id in ids and directive_id:
                    raw_status = str(entry.get("status") or "")
                    if "완료" in raw_status:
                        return "완료"
                    if "실패" in raw_status:
                        return "실패"
                    if "진행 중" in raw_status:
                        return "진행 중"
                    if "대기" in raw_status:
                        return "대기"
                    return raw_status or None
    except OSError:
        return None
    return None


# 2026-05-29 폐기: _process_directive_polish_queue + directive_polish_loop.
# 사후 polish 가 새 design (등록 직전 dialogue + 사용자 O/X 확인) 로 흡수됨.
# _polish_prompt + _run_claude_polish 는 Phase B 에서 등록 직전 정리 path 로 재활용.


def extract_cycle_forum_refs_from_body(body: str) -> list[tuple[str, str]]:
    """PR body 에서 `cycle-forum: <cycle>:<thread_id>` regex 매칭 list.

    spec: docs/features/cycle-forum-operation.md §5-5 (PR cf-3).
    Returns: [(cycle, thread_id), ...] — dedup 보존 순서.
    """
    if not body:
        return []
    matches = CYCLE_FORUM_PR_BODY_RE.findall(body)
    seen: set[tuple[str, str]] = set()
    result: list[tuple[str, str]] = []
    for cycle, thread_id in matches:
        key = (cycle.lower(), thread_id)
        if key not in seen:
            seen.add(key)
            result.append(key)
    return result


async def cycle_thread_complete_on_merge_loop(
    *,
    poll_interval: int = DIRECTIVE_COMPLETE_POLL_INTERVAL_DEFAULT,
    initial_delay: int = 30,
    fetcher=None,
) -> None:
    """5분 polling — PR body 의 cycle-forum: <cycle>:<thread_id> 매칭 시 자동 ✅ retag.

    spec: docs/features/cycle-forum-operation.md §5-5 (PR cf-3). PR B 자매 — 같은 fetcher / seen cap / heartbeat.
    """
    if poll_interval <= 0:
        logger.info("cycle_thread_complete_on_merge_loop disabled (poll_interval<=0)")
        return

    fetch = fetcher or fetch_recent_merged_prs_with_body
    seen_prs: set[int] = set()
    reply_script = Path.home() / ".mobruji" / "discord-reply.sh"

    if initial_delay > 0:
        await asyncio.sleep(initial_delay)

    while True:
        try:
            record_loop_heartbeat("cycle_thread_complete_on_merge_loop")
            prs = fetch()
            for pr in prs:
                pr_number = pr.get("number")
                if not isinstance(pr_number, int) or pr_number in seen_prs:
                    continue
                body = pr.get("body") or ""
                refs = extract_cycle_forum_refs_from_body(body)
                if not refs:
                    continue
                if not reply_script.exists():
                    logger.warning(
                        "cycle_thread_complete_on_merge: discord-reply.sh 부재 — skip"
                    )
                    break
                logger.info(
                    "cycle_thread_complete_on_merge: PR #%d body 에서 cycle-forum 매칭: %s",
                    pr_number, refs,
                )
                for cycle, thread_id in refs:
                    try:
                        subprocess.run(  # noqa: S603
                            ["bash", str(reply_script),
                             "--forum-retag", thread_id, cycle, "완료"],
                            check=False, timeout=15.0, capture_output=True,
                        )
                        logger.info(
                            "cycle_thread_complete_on_merge: ✅ retag cycle=%s thread=%s pr=#%d",
                            cycle, thread_id, pr_number,
                        )
                    except (OSError, subprocess.TimeoutExpired) as exc:
                        logger.warning(
                            "cycle_thread_complete_on_merge: forum-retag 실패 thread=%s: %r",
                            thread_id, exc,
                        )
                seen_prs.add(pr_number)
                if len(seen_prs) > 200:
                    seen_prs = set(list(seen_prs)[100:])
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("cycle_thread_complete_on_merge_loop iter 실패: %r", exc)

        await asyncio.sleep(poll_interval)


async def rev_post_merge_audit_loop(
    client: "discord.Client",
    digest_channel_id: int,
    *,
    inject_target: str = REV_POST_MERGE_AUDIT_DEFAULT_INJECT_TARGET,
    poll_interval: int = REV_POST_MERGE_AUDIT_DEFAULT_INTERVAL_SECONDS,
    initial_delay: int = REV_POST_MERGE_AUDIT_DEFAULT_INITIAL_DELAY_SECONDS,
    debounce_seconds: int = REV_POST_MERGE_AUDIT_DEBOUNCE_SECONDS,
    pass_label: str = REV_POST_MERGE_PASS_LABEL,
    search_window: str = REV_POST_MERGE_AUDIT_SEARCH_WINDOW,
    candidate_fetcher=None,
    time_source=time.monotonic,
) -> None:
    """5분 polling — develop 머지된 PR 단계 2 audit 자동 trigger (#1008).

    spec: docs/features/rev-e2e-3-stages.md §3-2.

    동작:
      1. ``initial_delay`` 초 warmup 후 polling 시작.
      2. ``poll_interval`` 초마다 `gh pr list ... -label:rev-post-merge-pass` 호출.
      3. 후보 PR ≥ 1 → debounce 적용 후 fresh PR 만 추출.
      4. fresh ≥ 1:
         - nmae tmux pane (``inject_target``) 에 inject (단계 2 audit launch 알림).
         - Discord ``digest_channel_id`` 에 push (cycle digest 채널 공유).
         - 각 fresh PR `last_inject_at` 갱신.
      5. graceful skip — gh CLI 실패 / fresh 없음 / tmux 부재 / Discord channel 부재 시
         warn 1회 + 다음 iter 재시도.
      6. ``poll_interval <= 0`` 이면 disabled — 즉시 return (테스트 용).

    asyncio.CancelledError 는 외부로 전파해 bot 종료 시 깔끔히 정리.

    Args:
        candidate_fetcher: ``() -> list[int]`` 콜러블. None 이면 기본
            ``fetch_rev_post_merge_candidates`` 사용. 테스트 stub 진입점.
        time_source: monotonic 시각 source. 테스트 stub.
    """
    if poll_interval <= 0:
        logger.info("rev_post_merge_audit_loop disabled (poll_interval<=0)")
        return

    inject_session = inject_target.split(":", 1)[0]
    last_inject_at: dict[int, float] = {}
    missing_session_warned = False
    missing_channel_warned = False

    if candidate_fetcher is None:
        def _default_fetcher() -> list[int]:
            return fetch_rev_post_merge_candidates(
                pass_label=pass_label,
                search_window=search_window,
            )
        candidate_fetcher = _default_fetcher

    await asyncio.sleep(initial_delay)

    while True:
        try:
            candidates = candidate_fetcher()
            if not candidates:
                logger.debug("rev post-merge audit: 후보 0 — skip")
                await asyncio.sleep(poll_interval)
                continue
            mono_now = time_source()
            fresh = filter_debounced_prs(
                candidates,
                last_inject_at,
                now_monotonic=mono_now,
                debounce_seconds=debounce_seconds,
            )
            if not fresh:
                logger.debug(
                    "rev post-merge audit: 모든 후보 debounce 적중 — skip (n=%d)",
                    len(candidates),
                )
                await asyncio.sleep(poll_interval)
                continue

            if not tmux_has_session(inject_session):
                if not missing_session_warned:
                    logger.warning(
                        "rev post-merge audit: tmux session 부재 — skip (target=%s)",
                        inject_target,
                    )
                    missing_session_warned = True
                await asyncio.sleep(poll_interval)
                continue
            missing_session_warned = False

            inject_msg = format_rev_post_merge_inject(fresh)
            tmux_inject_text(inject_target, inject_msg)

            channel = client.get_channel(digest_channel_id)
            if channel is None:
                if not missing_channel_warned:
                    logger.warning(
                        "rev post-merge audit: Discord channel 부재 — push skip (channel_id=%s)",
                        digest_channel_id,
                    )
                    missing_channel_warned = True
            else:
                missing_channel_warned = False
                await send_with_retry(
                    channel, content=format_rev_post_merge_discord(fresh)
                )

            for pr in fresh:
                last_inject_at[pr] = mono_now
            logger.info(
                "rev_post_merge_audit_loop: inject fresh=%s candidates=%d",
                ",".join(f"#{n}" for n in fresh),
                len(candidates),
            )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("rev_post_merge_audit_loop iter 실패: %s", exc)
        record_loop_heartbeat("rev_post_merge_audit_loop")
        await asyncio.sleep(poll_interval)


# ─────────────────────────────────────────────────────────────────────────────
# Claude API usage tracker loop (#1020)
# spec: 이슈 #1020 — `claude_usage_tracker.py` 모듈 + asyncio loop.
# ─────────────────────────────────────────────────────────────────────────────


async def claude_usage_watch_loop(
    client: "discord.Client",
    alert_channel_id: int,
    *,
    projects_root: Path,
    state_path: Path,
    daily_limit: int = CLAUDE_DAILY_TOKEN_LIMIT_DEFAULT,
    weekly_limit: int = CLAUDE_WEEKLY_TOKEN_LIMIT_DEFAULT,
    poll_interval: int = CLAUDE_USAGE_DEFAULT_INTERVAL_SECONDS,
    initial_delay: int = CLAUDE_USAGE_DEFAULT_INITIAL_DELAY_SECONDS,
) -> None:
    """주기적으로 Claude usage 를 scan + state 갱신 + threshold push.

    spec: 이슈 #1020.

    동작:
      1. ``initial_delay`` 초 warmup 후 polling 시작.
      2. ``poll_interval`` 초마다:
         a. ``claude_usage_scan(projects_root, daily/weekly_limit)`` 호출.
         b. ``claude_usage_read_state(state_path)`` 로 기존 state 로드.
         c. ``claude_usage_update_and_detect`` 로 자정/월요일 reset + 10% bucket
            새 진입 events 산출.
         d. ``claude_usage_write_state`` 로 atomic write.
         e. events 마다 ALERT_CHANNEL_ID 에 push (``format_threshold_message``).
      3. graceful skip — scan/state/push 실패는 warning 1회 후 다음 iter 재시도.
      4. ``poll_interval <= 0`` 이면 disabled (테스트 용).

    asyncio.CancelledError 는 외부로 전파해 bot 종료 시 깔끔히 정리.
    """
    if poll_interval <= 0:
        logger.info("claude_usage_watch_loop disabled (poll_interval<=0)")
        return

    await asyncio.sleep(initial_delay)
    missing_channel_warned = False

    while True:
        try:
            snapshot = claude_usage_scan(
                projects_root=projects_root,
                daily_limit=daily_limit,
                weekly_limit=weekly_limit,
            )
            state = claude_usage_read_state(
                state_path,
                daily_limit=daily_limit,
                weekly_limit=weekly_limit,
            )
            new_state, events = claude_usage_update_and_detect(state, snapshot)
            # limit 변경 반영.
            new_state.setdefault("limits", {})["daily"] = daily_limit
            new_state["limits"]["weekly"] = weekly_limit
            claude_usage_write_state(new_state, state_path)
            logger.info(
                "claude_usage scan: daily=%d/%d (%d%%) weekly=%d/%d (%d%%) events=%d",
                snapshot.daily_tokens,
                daily_limit,
                snapshot.daily_pct,
                snapshot.weekly_tokens,
                weekly_limit,
                snapshot.weekly_pct,
                len(events),
            )

            if events:
                channel = client.get_channel(alert_channel_id)
                if channel is None:
                    if not missing_channel_warned:
                        logger.warning(
                            "claude_usage_watch_loop: ALERT channel 부재 — skip (channel_id=%s)",
                            alert_channel_id,
                        )
                        missing_channel_warned = True
                else:
                    missing_channel_warned = False
                    for event in events:
                        msg = format_threshold_message(
                            event,
                            daily_limit=daily_limit,
                            weekly_limit=weekly_limit,
                        )
                        await send_with_retry(channel, content=msg)
                        logger.info(
                            "claude_usage threshold push: kind=%s bucket=%d%%",
                            event.kind,
                            event.bucket_pct,
                        )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("claude_usage_watch_loop iter 실패: %s", exc)
        record_loop_heartbeat("claude_usage_watch_loop")
        await asyncio.sleep(poll_interval)


# ─────────────────────────────────────────────────────────────────────────────
# directive-board auto-PATCH loop — 폐기 (PR #1140 event-driven 전환, 2026-05-27)
# ─────────────────────────────────────────────────────────────────────────────
# 기존 `directive_board_sync_loop` 함수는 PR #1140 (`docs/features/
# directive-board-event-driven-redesign.md`) 에 따라 폐기. polling sync 가
# desync 사고 (`directive_board_mismatch=106`, 2026-05-26) 의 root cause —
# event-driven actor atomic 호출 (`directive_append.sh` / `directive_status.sh`)
# 로 대체. bot.py 는 dumb conduit 으로 `on_message` 안 `directive_append.sh`
# 호출만 담당.
# ─────────────────────────────────────────────────────────────────────────────


# 2026-05-29 폐기: directive_register_watch_loop (옛 design 의 자동 분류 등록 가정).
# 새 design 은 사용자 📌 만 directive 등록 trigger — detect (모든 메시지) vs board
# (📌 적재만) 비교는 본질적으로 항상 mismatch. noise 알림 제거.
# directive-detect.jsonl 자체는 분류 로그로 보존 — 회고 / 통계 용도.


# ─────────────────────────────────────────────────────────────────────────────
# Loop heartbeat watchdog (#1087, 2026-05-26 사용자 P0)
# ─────────────────────────────────────────────────────────────────────────────


async def heartbeat_watch_loop(
    client: "discord.Client",
    digest_channel_id: int,
    *,
    expected_intervals: dict[str, int] | None = None,
    multiplier: int = HEARTBEAT_STALE_MULTIPLIER_DEFAULT,
    heartbeat_dir: Path | None = None,
    poll_interval: int = HEARTBEAT_WATCH_DEFAULT_INTERVAL_SECONDS,
    initial_delay: int = HEARTBEAT_WATCH_DEFAULT_INITIAL_DELAY_SECONDS,
    push_debounce_seconds: int = HEARTBEAT_WATCH_PUSH_DEBOUNCE_SECONDS,
    time_source=time.monotonic,
    sleeper=asyncio.sleep,
) -> None:
    """주기 polling — 다른 watchdog loop 들이 silent crash 시 가시화 (#1087).

    배경 (사용자 P0, 2026-05-26):
        bot.py 의 watchdog loop (digest / context_auto_clear / cycle_idle_watch
        / rev_post_merge_audit / claude_usage_watch / directive_board_sync /
        thread_cleanup / directive_detect_register_watch) 가 asyncio exception
        삼킴 또는 무한 await 시 silent crash. 발생 시 무한 idle — 사용자 P0
        ("사이클 절대 멈추면 안 됨") 위반.

    동작:
        1. ``initial_delay`` 초 warmup 후 polling 시작.
        2. ``poll_interval`` 초마다:
           a. ``detect_stale_heartbeats`` 호출 — 각 loop heartbeat 파일이
              ``expected_interval × multiplier`` 초과 stale 인지 검사.
           b. stale 발견 시 DIGEST_CHANNEL_ID 에 ``format_heartbeat_stale_message``
              push (동일 loop 1h debounce).
           c. ``logger.error`` 도 동시 emit — journalctl 추적 용.
        3. 자기 heartbeat 도 매 iter 기록 → meta detect (스스로 stale 진단 가능).
        4. graceful skip — channel 부재 / write 실패 시 warn 후 다음 iter.
        5. ``poll_interval <= 0`` 이면 disabled (테스트 용).

    asyncio.CancelledError 는 외부로 전파해 bot 종료 시 깔끔히 정리.

    Args:
        expected_intervals: loop name → 정상 max iter 초 매핑. None 이면
            ``LOOP_HEARTBEAT_EXPECTED_INTERVALS`` (heartbeat_watch_loop 자체 포함).
        multiplier: stale 임계 multiplier (default 3).
        heartbeat_dir: heartbeat 디렉토리 override (테스트). None 이면 env / default.
        push_debounce_seconds: 동일 loop stale push 재전송 차단 윈도우 (default 1h).
        time_source: monotonic 시각 source (테스트 stub).
        sleeper: async sleep 콜러블 (테스트 stub).
    """
    if poll_interval <= 0:
        logger.info("heartbeat_watch_loop disabled (poll_interval<=0)")
        return

    intervals = (
        expected_intervals
        if expected_intervals is not None
        else dict(LOOP_HEARTBEAT_EXPECTED_INTERVALS)
    )
    last_push_at: dict[str, float] = {}
    missing_channel_warned = False

    await sleeper(initial_delay)
    while True:
        try:
            stale = detect_stale_heartbeats(
                expected_intervals=intervals,
                multiplier=multiplier,
                heartbeat_dir=heartbeat_dir,
                monotonic_source=time_source,
            )
            if stale:
                mono_now = float(time_source())
                fresh = []
                for entry in stale:
                    last = last_push_at.get(entry["name"])
                    if last is None or (mono_now - last) >= push_debounce_seconds:
                        fresh.append(entry)
                if fresh:
                    names_label = ", ".join(e["name"] for e in fresh)
                    logger.error(
                        "heartbeat_watch_loop: stale 검출 %d loops — %s",
                        len(fresh),
                        names_label,
                    )
                    channel = client.get_channel(digest_channel_id)
                    if channel is None:
                        if not missing_channel_warned:
                            logger.warning(
                                "heartbeat_watch_loop: Discord channel 부재 — "
                                "push skip (channel_id=%s)",
                                digest_channel_id,
                            )
                            missing_channel_warned = True
                    else:
                        missing_channel_warned = False
                        push_text = format_heartbeat_stale_message(fresh)
                        await send_with_retry(channel, content=push_text)
                        for entry in fresh:
                            last_push_at[entry["name"]] = mono_now
            else:
                logger.debug("heartbeat_watch_loop: all loops alive")
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("heartbeat_watch_loop iter 실패: %s", exc)
        record_loop_heartbeat(
            "heartbeat_watch_loop", heartbeat_dir=heartbeat_dir
        )
        await sleeper(poll_interval)


# ─────────────────────────────────────────────────────────────────────────────
# Discord thread auto-cleanup (#1023, 2026-05-24 사용자 P0)
# ─────────────────────────────────────────────────────────────────────────────
#
# Why: helper sub-agent launch 별 per-launch thread (#1011) + auto-ack thread
# 가 쌓이면 채널 sidebar 가 18+ 누적 → 사용자 가시성 ↓. 주기 archive (또는
# delete) 로 시야 정리. requests 모듈을 직접 import 하지 않고 의존성 주입식
# (`http_get`/`http_patch`/`http_delete`) 으로 정의 — 테스트 stub 친화 + bot.py
# 시작 시점에 requests 부재여도 import 자체는 깨지지 않게 lazy import.


def snowflake_to_datetime(snowflake) -> datetime:
    """Discord snowflake 를 timezone-aware (UTC) datetime 으로 변환합니다.

    snowflake bit layout:
      [42b unix ms - DISCORD_EPOCH][5b worker][5b process][12b increment]

    Args:
        snowflake: int 또는 str. 정수 변환 불가 시 ValueError raise.

    Returns:
        ``datetime`` (tz=UTC).
    """
    value = int(snowflake)
    ms = (value >> 22) + DISCORD_EPOCH_MS
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc)


def thread_last_activity(thread: dict) -> datetime:
    """thread dict 에서 마지막 활동 시각을 추정합니다.

    우선순위:
      1. ``last_message_id`` (snowflake) → 변환.
      2. ``thread_metadata.create_timestamp`` (ISO8601) → 파싱.
      3. ``id`` (thread snowflake = 생성 시각) → 변환.

    어느 source 도 valid 하지 않으면 ``id`` 기반 결과 (= 2015 epoch) 로 fallback.
    """
    raw_last = thread.get("last_message_id")
    if raw_last is not None:
        try:
            return snowflake_to_datetime(raw_last)
        except (TypeError, ValueError):
            pass
    meta = thread.get("thread_metadata") or {}
    create_ts = meta.get("create_timestamp")
    if isinstance(create_ts, str) and create_ts.strip():
        try:
            # Discord ISO8601 timestamp — 'Z' 정규화.
            normalized = create_ts.strip().replace("Z", "+00:00")
            return datetime.fromisoformat(normalized).astimezone(timezone.utc)
        except (TypeError, ValueError):
            pass
    return snowflake_to_datetime(thread.get("id", "0"))


def select_threads_to_archive(
    threads: list[dict],
    *,
    now_utc: datetime,
    age_hours: float | None = None,
    age_minutes: float | None = None,
    keep_recent: int = 3,
) -> list[dict]:
    """archive 후보 thread 목록을 산출합니다.

    Args:
        threads: ``fetch_active_threads`` 결과.
        now_utc: 현재 시각 (tz=UTC) — 테스트 deterministic 위해 외부 주입.
        age_hours: 시간 단위 임계. (후방호환 — 호출부 minutes 우선시 무시.)
        age_minutes: 분 단위 임계. None 이면 ``age_hours`` 사용.
        keep_recent: 가장 최근 활동 thread N 개 보존.

    Returns:
        archive 대상 thread dict list (입력 순서 유지).
    """
    if not threads:
        return []
    if age_minutes is not None:
        threshold = timedelta(minutes=age_minutes)
    elif age_hours is not None:
        threshold = timedelta(hours=age_hours)
    else:
        # 안전 default — age 미지정 시 archive 안 함 (보수).
        return []

    enriched = [(thread_last_activity(t), t) for t in threads]
    enriched.sort(key=lambda pair: pair[0], reverse=True)
    # keep_recent 만큼 가장 최근 thread 제외.
    candidates = enriched[max(keep_recent, 0):]
    cutoff = now_utc - threshold
    return [t for ts, t in candidates if ts < cutoff]


def fetch_guild_id(
    channel_id: str,
    token: str,
    *,
    http_get=None,
) -> str | None:
    """채널의 guild_id 조회 (GET /channels/{channel.id}). 실패 시 None."""
    url = f"https://discord.com/api/v10/channels/{channel_id}"
    headers = {"Authorization": f"Bot {token}"}
    get = http_get if http_get is not None else _default_http_get
    try:
        resp = get(url, headers=headers, timeout=THREAD_CLEANUP_HTTP_TIMEOUT_SECONDS)
    except Exception as exc:  # noqa: BLE001
        logger.warning("fetch_guild_id 실패: channel=%s err=%s", channel_id, exc)
        return None
    ok = getattr(resp, "ok", 200 <= getattr(resp, "status_code", 500) < 300)
    if not ok:
        logger.warning(
            "fetch_guild_id non-2xx: channel=%s status=%s body=%r",
            channel_id,
            getattr(resp, "status_code", "?"),
            truncate_for_log(getattr(resp, "text", "") or ""),
        )
        return None
    try:
        data = resp.json()
    except Exception as exc:  # noqa: BLE001
        logger.warning("fetch_guild_id JSON 파싱 실패: %s", exc)
        return None
    guild_id = data.get("guild_id") if isinstance(data, dict) else None
    return str(guild_id) if guild_id is not None else None


def fetch_active_threads(
    guild_id: str,
    parent_channel_id: str,
    token: str,
    *,
    http_get=None,
) -> list[dict]:
    """guild 의 active threads 중 ``parent_id == parent_channel_id`` 인 것만 반환.

    실패 / 네트워크 오류 → 빈 list (loop 진행 보장).
    """
    url = f"https://discord.com/api/v10/guilds/{guild_id}/threads/active"
    headers = {"Authorization": f"Bot {token}"}
    get = http_get if http_get is not None else _default_http_get
    try:
        resp = get(url, headers=headers, timeout=THREAD_CLEANUP_HTTP_TIMEOUT_SECONDS)
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "fetch_active_threads 실패: guild=%s err=%s", guild_id, exc
        )
        return []
    ok = getattr(resp, "ok", 200 <= getattr(resp, "status_code", 500) < 300)
    if not ok:
        logger.warning(
            "fetch_active_threads non-2xx: guild=%s status=%s body=%r",
            guild_id,
            getattr(resp, "status_code", "?"),
            truncate_for_log(getattr(resp, "text", "") or ""),
        )
        return []
    try:
        data = resp.json()
    except Exception as exc:  # noqa: BLE001
        logger.warning("fetch_active_threads JSON 파싱 실패: %s", exc)
        return []
    if not isinstance(data, dict):
        return []
    threads = data.get("threads") or []
    if not isinstance(threads, list):
        return []
    return [
        t
        for t in threads
        if isinstance(t, dict) and str(t.get("parent_id")) == str(parent_channel_id)
    ]


def archive_thread(
    thread_id: str,
    token: str,
    *,
    http_patch=None,
) -> bool:
    """PATCH /channels/{thread.id} {"archived": true}. 성공 시 True."""
    url = f"https://discord.com/api/v10/channels/{thread_id}"
    headers = {
        "Authorization": f"Bot {token}",
        "Content-Type": "application/json",
    }
    patch = http_patch if http_patch is not None else _default_http_patch
    try:
        resp = patch(
            url,
            headers=headers,
            json={"archived": True},
            timeout=THREAD_CLEANUP_HTTP_TIMEOUT_SECONDS,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("archive_thread 실패: thread=%s err=%s", thread_id, exc)
        return False
    ok = getattr(resp, "ok", 200 <= getattr(resp, "status_code", 500) < 300)
    if not ok:
        logger.warning(
            "archive_thread non-2xx: thread=%s status=%s body=%r",
            thread_id,
            getattr(resp, "status_code", "?"),
            truncate_for_log(getattr(resp, "text", "") or ""),
        )
        return False
    return True


def delete_thread(
    thread_id: str,
    token: str,
    *,
    http_delete=None,
) -> bool:
    """DELETE /channels/{thread.id}. 성공 시 True. 사용자 옵션 (#1023 P0)."""
    url = f"https://discord.com/api/v10/channels/{thread_id}"
    headers = {"Authorization": f"Bot {token}"}
    delete = http_delete if http_delete is not None else _default_http_delete
    try:
        resp = delete(
            url, headers=headers, timeout=THREAD_CLEANUP_HTTP_TIMEOUT_SECONDS
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("delete_thread 실패: thread=%s err=%s", thread_id, exc)
        return False
    ok = getattr(resp, "ok", 200 <= getattr(resp, "status_code", 500) < 300)
    if not ok:
        logger.warning(
            "delete_thread non-2xx: thread=%s status=%s body=%r",
            thread_id,
            getattr(resp, "status_code", "?"),
            truncate_for_log(getattr(resp, "text", "") or ""),
        )
        return False
    return True


def _default_http_get(url, headers=None, timeout=None):
    """lazy ``requests.get`` — bot.py top-level import 회피."""
    import requests  # type: ignore[import-not-found]

    return requests.get(url, headers=headers, timeout=timeout)


def _default_http_patch(url, headers=None, json=None, timeout=None):
    """lazy ``requests.patch``."""
    import requests  # type: ignore[import-not-found]

    return requests.patch(url, headers=headers, json=json, timeout=timeout)


def _default_http_delete(url, headers=None, timeout=None):
    """lazy ``requests.delete``."""
    import requests  # type: ignore[import-not-found]

    return requests.delete(url, headers=headers, timeout=timeout)


async def thread_cleanup_loop(
    channel_id: str,
    token: str,
    *,
    poll_interval: int = THREAD_CLEANUP_DEFAULT_INTERVAL_SECONDS,
    initial_delay: int = THREAD_CLEANUP_DEFAULT_INITIAL_DELAY_SECONDS,
    age_hours: float | None = None,
    age_minutes: float | None = THREAD_CLEANUP_DEFAULT_AGE_MINUTES,
    keep_recent: int = THREAD_CLEANUP_DEFAULT_KEEP_RECENT,
    delete: bool = False,
    guild_id_fetcher=None,
    threads_fetcher=None,
    archiver=None,
    deleter=None,
    sleeper=asyncio.sleep,
    now_source=None,
) -> None:
    """주기적으로 채널의 오래된 thread 를 archive (또는 delete) 합니다 (#1023).

    의존성 주입 (모두 keyword-only)  로 테스트 stub 친화:
      - ``guild_id_fetcher``: ``() -> str | None``. None 이면 ``fetch_guild_id``.
      - ``threads_fetcher``: ``(guild_id) -> list[dict]``.
      - ``archiver``: ``(thread_id) -> bool``. None 이면 ``archive_thread``.
      - ``deleter``: ``(thread_id) -> bool``. None 이면 ``delete_thread``.
      - ``sleeper``: ``async (sec)``. asyncio.sleep default — pytest 에서 noop.
      - ``now_source``: ``() -> datetime (UTC)``. None 이면 ``datetime.now(tz)``.

    동작:
      1. ``poll_interval <= 0`` → disabled, 즉시 return.
      2. ``initial_delay`` 후 polling 시작.
      3. 매 iter:
         a. guild_id 미상이면 fetch — None 이면 다음 iter 재시도.
         b. active threads 조회 → ``select_threads_to_archive`` 로 후보 산출.
         c. 각 후보 archive (또는 delete) — per-thread sleep 으로 ratelimit 완화.
      4. 예외는 warning 으로 swallow — loop 가 죽지 않게.
    """
    if poll_interval <= 0:
        logger.info("thread_cleanup_loop disabled (poll_interval<=0)")
        return

    await sleeper(initial_delay)

    if guild_id_fetcher is None:
        def guild_id_fetcher() -> str | None:
            return fetch_guild_id(channel_id, token)
    if threads_fetcher is None:
        def threads_fetcher(guild_id: str) -> list[dict]:
            return fetch_active_threads(guild_id, channel_id, token)
    if archiver is None:
        def archiver(thread_id: str) -> bool:
            return archive_thread(thread_id, token)
    if deleter is None:
        def deleter(thread_id: str) -> bool:
            return delete_thread(thread_id, token)
    if now_source is None:
        def now_source() -> datetime:
            return datetime.now(timezone.utc)

    cached_guild_id: str | None = None
    age_repr = (
        f"{age_minutes}min" if age_minutes is not None else f"{age_hours}h"
    )
    logger.info(
        "thread_cleanup_loop launched: channel=%s interval=%ds age=%s keep=%d delete=%s",
        channel_id,
        poll_interval,
        age_repr,
        keep_recent,
        delete,
    )
    iter_count = 0
    while True:
        iter_count += 1
        try:
            if cached_guild_id is None:
                cached_guild_id = guild_id_fetcher()
                if cached_guild_id is None:
                    # #1062 silent root cause fix: debug → warning 격상.
                    # guild_id 미상이 silent 일 때 사용자는 "주기 청소 안되고
                    # 있네" 보고. warning 으로 가시화해야 root cause 추적 가능.
                    logger.warning(
                        "thread_cleanup_loop iter=%d: guild_id 미상 — "
                        "다음 iter 재시도 (channel=%s). 지속 발생 시 "
                        "Bot 권한 / channel ID 검증 필요.",
                        iter_count,
                        channel_id,
                    )
                    await sleeper(poll_interval)
                    continue

            # #1062 silent root cause fix: iter 진입 가시화 — debug 가 아닌
            # info 로 매 iter "alive" 신호. 사용자가 "주기 청소 안되고 있네"
            # 보고 시 첫 진단 점이 "loop 가 iter 도는가" 인데 이 로그가 없으면
            # silent. INFO 로 격상해 journal grep 으로 즉시 확인 가능.
            logger.info(
                "thread_cleanup_loop iter=%d: scan 시작 (guild=%s channel=%s)",
                iter_count,
                cached_guild_id,
                channel_id,
            )
            threads = threads_fetcher(cached_guild_id)
            scanned = len(threads)
            candidates = select_threads_to_archive(
                threads,
                now_utc=now_source(),
                age_hours=age_hours,
                age_minutes=age_minutes,
                keep_recent=keep_recent,
            )

            archived = 0
            deleted = 0
            skipped = 0
            for cand in candidates:
                tid = str(cand.get("id"))
                if not tid or tid == "None":
                    skipped += 1
                    continue
                if delete:
                    ok = deleter(tid)
                    if ok:
                        deleted += 1
                    else:
                        skipped += 1
                else:
                    ok = archiver(tid)
                    if ok:
                        archived += 1
                    else:
                        skipped += 1
                # per-thread 짧은 휴식 — 429 회피.
                await sleeper(THREAD_CLEANUP_PER_THREAD_SLEEP_SECONDS)

            logger.info(
                "thread_cleanup_loop iter=%d: scanned=%d candidates=%d "
                "archived=%d deleted=%d skipped=%d (keep=%d age=%s)",
                iter_count,
                scanned,
                len(candidates),
                archived,
                deleted,
                skipped,
                keep_recent,
                age_repr,
            )
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "thread_cleanup_loop iter=%d 실패: %s", iter_count, exc
            )
        record_loop_heartbeat("thread_cleanup_loop")
        await sleeper(poll_interval)


# ─────────────────────────────────────────────────────────────────────────────
# Discord client
# ─────────────────────────────────────────────────────────────────────────────


def build_client(env: dict[str, str], ledger: DedupLedger | None) -> discord.Client:
    """discord.py Client 를 셋업하고 핸들러를 바인딩합니다."""
    intents = discord.Intents.default()
    intents.message_content = True
    client = discord.Client(intents=intents)

    # 2026-05-29 — `/mb auto` / `/mb ask` / `/mb status` slash command.
    # Discord 의 핀 메시지 view 에서 button 작동 X 한계 (사용자 정정) 우회.
    # autocomplete 표준 UX — typing 최소 + 어디서나 작동.
    tree = discord.app_commands.CommandTree(client)
    client._mb_tree = tree  # type: ignore[attr-defined] — on_ready 안 sync 위해 보존

    allowed_user_ids = parse_allowed_user_ids(env["ALLOWED_USER_IDS"])
    try:
        target_channel_id = int(env["MOBRUJI_CHANNEL_ID"])
    except ValueError:
        logger.error("MOBRUJI_CHANNEL_ID 가 정수 아님: %r", env["MOBRUJI_CHANNEL_ID"])
        sys.exit(1)

    # ─── /mb slash command group ─────────────────────────────────────────────
    # `/mb auto` / `/mb ask` / `/mb status` — mode toggle 핀 메시지 button 한계 우회.
    mb_group = discord.app_commands.Group(
        name="mb", description="mobruji 명령 (mode toggle 등)",
    )

    @mb_group.command(name="auto", description="자율 모드 (사용자에게 묻지 않음)")
    async def _mb_auto(interaction: discord.Interaction) -> None:
        if interaction.user.id not in allowed_user_ids:
            await interaction.response.send_message(
                "권한이 없습니다.", ephemeral=True,
            )
            return
        try:
            write_user_mode("AUTO")
        except (OSError, ValueError) as exc:
            logger.warning("/mb auto write 실패: %r", exc)
            await interaction.response.send_message(
                f"❌ 변경 실패: {exc}", ephemeral=True,
            )
            return
        logger.info("/mb auto: user=%s", interaction.user.id)
        await interaction.response.send_message(
            "✅ 모드가 **AUTO** 로 변경되었습니다.", ephemeral=True,
        )

    @mb_group.command(name="ask", description="질문 모드 (확인 받음)")
    async def _mb_ask(interaction: discord.Interaction) -> None:
        if interaction.user.id not in allowed_user_ids:
            await interaction.response.send_message(
                "권한이 없습니다.", ephemeral=True,
            )
            return
        try:
            write_user_mode("ASK")
        except (OSError, ValueError) as exc:
            logger.warning("/mb ask write 실패: %r", exc)
            await interaction.response.send_message(
                f"❌ 변경 실패: {exc}", ephemeral=True,
            )
            return
        logger.info("/mb ask: user=%s", interaction.user.id)
        await interaction.response.send_message(
            "✅ 모드가 **ASK** 로 변경되었습니다.", ephemeral=True,
        )

    @mb_group.command(name="status", description="현재 모드 조회")
    async def _mb_status(interaction: discord.Interaction) -> None:
        if interaction.user.id not in allowed_user_ids:
            await interaction.response.send_message(
                "권한이 없습니다.", ephemeral=True,
            )
            return
        current = read_user_mode()
        await interaction.response.send_message(
            f"현재 모드: **{current}**", ephemeral=True,
        )

    tree.add_command(mb_group)

    # DIGEST_CHANNEL_ID (rename, #1019). load_env 에서 backward-compat
    # NOTIFY_CHANNEL_ID fallback 처리 후 env["DIGEST_CHANNEL_ID"] 보장.
    digest_raw = env.get("DIGEST_CHANNEL_ID", env["MOBRUJI_CHANNEL_ID"])
    try:
        digest_channel_id = int(digest_raw)
    except ValueError:
        logger.warning(
            "DIGEST_CHANNEL_ID 가 정수 아님(%r) — 메인 채널(%d)로 fallback",
            digest_raw,
            target_channel_id,
        )
        digest_channel_id = target_channel_id

    # ALERT_CHANNEL_ID (#1020) — cycle idle / future-ts ERROR / escalation 등
    # "알림" 류 push 1차 채널. fallback: ALERT_CHANNEL_ID → DIGEST_CHANNEL_ID →
    # MOBRUJI_CHANNEL_ID. load_env 가 ALERT_CHANNEL_ID 부재 시 이미 DIGEST 로
    # fallback 처리. 정수 parsing 실패 시 digest 로 한번 더 fallback.
    alert_raw = env.get("ALERT_CHANNEL_ID", str(digest_channel_id))
    try:
        alert_channel_id = int(alert_raw)
    except ValueError:
        logger.warning(
            "ALERT_CHANNEL_ID 가 정수 아님(%r) — digest 채널(%d)로 fallback",
            alert_raw,
            digest_channel_id,
        )
        alert_channel_id = digest_channel_id

    # Per-cycle 채널 (P12, 2026-05-24) — on_ready 로그용. 미설정 시 0 (= "unset").
    # discord-reply.sh 측이 fallback (DIGEST/NOTIFY) 책임 — bot.py 는 단순히
    # 운영자가 4 채널 분리를 의도했는지 가시화. 본 값을 별도 사용하지 않으나,
    # 향후 digest aggregate 가 4 cycle 별 최근 N 메시지를 묶어 전송하는 확장
    # (본 PR 범위 외) 의 진입점.
    def _parse_cycle_channel(name: str) -> int:
        raw = env.get(f"{name}_CHANNEL_ID", "")
        if not raw:
            return 0
        try:
            return int(raw)
        except ValueError:
            logger.warning(
                "%s_CHANNEL_ID 가 정수 아님(%r) — 라우팅 disabled (DIGEST fallback)",
                name,
                raw,
            )
            return 0

    cycle_channel_ids = {
        "be": _parse_cycle_channel("BE"),
        "fe": _parse_cycle_channel("FE"),
        "rev": _parse_cycle_channel("REV"),
        "plan": _parse_cycle_channel("PLAN"),
    }

    # Forum 채널 (#17, 2026-05-24) — on_ready 로그용. 0 = unset.
    # bot.py 가 본 값을 라우팅에 직접 쓰진 않으나 (discord-reply.sh 가 .env
    # 직접 read), 운영자가 5 forum 채널 설정을 의도했는지 가시화.
    def _parse_forum_channel(env_key: str) -> int:
        raw = env.get(env_key, "")
        if not raw:
            return 0
        try:
            return int(raw)
        except ValueError:
            logger.warning(
                "%s 가 정수 아님(%r) — forum routing disabled",
                env_key,
                raw,
            )
            return 0

    forum_channel_ids = {
        "directive": _parse_forum_channel("DIRECTIVE_BOARD_FORUM_ID"),
        "be": _parse_forum_channel("BE_FORUM_ID"),
        "fe": _parse_forum_channel("FE_FORUM_ID"),
        "rev": _parse_forum_channel("REV_FORUM_ID"),
        "plan": _parse_forum_channel("PLAN_FORUM_ID"),
    }

    session_name = env["TMUX_SESSION_NAME"]
    target_pane = env["TMUX_TARGET_PANE"]
    claude_bin = env["CLAUDE_BIN"]

    digest_enabled = env.get("DIGEST_ENABLED", "1") == "1"
    digest_interval = resolve_digest_interval(env.get("DIGEST_INTERVAL_SECONDS"))
    cycle_status_path = env.get("CYCLE_STATUS_PATH", DEFAULT_CYCLE_STATUS_PATH)
    cycle_counter_path = env.get("CYCLE_COUNTER_PATH", DEFAULT_CYCLE_COUNTER_PATH)

    bot_auto_ack_enabled = (
        env.get("BOT_AUTO_ACK", BOT_AUTO_ACK_DEFAULT_ENABLED) == "1"
    )
    # BOT_AUTO_ACK_MODE 는 #1175 에서 폐기. set 돼 있어도 reaction-only 로 강제.
    # 1회 deprecation log 만 emit — silent ignore 시 사용자가 env override 실패를
    # 못 알아챌 수 있음.
    if env.get("BOT_AUTO_ACK_MODE") is not None:
        logger.warning(
            "BOT_AUTO_ACK_MODE 는 폐기됐습니다 (#1175 reaction-only). 값(%r) 무시.",
            env.get("BOT_AUTO_ACK_MODE"),
        )
    bot_auto_ack_emoji = env.get(
        "BOT_AUTO_ACK_EMOJI", BOT_AUTO_ACK_EMOJI_DEFAULT
    )

    # secondary reaction (#1080) — nmae 점유 상태를 emoji 로 시각화.
    bot_secondary_reaction_enabled = (
        env.get(
            "BOT_SECONDARY_REACTION_ENABLED",
            BOT_SECONDARY_REACTION_DEFAULT_ENABLED,
        )
        == "1"
    )
    bot_secondary_reaction_emoji_idle = env.get(
        "BOT_SECONDARY_REACTION_EMOJI_IDLE",
        BOT_SECONDARY_REACTION_EMOJI_IDLE_DEFAULT,
    )
    bot_secondary_reaction_emoji_partial = env.get(
        "BOT_SECONDARY_REACTION_EMOJI_PARTIAL",
        BOT_SECONDARY_REACTION_EMOJI_PARTIAL_DEFAULT,
    )
    bot_secondary_reaction_emoji_full = env.get(
        "BOT_SECONDARY_REACTION_EMOJI_FULL",
        BOT_SECONDARY_REACTION_EMOJI_FULL_DEFAULT,
    )

    # cycle watchdog (#941) — env 해석.
    cycle_idle_watch_enabled = (
        env.get("CYCLE_IDLE_WATCH", CYCLE_IDLE_WATCH_DEFAULT_ENABLED) == "1"
    )

    def _resolve_int_env(key: str, default: int, *, allow_zero: bool = True) -> int:
        raw = env.get(key)
        if raw is None:
            return default
        try:
            parsed = int(raw)
        except ValueError:
            logger.warning("%s 정수 아님(%r) — 기본값 %d 사용", key, raw, default)
            return default
        if not allow_zero and parsed <= 0:
            logger.warning("%s 양수 아님(%d) — 기본값 %d 사용", key, parsed, default)
            return default
        if allow_zero and parsed < 0:
            logger.warning("%s 음수(%d) — 기본값 %d 사용", key, parsed, default)
            return default
        return parsed

    cycle_idle_threshold_minutes = _resolve_int_env(
        "CYCLE_IDLE_THRESHOLD_MINUTES", CYCLE_IDLE_THRESHOLD_DEFAULT_MINUTES
    )
    cycle_idle_poll_interval = _resolve_int_env(
        "CYCLE_IDLE_WATCH_INTERVAL_SECONDS",
        CYCLE_IDLE_WATCH_DEFAULT_INTERVAL_SECONDS,
        allow_zero=False,
    )
    cycle_inject_target = env.get(
        "CYCLE_INJECT_TARGET", CYCLE_IDLE_WATCH_DEFAULT_INJECT_TARGET
    )
    cycle_workspaces = resolve_cycle_targets(env.get("CYCLE_WATCH_WORKSPACES"))
    # CYCLE_REASON_REQUIRED (#956) — note 미명시 idle 을 strict 로 처리할지.
    cycle_reason_required = (
        env.get("CYCLE_REASON_REQUIRED", CYCLE_REASON_REQUIRED_DEFAULT) == "1"
    )
    # (#1020) watchdog idle / future-ts ERROR push 채널 — ALERT_CHANNEL_ID 1차.
    # CYCLE_NOTIFY_CHANNEL_ID env 명시되면 그것 우선 (운영 override 가능). 부재 시
    # alert_channel_id 사용 (= ALERT_CHANNEL_ID → DIGEST → MOBRUJI fallback 체인).
    cycle_notify_raw = env.get("CYCLE_NOTIFY_CHANNEL_ID", str(alert_channel_id))
    try:
        cycle_notify_channel_id = int(cycle_notify_raw)
    except ValueError:
        logger.warning(
            "CYCLE_NOTIFY_CHANNEL_ID 정수 아님(%r) — alert_channel_id(%d) fallback",
            cycle_notify_raw,
            alert_channel_id,
        )
        cycle_notify_channel_id = alert_channel_id
    # (#972) escalation env — threshold + debounce 정수 파싱 + 사용자 채널.
    cycle_escalation_threshold = _resolve_int_env(
        "CYCLE_INJECT_ESCALATION_THRESHOLD",
        CYCLE_INJECT_ESCALATION_THRESHOLD_DEFAULT,
    )
    cycle_escalation_debounce = _resolve_int_env(
        "CYCLE_INJECT_ESCALATION_DEBOUNCE_SECONDS",
        CYCLE_INJECT_ESCALATION_DEBOUNCE_SECONDS_DEFAULT,
        allow_zero=False,
    )
    # escalation push 채널 (#972 → #1020) — ALERT_CHANNEL_ID 로 통합.
    # 이전: MOBRUJI_CHANNEL_ID 직접 push 로 사용자 채널 노이즈 발생.
    # 변경: ALERT_CHANNEL_ID 가 별 채널 (#모부르지-알림) 로 분리되어 사용자
    # 본 채널 (#모부르지) 오염 없이 가시화 가능.
    cycle_escalation_channel_id: int | None = alert_channel_id

    # STALE_ACTIVE (#1015 follow-up P3a remediation) — env 해석.
    cycle_stale_active_enabled = (
        env.get("STALE_ACTIVE_ENABLED", STALE_ACTIVE_ENABLED_DEFAULT) == "1"
    )
    cycle_stale_active_threshold_minutes = _resolve_int_env(
        "STALE_ACTIVE_THRESHOLD_MIN",
        STALE_ACTIVE_THRESHOLD_DEFAULT_MIN,
        allow_zero=False,
    )

    # rev post-merge audit loop (#1008) — env 해석.
    rev_post_merge_audit_enabled = (
        env.get("REV_POST_MERGE_AUDIT_LOOP", REV_POST_MERGE_AUDIT_LOOP_DEFAULT_ENABLED)
        == "1"
    )
    rev_post_merge_audit_interval = _resolve_int_env(
        "REV_POST_MERGE_AUDIT_INTERVAL_SECONDS",
        REV_POST_MERGE_AUDIT_DEFAULT_INTERVAL_SECONDS,
        allow_zero=False,
    )
    rev_post_merge_audit_inject_target = env.get(
        "REV_POST_MERGE_AUDIT_INJECT_TARGET",
        REV_POST_MERGE_AUDIT_DEFAULT_INJECT_TARGET,
    )

    # Claude API usage tracker (#1020) — env 해석.
    claude_usage_loop_enabled = (
        env.get("CLAUDE_USAGE_LOOP", CLAUDE_USAGE_LOOP_DEFAULT_ENABLED) == "1"
    )
    claude_usage_interval = _resolve_int_env(
        "CLAUDE_USAGE_INTERVAL_SECONDS",
        CLAUDE_USAGE_DEFAULT_INTERVAL_SECONDS,
        allow_zero=False,
    )
    claude_daily_limit = _resolve_int_env(
        "CLAUDE_DAILY_TOKEN_LIMIT",
        CLAUDE_DAILY_TOKEN_LIMIT_DEFAULT,
        allow_zero=False,
    )
    claude_weekly_limit = _resolve_int_env(
        "CLAUDE_WEEKLY_TOKEN_LIMIT",
        CLAUDE_WEEKLY_TOKEN_LIMIT_DEFAULT,
        allow_zero=False,
    )
    claude_usage_projects_root = Path(
        env.get(
            "CLAUDE_USAGE_PROJECTS_ROOT", str(CLAUDE_USAGE_PROJECTS_ROOT_DEFAULT)
        )
    )
    claude_usage_state_path = Path(
        env.get("CLAUDE_USAGE_STATE_PATH", str(CLAUDE_USAGE_STATE_PATH_DEFAULT))
    )

    # directive-board (#P11 → PR #1140 event-driven) — env 해석.
    # polling sync_loop 폐기 — `directive_board_sync_enabled` / `_interval` /
    # `_channel_id` 변수 삭제. JSONL/STATE path 만 digest summary 와 향후
    # event-driven 호출용으로 유지.
    directive_board_jsonl_path = Path(
        env.get(
            "DIRECTIVE_BOARD_JSONL_PATH", str(DIRECTIVE_BOARD_JSONL_PATH_DEFAULT)
        )
    )
    directive_board_state_path = Path(
        env.get(
            "DIRECTIVE_BOARD_STATE_PATH", str(DIRECTIVE_BOARD_STATE_PATH_DEFAULT)
        )
    )

    # Discord thread auto-cleanup (#1023) — env 해석.
    thread_cleanup_enabled = (
        env.get("THREAD_CLEANUP_ENABLED", THREAD_CLEANUP_DEFAULT_ENABLED) == "1"
    )
    thread_cleanup_interval = _resolve_int_env(
        "THREAD_CLEANUP_INTERVAL_SECONDS",
        THREAD_CLEANUP_DEFAULT_INTERVAL_SECONDS,
        allow_zero=False,
    )
    # minutes 우선. legacy HOURS 도 인식 (비어 있지 않으면 시간 → 분 변환).
    thread_cleanup_age_minutes: float | None = float(
        _resolve_int_env(
            "THREAD_CLEANUP_AGE_MINUTES",
            THREAD_CLEANUP_DEFAULT_AGE_MINUTES,
            allow_zero=False,
        )
    )
    legacy_hours_raw = env.get("THREAD_CLEANUP_AGE_HOURS", "")
    if legacy_hours_raw and env.get(
        "THREAD_CLEANUP_AGE_MINUTES"
    ) is None:
        # MINUTES 미설정 + HOURS 만 설정 — legacy 모드. 시간 → 분 변환.
        try:
            thread_cleanup_age_minutes = float(legacy_hours_raw) * 60.0
            logger.info(
                "THREAD_CLEANUP_AGE_HOURS legacy 인식 — %s시간 = %.0f분",
                legacy_hours_raw,
                thread_cleanup_age_minutes,
            )
        except ValueError:
            logger.warning(
                "THREAD_CLEANUP_AGE_HOURS 정수 아님(%r) — 기본 %d분 사용",
                legacy_hours_raw,
                THREAD_CLEANUP_DEFAULT_AGE_MINUTES,
            )
    thread_cleanup_keep_recent = _resolve_int_env(
        "THREAD_CLEANUP_KEEP_RECENT",
        THREAD_CLEANUP_DEFAULT_KEEP_RECENT,
    )
    thread_cleanup_delete = (
        env.get("THREAD_CLEANUP_DELETE", THREAD_CLEANUP_DEFAULT_DELETE) == "1"
    )

    # directive-detect register watchdog (#1071) — env 해석.
    directive_detect_watch_enabled = (
        env.get(
            "DIRECTIVE_DETECT_WATCH_ENABLED",
            DIRECTIVE_DETECT_WATCH_DEFAULT_ENABLED,
        )
        == "1"
    )
    directive_detect_watch_interval = _resolve_int_env(
        "DIRECTIVE_DETECT_WATCH_INTERVAL",
        DIRECTIVE_DETECT_WATCH_DEFAULT_INTERVAL_SECONDS,
        allow_zero=False,
    )
    directive_detect_watch_window_minutes = _resolve_int_env(
        "DIRECTIVE_DETECT_WATCH_WINDOW_MINUTES",
        DIRECTIVE_DETECT_WATCH_DEFAULT_WINDOW_MINUTES,
        allow_zero=False,
    )
    directive_detect_watch_grace = _resolve_int_env(
        "DIRECTIVE_DETECT_WATCH_GRACE_COUNT",
        DIRECTIVE_DETECT_WATCH_DEFAULT_GRACE_COUNT,
        allow_zero=True,
    )

    # Loop heartbeat watchdog (#1087) — env 해석.
    heartbeat_watch_enabled = (
        env.get("HEARTBEAT_WATCH_ENABLED", HEARTBEAT_WATCH_DEFAULT_ENABLED) == "1"
    )
    heartbeat_watch_interval = _resolve_int_env(
        "HEARTBEAT_WATCH_INTERVAL_SECONDS",
        HEARTBEAT_WATCH_DEFAULT_INTERVAL_SECONDS,
        allow_zero=False,
    )
    heartbeat_stale_multiplier = _resolve_int_env(
        "HEARTBEAT_STALE_MULTIPLIER",
        HEARTBEAT_STALE_MULTIPLIER_DEFAULT,
        allow_zero=False,
    )

    context_auto_clear_enabled = env.get("CONTEXT_AUTO_CLEAR_ENABLED", "0") == "1"
    context_trigger_pct = resolve_context_pct_env(
        env.get("CONTEXT_CLEAR_TRIGGER_PCT"),
        CONTEXT_AUTO_CLEAR_DEFAULT_TRIGGER_PCT,
    )
    context_hysteresis_pct = resolve_context_pct_env(
        env.get("CONTEXT_CLEAR_HYSTERESIS_PCT"),
        CONTEXT_AUTO_CLEAR_DEFAULT_HYSTERESIS_PCT,
    )
    # multi-pane (#855). plural CSV 우선, singular 후방호환.
    context_pane_targets = resolve_pane_targets(env.get("TMUX_PANE_TARGETS"))

    @client.event
    async def on_ready() -> None:  # noqa: D401
        # 2026-05-29 — /mb slash command tree sync (global). Discord 가 등록된
        # command list 를 모든 guild 로 propagate. 첫 sync 후 cache 됨 — 명령 변경
        # 없으면 cost 0. graceful — sync 실패 시 warning 만 (다른 path 차단 X).
        try:
            synced = await tree.sync()
            logger.info("/mb slash commands sync OK: count=%d", len(synced))
        except Exception as exc:  # noqa: BLE001
            logger.warning("/mb slash commands sync 실패: %r", exc)

        logger.info(
            "Discord Gateway 연결 OK: user=%s channel=%s digest=%s alert=%s allowed=%d digest_enabled=%s auto_ack=%s auto_ack_emoji=%s secondary_reaction=%s secondary_emojis=(idle=%s partial=%s full=%s)",
            client.user,
            target_channel_id,
            digest_channel_id,
            alert_channel_id,
            len(allowed_user_ids),
            digest_enabled,
            bot_auto_ack_enabled,
            bot_auto_ack_emoji,
            bot_secondary_reaction_enabled,
            bot_secondary_reaction_emoji_idle,
            bot_secondary_reaction_emoji_partial,
            bot_secondary_reaction_emoji_full,
        )
        # P12 (2026-05-24) per-cycle 채널 가시화. 0 = unset (DIGEST fallback).
        logger.info(
            "cycle channels: be=%s fe=%s rev=%s plan=%s (0 = unset → DIGEST fallback)",
            cycle_channel_ids["be"] or "unset",
            cycle_channel_ids["fe"] or "unset",
            cycle_channel_ids["rev"] or "unset",
            cycle_channel_ids["plan"] or "unset",
        )
        # #17 (2026-05-24) forum 채널 가시화. 0 = unset.
        # directive-board / per-cycle 채널 = forum 강제 (텍스트 채널 deprecated).
        logger.info(
            "forum channels: directive=%s be=%s fe=%s rev=%s plan=%s (0 = unset)",
            forum_channel_ids["directive"] or "unset",
            forum_channel_ids["be"] or "unset",
            forum_channel_ids["fe"] or "unset",
            forum_channel_ids["rev"] or "unset",
            forum_channel_ids["plan"] or "unset",
        )
        # 2026-05-29 — forum 채널 5개 의 bot 권한 probe. 사용자 보고
        # "forum 채널 자체에 단 댓글 답 0" root cause 추적: on_message event 자체
        # 미수신 → bot 권한 부재 가설. View Channel + Read Message History 가
        # forum thread message_create event 수신의 필요조건.
        for forum_kind, forum_id in forum_channel_ids.items():
            if not forum_id:
                continue
            forum_channel = None
            forum_guild = None
            for guild in client.guilds:
                candidate = guild.get_channel(forum_id)
                if candidate is not None:
                    forum_channel = candidate
                    forum_guild = guild
                    break
            if forum_channel is None:
                logger.warning(
                    "forum permission probe: kind=%s id=%s NOT VISIBLE — bot 가 "
                    "채널 보지 못함 (Role 미부여 또는 권한 부재). 사용자 Discord "
                    "UI 에서 채널별 권한 부여 필요.",
                    forum_kind,
                    forum_id,
                )
                continue
            bot_member = forum_guild.me  # type: ignore[union-attr]
            perms = forum_channel.permissions_for(bot_member)
            logger.info(
                "forum permission probe: kind=%s id=%s name=%r view=%s "
                "read_history=%s send_in_threads=%s create_public_threads=%s",
                forum_kind,
                forum_id,
                forum_channel.name,
                perms.view_channel,
                perms.read_message_history,
                perms.send_messages_in_threads,
                perms.create_public_threads,
            )
        if digest_enabled and not hasattr(client, "_digest_task_started"):
            # on_ready 는 reconnect 시 재호출 — task 중복 시작 방지.
            client._digest_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                digest_loop(
                    client,
                    digest_channel_id,
                    interval=digest_interval,
                    cycle_status_path=cycle_status_path,
                    cycle_counter_path=cycle_counter_path,
                    directive_board_state_path=directive_board_state_path,
                )
            )
            logger.info(
                "digest_loop launched: channel=%d interval=%ds heartbeat=%ds status=%s counter=%s",
                digest_channel_id,
                digest_interval,
                DIGEST_HEARTBEAT_SECONDS,
                cycle_status_path,
                cycle_counter_path,
            )

        # context auto-clear loop (spec §5-2, #809 → #855 multi-pane → #910 G-4).
        # opt-in 시 **항상 launch**. pane 존재 체크는 loop 안의 매 iter 에서
        # graceful skip — NCP 재부팅 순서 (bot.py boot < maestro tmux 세션 생성)
        # 의존성으로 loop 가 영구 dead 되는 버그(#910 G-4) 차단.
        if context_auto_clear_enabled and not hasattr(
            client, "_context_auto_clear_task_started"
        ):
            available_panes = [
                p
                for p in context_pane_targets
                if tmux_has_session(p.split(":", 1)[0])
            ]
            missing_panes = [
                p for p in context_pane_targets if p not in available_panes
            ]
            if missing_panes:
                logger.warning(
                    "context auto-clear: 부재 pane(들) graceful skip (loop 안에서 매 iter 재확인): %s",
                    ", ".join(missing_panes),
                )
            client._context_auto_clear_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                context_auto_clear_loop(
                    client,
                    digest_channel_id,
                    pane_targets=context_pane_targets,
                    trigger_pct=context_trigger_pct,
                    hysteresis_pct=context_hysteresis_pct,
                )
            )
            logger.info(
                "context_auto_clear_loop launched: panes=%s trigger=%d%% hysteresis=%d%% available=%d/%d",
                ", ".join(context_pane_targets),
                context_trigger_pct,
                context_hysteresis_pct,
                len(available_panes),
                len(context_pane_targets),
            )
        elif not context_auto_clear_enabled:
            logger.info("context auto-clear disabled (CONTEXT_AUTO_CLEAR_ENABLED=0)")

        # nmae cycle watchdog (#941, spec: docs/features/nmae-cycle-watchdog.md).
        # 2026-05-29 폐기 — agent SDK design 에서 nmae 자동 위임 path 폐기됨
        # (STRICT 룰 — directive_approved event 통해서만 launch). idle alert /
        # escalation push 가 nmae 에 자동 trigger 보내는 path 가 새 design 위반.
        # cycle_idle_watch_loop disabled (env 토글 무관). 함수 자체는 dead code 로
        # 유지 — 후속 PR 에서 함수 정의 + escalation 헬퍼 정리.
        if False and cycle_idle_watch_enabled and not hasattr(
            client, "_cycle_idle_watch_task_started"
        ):
            client._cycle_idle_watch_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                cycle_idle_watch_loop(
                    client,
                    cycle_notify_channel_id,
                    cycle_status_path=cycle_status_path,
                    inject_target=cycle_inject_target,
                    threshold_minutes=cycle_idle_threshold_minutes,
                    poll_interval=cycle_idle_poll_interval,
                    workspaces=cycle_workspaces,
                    reason_required=cycle_reason_required,
                    escalation_threshold=cycle_escalation_threshold,
                    escalation_debounce_seconds=cycle_escalation_debounce,
                    escalation_channel_id=cycle_escalation_channel_id,
                    stale_active_enabled=cycle_stale_active_enabled,
                    stale_active_threshold_minutes=cycle_stale_active_threshold_minutes,
                )
            )
            logger.info(
                "cycle_idle_watch_loop launched: channel=%d interval=%ds threshold=%dmin target=%s workspaces=%s reason_required=%s escalation=(threshold=%d debounce=%ds channel=%s) stale_active=(enabled=%s threshold=%dmin)",
                cycle_notify_channel_id,
                cycle_idle_poll_interval,
                cycle_idle_threshold_minutes,
                cycle_inject_target,
                ",".join(cycle_workspaces),
                cycle_reason_required,
                cycle_escalation_threshold,
                cycle_escalation_debounce,
                cycle_escalation_channel_id,
                cycle_stale_active_enabled,
                cycle_stale_active_threshold_minutes,
            )
        elif not cycle_idle_watch_enabled:
            logger.info("cycle_idle_watch disabled (CYCLE_IDLE_WATCH=0)")

        # rev e2e 단계 2 (post-merge) 자동 trigger (#1008).
        # 5분 polling — develop 머지된 PR 단계 2 audit 자동 launch.
        # spec: docs/features/rev-e2e-3-stages.md §3-2.
        if rev_post_merge_audit_enabled and not hasattr(
            client, "_rev_post_merge_audit_task_started"
        ):
            client._rev_post_merge_audit_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                rev_post_merge_audit_loop(
                    client,
                    digest_channel_id,
                    inject_target=rev_post_merge_audit_inject_target,
                    poll_interval=rev_post_merge_audit_interval,
                )
            )
            logger.info(
                "rev_post_merge_audit_loop launched: channel=%d interval=%ds target=%s",
                digest_channel_id,
                rev_post_merge_audit_interval,
                rev_post_merge_audit_inject_target,
            )
        elif not rev_post_merge_audit_enabled:
            logger.info(
                "rev_post_merge_audit_loop disabled (REV_POST_MERGE_AUDIT_LOOP=0)"
            )

        # PR B (2026-05-28) — directive completed 자동 전이.
        # 5분 polling — develop 머지 PR body 의 `directive: <id>` 매칭 시 자동
        # `directive_status.sh completed <id> <pr_url>` 호출. 멱등 — 이미 completed
        # 면 sh 가 no-op. spec: directive-board-template-and-tags.md §5-6 완료 자동화.
        if not hasattr(client, "_directive_complete_on_merge_task_started"):
            client._directive_complete_on_merge_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(directive_complete_on_merge_loop())
            logger.info(
                "directive_complete_on_merge_loop launched: interval=%ds (5min polling)",
                DIRECTIVE_COMPLETE_POLL_INTERVAL_DEFAULT,
            )

        # 2026-05-29 폐기: directive_polish_loop launch. 새 design (Phase A-C) 가
        # 등록 직전 dialogue 안에서 polish 처리 — 사후 polling 폐기.

        # Phase 2.3 (2026-05-29) — agent outbox consumer.
        # tools/agent/ 가 events 테이블 에 'agent_reply' / 'agent_forum_action' INSERT.
        # bot.py 가 1초 polling → consume → Discord 실제 push.
        # spec: tools/agent/README.md (Phase 2.3).
        if not hasattr(client, "_agent_outbox_task_started"):
            client._agent_outbox_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(agent_outbox_loop(client))
            logger.info("agent_outbox_loop launched: interval=1s polling")

        # PR cf-3 (2026-05-29) — cycle forum thread ✅ 자동 retag.
        # 5분 polling — PR body 의 cycle-forum: <cycle>:<thread_id> 매칭 시 자동
        # discord-reply.sh --forum-retag <id> <cycle> "완료" 호출. spec: cycle-forum-operation.md §5-5.
        if not hasattr(client, "_cycle_thread_complete_on_merge_task_started"):
            client._cycle_thread_complete_on_merge_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(cycle_thread_complete_on_merge_loop())
            logger.info(
                "cycle_thread_complete_on_merge_loop launched: interval=%ds (5min polling)",
                DIRECTIVE_COMPLETE_POLL_INTERVAL_DEFAULT,
            )

        # Claude API usage tracker loop (#1020).
        # 10% bucket 도달 시 ALERT_CHANNEL_ID 로 push, atomic state write 로 dedup.
        if claude_usage_loop_enabled and not hasattr(
            client, "_claude_usage_task_started"
        ):
            client._claude_usage_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                claude_usage_watch_loop(
                    client,
                    alert_channel_id,
                    projects_root=claude_usage_projects_root,
                    state_path=claude_usage_state_path,
                    daily_limit=claude_daily_limit,
                    weekly_limit=claude_weekly_limit,
                    poll_interval=claude_usage_interval,
                )
            )
            logger.info(
                "claude_usage_watch_loop launched: alert_channel=%d interval=%ds "
                "daily_limit=%d weekly_limit=%d projects_root=%s state_path=%s",
                alert_channel_id,
                claude_usage_interval,
                claude_daily_limit,
                claude_weekly_limit,
                claude_usage_projects_root,
                claude_usage_state_path,
            )
        elif not claude_usage_loop_enabled:
            logger.info("claude_usage_watch_loop disabled (CLAUDE_USAGE_LOOP=0)")

        # directive-board polling sync_loop — PR #1140 event-driven 전환으로 폐기.
        # 등록 코드 자체 제거. trigger 3 시점 actor atomic 호출 (`directive_append.sh`
        # / `directive_status.sh`) + wrapper 누락 detect 가 대체.
        # spec: `docs/features/directive-board-event-driven-redesign.md`.

        # Discord thread auto-cleanup (#1023, 2026-05-24 사용자 P0 + #1062 사용자 P1).
        # per-launch thread 누적 → sidebar 가시성 ↓ 해소.
        # default age=15min interval=2min (#1062) keep=3 archive (delete 옵션 별도 toggle).
        if thread_cleanup_enabled and not hasattr(
            client, "_thread_cleanup_task_started"
        ):
            client._thread_cleanup_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                thread_cleanup_loop(
                    str(target_channel_id),
                    env["DISCORD_BOT_TOKEN"],
                    poll_interval=thread_cleanup_interval,
                    age_minutes=thread_cleanup_age_minutes,
                    keep_recent=thread_cleanup_keep_recent,
                    delete=thread_cleanup_delete,
                )
            )
            logger.info(
                "thread_cleanup_loop launched: channel=%s interval=%ds age=%.0fmin keep=%d delete=%s",
                target_channel_id,
                thread_cleanup_interval,
                thread_cleanup_age_minutes,
                thread_cleanup_keep_recent,
                thread_cleanup_delete,
            )
        elif not thread_cleanup_enabled:
            logger.info("thread_cleanup_loop disabled (THREAD_CLEANUP_ENABLED=0)")

        # 2026-05-29 폐기: directive-detect register watchdog loop launch.
        # 새 design 은 사용자 📌 만 directive 등록 trigger — detect (모든 메시지) vs
        # board (📌 적재만) 비교가 본질적으로 항상 mismatch. noise 알림 폐기.

        # Loop heartbeat watchdog (#1087, 2026-05-26 사용자 P0).
        # 다른 watchdog loop 들이 silent crash 시 가시화.
        if heartbeat_watch_enabled and not hasattr(
            client, "_heartbeat_watch_task_started"
        ):
            client._heartbeat_watch_task_started = True  # type: ignore[attr-defined]
            client.loop.create_task(
                heartbeat_watch_loop(
                    client,
                    digest_channel_id,
                    poll_interval=heartbeat_watch_interval,
                    multiplier=heartbeat_stale_multiplier,
                )
            )
            logger.info(
                "heartbeat_watch_loop launched: channel=%d interval=%ds "
                "multiplier=%d loops=%d",
                digest_channel_id,
                heartbeat_watch_interval,
                heartbeat_stale_multiplier,
                len(LOOP_HEARTBEAT_EXPECTED_INTERVALS),
            )
        elif not heartbeat_watch_enabled:
            logger.info(
                "heartbeat_watch_loop disabled (HEARTBEAT_WATCH_ENABLED=0)"
            )

        # 2026-05-29 폐기: Mode toggle buttons UI (ModeToggleView + ensure_mode_toggle_message).
        # 사용자 정정: "버튼으로 모드설정하는 건 삭제해 그럼" — `/mb auto` / `/mb ask` / `/mb status`
        # slash command 로 대체 (PR #1290). 핀 메시지 view 의 button 한계 우회.
        # 기존 채널 핀 메시지는 사용자 manual 삭제 또는 unpin 필요.

    @client.event
    async def on_message(message: discord.Message) -> None:
        if message.author.bot:
            return
        # 2026-05-29 (B+E2 옵션) — main 채널 thread + 4 cycle forum + directive forum 의
        # thread 안 사용자 메시지 모두 agent 처리. E2: forum_kind / directive_id 매핑으로
        # context 명시 → agent 가 어느 cycle / directive 의 thread 안 코멘트인지 인식.
        is_thread_of_target = False
        forum_kind: str | None = None  # be/fe/rev/plan/directive/main
        if message.channel.id != target_channel_id:
            parent = getattr(message.channel, "parent", None)
            parent_id = getattr(parent, "id", None)
            if parent_id == target_channel_id:
                is_thread_of_target = True
                forum_kind = "main"
            else:
                # forum thread 분기 — parent.id 가 4 cycle / directive forum 매칭.
                for kind, fid in forum_channel_ids.items():
                    if fid and parent_id == fid:
                        forum_kind = kind
                        is_thread_of_target = True
                        break
                if forum_kind is None:
                    return
        if message.author.id not in allowed_user_ids:
            logger.info("허용되지 않은 사용자 무시: user_id=%s", message.author.id)
            return

        message_id = str(message.id)
        # #909 F-3: dedup race fix.
        # 기존 흐름은 `is_processed` → tmux send → `mark_processed` 순서라
        # Discord Gateway reconnect (`on_message` 재호출) 시 mark 이전에 두 번째
        # 진입이 가능했다. `claim` 으로 가드+마크를 단일 SQLite 트랜잭션으로
        # 원자화하고, 이후 단계 (tmux send 등) 실패는 warning 만 남긴다.
        # mark 는 유지 — 재처리 위험이 tmux 재전송 누락보다 비용이 큼.
        if ledger is not None and not ledger.claim(message_id):
            logger.info("dedup hit: message_id=%s", message_id)
            return

        # reply.referenced_message — 사용자가 Discord "답장" 으로 보낸 경우,
        # 어떤 메시지에 대한 답장인지 prefix 로 helper 에게 전달 (#880).
        # discord.py 가 message.reference 와 message.referenced_message (hydrated)
        # 를 제공. referenced_message 가 None / 부분 정보 (delete 등) 면 ignore.
        referenced_content: str | None = None
        referenced_message = getattr(message, "referenced_message", None)
        if referenced_message is not None:
            ref_raw = getattr(referenced_message, "content", None)
            if isinstance(ref_raw, str) and ref_raw.strip():
                referenced_content = ref_raw

        original_body = message.content or ""
        forwarded_text = build_reply_context_prefix(
            referenced_content, original_body
        )

        ts_iso = message.created_at.astimezone(timezone.utc).isoformat()
        payload = {
            "text": forwarded_text,
            "author": str(message.author.id),
            "author_name": message.author.name,
            "ts": ts_iso,
            "message_id": message_id,
            "channel_id": str(message.channel.id),
        }

        logger.info(
            "메시지 수신: author=%s ts=%s preview=%r reply=%s",
            payload["author"],
            payload["ts"],
            truncate_for_log(payload["text"]),
            referenced_content is not None,
        )
        append_inbox(payload)
        # #946: helper 본답 자동 reply 용 message_id 캐시.
        # discord-reply.sh bare body 모드가 이 파일을 읽어
        # `message_reference` 를 payload 에 포함시켜 자동 reply 형태로 push.
        write_last_user_msg_id(message_id)

        # 2026-05-29 B안 가시화 — 사용자 메시지마다 thread 자동 생성.
        # agent 의 도구 호출 / 답 모두 그 thread 안 stream. 채널 noise 0.
        # graceful: thread 생성 실패 시 thread_id="" — agent 가 채널 push fallback.
        # B 옵션 (2026-05-29) — thread 안 사용자 메시지면 새 thread 생성 X, 기존
        # thread 안에서 계속 처리. agent 는 같은 thread 안 stream 유지 (대화 흐름).
        thread_id_str = ""
        channel_id_str = str(message.channel.id)
        directive_id_str: str | None = None
        if is_thread_of_target:
            # 사용자가 기존 thread 안 메시지 (B+E2 옵션) — 그 thread 안 처리.
            thread_id_str = channel_id_str
            # forum thread 면 channel_id = forum, main thread 면 channel_id = main.
            parent_id = getattr(getattr(message.channel, "parent", None), "id", None)
            channel_id_str = str(parent_id or target_channel_id)
            # E2 — forum thread → directive_id 매핑 (directive-board.jsonl scan).
            if forum_kind and forum_kind != "main":
                directive_id_str = _lookup_directive_by_thread_id(thread_id_str)
            logger.info(
                "user_message in thread: forum_kind=%s thread=%s directive=%s body=%r",
                forum_kind, thread_id_str, directive_id_str, original_body[:60],
            )
        else:
            try:
                thread_name = (original_body[:50] or "대화") + " 진행"
                agent_thread = await message.create_thread(name=thread_name[:99])
                thread_id_str = str(agent_thread.id)
                logger.info(
                    "user_message thread 생성: id=%s name=%r",
                    thread_id_str, thread_name[:30],
                )
            except Exception as exc:  # noqa: BLE001
                logger.warning("user_message thread 생성 실패 — fallback 채널: %r", exc)

        append_agent_event("user_message", {
            "message_id": message_id,
            "channel_id": channel_id_str,
            "thread_id": thread_id_str,  # B안 — agent 가 답/진행 thread 안 push
            "user_id": str(message.author.id),
            "user_name": message.author.name,
            "body": original_body,
            "referenced_content": referenced_content,
            "ts_iso": ts_iso,
            # E2 — forum context. agent 가 어디서 / 어떤 directive 의 코멘트인지 인식.
            "forum_kind": forum_kind or "main",
            "directive_id": directive_id_str or "",
        })

        # #1071 / PR #1140: directive classify + jsonl 로그 (자동 등록 path 폐지).
        #
        # 2026-05-28 정정 (spec: docs/features/directive-pushpin-registration.md):
        # classify 결과의 `directive_append.sh` **자동 호출 path 폐지**.
        # 사유: 한국어 regex 한계로 false-positive 다발 ("잔존 작업들 어떻게 정리할래?"
        # → directive-mixed → 자동 등록 같은 의도 misalignment). 등록은 사용자가
        # 📌 reaction tap 으로만 명시 trigger — `on_raw_reaction_add` 의 📌 분기 참조.
        # classify 자체는 유지 — directive-detect.jsonl 로그가 회고 / 통계 / 미래 LLM
        # 추천에 활용 가능. 자동 등록만 제거.
        try:
            detect_entry = make_detect_entry(
                message_id=message_id,
                ts_iso=payload["ts"],
                text=original_body,
                channel_id=payload["channel_id"],
            )
            append_detect_entry(DIRECTIVE_DETECT_PATH_DEFAULT, detect_entry)
            if detect_entry["class"] in DIRECTIVE_CLASSES:
                logger.info(
                    "directive-detect classify (log only — auto-register 폐지 2026-05-28): "
                    "id=%s class=%s summary=%r",
                    message_id,
                    detect_entry["class"],
                    detect_entry["summary"],
                )
        except OSError as exc:
            logger.warning(
                "directive-detect append 실패: id=%s exc=%r", message_id, exc
            )

        # bot.py 1초 generic auto-ack (#880, reaction-only #1175) — helper 자체
        # ack 까지 bash chain latency 5+초 깜깜이 해소.
        # 2026-05-28: 사용자 메시지에 👀 emoji reaction 만 add — 별도 채팅 메시지 0,
        # 본답만 1건 노출 (채널 가독성 ↑). 기존 text/both mode 폐기.
        # 실패 path 는 exc_info=True 로 traceback 보존 (#1026).
        if bot_auto_ack_enabled:
            try:
                await message.add_reaction(bot_auto_ack_emoji)
                logger.info(
                    "bot auto-ack reaction OK: message_id=%s emoji=%s",
                    message_id,
                    bot_auto_ack_emoji,
                )
            except Exception as exc:  # noqa: BLE001
                logger.warning(
                    "bot auto-ack reaction 실패: message_id=%s emoji=%s exc=%r",
                    message_id,
                    bot_auto_ack_emoji,
                    exc,
                    exc_info=True,
                )

        # 📌 directive 등록 후보 marker (spec: directive-pushpin-registration.md).
        # 매 사용자 메시지에 📌 자동 부착 (passive). 사용자가 추적 원하는 메시지에서
        # 📌 tap 시 `on_raw_reaction_add` 의 📌 분기가 directive_append.sh 호출.
        # emoji picker 부담 0 + retro-register 가능 (시간 지난 메시지도 박을 수 있음).
        try:
            await message.add_reaction(PIN_REACTION_EMOJI)
            logger.info(
                "📌 pin marker OK: message_id=%s emoji=%s",
                message_id,
                PIN_REACTION_EMOJI,
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "📌 pin marker 부착 실패: message_id=%s exc=%r",
                message_id,
                exc,
                exc_info=True,
            )

        # helper tmux 세션 routing — 단순화본은 routing 만 수행. 응답은 helper 측
        # `~/.mobruji/discord-reply.sh "<msg>"` 가 직접 bot REST API 로 push.
        # #909 F-3: claim 으로 이미 마킹됐기 때문에 여기서 실패해도 unclaim 하지
        # 않는다 (재처리 위험 회피). 실패는 warning + 운영자가 로그로 인지.
        if not ensure_tmux_session(session_name, claude_bin):
            logger.warning(
                "tmux 세션 확보 실패 — 메시지 dropped (claim 유지): id=%s",
                message_id,
            )
            return
        if not tmux_send_payload(target_pane, payload["text"]):
            logger.warning(
                "tmux send-keys 실패 — 메시지 dropped (claim 유지): id=%s",
                message_id,
            )
            return

    @client.event
    async def on_raw_reaction_add(
        raw_payload: discord.RawReactionActionEvent,
    ) -> None:
        # spec: docs/features/discord-reaction-choice-input.md
        # keycap reaction (1️⃣–🔟) 으로 사용자 선택지 응답 처리.
        # 다른 채널 / 다른 사용자 / 다른 emoji / 미등록 message → early return.
        #
        # 2026-05-29 fix — B안 가시화 (PR #1307) 후 사용자 메시지마다 thread 자동
        # 생성. 사용자가 thread 안에서 📌 reaction 누르면 raw_payload.channel_id =
        # thread id ≠ target_channel_id → 옛 코드 가 즉시 return → 사고.
        # thread parent 가 target_channel_id 면 통과.
        # 2026-05-29 추가 fix — main 채널 thread + 4 cycle forum + directive forum 의
        # thread 안 reaction 도 처리. 사용자 정정: "directive forum 승인하는 OX 질문도
        # 누락" → forum thread 안 📌 / 키캡 reaction 처리 필요.
        if raw_payload.channel_id != target_channel_id:
            ch = client.get_channel(raw_payload.channel_id)
            parent_id = getattr(getattr(ch, "parent", None), "id", None)
            allowed_parents = {target_channel_id}
            for _fid in forum_channel_ids.values():
                if _fid:
                    allowed_parents.add(_fid)
            if parent_id not in allowed_parents:
                return
        if raw_payload.user_id not in allowed_user_ids:
            return
        # bot self reaction skip (pre-attach 1️⃣–🔟 시 자기 자신 trigger 방지).
        bot_user = getattr(client, "user", None)
        if bot_user is not None and raw_payload.user_id == bot_user.id:
            return

        emoji_str = str(raw_payload.emoji)

        # ⏹ helper 작업 중단 (2026-05-29) — helper claude tmux pane 에 Ctrl-C send.
        # 사용자가 helper 답 작성 메시지 / 작업 중간 메시지에 ⏹ tap → 즉시 SIGINT.
        # ack 는 그 메시지의 thread 에 정리 (사용자 추적 용이).
        if emoji_str == CONTROL_STOP_EMOJI:
            stop_dedup_key = f"stop:{raw_payload.message_id}"
            if ledger is not None and not ledger.claim(stop_dedup_key):
                return
            helper_pane = os.environ.get("MOBRUJI_HELPER_PANE", "helper:0.0")
            try:
                subprocess.run(  # noqa: S603 — tmux fixed
                    ["tmux", "send-keys", "-t", helper_pane, "C-c"],
                    check=False, timeout=5.0, capture_output=True,
                )
                logger.info("⏹ helper stop: pane=%s user=%s msg=%s",
                            helper_pane, raw_payload.user_id, raw_payload.message_id)
            except (OSError, subprocess.TimeoutExpired) as exc:
                logger.warning("⏹ helper stop tmux 실패: %r", exc)
            await _post_control_ack(
                client=client,
                channel_id=raw_payload.channel_id,
                message_id=str(raw_payload.message_id),
                thread_name="⏹ 중단",
                ack_text="⏹ 중단 신호 처리됨 — helper claude 인스턴스 Ctrl-C send 완료.",
            )
            return

        # ❓ "이건 왜?" (2026-05-29) — 다음 turn 에 helper 가 직전 작업 사유 설명.
        # 사용자 메시지로 synthetic inbox entry 추가 + tmux send → helper 가 처리.
        # ack + helper 응답은 그 메시지의 thread 에 정리 (사용자 추적 용이).
        # thread_id 를 inject text 에 marker `[reply_thread=ID]` 로 전달 — helper 가 다음
        # turn 에서 discord-reply.sh --thread-id <ID> 로 그 thread 안에 응답.
        if emoji_str == CONTROL_WHY_EMOJI:
            why_dedup_key = f"why:{raw_payload.message_id}"
            if ledger is not None and not ledger.claim(why_dedup_key):
                return
            thread_id = await _post_control_ack(
                client=client,
                channel_id=raw_payload.channel_id,
                message_id=str(raw_payload.message_id),
                thread_name="❓ 사유 설명",
                ack_text="❓ 사유 설명 요청 — helper 가 다음 turn 에 이 스레드 안에 답합니다.",
            )
            thread_marker = (
                f"[reply_thread={thread_id}] " if thread_id else ""
            )
            synthetic_text = (
                f"{thread_marker}[❓ 사용자 질문] 직전 message_id={raw_payload.message_id} 의 "
                "작업 / 결정 사유를 1-3 줄로 짧게 설명해 주세요. "
                "(helper 본체 = relay only, 단순 reasoning recap 만)"
            )
            ts_iso = datetime.now(timezone.utc).isoformat()
            payload: dict[str, str] = {
                "text": synthetic_text,
                "author": str(raw_payload.user_id),
                "author_name": "<control-why>",
                "ts": ts_iso,
                "message_id": why_dedup_key,
                "channel_id": str(raw_payload.channel_id),
            }
            logger.info("❓ helper why: msg=%s user=%s thread=%s",
                        raw_payload.message_id, raw_payload.user_id, thread_id)
            append_inbox(payload)
            # 2026-05-29 (PR helper-control-emoji-reply-fix) — write_last_user_msg_id
            # 호출 제거. ❓ tap message_id 는 helper 자기 답 메시지 (bot self) 라,
            # last-user-msg-id.txt 에 write 하면 다음 helper turn 의 reply_to 가
            # 그 bot self 답을 가리켜 "엉뚱한 메시지에 reply" 사고. ❓ 응답은
            # `[reply_thread=ID]` marker + helper-role.md 룰로 thread 안 push.
            if ensure_tmux_session(session_name, claude_bin):
                tmux_send_payload(target_pane, synthetic_text)
            return

        # 📌 directive 등록 분기 (spec: directive-pushpin-registration.md).
        # 사용자가 자기 / helper 메시지에 📌 tap → directive_append.sh 호출 → forum 등록 → ✅ 부착.
        # bot 이 미리 부착해 둔 📌 위에 사용자가 self-react = 자연 신호.
        if emoji_str == PIN_REACTION_EMOJI:
            target_msg_id = str(raw_payload.message_id)
            pin_dedup_key = f"pin:{target_msg_id}"
            if ledger is not None and not ledger.claim(pin_dedup_key):
                logger.info("📌 pin dedup hit: msg_id=%s", target_msg_id)
                return
            # 2026-05-29 사용자 정정 — cycle forum 매칭 검색 추가.
            # forum_ids = 4 cycle (be/fe/rev/plan) forum ID list (.env 에서 load).
            cycle_forum_id_list = [
                forum_channel_ids.get("be", 0),
                forum_channel_ids.get("fe", 0),
                forum_channel_ids.get("rev", 0),
                forum_channel_ids.get("plan", 0),
            ]
            await _handle_pin_reaction(
                client=client,
                channel_id=raw_payload.channel_id,
                message_id=target_msg_id,
                user_id=raw_payload.user_id,
                forum_ids=cycle_forum_id_list,
                guild_id=raw_payload.guild_id or 0,
            )
            return

        # 선택지 응답 분기 — keycap (1️⃣–🔟) 또는 register dialogue 3 button
        # (⭕ 등록 / ✏️ 수정 / 🗑️ 제거). 둘 중 매칭 못 하면 ignore.
        choice_idx = parse_choice_emoji(emoji_str)
        if choice_idx is None:
            choice_idx = REGISTER_DIALOGUE_EMOJI_TO_IDX.get(emoji_str)
        if choice_idx is None:
            return

        bot_msg_id = str(raw_payload.message_id)
        register = lookup_choice_prompt(bot_msg_id)
        # 2026-05-30 — agent SDK choice_prompt fallback. legacy ledger 미등록 +
        # agent path (events.choice_prompt) 만 등록된 케이스 (사용자 보고
        # "O 눌렀는데 무반응") 해소. 어느 한 path 라도 등록돼 있으면 처리.
        agent_choice_value: str | None = None
        try:
            agent_choice_value = _lookup_agent_choice(bot_msg_id, choice_idx)
        except Exception as exc:  # noqa: BLE001
            logger.warning("agent choice lookup 실패: %r", exc)

        if register is None and agent_choice_value is None:
            return  # 미등록 message — silent skip

        if register is not None:
            choices = register.get("choices") or []
            if not isinstance(choices, list) or choice_idx >= len(choices):
                return
            label = str(choices[choice_idx])
        else:
            # agent path 만 — label 은 agent value 그대로.
            label = agent_choice_value or ""

        # dedup — Gateway reconnect / 사용자 toggle reaction race 가드.
        dedup_key = f"choice:{bot_msg_id}:{choice_idx}"
        if ledger is not None and not ledger.claim(dedup_key):
            return

        if register is not None:
            mark_choice_consumed(
                message_id=bot_msg_id,
                choice_idx=choice_idx,
                user_id=str(raw_payload.user_id),
            )

        synthetic_text = f"[choice {choice_idx + 1}] {label}"
        ts_iso = datetime.now(timezone.utc).isoformat()

        logger.info(
            "choice reaction received: bot_msg=%s idx=%d label=%r user=%s "
            "register=%s agent=%s",
            bot_msg_id,
            choice_idx,
            label,
            raw_payload.user_id,
            register is not None,
            agent_choice_value is not None,
        )

        # helper 답 push 시 reply target = choice prompt message (시각적 연결).
        write_last_user_msg_id(bot_msg_id)

        # legacy path — register OK 시만 helper tmux send. agent SDK only path
        # (register None) 는 tmux send skip 후 agent INSERT 만.
        if register is not None:
            legacy_payload: dict[str, str] = {
                "text": synthetic_text,
                "author": str(raw_payload.user_id),
                "author_name": "<reaction-choice>",
                "ts": ts_iso,
                "message_id": dedup_key,
                "channel_id": str(raw_payload.channel_id),
            }
            append_inbox(legacy_payload)
            if not ensure_tmux_session(session_name, claude_bin):
                logger.warning(
                    "choice reaction tmux 세션 확보 실패 — legacy path skip: "
                    "bot_msg=%s",
                    bot_msg_id,
                )
            elif not tmux_send_payload(target_pane, synthetic_text):
                logger.warning(
                    "choice reaction tmux send 실패 — legacy path skip: "
                    "bot_msg=%s",
                    bot_msg_id,
                )

        if agent_choice_value is not None:
            # 사용자 thread (사용자 reaction 채널) — agent_reply 가 push 한 thread
            user_thread_id = str(raw_payload.channel_id)
            append_agent_event("user_message", {
                "message_id": f"choice:{bot_msg_id}:{choice_idx}",
                "channel_id": str(target_channel_id),
                "thread_id": user_thread_id if user_thread_id != str(target_channel_id) else "",
                "user_id": str(raw_payload.user_id),
                "user_name": "<reaction-choice>",
                "body": agent_choice_value,
                "ts_iso": datetime.now(timezone.utc).isoformat(),
            })
            logger.info(
                "agent choice → user_message INSERT: bot_msg=%s idx=%d value=%r",
                bot_msg_id, choice_idx, agent_choice_value[:40],
            )

    return client


def _lookup_agent_choice(message_id: str, choice_idx: int) -> str | None:
    """events.choice_prompt 에서 message_id 매칭 + idx 의 선택 value 반환."""
    try:
        conn = sqlite3.connect(str(AGENT_EVENTS_DB_PATH), isolation_level=None, timeout=5.0)
        try:
            conn.row_factory = sqlite3.Row
            row = conn.execute(
                "SELECT payload FROM events WHERE kind = ? AND "
                "json_extract(payload, '$.message_id') = ? ORDER BY id DESC LIMIT 1",
                ("choice_prompt", message_id),
            ).fetchone()
        finally:
            conn.close()
    except (sqlite3.Error, OSError) as exc:
        logger.warning("_lookup_agent_choice 실패: %r", exc)
        return None
    if not row:
        return None
    try:
        p = json.loads(row["payload"])
        choices = p.get("choices", [])
        if 0 <= choice_idx < len(choices):
            return str(choices[choice_idx])
    except (json.JSONDecodeError, KeyError):
        pass
    return None


def main() -> None:
    # bridge 배포 dir 격리 검증 (PR #1126) — load_env 이전에 호출하여
    # 잘못된 dir 실행 시 가능한 한 일찍 fail-fast 합니다.
    verify_deploy_dir()
    env = load_env()
    ledger = DedupLedger(env["DEDUP_LEDGER_PATH"])
    start_dedup_gc_thread(ledger)
    ensure_tmux_session(env["TMUX_SESSION_NAME"], env["CLAUDE_BIN"])

    client = build_client(env, ledger)
    logger.info(
        "Discord daemon 시작 (received_at=%s)",
        datetime.now(timezone.utc).isoformat(),
    )
    client.run(env["DISCORD_BOT_TOKEN"], log_handler=None)


if __name__ == "__main__":
    main()
