# Memory ↔ Code: 책임 분리 + Promote 트래킹 (구 13+16 통합)

> 룰의 **"왜"** 는 메모리 (`~/.claude/projects/.../memory/`) + CLAUDE.md 가 담고, **"어떻게"** 는 코드 (hook / wrapper script / test) 가 강제한다.
> 본 문서는 mobruji multi-agent 운영에서 학습 의존 룰이 반복 위반되는 사고 (#1008 / #1014 / #1015 / #1055 등) 후속으로 박제된 분리 원칙을 정의한다.
> 메모리 actor: 모든 actor (common 적용).

## 1) 배경

### 1-1) 사고 패턴 — 학습 의존 룰의 반복 위반

다음 메모리 룰들이 **명시 학습 후에도 반복 위반**된 사례:

| 메모리 룰 | 위반 사고 | 학습 채널 | 위반 원인 |
|---|---|---|---|
| `feedback-keep-4-cycles-active` | nmae 가 sub-agent 완료 통지 받고 다음 launch 까먹음 (#970, #972) | CLAUDE.md §11-1 + nmae/ 디렉토리 | "다음 turn 에서 처리하면 되겠지" 우선순위 밀림 |
| `feedback-sub-agent-launch-mandatory` | nmae 가 자체 reasoning 으로 코드 작업 시도 (다수) | sub-agent prompt template §1 | "내가 직접 하는 게 빠르다" 판단 |
| `feedback-helper-empty-message-classify` | helper 가 ZWSP-only 메시지 "비어있다" 단정 (사용자 인계 #11) | helper/ 디렉토리 | visible char count 만으로 판단 |
| `feedback-sub-agent-no-user-wait` (#1015) | sub-agent 가 AskUserQuestion 사용 → 사용자 wait | subagent/ 디렉토리 | "허용된 우회" 학습 |
| `feedback-rev-e2e-always` | helper sub-agent 자율 머지가 rev 우회 (#945) | rev/ + workflow/ | rev gate 라벨 부재여도 머지 가능 |

공통 원인: **메모리 / CLAUDE.md 룰은 학습 의존**. LLM 이 매 turn 마다 모든 룰을 동시 active 로 유지하지 못한다. 컨텍스트 압박 / 우선순위 충돌 / "이번엔 예외" 판단 시 룰 누락 → 사용자 정정.

### 1-2) 해결 패턴 — 코드 강제

같은 룰을 **코드 (hook / wrapper script / test / workflow)** 로 옮기면 학습 무관하게 자동 강제 가능. 이미 mobruji 에 도입된 사례:

| 룰 | 학습 채널 (메모리) | 코드 강제 채널 | 효과 |
|---|---|---|---|
| watchdog inject 4단계 절차 | CLAUDE.md §11-2 + nmae/ | `tools/agent-launch-wrapper.sh` (#1008) — set-active + launch prompt emit 한 번에 | 학습 의존 ↓ (한 명령으로 단계 1-2 처리) |
| helper turn 시작 5 액션 | CLAUDE.md §12-3 + helper/ | `tools/discord-daemon/helper-turn-start.sh` (#1014) — target freeze + cycle 요약 + queue 표시 | 매 turn 첫 명령 1회 호출 = 5 액션 자동 |
| cycle-status.json 수동 편집 금지 | nmae/ feedback-cycle-status-json | `tools/cycle-status/update.sh` + `validate.sh` (#1003) — atomic write + 스키마 검증 | vim/jq 직접 편집 시 validate 실패 |
| rev e2e 머지 게이트 | rev/ feedback-rev-e2e-always | `.github/workflows/rev-gate.yml` (#945) — 라벨 + 코멘트 부재 시 PR fail | 라벨 없으면 머지 차단 |
| rev 매 사이클 큐 discovery | rev/ feedback-rev-queue-script | `tools/rev-queue/rev-queue.sh` (#952) — GitHub 라벨 = SoT | 메모리 학습 의존 X |
| cycle-status `note` 필드 의무 | nmae/ feedback-cycle-status-json | `validate.sh` STRICT mode (#956) — `CYCLE_REASON_REQUIRED=1` | watchdog 가 STRICT relaunch prompt inject |
| sub-agent launch thread 전달 | helper/ | `~/.mobruji/last-launch-thread.txt` 파일 passthrough (#1021) | thread_id hardcode 사고 회피 |
| discord-reply.sh read_env_value | helper/ | grep no-match graceful exit (#1040) | 빈 메시지 발송 차단 |
| `.env.example` ↔ production sync | workflow/ feedback-env-sync-ops | docs 절차 박제 (#1039) — 추후 sync 검증 스크립트 후보 | 사고 재발 방지 |

## 2) 책임 분리 룰

### 2-1) 메모리 + CLAUDE.md 의 영역 — "왜" (Why)

- **사고 배경**: 어떤 사용자 정정 / 운영 사고 / 도메인 결정이 룰의 존재 사유인가
- **위반 시 영향**: 이 룰 누락 시 어떤 시스템 사고가 발생하는가
- **의사결정 trail**: 대안 / 채택 사유 / trade-off
- **운영 가이드**: 코드 강제 채널과의 매핑 (어떤 hook / wrapper 가 이 룰을 강제하는가)

메모리 작성 시 점검:
- [ ] 사고 인용 (사용자 정정 문구 또는 PR 번호)
- [ ] 위반 시 직접 손실 (사용자 wait / 사이클 idle / 사고 재발 등)
- [ ] 코드 강제 가능한가 (가능 → 강제 채널 명시 / 불가능 → 학습 의존 인정 + frequency 조정)

### 2-2) 코드의 영역 — "어떻게" (How)

- **hook / wrapper script**: 매 turn 자동 실행되는 절차 (예: `helper-turn-start.sh`, `agent-launch-wrapper.sh`)
- **검증 스크립트**: 룰 위반을 즉시 fail 처리 (예: `cycle-status/validate.sh`, `.github/workflows/rev-gate.yml`)
- **테스트**: 회귀 가드 (예: `tools/discord-daemon/tests/test_thread_cleanup.py`)
- **단방향 파일 채널**: 학습 의존 우회 (예: `~/.mobruji/last-launch-thread.txt`, `~/.mobruji/helper-current-target.txt`)

코드 강제 도입 시 점검:
- [ ] 학습 의존 ↓ — LLM 이 룰을 까먹어도 자동 작동
- [ ] graceful fallback — 코드 강제 실패 시 사이클 깨지지 않음 (예: `helper-turn-start.sh` exit 1 안 함)
- [ ] 검증 — 도입 후 사고 재발 0건 확인 (수동 / cron / 로그)

## 3) 분리 패턴 카탈로그

### 패턴 A — wrapper script 가 다단계 절차 한 번에 (#1008, #1014)

**문제**: 룰이 N 단계 절차로 구성되어 단계별 학습 의존 — 한 단계라도 누락 시 다음 사이클에서 또 정정.

**해결**: wrapper script 가 N 단계를 한 명령으로 캡슐화. 사용자가 "wrapper 호출" 만 학습하면 N 단계 자동 수행.

**사례**:
- `tools/agent-launch-wrapper.sh <ws>` (#1008) — set-active + launch prompt emit 한 번. 학습 = "watchdog inject 받으면 wrapper 호출" 1줄.
- `tools/discord-daemon/helper-turn-start.sh` (#1014) — target freeze + cycle 요약 + user-presence + queue pending + 다음 액션 reminder 5 액션. 학습 = "매 turn 첫 명령" 1줄.

**관련 메모리**:
- `nmae/feedback-cycle-status-json` (cycle-status.json 헬퍼 사용)
- `helper/feedback-helper-reply-target-freeze` (target freeze wrapper 캡슐화)

### 패턴 B — 파일 passthrough 로 LLM hallucination 우회 (#1021)

**문제**: helper LLM 이 sub-agent launch prompt 에 thread_id 를 직접 hardcode → hallucinated 99999 같은 값 학습.

**해결**: helper 가 launch 직전 `~/.mobruji/last-launch-thread.txt` 에 atomic write → sub-agent 는 `discord-reply.sh --auto-thread` 호출 시 파일 read. LLM 이 thread_id 를 prompt 에 박지 않음.

**사례**: `~/.mobruji/last-launch-thread.txt` (#1021), `~/.mobruji/helper-current-target.txt` (#987), `~/.mobruji/helper-current-thread.txt` (turn-level).

**관련 메모리**: `helper/feedback-helper-launch-thread-file-passthrough` (#1021).

### 패턴 C — workflow gate 가 머지/push 차단 (#945)

**문제**: rev 사이클 통과 의무가 메모리 룰만 있고 코드 강제 부재 → helper sub-agent 가 reviewed:claude 라벨 없이 자율 머지.

**해결**: `.github/workflows/rev-gate.yml` 가 `reviewed:claude` 라벨 + rev 코멘트 부재 시 PR check fail → 머지 차단. 라벨 없으면 GitHub UI 가 머지 버튼 disable.

**사례**: `.github/workflows/rev-gate.yml` (#945), `.github/workflows/auto-label.yml` (보호 영역 라벨 강제, #124).

**관련 메모리**: `rev/feedback-rev-e2e-always`, `subagent/feedback-pr-base-develop`.

### 패턴 D — 검증 스크립트가 룰 위반 즉시 fail (#956, #1003)

**문제**: cycle-status.json 의 schema / future timestamp / note 부재 같은 위반이 silent → 다음 사이클까지 검출 안 됨.

**해결**: `tools/cycle-status/validate.sh` 가 매 update 후 sanity 검사 + STRICT mode 로 `note` 미명시 시 watchdog STRICT relaunch prompt inject.

**사례**: `tools/cycle-status/validate.sh` (#956), `tools/cycle-status/tests/` (회귀 테스트).

**관련 메모리**: `nmae/feedback-cycle-status-json`.

### 패턴 E — 단방향 큐가 학습 의존 ↓ (rev queue, helper queue)

**문제**: 매 사이클 무엇을 처리할지 메모리 학습으로 추적 → 우선순위 충돌 시 누락.

**해결**: 외부 SoT (GitHub 라벨 / jsonl 파일) 가 큐. 매 사이클 첫 명령으로 큐 fetch → 처리 → 라벨 / status 갱신 → 다음 사이클에서 자동 제외.

**사례**:
- `tools/rev-queue/rev-queue.sh all` (#952) — GitHub 라벨 = SoT
- `~/.mobruji/helper-queue.jsonl` — 사용자 메시지 큐 (`feedback-user-request-queue`)
- `~/.mobruji/cycle-status.json` — 4 워크트리 상태 SoT

**관련 메모리**: `rev/feedback-rev-queue-script`, `helper/feedback-user-request-queue`.

### 패턴 F — graceful fallback 으로 wrapper turn 안 깨짐

**문제**: wrapper script 가 exit 1 처리 시 helper turn 전체 깨짐 → 더 큰 사고 (#1043 메시지 발송 차단).

**해결**: wrapper script 는 항상 exit 0. 부분 실패 시 stderr 로 경고 + skip + 다음 단계 진행.

**사례**:
- `helper-turn-start.sh` (#1014) — 5 액션 중 일부 실패해도 turn 안 깨짐
- `discord-reply.sh --auto-thread` (#1021) — thread_id 부재 시 graceful skip
- `discord-reply.sh read_env_value` (#1040, #1043 정정) — grep no-match 시 graceful (이전엔 set -e + grep no-match 로 빈 메시지 발송)

**관련 메모리**: `workflow/feedback-env-sync-ops`, `helper/feedback-discord-reply-script`.

## 4) 사용자 정정 → 룰 박제 결정 트리

사용자 정정 받았을 때 (메모리 추가 vs 코드 강제 vs 둘 다) 결정 절차:

```
사용자 정정 발생
   ↓
[Q1] 같은 사고 재발 가능성?
   ├─ Low (1회성) → 메모리 추가만 (학습 의존 인정)
   └─ High (반복 가능) → Q2
       ↓
   [Q2] 코드 강제 가능?
       ├─ Yes (hook / wrapper / workflow / test 적용 가능) → 코드 강제 + 메모리 (왜)
       │   ↓
       │  [Q3] graceful fallback 가능?
       │   ├─ Yes → 코드 강제 도입
       │   └─ No (정확성 critical) → 코드 강제 + alert (사용자 가시화)
       └─ No (LLM 판단 영역) → 메모리 강조 + 반복 위반 마커
           ↓
       [Q4] 메모리 학습 후에도 재위반?
           ├─ Yes (3회 이상) → 코드 강제 재검토 (창의적 hook 설계)
           └─ No → 학습 의존 유지
```

### 4-1) 결정 트리 예시 적용

| 정정 사고 | Q1 | Q2 | Q3 | 결정 | 채널 |
|---|---|---|---|---|---|
| #1008 watchdog inject 4단계 누락 | High (자주 반복) | Yes (wrapper script) | Yes | 코드 강제 + 메모리 | `agent-launch-wrapper.sh` + `feedback-cycle-status-json` |
| #1014 helper turn 5 액션 누락 | High | Yes (wrapper) | Yes | 코드 강제 + 메모리 | `helper-turn-start.sh` + helper/ 메모리 |
| #1015 AskUserQuestion 우회 | High | No (LLM 도구 사용 판단) | - | 메모리 STRICT + 반복 마커 | `feedback-sub-agent-no-user-wait` STRICT mode |
| #1024 줄임 표현 / 비문 | Medium | No (LLM 자연어 생성 영역) | - | 메모리 + 사용자 정정 마커 | `feedback-discord-tone-formal` 줄임 표현 금지 추가 |
| #1039 .env.example ↔ production drift | High (배포 마다 reusable) | Partial (sync 스크립트 가능, 미도입) | Yes | 메모리 + 후속 sync 스크립트 후보 | `feedback-env-sync-ops` |
| #1043 nmae manual status push 노이즈 | High | Partial (cron digest 가 cover) | Yes | 메모리 + 채널 분리 코드 | `feedback-nmae-no-manual-status-push` + DIGEST 채널 분리 |

## 5) 운영 가이드 — 새 룰 박제 시 절차

### 5-1) 룰 작성 전

1. 사용자 정정 인용 + 사고 트리거 PR/이슈 번호 수집
2. §4 결정 트리 적용 → 메모리 / 코드 / 둘 다 결정
3. 코드 강제 채택 시 — 어떤 채널 (hook / wrapper / workflow / test) 인가 명시

### 5-2) 메모리 작성

- 디렉토리 분류 (common / nmae / helper / subagent / rev / workflow)
- frontmatter `metadata.actor` 정확히
- 사고 인용 (사용자 정정 문구 또는 PR 번호) 의무
- 코드 강제 채널 있으면 "Code 강제: <wrapper script 경로>" 라인 추가

### 5-3) 코드 강제 도입

- wrapper script — `tools/<actor>/<command>.sh` 위치 (예: `tools/discord-daemon/helper-turn-start.sh`)
- workflow — `.github/workflows/<name>.yml` (보호 영역 라벨 부착 의무)
- 테스트 — `tests/<module>/test_<feature>.py` 회귀 가드
- graceful fallback 필수 — exit 1 신중

### 5-4) CLAUDE.md 갱신

- 룰의 포인터 1-2줄만 CLAUDE.md 에 박고 상세는 docs/메모리에 위임
- actor 별 섹션 (§11 nmae / §12 helper / §13 sub-agent / §14-15 doc-check) 위치 결정

### 5-5) doc-check 4-way 일치 (`/clear` 직전 의무)

새 룰 박제 후 다음 세션 시작 전 4-way 일치 확인:
1. 메모리 파일 — 디렉토리 + actor 정확
2. CLAUDE.md 포인터 — 1-2줄 추가
3. docs/ai-harness — 절차/spec 상세 (해당 시)
4. docs/features — feature spec 갱신 (해당 시)

## 6) 학습 의존 vs 코드 강제 — trade-off

### 6-1) 학습 의존의 장점

- 도입 비용 ↓ (메모리 1 파일 추가만)
- 유연한 적용 (LLM 이 컨텍스트 보고 판단)
- 룰 변경 비용 ↓ (메모리 edit 만)

### 6-2) 학습 의존의 단점

- 반복 위반 risk (LLM 우선순위 / 컨텍스트 압박)
- 검증 어려움 (위반 후 사용자 정정 받아야 발견)
- /clear 후 누락 risk (메모리 로드 의존)

### 6-3) 코드 강제의 장점

- 학습 무관 자동 작동
- 회귀 0 보장 (테스트 / workflow gate)
- 사고 재발 차단 (silent 위반 X)

### 6-4) 코드 강제의 단점

- 도입 비용 ↑ (wrapper / workflow / 테스트 작성)
- 유연성 ↓ (예외 케이스 대응 시 코드 수정)
- 디버깅 비용 (graceful fallback 실패 시 turn 깨짐 가능)

### 6-5) 채택 가이드라인

- **High frequency / High criticality** (사고 영향 큰 룰) → 코드 강제 + 메모리
- **Low frequency / High criticality** (1회성이지만 영향 큰 룰) → 메모리 STRICT + alert
- **High frequency / Low criticality** (자주 위반되지만 영향 작음) → 메모리 + 반복 위반 마커
- **Low frequency / Low criticality** → 메모리만

## 7) 관련 문서

- `CLAUDE.md §4 비협상 룰` — 룰의 첫 진입점
- `docs/decisions/0019-event-driven-architecture-v2.md` — 본 spec 의 원칙을 작업 체계 전체에 확장한 ADR (PR #1077). 본 spec 의 "왜 메모리 / 어떻게 코드" 결정 트리를 12 critical event 의 코드 hook 강제로 정형화.
- `docs/features/event-action-mapping.md` — ADR-0019 의 동반 spec. event ↔ action 매핑 + state machine + failure modes.
- `docs/features/work-cycle-refactor.md` — ADR-0019 의 5단계 마이그 plan (운영 break 0).
- `docs/ai-harness/11-multi-session-runbook.md` — 다중 세션 운영 런북
- `docs/ai-harness/12-sub-agent-prompt-template.md` — sub-agent 룰 SoT
- `docs/ai-harness/13-memory-promote-tracking.md` — 메모리 → 코드 promote 트래킹
- `docs/features/nmae-cycle-watchdog.md` — watchdog inject 절차 (코드 강제 사례)
- `tools/cycle-status/README.md` — cycle-status.json 헬퍼 (코드 강제 사례)
- `tools/rev-queue/README.md` — rev 큐 스크립트 (코드 강제 사례)
- `tools/discord-daemon/helper-turn-start.sh` — helper turn wrapper (#1014)
- `tools/agent-launch-wrapper.sh` — sub-agent launch wrapper (#1008)

## 8) 변경 이력

- 2026-05-24 — 최초 작성. #1008 wrapper / #1014 helper-turn-start / #1015 sub-agent no-wait / #1021 thread file passthrough / #1043 nmae manual status push 사고들 후속으로 박제. 결정 트리 + 패턴 카탈로그 6종.


## 9) 메모리 → 코드 promote 운영 룰 (구 13 흡수, 2026-05-28)

> 구 `13-memory-promote-tracking.md` 통합. 반복 적용 메모리 룰을 코드(런북/CLAUDE.md/ADR/spec)로 promote 하는 규율. plan-22 시점 promote 매트릭스(구 13 §1)는 stale 이라 git 이력으로만 보존하고, 본 절은 enduring 룰만 남긴다.

- **메모리 수정 채널 단일화**: race 회피 — nmae(사용자 직접 작성 채널 보유)만 메모리 본문 갱신. plan/sub-agent 는 읽기만.
- **promote 완료 표식**: 메모리에 "코드 promote 완료" 문구가 있으면 코드가 SoT, 메모리는 짧은 포인터만 유지.
- **새 반복 룰**: §3 패턴 카탈로그 + §4 결정 트리에 따라 코드(hook/wrapper/workflow/test)로 강제하고 "왜"만 메모리에 남긴다.
- **actor 별 메모리 디렉토리**: `memory/<actor>/` (`common/nmae/helper/subagent/rev/workflow`).
