---
feature: helper-turn-start 채널 history grep step 추가
slug: channel-history-grep-wrapper
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1109]
related_prs: []
last_reviewed: 2026-05-26
---

# helper-turn-start 채널 history grep step 추가

## 1) 개요 (What / Why)
- `tools/discord-daemon/helper-turn-start.sh` (#1014) 에 매 turn 첫 명령으로 채널 history grep step 1개 추가. 직전 N 메시지(default 20) 의 사용자 발화 + bot.py auto-ack 본문에서 키워드(`/clear` / `/reset` / `release` / `revert` / `hotfix` / `정정` / `왜` / `안 비어있어` / `다시` 등) 가 1건 이상이면 helper 본체 turn 첫 줄에 `[channel-history grep] 키워드 N건 발견 — <preview>` 형태로 prepend 출력.
- 사용자 2026-05-26 정정: "helper 가 직전 사용자 정정/`/clear` 직전 지시 등 채널 history context 를 안 읽고 본답 시작". turn-start wrapper 는 cycle-status / queue / target freeze 만 수행, 채널 history 자체는 학습 의존이라 누락 빈발 → wrapper 1 step 으로 강제.
- 대상 액터: helper 본체. wrapper 호출 의무는 §12-3 step 0 (기존).

## 2) 사용자 시나리오
- 사용자가 직전 turn 에서 "안 비어있어 잘 읽어 공백때문에 그래?" 정정 → 다음 turn 에서 helper 본체가 자동 channel-grep step 실행 → `[channel-history grep] 키워드 1건 발견 — "안 비어있어 잘 읽어..."` prepend.
- helper 본체가 정정 context 를 본답 직전에 강제 인지 → "비어있는 메시지" 같은 단정 표현 회피 ([[feedback-helper-empty-message-classify]]).
- `/clear` 직전 사용자 지시 (예: "이번 turn 끝나면 clear 해") 누락 방지.

## 3) 요구사항
### 기능 요구사항
- [ ] `helper-turn-start.sh` 에 step 추가 (기존 5 action 다음, exit 0 직전).
- [ ] Discord REST API `GET /channels/<MOBRUJI_CHANNEL_ID>/messages?limit=<CHANNEL_HISTORY_GREP_LIMIT>` (default 20) 호출.
- [ ] keyword set: `/clear`, `/reset`, `release`, `revert`, `hotfix`, `rollback`, `정정`, `왜`, `안 비어있어`, `다시`, `근본`, `잘못`. env override (`CHANNEL_HISTORY_GREP_KEYWORDS`, CSV).
- [ ] hit 발견 시 stdout 첫 줄에 `[channel-history grep] 키워드 N건 — <preview>` 출력 (turn-start wrapper stdout 은 helper turn 첫 명령 출력이라 자동 helper 본체 input 으로 noticeable).
- [ ] 0 hit 면 stdout 한 줄 `[channel-history grep] keyword hit 0` 출력 (silent skip 시 wrapper 호출 의무 검증 불가).
- [ ] env toggle: `CHANNEL_HISTORY_GREP_ENABLED=1` default.
- [ ] env: `CHANNEL_HISTORY_GREP_LIMIT` (default 20) / `CHANNEL_HISTORY_GREP_KEYWORDS` (default 위 set).
- [ ] graceful: Discord API rate limit / 4xx / network fail 시 stdout `[channel-history grep] skip (reason: ...)` + exit 0.

### 비기능 요구사항
- 성능: REST API 1회 호출 (~200-500ms). turn-start wrapper 전체 latency ≤ 2초 유지.
- 보안: Discord BOT_TOKEN 은 wrapper 가 .env 에서 sourcing — 기존 패턴 일치. token raw 출력 금지.
- 관측성: wrapper 자체 로그 + bot.py 와 별도. systemd journal 영향 없음 (wrapper 는 helper tmux 안 실행).
- graceful: API fail 가 helper turn 깨뜨리지 않음 (`set -e` 회피 — 본 step 만 `|| true`).

## 4) 범위 / 비범위
### 포함
- `helper-turn-start.sh` 단일 wrapper 1 step 추가. 외부 도구 신설 X.
- helper 본체 turn 첫 명령 stdout 에 grep 결과 prepend.
- keyword set 은 env CSV override 가능.

### 제외 (Out of Scope)
- 자동 채널 history 요약 / LLM 요약 — keyword grep 만, 의미 분석 X.
- sub-agent (be/fe/rev/plan) 의 채널 history 인지 — sub-agent 는 자기 워크트리 + LAUNCH_THREAD_ID 안 thread 만 본다 (CLAUDE.md §13).
- 채널 history persist / 저장 — REST API 1회 read 만, 캐시 X.
- 다른 채널 (`#모부르지-be` 등) grep — `MOBRUJI_CHANNEL_ID` 만 (helper 사용자 응답 채널).

## 5) 설계
### 5-1) 도메인 모델
- 인프라 도메인. wrapper script 영역.

### 5-2) API 엔드포인트
N/A (Discord REST 외부 호출).

### 5-3) 외부 연동
- **Discord REST**: `GET https://discord.com/api/v10/channels/<MOBRUJI_CHANNEL_ID>/messages?limit=20`. Authorization: `Bot $DISCORD_BOT_TOKEN`.
- `jq` (이미 wrapper 의존성) 으로 message content + author.bot 파싱.

### 5-4) 데이터 흐름 / 시퀀스
```
[helper turn start]
  └ bash helper-turn-start.sh
       ├ step 0-4 (기존: queue append, target freeze, cycle-status 요약, user-presence, queue pending)
       └ step 5 (신설: channel-history grep)
            1. curl -s GET .../messages?limit=20 -H "Authorization: Bot $TOKEN"
            2. jq '[.[] | select(.author.bot==false) | .content] | join("\n")'
            3. grep -iE "$(KEYWORDS_REGEX)" --count
            4. hit ≥ 1: echo "[channel-history grep] 키워드 N건 — <preview 첫 hit 30자>"
            5. hit = 0: echo "[channel-history grep] keyword hit 0"
            6. fail: echo "[channel-history grep] skip (reason: <code>)" ; exit 0
```

### 5-5) DB 마이그레이션
없음.

### 5-6) 프론트엔드 화면
없음.

### 5-7) keyword set (default 12종)
| 카테고리 | keyword |
|---|---|
| 세션 제어 | `/clear` `/reset` |
| 릴리즈/회귀 | `release` `revert` `hotfix` `rollback` |
| 정정 신호 | `정정` `왜` `안 비어있어` `다시` `근본` `잘못` |

env override: `CHANNEL_HISTORY_GREP_KEYWORDS="release,hotfix,정정,왜"` 같이 CSV.

### 5-8) preview 추출 규칙
- 첫 hit 메시지의 content `[:30]` + `...` suffix.
- 한국어 문자 깨짐 회피: `jq` 가 utf-8 처리. terminal 출력 LANG=C.UTF-8 가정.

## 6) 작업 분할 (예상 PR 리스트)
- [ ] PR 1: `helper-turn-start.sh` step 5 추가 + 단위 테스트 (bash test, mock curl) + .env.example 갱신 + CLAUDE.md §12-3 step 0 보강.
- [ ] PR 2 (옵션): smoke test — 실 채널 history 에 keyword 메시지 1건 삽입 후 wrapper 호출 → stdout 검증.

## 7) 테스트 전략
- 단위 테스트 (bash, mock curl 응답 fixture):
  - 정상 hit 1건 / 0건 / 다건 / non-bot 만 / bot only 케이스.
  - keyword CSV override 적용.
  - rate limit (429) graceful skip.
  - 4xx (token 만료) graceful skip.
- E2E: NCP 서버 wrapper 호출 → stdout 검증 (1회 manual smoke).

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | history limit — 20 vs 50 | (a) 20 (latency 우선) / (b) 50 (recall 우선) | @user / TBD |
| Q2 | bot 메시지 (auto-ack) 도 grep 대상? | (a) 사용자 only / (b) bot 포함 | @user / TBD |
| Q3 | 키워드 미스 false negative 보강 — embedding 검색 도입? | (a) v1 keyword only / (b) v2 embedding | @user / 추후 |

## 9) 관련 spec / ADR / 메모리

- `docs/features/discord-realtime-bidirectional.md` — Discord bot 양방향 인프라. 본 spec 의 REST API 호출 가능 전제.
- `docs/features/work-cycle-simplification.md` — 본 spec 이 Hook 1 (helper turn-start wrapper 채널 grep) 의 구체화.
- 메모리: [[feedback-helper-empty-message-classify]] (사용자 정정 시 retract + 재해석) / [[feedback-session-persist-rules]] (세션 룰 영속) / [[feedback-verify-and-iterate]].
- CLAUDE.md §12-3 step 0 — turn-start wrapper. 본 spec 이 step 0 wrapper 의 5번째 action 확장.

## 10) 결정 로그
- 2026-05-26: 초안 작성 (status=draft). 이슈 #1109 plan 사이클. B-2 task brief.
- 2026-05-26: keyword set 12종 default — env override 로 운영 중 조정 가능. embedding 보강은 v2 후속.
- 2026-05-26: 0 hit case 도 stdout 출력 — silent skip 시 wrapper step 누락과 구분 안 됨, 검증 가능성 우선.
