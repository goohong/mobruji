---
feature: cycle-status.json — 4 워크트리 진행/완료 상태 영속 파일
slug: cycle-status-json
status: shipped
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: [#804, #806]
last_reviewed: 2026-05-23
---

# cycle-status.json — 4 워크트리 진행/완료 상태 영속 파일

## 1) 개요 (What / Why)
nmae(maestro 본진) 가 매 sub-agent launch / 완료 / PR 머지 시점에 **실시간 갱신**하는 단일 상태 파일. bot.py 의 `digest_loop` 가 일정 주기로 읽어 #모부르지(또는 digest 채널) 로 4 워크트리(be/fe/rev/plan) 한 줄씩 push 합니다.

기존 GitHub events 기반 PR 카운트 digest 가 부정확/지연되는 문제를 단순화 결정으로 폐기하고, nmae 자기 보고를 단일 진실 소스(SSoT) 로 채택했습니다 (사용자 결정 — 2026-05-23, [[feedback-cycle-status-json]]).

> **권위**: 본 spec 은 schema 의 **계약** 만 명시합니다. 직렬화/파싱 구현의 진실 소스는 `tools/discord-daemon/bot.py` (`read_cycle_status` / `_format_in_progress` / `format_cycle_digest`) 입니다. 구현과 본 spec 이 충돌하면 코드를 우선하고 본 spec 을 갱신합니다.

## 2) 사용자 시나리오
- (S1) **nmae 가 be 사이클 launch** → `cycle-status.json` 의 `be.in_progress` 를 `{"issue": "#N", "title": "...", "started_at": "..."}` 로 갱신 → 다음 digest tick 에 채널에 `[be] 진행: #N <title> / 최근: ...` 표시.
- (S2) **be PR 머지 완료** → nmae 가 `be.last_completed` 를 `{"pr": "#M", "title": "...", "merged_at": "..."}` 로 갱신 + `be.in_progress` 를 `null` (또는 다음 사이클 dict) 로 전환.
- (S3) **rev 사이클 가동** → `rev.in_progress = {"target": "PR #N", "title": "...", "started_at": "..."}` (rev 워크트리는 issue/PR 식별자 대신 `target` 사용).
- (S4) **파일 누락/깨짐** → bot.py 는 graceful fallback — digest 본문에 `(cycle-status.json 읽기 실패 — 본진 갱신 대기)` 한 줄만 표시 (crash 안 함).

## 3) 요구사항
### 기능 요구사항
- [x] nmae 가 매 sub-agent launch / 완료 / 머지 시점에 `~/.mobruji/cycle-status.json` 을 atomic write (tmp file + rename) 로 갱신.
- [x] bot.py `read_cycle_status()` — 파일 없음 / 깨진 JSON / OSError 3분기 모두 `None` 반환 (warning log + graceful fallback).
- [x] bot.py `_format_in_progress()` — `in_progress` 필드를 3분기 (str / dict / null) 모두 한 줄 label 로 변환.
- [x] bot.py `format_cycle_digest()` — 4 워크트리 고정 순서 (`be`, `fe`, `rev`, `plan`) 로 한 줄씩 출력.
- [x] 경로 환경 변수 외부화 — `CYCLE_STATUS_PATH` env (default `~/.mobruji/cycle-status.json`).

### 비기능 요구사항
- **결정성**: 동일 입력 → 동일 출력. `format_cycle_digest` 는 `now` 인자로 KST timestamp 까지 테스트 가능 (`tests/test_digest_cycle_status.py`).
- **graceful fallback**: 어떤 schema 위반에도 bot.py crash 금지. 한 필드 누락은 해당 줄만 `idle` / `없음` 으로 대체.
- **mention 안전**: `in_progress` / `last_completed` 의 title 에 `@everyone` 등이 포함돼도 `sanitize_mentions` 가 zero-width space 삽입으로 실제 알림 차단.
- **noise 억제**: signature 동일 시 push skip. heartbeat 주기 (default 1h) 도래 시에만 같은 내용 재push.

## 4) 범위 / 비범위
### 포함
- 4 워크트리(be/fe/rev/plan) 진행/완료 1줄 digest
- in_progress 3분기 schema (null / str legacy / dict 현행)
- last_completed schema (null / dict)
- bot.py 읽기 + render 계약

### 제외 (Out of Scope)
- nmae 측 갱신 코드 (본 spec 은 schema 계약만 정의. 갱신 동작은 nmae 행동 룰 — `CLAUDE.md` / 메모리에 명시)
- 사이클 통계 (소요 시간 / 빈도) — 후속 spec
- 다른 도구 (mmae / helper) 의 cycle-status.json 갱신 — nmae 단독 SSoT

## 5) Schema 명세

### 5-1) 파일 위치
- 기본 경로: `~/.mobruji/cycle-status.json`
- override env: `CYCLE_STATUS_PATH`
- 권한: mode 0600 권장 (사용자 사이클 메타 노출 방지)

### 5-2) Top-level 구조
```json
{
  "be":   { "in_progress": ..., "last_completed": ... },
  "fe":   { "in_progress": ..., "last_completed": ... },
  "rev":  { "in_progress": ..., "last_completed": ... },
  "plan": { "in_progress": ..., "last_completed": ... }
}
```
- 키 누락 시 해당 워크트리 줄은 `진행: idle / 최근: 없음`.
- 그 외 키는 무시 (forward-compatible).

### 5-3) `in_progress` (3분기)
| 타입 | 형식 | 사용 | 예시 |
|---|---|---|---|
| `null` | `null` | sub-agent 미가동 | `null` |
| `str` | trimmed 문자열 (legacy) | 1.x 초기 호환 | `"#N feat(be): ..."` |
| `dict` | 아래 schema | 현행 nmae 갱신 | 아래 참고 |

**dict schema (현행 — be/fe/plan)**
```json
{
  "issue": "#N",          // 필수 (1차 식별자) — null 또는 누락 시 pr 대체
  "pr":    "#M",          // 선택 — issue 없으면 1차 식별자로 사용
  "title": "feat(be): ...",
  "started_at": "2026-05-23T10:00:00+09:00"
}
```

**dict schema (rev 전용)**
```json
{
  "target": "PR #N",      // rev 는 issue/pr 대신 target 으로 명시
  "title": "rev QA — ...",
  "started_at": "2026-05-23T10:00:00+09:00"
}
```

**dict → label 변환 우선순위** (`_format_in_progress`):
1. `issue` 또는 `pr` 있으면 → `"<id> <title>"` (둘 다 있으면 `issue` 우선)
2. `target` 있으면 → `"<target>: <title>"`
3. `title` 만 있으면 → `"<title>"`
4. 셋 다 없으면 → `"진행 중(스키마 미상)"` (실측상 도달 X)

`title` 누락/비-str → `"제목 없음"` 대체.

### 5-4) `last_completed`
| 타입 | 형식 | 사용 |
|---|---|---|
| `null` | `null` | 아직 머지 없음 → `최근: 없음` |
| `dict` | 아래 schema | 마지막 머지 1건 캐시 |

**dict schema**
```json
{
  "pr": "#N",                            // null 가능 (PR 없는 사이클)
  "title": "feat(be): ...",
  "merged_at": "2026-05-23T11:00:00+09:00"
}
```

**dict → label 변환**:
- `pr` 있으면 → `"<pr> (<title>)"`
- `pr` 없으면 → `"<title>"`
- title 누락 → `"제목 없음"`

### 5-5) 갱신 타이밍 (nmae 행동 룰)
- **sub-agent launch 직후**: `<ws>.in_progress` 를 dict 로 set.
- **sub-agent 완료** (PR 생성 직후): `in_progress` 의 `pr` 필드를 갱신 (issue → pr 보강).
- **PR 머지 직후**: `<ws>.last_completed` 를 set + `<ws>.in_progress` 를 `null` (또는 다음 사이클 dict).
- **사이클 abort / sub-agent crash**: `<ws>.in_progress` 를 `null` 로 복귀.
- **atomic write 권장** (예: `tmp` 에 write → `os.replace`). bot.py 가 동시 read 해도 깨진 JSON 발생 안 함.

### 5-6) 출력 형식 (digest 본문)
```
📊 **cycle digest**
🕒 2026-05-23 18:00 KST
[be] 진행: #N feat(be): ... / 최근: #M (feat(be): ...)
[fe] 진행: idle / 최근: 없음
[rev] 진행: PR #N: rev QA — ... / 최근: ...
[plan] 진행: ... / 최근: ...
```
- 한 줄 최대 200 자 (`CYCLE_DIGEST_MAX_LINE_LEN`), 초과분은 `…` 절단.
- timestamp 는 KST 고정 (`Asia/Seoul`).

## 6) 작업 분할
본 spec 자체는 단일 PR (드리프트 정리 묶음 일부). 구현은 이미 shipped:
- [x] PR #804 — cycle-status.json digest cron 도입 (read_cycle_status / format_cycle_digest)
- [x] PR #806 — digest cron release tag
- [ ] (선택) 후속: `_format_in_progress` 의 `dict` 분기에 `started_at` ISO8601 검증 추가 — 현재는 raw 그대로 노출.

## 7) 테스트 전략
- 단위: `tools/discord-daemon/tests/test_digest_cycle_status.py` — 4분기 (정상 / 누락 필드 / 빈 dict / 깨진 JSON) 케이스 cover.
- 통합: nmae 실제 갱신 → bot.py digest tick → Discord push end-to-end 는 운영 검증 (수동).

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | `in_progress` legacy str 분기를 언제 제거할지 (dict 단일화) | (a) v0.5 / (b) 운영 사용자 0 확인 후 | @maestro / 운영 데이터 |
| Q2 | last_completed 를 N 건 히스토리로 확장할지 | (a) 1건 유지 (현재) / (b) 최근 5건 ring-buffer | @user / 가독성 검토 |

## 9) 결정 로그
- 2026-05-23: PR #804 / #806 에서 첫 도입 (사용자 단순화 결정). 기존 GitHub events PR 카운트 digest 폐기. nmae self-report 단일 SSoT 채택.
- 2026-05-23 (본 spec): bot.py docstring 만 있던 schema 를 spec 으로 승격 (도메인 감사 priority 1).
