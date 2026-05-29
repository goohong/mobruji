---
feature: cycle-forum placeholder thread_id 가드
slug: cycle-forum-placeholder-guard
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: [1247, 1248, 1336]
last_reviewed: 2026-05-29
---

# cycle-forum placeholder thread_id 가드

## 1) 개요 (What / Why)

- `~/.mobruji/last-launch-thread.txt` (그리고 친구 `helper-current-thread.txt` 등) 에 **Discord snowflake 가 아닌 짧은 placeholder 값** (예: `99999`) 이 atomic write 로 박히는 사고를 차단하기 위한 가드 spec.
- 직전 plan 사이클 §5-1 evidence: sub-agent launch 직후 `~/.mobruji/last-launch-thread.txt` 가 `99999` (5자) 로 박혀 있었음. `discord-reply.sh validate_snowflake` 가 17-20자 강제로 reject → `--auto-thread` fallback chain 도 줄줄이 fail → sub-agent.md §1-11 STRICT 룰 "별 thread 생성 금지" 때문에 thread 신설도 못 함 → **사용자 입장 사이런스** (DIGEST fallback 도 동작 안 하는 케이스).
- 본 spec 은 (a) `atomic_write_thread_file` 에서 **write 직전 snowflake 검증 의무**, (b) write reader 에서 **invalid 값 자동 quarantine**, (c) wrapper 환경변수 가드 (`AGENT_LAUNCH_CREATE_FORUM_THREAD` 와 pending-thread 부재 조합 명시 빈도), (d) test sandbox 가 production file 을 오염시키지 않도록 `LAUNCH_THREAD_FILE` env override 의무 — 네 갈래 가드를 박는다.

## 2) 사용자 시나리오

- nmae 가 `agent-launch-wrapper.sh` 로 sub-agent launch → wrapper 가 pending-thread-id 미보유 + `AGENT_LAUNCH_CREATE_FORUM_THREAD!=1` 이라 forum 신규 thread 생성 skip → DIGEST fallback push → sub-agent 가 `discord-reply.sh --auto-thread` 호출 시 `last-launch-thread.txt` 에 박힌 placeholder `99999` 를 read → `validate_snowflake` reject → 사용자 보고 누락.
- helper 본체가 `--auto-ack-thread` 호출 → Discord API 가 4xx error 응답 → `jq -r '.id // empty'` 가 짧은 / 비어있는 / placeholder 값 추출 → `atomic_write_thread_file` 가 검증 없이 production file 에 박음 → 다음 turn 의 helper 또는 sub-agent 가 그 값을 read → 사이런스.
- test_helper_ux.py 류 unit test 가 fake curl mock (`printf '{"id": "99999"}\n200'`) 으로 discord-reply.sh 를 호출 → `LAUNCH_THREAD_FILE` env override 가 없으면 **production `~/.mobruji/last-launch-thread.txt` 오염** — 테스트 종료 후에도 잔존.

## 3) 요구사항

### 기능 요구사항

- [ ] **F-1**: `atomic_write_thread_file` (discord-reply.sh) 가 write 직전 thread_id 가 `^[0-9]{17,20}$` (snowflake) 인지 검증. 실패 시 write 안 함 + stderr warning + exit 1 (호출자에게 신호).
- [ ] **F-2**: `--auto-ack-thread` mode 의 `jq -r '.id // empty'` 추출 결과를 `validate_snowflake` 한 번 더 통과한 뒤에만 atomic write. 추출 실패 / placeholder / 너무 짧은 값 → file write skip + stderr warning + stdout 빈 줄 (sub-agent inherit chain 끊기 명시).
- [ ] **F-3**: `--auto-thread` mode 의 reader (line 1800 부근) 가 file 에서 read 한 값이 snowflake 가 아닐 경우 — **file 자동 quarantine** (rename to `.txt.invalid-<ts>`) + 다음 fallback chain 으로 진행. quarantine 이력은 stderr warning 으로 표시.
- [ ] **F-4**: `agent-launch-wrapper.sh` 가 pending-thread-id 부재 + `AGENT_LAUNCH_CREATE_FORUM_THREAD!=1` 조건 진입 시 — DIGEST fallback push 와 함께 **현재 stale `last-launch-thread.txt` 가 있으면 invalidate (rename 또는 truncate)**. 다음 sub-agent 가 옛 값 read 사고 차단.
- [ ] **F-5**: unit test (`tests/test_helper_ux.py` 등 fake curl mock 사용 테스트) 는 setUp 에서 `LAUNCH_THREAD_FILE`, `HELPER_THREAD_FILE` env 를 tmpdir 경로로 override 의무. test 폴더에 `conftest.py` 또는 helper fixture 추가 — production `~/.mobruji/` 오염 차단.
- [ ] **F-6**: pre-commit / pre-push hook 에 `LAUNCH_THREAD_FILE` env override 누락된 fake curl mock 테스트 grep 가드 — 신규 test 가 isolation 안 한 채 머지 차단.

### 비기능 요구사항

- **신뢰성**: F-1/F-2 검증 실패가 sub-agent launch 자체를 깨지 않는다 (graceful warning + chain 다음 fallback). 사용자 가시성 보장 우선.
- **관측성**: 모든 reject / quarantine 케이스 = stderr 1줄 warning + bot.py journal `[CYCLE-FORUM-GUARD]` prefix 로 색인. cron digest 가 24h 누적 quarantine 수치 보고.
- **회귀 가드**: test 추가 — fake curl `99999` 응답 → atomic_write 가 skip 되고 stdout 빈 줄 + stderr warning 검증. quarantine rename 동작 검증.
- **보안**: snowflake 검증 정규식은 readable file 의 첫 줄 trim 만 대상으로. 임의 길이 input 으로 ReDoS 가능성 없는지 패턴 확인 (`^[0-9]{17,20}$` 는 안전).

## 4) 범위 / 비범위

### 포함

- `tools/discord-daemon/discord-reply.sh` 의 `atomic_write_thread_file` + `--auto-ack-thread` / `--auto-thread` mode 가드.
- `tools/agent-launch-wrapper.sh` 의 fallback path stale file invalidate.
- unit test isolation (fixture / env override).
- 신규 hook 또는 pre-push grep 가드.

### 제외 (Out of Scope)

- `~/.mobruji/last-launch-thread.txt` 외 다른 thread cache file 의 전반적 schema 재설계 — 본 spec 은 placeholder 가드만.
- Discord API error 응답 형식 자체 분석 / retry 정책 — 별도 spec (`cycle-forum-operation.md` §5-6 fallback chain) 위임.
- bot.py `cycle_thread_complete_on_merge_loop` 의 PR body grep — 별도 spec.
- `AGENT_LAUNCH_CREATE_FORUM_THREAD` env 자체 폐기 / 운영 전환 — `cycle-forum-operation.md` SoT.

## 5) 설계

### 5-1) 도메인 모델

- 본 spec 은 사이클 forum 운영 인프라 영역. `docs/ai-harness/06-domain-model.md §4` 의 유비쿼터스 랭귀지에 다음 용어 추가 후보 (별도 PR plan §1-1 위임):
  - **cycle launch thread id**: sub-agent launch 시 wrapper 가 emit 하는 forum thread 의 Discord snowflake (17-20자).
  - **launch thread cache file**: `~/.mobruji/last-launch-thread.txt` — atomic write 로 launch thread id 를 박아 다음 sub-agent inherit 보장.
  - **placeholder thread id**: snowflake 검증 통과 못 하는 값 (예: 짧은 정수, 빈 값, JSON literal). 사이런스 사고의 원인.

### 5-2) API 엔드포인트

해당 없음 (인프라 스크립트).

### 5-3) 외부 연동

- Discord API `POST /channels/{id}/messages/{mid}/threads` 응답 형식. 4xx / 5xx 시 응답 body 가 `{"id": ...}` 가 아닐 수 있음 — `jq -r '.id // empty'` 가 빈 문자열 또는 nonsense 추출 가능.

### 5-4) 데이터 흐름 / 시퀀스

```
[user input]
    ↓
helper/nmae turn
    ↓
discord-reply.sh --auto-ack-thread "<ack>"
    ├─ start_thread_from_message 호출 (Discord API POST)
    ├─ THREAD_RESPONSE = {"id": "..."} or error JSON
    ├─ NEW_THREAD_ID = jq '.id // empty'
    ├─ [GUARD F-2] validate_snowflake "$NEW_THREAD_ID"
    │      ├─ pass → atomic_write_thread_file (with F-1 재검증)
    │      └─ fail → stderr warning + exit 0 (write skip, sub-agent inherit chain 끊김)
    ↓
~/.mobruji/last-launch-thread.txt (valid snowflake 만 박힘)
    ↓
sub-agent launched by nmae
    ↓
discord-reply.sh --auto-thread "<milestone>"
    ├─ LAUNCH_THREAD_FILE read
    ├─ [GUARD F-3] validate_snowflake "$LAUNCH_RAW"
    │      ├─ pass → POST /channels/$thread_id/messages
    │      └─ fail → file quarantine (.txt.invalid-<ts>) + 다음 fallback (HELPER_THREAD_FILE / DIGEST)
    ↓
사용자 가시
```

### 5-5) DB 마이그레이션

해당 없음.

### 5-6) 프론트엔드 화면

해당 없음.

## 6) 작업 분할 (예상 PR 리스트)

- [ ] **PR A**: `discord-reply.sh` 의 `atomic_write_thread_file` 에 F-1 검증 추가 + `--auto-ack-thread` 의 F-2 추출 후 재검증. fake curl mock 테스트 추가.
- [ ] **PR B**: `discord-reply.sh` 의 `--auto-thread` reader (line 1800 부근) F-3 quarantine rename 로직. quarantine 시 stderr `[CYCLE-FORUM-GUARD] placeholder thread_id quarantined: <path>` 표준 format.
  - F-3 quarantine 후 fallback chain 종착점 = **`--digest` 단발 모드 1회 push + nmae 보고** (sub-agent.md §1-11 별 forum thread 신설 금지 룰 준수). `docs/ai-harness/14-discord-ops.md §8-7` cross-ref 박제 완료 (plan round 16, 2026-05-29).
  - cron digest signature 추가 의무: `🛡️ cycle-forum guard quarantine: N건 (last-launch-thread placeholder) — 직전 24h` (관측성 비기능 요구사항 §3 참조).
- [ ] **PR C**: `agent-launch-wrapper.sh` F-4 — pending-thread-id 부재 진입 시 `last-launch-thread.txt` stale invalidate.
- [ ] **PR D**: test fixture / conftest 작성 (F-5) + 기존 `test_helper_ux.py` 류 setUp 에 env override 추가 일괄 리팩터.
- [ ] **PR E**: pre-push hook (F-6) — `printf '{"id":` mock 사용 테스트 grep + `LAUNCH_THREAD_FILE` env override 부재 시 차단. `06-domain-model.md §4` 용어 등재 함께.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 / ☑ 있음 — (PR E 한정) `.github/workflows/` 가 아닌 `tools/git-hooks/pre-push` 변경 가능성. 본 spec 단계에서는 코드 변경 없음 (docs only). 구현 PR 단계에서 라벨 명시 의무 — 정보성 (CLAUDE.md §4 — rev 사이클 통과 의무 동일).

## 7) 테스트 전략

- **단위**: discord-reply.sh bash unit test (`tests/test_atomic_write_thread_file.bats` 후보) — `validate_snowflake` 호출 분기, atomic_write skip 검증, quarantine rename 검증.
- **통합**: `tests/test_helper_ux.py` 류 fake curl mock + `LAUNCH_THREAD_FILE` tmp override 로 production 오염 없는지 검증.
- **회귀 가드**: 기존 `99999` mock 응답 케이스에서 production `~/.mobruji/last-launch-thread.txt` mtime 변경 안 됨을 명시 검증.
- **e2e**: 본 spec 은 인프라 스크립트라 rev 단계 1 (코드 review) + 단계 2 (dev 환경 helper turn 1회 검증) 통과로 충분. 단계 3 production 검증은 release PR 단계에서.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | F-3 quarantine 시 rename vs truncate 선택 | (a) rename `.invalid-<ts>` — 사후 분석 / cron 가비지 콜렉터 / (b) truncate — 단순 / 디스크 차지 X | @goohong / 다음 plan 사이클 |
| Q2 | F-4 wrapper invalidate 가 race 사고 일으키지 않는가 (helper / nmae 동시 launch) | (a) flock 의무 / (b) 그냥 truncate (race 시 둘 다 빈 값 → 다음 fallback OK) | @goohong / PR C 구현 시 |
| Q3 | F-6 hook 이 false-positive 없이 정확히 mock 패턴 잡는가 | (a) 정확한 grep / (b) AST 기반 검사 (오버킬) | @goohong / PR E 구현 시 |
| Q4 | `06-domain-model.md §4` 등재 시 "placeholder thread id" 를 명시 용어로 박을지, 사고 박제 메모리로만 둘지 | (a) 등재 — 다른 spec 참조 가능 / (b) 메모리만 — 도메인 용어 noise 회피 | @goohong / 다음 plan 사이클 |

## 9) 결정 로그

- 2026-05-29: 초안 작성 (status=draft). 직전 plan 사이클 §5-1 evidence (`~/.mobruji/last-launch-thread.txt` = "99999") + root cause 추적 (`test_helper_ux.py` fake curl mock `{"id": "99999"}` 가 isolation 부재로 production file 오염) + discord-reply.sh atomic_write_thread_file 검증 없음 + `validate_snowflake` 이미 17-20자 강제이긴 하나 read 시점만 catch (write 시점은 무방어).
- **2026-05-29 (plan round 16)**: §6 PR B (F-3 quarantine) 의 fallback chain 종착점 박제 — `--digest` 단발 모드 1회 push + nmae 보고. `sub-agent.md §1-11` STRICT 룰 (별 forum thread 신설 금지) 의 fallback 종착이 사일런스가 아니라 "DIGEST 단발 + nmae 알림" 임을 명시. `docs/ai-harness/14-discord-ops.md §8-7` 신설 sub-section 에 F-1~F-4 매트릭스 + `--digest` 종착점 + cron digest signature (`🛡️ cycle-forum guard quarantine: N건`) cross-ref 박제 완료. 본 spec §6 PR B 항목에 14-discord-ops cross-ref 마커 추가 + frontmatter `related_prs` 에 #1336 추가. plan round 16 trigger.
