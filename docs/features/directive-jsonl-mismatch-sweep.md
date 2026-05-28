---
feature: directive-board jsonl ↔ Discord mismatch 106건 자동 sweep + 지속 sync
slug: directive-jsonl-mismatch-sweep
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1109]
related_prs: []
last_reviewed: 2026-05-26
---

# directive-board jsonl ↔ Discord mismatch 106건 자동 sweep + 지속 sync

## 1) 개요 (What / Why)
- 2026-05-26 현재 `~/.mobruji/directive-board.jsonl` (source of truth, SoT — [[feedback-nmae-directive-board-update-flow]]) 가 detect=142건 / board=36건 mismatch 106건 누적. 사용자가 #모부르지-지시 forum / 텍스트 채널에서 본 directive 진행 상황이 실제 PR / 작업 상태와 desync — "진행상황 변동 없네" 사고 박제.
- 사용자 2026-05-26 정정: "directive_board_mismatch=106 — 자동 PATCH 가 안 됨, 1회 sweep + 지속 sync 둘 다 필요". 1회 sweep PR 으로 잔존 106건 PATCH + bot.py `directive_status_sync_loop` (be 사이클 별도 구현 진행 중, PR #1041 후속 P11 automation) 가 지속 sync.
- 대상 액터: nmae (sweep PR 작성 / sync loop owner) + be sub-agent (sync loop 구현). plan 본 spec 은 정책 + 알고리즘 spec.

## 2) 사용자 시나리오
- 1회 sweep: nmae 가 본 spec 의 알고리즘에 따라 jsonl 142건 read + Discord forum/채널 36건 fetch + diff → 106건 mismatch detect → 각 mismatch 항목에 대해 (1) jsonl entry status 정정 (PR head 라벨 / 머지 여부 기반 추론) (2) `discord-reply.sh --update-status <message_id> "<status>" [<pr>]` 호출 → board 동기화.
- 지속 sync: bot.py `directive_status_sync_loop` 5분 polling 으로 jsonl ↔ Discord 비교 + 자동 PATCH. cron digest 에 mismatch 카운트 한 줄 표시.
- 결과: 사용자가 #모부르지-지시 view 시 항상 최신 상태.

## 3) 요구사항

### 기능 요구사항 — 1회 sweep
- [ ] **inventory**:
  - [ ] jsonl 142건 전부 read + PR 번호 / status / created_at / message_id 추출.
  - [ ] Discord forum threads + 텍스트 채널 36건 메시지 fetch + 매칭 (`message_id` 키 또는 PR 번호 grep).
- [ ] **diff 분류**:
  - [ ] (A) jsonl 있고 Discord 없음 (Discord PATCH 누락) — 106건 추정 대부분.
  - [ ] (B) jsonl 없고 Discord 있음 (jsonl append 누락) — 적음.
  - [ ] (C) 양쪽 있지만 status mismatch — 적음.
- [ ] **자동 status 추론**:
  - [ ] PR head: `gh pr view <N> --json state,mergedAt,closedAt` 조회.
  - [ ] state=MERGED + mergedAt 존재 → `done`.
  - [ ] state=CLOSED + mergedAt null → `dropped` (또는 `cancelled`).
  - [ ] state=OPEN + draft → `pending`.
  - [ ] state=OPEN + ready + last_review pass → `in_progress`.
  - [ ] PR 부재 (jsonl 만) → 작업 자체 부재 → `unknown` 표기 + manual 검토 후보 출력.
- [ ] **PATCH 실행**:
  - [ ] 분류 (A): `discord-reply.sh --update-status <message_id> "<status>" [<pr_url>]` 호출.
  - [ ] 분류 (B): jsonl append + Discord 메시지 재생성 (또는 message_id 추적 후 PATCH).
  - [ ] 분류 (C): jsonl 우선 (SoT) → Discord PATCH.
- [ ] **dry-run mode**:
  - [ ] `--dry-run` flag — diff 리스트만 출력, 실 PATCH X.
  - [ ] PR review 단계에서 dry-run 결과 사용자 확인 후 실행.

### 기능 요구사항 — 지속 sync
- [ ] bot.py `directive_status_sync_loop` (be 사이클 구현 진행 중):
  - [ ] `DIRECTIVE_STATUS_SYNC_INTERVAL_SECONDS` (default 300) polling.
  - [ ] jsonl 신규 entry 또는 PR state 변경 detect → 자동 PATCH.
  - [ ] 429 / 4xx graceful skip — daemon crash 금지.
  - [ ] mismatch 잔존 시 cron digest 에 `directive_board_mismatch=N` 한 줄 표시.
- [ ] **검증 헬퍼**:
  - [ ] `tools/directive-board/validate.sh` — jsonl ↔ Discord 일치 검증. exit 1 + mismatch 목록 출력. CI 단계 또는 nmae self-check 용.

### 비기능 요구사항
- 무손실: jsonl SoT 정책 — Discord PATCH 가 실패해도 jsonl 손상 X. 1회 sweep / 지속 sync 모두 jsonl 우선.
- Discord rate limit: 5 req / 5sec 안전. sweep 106건 → 약 110초 ((106 * 1.0s + retry) 안전 마진).
- 관측성: sweep PR 본문에 diff summary table (A/B/C 분류 + 카운트). cron digest 매 5분 mismatch 카운트.
- 멱등성: 같은 sweep 2회 실행 시 결과 동일 (이미 PATCH 된 항목 skip).

## 4) 범위 / 비범위
### 포함
- 1회 sweep PR (nmae 가 plan 사이클 후 직접 또는 be 위임).
- bot.py `directive_status_sync_loop` 지속 sync (be 사이클 별 구현).
- `discord-reply.sh --update-status` mode 활용 (이미 PR #1041 mode 존재).
- jsonl SoT 정책 (이미 [[feedback-nmae-directive-board-update-flow]] 룰).

### 제외 (Out of Scope)
- Discord UI manual edit 복원 — 사용자 수동 edit 가 desync 원인 1건이면 PATCH 만, edit 막는 권한 변경 X.
- forum thread 자동 생성 — 분류 (B) 케이스 jsonl 만 있고 Discord 없음 시, v1 은 message 재생성 까지만, forum thread 신설은 manual.
- jsonl 스키마 변경 — 기존 SoT 형식 그대로 read.
- 다른 board (rev queue / nmae-status-channel) sync — directive-board 만.

## 5) 설계
### 5-1) 도메인 모델
- 인프라 도메인 (`scope: infra`). directive-board jsonl + Discord forum/채널.

### 5-2) API 엔드포인트
N/A (Discord REST + 파일).

### 5-3) 외부 연동
- **Discord REST**: `GET /channels/<forum_id>/threads/active`, `GET /channels/<channel_id>/messages?limit=100`, `PATCH /channels/<channel_id>/messages/<message_id>`.
- **gh CLI**: `gh pr view <N> --json state,mergedAt,closedAt,headRefName`.
- **파일**: `~/.mobruji/directive-board.jsonl` (read), `tools/directive-board/sweep.py` 신설 (구현 시점).

### 5-4) 데이터 흐름 / 시퀀스
```
[1회 sweep 알고리즘]
  1. jsonl read → entries[] (142건)
  2. discord forum + 채널 fetch → discord_state{message_id: status}
  3. for entry in entries:
       (a) discord_state[entry.message_id] 부재 → 분류 (A)
       (b) entry.message_id 부재 → jsonl 만, 분류 (B)
       (c) status 다름 → 분류 (C)
  4. for entry in classified:
       inferred_status = infer_status_from_pr(entry.pr) 또는 jsonl status
       if dry_run:
         print diff
       else:
         apply_patch(entry, inferred_status)
  5. sweep PR 본문에 summary table 출력

[지속 sync — bot.py loop, be 구현]
  1. 매 5분: jsonl 마지막 N entries (또는 신규 since last_sync_ts) read
  2. PR state 변경 detect: pr_state_cache 비교
  3. 변경 항목 → apply_patch (jsonl status 갱신 + Discord PATCH)
  4. mismatch 잔존 카운트 → digest 채널 / cron digest
```

### 5-5) DB 마이그레이션
없음 (jsonl 기반).

### 5-6) 프론트엔드 화면
없음 (Discord forum / 채널 가시).

### 5-7) sweep tool (구현 위치)

- 경로: `tools/directive-board/sweep.py` (be 사이클 구현, plan 본 spec 은 위치만 지정).
- 호출: `python3 tools/directive-board/sweep.py --dry-run` / `--apply`.
- 출력: diff table (stdout) + sweep summary (stderr).

### 5-8) sweep PR 본문 양식 예시

```markdown
## directive-board 1회 sweep — mismatch 106건 PATCH

### Summary
| 분류 | 카운트 | 처리 |
|---|---|---|
| (A) jsonl 있고 Discord 없음 | 89 | Discord PATCH |
| (B) jsonl 없고 Discord 있음 | 12 | jsonl append + 메시지 재생성 |
| (C) status mismatch | 5 | jsonl SoT 우선 PATCH |

### Pre-sweep
- jsonl: 142 entries
- Discord forum + 채널: 36 messages
- mismatch detect: 106

### Post-sweep (dry-run 검증)
- mismatch 잔존: 0
- 의심 항목 (PR 부재 → unknown): 3건 manual 검토 필요
```

### 5-9) 검증 헬퍼

`tools/directive-board/validate.sh`:
```bash
# jsonl + Discord state 동기 검증
mismatch=$(python3 tools/directive-board/sweep.py --dry-run --count-only)
if [[ "$mismatch" -gt 0 ]]; then
  echo "[directive-board] mismatch=$mismatch" >&2
  exit 1
fi
```

cron digest 호출 부 또는 nmae self-check 단계에서 활용.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1 (nmae, 1회 sweep): `tools/directive-board/sweep.py` + `validate.sh` 신설. dry-run 결과 PR 본문에 포함 → 사용자 확인 후 `--apply` 별 PR.
- [ ] PR 2 (be, 지속 sync): bot.py `directive_status_sync_loop` + .env.example + pytest 5건. (이미 be 사이클 진행 중 — task #1 의 forum sync 와 통합 가능.)
- [ ] PR 3 (옵션): cron digest 에 `directive_board_mismatch=N` 한 줄 표시 + nmae self-check 단계 통합.

## 7) 테스트 전략
- 단위 테스트 (sweep.py):
  - jsonl 정상 / empty / malformed.
  - 분류 (A) / (B) / (C) 각 1건 fixture.
  - PR state 추론 — MERGED / CLOSED / OPEN draft / OPEN ready.
  - dry-run vs apply 멱등성.
- 단위 테스트 (bot.py sync loop):
  - 신규 entry detect + PATCH.
  - PR state 변경 detect.
  - 429 graceful skip.
  - cron digest mismatch 카운트 출력.
- E2E: NCP 서버 실 jsonl + Discord forum 에 mismatch 1건 강제 → sync loop 5분 polling → PATCH 확인.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | sweep PR 분류 (B) — message 재생성 vs manual | (a) 자동 재생성 (forum thread 신설) / (b) manual 검토 후 nmae 가 처리 | @user / TBD |
| Q2 | status 추론 — PR 부재 시 `unknown` vs `dropped` | (a) `unknown` + manual / (b) `dropped` 자동 | @user / TBD |
| Q3 | 지속 sync trigger — 5분 polling vs Discord webhook | (a) polling (단순) / (b) webhook (실시간, 인프라 복잡) | @user / TBD |

## 9) 관련 spec / ADR / 메모리

- `docs/features/nmae-cycle-watchdog.md` — 본 spec 의 sync loop 가 cycle watchdog 와 동일한 외부 데몬 정정 패턴.
- `docs/features/discord-realtime-bidirectional.md` — `discord-reply.sh --update-status` mode 활용.
- `docs/features/event-action-mapping.md` — directive board 갱신이 event 1:1 매핑 대상.
- `docs/features/work-cycle-simplification.md` — 본 spec 이 work-cycle 의 directive sync 자동화 단계 1개.
- 메모리: [[feedback-nmae-directive-board-update-flow]] (jsonl SoT + status 전이 즉시 PATCH 의무) / [[feedback-nmae-forum-channel-enforce]] (forum 전환) / [[feedback-verify-and-iterate]].

## 10) 결정 로그
- 2026-05-26: 초안 작성 (status=draft). 이슈 #1109 plan 사이클. directive sweep + 지속 sync spec.
- 2026-05-26: 1회 sweep + 지속 sync 2단 — sweep 은 즉시 정정 (106건), sync loop 은 정상 운영 패턴 (앞으로 desync 차단). 둘 다 필요.
- 2026-05-26: jsonl SoT 정책 유지 — Discord 가 view, jsonl 이 본질. PATCH 충돌 시 jsonl 우선.
