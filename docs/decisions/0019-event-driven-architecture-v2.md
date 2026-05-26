---
status: accepted
date: 2026-05-24
deciders: plan sub-agent (사용자 strategic 정정 #1074)
related_issues: [1074]
related_prs: [1077]
supersedes: []
superseded_by: []
---

# ADR-0019: 작업 체계 event-driven 아키텍처 v2 — agent 망각 의존 폐기

## Context

사용자 strategic 정정 (2026-05-24, 이슈 #1074):

> "내가 제안한 지금의 작업체계를 집행하는 것에 있어서 주먹구구식으로 결과만을 위한 보수 설계를 하지는 않았는지(파이프 한구멍막으면 다른 구멍터지고) 좋은 아키텍쳐를 만들었는지(중요한 작업은 스크립트, 봇으로 강제 -> 컨벤션이나 말투등은 메모리로 저장 등 우선순위 적절한가?)"
>
> "어느시점에는 이걸 해야하고 이걸 체크해야하는 구나. 끝나면 ~하라했지를 기억에의존할게 아니라 체계가 잡혀있어야한다고 봐"

### 1. 현 시스템 audit (실측 inventory, 2026-05-24)

| 영역 | 수치 | 비고 |
|---|---|---|
| `tools/*` 스크립트 | **17** | sh 10 + py 6 + bot.py 1 (별도) |
| `tools/discord-daemon/bot.py` | **3403 줄, 6 _loop** | digest / context_auto_clear / cycle_idle_watch / rev_post_merge_audit / claude_usage_watch / directive_board_sync |
| `tools/discord-daemon/discord-reply.sh` | **1085 줄** | mode flag 8종 (forum-post / forum-edit / forum-retag / cycle-channel / status-channel / auto-thread / auto-ack-thread / no-reply) |
| `CLAUDE.md` | **434 줄, 16 sections** | §11 nmae / §12 helper / §13 sub-agent + 공통 |
| `docs/ai-harness/**` | **16 문서, 4019 줄** | 룬북 + spec + 컨벤션 |
| `docs/features/**` | **41 spec** | feature 단위 living 명세 |
| 메모리 | **67 파일** | common 10 / nmae 16 / helper 18 / subagent 5 / rev 3 / workflow 3 + index/handoff |
| `directive-board.jsonl` | **30 entries** | 사용자 지시 source-of-truth |
| 최근 50 commit | **16건 (32%)** | "후속/회귀/drift/사고/가드" 키워드 — 파이프 누수 chain |

### 2. 분류 표 — 항목 × 현 enforcement × 본질적 importance × mismatch

> **mismatch 정의**: critical (사고 시 service downtime / 사용자 신뢰 손실) 인데 메모리/CLAUDE.md 룰만 의존 → 🔴 / critical 인데 코드 강제 → 🟢 / 보조 항목 (말투/표기) 인데 메모리 의존 → 🟢 / 보조 인데 코드 강제 → 🟡 (과잉).

| # | 항목 | 현 enforcement | 본질적 importance | mismatch |
|---|---|---|---|---|
| 1 | 사용자 directive forum 등록 | helper LLM 기억 + 메모리 룰 ([[feedback-helper-directive-board]]) | **critical** (등록 누락 시 사용자 지시 lost) | 🔴 코드 강제 필요 |
| 2 | directive jsonl PR 머지 → status PATCH | 메모리 룰 ([[feedback-directive-board-update-flow]]) + `directive_board_sync_loop` (5분 polling) | **critical** | 🟡 부분 enforce — webhook 직접 trigger 없음, 5분 race window |
| 3 | sub-agent launch 직후 cycle-status set-active | `agent-launch-wrapper.sh` 강제 (#1008) | **critical** (watchdog idle 분류 source) | 🟢 적절 |
| 4 | sub-agent 완료 → cycle-status set-idle + note | nmae LLM 기억 + 메모리 룰 ([[feedback-keep-4-cycles-active]]) + watchdog 강제 | **critical** | 🟡 watchdog 가 idle 만 detect — set-idle note 누락 자체는 nmae 책임 |
| 5 | PR 머지 → 다음 사이클 launch | nmae LLM 기억 + watchdog 5분 polling | **critical** | 🟡 5분 race window + nmae 망각 시 6회 inject 사례 (#970) |
| 6 | helper 사용자 메시지 queue append | bot.py `on_message` 자동 + helper LLM 기억 | **critical** | 🟢 적절 (bot 측 코드 강제) |
| 7 | helper 본답 자동 reply | discord-reply.sh `--no-reply` 옵트아웃 default | **critical** | 🟢 적절 |
| 8 | helper turn-start (target freeze + cycle-status 요약 + queue 표시) | `helper-turn-start.sh` 강제 (#1014) | **critical** | 🟢 적절 |
| 9 | sub-agent thread_id 전달 (per-launch thread) | `last-launch-thread.txt` 파일 passthrough (#1021) | **critical** (hallucination 사고 박제) | 🟢 적절 |
| 10 | `/clear` 직전 doc-check 4-way | CLAUDE.md §15 + helper LLM 기억 + clear pre-hook spec (PR #1066 미머지) | **critical** (lost handoff) | 🔴 clear pre-hook 미구현 — 메모리만 의존 |
| 11 | rev 3단계 e2e (단계 1 머지 게이트) | `rev-gate.yml` workflow + rev sub-agent 매 사이클 `rev-queue.sh all` | **critical** | 🟢 적절 |
| 12 | rev 단계 2 develop 후 audit | `rev_post_merge_audit_loop` (cron) + rev-queue 큐 | **critical** | 🟢 적절 |
| 13 | 보호 영역 변경 시 needs-human-review 라벨 | `.github/workflows/auto-label.yml` (보호 영역 grep) | **critical** | 🟢 적절 |
| 14 | session:* 라벨 부착 | sub-agent LLM 기억 + auto-label.yml fallback (이슈 #1002) | 보조 | 🟢 (1002 후 자동화 충분) |
| 15 | watchdog inject 시 nmae 4단계 대응 | CLAUDE.md §11-2 + nmae LLM 기억 | **critical** | 🔴 LLM 망각 시 무한 inject loop (사례: #970 6회 연속) |
| 16 | Discord 정중체 ("~합니다") | CLAUDE.md §4 + 메모리 ([[feedback-discord-tone-formal]]) | **보조** (UX) | 🟢 적절 (코드 강제 과잉) |
| 17 | 줄임 표현 금지 ("별 sub" → "별도 sub-agent") | 메모리 ([[feedback-discord-tone-formal]]) | **보조** | 🟢 적절 |
| 18 | maestro 약어 (mmae/nmae) | CLAUDE.md §4 + 메모리 | **보조** | 🟢 적절 |
| 19 | 런북 표기 "런북" (NOT "룬북") | 메모리 ([[feedback-runbook-spelling]]) | **보조** | 🟢 적절 |
| 20 | 도메인 모델 §7 변경 금지 | CLAUDE.md §10 + 메모리 ([[feedback-domain-model-section-7]]) | 보조 | 🟢 적절 |

**Mismatch 합계**: 🔴 3 (항목 1 / 10 / 15) + 🟡 3 (항목 2 / 4 / 5) = 6/20 (30%). 모두 **사이클 / directive flow / nmae 망각** 도메인. 보조 (말투/표기) 영역은 모두 🟢 (적절 분류).

### 3. "파이프 한 구멍" chain 사례 (최근 1주, "후속/회귀/drift/사고/가드" 키워드 grep)

| # | chain | 근본 원인 |
|---|---|---|
| 1 | turn-start freeze (#987) → directive PATCH silent (#1047) → forum 마이그레이션 → message_id stale → sync_loop 404 (#1068 직전) | 매번 한 곳 patch 시 인접 영향 검증 X — event 명시 부재 |
| 2 | `.env.example` 변경 → production .env drift (#1025) → discord-reply.sh `read_env_value` no-match crash (#1040 후속) → sync ops 룬북 박제 (#1039) | env 변경 event 후속 action chain (sync + graceful) 미연결 |
| 3 | sub-agent thread_id hallucination → file passthrough (#1021) → `last-launch-thread.txt` 99999 invalid (현재 본 작업에서도 발견) → graceful fallback skip | thread_id 라이프사이클 (생성 → write → read → 만료) event chain 미정의 |
| 4 | mutation race (Like/Bookmark/Rec/voice-range/history 등) → PR #985 / #989 / #993 / #995 / #1029 / #1033 / #1045 / #1048 / #1054 9 회귀 PR | 단일 race 가드 → 인접 페이지 동일 패턴 미점검 (rev 매트릭스 grep 미적용) |
| 5 | sub-agent session 라벨 누락 (43 PR 중 7건만 부착, 16%) → auto-label.yml fallback (#1002) | sub-agent LLM 기억 의존 — 명시 부착 학습 안 됨, 코드 fallback 만이 구원 |

### 4. 토큰 hot path 측정 (proxy)

- `~/.claude/projects/-home-mobruji-mobruji/`: **135 MB** (helper/nmae/sub-agent 누적 transcript)
- `~/.mobruji/` 상태 디렉토리: **11 MB** (queue / thread / target / status)
- 메모리 67 파일 / common 10건 (모든 actor 항상 로드) — sub-agent prompt 안 critical
- CLAUDE.md 434 줄 — 모든 sub-agent 가 매 사이클 컨텍스트에 로드

**시사**: 메모리 영역 (말투/표기, 보조 분류 5건) 은 코드 강제 비용보다 컨텍스트 비용이 작음 — 유지 OK. critical 분류 mismatch 6건이 토큰 (재시도 / chain PR) + wall-clock (망각 inject loop) 의 주범.

## Decision

**critical = 코드/스크립트/봇 강제. 보조 = 메모리. 망각 방지 = 외부 진실 (jsonl / cycle-status / git) 만 신뢰. agent 컨텍스트 ephemeral 전제.**

### 4.1 핵심 원칙 (4개)

1. **State 는 외부 진실** — agent reasoning state 신뢰 금지. 모든 핵심 state 는 (a) `~/.mobruji/*.jsonl` / (b) `~/.mobruji/cycle-status.json` / (c) GitHub (labels / PR base / merged_at) 중 하나에 영속.
2. **Event 가 action 을 trigger** — "X 끝나면 Y 하라" 는 agent prompt rule 이 아니라 코드 hook (bot.py loop / wrapper.sh / GitHub webhook). agent 가 망각해도 hook 이 catch up.
3. **메모리 = 보조 컨벤션 only** — 말투 / 표기 / 도메인 약어 / 컨벤션 (final 키워드 등). critical 강제 룰은 메모리 제거 + 코드 hook 으로 promote.
4. **단순 산출물 / 명시 산출물** — event ↔ action 1:1 표가 single source. 다이어그램은 1개. 룰 룬북 텍스트는 hook 의 reference 만.

### 4.2 enforcement 분류 표 (자동 결정 기준)

| importance | recovery cost | enforcement |
|---|---|---|
| critical | service downtime / 사용자 신뢰 손실 | **코드/스크립트/봇 강제** (bot.py loop / wrapper.sh / GitHub workflow) |
| 보조 | redo 1-2 PR / 가독성 ↓ | 메모리 룰 (학습 의존 OK) |
| 메타 | doc drift / 사고 후 박제 | CLAUDE.md / docs (참조만, enforcement 0) |

## Alternatives Considered

### 거부 1: status quo (현 시스템 유지 + 사고마다 보수 PR)
- **거부 사유**: 32% commit 이 후속 PR — 사고 누적 = 선형 증가. 사용자 정정 인용 "주먹구구식 보수 설계" 가 정확한 진단.
- 보수 PR 1건 평균 wall-clock 4-6h (rev + 머지 + watchdog 정정) → 연간 100+ 후속 PR 추산 시 600+ h 손실.

### 거부 2: 완전 rewrite (bot.py 분리 + maestro orchestration 외부화)
- **거부 사유**: 운영 break 0 우선. 사용자 정정 "단순화" 와 충돌 (rewrite = 일시적 복잡도 spike).
- 현 6 loop 가 기능적 분리는 적절 — 결함은 event hook 부재이지 코드 구조 아님.

### 거부 3: 메모리 룰 전면 코드화 (critical / 보조 무관)
- **거부 사유**: 보조 (말투) 까지 코드 강제 = 과잉. 토큰 hot path 측정 결과 메모리 5건 (말투/표기) 누적 100 줄 미만 — 코드 hook 추가 비용 (테스트 / CI / 유지보수) 보다 작음.
- 사용자 정정 인용 "컨벤션이나 말투등은 메모리로 저장 ... 우선순위 적절한가?" → 우선순위 OK 확정.

## Consequences

### 긍정
- **사고 chain 감소**: critical event 6건 각각 코드 hook → 32% 후속 PR 비율 ↓ 목표 (단계 4 마이그 후 측정).
- **토큰 ↓**: critical 룰 메모리 제거 (단계 2) → sub-agent 매 사이클 컨텍스트 평균 줄 수 감소 (현재 sub-agent prompt 첫 머리에 12-template 만 reference, 본 ADR 후 메모리 직접 ref 도 감소).
- **wall-clock ↓**: directive PATCH webhook 직접 (5분 race → 5초) + clear pre-hook 강제 → handoff 누락 0.
- **agent 망각 무관**: 같은 룰 두 번 정정 받는 [[feedback-session-persist-rules]] 위반 패턴 차단 — 메모리 학습 의존 0.

### 부정
- bot.py 추가 loop / wrapper / hook = 단기 코드 증가. 단계 4 (validation) 매트릭스로 회귀 0 보장.
- 메모리 reduce (10건) 후 일부 룰은 코드 hook 직접 reference (예: helper-turn-start.sh 안 comment) — 메모리 ↔ 코드 sync 가 새 doc-check 항목.

### 마이그 (별도 spec)
- `docs/features/event-action-mapping.md` — event × action 표 + state machine + failure modes (Phase 2)
- `docs/features/work-cycle-refactor.md` — 5단계 마이그 + rollback (Phase 3)

## References

- 이슈 #1074 — 본 ADR + 통합 PR
- `docs/ai-harness/16-memory-vs-code-enforcement.md` — 메모리 vs 코드 책임 분리 spec (PR #1060 머지)
- `docs/features/clear-pre-hook.md` — `/clear` 직전 doc-check 강제 (PR #1066 머지)
- `docs/features/event-action-mapping.md` — 본 ADR 의 동반 spec
- `docs/features/work-cycle-refactor.md` — 본 ADR 의 마이그 spec
- 메모리 [[feedback-session-persist-rules]] [[feedback-verify-and-iterate]] [[feedback-autonomous-default]] — agent 학습 한계 박제

## 변경 이력
- 2026-05-24 — 최초 작성 (plan sub-agent, 사용자 strategic 정정 #1074).
