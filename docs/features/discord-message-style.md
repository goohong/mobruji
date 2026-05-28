---
feature: Discord 메시지 체계화 + 채널 매핑
slug: discord-message-style
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: [386]
related_prs: [386, 387, 827, 830, 939]
last_reviewed: 2026-05-24
---

# Discord 메시지 체계화 + 채널 매핑

## 1) 개요 (What / Why)
**문제**: maestro 가 보내는 메시지가 중구난방 — 매번 즉흥 emoji + 본문 형식. 어떤 종류인지 식별 어렵고, 어느 채널 가야할지 일관성 없음.

**해결**: 메시지를 **7 카테고리** 로 분류 + 각 종류 **표준 템플릿** + **채널 매핑** 정형화. 본 spec 머지 후 maestro 모든 push 는 본 표준을 따른다.

[[feedback-discord-status-push]] (push 룰) 의 후속 — 룰 박았는데 메시지 형식이 정형화되지 않은 부분 보강.

## 2) 사용자 시나리오
- **(S1)** 사용자가 모바일에서 알림 받음 — 첫 줄 emoji 만 보고 종류 즉시 식별 (사이클 시작? 답? 위험?). 본문 읽기 전에 결정.
- **(S2)** maestro 가 사용자 메시지에 답할 때 — `💬 reply` 로 시작. 사용자가 reply 인지 즉시 알 수 있음.
- **(S3)** 알림 채널 미리보기 — 누적 푸시 한눈에 카테고리 별 색 emoji 로 가시화.

## 3) 메시지 카테고리 (7종)

| emoji | 종류 | 채널 | 트리거 | 예시 첫 줄 |
|---|---|---|---|---|
| 💬 | **reply** | 메인 | 사용자 메시지에 maestro 답 | `💬 reply: ACG 80 ok` |
| 🚀 | **cycle-start** | 알림 | maestro/sub-agent 사이클 시작 | `🚀 cycle 47 [be sub-agent]: voice-range-history 추가` |
| ✅ | **cycle-end** | 알림 | 사이클 정상 종료 + 1줄 결과 | `✅ cycle 47 done: PR #391 OPEN` |
| 📊 | **digest** | 알림 | 5분 cron 상태 1줄 | `📊 PR open:2 / 머지 24h:5 / type:bug:0 — 13:42 KST` |
| 🚨 | **alert** | 알림 + P1↑ 메인 | 위험 발생 | `🚨 [P1] wake stuck — 95분 전 마지막 활동` |
| 🟢 | **recovery** | 알림 | 위험 회복 1회 | `🟢 wake 회복` |
| 📌 | **decision** | 메인 | 사용자 결정 묶음 질문 | `📌 decision 3건 묶음` |

추가 룰:
- 한 메시지 = 한 카테고리. 섞지 않음 (예: cycle-end + alert 같이 발사하면 별 메시지 2개).
- 알림 채널 카테고리는 사용자 silent 가능 (모바일 알림 off).
- 메인 채널 카테고리는 사용자 즉시 인지 필요.

## 4) 템플릿 표준

### 4-1) reply (💬)
```
💬 reply: {1줄 요약 ≤ 60자}

{본문 — bullet 또는 표, ≤ 1500자}

{다음 액션 또는 묶음 질문 (선택)}
```

### 4-2) cycle-start (🚀)
```
🚀 cycle {N} [{maestro|be|fe|rev|plan}]: {prompt 또는 의도 1줄 ≤ 70자}
```
한 줄만. 부가 정보 없음.

### 4-3) cycle-end (✅)
```
✅ cycle {N} done: {결과 1줄 ≤ 70자} (PR #{N} | 메모리 갱신 | etc)
```
한 줄. PR 번호 또는 산출물 명시.

### 4-4) digest (📊)
```
📊 PR open:{N} / 머지 24h:{N} / type:bug:{N} — {HH:MM KST}
```
이미 [[feedback-discord-status-push]] §C 에 박힌 형식 그대로.

### 4-5) alert (🚨)
```
🚨 [P{1-3}] {위험 이름} — {감지값}
조치: {권장 액션 ≤ 60자}
```
P1 이상은 알림 + 메인 동시. P2/P3 은 알림 만.

### 4-6) recovery (🟢)
```
🟢 {위험 이름} 회복 — {새 상태값}
```
한 줄. 발생/회복 시 1회씩만 push (stuck loop 회피).

### 4-7) decision (📌)
```
📌 decision {N}건 묶음

1. {질문 1} — (a) {옵션} / (b) {옵션}
2. ...

답을 한 번에 주면 maestro 일괄 적용.
```

## 5) 채널 매핑

### 5-1) 메인 (#모부르지)
- ID: `1506925497651560458`
- 발사 수단: **bot REST API 직접** ([[feedback-maestro-direct-channel-send]])
- 카테고리: 💬 reply / 📌 decision / 🚨 alert (P1+)
- 사용자가 알림 켜고 즉시 인지

### 5-2) 알림 (#모부르지 알림)
- ID: TBD (사용자 입력 또는 신규 채널 생성)
- 발사 수단: **webhook** (`discord-reply.yml` workflow_dispatch 또는 bot.py 가 환경변수 url 로 curl) **또는 bot REST API** (채널 id env 분기)
- 카테고리: 🚀 cycle-start / ✅ cycle-end / 📊 digest / 🚨 alert / 🟢 recovery
- 사용자가 모바일 silent 또는 채널 미리보기로 누적 가시화

### 5-3) 채널 분리 사용자 액션
| 옵션 | 액션 | 장단점 |
|---|---|---|
| (a) 기존 webhook 채널 활용 | webhook URL 의 channel_id 알려주면 `.env` 에 `NOTIFY_CHANNEL_ID` 등록 | 추가 채널 생성 X, 즉시 적용 가능 |
| (b) 신규 채널 생성 | 사용자가 `#모부르지 알림` 또는 `#mobruji-cycle` 채널 생성 + bot 추가 + channel_id 알려줌 | 깔끔한 분리, 향후 카테고리 별 채널 확장 여지 |

본 spec 머지 시점에 결정 — 결정 안 되면 메인 채널로 fallback (현재 동작 유지).

## 6) bot.py 변경 (후속 PR)
- `.env` 에 `NOTIFY_CHANNEL_ID` 추가 (default = `MOBRUJI_CHANNEL_ID`).
- `digest_loop` 의 `client.get_channel(target_channel_id)` → `client.get_channel(notify_channel_id)`.
- `auto-ack`, `cycle 알림` 도 동일 분기. reply 는 메인 채널.

코드 변경은 본 PR 외 후속:
- **PR B (이 spec 머지 후)**: `bot.py` channel 분리 + `.env.example` 갱신 + test_bot.py
- **PR C (선택)**: maestro 가 사이클 시작/끝 push 발사할 shell helper `tools/maestro/push.sh` — 한 줄 호출로 카테고리 별 표준 push.

## 7) 작업 분할 (PR 리스트)
- [x] **PR A (본 PR)**: spec 신설 + 메모리 [[feedback-discord-status-push]] cross-ref
- [x] **PR B**: `bot.py` `NOTIFY_CHANNEL_ID` env 분기 + `.env.example` + test_bot.py
- [ ] **PR C (선택)**: `tools/maestro/push.sh` shell helper (카테고리 별 wrapper)
- [ ] **PR D (선택)**: spec 룰을 [[feedback-discord-status-push]] 에 cross-ref + 메모리 갱신

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | 알림 채널 ID — 기존 webhook 채널 vs 신규 | (a) 기존 / (b) 신규 | @user / PR B 시작 시 |
| Q2 | maestro 의 사이클 시작/끝 push 호출 위치 | (a) 모든 도구 호출 직전/직후 자동 / (b) 명시적 명령 호출 시만 / (c) 메모리 룰만 (사람 의지) | @user / PR C 또는 메모리 |
| Q3 | P1 alert 메인 + 알림 동시 push 또는 메인 단일? | (a) 동시 / (b) 메인 단일 (이미 알림은 cycle/digest 로 가시) | @user / 1주 운영 |

## 9) 결정 로그
- 2026-05-23: 초안 + 즉시 운영 적용 (status=approved). 사용자 위임 — 메시지 체계화 + 5분 digest 채널 분리. 7 카테고리 표준 + 채널 매핑 룰. 후속 PR B/C 분할.
