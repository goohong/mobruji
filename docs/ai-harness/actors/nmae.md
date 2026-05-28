# nmae Runbook — NCP maestro 전용

> **로드 대상: nmae 세션(NCP `tmux mobruji:0.0`)만.** 다른 actor(helper/be/fe/rev/plan)는 읽지 않습니다.
> **강제는 prose 가 아니라 코드** — bot.py watchdog loop + `tools/agent-launch-wrapper.sh` / `tools/cycle-status/update.sh` 가 본 룰을 강제합니다. 본 문서는 **판단이 필요한 룰만**, 인시던트 "왜" 는 메모리(`memory/nmae/feedback_*.md`)가 보관.
> 메모리 actor: `nmae`. 충돌 시 본 문서 + 강제 코드 우선.

---

## 11) nmae 전용 룰 (NCP maestro 오케스트레이션)

### 11-1) 항시 가동 — 사이클 idle 금지

**비협상 (2026-05-24): "절대로 nmae 사이클이 멈춰서는 안돼".** be/fe/rev/plan **4 워크트리 동시 가동 고정** ([[feedback-keep-4-cycles-active]]).

- 강제: bot.py `cycle_idle_watch_loop` 가 5분 polling `~/.mobruji/cycle-status.json`, idle 발견 시 자동 tmux inject + push (spec `docs/features/nmae-cycle-watchdog.md`, [[feedback-nmae-cycle-watchdog]]). **idle 정의**: `in_progress: null` AND `last_completed.completed_at` > now − 10분.
- **nmae 판단**: 1 sub-agent 완료 통지 받자마자 같은 워크트리 다음 백로그 launch — idle default 금지. (nmae 가 반복적으로 까먹어 watchdog 가 핵심 안전망)

### 11-2) watchdog inject 대응 (#972)

inject 받으면 **다음 turn 시작 즉시**: 백로그 후보 1개 선정 → `bash tools/agent-launch-wrapper.sh <ws> --title "<후보>"` (set-active + per-cycle 채널 launch 알림/thread push + directive in_progress 를 한 번에) → `Agent` tool 로 sub-agent launch (`/home/mobruji/mobruji-<ws>`) → cycle 채널 재개 알림. 누락 시 다음 5분 polling 에서 재inject = 무한 idle loop. 같은 워크트리 3회 연속 inject + NULL → bot.py 가 가시화 push (debounce 1h).

### 11-3) cycle-status.json — 수동 편집 절대 금지 (#971 회귀 가드)

- `~/.mobruji/cycle-status.json` 직접 편집(`vim` / `jq` / `cat <<EOF`) 금지. `tools/cycle-status/update.sh` 헬퍼만 (atomic write + 스키마 안전). [[feedback-cycle-status-json]]
- idle 진입 시 `note` 의무 — `update.sh <ws> set-idle --note "..."`. 미명시 시 watchdog 가 STRICT relaunch prompt inject (`CYCLE_REASON_REQUIRED=1` default).
- 강제: `validate.sh` 가 매 update 후 sanity + future-timestamp detect → 발견 시 bot.py ERROR 로그 + push. (사고 박제: KST 시각을 Z suffix 로 hand-edit → future timestamp → idle detect 차단.)

### 11-4) 본진 = 오케스트레이션 only (2026-05-26 비협상)

**"동시 사이클 4개 유지. 과부하 대응 = 사이클 축소 X, 본진 context 가벼이 유지 + 위임."**

- 본진 책임: ① sub-agent launch / 위임 prompt 작성 ② 완료 통지 수신 + cycle-status 갱신 + 다음 백로그 launch ③ PR 머지 결정 ④ cron 미커버 1회성 보고.
- 본진 **금지**: 코드 변경 / 길어진 git 작업 / gradle·npm 실행 / 대량 로그 분석 — 전부 sub-agent 위임.
- context% marker(§14) 75%+ → 추가 작업 자제 + 다음 turn `/clear` 후보. context 폭증을 본진이 흡수하면 4 사이클 launch 가 끊긴다. (메모리 `nmae/feedback_nmae_main_lightweight.md`)
- 같은 워크트리 = 동시 1 sub-agent ([[feedback-worktree-lock]]). 같은 도메인 병렬 필요 시 임시 워크트리(`git worktree add`). placeholder/null fallback 으로 BE/FE 대부분 동시 launch 가능 — "BE 필드 의존" 보수적 미룸 금지 ([[feedback-be-fe-parallel]]).

### 11-5) launch / 통지 / autonomous wake

- launch 까먹기 금지 (nmae 가 직접 코드·테스트 처리 시 워크트리 idle + context 폭증). launch 직후 즉시 해당 cycle 채널 push ([[feedback-subagent-launch-report]]).
- sub-agent 완료 통지는 본진 자기 작업보다 **우선 처리** ([[feedback-notification-preempts-main]]) — wall-clock 최소화.
- rev 코멘트 → nmae 자동 후속 이슈 등록, 🔴 시급 항목은 같은 사이클 즉시 트리거 ([[feedback-auto-register-rev-findings]]).
- idle 시 ScheduleWakeup 3600s self-perpetuating ([[feedback-autonomous-wake-pattern]]). nmae 가 Agent 도구로 4 sub-agent background 가동 ([[feedback-orchestration-pattern]]). 사이클 launch/오류/결정 분기는 GitHub events → webhook push, URL .env 보유 금지 ([[feedback-discord-status-push]]).

### 11-6) Discord 채널 라우팅

- **사이클 별 채널** ([[feedback-nmae-per-cycle-channel]]): sub-agent launch / 완료 / milestone / audit = 해당 cycle 전용 채널 (`BE_/FE_/REV_/PLAN_CHANNEL_ID` .env). 종합(cron digest + cross-cycle alert)만 `DIGEST_CHANNEL_ID`. generic DIGEST 묶음 금지.
- **manual status push 자제** ([[feedback-nmae-no-manual-status-push]] [[feedback-nmae-status-channel]]): 일반 launch/완료/요약 = cron digest(5분 주기)만 신뢰. manual 예외 3종만 — ① 긴급 escalation ② 사용자 인계 직후 1줄 ack ③ cron 미커버 1회성(release tag 등).
- 호출: `bash ~/.mobruji/nmae-discord-push.sh "<본문>"` 또는 `discord-reply.sh --status-channel`. #모부르지(`MOBRUJI_CHANNEL_ID`) = 사용자 응답 전용, status leak 금지.

### 11-7) directive-board (#1129 event-driven, jsonl = SoT)

- `~/.mobruji/directive-board.jsonl` 의 각 entry status = 단일 진실. Discord #모부르지-지시 forum 본문/태그는 그 view. **수동 Discord 본문 edit 금지** (desync 원인) — 반드시 헬퍼 호출. [[feedback-nmae-directive-board-update-flow]]
- 3 트리거 atomic 호출 (상세 `docs/features/directive-board-event-driven-redesign.md`):
  - (a) 지시 분류 → `directive_append.sh <msg_id> "<title>" [pr_url]`
  - (b) 위임 / launch → `directive_status.sh <id> in_progress [pr_url]` (`agent-launch-wrapper.sh` 강제)
  - (c) 완료 / 머지 → `directive_status.sh <id> completed [pr_url]`
- 강제: `helper-turn-start.sh`(helper) + `agent-launch-wrapper.sh`(launch) 가 turn/launch 시작 시 jsonl ↔ forum mismatch warning. polling sync_loop 폐기 (#1129 — 자동 PATCH 불완전 + 5분 latency 가 desync 원인).

---

## 부록) sub-agent launch quick-ref — 상세 SoT `docs/ai-harness/actors/sub-agent.md`

> nmae 가 be/fe/rev/plan launch 시 의존하는 비협상 룰만. 역할 매핑/주입 맵은 `docs/ai-harness/00-MANIFEST.md`.

| Role | 워크트리 | 작업 경로 | 품질 게이트 |
|---|---|---|---|
| **be** | `/home/mobruji/mobruji-be` | `backend/**` | `cd backend && ./gradlew checkstyleMain spotlessCheck test` |
| **fe** | `/home/mobruji/mobruji-fe` | `web/**` | `cd web && npm run lint && npm run typecheck && npm test && npm run build` (`npm install` 금지 — 외부 디스크 symlink 보존, [[feedback-npm-install-symlink-swap]]) |
| **rev** | `/home/mobruji/mobruji-rev` | **수정 금지** (PR 코멘트만) | read-only 실행 검증. 매 사이클 첫 액션 `tools/rev-queue/rev-queue.sh all` |
| **plan** | `/home/mobruji/mobruji-plan` | `docs/**` `.github/**` (보호 영역 라벨) | 없음 |

- 공통 비협상: 워크트리 격리 + 동시 1 ([[feedback-worktree-lock]]) / 메모리 직접 수정 금지(nmae 만 갱신) / 사용자 wait state 금지 — 자율 결정 default ([[feedback-sub-agent-no-user-wait]]) / hook 우회(`--no-verify`) 금지 / `gh pr create --base develop` 강제 ([[feedback-pr-base-develop]]) / 보호 영역 = 정보성 분류 (라벨 의무 폐지 2026-05-28, rev 대행) / session 라벨 (be→backend, fe→frontend, rev→review, plan→plan).
- **be**: 새 엔드포인트 성공 케이스 E2E(RestAssured) 필수, DDD 계층 침범 금지. **rev**: 3단계 e2e — 단계 1 머지 전 / 2 develop 후 / 3 release 후 ([[feedback-rev-e2e-always]] [[feedback-rev-release-gate]]). **plan**: docs/ADR/spec 만, 구현 코드 금지.
- git 가드: stash pop conflict 후 working tree 마커 잔존 → service crash, 별도 restore 필수 ([[feedback-stash-drop-unmerged-file]]).
