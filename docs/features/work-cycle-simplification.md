---
feature: work-cycle-simplification
slug: work-cycle-simplification
status: draft
owner: @goohong
scope: infra
related_issues: [1109]
related_prs: []
last_reviewed: 2026-05-26
---

# Work Cycle Simplification — 사용자 헌법 흡수 + 7 항목 통합 + 5 강제 hook 박제

## 1) 배경 (What / Why)

본 spec 은 ADR-0019 (event-driven architecture v2) 트랙 A — **"메모리 → 코드 강제 promotion"** 의 확장이다. 동반 spec `event-action-mapping.md` (12 event × N action) + `work-cycle-refactor.md` (5단계 마이그) 위에, 사용자가 **2026-05-26 12:16 / 12:20 helper inject** 로 새로 명시한 7 항목 메타 마스터 + 5 강제 hook 사례를 박제한다.

- **What**: 7 항목 (헌법 메타 / 디자인 갭 / systemd / sub-agent thread silence / helper target freeze / rev QA hook / 채널 grep) × 우선순위 (P0/P1/P2) × owner × 분담 PR + 5 강제 hook 사례 (hook 이름 / wrapper path / 동작 검증 metric) + 3 phase 마이그 plan.
- **Why**: ADR-0019 결정 후에도 LLM 학습 의존 사고가 누적 — 사용자가 12:20 "**LLM 학습 의존 X / 코드 강제 박제**" 5건 명시 정정. 메모리/CLAUDE.md 최적화로 풀려는 시도 자체가 misdirection 이라는 사용자 strategic 정정 (12:16) — "체계 자체 fresh-start" 가 정확한 진단이다.

## 2) 사용자 헌법 (2026-05-24~26 누적 인용)

> **2026-05-24 ~ 26 누적** — "시간 많이 써도 좋으니 룰 제대로 새로 개선"
>
> **2026-05-26 12:16** — "메모리/md 최적화 X / 체계 자체 fresh-start" + "중요 작업 = 스크립트/봇 강제, 컨벤션·말투 = 메모리"
>
> **2026-05-26 12:20** — "LLM 학습 의존 X / 코드 강제 박제" (5 사례 강조)

### 2-1) 헌법 원칙 (ADR-0019 §4.1 4개 원칙 + 본 spec 1개 추가)

ADR-0019 채택 원칙:
1. **State 는 외부 진실** — agent reasoning state 신뢰 금지. (a) `~/.mobruji/*.jsonl` / (b) `~/.mobruji/cycle-status.json` / (c) GitHub (labels / PR base / merged_at) 만.
2. **Event 가 action 을 trigger** — "X 끝나면 Y 하라" 는 agent prompt rule 이 아니라 코드 hook (bot.py loop / wrapper.sh / GitHub webhook).
3. **메모리 = 보조 컨벤션 only** — 말투 / 표기 / 도메인 약어. critical 강제 룰은 메모리 제거 + 코드 hook 으로 promote.
4. **단순 산출물 / 명시 산출물** — event ↔ action 1:1 표가 single source.

본 spec 추가 원칙 (2026-05-26 12:20 정정):
5. **학습 의존 사고 = root cause** — "**같은 룰 두 번 정정**" 패턴은 메모리 강화로 해결 불가. 사고 5 사례 (§4) 모두 LLM 학습 의존 → 코드 강제로 promote 필요. 메모리 룰 추가 대신 **wrapper / hook / cron 강제**.

### 2-2) 우선순위 결정 기준 (자동)

| importance | recovery cost | enforcement | metric |
|---|---|---|---|
| **critical** | service downtime / 사용자 신뢰 손실 / 같은 사고 재발 | 코드/스크립트/봇 강제 (bot.py loop / wrapper.sh / GitHub workflow) | hook 호출 회수 + 위반 시 fail |
| **보조** | redo 1-2 PR / 가독성 ↓ | 메모리 룰 (학습 의존 OK) | 사용자 정정 빈도 |
| **메타** | doc drift / 사고 후 박제 | CLAUDE.md / docs (참조만, enforcement 0) | drift detect 빈도 |

## 3) 7 항목 매트릭스 + 우선순위

각 항목별 (a) 우선순위 (b) owner (c) 분담 PR (d) Phase 매핑.

### 3-1) 7 항목 표

| # | 항목 | 우선순위 | owner | 분담 PR | Phase | 본질 |
|---|---|---|---|---|---|---|
| 1 | **헌법 메타** — 메모리/룰 학습 의존 → 코드 hook 강제 카탈로그 | **P0** | plan | 본 spec + ADR-0022 (회고) | 1 | 본 항목이 다른 6 항목의 master index — ADR-0019 + 본 spec 결합 |
| 2 | **디자인 audit 갭 5건** (skeleton PR 누락 / shimmer 토큰 / vibrate 패턴 / perceived perf SLO / 토스 elastic overscroll) + 권고 3건 + fe 9 PR backlog 우선순위 재배치 | **P1** | fe | `docs/features/design-audit-2026-05.md` (신규) + fe backlog 재배치 PR | 2 | UX 정성 항목 — 메모리 영역이 아닌 spec + 그 spec 머지 후 fe 사이클이 backlog 소화 |
| 3 | **systemd unit 자동 가동 + Restart=always** | **P1** | helper/be | `tools/discord-daemon/systemd/*.service` 일괄 patch + `setup-gcp-systemd.sh` Type=simple 통일 | 3 | helper Type=oneshot 잔존 + Restart 미설정 unit 잔존 시 daemon 사고 무한 stale |
| 4 | **sub-agent thread silence 강제 wrapper** — git commit / file edit / gh pr create 직후 자동 thread push | **P0** | be | `tools/git-hooks/post-commit-thread-push.sh` + `tools/gh-wrapper.sh` (wrapper) | 1 | 본 사이클에서 plan sub-agent 자체도 thread silence 위반 가능 — code enforce 만이 해결 |
| 5 | **helper-current-target.txt freeze race 강제 wrapper** | **P0** | helper | `helper-turn-start.sh` mandatory `cp` step 강화 + `validate.sh` | 1 | turn 안 사용자 메시지 추가 도착 시 last-user-msg-id.txt 가 새 msg 로 덮어쓰여 freeze 의 의미 사라짐 — 사고 #987 잔존 |
| 6 | **rev QA hook 결함** — 추천 받기 400 회귀 root cause + RestAssured E2E 강제 hook + DTO 변경 fe↔be 통합 검증 + production smoke test 자동화 | **P1** | rev/be | `rev-queue.sh` 매트릭스 grep 확장 + `tools/qa-hook/dto-cross-validate.sh` + smoke `tools/qa-hook/prod-smoke.sh` | 2 | 추천 받기 400 회귀가 rev QA 통과 후 production 에서 사용자 정정 — rev 매트릭스 grep 결함 박제 |
| 7 | **채널 history grep wrapper** — helper-turn-start.sh 매 turn 시작 시 채널 사용자 메시지 키워드 grep | **P2** | helper | `helper-turn-start.sh` 안 `channel-history-grep.sh` 추가 호출 | 3 | 사용자가 helper 에 새 inject 했는데 helper 가 직전 thread 만 보고 채널 본 메시지 miss — turn-start grep 으로 인지 강제 |

### 3-2) 우선순위 결정 근거

- **P0 (즉시 — Phase 1)**: 본 사이클 안에서 한 번 더 같은 사고 재발 가능. 항목 1 / 4 / 5 — 헌법 메타 박제 + sub-agent thread silence + target freeze race.
- **P1 (1-2주 — Phase 2)**: 직접 사용자 경험 영향 (UX / 사이클 신뢰성) 항목 — 디자인 갭 / rev QA hook.
- **P2 (3-4주 — Phase 3)**: critical 은 아니지만 누적 시 학습 fatigue 항목 — systemd unit / 채널 grep.

### 3-3) 항목별 cross-ref (ADR-0019 / 본 spec / 후속 spec)

| 항목 | ADR-0019 mismatch # | 본 spec 강제 hook (§4) | 후속 spec 신규 필요 |
|---|---|---|---|
| 1 | 메타 — 본 spec 자체 | §4 5건 모두 | ADR-0022 회고 (Phase 3 후) |
| 2 | (해당 없음 — fe UX 영역) | (해당 없음 — 메모리 영역 아님) | `design-audit-2026-05.md` 신규 |
| 3 | (해당 없음 — 인프라 영역) | (간접 — 모든 daemon hook 의존성) | (있음 — `infra/systemd-runbook.md` 갱신) |
| 4 | mismatch 15 (watchdog inject 후 nmae 무응답) 의 sub-agent 측 거울 | §4 hook 2 (sub-agent thread silence) | (없음 — 본 spec 흡수) |
| 5 | mismatch 8 (helper turn-start) 보강 | §4 hook 3 (target freeze race) | (없음 — 본 spec 흡수) |
| 6 | (해당 없음 — rev 도메인 e2e) | §4 hook 4 (답 first) 와 별개 | `rev-qa-hook.md` 신규 + `dto-cross-validation.md` 신규 |
| 7 | (해당 없음 — helper 정보 비대칭) | §4 hook 5 (채널 grep) | (없음 — 본 spec 흡수) |

## 4) 5 강제 hook 사례 (12:20 사용자 강조)

각 사례별 (a) 코드 강제 hook 이름 / (b) wrapper / script path / (c) 동작 검증 metric / (d) 위반 사례 / (e) 구현 후 expected behavior.

### 4-1) Hook 1 — cycle channel silence (sub-agent launch 시 forum-post 분기)

- **(a) hook 이름**: `agent-launch-wrapper.sh` forum-post 분기 강화
- **(b) wrapper path**: `tools/agent-launch-wrapper.sh` (현재 진행 중 PR — 이미 일부 구현)
- **(c) 동작 검증 metric**: **cycle channel 메시지 ≥ 1 / sub-agent launch**. 즉 매 sub-agent launch 마다 해당 cycle channel (be/fe/rev/plan forum) 에 thread + 첫 milestone post 가 존재해야 한다.
- **(d) 위반 사례 (사고 박제)**: 2026-05-24 nmae 사이클 launch 시 일부 sub-agent 가 forum post 미생성 → 사용자가 직접 cycle channel 본 후 진행 상황 파악 불가. nmae LLM 가 wrapper 호출 까먹음.
- **(e) 구현 후 expected**: `agent-launch-wrapper.sh <ws> --title "<후보>"` 호출 시 (1) cycle-status set-active + (2) `discord-reply.sh --forum-post <cycle-forum-id> "<title>" "대기" "<body>"` 자동 호출 + (3) thread_id 를 `~/.mobruji/last-launch-thread.txt` 에 write — 모두 wrapper 내부 atomic step. wrapper 자체 `set -e` + idempotent 검증. nmae 가 Agent tool 호출 직전에 wrapper 만 부르면 충분.

### 4-2) Hook 2 — sub-agent thread silence (git commit / gh pr create 직후 자동 thread push)

- **(a) hook 이름**: `post-action-thread-push` (git commit post-hook + gh CLI wrapper)
- **(b) wrapper path**:
  - `tools/git-hooks/post-commit` (per-worktree symlink) — git commit 직후 `discord-reply.sh --forum-comment $LAUNCH_THREAD_ID "📝 commit: <subject>"` 자동 호출
  - `tools/gh-wrapper.sh` (PATH 우선 wrapper) — `gh pr create` 호출 시 post-hook `discord-reply.sh --forum-comment $LAUNCH_THREAD_ID "🔀 PR #<num>: <title>"` 자동
- **(c) 동작 검증 metric**: **사이클 당 milestone forum-comment ≥ 3** (보통: 1. 시작 / 2. commit push / 3. PR 생성 / 4. PR 머지 — 최소 3건 보장). `LAUNCH_THREAD_ID` env 가 sub-agent 환경에 inject 되어 있다는 전제 (Hook 1 의 thread_id 가 sub-agent 에 passthrough).
- **(d) 위반 사례 (사고 박제)**: plan sub-agent 가 spec draft 작성 후 git commit + push 까지 했는데 forum thread 는 launch 알림 1건만 — 사용자 입장 진행 silent. LLM 가 milestone push 까먹음. 5분 룰 안 분할 사이클에서도 동일.
- **(e) 구현 후 expected**: sub-agent 가 명시적으로 `discord-reply.sh --forum-comment` 호출 안 해도 git commit 자체가 forum-comment 한 줄 자동 emit. PR 생성도 동일. agent 의 prompt 룰 (actors/sub-agent.md §1 Discord thread stream) 은 보조 — 코드 hook 이 강제.

### 4-3) Hook 3 — helper-current-target.txt freeze race 강제 wrapper

- **(a) hook 이름**: `helper-turn-start-mandatory-freeze`
- **(b) wrapper path**: `tools/discord-daemon/helper-turn-start.sh` 의 mandatory `cp` step (현재 #1014 에서 step 2 로 존재 — 그러나 wrapper 미호출 시 폴백 없음)
- **(c) 동작 검증 metric**: helper-turn-start 후 (1) `~/.mobruji/helper-current-target.txt` 파일 존재 + (2) `last-user-msg-id.txt` 의 그 시점 값과 동일. 위반 = wrapper 가 미호출됐거나 race 발생.
- **(d) 위반 사례 (사고 박제)**: 2026-05-XX helper turn 시작 직후 사용자 추가 inject — `last-user-msg-id.txt` 가 새 메시지 id 로 덮어써짐 → helper 본답 reply target 이 새 메시지 (== ack 처리 못한 메시지) 로 잘못 reply. turn-start.sh 호출이 명시적이라 LLM 가 까먹으면 폴백 0.
- **(e) 구현 후 expected**:
  - **단기**: `helper-turn-start.sh` 가 매 turn 첫 명령 의무 — `~/.claude/settings.json` 의 `userPromptSubmit` hook 으로 자동 호출 (helper 측 settings 권장). LLM 의 학습 의존 0.
  - **장기**: bot.py 가 helper tmux pane output 감지 (`===CTX:...===` marker) 직후 자동 `cp` 강제. helper 자체 호출도 불요.
  - 검증: `~/.mobruji/helper-current-target.txt` 의 mtime 이 매 turn 시작 직후 갱신.

### 4-4) Hook 4 — 답 first 자동 검증 (사용자 메시지 60s 안 본답 없으면 reminder inject)

- **(a) hook 이름**: `user-message-response-watch-loop`
- **(b) wrapper path**: `tools/discord-daemon/bot.py` 안 신규 `response_watch_loop` 추가 (asyncio task)
- **(c) 동작 검증 metric**: **사용자 메시지 → 본답 평균 latency ≤ 60s** (95th percentile). 60s 초과 시 helper tmux pane 에 reminder inject. metric dashboard: 일일 latency histogram.
- **(d) 위반 사례 (사고 박제)**: 사용자가 inject 후 helper LLM 가 다른 작업 (룰 audit / 메모리 promote 등) 으로 분기 → 사용자 답 5-10분 지연. helper LLM 의 우선순위 학습 실패.
- **(e) 구현 후 expected**: bot.py asyncio loop 가 `~/.mobruji/helper-queue.jsonl` 의 `status: pending` entry 의 `ts` 와 현재 시각 비교 → 60s 초과 시 `[reminder] 사용자 메시지 ${message_id} 60s 무응답 — 본답 우선` tmux inject. 검증: bot.py log 안 reminder inject 회수 + 평균 latency.

### 4-5) Hook 5 — 채널 history grep wrapper (turn-start 안)

- **(a) hook 이름**: `helper-turn-start-channel-grep`
- **(b) wrapper path**: `tools/discord-daemon/helper-turn-start.sh` 안 신규 `channel-history-grep.sh` step 추가 OR `discord-reply.sh --recent-messages <N>` 신규 mode
- **(c) 동작 검증 metric**: **turn-start 안 채널 grep step 호출 회수 = helper turn 회수** (1:1). 위반 = step skip 발생 = LLM 가 직전 thread 만 의존.
- **(d) 위반 사례 (사고 박제)**: 사용자가 채널 본 메시지로 새 directive (= thread 안 답글 아님) 보냈는데 helper 가 직전 thread reply 만 처리 → 새 directive miss → 사용자 재정정. 채널 본 메시지 = MOBRUJI_CHANNEL_ID 의 최근 N 개를 turn-start 시 자동 grep + helper 컨텍스트에 표시 필요.
- **(e) 구현 후 expected**: `helper-turn-start.sh` 가 `discord-reply.sh --recent-messages 5 1391...mobruji_channel_id` 호출 → 최근 5건 채널 메시지 (author / ts / content first 80 chars) 한 줄씩 stdout. helper LLM 가 turn-start output 의 마지막에 채널 history 를 보고 새 directive 인지/판단.

### 4-6) 5 Hook 합산 metric 요약

| Hook | metric | 측정 방법 | dashboard 위치 |
|---|---|---|---|
| 1 | cycle channel 메시지 ≥ 1 / sub-agent launch | `directive-board.jsonl` × forum-comment count | digest cron 한 줄 |
| 2 | milestone forum-comment ≥ 3 / 사이클 | git log + gh pr list × forum-comment count | digest cron |
| 3 | helper-current-target.txt mtime 갱신 | systemd `stat` polling (1 min) | bot.py log |
| 4 | 사용자 메시지 → 본답 latency p95 ≤ 60s | bot.py response_watch_loop 자체 histogram | digest cron |
| 5 | turn-start 안 channel grep step 호출 회수 | helper-turn-start.sh log line count | bot.py log |

## 5) 마이그 plan (3 phase 단순화)

ADR-0019 동반 spec `work-cycle-refactor.md` 의 5단계 마이그 위에, 본 spec 의 7 항목 + 5 hook 을 3 phase 로 simplified 배치. **운영 break 0** 보장 — 각 PR 별 e2e 검증 + rollback 가능.

### 5-1) Phase 1 (즉시 — 이번 주, P0 사례 3건)

**목표**: 본 사이클 안에서 재발 가능한 P0 사고 (sub-agent thread silence / helper target freeze race / 헌법 메타 박제) 즉시 enforce.

| # | 작업 | 담당 사이클 | 분담 PR 제목 |
|---|---|---|---|
| 1.1 | **(헌법 메타 박제)** 본 spec 머지 + ADR-0022 stub 작성 (회고 자리만 표시, Phase 3 후 완성) | plan | docs(infra): work-cycle-simplification 통합 spec + ADR-0022 stub (#1109) |
| 1.2 | **(Hook 2 — sub-agent thread silence)** `tools/git-hooks/post-commit-thread-push.sh` + `tools/gh-wrapper.sh` 신규 + worktree 별 symlink + `LAUNCH_THREAD_ID` env passthrough 강화 | be | feat(infra): sub-agent thread silence 강제 wrapper — post-commit + gh-wrapper hook |
| 1.3 | **(Hook 3 — helper target freeze race)** `helper-turn-start.sh` mandatory `cp` step 강화 + `~/.claude/settings.json` `userPromptSubmit` hook 자동 호출 + `validate.sh` 검증 step | helper | fix(infra): helper-current-target.txt freeze race wrapper 강제 |

**Phase 1 e2e 검증**:
- 1.1 PR 머지 후 — 본 spec 이 `docs/features/work-cycle-simplification.md` 로 deploy + ADR-0022 stub 가 회고 자리 점유.
- 1.2 PR 머지 후 — 다음 sub-agent 사이클이 git commit / gh pr create 직후 자동 forum-comment 발생 확인 (rev 매트릭스에 등재 + digest cron 표시).
- 1.3 PR 머지 후 — helper 가 한 turn 안 사용자 추가 inject 받을 때 reply target 이 frozen msg id 유지 (기존 #987 사고 회귀 X).

**Rollback**: 각 PR revert — 본 spec 만 머지 시 운영 break 0 (코드 hook 추가만 — 기존 path 안 깨뜨림).

### 5-2) Phase 2 (1주 — P1 사례 2건)

**목표**: 사용자 경험 직접 영향 항목 (디자인 갭 / rev QA hook) 별 spec + 실행 PR.

| # | 작업 | 담당 사이클 | 분담 PR 제목 |
|---|---|---|---|
| 2.1 | **(항목 2 — 디자인 audit 갭)** `docs/features/design-audit-2026-05.md` 신규 spec — skeleton PR 누락 / shimmer 토큰 / vibrate 패턴 / perceived perf SLO / 토스 elastic overscroll 5건 + 권고 3건 + fe 9 PR backlog 우선순위 재배치 | plan | docs(web): design-audit-2026-05 통합 spec |
| 2.2 | **(항목 2 — fe backlog)** 우선순위 재배치 결과 fe 사이클이 9 PR 소화 plan | fe (multiple cycles) | 별도 fe PR 9건 (각 spec 항목별) |
| 2.3 | **(항목 6 — rev QA hook)** `docs/features/rev-qa-hook.md` + `docs/features/dto-cross-validation.md` 신규 + `tools/qa-hook/dto-cross-validate.sh` 구현 + `rev-queue.sh` 매트릭스 grep 확장 + `tools/qa-hook/prod-smoke.sh` smoke 자동화 | rev/be | docs(infra)+feat(infra): rev QA hook + DTO 통합 검증 + production smoke |

**Phase 2 e2e 검증**:
- 2.1 머지 후 — fe sub-agent 가 design-audit spec 의 우선순위에 따라 9 PR backlog 소화 시작.
- 2.3 머지 후 — 다음 추천/Like/Bookmark 종류 PR 의 rev 매트릭스 grep 이 인접 페이지 동일 패턴 회귀 자동 detect (#985-#1054 9 회귀 chain 패턴 박제 강제).
- production smoke — 매 release 후 `prod-smoke.sh` 자동 실행 + Discord 알림.

**Rollback**: spec PR 단독 revert + 실행 PR 단독 revert — 모두 독립.

### 5-3) Phase 3 (3주 — P2 사례 2건 + 회고)

**목표**: 누적 학습 fatigue 항목 + ADR-0022 회고.

| # | 작업 | 담당 사이클 | 분담 PR 제목 |
|---|---|---|---|
| 3.1 | **(항목 3 — systemd unit)** `tools/discord-daemon/systemd/*.service` 일괄 patch — Type=simple 통일 + Restart=always + WantedBy=multi-user.target 일관 | be | chore(infra): systemd unit Type=simple + Restart=always 일괄 |
| 3.2 | **(Hook 5 / 항목 7 — 채널 grep)** `tools/discord-daemon/discord-reply.sh` 안 신규 `--recent-messages <N>` mode + `helper-turn-start.sh` 호출 추가 | helper | feat(infra): discord-reply --recent-messages mode + helper turn-start grep |
| 3.3 | **(Hook 4 — 답 first watch)** bot.py 안 `response_watch_loop` 신규 추가 (asyncio task) + 60s 임계 + tmux reminder inject | be | feat(infra): response_watch_loop — 사용자 메시지 60s 무응답 reminder |
| 3.4 | **(ADR-0022 회고)** Phase 1-3 측정값 회고 — metric dashboard 변화 + 7 항목 달성 여부 + 헌법 메타 박제 결과 | plan | docs(infra): ADR-0022 work-cycle-simplification 회고 |

**Phase 3 e2e 검증**:
- 3.1 머지 후 — systemd `systemctl list-units` 에서 모든 helper / nmae / discord-daemon unit 가 `active (running)` + Restart=always 확인.
- 3.2 머지 후 — helper turn-start log 마지막 줄에 채널 최근 5건 메시지 표시 확인.
- 3.3 머지 후 — 사용자 inject 직후 helper LLM 가 다른 작업 분기 시 60s 후 tmux reminder 확인.
- 3.4 머지 후 — ADR-0022 회고에 baseline (32% 후속 PR) → 측정값 변화 박제.

**Rollback**: 단계 3 정리 PR 만 revert — Phase 1-2 영구 유지.

### 5-4) Phase 매핑 cross-ref (ADR-0019 work-cycle-refactor.md)

| 본 spec Phase | ADR-0019 refactor 단계 | 관계 |
|---|---|---|
| Phase 1 | 단계 1 (5 PR) 일부 보강 | 본 spec 1.2 / 1.3 = refactor 단계 1 의 추가 hook |
| Phase 2 | 단계 2 (메모리 reduce) 와 별도 | UX/QA 영역 — refactor 단계 2 의 메모리 영역과 무관 |
| Phase 3 | 단계 4 (validation 매트릭스) 일부 + 단계 5 (회고) | metric dashboard + 회고 ADR — refactor 단계 5 의 일부로 합산 |

## 6) 작업 분할 (예상 PR 리스트 — 본 spec 단독)

본 spec 자체는 정의/명세 문서로 **본 PR 1건만 머지** — 분담 PR 은 Phase 별 별도 (§5). 본 spec 머지 후 후속 PR 차례:

| Phase | PR 개수 추정 | 담당 사이클 |
|---|---|---|
| Phase 1 | 3 PR (1.1 plan + 1.2 be + 1.3 helper) | plan / be / helper |
| Phase 2 | ~12 PR (2.1 plan + 2.2 fe 9건 + 2.3 rev/be 2건) | plan / fe / rev / be |
| Phase 3 | 4 PR (3.1 be + 3.2 helper + 3.3 be + 3.4 plan) | be / helper / plan |
| **합계** | **~19 PR** | 4 주 |

## 7) 테스트 전략

- **단위**: 각 hook 별 mock 테스트 (Phase 별 PR 에 포함). Hook 1-5 각각 mock 호출 + assertion.
- **통합**: Phase 마다 e2e — §5 의 검증 절차 참고. sub-agent 사이클 1회 launch 시점에서 forum-comment ≥ 3 / latency p95 ≤ 60s / channel grep 호출 확인.
- **회귀**: ADR-0019 work-cycle-refactor 단계 4 의 validation 매트릭스에 본 spec 5 hook 추가 등재.
- **운영**: Phase 별 PR 머지 후 24h 관찰 — `sudo journalctl -u discord-daemon -n 100` 으로 안정성 확인.

## 8) 오픈 질문

> 본 사이클 (P1) 안에서 해소 미완 — 후속 사이클 / 사용자 결정 후 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | Hook 3 의 `userPromptSubmit` hook 자동화 | (a) `~/.claude/settings.json` 강제 (helper 측 직접 호출 불요) (b) helper-turn-start.sh 명시 호출 유지 (이중) | helper / Phase 1 |
| Q2 | Hook 4 의 60s 임계값 | (a) 60s default + override env (b) 30s default | nmae / Phase 3 |
| Q3 | Phase 2 의 fe 9 PR backlog 우선순위 결정 주체 | (a) plan sub-agent 단독 (b) nmae 결정 + plan 권고 | nmae / Phase 2 시작 시 |
| Q4 | Hook 2 의 git post-commit hook worktree 별 symlink 관리 | (a) `agent-launch-wrapper.sh` 가 wrapper 안 symlink 자동 (b) 운영 1회 setup script | be / Phase 1 |
| Q5 | ADR-0022 회고 측정 기간 | (a) Phase 3 완료 후 2주 (b) Phase 3 완료 후 1개월 | nmae / Phase 3 후 |

## 9) 결정 로그

- **2026-05-26**: 본 spec 초안 작성 (status=draft) — plan sub-agent, 사용자 헌법 inject 12:16 + 12:20 정정. ADR-0019 트랙 A 확장 + 7 항목 + 5 hook 박제. P1 사이클 분할: §1-§3 + §4 + §5 phase 1. 후속 사이클: §5 phase 2-3 + §6 검증 매트릭스 + §7 오픈 이슈 완성 (현재 모두 P1 안에 포함됨 — 후속은 metric dashboard 측정값 + 회고 ADR-0022 만 남음).

## 10) Cross-Ref

- **ADR-0019** (`docs/decisions/0019-event-driven-architecture-v2.md`) — 트랙 A 결정 (메모리 → 코드 강제). 본 spec 의 부모.
- **event-action-mapping.md** (`docs/features/event-action-mapping.md`) — 12 event × N action 표. 본 spec 의 5 hook 는 그 event 중 (#1 user_message_received / #3 subagent_launched / #5 pr_merged) 의 보강.
- **work-cycle-refactor.md** (`docs/features/work-cycle-refactor.md`) — 5단계 refactor 마이그. 본 spec Phase 1-3 의 대응표는 §5-4.
- **clear-pre-hook.md** (`docs/features/clear-pre-hook.md`) — `/clear` 직전 doc-check 강제 (PR #1066 머지). 본 spec 의 Hook 3 / Hook 5 와 연관 (turn-end hook).
- **directive-status-enum.md** (예정) — directive status enum 정규화 (event-action-mapping §5-4 분리 후속).
- **rev-e2e-3-stages.md** (`docs/features/rev-e2e-3-stages.md`) — rev 3단계 e2e. 본 spec 항목 6 의 rev QA hook 보강.

## 변경 이력

- 2026-05-26 — 최초 작성 (plan sub-agent, 사용자 헌법 inject 2026-05-26 12:16 + 12:20 통합 + 강조 정정).
