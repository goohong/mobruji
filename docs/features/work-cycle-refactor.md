---
feature: work-cycle-refactor
slug: work-cycle-refactor
status: approved
owner: @goohong
scope: infra
related_issues: [1074]
related_prs: [1077]
last_reviewed: 2026-05-26
---

# Work Cycle Refactor — 5단계 마이그 (운영 break 0)

## 1) 개요 (What / Why)

ADR-0019 + `event-action-mapping.md` 결정의 실행 plan. 사용자 strategic 정정 ("agent 망각 의존 → 코드 강제 + 단순화") 을 운영 break 0 으로 적용하는 5단계 마이그.

- **What**: 단계 1-5 각각 1-3 PR 묶음. 단계 마다 e2e 검증 + rollback 명시. 시간 (단계 1 = 이번 주 / 단계 5 = 4-6주 후).
- **Why**: 한 번에 rewrite 시 운영 break 위험 — 6 loop 동시 변경 = bot.py 재시작 회귀 spike. 단계별 점진 적용으로 회귀 격리.

## 2) 사용자 시나리오

각 단계 머지 후 사용자 입장 검증 시나리오:
- 단계 1 후: directive 메시지 → 5초 내 forum-post + jsonl 등록 (현 ~10분).
- 단계 2 후: nmae 가 같은 룰 두 번 정정받는 빈도 ↓ (메모리 reduce → 코드 hook 강제).
- 단계 3 후: helper turn 컨텍스트 평균 줄 수 ↓ (wrapper 자동화 → CLAUDE.md §12-3 6-step 명시 제거 가능).
- 단계 4 후: 매 PR 머지마다 validation 매트릭스 통과 — critical event hook 회귀 0 보장.
- 단계 5 후: 회고 ADR — 32% 후속 PR 비율 측정값 변화 + 메모리 67 → 50 건 목표 달성 여부.

## 3) 요구사항

### 기능 요구사항
- [ ] 단계 1: directive auto-detect / sync rate-limit + 404 / utf-8 graceful / PR merge webhook / handoff template — 코드 enforce 5건.
- [ ] 단계 2: 메모리 reduce 10건 → 코드 hook reference + CLAUDE.md 3 sections 폐기.
- [ ] 단계 3: 스크립트 통합 — helper-turn-start.sh / nmae 측 wrapper → bot.py 자동 처리. tools/* 17 → 9 목표 (47% ↓).
- [ ] 단계 4: validation 매트릭스 — 12 event 각 hook coverage 자동 통합 테스트.
- [ ] 단계 5: 청소 + 회고 — 폐기 git 제거 + 회고 ADR.

### 비기능 요구사항
- 운영 break 0 — 각 단계 PR 별 e2e 검증 + rollback 가능.
- wall-clock 정정 5분 → 5초 (event hook = realtime).
- 토큰 ↓ (메모리 reduce + CLAUDE.md 폐기로 sub-agent 컨텍스트 평균 ↓).

## 4) 범위 / 비범위

### 포함
- 5단계 마이그 spec (PR 별 작업 분할 §6)
- rollback plan (단계 별)
- 검증 절차 (단계 별 e2e)
- 폐기 후보 명시 (메모리 10건 / CLAUDE.md 3 sections / 스크립트 8개)

### 제외 (Out of Scope)
- 본 spec 안에서 구현 코드 변경 — 단계 별 별도 PR.
- 도메인 코드 (be/fe) 변경 — 본 마이그 = 운영 체계 only.
- 컨벤션 (말투/표기) 메모리 — 유지 (ADR-0019 §4.1 원칙 3).

## 5) 설계

### 5-1) 단계 1 — 이번 주, 진행 중 (code enforce 5건)

**목표**: critical event 중 코드 hook 부재인 5건 우선 enforce.

| # | event hook | 대상 PR | 담당 사이클 |
|---|---|---|---|
| 1.1 | `user_message_classified` heuristic — bot.py `on_message` 안 directive auto-detect + `--forum-post directive` 자동 호출 | feat(infra): directive auto-detect classifier (event 2 hook) | helper |
| 1.2 | `directive_board_sync_loop` rate-limit + 404 graceful — message_id stale 시 jsonl backfill + #모부르지 alert + **directive status enum 정규화 spec 분리** (`docs/features/directive-status-enum.md` 신규 + 기존 30 entries 자동 정규화 best-effort) | fix(infra): directive sync 404/rate-limit graceful + backfill + status enum 분리 spec | be |
| 1.3 | discord-reply.sh utf-8 graceful — bash heredoc 안 한글 encoding 사고 가드 | fix(infra): discord-reply.sh utf-8 fallback (현 forum-post 에서 한글 깨짐 사례) | helper |
| 1.4 | GitHub `pull_request.closed && merged` webhook → bot.py /webhook endpoint → event 5 즉시 trigger (5분 race → 5초) | feat(infra): GitHub webhook listener for pr_merged (event 5 realtime) | be |
| 1.5 | `clear-pre-hook.sh` 구현 (PR #1066 spec) — directive 대기 0건 check + handoff template auto-write | feat(infra): clear-pre-hook implementation (event 8 enforce) | helper |

**검증 (단계 1 e2e)**:
- 사용자 directive 메시지 1건 보내 → 5초 내 forum-post + jsonl 등록 자동 확인 (stopwatch).
- PR 1건 머지 → 10초 내 directive jsonl status `완료` PATCH 자동 확인 (현 5분 polling).
- `/clear` 시도 — directive 대기 1건 있으면 차단 / 0건이면 handoff 파일 생성 확인.

**Rollback**:
- 각 PR 별 revert 가능. 단계 1 모든 PR revert 시 status quo 복귀 — 사용자 메시지 / 메모리 / 사이클 모두 정상 작동 (코드 hook 추가만 — 기존 path 안 깨뜨림).

### 5-2) 단계 2 — 메모리 reduce 10건 + CLAUDE.md 3 sections 폐기

**목표**: 단계 1 코드 hook 강제 후, 학습 의존인 메모리 룰 promote → 코드 hook reference 만 남기고 본문 제거.

**메모리 폐기 후보 10건** (코드 hook 강제로 학습 불요):

| # | 메모리 파일 | 강제 hook | 폐기 사유 |
|---|---|---|---|
| 2.1 | `helper/feedback_helper_directive_board.md` | event 2 (directive auto-detect, 단계 1.1) | helper LLM 가 directive 라벨 결정 안 해도 코드가 자동 분류 |
| 2.2 | `nmae/feedback_keep_4_cycles_active.md` | event 4 + watchdog (이미 강제) | 4 사이클 launch 누락 = watchdog 가 자동 정정 |
| 2.3 | `nmae/feedback_nmae_status_channel.md` | `nmae-discord-push.sh` wrapper (이미 강제) | wrapper 만 호출하면 채널 라우팅 자동 |
| 2.4 | `helper/feedback_helper_thread_usage.md` | event 1 `on_message` + helper-turn-start.sh (이미 강제) | thread 생성/stream 자동 |
| 2.5 | `helper/feedback_helper_reply_target_freeze.md` | helper-turn-start.sh (이미 강제, #987) | freeze 자동 |
| 2.6 | `nmae/feedback_directive_board_update_flow.md` | event 5 (단계 1.4 webhook) + sync_loop | PR 머지 → PATCH 자동 |
| 2.7 | `helper/feedback_helper_subagent_launch_thread.md` | helper-turn-start + last-launch-thread file passthrough (이미 강제, #1021) | per-launch thread 자동 |
| 2.8 | `helper/feedback_helper_launch_thread_file_passthrough.md` | discord-reply.sh `--auto-ack-thread` 자체가 파일 write 의무 | 파일 passthrough 코드 강제 |
| 2.9 | `nmae/feedback_nmae_no_manual_status_push.md` | cron digest 가 cover (이미 강제) | manual push 자체가 필요 없음 |
| 2.10 | `helper/feedback_helper_ack_removed.md` | bot.py auto-ack 1초 (이미 강제, #963) | helper LLM 가 ack push 안 함 = 코드 default |

**CLAUDE.md 3 sections 폐기 후보**:
- §11-2 (watchdog inject 대응 의무 절차) — 코드 hook 으로 강제 가능 (nmae LLM 학습 의존 폐기)
- §12-3 (매 사용자 메시지마다 6 step) — helper-turn-start.sh 가 자동 수행, step 명시 불요
- §11-9 (nmae manual status push 자제) — cron digest 강제로 manual 자체 필요 없음

**검증 (단계 2 e2e)**:
- 메모리 폐기 10건 후 sub-agent prompt 컨텍스트 평균 줄 수 측정 (현 baseline 대비).
- helper LLM 가 학습 없는 신규 세션에서도 위 룰 자동 적용되는지 — bot.py log 로 행동 검증.

**Rollback**:
- 메모리 git revert — 학습 패턴 즉시 복구 (코드 hook 와 메모리 룰은 OR 관계 — 둘 다 있어도 무해).
- CLAUDE.md sections 복구 — 같은 revert.

### 5-3) 단계 3 — 스크립트 통합 (tools/* 17 → 9 목표)

**목표**: helper-turn-start.sh / nmae 측 wrapper / discord-reply.sh mode flag 일부 → bot.py 자동 처리로 흡수. agent 가 명령 직접 호출 안 함 = 학습 부담 ↓.

**통합 후보** (단계 3 분리 PR):
- `helper-turn-start.sh` (231 줄 추정) → bot.py `on_message` 안 자동 (helper LLM turn 시작 시 별 호출 불요)
- `nmae-discord-push.sh` wrapper → bot.py `on_message` 가 nmae tmux output 감지 (`===CTX:...===`) 시 자동 라우팅
- `agent-launch-wrapper.sh` → 유지 (Agent tool 외부 — bot.py 흡수 X)
- `discord-reply.sh` mode flag 일부 → 유지 (helper LLM 가 명시 호출 필요)

**최종 tools/* inventory (목표)**:
- `agent-launch-wrapper.sh` (유지)
- `cycle-status/{update.sh, validate.sh}` (유지)
- `discord-daemon/{bot.py, discord-reply.sh, claude_usage_tracker.py, directive_board_sync.py}` (유지)
- `rev-queue/rev-queue.sh` (유지)
- `audio-analysis/analyze.py` (도메인 — 별도)

폐기: `helper-turn-start.sh`, `nmae-discord-push.sh`, `setup-gcp-systemd.sh` (운영 1회), `setup-launchagent.sh` (운영 1회), `deploy/ncp-bootstrap-dev.sh` (운영 1회) → bot.py / systemd unit 통합.

**검증 (단계 3 e2e)**:
- helper LLM 가 turn-start.sh 호출 없이도 자동 작업 시작 — bot.py log 로 검증.
- nmae tmux output 감지 자동 라우팅 — DIGEST channel push 자동 확인.

**Rollback**:
- 각 스크립트 file restore (`git checkout HEAD~ -- tools/discord-daemon/helper-turn-start.sh`) — 즉시 복구. bot.py 흡수 코드는 feature flag 로 toggle.

### 5-4) 단계 4 — Validation 매트릭스 (자동 통합 테스트)

**목표**: 12 event 의 코드 hook coverage 자동 검증 — 회귀 0 보장.

**테스트 항목**:
- 각 event 의 trigger 지점 (`grep`) 가 코드에 존재
- 각 event 의 action chain 호출 (`mock + log assertion`)
- State machine transition 강제 (`directive jsonl status enum 5 값만 허용`)
- Failure mode recover hook 작동 (`mock failure → recover hook 호출 확인`)

**구현**:
- `tools/discord-daemon/tests/test_event_action_matrix.py` — pytest 통합 테스트 모음
- GitHub Actions `.github/workflows/event-matrix.yml` — 매 PR 별 자동 실행
- CI fail 시 머지 차단 (단계 1 의 rev-gate 와 함께)

**검증**:
- 단계 1-3 모든 hook 이 매트릭스에 등재 — 회귀 PR 발생 시 fail 확인.
- 신규 event 추가 시 매트릭스 자동 확장 — event-action-mapping.md §5-1 표가 source.

**Rollback**:
- 테스트 워크플로우 disable (`.github/workflows/event-matrix.yml` rename) — 즉시 머지 가능 복귀.

### 5-5) 단계 5 — 청소 + 회고

**목표**: 폐기 git 제거 + 회고 ADR.

**작업**:
- 단계 2 메모리 10건 git rm (이미 폐기됐지만 file 남아있음 — 단계 2 PR 에서 file 자체 삭제 안 한 경우)
- 단계 3 폐기 스크립트 5건 git rm
- CLAUDE.md 3 sections 본문 삭제 (단계 2 후 reference 만 남은 경우)
- 회고 ADR (`docs/decisions/0020-event-driven-v2-retrospective.md`) — 단계 1-4 적용 후 측정값:
  - 후속/회귀/drift 키워드 commit 비율 변화 (baseline 32% → 목표 < 15%)
  - 메모리 67 → 50 건 목표 달성 여부
  - sub-agent 평균 컨텍스트 줄 수 변화
  - wall-clock 정정 시간 변화 (5분 → 5초)

**검증**:
- 본 단계는 정리만 — 회귀 0 보장 단계.

**Rollback**:
- 정리 PR revert — file restore.

### 5-6) DB 마이그레이션 / 프론트엔드
- 해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

| 단계 | PR 개수 추정 | 담당 사이클 |
|---|---|---|
| 단계 1 | 5 PR | helper 2 / be 2 / nmae 1 |
| 단계 2 | 2 PR (메모리 reduce + CLAUDE.md 폐기) | helper |
| 단계 3 | 3 PR (스크립트 통합 별 PR) | be 1 / helper 2 |
| 단계 4 | 1 PR (validation 매트릭스) | be |
| 단계 5 | 2 PR (정리 + 회고 ADR) | plan |
| **합계** | **~13 PR** | 4-6주 |

## 7) 테스트 전략

- **단위**: 각 hook 별 mock 테스트 (단계 1 별 PR 에 포함)
- **통합**: 단계 마다 e2e — 사용자 directive → 5초 내 등록 자동 (stopwatch). PR 머지 → 10초 내 jsonl PATCH 자동.
- **회귀**: 단계 4 validation 매트릭스 = 12 event coverage 자동 검증. 회귀 발생 시 매 PR CI fail.
- **운영**: 각 단계 PR 머지 후 24h 관찰 — sudo journalctl 로 daemon 안정성 확인.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 단계 3 helper-turn-start.sh 통합 시 graceful fallback | (a) bot.py 자동 + script 유지 (이중) (b) bot.py only | nmae / 단계 3 |
| Q2 | 단계 1.4 GitHub webhook secret 관리 | (a) `.env` 추가 (b) GitHub App | nmae / 단계 1.4 |
| Q3 | 단계 5 회고 ADR 측정 기간 | (a) 단계 4 후 2주 (b) 단계 4 후 1개월 | nmae / 단계 5 |

## 9) 결정 로그

- 2026-05-24: 초안 작성 (status=draft) — plan sub-agent, 사용자 strategic 정정 #1074. ADR-0019 + event-action-mapping.md 의 마이그 plan.
