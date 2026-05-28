---
feature: discord-reply.sh --cycle-channel forum adapter
slug: discord-reply-cycle-channel-forum-adapter
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-27
---

# discord-reply.sh --cycle-channel forum adapter

## 1) 개요 (What / Why)

- `tools/discord-daemon/discord-reply.sh` 의 `--cycle-channel <be|fe|rev|plan>` mode 가 cycle channel 이 forum (Discord channel type 15) 으로 전환된 후 `POST /channels/<id>/messages` 호출 → Discord 응답 `Cannot send messages in a non-text channel (50008)` 차단된다.
- nmae forum channel 전환 ([[feedback-nmae-forum-channel-enforce]]) 이후 `BE_CHANNEL_ID` / `FE_CHANNEL_ID` / `REV_CHANNEL_ID` / `PLAN_CHANNEL_ID` 는 모두 forum. forum 은 thread 생성 (`POST /channels/<id>/threads`) 만 허용.
- 영향: sub-agent / nmae 가 cycle channel 에 milestone push 시 silent 실패 → 사용자 가시성 손실. 2026-05-27 plan 사이클 PR #1153 audit 박제.
- 대상 액터: sub-agent (be/fe/rev/plan), nmae 본진. helper 본체는 cycle channel push 금지 룰 ([[feedback-helper-relay-scope]]) 이라 영향 없음.

## 2) 사용자 시나리오

- 시나리오 A (sub-agent milestone stream): plan sub-agent 가 launch 시 `LAUNCH_THREAD_ID` 받아 `--thread <id>` 로 push. 본 adapter 후에는 `LAUNCH_THREAD_ID` 누락 시에도 `--cycle-channel plan "<본문>"` 만으로 forum post 자동 생성 + 가시화.
- 시나리오 B (nmae cycle 단발 알림): nmae 가 cycle channel 에 종합 통지 (예: "be 사이클 5건 백로그 추가") push 시 mode 분기 없이 `--cycle-channel be "<본문>"` 호출만으로 forum post 생성.
- 시나리오 C (호환성): 향후 cycle channel 이 다시 text channel 로 회귀 시 동일 명령이 text channel API 로 동작.

## 3) 요구사항

### 기능 요구사항

- [ ] `--cycle-channel <ws>` 호출 시 channel id 결정 직후 channel type 조회 (`GET /channels/<id>` → `type` 필드). 결과 cache (per-process) 로 동일 cycle 내 중복 호출 1회만.
- [ ] channel type 분기:
  - text channel (`type=0`, `type=5`): 기존 동작 유지 (`POST /channels/<id>/messages`).
  - forum (`type=15`) / media (`type=16`): forum post 생성 모드로 fallback. tag 자동 매칭 로직은 `--forum-post-auto-tag` 와 공유. title 인자 미전달이면 본문 첫 줄 잘라 title 추론 (최대 80자, 줄바꿈 제거).
  - 그 외: 명확한 에러 로그 + exit 1.
- [ ] graceful fallback: channel type 조회 실패 (네트워크 / 권한) 시 기존 text channel API 호출 시도 + 에러는 stderr 에 1줄 로그 (block 안 함).
- [ ] `--cycle-channel` 의 `--no-reply` 동작 (NO_REPLY=1) 은 모드 무관하게 유지. forum post 는 본래 message_reference 불가하므로 자동 NO_REPLY.
- [ ] 환경변수 우선순위 (`--channel <id>` > `--cycle-channel <name>` > `--status-channel` > default) 는 변경 없음.
- [ ] help 텍스트 / usage 갱신: `--cycle-channel` 줄에 "(forum 자동 감지)" 한 줄 추가.

### 비기능 요구사항

- 성능: channel type 조회 1회 추가 (HTTP GET). 100ms 이내 응답 기대. cache 로 동일 sub-agent turn 내 중복 호출 회피.
- 신뢰성: type 조회 실패해도 push 자체는 best-effort 시도 (silent block 금지).
- 관측성: type 분기 시 stderr 1줄 로그 (`discord-reply.sh: --cycle-channel <ws> — forum (type=15) detected, fallback to forum-post mode`).
- 보안: bot token 노출 금지. 기존 `DISCORD_BOT_TOKEN` env 사용.

## 4) 범위 / 비범위 (중요)

### 포함

- `tools/discord-daemon/discord-reply.sh` 의 `--cycle-channel` mode 에 forum adapter 추가.
- channel type 자동 감지 로직 + per-process cache.
- help 텍스트 갱신.
- 기존 `--forum-post-auto-tag` mode 와 tag 매칭 로직 공유 (별도 함수 추출).

### 제외 (Out of Scope)

- `--status-channel` mode 의 forum 대응 (DIGEST 채널은 text 유지 — nmae status push 대상).
- `--channel <id>` 직접 mode 의 forum 자동 감지 (사용자 책임).
- `--thread <id>` mode (이미 thread 직접 지정 → forum 무관).
- helper 본체 호출 경로 (helper 는 cycle channel push 금지 룰).
- nmae 본진 측 cycle channel 라우팅 룰 변경 ([[feedback-nmae-per-cycle-channel]] 그대로).
- bot.py 측 forum 처리 (cycle_idle_watch_loop 등) — 별도 PR.

## 5) 설계

### 5-1) 도메인 모델

- 본 기능은 인프라 (Discord daemon) 영역. 도메인 모델 영향 없음. `docs/ai-harness/06-domain-model.md` 변경 불요.

### 5-2) API 엔드포인트

- 외부 (Discord REST):
  - `GET /channels/<id>` — channel metadata 조회 (type 필드 추출).
  - 기존 `POST /channels/<id>/messages` (text) / `POST /channels/<id>/threads` (forum) 호출은 mode 별 분기.
- 내부 API: 없음 (bash script).

### 5-3) 외부 연동

- Discord REST API (기존). `DISCORD_BOT_TOKEN` 인증.
- 실패 처리: HTTP 비-2xx 응답 시 stderr 로그 + exit code 1. type 조회 실패 (5xx / network) 는 best-effort fallback (text channel 시도).

### 5-4) 데이터 흐름 / 시퀀스

```
sub-agent
  └─ discord-reply.sh --cycle-channel plan "<본문>"
       ├─ resolve channel_id (PLAN_CHANNEL_ID)
       ├─ GET /channels/<channel_id>  → type 필드 추출 (cache hit 시 skip)
       ├─ if type in (0, 5):  # text-like
       │    └─ POST /channels/<channel_id>/messages
       ├─ elif type in (15, 16):  # forum / media
       │    ├─ title 추론 (본문 첫 줄, 80자 truncate)
       │    ├─ tag 자동 매칭 (forum available_tags 와 본문 keyword 매칭)
       │    └─ POST /channels/<channel_id>/threads (with message)
       └─ else: stderr error + exit 1
```

### 5-5) DB 마이그레이션

- 해당 없음 (bash script).

### 5-6) 프론트엔드 화면

- 해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [x] PR (이번, plan): spec + 메모리 박제 (`docs/features/discord-reply-cycle-channel-forum-adapter.md` + `memory/subagent/feedback_discord_reply_cycle_channel_forum.md`).
- [ ] PR (be): `tools/discord-daemon/discord-reply.sh` adapter 구현 + bats 테스트 (cycle-channel forum / text mode 분기).
- [ ] PR (rev): rev 사이클 3단계 audit (단계 1 머지 전 / 단계 2 develop / 단계 3 release).

## 7) 테스트 전략

- 단위 / 통합 (be PR 책임):
  - bats 테스트 — `--cycle-channel be` mock channel type=15 시 forum-post API 호출 검증.
  - mock channel type=0 시 기존 text message API 호출 검증.
  - channel type 조회 실패 mock 시 stderr 로그 + text channel fallback 검증.
- 수동 검증:
  - 4 cycle (be/fe/rev/plan) 채널 모두 forum 으로 push 성공 확인.
  - DIGEST_CHANNEL_ID (text) 로 `--status-channel` push 영향 없음 확인 (regression).
  - `--thread <id>` 모드 영향 없음 확인.
- 회귀 방지: bats 테스트 CI 통합 (`tools/discord-daemon/tests/` 디렉토리).

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

(없음 — 자율 결정 §9 참조)

## 9) 결정 로그

- 2026-05-27: 초안 작성 (status=draft). 본 spec PR (plan 사이클).
- 2026-05-27: **adapter vs deprecate** — adapter 선택 (자율). 이유: (a) `--cycle-channel` 호출 패턴이 sub-agent prompt / nmae wrapper 곳곳에 박혀 있어 deprecate 시 광범위 grep + 치환 부담, (b) channel type 회귀 (forum → text) 가능성 대비 호환성 유지, (c) help / docs 변경 최소화.
- 2026-05-27: **title 추론 자동화** — `--cycle-channel` 호출 시 title 인자 추가하지 않고 본문 첫 줄 80자 truncate. 이유: 기존 호출자 (sub-agent / nmae) prompt 광범위 변경 회피.
- 2026-05-27: **channel type cache scope** — per-process (script 1회 실행 내) 만. cross-invocation cache 는 stale 위험 + 복잡도 증가로 회피.
