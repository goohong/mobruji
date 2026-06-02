---
feature: Directive board stale entry 분류 + close 정책 (대기 long-running entry 정리)
slug: directive-board-stale-close-policy
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-28
---

# Directive board stale entry 분류 + close 정책

## 1) 개요 (What / Why)

`~/.mobruji/directive-board.jsonl` 의 `status: 대기` entry 가 운영 중 누적되어,
의미적으로는 완료 / 폐기 / 사용자 결정 보류임에도 jsonl 상 `대기` 로 남아 forum
sidebar 백로그 가시화를 왜곡한다.

2026-05-28 시점 (helper-queue / nmae 백로그 스캔 결과) **7건 stale directive**
가 일괄 식별됐다:

| ID | 본문 한 줄 | 의미적 상태 | 추천 액션 |
|---|---|---|---|
| `1508670373473026350` | 💰 룰 개선 완료 후 서버 다운그레이드 | ⚪ 보류 (사용자 결정 보류 — 시점 미정) | `deferred` 전이 |
| `1508703025114644602` | 🤖 forum 상태 동기화 bot/script | 🟢 완료 (PR #1129 event-driven 으로 대체) | `completed` 전이 |
| `1508704649828368457` | 📏 discord-reply.sh 2000 chunk split | 🟢 완료 (PR #1119 MERGED) | `completed` 전이 |
| `1509427220802830336` | 잔존 작업들 정리 옵션 — 사용자 정정 | 🟢 완료 (옵션 B 선정 + 적용) | `completed` 전이 |
| `1509427824803577936` | 옵션 B 채택 (sweep 적용) | 🟢 완료 (sweep 진행 완료) | `completed` 전이 |
| `1509442749319872592` | rev 만 재개 (사용자 결정) | 🟢 완료 (적용 완료) | `completed` 전이 |
| `1509456551520505978` | "그냥 테스트" | 🔴 폐기 (testing payload) | `dropped` 전이 |

본 spec = **stale 분류 기준 + close 자동/수동 가이드** 문서화. spec
`directive-board-template-and-tags` §5-1 의 status 5종 (🟡/🔵/🟢/🔴/⚪) +
§5-7 forum tag setup 을 운영 정책으로 확장.

대상 actor:

- **nmae** — 백로그 owner ([[directive-board-template-and-tags §5-6]]). 본 정책으로
  stale entry close 의무화. 매 백로그 스캔 시 1회 sweep.
- **helper** — 사용자 보고 시 stale entry 노이즈 감소. 사용자 회고 정확도 ↑.
- **사용자** — forum sidebar 가 실제 운영 백로그만 표시 → 결정 부담 ↓.

## 2) 사용자 시나리오

- **시나리오 1 (사용자 회고)**: 사용자가 `#모부르지-지시` forum sidebar 에서
  🟡 대기 filter → 진짜 대기 entry 만 표시 (현재는 7건 stale 노이즈 섞임).
- **시나리오 2 (nmae 백로그 스캔)**: nmae 가 매 사이클 시작 시 `directive-board.jsonl`
  스캔 → 🟡 대기 entry 중 본 spec §5-1 기준 통과 entry 만 분배 대상. stale entry
  는 즉시 close + 다음 스캔에서 제외.
- **시나리오 3 (helper 사용자 정정 응답)**: 사용자가 "잔존 작업 정리하자" 직후
  helper / nmae 가 본 spec 기준으로 sweep 수행 → 사용자가 "어떻게 정리됐는지"
  명시적으로 확인 가능 (각 entry → 어떤 상태 전이 + 사유).
- **시나리오 4 (사용자 결정 보류 - 명시)**: 💰 "서버 다운그레이드" 같은
  "timeline 미정 + 사용자 결정 필요" entry → ⚪ 보류 로 명시 전이. 백로그 압박 ↓
  + 후일 재개 시 사용자가 "이 entry 다시 살리자" 신호 가능.

## 3) 요구사항

### 기능 요구사항

- [ ] `대기` entry 의 stale 분류 기준 명문화 (본 spec §5-1) — nmae 가 매 사이클
  시작 시 적용.
- [ ] 4 close 액션 매핑 (`completed` / `dropped` / `deferred` / `재활성`) 정의.
- [ ] 자동 sweep 가이드 — `directive_status.sh` 호출 시퀀스 (`for id in $stale; do
  ... done` 패턴).
- [ ] 사용자 확인 필요 entry vs 자율 close entry 분기 — §5-3 의무 자동/명시 결정.
- [ ] 신규 stale 패턴 발견 시 본 spec 갱신 + nmae 백로그 스캔 룰 update.

### 비기능 요구사항

- **idempotency**: 같은 entry 재 sweep 시 jsonl 중복 status 전이 가드 — 이미
  `completed` / `dropped` / `deferred` 면 skip. (`directive_status.sh` 기존
  멱등성 활용)
- **graceful**: jsonl entry 파싱 실패 시 warning 만 (sweep 전체 fail X).
- **명시성**: 자율 close (특히 `dropped`) 시 jsonl `note` 필드에 사유 박제 —
  사용자 회고 시 "왜 폐기됐지" 추적 가능.
- **공정성**: nmae 자율 sweep 결정은 사용자 정정 가능. 사용자 "이 entry 살려" →
  `directive_status.sh in_progress` 로 재활성.

## 4) 범위 / 비범위

### 포함

- jsonl 안 `status: 대기` entry 의 stale 분류 4 기준 (§5-1).
- 4 close 액션 (`completed` / `dropped` / `deferred` / `재활성`) 의 호출 시퀀스.
- 자율 vs 사용자 확인 필요 분기 (§5-3).
- 2026-05-28 시점 7건 sweep 권고 (§5-4) — 본 PR 머지 후 nmae 가 자율 적용.

### 제외 (Out of Scope)

- `directive_status.sh` 의 신규 액션 추가 (`deferred` 액션이 이미 spec
  `directive-board-template-and-tags` §5-1 의 ⚪ 보류 로 정의됨, impl PR 별도).
- 자동 sweep cron / GHA — 본 spec 은 nmae 사이클 시작 시 수동 적용. 자동화는
  follow-up.
- 새 status 추가 (🟣 결정 대기는 이미 `directive-board-template-and-tags` §5-6
  에서 정의).
- jsonl 스키마 변경 (관련 PR # 추적 필드 등).

## 5) 설계

### 5-1) Stale 분류 4 기준

`status: 대기` entry 가 다음 4 패턴 중 하나라도 매칭 시 **stale** 분류:

| 패턴 | 기준 | close 액션 |
|---|---|---|
| **A. PR 머지 완료** | 본문에 언급된 PR (또는 spec 에 따라 추적된 PR) 이 develop 머지됨 | `completed` (PR URL 박제) |
| **B. 의사 결정 적용 완료** | 사용자가 "옵션 X 로 가자" / "Y 만 재개" 등 결정 → nmae/helper 가 실제 적용 (즉시 반영 또는 후속 PR) | `completed` (적용 PR URL 또는 적용 사유) |
| **C. testing/no-op payload** | "테스트" / "ping" / "메시지 확인" / 빈 본문 (visible char 1 이하) | `dropped` (사유: testing payload) |
| **D. timeline 미정 / 사용자 결정 보류** | 본문에 "추후" / "나중에" / "시점 미정" / 사용자 명시 보류 (예: 비용 절감 후 진행) | `deferred` (사유 박제, 재활성 조건 명시) |

**핵심 룰**: 4 기준 중 정확히 하나만 적용 — 복합 매칭 시 우선순위 A > B > C > D.

### 5-2) 4 close 액션 매핑

| 액션 | 호출 명령 | 결과 |
|---|---|---|
| `completed` | `bash ~/.mobruji/directive_status.sh <id> completed [<pr_url>]` | 🟡 → 🟢. PR URL fragment 옵션. |
| `dropped` | `bash ~/.mobruji/directive_status.sh <id> dropped` | 🟡 → 🔴. 사유 jsonl note 박제 권장. |
| `deferred` | `bash ~/.mobruji/directive_status.sh <id> deferred` | 🟡 → ⚪. 재활성 조건 jsonl note 박제 의무. |
| `재활성` | `bash ~/.mobruji/directive_status.sh <id> in_progress [<cycle>]` | 🟡/⚪ → 🔵. 사용자 명시 신호 시. |

**호출 위치**: nmae 가 자기 turn 안 수행 — sub-agent 위임 불요. helper 본체는
relay-only ([[feedback-helper-relay-only]]) 라 close 수행 권한 없음 — helper 가
stale entry 발견 시 nmae 에게 보고만.

### 5-3) 자율 vs 사용자 확인 분기

| 패턴 | 자율 sweep 가능? | 사유 |
|---|---|---|
| A (PR 머지 완료) | ✅ | 결정성 명확 — PR URL 검증 가능 |
| B (의사 결정 적용 완료) | ✅ | 결정 + 적용 history 추적 가능 (메모리 / Discord queue) |
| C (testing/no-op) | ✅ | 사용자 정정 risk 낮음 — 실제 작업 의도 X |
| D (timeline 미정) | ⚠️ 한정 자율 | 사용자가 명시 "나중에" 표현 또는 jsonl 안 명시 사유 있을 때만. 모호하면 nmae 가 helper 통해 사용자 확인. |

**위반 risk**: 자율 sweep 가 사용자 의도와 다를 때 — 사용자 정정 ("그 entry
살려 둬") 즉시 `재활성` 으로 되돌림. 본 spec 은 자율 default ([[feedback-autonomous-default]])
원칙 유지하되, 정정 가능 path 보장.

### 5-4) 2026-05-28 7건 sweep 권고

본 spec PR 머지 후 nmae 가 다음 시퀀스 자율 적용:

```bash
# A 패턴 (PR 머지 완료)
bash ~/.mobruji/directive_status.sh 1508704649828368457 completed \
  https://github.com/goohong/mobruji/pull/1119
bash ~/.mobruji/directive_status.sh 1508703025114644602 completed \
  https://github.com/goohong/mobruji/pull/1129  # event-driven redesign 적용 PR

# B 패턴 (의사 결정 적용 완료)
bash ~/.mobruji/directive_status.sh 1509427220802830336 completed
bash ~/.mobruji/directive_status.sh 1509427824803577936 completed
bash ~/.mobruji/directive_status.sh 1509442749319872592 completed

# C 패턴 (testing payload)
bash ~/.mobruji/directive_status.sh 1509456551520505978 dropped

# D 패턴 (timeline 미정 — 사용자 명시 보류)
bash ~/.mobruji/directive_status.sh 1508670373473026350 deferred
```

**주의**:

- 7건 중 1508703025114644602 (forum 동기화) 의 "PR #1129 event-driven redesign"
  cross-ref 는 nmae 가 실제 PR 번호 검증 후 호출 (다른 PR 번호일 수 있음).
- 본 PR 자체는 close 명령 호출 X — docs only. nmae 가 본 PR 머지 후 별도 turn
  에서 자율 적용 (sub-agent 위임 외).

### 5-5) 신규 stale 패턴 운영 룰

- nmae 가 백로그 스캔 시 4 기준 외 stale 패턴 발견 → 본 spec §5-1 표 추가 PR
  (plan 사이클).
- 사용자 정정으로 "이 entry 도 close 해야 했는데 빠뜨림" 발견 → 메모리 박제
  ([[feedback-directive-stale-close-miss]] 신설) + 본 spec § 갱신.
- 자동 sweep cron / GHA 화 (follow-up):
  - 트리거: PR 머지 webhook 또는 nmae cron digest.
  - 매칭 로직: jsonl scan + 본 spec §5-1 4 패턴 자동 분류.
  - rollout: 우선 manual sweep 1주 운영 검증 후 cron 화.

### 5-6) 사용자 결정 보류 entry (⚪ 보류) 운영

⚪ 보류 entry 는 다음 조건 충족 시 자동 재활성:

- jsonl note 안 "재활성 조건" 명시 (예: "비용 절감 후" / "v0.4 release 후").
- 조건 만족 신호 발생 시 nmae 가 helper 통해 사용자 확인 → in_progress 전이.

조건 명시 누락 시 ⚪ 보류 entry 는 영구 보존 — 사용자 정정만 재활성 가능 (자율
재활성 X, 정합성 가드).

### 5-7) API / DB / FE

해당 없음. 운영 정책 + jsonl entry 분류 만.

## 6) 작업 분할 (예상 PR 리스트)

본 spec PR (현재) 은 docs only. 후속:

- [x] **PR docs** (본 PR): `docs/features/directive-board-stale-close-policy.md`
  + `docs/features/README.md` §9 인덱스 sync.
- [ ] **NMAE 후속 turn (코드 변경 X)**: 본 spec §5-4 의 7건 sweep 시퀀스 자율
  적용 + jsonl 검증.
- [ ] **PR follow-up (선택)**: 자동 sweep cron 화 — bot.py 또는 GHA. impl 시점:
  manual sweep 1주 운영 검증 후.
- [ ] **PR follow-up (선택)**: `directive_status.sh` 의 sweep mode 추가 (`bash
  directive_status.sh sweep`) — 4 패턴 자동 분류 + 일괄 호출. impl 시점:
  사용자 운영 피드백 후.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☒ 없음 — docs only (`docs/features/**` + `README.md`
  인덱스).

## 7) 테스트 전략

### 단위

- 없음 — 본 PR docs only.

### 통합 (nmae sweep 후 검증)

본 PR 머지 후 nmae 가 §5-4 시퀀스 적용 시 다음 검증:

- `tail ~/.mobruji/directive-board.jsonl` → 7 entry status 가 각각 `completed`
  / `dropped` / `deferred` 로 전이됐는지.
- Discord `#모부르지-지시` forum sidebar → 🟡 대기 filter 적용 시 7 entry 미표시.
  ⚪ 보류 filter → 1 entry 표시 (1508670373473026350).
- `directive_status.sh` 호출 후 forum tag 전이 (🟡 → 🟢/🔴/⚪) 시각 검증.

### 회귀

- nmae 가 다음 사이클 백로그 스캔 시 close 된 7 entry 가 분배 후보에서 제외되는지
  확인.
- 자율 sweep 1주 후 false-positive (의도와 다른 close) 발견 여부 — 0건 목표.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 자동 sweep cron 도입 시점. | (a) manual sweep 1주 운영 검증 후 / (b) 본 spec 머지 즉시 GHA / (c) 안 함 (수동만) | @mobruji-maestro / 2026-06-04 |
| Q2 | ⚪ 보류 entry 의 자동 재활성 — 조건 만족 신호 detect 메커니즘. | (a) jsonl 안 명시 조건 + 사용자 확인만 / (b) cron 자동 detect + 사용자 push / (c) 미도입 (사용자 명시 신호만) | @mobruji-maestro / 2026-06-04 |
| Q3 | testing/no-op payload (C 패턴) 의 sensitivity — "테스트" / "ping" 외 추가 패턴 (예: emoji-only 메시지). | (a) §5-1 표 점진 확장 / (b) bot.py 등록 단계에서 미리 필터 / (c) 현행 4 패턴 충분 | @mobruji-maestro / 2026-06-04 |
| Q4 | 본 spec §5-4 7건 권고 중 1508703025114644602 (forum 동기화) 의 cross-ref PR — PR #1129 (event-driven redesign) 가 맞는가, 아니면 다른 PR (예: #1131 sweep 계열) 인가. nmae 가 실제 검증 후 결정. | (a) #1129 / (b) 다른 PR / (c) PR URL 없이 completed (사유: "event-driven redesign 으로 대체") | @nmae / 본 PR 머지 후 즉시 |

## 9) 결정 로그

- 2026-05-28: 초안 작성 (status=draft). 7건 stale directive 일괄 식별 후 분류
  정책 명문화 필요성 부각.
- 2026-05-28: stale 분류 4 기준 (A/B/C/D) 채택 사유 — 7건 중 6건이 4 패턴 안
  완전히 분류 가능 (1건만 D 의 사용자 결정 보류 — 자율 sweep 한정).
- 2026-05-28: nmae 자율 sweep default 채택 사유 — [[feedback-autonomous-default]]
  일치 + 사용자 정정 path 보장 (재활성 액션).
- 2026-05-28: 자동 sweep cron 화는 follow-up — manual sweep 1주 운영 검증 후
  결정 (Q1).

## 10) 자율 결정 (사유)

- 본 PR 자체는 close 명령 호출 X (docs only) — sub-agent 권한 외. nmae 후속
  turn 에 §5-4 시퀀스 자율 적용. plan 사이클 (현재) = 정책 문서화만.
- 7건 sweep 권고 중 PR cross-ref 가 불확정 (Q4) 인 entry 는 nmae 가 검증 후
  결정 — 본 spec 은 "추천 액션" 만 제시.

## 11) 사용자 확인 필요

- ⚪ 보류 entry (1508670373473026350) 의 재활성 조건 — 현재 jsonl 본문에 명시 안
  됐음. 본 PR 머지 + nmae sweep 후 사용자가 "v0.4 release 후" 등 명시 조건
  추가하면 자동 재활성 가이드 가능.
- 4 패턴 외 stale 패턴 발견 시 본 spec § 갱신 PR 검토 필요.

## 12) References

- [[directive-board-template-and-tags]] §5-1 (status 5종 + close 액션 정의) —
  본 spec 의 정책 SoT.
- [[directive-board-template-and-tags]] §5-6 (백로그 운영 모델 + nmae owner) —
  본 spec 의 actor 책임 SoT.
- [[directive-board-event-driven-redesign]] — directive jsonl event-driven sync
  정책 (사이드카 PR).
- `tools/discord-daemon/directive_status.sh` — 호출 대상 스크립트.
- 메모리: [[feedback-autonomous-default]] / [[feedback-evidence-based-root-cause]].

## 13) 변경 이력

- 2026-05-28: 초안 작성. 2026-05-28 시점 7건 stale directive sweep 권고 +
  분류 4 기준 + close 액션 매핑 + 자율 vs 사용자 확인 분기 명문화.
