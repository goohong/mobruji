---
feature: 내부 ID 라벨 scrub (A1/B2/D1 → user-friendly paraphrase 분리)
slug: internal-label-scrub
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1063]
related_prs: []
last_reviewed: 2026-05-24
---

# 내부 ID 라벨 scrub (사용자 가시 분리)

## 1) 개요 (What / Why)

- `~/.mobruji/directive-board.jsonl` 에 `[A1] [A2] [A3] [A4] [B1] [B2] [B3] [C1] [C2] [D1] [D2] [D3] [D4]` 류 **내부 작업 ID** 가 사용자 가시 Discord 본문에 그대로 노출되어 가독성 ↓.
- 본 spec 은 내부 ID (작업 tracking 용) 와 사용자 가시 텍스트 (paraphrase) 를 **데이터 모델 + render 두 레이어로 분리**한다.
- 사용자 정정 (2026-05-24): "D4/D3/A1 라벨 내부용 - 사용자 가시 제거".

### 1-1) 트리거 사고

현재 `~/.mobruji/directive-board.jsonl` 의 `summary` 필드 (Discord 본문 render 대상) 가 다음 형식:

```
[A1] directive 채널 8 backfill manual PATCH (jsonl → Discord 본문 갱신)
[B2] agent-launch-wrapper.sh per-cycle 채널 push 강제
[D1] nmae 본체 manual status push 폐기 코드 enforce (메모리만 X)
```

사용자가 #모부르지-지시 Discord 채널에서 보면:
- `[A1]` / `[B2]` / `[D1]` 같은 내부 ID 가 가독성 노이즈.
- A/B/C/D 분류 + 1-4 우선순위가 운영자 (nmae) 만 이해.
- 사용자는 "무엇을 하고 있는가" 만 알면 충분.

### 1-2) 책임 분리

| 영역 | 채널 | 담당 |
|---|---|---|
| 내부 ID (`A1` / `B2` / `D1`) | jsonl `internal_id` 필드 (신설) | nmae 운영자 추적용 |
| 사용자 가시 paraphrase | jsonl `summary` 필드 (paraphrase) | directive_board_sync_loop render 대상 |
| Discord 본문 | `directive_board_sync_loop` render — `summary` 우선 | 코드 강제 (학습 무관) |

## 2) 사용자 시나리오

### 2-1) 운영자 (nmae) 측

1. nmae 가 새 작업 백로그 등록 시 `summary` (사용자 paraphrase) + `internal_id` (운영자 ID) 두 필드 명시.
   - 예: `internal_id="A1"`, `summary="백로그 8건 본문 보강 (자동 PATCH 적용 전 임시)"`
2. directive-board.jsonl 에 append. `internal_id` 는 운영자가 사이클 진행 추적용 (P0/P1/P2 우선순위 매칭 + 카테고리 grouping).
3. `directive_board_sync_loop` 가 5분 주기로 jsonl read → Discord 본문 PATCH 시 `summary` 만 render. `internal_id` 는 노출 X (단, jsonl 안 + 메타 코멘트 line 은 보존).

### 2-2) 사용자 측

1. 사용자가 #모부르지-지시 forum 채널에서 directive thread 본문 봄.
2. 본문 = `summary` paraphrase 만 노출 — 내부 ID 없음.
3. 진행 상황 (`status` 필드) + 담당자 (`owner` 필드) 만 사용자 가시.

## 3) 요구사항

### 기능 요구사항

#### 3-1) jsonl 스키마 확장

- [ ] **신설 필드**: `internal_id` (string, optional). 예: `"A1"` / `"B2"` / `"D1"`.
- [ ] **기존 필드 의미 변경**: `summary` 필드는 **사용자 가시 paraphrase** 만 담는다 (내부 ID prefix 금지).
- [ ] **하위 호환**: `internal_id` 부재 시 (기존 13 entries) — 운영자 paraphrase 작업 시 backfill.
- [ ] **validation**: jsonl 신규 entry 추가 시 `summary` 에 `[A-Z][0-9]+` 정규식 매치 시 warn (linter — `tools/discord-daemon/directive_board_lint.py` 신설 또는 기존 sync 안에 통합).

#### 3-2) `directive_board_sync_loop` 확장

- [ ] **render 우선순위**: Discord 본문 render 시 `summary` 우선. `internal_id` 는 jsonl 안 + 메타 코멘트만.
- [ ] **메타 코멘트 line**: thread 본문 끝에 운영자만 식별 가능한 1줄 추가 (선택). 예: `<!-- internal: A1 / P0 -->`. Discord 가 HTML 주석 unsupported 이므로 zero-width invisible char + suffix 검토 (선택사항 — Q2 참조).
- [ ] **paraphrase migration**: 기존 13 entries 의 `summary` 본문 paraphrase 갱신 + `internal_id` 추출 (예: `[A1] X` → `summary: "X paraphrase"` + `internal_id: "A1"`).

#### 3-3) 변환 룰 매핑 표 (운영자 가이드)

13 기존 entries 변환 예시:

| 기존 summary | internal_id | 신규 summary (paraphrase) |
|---|---|---|
| `[A1] directive 채널 8 backfill manual PATCH (jsonl → Discord 본문 갱신)` | A1 | 백로그 8건 본문 보강 (자동 PATCH 적용 전 임시) |
| `[A2] per-cycle 4 채널 backfill 포스트 (in-progress + 최근 완료)` | A2 | 사이클 4 채널 진행 상황 일괄 기록 |
| `[A3] P8 PR #1042 discord-reply.sh --directive-edit mode 머지 가속` | A3 | 지시 편집 기능 PR 머지 가속 (PR #1042) |
| `[A4] PR 머지 webhook → jsonl 자동 갱신 → Discord PATCH 자동 hook` | A4 | PR 머지 시 백로그 자동 갱신 hook |
| `[B1] discord-reply.sh channel resolver enum (msg type→channel SoT)` | B1 | 메시지 type별 채널 라우팅 자동화 |
| `[B2] agent-launch-wrapper.sh per-cycle 채널 push 강제` | B2 | 사이클 launch 알림 정확한 채널 라우팅 |
| `[B3] 메모리/CLAUDE.md ↔ 코드 hook 분리 룰 (왜 vs 어떻게)` | B3 | 룰의 "왜" vs "어떻게" 책임 분리 spec |
| `[C1] UI/UX 4단계 (rev audit ✅ / 토스 ref / docs/features/ui-ux-redesign.md / fe 컴포넌트 PR)` | C1 | UI/UX 디자인 부활 4단계 진행 |
| `[C2] persona/pain 4단계 (페르소나 3-5 / 페인 5-10 / 매핑 / spec + 백로그)` | C2 | 사용자 페르소나 / 페인 포인트 정의 후속 |
| `[D1] nmae 본체 manual status push 폐기 코드 enforce (메모리만 X)` | D1 | 사이클 알림 중복 자동 차단 |
| `[D2] helper 빈 메시지 오독 룰 코드 박제 (visible char 0 처리)` | D2 | 빈 메시지 오독 자동 분류 |
| `[D3] helper sub-agent 디테일 relay 금지 룰 enforce (cycle channel 사용)` | D3 | helper 가 nmae 사이클 디테일 relay 금지 enforce |
| `[D4] 답장 freeze UX (turn 길어지면 최신 user msg 재 freeze) + sub-agent thread stream 검증` | D4 | 답장 대상 정확성 + sub-agent 진행 stream 검증 |

추가로 `[누락방지]` prefix 항목 1건:

| 기존 summary | internal_id | 신규 summary |
|---|---|---|
| `[누락방지] directive-board 대기 카운트 cron digest signature + /clear pre-check` | E1 | 백로그 대기 자동 알림 + /clear 직전 검증 |

#### 3-4) post-deploy 갱신 절차

- [ ] **jsonl backfill** (nmae 또는 be 사이클): 기존 13 entries 각각 paraphrase 갱신 + `internal_id` 추출. atomic write (mktemp + rename) — 동시 sync_loop 와 race 회피.
- [ ] **Discord 본문 PATCH**: sync_loop 가 5분 주기로 자동 PATCH. 즉시 trigger 필요 시 `touch ~/.mobruji/directive-sync-trigger` (clear-pre-hook.sh 액션 3 와 동일 채널).
- [ ] **검증**: PATCH 완료 후 #모부르지-지시 forum 채널 thread 본문 grep — `[A-Z][0-9]+` 패턴 0건 확인.

### 비기능 요구사항

- **하위 호환**: 기존 jsonl entries (`internal_id` 부재) read 시 graceful — `summary` 에서 `[A-Z][0-9]+` 패턴 detect 시 자동 추출 fallback (전이 기간).
- **linter**: 신규 entry append 시 `summary` 에 `[A-Z][0-9]+` 패턴 매치 시 warn (CI 또는 pre-commit). hard fail 은 X (운영자 의도적 표기 가능).
- **render 일관성**: forum thread 본문 + 텍스트 채널 (deprecated) 둘 다 `summary` 만 render.
- **운영자 가시성**: jsonl 안에서 `internal_id` 는 grep 가능 — 운영자가 "B2 처리 중인 PR 찾기" 시 1초.
- **race safety**: jsonl 동시 read/write 시 atomic write 사용. 기존 sync_loop 패턴 재사용.

## 4) 범위 / 비범위

### 포함

- jsonl 스키마 `internal_id` 필드 신설.
- `summary` 필드 의미 명확화 (사용자 가시 paraphrase only).
- `directive_board_sync_loop` render 로직 — `summary` 우선.
- 기존 13 entries paraphrase migration.
- linter (`directive_board_lint.py` 또는 sync 안에 통합).
- 운영자 가이드 매핑 표 (§3-3).

### 제외 (Out of Scope)

- **구현 코드 본 PR 안에서 작성 X** — 본 PR 은 spec 만. 구현은 별도 be 사이클 위임 (§6).
- 내부 ID 자동 할당 (현재는 운영자 수동) — 추후 자동 generator 후보.
- forum thread 태그 전이 ([[feedback-nmae-forum-channel-enforce]]) 룰 변경 — 본 spec 은 본문 render 만.
- `[누락방지]` prefix 폐기 — E1 형식으로 표준화 (위 §3-3 표 참조).
- 메타 코멘트 (`<!-- internal: A1 -->`) Discord 노출 방식 결정 — Q2 오픈 질문.

## 5) 설계

### 5-1) 도메인 모델

- 인프라 도메인 (`scope: infra`). 외부 사용자 도메인 무관.
- 데이터 모델:
  - `directive-board.jsonl` (SoT) — `internal_id` (운영자) + `summary` (사용자) 분리.
  - Discord forum thread (`#모부르지-지시`) — render target. `summary` 만 노출.

### 5-2) API 엔드포인트

N/A (script + sync_loop 만).

### 5-3) 외부 연동

- **Discord API** — thread message PATCH (`discord-reply.sh --directive-edit` 또는 sync_loop 직접 PATCH).
- **gh CLI** — N/A (현재 미사용).

### 5-4) 데이터 흐름 / 시퀀스

```
[운영자 (nmae)] 신규 백로그 추가
   ↓ jsonl append: {"summary": "<paraphrase>", "internal_id": "B2", ...}
[directive-board.jsonl SoT]
   ↓ 5분 cron 또는 ~/.mobruji/directive-sync-trigger touch
[directive_board_sync_loop]
   ↓ read jsonl
   ↓ for each entry:
   │   ├ summary = entry["summary"]  (paraphrase only)
   │   ├ internal_id = entry.get("internal_id")  (운영자 grep용, render X)
   │   └ render body = summary + status + owner + related
   ↓ Discord PATCH (thread message)
[Discord forum thread]
   ↓ 사용자 view = paraphrase만 노출
```

### 5-5) DB 마이그레이션

없음 (파일 기반 jsonl).

### 5-6) 프론트엔드 화면

없음 (Discord forum thread 가 사용자 인터페이스).

### 5-7) jsonl entry 예시 (after migration)

**Before**:

```json
{"ts": "2026-05-24 15:13 KST", "summary": "[B2] agent-launch-wrapper.sh per-cycle 채널 push 강제", "status": "⏳ 대기 (be 워크트리 set-active 예정)", "owner": "be sub-agent (예정)", "related": "tools/agent-launch-wrapper.sh + cycle resolver", "priority": "P0 10분", "message_id": "...", "thread_id": "...", "last_updated_kst": "2026-05-24 15:20 KST"}
```

**After**:

```json
{"ts": "2026-05-24 15:13 KST", "summary": "사이클 launch 알림 정확한 채널 라우팅", "internal_id": "B2", "status": "⏳ 대기 (be 워크트리 set-active 예정)", "owner": "be sub-agent (예정)", "related": "tools/agent-launch-wrapper.sh + cycle resolver", "priority": "P0 10분", "message_id": "...", "thread_id": "...", "last_updated_kst": "2026-05-24 15:20 KST"}
```

차이:
- `summary` paraphrase 갱신 (`[B2]` prefix 제거 + 사용자 친화 표현)
- `internal_id` 신설 (`B2`)
- 나머지 필드 동일

### 5-8) linter 로직 (사용자 가시 prefix 차단)

```python
import re

INTERNAL_ID_PATTERN = re.compile(r'^\[[A-Z][0-9]+\]')

def validate_summary(summary: str) -> tuple[bool, str | None]:
    """summary 가 사용자 가시 텍스트 인지 검증.

    Returns:
        (is_valid, warning_message)
    """
    if INTERNAL_ID_PATTERN.match(summary):
        return False, f'summary 에 내부 ID prefix 검출 — internal_id 필드로 분리 권고: {summary[:30]}'
    return True, None
```

linter 호출 위치:
- (a) `directive_board_sync_loop` 매 사이클 read 시 warn 로그 + skip render 가능 옵션.
- (b) pre-commit hook (선택) — jsonl 직접 편집 시 (단, [[feedback-nmae-directive-board-update-flow]] 룰상 jsonl 직접 편집은 운영자만).

## 6) 작업 분할 (예상 PR 리스트)

### PR 1: jsonl 스키마 + linter (be 사이클)

- 신설: `tools/discord-daemon/directive_board_lint.py` (또는 `directive_board_sync.py` 안에 통합)
- `internal_id` 필드 schema + validation
- linter unit test (`tests/test_directive_board_lint.py`)
- session: `session:backend`

### PR 2: `directive_board_sync_loop` render 로직 변경 (be 사이클)

- `summary` 우선 render
- `internal_id` 는 jsonl 안 + 메타 line (Q2 결정 후)
- 회귀 테스트 (기존 sync_loop 테스트 보강)
- session: `session:backend`

### PR 3: 기존 13 entries paraphrase migration (be 또는 nmae)

- atomic write (mktemp + rename)
- migration script (`tools/discord-daemon/migrate_directive_internal_id.py` 1회용)
- post-deploy 검증 — forum thread 본문 grep 0건 (`[A-Z][0-9]+` 패턴)
- session: `session:backend`

### PR 4: docs/CLAUDE.md 갱신 (plan 사이클)

- `feedback-nmae-directive-board-update-flow` 메모리 갱신 — `internal_id` + `summary` 분리 룰 추가
- CLAUDE.md §11 nmae directive-board 절에 1줄 추가
- 운영자 가이드 매핑 표 (§3-3) docs/runbooks 또는 본 spec 안에 영속
- session: `session:plan`

## 7) 테스트 전략

### 7-1) 단위 (PR 1)

- linter: `[B2] X` 패턴 detect → warn
- linter: 정상 paraphrase → pass
- jsonl read: `internal_id` 부재 entry graceful → fallback 추출

### 7-2) 통합 (PR 2)

- `directive_board_sync_loop` render 결과 — `summary` 만 포함, `internal_id` 미포함
- 메타 line (Q2 결정 시) — Discord 노출 방식 검증

### 7-3) E2E (PR 3 post-deploy)

- 기존 13 entries migration 후 forum thread 본문 grep `[A-Z][0-9]+` 0건
- 운영자 jsonl grep `"internal_id": "B2"` 1건 (보존)
- 5분 cron 후 자동 PATCH 적용 확인

### 7-4) 외부 연동 mock

- Discord API PATCH — 기존 sync_loop mock 패턴 재사용
- jsonl I/O — atomic write fixture

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | linter hard fail vs warn-only | (a) hard fail (CI block) / (b) warn (운영자 의도 허용) | be / 2026-05-25 |
| Q2 | `internal_id` Discord 메타 노출 방식 | (a) zero-width invisible char suffix / (b) thread 제목 prefix / (c) 노출 X (jsonl만) | nmae / 2026-05-25 |
| Q3 | 기존 13 entries paraphrase 누가 작성 | (a) nmae 직접 / (b) plan sub-agent / (c) 매핑 표 (§3-3) 기반 자동 migration | nmae / 2026-05-25 |
| Q4 | `[누락방지]` prefix 처리 — E1 으로 표준화 vs 다른 카테고리 | (a) E1 (§3-3 표 채택) / (b) 신규 카테고리 신설 | nmae / 2026-05-25 |
| Q5 | 향후 internal_id 자동 generator 필요성 | (a) 현재 운영자 수동 OK / (b) 자동 generator 다음 PR | nmae / 추후 |

## 9) 결정 로그

- 2026-05-24: 초안 작성 (status=draft). 사용자 정정 인용 — "D4/D3/A1 라벨 내부용 - 사용자 가시 제거".

## 10) 관련 문서

- `CLAUDE.md §11 nmae directive-board update flow` — directive-board jsonl SoT 룰
- `docs/ai-harness/16-memory-vs-code-enforcement.md` — 메모리 vs 코드 분리 원칙
- `tools/discord-daemon/directive_board_sync.py` — sync_loop 코드
- 메모리 [[feedback-nmae-directive-board-update-flow]] [[feedback-nmae-forum-channel-enforce]] [[feedback-helper-directive-board]]
- 관련 PR: #1042 (`--directive-edit` mode), #1047 (sync_loop)
