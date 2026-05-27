---
feature: directive board event-driven redesign
slug: directive-board-event-driven-redesign
status: approved
owner: plan
scope: infra
related_issues: [1129]
related_prs: [1140]
last_reviewed: 2026-05-27
---

# directive board event-driven 재설계

> 2026-05-26 사용자 16:21-24 directive 박제. directive board 의 jsonl ↔ Discord
> 본문/태그 sync 를 polling 기반 (`bot.py directive_board_sync_loop` / 5분 주기)
> 에서 **트리거 3 시점 event-driven actor atomic 호출** 로 전환. bot.py 는 dumb
> conduit 으로 단순화 — desync 0 운영.
>
> **PR 분담**: 본 SPEC PR (plan) + impl PR ×3 (be / helper-launched).
>
> **사용자 16:21-24 directive — 사용자 결정 대기 없이 PR 머지까지 자율 완료 목표**.

## §1 현상

현행 directive board sync 구조:

| 채널 | 역할 | 문제 |
|---|---|---|
| `~/.mobruji/directive-board.jsonl` | jsonl SoT (status / pr / assignee 등) | 정상 |
| `#모부르지-지시` Discord forum | 사용자 view | 본문/태그 desync 사고 다수 |
| `bot.py directive_board_sync_loop` | 5분 polling 으로 jsonl ↔ Discord 비교 + 자동 PATCH | desync 윈도우 5분 + mismatch 자동 PATCH 불완전 (사용자 2026-05-26 정정 "directive_board_mismatch=106 — 자동 PATCH 가 안 됨") |
| `~/.mobruji/directive-board-sync.json` | sync state 캐시 | drift 누적 |

**사고 패턴**:
- 작업자 (helper / nmae / sub-agent) 가 PR 머지 직후 jsonl entry status 갱신은 했지만 `discord-reply.sh --update-status` 호출 누락 → Discord 본문 stale → 사용자 가시화 X.
- `directive_board_sync_loop` 가 5분 후 detect 했지만 자동 PATCH 가 thread 태그 / message body / forum tag 일부 시점에서 실패. mismatch 카운트 누적 (사용자 2026-05-26 정정: 106 건 잔존).
- 사용자 시점 latency: directive 발생 → forum 가시화 5분, 상태 변경 → 본문 갱신 5분 (mismatch 잔존 시 무한).

사용자 정정 (2026-05-26 16:21-24): event-driven 재설계 — polling sync_loop 폐기.

## §2 목표

- bot.py = **dumb conduit** (Discord Gateway / on_message / auto-ack / forum thread emit 만).
- directive board sync = 트리거 3 시점 actor atomic 호출.
- desync 0 (mismatch 카운트 항상 0).
- directive 발생 → forum 가시화 latency < 2s (event-driven).
- 누락 detect = 다음 wrapper 실행 시점 visible warning.

비목표:
- directive jsonl 스키마 변경 (`directive-board.jsonl` 구조 유지).
- Discord forum 채널 / 태그 체계 변경 (#모부르지-지시 그대로).

## §3 변경 방향 — 트리거 3 시점 actor atomic 호출

### (a) 지시 발생 — directive append

| 시점 | 호출 | actor |
|---|---|---|
| bot.py `on_message` 가 directive 분류 시 (사용자 메시지가 코드 변경 / PR / sub-agent 작업 지시로 판정) | `bash ~/.mobruji/directive_append.sh <msg_id> "<title>" [pr_url]` | bot.py 자체 호출 (Discord Gateway thread 안) |
| helper 본체가 사용자 지시 채택 시 (메시지 분류 결과 = 지시) | 동일 (멱등 호출) | helper 본체 |
| nmae 가 사용자 inject / digest 읽고 신규 백로그 채택 시 | 동일 | nmae |

`directive_append.sh` 동작:
1. `directive-board.jsonl` 에 새 entry append (status=`대기 중`, ts, msg_id, title, pr=null).
2. Discord forum 채널 `#모부르지-지시` 에 새 thread 생성 (`discord-reply.sh --forum-post directive "<title>" "<body>"`).
3. thread_id 를 jsonl entry 의 `discord_thread_id` 필드에 atomic write.

멱등성: 같은 `msg_id` 로 이미 append 됐으면 no-op (race 가드).

### (b) 위임 — directive in_progress

| 시점 | 호출 | actor |
|---|---|---|
| nmae 또는 helper 가 sub-agent launch 시점 | `bash ~/.mobruji/directive_status.sh <msg_id_or_thread_id> in_progress [pr_url]` | nmae / helper / `agent-launch-wrapper.sh` 강제 |

`directive_status.sh` 동작:
1. jsonl entry status → `진행 중`. `assignee` (sub-agent role / actor name) + `last_updated_ts` 갱신.
2. Discord forum thread 태그 `진행 중` 으로 retag (`discord-reply.sh --forum-retag <thread_id> directive "진행 중"`).
3. message body 갱신 (`discord-reply.sh --update-status <thread_id> "진행 중" [pr_url]`).

`agent-launch-wrapper.sh` 가 sub-agent launch 직전 본 호출을 강제 — 작업자가 명시 호출 안 해도 자동 실행. 학습 의존 ↓.

### (c) 완료 — directive completed

| 시점 | 호출 | actor |
|---|---|---|
| sub-agent 완료 보고 시점 | `bash ~/.mobruji/directive_status.sh <msg_id_or_thread_id> completed [pr_url]` | sub-agent (`12-sub-agent-prompt-template.md` 강제) 또는 nmae ack 시점 |
| PR 머지 직후 | 동일 (멱등 호출) | nmae (auto-merge 시점) |

`directive_status.sh completed` 동작:
1. jsonl entry status → `완료`. `completed_ts` + `pr_merged` 갱신.
2. Discord forum thread 태그 `완료` retag.
3. message body 갱신 (PR URL 포함).

**기타 상태 전이** (`차단` / `취소` / `대기`) 도 동일 헬퍼 사용 — `directive_status.sh <id> <status> [pr_url]`.

## §4 누락 검출 — wrapper 안 jsonl ↔ Discord diff

polling sync_loop 폐기 후에도 actor 호출 누락 사고는 발생 가능 (helper 본체가 step 5 처리 중 crash, sub-agent reasoning interrupt 등).

### 검출 방법

`helper-turn-start.sh` (helper) + `agent-launch-wrapper.sh` (sub-agent launch) 두 wrapper 가 turn / launch 시작 시점에 다음 수행:

1. `directive-board.jsonl` 최근 N=20 entry 의 status 와 Discord forum thread 태그 비교 (`discord-reply.sh --forum-state-dump directive` API).
2. mismatch 발견 시 stdout 에 visible warning 출력:
   ```
   [!] directive append/status missed: thread_id=<id> jsonl_status=<s1> forum_tag=<s2>
   ```
3. actor (helper / nmae / sub-agent) 가 warning 보고 즉시 정정 호출 (`directive_status.sh`).

### 비목표

- 자동 PATCH 는 하지 않음 (polling sync_loop 의 사고 원인). actor 책임 강제.
- 1회성 sweep (잔존 mismatch 정리) 는 `docs/features/directive-jsonl-mismatch-sweep.md` PR 에서 별도 처리. 본 SPEC 의 누락 detect 는 평상시 sync.

## §5 폐기 대상

| 항목 | 폐기 사유 |
|---|---|
| `bot.py directive_board_sync_loop` 함수 | polling sync 자체가 desync 원인 (자동 PATCH 불완전 + 5분 latency) |
| `bot.py directive_status_sync_loop` 함수 (be 사이클 진행 중, PR #1041 후속) | 동일 |
| `~/.mobruji/directive-board-sync.json` 캐시 파일 | sync_loop 폐기와 동시에 제거. drift 누적 source |
| cron digest `directive_board_mismatch=N` 한 줄 | wrapper visible warning 으로 대체. mismatch 누적 자체가 0 이 목표 |

폐기 후 bot.py 안 directive 관련 책임 = `on_message` 안 directive 분류 + `directive_append.sh` 호출만.

## §6 영향 파일

| 파일 | 변경 | 담당 PR |
|---|---|---|
| `bot.py` | `directive_board_sync_loop` / `directive_status_sync_loop` 함수 제거 + `on_message` 안 `directive_append.sh` 호출 추가 | impl PR 1 (be) |
| `~/.mobruji/directive_append.sh` | 신설 — jsonl append + forum-post atomic | impl PR 2 (helper-launched) |
| `~/.mobruji/directive_status.sh` | 신설 — jsonl update + forum-retag + update-status atomic | impl PR 2 (helper-launched) |
| `tools/agent-launch-wrapper.sh` | sub-agent launch 직전 `directive_status.sh in_progress` 자동 호출 + mismatch detect | impl PR 3 (be) |
| `tools/discord-daemon/helper-turn-start.sh` | turn 시작 시점 mismatch detect | impl PR 3 (be) |
| `~/.mobruji/discord-reply.sh` | `--forum-state-dump directive` mode 신설 (state 비교 API) | impl PR 2 (helper-launched) |
| `~/.mobruji/directive-board-sync.json` | 폐기 | impl PR 1 (be) |
| `docs/helper-rules.md` §4 | 트리거 3 시점 actor atomic 호출 명시 + polling 폐기 표기 | 본 SPEC PR |
| `CLAUDE.md` §11-11 | 트리거 3 시점 표 + polling 폐기 + 누락 detect | 본 SPEC PR |
| `CLAUDE.md` §12-3 step 5 (helper sub-agent launch) | `directive_status.sh in_progress` 호출 명시 (wrapper 가 자동이지만 폴백 명시) | 본 SPEC PR |
| `docs/ai-harness/12-sub-agent-prompt-template.md` | sub-agent 완료 보고 시점 `directive_status.sh completed` 호출 의무 | 본 SPEC PR |
| 메모리 `nmae/feedback_directive_board_update_flow.md` | polling sync → event-driven 으로 정정 | nmae 갱신 권고 (본 PR 본문 명시) |

## §7 PR 분담

| PR | 작업 | 담당 |
|---|---|---|
| 본 SPEC PR | docs/features 신설 + CLAUDE.md §11-11 + §12-3 + docs/helper-rules.md + 12-sub-agent-prompt-template.md 갱신 + 메모리 정정 권고 | plan (본 사이클) |
| impl PR 1 — bot.py sync_loop 제거 | `bot.py directive_board_sync_loop` / `directive_status_sync_loop` 함수 제거 + `on_message` 안 `directive_append.sh` 호출 추가 + `directive-board-sync.json` 폐기 | be (후속 사이클) |
| impl PR 2 — helper script 신설 | `directive_append.sh` / `directive_status.sh` 신설 + `discord-reply.sh --forum-state-dump directive` mode 신설 + pytest 5건 | helper-launched 또는 be (후속) |
| impl PR 3 — wrapper 누락 detect | `agent-launch-wrapper.sh` + `helper-turn-start.sh` 안 jsonl ↔ Discord diff + visible warning | be (후속) |

PR 진행 순서: 1 (필수 — 폐기 먼저) → 2 (신설) → 3 (보조 detect). 1+2 병행 가능 (다른 파일). 3 은 1+2 머지 후.

## §8 검증

| 단계 | 방법 |
|---|---|
| event-driven append latency 검증 | 신규 directive 발생 시점 ↔ Discord forum thread 생성 시점 wall-clock diff < 2s. `sudo journalctl -u mobruji-bot -n 50` |
| 상태 전이 latency 검증 | sub-agent launch ↔ Discord thread 태그 변경 wall-clock diff < 2s. `journalctl` + Discord forum 가시 |
| polling sync_loop 폐기 검증 | `bot.py` source 안 `directive_board_sync_loop` / `directive_status_sync_loop` 함수 부재 확인 (`grep -n "_sync_loop" tools/discord-daemon/bot.py` 0건) |
| mismatch 누적 검증 | impl PR 머지 후 24시간 운영 시 cron digest `directive_board_mismatch=0` 유지 (또는 wrapper visible warning 0건) |
| 누락 정정 동작 검증 | actor 호출 누락 시뮬레이션 (helper 본체 crash) → 다음 wrapper 실행 시점 visible warning + actor 정정 호출 → 정정 완료 확인 |

## §9 의존

없음. independent. 단 1회성 sweep (`docs/features/directive-jsonl-mismatch-sweep.md`) 가 잔존 106 건 mismatch 정리하면 본 PR 의 event-driven sync 가 0 부터 깨끗하게 시작 가능.

## §10 미해결 질문

없음. 사용자 결정 완료 (2026-05-26 16:21-24).

## §11 관련

- PR #1041 — directive board / update-status mode 도입
- PR #1122 — `directive_board_sync` forum thread route 자동 라우팅
- `docs/features/directive-jsonl-mismatch-sweep.md` — 1회성 sweep (별도 PR)
- CLAUDE.md §11-11 directive-board update flow
- 메모리 `nmae/feedback_directive_board_update_flow.md`
- 메모리 `nmae/feedback_forum_channel_enforce.md`
