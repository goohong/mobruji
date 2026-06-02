---
feature: directive polish alert status filter (false positive 가드)
slug: directive-polish-alert-status-filter
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1248]
related_prs: [1252]
last_reviewed: 2026-05-29
---

# Directive polish alert status filter (false positive 가드)

> 2026-05-29 사용자 정정 박제 (issue #1248). 이미 `status=완료` 인 directive
> 에 대해 `directive_polish_loop` (PR #1242 / #1243) 가 polish 를 trigger →
> forum-edit push → nmae 본진 / 사용자 알림 6회 false positive. 추가로
> `tools/directive-board/mark-polished.sh` 가 매칭 entry status 무관하게 nmae
> tmux pane inject 호출 — 이중 사고 경로.
>
> root cause + fix 전략 (이중 가드) + 회귀 가드 spec.
>
> **PR 분담**: 본 SPEC PR (plan) + impl PR (be 또는 helper-launched, bot.py
> `_process_directive_polish_queue` + `mark-polished.sh` 동시 가드 추가).

## 1) 개요 (What / Why)

**Sub-사고 A — bot.py polish loop**: `bot.py` `directive_polish_loop` 가
`helper-queue.jsonl` 의 `type=directive_polish` & `status=pending` task 를
처리할 때, **대응되는 directive jsonl entry 의 `status` (대기 / 완료) 를
cross-check 하지 않습니다**. 결과:

- `📌 pin` 등록 직후 queue 에 polish task append (`status=pending`).
- 사용자가 즉시 directive 를 `완료` 로 마크해도 queue 의 polish task 는 그대로 `pending`.
- 1분 후 `directive_polish_loop` iter 가 pending 발견 → `claude -p` polish → `--forum-edit` 호출.
- 이미 완료된 directive forum thread 가 `--forum-edit` 으로 본문 덮어쓰임 → 사용자 알림 발생.

**Sub-사고 B — mark-polished.sh inject 분기**:
`tools/directive-board/mark-polished.sh` 가 매칭 entry `status` 를 보지 않고
**항상 nmae tmux pane inject 실행**. polish 가 끝난 (또는 외부 경로에서
호출된) 완료 entry 도 inject 되어 nmae 가 stale "polish 필요" 신호로 오인.

대상: nmae / helper 본체 / 사용자 (forum thread starter 본문 update 시 알림 +
nmae tmux pane inject 가시화).

## 2) 사용자 시나리오 (false positive)

### 시나리오 A: 빠른 완료 처리

1. 사용자가 메시지 📌 reaction → bot.py 가 `directive_append` + queue polish task append.
2. (1분 미만) 사용자가 즉시 directive 를 `완료` 처리 (mark-complete script 또는 PR 머지 trigger).
3. `directive_polish_loop` 다음 iter (≤1분) 가 queue 의 pending polish task 발견.
4. polish 실행 + `--forum-edit` 호출 → 이미 완료된 thread 가 update 됨 → 사용자 알림.

### 시나리오 B: 큐 stale 누적

1. directive A (msg_id=X) `📌` 등록 → queue polish task `status=pending`.
2. polish loop 첫 iter 직전 claude -p 가 hang / timeout → task 그대로 `pending` 유지.
3. 사용자가 다른 channel 에서 directive A 를 완료 마크 (jsonl status=완료).
4. polish loop 다음 iter 가 (stale) pending task 재시도 → 동일 false positive.

### 사용자 컨텍스트 evidence (2026-05-29, issue #1248)

알림 ID 6건 (`~/.mobruji/directive-board.jsonl` 의 매칭 entry):

| 알림 ID | summary |
|---|---|
| 1507983360562303209 | ALERT_CHANNEL_ID … |
| 1507983377113022514 | 채널 leak 라우팅 분리 … |
| 1507983408196747345 | digest 채널 leak root cause … |
| 1507991573466447952 | [B1] discord-reply.sh channel resolver enum … |
| 1507991580198309969 | [B2] agent-launch-wrapper.sh per-cycle 채널 push 강제 … |
| 1508005692412395632 | 내부 라벨 D4/D3/A1 사용자 가시 제거 … |

모두 `status=완료` + `polished=true` + `last_updated_kst=2026-05-29 10:39~10:40 KST`.
`tools/directive-board/backlog-scan.sh` 출력은 COUNT=0/1 (대기 entry 만 list)
→ polish 알림 ↔ backlog mismatch 가 추가 evidence.

## 3) 요구사항

### 기능 요구사항 (이중 가드 — issue #1248 채택 (a) + (b))

- [ ] **가드 A (bot.py polish loop)**: `_process_directive_polish_queue` 가
      queue entry 처리 직전, 대응 directive jsonl entry (`~/.mobruji/directive-board.jsonl`)
      의 `status` 를 read 해 `대기` / `진행 중` 외이면 polish skip + queue
      entry `status=skipped, skipped_reason=archived_or_completed` mark.
- [ ] **가드 B (mark-polished.sh)**: nmae tmux pane inject 분기 직전, 매칭
      entry `status` 가 `대기` / `진행 중` 외이면 inject skip + stderr 에 사유
      표시. polished flag update 자체는 멱등하게 진행 (race 가드 보존).
- [ ] polish skip 시 `--forum-edit` 호출 안 함 (false positive 알림 원천 차단).
- [ ] queue entry skip 도 atomic rewrite 에 반영 (재시도 안 함, 멱등).
- [ ] jsonl entry 부재 (드물지만 jsonl 손상 / 수동 삭제) 시 graceful skip
      (warning log + queue entry `status=skipped, skipped_reason=missing` mark).

### 비기능 요구사항

- **성능**: 추가 jsonl read 1회 / task. directive jsonl 크기 무관 시간 복잡도
  O(N) 이지만 polling 1분 / loop iter, queue 크기 보통 1-10 → 무시 가능.
- **관측성**: skip 사유 (`completed` / `missing` / `closed`) 별 log info 라인.
- **회귀 안전**: `directive_polish_loop` 자체 동작 / `_run_claude_polish` 인터
  페이스 변경 없음. status filter 만 add (subtract 없음).

## 4) 범위 / 비범위 (중요)

### 포함

- `bot.py` `_process_directive_polish_queue` 의 status filter 추가 (가드 A).
- `tools/directive-board/mark-polished.sh` 의 nmae inject 분기 status filter
  추가 (가드 B).
- queue entry skip mark + 재시도 방지 (멱등).
- jsonl entry 부재 graceful 처리.
- 회귀 가드 test (queue + jsonl fixture 로 status filter 검증) — bash test
  (`tests/test_mark_polished.sh`) + python test (`tests/test_helper_ux.py`
  또는 신규 `tests/test_directive_polish_loop.py`) 양쪽.

### 제외 (Out of Scope)

- `directive_polish_loop` poll interval / claude -p timeout 조정 — 본 issue
  와 무관.
- queue stale task cleanup cron — 별 spec (`helper-subagent-directive-polish.md`
  `7) follow-up` 참조).
- `📌 pin` 등록 시점에서 jsonl status pre-check — 등록 시점에는 항상 `대기`
  이므로 cross-check 무의미.
- forum thread `--forum-edit` 자체 멱등화 (동일 내용이면 PATCH skip) —
  Discord API 가 직접 지원 안 함, 별 spec 필요.
- (c) `backlog-scan COUNT≥1` cross-check — issue #1248 이 명시 채택 안 함 ((b)
  로 직접 차단 충분).

## 5) 설계

### 5-1) 도메인 모델

- `directive` 도메인 (jsonl + forum thread + queue) — `06-domain-model.md`
  scope=infra, 별 ERD 영향 없음.
- queue entry life-cycle: `pending → done | skipped-completed | skipped-missing`.

### 5-2) API 엔드포인트

해당 없음 (bot.py 내부 함수 변경).

### 5-3) 외부 연동

해당 없음.

### 5-4) 데이터 흐름 / 시퀀스

**AS-IS** (false positive 경로):

```
queue pending polish task
  → directive_polish_loop iter
     → _run_claude_polish → polished body
     → discord-reply.sh --forum-edit  ← ❌ status=완료 thread 에도 PATCH 호출
     → mark-polished.sh
     → queue entry status=done
```

**TO-BE** (이중 가드 — A 가 1차 차단, B 가 2차 차단):

```
queue pending polish task
  → directive_polish_loop iter (가드 A)
     → directive jsonl entry read (by message_id)
     → entry.status 확인
        ├─ 대기 / 진행 중 → 기존 흐름 (polish + forum-edit + mark-polished.sh + done)
        │     → mark-polished.sh 호출 시 가드 B 가 추가 cross-check
        ├─ 완료 / 외 → polish skip (가드 A 차단)
        │     → queue entry status=skipped, skipped_reason=archived_or_completed
        │     → forum-edit / mark-polished.sh 호출 안 함
        └─ jsonl entry 부재 → polish skip
              → queue entry status=skipped, skipped_reason=missing + warning log
     → queue atomic rewrite (skip 도 반영)

mark-polished.sh (외부 trigger 포함 — 가드 B)
  → jq match entry by message_id
  → polished=true update (always — 멱등성 보존)
  → nmae inject 분기 진입 직전
     → entry.status 가 대기 / 진행 중 외이면
        → stderr "inject skip: status=<v> (대기/진행 중 외)"
        → exit 0 (멱등)
     → 정상이면 nmae tmux pane inject 호출
```

### 5-5) DB 마이그레이션

해당 없음.

### 5-6) 프론트엔드 화면

해당 없음.

### 5-7) 구현 노트

#### jsonl path (issue #1248 evidence 확정)

`~/.mobruji/directive-board.jsonl` — issue #1248 에서 직접 확인된 path. impl
시 hard-coded path 가 아닌 `Path.home() / ".mobruji" / "directive-board.jsonl"`
로 일관.

#### lookup 전략 (bot.py 가드 A)

`message_id` 로 grep — N 작으니 linear scan 충분. jq dependency 회피:

```python
def _read_directive_status(jsonl_path: Path, message_id: str) -> str | None:
    if not jsonl_path.exists():
        return None  # missing
    for raw_line in jsonl_path.read_text(encoding="utf-8").splitlines():
        if not raw_line.strip():
            continue
        try:
            entry = json.loads(raw_line)
        except json.JSONDecodeError:
            continue
        if str(entry.get("message_id") or "") == message_id:
            return str(entry.get("status") or "")
    return None  # missing
```

#### lookup 전략 (mark-polished.sh 가드 B)

기존 jq 사용 패턴 보존 — jq 의존 회피보다는 일관성 우선. shell 구현 권장
패턴:

```bash
matched_status=$(jq -r --arg id "$DIRECTIVE_ID" \
  '. | select(.message_id == $id) | .status // ""' "$JSONL_PATH" | head -1)

case "$matched_status" in
  "대기"|"진행 중")
    # 정상 진행 — nmae inject 호출
    ;;
  "")
    echo "[mark-polished] inject skip: entry not found id=$DIRECTIVE_ID" >&2
    exit 0  # 멱등 exit
    ;;
  *)
    echo "[mark-polished] inject skip: status=$matched_status (대기/진행 중 외)" >&2
    exit 0  # 멱등 exit
    ;;
esac
```

#### status enum

`directive-board-template-and-tags.md` 의 status 5 enum 참조. issue #1248 채택:
- `대기` / `진행 중` → polish 진행 + inject
- 그 외 (`완료` / `보류` / `취소`) → skip

allowlist 방식 — magic string 회피 + 미래 status enum 추가 시 default deny:

```python
DIRECTIVE_POLISH_ACTIVE_STATUSES: Final[frozenset[str]] = frozenset({
    "대기", "진행 중",
})
```

#### status enum cross-reference 매트릭스 (SoT: `directive-board-template-and-tags.md §5-1`)

**중요**: 본 spec 의 allowlist 와 [[directive-board-template-and-tags]] 의 status enum 은 **다른 spec 에 분산** 되므로 enum 변경 시 정합성 cross-ref 필수. 본 표는 양 spec 간 contract 박제.

| status (한글) | 의미 | tag (emoji) | 정의 spec | 본 polish allowlist | inject (mark-polished) | 사유 |
|---|---|---|---|---|---|---|
| `대기` | 등록 직후 default | 🟡 | template-and-tags §5-1 | ✅ allow | ✅ allow | helper sub-agent 정제 대상 — polish + inject 모두 필요 |
| `진행 중` | sub-agent / nmae 작업 중 | 🔵 | template-and-tags §5-1 | ✅ allow | ✅ allow | 진행 중 entry 도 추가 정제 / re-inject 가능 (rare) |
| `결정 대기` | plan 분석 완료, 사용자 결정 대기 | 🟣 | template-and-tags §5-1 (PR E) | ❌ skip | ❌ skip | plan 이 이미 정제 → 사용자 결정 lock. polish 재실행 noise |
| `완료` | PR 머지 / 결정 적용 | 🟢 | template-and-tags §5-1 | ❌ skip | ❌ skip | 본 spec 의 핵심 차단 대상 (issue #1248 false positive 6건) |
| `폐기` (취소) | 취소 / reject | 🔴 | template-and-tags §5-1 | ❌ skip | ❌ skip | 종결 entry — 어떤 update 도 noise |
| `보류` | defer (timeline 미정) | ⚪ | template-and-tags §5-1 | ❌ skip | ❌ skip | 보류 = 사용자 의도적 대기 — polish 재실행 시 alert false positive |

**enum 명명 매핑 주의**:
- template-and-tags §5-1 의 `directive_status.sh` 호출 인자: `in_progress` (코드) → 본문 tag `진행 중` (한글).
- 본 spec 의 jsonl `entry.status` 값은 **한글** (예: `"진행 중"`, `"완료"`) 으로 박힘.
- bot.py / mark-polished.sh 는 한글 string 매칭 (`"대기"` / `"진행 중"`) — allowlist constant 도 한글 string 유지.
- `취소` 는 §5-1 표 의 `🔴 폐기` 와 동일 (사용자 표기 가변). impl PR 시 jsonl 실측 후 정합.

#### 신규 status enum 추가 시 영향 path (cross-spec)

`directive-board-template-and-tags.md §5-1` 의 status enum 에 신규 값 추가 시 (예: 가설 `검토 중` 🟠) — 본 spec allowlist 가 **default deny** 이므로 자동으로 polish skip. 안전 default. 단 다음 path 검토 의무:

| # | 영향 file | 영향 내용 | 의사결정 |
|---|---|---|---|
| 1 | `docs/features/directive-board-template-and-tags.md §5-1` | status enum 표 update | 신규 status 정의 (의미 / tag emoji / 부착 시점) |
| 2 | `docs/features/directive-polish-alert-status-filter.md §5-7` (본 spec) | 위 cross-ref 매트릭스 update | 신규 status 가 polish 적합 (✅) / 부적합 (❌) 결정 + 사유 박제 |
| 3 | `tools/discord-daemon/bot.py` `DIRECTIVE_POLISH_ACTIVE_STATUSES` | allowlist 갱신 (적합 시) | 적합 시 frozenset add, 부적합 시 default deny 유지 (코드 변경 0) |
| 4 | `tools/directive-board/mark-polished.sh` case 분기 | allowlist 갱신 (적합 시) | bot.py 와 동일 정합 |
| 5 | `tools/discord-daemon/tests/test_directive_polish_loop.py` | 신규 status 테스트 케이스 add | allowlist 갱신 시 ✅ 케이스 / 미갱신 시 ❌ skip 케이스 |
| 6 | `tools/discord-daemon/tests/test_mark_polished.sh` | 신규 status 테스트 케이스 add | 동일 |
| 7 | `tools/directive-board/directive_status.sh` 분기 | status 전이 API 확장 | 신규 status 진입 / 이탈 분기 정의 |
| 8 | `tools/directive-board/backlog-scan.sh` filter | scan 대상 분기 update | 신규 status 가 backlog 진입 / 이탈 대상인지 결정 |
| 9 | bot.py `directive_polish_loop` skip 사유 enum | `skipped_reason` 값 확장 | 신규 status 별 사유 명시 (예: `under_review` 등) |

**checklist (신규 status 추가 PR)**:

- [ ] template-and-tags §5-1 표에 신규 status 1행 add.
- [ ] 본 spec §5-7 cross-ref 매트릭스에 신규 status 1행 add (✅/❌ 결정 + 사유).
- [ ] 신규 status 가 polish allowlist 적합 결정 시 → bot.py `DIRECTIVE_POLISH_ACTIVE_STATUSES` + mark-polished.sh case + test fixture 양쪽 정합 commit.
- [ ] 부적합 결정 시 → 코드 변경 0 (default deny 자동), spec 본문 사유 박제만.
- [ ] `directive_status.sh` 의 status 전이 분기 update — 신규 status 로 / 부터의 전이 가능 path 정의.
- [ ] `backlog-scan.sh` filter 검토 — 신규 status 가 nmae 분배 대상인지 결정 (현행 default: `대기` 만).
- [ ] forum tag setup 안내 (template-and-tags §5-7) — 사용자 수동 1회 신규 tag emoji + 이름 등록.

**회귀 가드**: 본 spec §11 표에 "status enum 추가 시 default deny" 행 (line 382-383) 이 이미 명시. 신규 status 가 잘못 적합 분류되어 false positive 재발 시 → spec §12 의 env escape hatch (`BOT_DIRECTIVE_POLISH_STATUS_FILTER=0` / `MARK_POLISHED_STATUS_FILTER=0`) 로 fast roll-back.

#### 멱등성

`skipped, skipped_reason=*` mark 된 queue entry 는 다음 iter 에서
`status != "pending"` 으로 자연 skip — 추가 가드 불필요.

mark-polished.sh 의 `polished=true` update 는 항상 수행 (inject 만 분기) —
race 가드 일관성 보존. 다음 호출도 `이미 polished=true — no-op` path 로
자연 멱등 ([[feedback-mark-polished-idempotent]] — 기존 test
`test_mark_polished.sh` Case 2).

## 6) 작업 분할 (예상 PR 리스트)

- [x] PR 1 (본 spec PR, plan): `docs/features/directive-polish-alert-status-filter.md` + `helper-writing-marker-timing-fix.md §10-1` 신설 + frontmatter + root cause + 회귀 가드.
- [ ] PR 2 (impl 가드 A, be): `bot.py` `_process_directive_polish_queue` 의 status filter + test_directive_polish_loop.py 케이스 추가.
- [ ] PR 3 (impl 가드 B, be 또는 helper-launched): `tools/directive-board/mark-polished.sh` 의 nmae inject 분기 status filter + `tests/test_mark_polished.sh` 케이스 추가.

자율 결정 (impl agent): PR 2 + PR 3 묶음 1 PR 가능 (fix 1 issue scope 일관)
또는 분리 2 PR 가능. rev 부담 / 회귀 risk 고려해 묶음 권장.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☑ 없음 (본 PR = docs/features 신설만, bot.py /
  mark-polished.sh 변경은 impl PR 분리).
  - 본 spec PR: `docs/features/` 만 — 보호 영역 외.
  - impl PR: `tools/discord-daemon/bot.py` + `tools/directive-board/mark-polished.sh`
    + test. 모두 정보성 보호 영역 외 (CLAUDE.md §4 목록 외).

## 7) 테스트 전략

### 단위 / 통합 test

#### 가드 A (python) — `tools/discord-daemon/tests/test_directive_polish_loop.py`

(신규 또는 기존 `test_helper_ux.py` 에 케이스 추가)

| 케이스 | fixture (jsonl + queue) | 기대 |
|---|---|---|
| jsonl status=대기 | jsonl `{message_id:X, status:대기}` + queue pending polish task X | polish 진행, queue `done` |
| jsonl status=진행 중 | jsonl `{message_id:X, status:진행 중}` + queue pending polish task X | polish 진행, queue `done` |
| jsonl status=완료 | jsonl `{message_id:X, status:완료}` + queue pending polish task X | polish skip, queue `skipped, skipped_reason=archived_or_completed`, `--forum-edit` 호출 0회 |
| jsonl status=보류 | 동일하나 status=보류 | skip + `skipped, skipped_reason=archived_or_completed` |
| jsonl status=취소 | 동일하나 status=취소 | skip + `skipped, skipped_reason=archived_or_completed` |
| jsonl entry 부재 | jsonl 에 message_id X 없음 + queue pending polish task X | skip + `skipped, skipped_reason=missing` + warning log |
| jsonl 부재 | jsonl 파일 자체 없음 + queue pending polish task X | skip + `skipped, skipped_reason=missing` (loop iter no-op X — task 만 skip) |
| 멱등성 | 위 skip 된 queue entry 가 다음 iter 에 재시도 안 됨 | log 0회 추가 |

#### 가드 B (bash) — `tools/discord-daemon/tests/test_mark_polished.sh` 보강

기존 8 케이스 + 신규:

| 케이스 | fixture | 기대 |
|---|---|---|
| inject 대기 | jsonl `{message_id:X, status:대기}` | polished=true update + nmae inject 정상 호출 |
| inject 진행 중 | jsonl `{message_id:X, status:진행 중}` | polished=true update + nmae inject 정상 호출 |
| inject 완료 skip | jsonl `{message_id:X, status:완료}` | polished=true update (멱등) + inject 호출 0회 + stderr "inject skip: status=완료" |
| inject 보류 skip | jsonl `{message_id:X, status:보류}` | 동일 (inject skip) |
| inject 취소 skip | jsonl `{message_id:X, status:취소}` | 동일 (inject skip) |
| entry 부재 | jsonl 에 message_id X 없음 | stderr "inject skip: entry not found" + exit 0 (멱등) |

### E2E 검증 (impl PR 머지 후)

```bash
# 1. test 케이스 통과 (가드 A + B 양쪽)
cd tools/discord-daemon && python3 -m pytest tests/test_directive_polish_loop.py -xvs
bash tools/discord-daemon/tests/test_mark_polished.sh

# 2. NCP deploy + 실제 시나리오 재현 (이중 가드 검증)
#    Step 1: 새 directive 등록 → 즉시 완료 처리 → 1분 대기
#      → forum thread 본문 변동 0회 확인 (가드 A 차단 evidence)
#      → sudo journalctl -u mobruji-bot -n 100 | grep "directive_polish"
#        → "skip status=완료" 라인 확인
#    Step 2: 완료 entry 에 mark-polished.sh 수동 호출
#      → stderr "inject skip: status=완료" 확인 (가드 B 차단 evidence)
#      → nmae tmux pane 의 inject text 부재 확인
#      → polished=true update 자체는 jsonl 에 박힘 (멱등 보존)
#    Step 3: 정상 시나리오 회귀 확인 — 대기 entry 신규 등록
#      → 1분 안에 forum thread starter body 4 항목 update
#      → nmae tmux pane 에 정상 inject text 가시
```

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 | 본 spec 결정 |
|---|---|---|---|---|
| Q1 | directive jsonl 의 정확한 path | (a) `~/.mobruji/directive-board.jsonl` (b) `tools/directive-board/directive-board.jsonl` | issue #1248 | (a) 확정 (evidence) |
| Q2 | `status` 처리 방식 | (a) allowlist (`대기` / `진행 중`) — 그 외 skip (b) denylist (`완료` / `취소` / ...) — 그 외 진행 | impl PR | (a) 권장 — 미래 status enum 추가 시 default deny |
| Q3 | queue stale skipped entry cleanup 책임 | (a) 본 spec 외 별 spec (b) impl PR 에서 7일 이상 stale skip entry cron 추가 (c) 영구 잔존 (queue 크기 부담 없음) | follow-up | (a) — `helper-subagent-directive-polish.md §7 follow-up` 이전 |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-29: 초안 작성 (status=draft). 사용자 false positive 6건 (알림 ID
  1507983360-1508005692) 박제. issue #1248 의 root cause + fix 후보 (a)+(b)
  채택 + Q1/Q2 결정 동시 박제. (본 spec PR)
- 2026-05-29: §5-7 status enum cross-reference 매트릭스 + 신규 enum 추가 시
  영향 path (9 file) + checklist 추가. 사유: 본 spec allowlist 와
  [[directive-board-template-and-tags]] §5-1 enum 이 다른 spec 에 분산 →
  enum 변경 시 정합성 누락 가능성 박제. 6 status (대기/진행 중/결정 대기/완료/
  폐기/보류) 각각의 polish + inject 적합성 결정 박제. plan sub-agent 후속 PR
  (#1252 보강 사이클).

## 10) 관련

- issue: #1248 (fix(infra): directive polish nmae 알림 status 필터 — 완료
  entry false positive 차단) — 본 spec 의 직접 근원, root cause + fix 채택
  명시.
- spec: `docs/features/helper-subagent-directive-polish.md` — `directive_polish_loop` 본체 spec.
- spec: `docs/features/directive-board-template-and-tags.md` — directive status 5 enum.
- code: `tools/discord-daemon/bot.py` `_process_directive_polish_queue` (현재 ~3532-3600 line).
- code: `tools/discord-daemon/bot.py` `directive_polish_loop` (현재 ~3603-3638 line).
- code: `tools/directive-board/mark-polished.sh` — 가드 B 적용 대상.
- code: `tools/discord-daemon/tests/test_mark_polished.sh` — 기존 8 케이스
  (Case 1-8) + 신규 status filter 케이스.
- PR #1242 / #1243 — `directive_polish_loop` 도입.
- 메모리 후보 (nmae 가 갱신): `subagent/feedback_directive_polish_status_filter.md` 신설 권고
  — "polish task append 시점 ↔ polish loop 처리 시점 race. jsonl status
  cross-check 부재 시 완료된 directive 에 forum-edit false positive 알림 6회
  + mark-polished.sh nmae inject 무차별 호출 (2026-05-29). 이중 가드 (bot.py
  polish loop allowlist + mark-polished.sh inject 분기 status filter) 로 차단."

## 11) 회귀 가드 (impl PR 머지 후)

| 항목 | 가드 |
|---|---|
| 동일 false positive 재발 | test_directive_polish_loop.py 의 status filter 케이스 (대기 / 진행 중 / 완료 / 보류 / 취소 / missing) + test_mark_polished.sh 신규 inject skip 케이스 모두 green |
| jsonl entry 부재 graceful | test 의 missing 케이스 + 운영 환경 `sudo journalctl -u mobruji-bot` 에 warning log 확인 |
| polish 자체 동작 회귀 | 기존 `대기` / `진행 중` 케이스 통과 (polish + forum-edit + mark-polished + done + nmae inject) |
| mark-polished 멱등성 회귀 | 기존 test_mark_polished.sh Case 1-8 모두 통과 — polished=true update 자체는 status 무관 멱등 |
| queue stale skip entry 누적 | spec §8 Q3 follow-up 등록 — 본 spec scope 외 |
| `directive_polish_loop` heartbeat | `record_loop_heartbeat("directive_polish_loop")` 호출 그대로 — 변경 없음 |
| status enum 추가 시 | allowlist (`대기` / `진행 중`) default deny — 새 status 가 들어와도 polish 자동 skip (사고 방지). 새 status 가 polish 적합이면 본 spec 갱신 + allowlist 추가 PR 분리. |

## 12) Roll-back 경로

impl PR 머지 후 회귀 발견 시:

1. **fast roll-back (가드 A)**: env `BOT_DIRECTIVE_POLISH_STATUS_FILTER=0` →
   filter 우회. impl PR 시 env escape hatch 같이 도입 권고.
2. **fast roll-back (가드 B)**: env `MARK_POLISHED_STATUS_FILTER=0` → inject
   분기 filter 우회. impl PR 시 env escape hatch 같이 도입 권고.
3. **full revert**: impl PR 의 git revert. 다시 false positive 발생 risk
   인지하고 진행 — issue #1248 의 6건 사고 재발 가능성 명시.

선택 기준: 회귀 원인이 jsonl path / status enum 오인이면 (1)/(2) + spec
보강. 회귀 원인이 이중 가드 설계 자체 오류면 (3) + 본 spec status=blocked
전환 + alternative spec 작성.
