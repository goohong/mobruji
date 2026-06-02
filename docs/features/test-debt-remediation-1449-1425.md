---
feature: 선재 테스트 부채 정리 방안 (#1449 / #1425)
slug: test-debt-remediation-1449-1425
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1449, 1425]
related_prs: []
last_reviewed: 2026-06-03
---

# 선재 테스트 부채 정리 방안 (#1449 / #1425)

## 1) 개요 (What / Why)
- `tools/discord-daemon` + `tools/agent` 의 pytest 스위트에 누적된 **선재(pre-existing) 실패**를 원인별로 분류하고 정리 우선순위·접근을 정한다.
- 대상: #1449(discord-daemon ~29 + agent 1), #1425(OnMessageDedup 3). 두 이슈의 실패 집합은 **겹친다** (#1425 의 3건 = #1449 의 `test_bot_security` 3건).
- 본 문서는 **분석 전용** — 실제 수정 코드는 작성하지 않는다 (infra 코드라 #1531 이후 또는 mmae 가 진행). 분류 = flaky / 환경 의존 / 실결함(=stale test 포함) 셋 중 하나.
- 재현 기준 커밋: `mobruji` develop tip `fd6a3fb` (2026-06-03). 결과: **discord-daemon 30 failed / 576 passed, agent 1 failed / 98 passed**. (#1449 가 집계한 0015d68 대비 +1 = `extract_directive_id` 1건 추가 — §3-E.)

## 2) 분류 기준 정의
| 분류 | 정의 | 정리 주체 |
|---|---|---|
| **flaky** | 동일 코드/환경에서 실행마다 결과가 흔들림 (타이밍/순서/난수 의존) | — |
| **환경 의존** | 코드는 정상이나 테스트 fixture/stub 이 런타임 환경(라이브러리 버전 등)과 어긋나 결정론적으로 실패 | 테스트 fixture 수정 |
| **실결함 / stale test** | 프로덕션 동작이 (의도적으로) 바뀌었는데 테스트가 옛 동작을 박제 → 테스트 갱신/삭제 필요. 또는 프로덕션 자체 버그 | 테스트 갱신·삭제 또는 코드 fix |

> **재현 결과: flaky 0건.** 33건 전부 매 실행 동일하게 실패하는 **결정론적** 실패다. 따라서 "정리"는 재시도/격리가 아니라 **테스트-코드 정합성 복구**가 본질이다.

## 3) 실패 원인 버킷 (root cause 별)

### A. `FakeClient` 에 `http` 속성 없음 — 공유 fixture 노후 · **13건** · *환경 의존*
- 대상: `test_helper_ux.py::BotAutoAckTests` (10) + `test_bot_security.py::OnMessageDedupOrderTests` (3, = **#1425**).
- root cause: `bot.py:5897 build_client()` 이 `discord.app_commands.CommandTree(client)` 를 생성하고, 해당 생성자(`tree.py:146`)가 `client.http` 에 접근한다. 테스트의 `FakeClient` stub 에는 `http` 속성이 없어 `AttributeError` 발생.
- 판정: **프로덕션 정상** (실제 `discord.Client` 는 `.http` 보유). 테스트 stub 이 코드 변경(CommandTree 도입)을 못 따라간 **환경 의존 fixture 노후**.
- 정리: `FakeClient` 에 `http` 모의 속성 1개 추가 — **단일 지점 수정으로 13건 동시 해소**. 가장 비용 대비 효과 큼.
- #1425 메모: 이슈 본문은 "on_message dedup/claim 순서 로직 또는 mock 어긋남" 을 의심했으나, **실제로는 dedup 로직과 무관** — `_build_handler → build_client` 단계에서 먼저 터진다. 순서 로직 검증까지 도달조차 못 한 상태.

### B. `bot.directive_register_watch_loop` 속성 없음 — 폐기된 기능 테스트 · **5건** · *stale test*
- 대상: `test_directive_register_watch.py` 전체 (5).
- root cause: 해당 loop 은 `bot.py:5351` 주석대로 **2026-05-29 폐기** ("옛 design 의 자동 분류 등록 가정"). 프로덕션에서 의도적으로 제거됨.
- 정리: 테스트 파일 **삭제**. (폐기 PR 에서 테스트 cleanup 이 누락된 drift.)

### C. `bot._process_directive_polish_queue` 속성 없음 — 폐기된 기능 테스트 · **6건** · *stale test*
- 대상: `test_directive_polish_status_filter.py` 전체 (6).
- root cause: `directive_polish_loop` + `_process_directive_polish_queue` 는 `bot.py:4834` 주석대로 **2026-05-29 폐기** (등록 직전 dialogue 가 polish 역할 흡수). `_polish_prompt`/`_run_claude_polish` 만 Phase B 재활용으로 보존.
- 정리: 테스트 파일 **삭제**. (B 와 동일한 2026-05-29 redesign 의 test cleanup 누락.)

### D. ❓ control emoji 추가 HTTP 호출 — discord-reply.sh 채널 게이팅 누락 · **5건** · *실결함(경미) — 결정 필요*
- 대상: `test_cycle_channel_routing.py` (4) + `test_status_channel_routing.py` (1).
- 증상: 채널 push 시 HTTP 호출이 **1건이 아니라 2건** (`POST messages` + `PUT .../reactions/%E2%9D%93/@me`).
- root cause: `discord-reply.sh:1738` 이 본답(main-reply) 모드 push 직후 ❓ control emoji 를 자동 부착 (2026-05-29 신규 기능). 그러나 부착 조건이 `MOBRUJI_CONTROL_EMOJI=1` (default) 뿐 — `--status-channel` / `--cycle-channel` 같은 **비사용자(자동) 채널에도 ❓ 가 붙는다**.
- 판정 갈림: ❓ 는 "사용자가 helper 답에 사유를 묻는" 컨트롤이므로 **사용자 채널(#모부르지)에서만 의미** 있음. digest/cycle 채널엔 반응할 사용자가 없어 **노이즈/leak**. 기능 자체는 의도된 것이나 **게이팅이 빠진 경미한 실결함**으로 본다.
- 정리 권고: ❓ 부착을 `writing_hook` 과 동일하게 **`[[ -n "$REPLY_TO_ID" ]]` (= 실제 사용자 reply) 조건으로 게이팅**. 그러면 자동 채널엔 ❓ 미부착 → 테스트(1건 기대)도 그대로 통과하고 의미도 정합. (대안: 테스트를 2건 기대로 갱신 — 단 ❓ leak 은 방치되므로 비권장.)

### E. `extract_directive_ids_from_body` 줄-중간 prose 미매칭 — #1473 의도 변경 · **1건** · *stale test*
- 대상: `test_bot.py::DirectiveCompleteOnMergeTests::test_extract_directive_id_case_insensitive`.
- 증상: `"DIRECTIVE: ... / closes Directive 1234567890124"` 에서 두 번째 id 미추출.
- root cause: PR #1473(`f37966f`) 가 directive id 매칭에 **줄 시작 앵커**를 도입해 산문 언급 오매칭을 차단함. 줄 중간의 "closes Directive ..." 는 **의도적으로 더 이상 매칭 안 됨**.
- 정리: 테스트를 #1473 동작에 맞게 **갱신** (줄 중간 언급은 미추출이 정답). #1449 집계(0015d68) 이후 추가된 +1 실패 — #1473 PR 이 테스트 동반 갱신을 누락.

### F. `launch_subagent` 가 FileNotFoundError 미발생 — 계약 drift · **1건** · *stale test(추정) — 검증 필요*
- 대상: `tools/agent/tests/test_tools_subagent.py::test_launch_cycle_thread_id_satisfies_pending_requirement`.
- 증상: `launch_subagent(..., cycle_thread_id="T-CYCLE")` 가 wrapper 부재로 `FileNotFoundError` 를 던질 것으로 기대했으나 **미발생** ("DID NOT RAISE").
- root cause(추정): #1401 후속으로 `launch_subagent` 흐름이 바뀌어 wrapper exec 단계 이전에 early-return 하거나, wrapper 경로가 현 체크아웃에서 실제로 존재. 셋 중 하나로 계약이 drift.
- 정리: **수정 전 git blame/실행 추적으로 현 계약 확정 후** 테스트 기대를 갱신(또는 프로덕션 early-return 이 회귀면 코드 fix). 단독 1건이라 정리 우선순위 낮음.

## 4) 집계 · 우선순위

| 버킷 | 건수 | 분류 | 정리 방법 | 비용 | 우선순위 |
|---|---|---|---|---|---|
| A. FakeClient.http | 13 | 환경 의존 | fixture 에 `http` 속성 추가 (1 지점) | 매우 낮음 | **P1** |
| B. register_watch_loop 폐기 | 5 | stale test | 테스트 파일 삭제 | 낮음 | **P1** |
| C. polish_queue 폐기 | 6 | stale test | 테스트 파일 삭제 | 낮음 | **P1** |
| D. ❓ emoji 채널 leak | 5 | 실결함(경미) | `REPLY_TO_ID` 게이팅 | 중간(결정 필요) | **P2** |
| E. directive id 앵커 | 1 | stale test | 테스트 기대 갱신 | 낮음 | **P2** |
| F. launch_subagent 계약 | 1 | stale test(추정) | 계약 확정 후 갱신 | 중간(검증 필요) | **P3** |
| **합계** | **31** | flaky 0 | — | — | — |

> 31 = discord-daemon 30 + agent 1. (#1449 가 ~29 로 적은 건 0015d68 기준이며 이후 E 1건 추가 + 집계 시점차. 본 분석 기준 30+1.)

### 권장 정리 순서 (PR 분할)
1. **PR-1 (P1, 일괄 24건)**: A fixture 1줄 + B/C 테스트 파일 2개 삭제. 위험 0(프로덕션 미변경), 24/31 = 77% 해소. **선행 권장**.
2. **PR-2 (P2)**: D ❓ 게이팅(코드 변경 — discord-reply.sh) + E 테스트 갱신. D 는 동작 변경이라 rev 사이클 신중도 ↑.
3. **PR-3 (P3)**: F 계약 확정 후 갱신. 단독.

## 5) 비기능 / 게이트 메모
- **CI 게이트 미적용 의심 (#1425 지적)**: 이 31건이 develop 에 누적됐다는 것은 pytest 가 머지 게이트로 강제되지 않았음을 시사. 정리 후 `docs/ai-harness/03-quality-gates.md` 에 discord-daemon/agent pytest 를 CI 게이트로 승격할지 **별도 검토 권장** (본 spec 범위 밖, §6 오픈 이슈 후보).
- **테스트 cleanup 동반 의무**: B/C/E 는 모두 "기능 변경 PR 이 테스트 동반 갱신·삭제를 누락" 한 drift. 재발 방지를 위해 폐기/동작변경 PR 의 rev 단계 1 audit 에서 "관련 테스트 갱신 여부" 확인 항목 추가를 제안.

## 6) 후속
- 본 분석 합의 후 정리 작업(코드 수정)은 infra 코드라 mmae/be 사이클 또는 #1531 이후 진행.
- 위 PR-1~3 분할대로 진행 시 #1449·#1425 동시 종결 가능.
