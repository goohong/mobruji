---
feature: forum_edit_starter template validation guard (PR F)
slug: forum-starter-template-guard
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-30
---

# forum_edit_starter template validation guard (PR F)

## 1) 개요 (What / Why)

`forum_edit_starter` agent tool 이 forum thread starter message body 를 **무조건 덮어쓴다** — template 양식 (📌 / 💬 / 🆔 / 📋 / 🔖 / footer) 위배 시 reject / warning 가드 0. sub-agent / nmae 가 자유롭게 양식 무시한 PATCH (예: "PR 링크만 + 작업 끝") 적용 가능 — 실제 발생 확인.

**사용자 정정 (2026-05-30)**:
> "포럼 작업 갱신한 거 보니까 원래 있던 양식에 값을 채워넣거나 새로운 내용을 적는게 아니라 아예 양식 무시하고 뭐 pr링크랑 띡 작업끝 이렇게 해두더라고. 기존 양식을 위배하지 않아야할 것 같은데, 어떻게 생각해?"

본 spec = **template 위배 강제 차단 메커니즘** 도입. CLAUDE.md §17 ("학습 의존 핑계 금지 — 같은 사고가 두 번째면 강제 메커니즘 (hook / wrapper / system prompt) 도 같이 제안") 정신 적용. directive-board-template-and-tags.md (template SoT) + cycle-forum-operation.md / agent-launch-wrapper.sh (cycle forum template SoT) 의 follow-up.

## 2) 사용자 시나리오

- **시나리오 1 (정상 PATCH — milestone update)**: be sub-agent 가 PR 생성 milestone 도달 → 기존 starter body read → 📋 진행 체크박스 [x] 갱신 + 🔖 관련 섹션에 PR URL append → `forum_edit_starter(thread_id, body)` 호출. validation pass — template marker (📋 / 🔖 / 📌 또는 🛠️ / 🆔) 모두 존재. body PATCH 적용.
- **시나리오 2 (위배 시도 — graceful reject)**: rev sub-agent 가 round 종료 보고 시 starter body 를 "## 완료\n\nPR #1234" 로 덮어쓰려 시도. validation 가 template marker grep fail → graceful reject (기존 starter 보존 + warning log + 사용자 가시 alert). sub-agent 가 retry 시 기존 body 를 read 해 마커 유지한 채 PATCH.
- **시나리오 3 (운영 점검)**: 사용자가 forum sidebar 에서 thread 회고 시 모든 starter 가 template 양식 유지 — "어떤 사이클이 어디까지 진행됐는지" 한눈에 파악 가능.

## 3) 요구사항

### 기능 요구사항

- [ ] **layer 1 (hard guard, 최우선)** — `bot.py _forum_edit_starter` 에 validation 추가. body 가 template marker pattern grep 실패 시 graceful reject (starter 보존 + warning log) + alert push (사용자 가시).
- [ ] **layer 2 (인지)** — `tools/agent/tool_definitions.py` `forum_edit_starter` tool description 보강. template SoT 인용 + 정상 / 위배 예시 박제.
- [ ] **layer 3 (학습)** — `docs/ai-harness/actors/sub-agent.md §2-be / §2-fe / §2-rev / §2-plan` 에 "starter 본문 PATCH 전 기존 본문 read 의무" 룰 추가 (read → 마커 유지한 채 업데이트).
- [ ] **layer 4 (감지)** — nmae digest 가 매일 forum thread template 양식 검사 + 위배 thread 1+ 시 사용자 alert.
- [ ] **layer 5 (보조)** — 메모리 `feedback_forum_template_no_bypass.md` + CLAUDE.md §4 "비협상 룰" 한 줄 박제.
- [ ] **신규 모듈** — `tools/discord-daemon/lib/forum_template_validator.py` validation regex SoT (모듈 단일 진실 — 단위 테스트 + bot.py + nmae digest 가 공유).

### 비기능 요구사항

- **regex 정확성**: false positive (정상 update 도 reject) / false negative (위배 통과) 가드. SoT 모듈 단위 테스트 N≥10 case (정상 / 위배 / 경계).
- **graceful**: validation 실패 = starter 보존 (덮어쓰기 skip) + warning log + alert 1회. sub-agent crash 안 시킴.
- **alert 채널**: DIGEST 1회 push + 해당 cycle forum thread 안 댓글 (sub-agent 가 자기 사이클 thread 에 사고 인지). 사용자 reply 채널은 사용 안 함 (잡음 ↓).
- **로그 박제**: `~/.mobruji/forum-template-violations.jsonl` append (thread_id / cycle / attempted_body 80자 / ts) — 회고 / 통계 / regex 정확성 평가.
- **idempotency**: 같은 thread + 같은 위배 body 재 호출 시 alert 1회만 (jsonl grep 가드).

## 4) 범위 / 비범위 (중요)

### 포함 (본 spec)

- `bot.py _forum_edit_starter` validation 추가 (layer 1).
- `tool_definitions.py` `forum_edit_starter` description 보강 (layer 2).
- `actors/sub-agent.md` §2 룰 추가 (layer 3).
- `lib/forum_template_validator.py` 신규 모듈 (regex SoT) + 단위 테스트.
- nmae digest cross-check hook (layer 4).
- 메모리 + CLAUDE.md (layer 5).

### 제외 (Out of Scope)

- `forum_create_thread` validation — thread 생성 시점은 wrapper / directive_append.sh / discord-reply.sh `--forum-post-auto-tag` 가 이미 template 박음. 별 트랙 (수동 PATCH 가 아니라 자동 build 이므로 위배 risk ↓).
- `forum_comment` validation — 댓글은 milestone 1줄 stream 이라 양식 X. validation 대상 X.
- `forum_retag` validation — tag 전이만, body 영향 X.
- 기존 위배된 thread cleanup — 본 spec 은 신규 PATCH 만 가드. 기존 thread backfill 은 §10 오픈 질문.
- `directive_status.sh` (사용 안 함, jsonl + tag 전이 SoT) 와의 통합.

## 5) 설계

### 5-1) 도메인 모델

신규 도메인 용어 — `docs/ai-harness/06-domain-model.md §4` 등재 의무:

| 한국어 | 영어 (코드) | 정의 |
|---|---|---|
| forum starter template marker | ForumStarterTemplateMarker | forum thread starter body 가 template 양식 (directive / cycle 양식 공통) 임을 식별하는 markup 패턴 집합. 정규 marker = `📌` 또는 `🛠️` (title prefix), `📋 진행` (체크박스 섹션), `🔖 관련` (관련 링크 섹션), `🆔` (id line), footer `---` + `_갱신: ` (모두 필수 N=5). validation = N=5 중 ≥4 매칭 시 pass (graceful — 1개 missing 은 marker 누락 우연 허용). 출처: `directive-board-template-and-tags.md §5-2·§5-3` + `agent-launch-wrapper.sh:421-450` (`_build_cycle_template_body`) |
| 양식 위배 시도 | ForumTemplateViolationAttempt | `forum_edit_starter` 호출 body 가 `ForumStarterTemplateMarker` 매칭 실패한 PATCH 시도 1건. 박제 항목 = thread_id / cycle / actor (be/fe/rev/plan/nmae) / attempted_body 첫 80자 / ts. `~/.mobruji/forum-template-violations.jsonl` append. validation reject 후에도 sub-agent 가 재시도 가능 (학습 후 정상 PATCH 적용 기대). 출처: 본 spec §5-3 |
| template validation 모듈 | ForumTemplateValidator | `tools/discord-daemon/lib/forum_template_validator.py` 모듈. regex / marker count / pass-fail 판단 SoT. bot.py + nmae digest hook + 단위 테스트 가 공유. 변경 시 marker 집합 변경 전 모든 consumer 영향 분석 의무. 출처: 본 spec §5-2 |

### 5-2) Template marker 패턴 (regex SoT)

`lib/forum_template_validator.py` 단일 진실:

```python
# template marker — directive / cycle forum 공통
TEMPLATE_MARKERS = {
    "title_prefix": r"^(📌|🛠️)\s+\*\*.+\*\*",          # 제목 prefix (직접 1줄)
    "progress_section": r"📋\s+진행",                    # 진행 섹션
    "related_section": r"🔖\s+관련",                     # 관련 섹션
    "id_line": r"🆔",                                    # 사이클 id / directive id line
    "footer_update": r"---[\s\n]+_갱신:\s+",             # footer + 갱신 시각
}

# pass 기준 — N=5 중 ≥4 매칭 (graceful, marker 1개 누락 허용)
PASS_THRESHOLD = 4

def validate(body: str) -> tuple[bool, list[str]]:
    """body 가 template 양식 매칭 여부 + 매칭 / 미매칭 marker 목록 반환.

    Returns
    -------
    (passed, matched_markers, missing_markers)
        passed = matched_count >= PASS_THRESHOLD
        matched_markers = pass 한 marker name list
        missing_markers = fail 한 marker name list
    """
    ...
```

**pass 기준 = ≥4 / 5**:
- 5/5 = ideal
- 4/5 = graceful (예: directive starter 에 🆔 line 만 누락 — 이전 template 호환)
- ≤3/5 = reject (양식 위배 의심)

**위배 예시** (≤3 매칭):
- `"PR #1234 작업 끝"` — 0/5
- `"## 완료\n\n링크: https://..."` — 0/5
- `"🛠️ 작업 완료\n\nPR #1234"` — 1/5 (title_prefix 만)

**정상 예시** (≥4 매칭):
- `agent-launch-wrapper.sh _build_cycle_template_body` 출력 — 5/5
- `directive_append.sh build_template_body` 출력 — 5/5
- sub-agent milestone PATCH (`📋 진행 [x] PR 생성 — #1234`) — 5/5

### 5-3) bot.py `_forum_edit_starter` validation 적용

```python
async def _forum_edit_starter(client: discord.Client, payload: dict) -> None:
    """forum thread starter message body PATCH — template validation 가드 적용."""
    thread_id = int(payload.get("thread_id", 0))
    body = payload.get("body", "")
    thread = client.get_channel(thread_id)
    if thread is None:
        logger.warning("forum_edit_starter: thread %s 미발견 — drop", thread_id)
        return

    # ─── PR F (forum-starter-template-guard) — validation 가드 ─────
    from lib.forum_template_validator import validate
    passed, matched, missing = validate(body)
    if not passed:
        # graceful reject — starter 보존 + warning + violation log + alert
        logger.warning(
            "forum_edit_starter REJECT: thread=%s matched=%s missing=%s body_head=%r",
            thread_id, matched, missing, body[:80],
        )
        _append_template_violation(thread_id, payload, matched, missing)
        await _alert_template_violation(client, thread_id, missing)
        return
    # ───────────────────────────────────────────────────────────────

    try:
        starter = await thread.fetch_message(thread_id)
        await starter.edit(content=body)
        logger.info("forum_edit_starter: thread=%s len=%d", thread_id, len(body))
    except Exception as exc:  # noqa: BLE001
        logger.warning("forum_edit_starter 실패 thread=%s exc=%r", thread_id, exc)
```

**graceful path 선택 사유**: hard reject (RuntimeError) 시 sub-agent 가 catch 안 하면 crash + 사이클 중단 — operation cost ↑. graceful = warning + log + alert 로 회고 가능하며 sub-agent 가 다음 turn 재시도 가능. mobruji 운영 패턴 일치 (모든 wrapper 가 graceful path 우선).

### 5-4) tool description 보강 (layer 2)

`tools/agent/tool_definitions.py`:

```python
@tool(
    name="forum_edit_starter",
    description=(
        "Forum thread starter body PATCH. **template 양식 의무** — "
        "기존 starter 본문을 read 후 마커 유지한 채 update. "
        "필수 마커 5: 📌|🛠️ title / 📋 진행 / 🔖 관련 / 🆔 id / footer (---/_갱신:). "
        "4/5 미만 매칭 시 bot.py 가 graceful reject + alert. "
        "정상 예: 'agent-launch-wrapper.sh _build_cycle_template_body' 출력 갱신. "
        "위배 예: 'PR #1234 작업 끝' (마커 0 — reject). "
        "SoT: docs/features/forum-starter-template-guard.md §5-2."
    ),
    input_schema={"thread_id": str, "body": str},
)
async def forum_edit_starter(args: dict[str, Any]) -> dict[str, Any]:
    ...
```

### 5-5) sub-agent.md §2 룰 추가 (layer 3)

`actors/sub-agent.md §2-be / §2-fe / §2-rev / §2-plan` 공통 추가:

> **starter body PATCH 전 기존 본문 read 의무** (PR F, `docs/features/forum-starter-template-guard.md`):
> - `forum_edit_starter(thread_id, body)` 호출 전 **반드시 기존 starter body 를 read** (Discord API fetch 또는 thread 본문 가시 확인).
> - 기존 마커 5 (📌|🛠️ / 📋 / 🔖 / 🆔 / footer) 를 **유지한 채** 부분 update — 체크박스 [x] 갱신 / 🔖 관련 섹션에 PR URL append / footer 갱신 시각.
> - 양식 통째 덮어쓰기 (예: "PR #1234 작업 끝") 금지 — bot.py 가 graceful reject + alert.
> - milestone update 표준 절차: `actors/sub-agent.md §1-9` "milestone 시 본문 PATCH" 의 cat <<'EOF' 양식 사용 — 그대로 복제하면 마커 5/5 보장.

### 5-6) nmae digest cross-check (layer 4)

`tools/cycle-status/digest.sh` 또는 nmae 의 매일 digest 호출에 hook 추가:

```bash
# forum thread template violation cross-check
python3 -c "
from tools.discord_daemon.lib.forum_template_validator import scan_all_threads
violations = scan_all_threads()  # discord API fetch — N개 forum thread starter read
if violations:
    print(f'⚠️ forum template 위배: {len(violations)}건')
    for v in violations[:5]:
        print(f'  - thread={v.thread_id} matched={v.matched}/5')
"
```

**graceful**: discord API rate limit / fetch 실패 시 skip. digest 자체 중단 X.

**가시화**: digest 채널 1회 push + 사용자가 forum sidebar 에서 직접 확인 가능.

### 5-7) 데이터 흐름 / 시퀀스

```text
sub-agent (be) milestone (PR 생성)
    ↓
[정상 path]
1. discord-reply.sh --forum-edit <thread> "<template body with 5 markers>"
2. → discord-daemon → _forum_edit_starter
3. → validate(body) → passed=True (5/5)
4. → starter.edit(content=body) → Discord 적용
5. → logger.info("OK")

[위배 path]
1. sub-agent 가 starter read skip + "PR #1234 작업 끝" 으로 PATCH 시도
2. → discord-reply.sh --forum-edit <thread> "PR #1234 작업 끝"
3. → discord-daemon → _forum_edit_starter
4. → validate(body) → passed=False (0/5)
5. → starter 보존 (덮어쓰기 skip)
6. → warning log + jsonl append (~/.mobruji/forum-template-violations.jsonl)
7. → alert push (DIGEST 채널 + 해당 cycle forum thread 안 댓글)
8. → sub-agent 가 다음 turn 에 starter read + 마커 유지한 채 재시도

[감지 path — nmae digest]
1. 매일 digest 호출 시 scan_all_threads() — N개 forum thread starter fetch
2. → validate(body) 각각 — fail thread 1+ 시 alert
3. → DIGEST 채널 push: "⚠️ forum template 위배 N건"
4. → 사용자가 sidebar 에서 thread 확인 후 사용자 결정 (수동 backfill / 무시)
```

### 5-8) API / 모듈 인터페이스

신규 모듈 `tools/discord-daemon/lib/forum_template_validator.py`:

| 함수 | 인자 | 반환 | 용도 |
|---|---|---|---|
| `validate(body: str)` | body markup string | `(passed: bool, matched: list[str], missing: list[str])` | bot.py + 단위 테스트 |
| `scan_all_threads(client)` | discord client | `list[Violation]` | nmae digest |
| `format_alert(missing: list[str])` | missing marker names | str (한국어 사용자 가시 1줄) | alert push body |

신규 jsonl `~/.mobruji/forum-template-violations.jsonl` (append-only, 1줄 1 entry):

```json
{
  "thread_id": "1509466456230989926",
  "cycle": "be",
  "actor": "be sub-agent",
  "attempted_body_head": "PR #1234 작업 끝",
  "matched": ["title_prefix"],
  "missing": ["progress_section", "related_section", "id_line", "footer_update"],
  "ts": "2026-05-30T12:34:56+09:00"
}
```

### 5-9) DB 마이그레이션

없음. jsonl 파일 only (`~/.mobruji/forum-template-violations.jsonl`).

### 5-10) 프론트엔드 화면

없음.

## 6) 작업 분할 (예상 PR 리스트)

**옵션 A — 단일 PR (권고)**:
- [ ] PR F: validator 모듈 + bot.py 적용 + tool description + sub-agent.md + 메모리 + CLAUDE.md + 단위 테스트.
- 사유: 한 layer 만 머지하면 다른 layer 가시화 X. 5 layer 가 함께 시너지 (hard guard + 인지 + 학습 + 감지 + 보조).

**옵션 B — 2 PR (분리)**:
- [ ] PR F-1: validator 모듈 + 단위 테스트 (scope:infra, type:feat) — 독립 mergeable.
- [ ] PR F-2: bot.py 적용 + tool description + sub-agent.md + nmae digest + 메모리 (scope:infra, type:feat) — F-1 의존.
- 사유: F-1 만으로도 SoT 박혀 다른 PR 이 import 가능. F-2 가 rev 단계 1 통과 시간 ↓ (F-1 라인 수 ↓).

**본 spec 권고 = 옵션 A** (단일 PR). 사유: F-1 만 머지 + F-2 머지 지연 시 layer 1 (강제 차단) 활성화 안 됨 = 사용자 의도 미충족. 2 PR 분리 이득 < 한 PR 동시 활성화 이득.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 / ☒ 있음 (선택 layer 4 추가 시)
  - `tools/cycle-status/digest.sh` — 본 spec layer 4 시 hook 추가. 단순 호출만 — 회귀 risk ↓.
  - `.github/workflows/` 영향 X (workflow 변경 없음).
  - `backend/build.gradle*` / `web/package.json` / `Dockerfile` 영향 X.

## 7) 테스트 전략

### 단위 (필수)

- `lib/forum_template_validator.py` regex 정확성 — N≥10 case:
  - 정상 5/5: cycle forum template (`_build_cycle_template_body` 출력 그대로)
  - 정상 5/5: directive template (`build_template_body` 출력)
  - 정상 4/5: directive template 에서 🆔 line 만 누락 (graceful)
  - 위배 0/5: `"PR #1234 작업 끝"`
  - 위배 0/5: `"## 완료\n\n링크: https://..."`
  - 위배 1/5: `"🛠️ 작업 완료\n\nPR #1234"` (title_prefix 만)
  - 위배 2/5: 마커 2 개만 (경계 — fail 의무 확인)
  - 위배 3/5: 마커 3 개만 (경계 — fail 의무 확인)
  - 정상 5/5 with emoji variant: 🛠️ vs 📌 둘 다 매칭
  - empty body: `""` → 0/5 reject

### 통합 (필수)

- bot.py `_forum_edit_starter` 호출 — 정상 body → starter.edit 호출 / 위배 body → starter.edit 호출 X + violation jsonl append + alert.
- nmae digest hook — discord API mock 으로 N=3 thread (정상 2 / 위배 1) → alert 1건.

### 수동 (NCP 배포 후)

1. develop 머지 후 `tools/discord-daemon/deploy.sh`.
2. be sub-agent 임의 forum thread 에 정상 PATCH (template 유지) → 적용 확인.
3. helper 가 의도적으로 위배 body (`"테스트 위배"`) PATCH 시도 → starter 보존 + warning log + DIGEST alert 확인.
4. nmae digest 호출 → 위배 thread 1+ 시 alert push 확인.
5. `~/.mobruji/forum-template-violations.jsonl` cat → entry 박힘 확인.

### Mock 전략

- `discord.Client` mock — `get_channel` / `fetch_message` / `edit` stub.
- `forum.available_tags` mock — tag list stub.

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | graceful vs hard reject | (a) graceful (starter 보존 + warning + alert) / (b) hard (RuntimeError raise → sub-agent catch 안 하면 crash) | @goohong / be 사이클 launch 전 |
| Q2 | alert 채널 | (a) DIGEST 1회 + 해당 cycle forum thread 댓글 (권고) / (b) DIGEST 만 / (c) DIGEST + 사용자 reply 채널 (잡음 ↑) | @goohong / be 사이클 launch 전 |
| Q3 | 기존 위배 thread cleanup 정책 | (a) 본 spec out-of-scope, 기존 thread 무시 / (b) 별 backfill PR 신설 / (c) nmae digest 가 사용자에게 backfill 권고 1회 | @goohong / be 사이클 launch 후 |
| Q4 | PASS_THRESHOLD 4 vs 5 | (a) 4/5 (graceful, marker 1개 누락 허용) / (b) 5/5 (strict, 모두 매칭 의무) | @goohong / be 사이클 launch 전 |
| Q5 | regex 변경 시 기존 thread 호환성 | (a) marker 집합 변경 = breaking, 모든 consumer 영향 분석 의무 / (b) marker version field 도입 (`template_version: v1/v2`) — over-engineering | @goohong / be 사이클 launch 후 |
| Q6 | layer 4 (nmae digest cross-check) 본 PR vs 별 PR | (a) 본 PR — 한 번에 5 layer 동시 활성화 / (b) 별 PR — digest hook 은 follow-up | @goohong / be 사이클 launch 전 |

## 9) 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-30: 초안 작성 (status=draft). 사용자 정정 "포럼 작업 갱신한 거 보니까 ... 양식 무시하고 뭐 pr링크랑 띡 작업끝 ... 기존 양식을 위배하지 않아야할 것 같은데" → template validation guard spec 신설.

## 10) 자율 결정 (사유)

- **graceful reject 권고** (Q1): hard reject 시 sub-agent 가 catch 안 하면 사이클 crash → operation cost ↑. graceful = log + alert 로 회고 가능하며 sub-agent retry 가능. mobruji 모든 wrapper 가 graceful 우선 패턴 일치.
- **alert = DIGEST + cycle thread 댓글** (Q2): DIGEST 만 시 sub-agent 가 자기 사고 모름 (학습 못 함). 사용자 reply 시 잡음 ↑. cycle thread 댓글 = sub-agent 가 다음 turn 에 자기 thread 보고 인지.
- **PASS_THRESHOLD = 4/5** (Q4): 5/5 strict 시 기존 template 의 marker 1개 누락 (예: 🆔 line 없는 옛 directive entry) 도 reject — 회귀 위험. 4/5 = graceful, marker 1개 누락 허용으로 backward compat.
- **단일 PR 권고** (옵션 A, §6): layer 5 동시 활성화 시너지가 분리 이득 초과.

## 11) 사용자 확인 필요

- Q1-Q6 사용자 결정 (위 표).
- 본 spec approved 전환 시 PR F be 사이클 launch 준비 완료.
- be 사이클은 본 spec 기반 구현 — 옵션 A (단일 PR) / 옵션 B (2 PR) 사용자 선택.

## 12) References

- `docs/features/directive-board-template-and-tags.md` — template SoT (📌 / 💬 / 🆔 / 📋 / 🔖 / footer 양식).
- `docs/features/cycle-forum-operation.md` — cycle forum 운영 SoT.
- `docs/ai-harness/actors/sub-agent.md §1-9 / §1-10 / §2` — sub-agent template PATCH 룰.
- `tools/agent/tool_definitions.py:142-152` — `forum_edit_starter` tool 정의 (변경 대상).
- `tools/discord-daemon/bot.py:2084-2097` — `_forum_edit_starter` 구현 (변경 대상).
- `tools/agent-launch-wrapper.sh:421-450` — cycle forum template SoT (`_build_cycle_template_body`).
- `tools/discord-daemon/directive_append.sh` — directive template SoT (`build_template_body`).
- 메모리: [[feedback-evidence-based-root-cause]] / [[feedback-verify-and-iterate]] / [[feedback-autonomous-default]] (CLAUDE.md §17 — 학습 의존 핑계 금지, 강제 메커니즘 의무).

## 13) 변경 이력

- 2026-05-30: 초안 작성 (status=draft). sub-agent.md §1-9 / §1-10 의 template 룰은 학습 의존 — 강제 메커니즘 layer 1 (bot.py validation) 추가로 사용자 발견 사고 (양식 위배) 차단.
