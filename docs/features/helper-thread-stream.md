---
feature: Helper UX 묶음 — bot 1초 auto-ack + Discord thread stream + reply.referenced_message forwarding
slug: helper-thread-stream
status: draft
owner: @goohong
scope: infra
related_issues: [880]
related_prs: [880, 891, 939]
last_reviewed: 2026-05-24
---

# Helper UX 묶음 — bot 1초 auto-ack + Discord thread stream + reply 컨텍스트 forwarding

## 1) 개요 (What / Why)
사용자가 helper 운영 중 1시간 내 3가지 답답함을 보고했다. helper-agent.md PR-C 후속으로 UX 한 묶음.

1. **ack latency 5+초 깜깜이** — helper 가 turn 안에서 bash 도구를 chain 으로 부르면, 사용자 입장에선 메시지 전송 후 helper 의 첫 ack push 까지 5+초 침묵. mid-turn 새 메시지 보내도 즉시 반응 없음.
2. **실시간 가시성 부재** — helper 가 어떤 tool 을 부르는지/무엇을 reasoning 하는지 보고 싶음. 본답이 나오기 전까지 진행 상황이 캄캄.
3. **답장 인식 불가** — 사용자가 Discord "답장" 기능으로 helper 의 특정 메시지에 답해도, helper 입장에선 그냥 plain 텍스트로만 보임. "이거 해줘" 가 어느 메시지에 대한 답인지 모름.

세 가지 모두 같은 PR 에 묶는다. 셋 다 helper UX 의 latency / observability / context 축이라 단일 PR 검토가 효율적.

대상 액터: 오너 (폰 Discord 단독 사용), helper Claude CLI, bot.py daemon.

## 2) 사용자 시나리오
- **시나리오 A (auto-ack)**: 오너가 "PR 머지해줘" 보냄 → bot.py 가 0.5초 안 `📥 받음 — helper 작업 중 (구체 ack 곧 도착)` push → helper 가 1~3초 안 자체 ack push ("PR #790 머지 작업 시작합니다") → thread stream → 본답.
- **시나리오 B (thread stream)**: helper 가 ack 의 reply 로 thread 자동 생성. ack 메시지 아래 작은 thread 아이콘. 클릭하면 `[tool] gh pr view 790 — open, CI green` / `[tool] gh pr merge 790 --squash --delete-branch — exit 0` / `[tool] git status — clean` 같은 한 줄 stream. 본답은 메인 채널에 그대로 push.
- **시나리오 C (reply 인식)**: 오너가 helper 가 보낸 "PR #790 머지 완료" 메시지에 Discord "답장" 으로 "고마워 다음 PR 도 부탁" 보냄 → bot.py 가 helper 에 `[답장→ PR #790 머지 완료] 고마워 다음 PR 도 부탁` 으로 forward → helper 가 어느 PR 작업의 후속 요청인지 즉시 파악.

## 3) 요구사항
### 기능 요구사항
- [x] **F-1 bot.py 1초 generic auto-ack**: `BOT_AUTO_ACK=1` (default) 일 때 on_message 진입 즉시 `BOT_AUTO_ACK_TEXT` 한 줄을 채널에 push. `BOT_AUTO_ACK=0` 면 legacy 동작 (helper ack 만).
- [x] **F-2 discord-reply.sh `--ack <문구>` 모드**: 메인 채널에 ack 메시지 push + 그 메시지에 thread 생성 (이름 = ack 첫 30자 + `HHMMSS`). stdout 으로 thread_id 만 출력. `~/.mobruji/helper-current-thread.txt` 에도 1줄 저장.
- [x] **F-3 discord-reply.sh `--thread <id> <메시지>` 모드**: thread snowflake 를 channel id 처럼 사용해 push.
- [x] **F-4 discord-reply.sh 기본 호출 호환**: `discord-reply.sh "<본문>"` (mode 없음) = 메인 채널 push 기존 호환.
- [x] **F-5 reply.referenced_message forwarding**: bot.py 가 `message.referenced_message.content` 가 있을 때 prefix `[답장→ <30자 요약, 줄바꿈 제거>] <user 본문>` 으로 helper tmux 에 send-keys.
- [x] **F-6 reply 없을 때 호환**: 답장 아닌 평소 메시지는 prefix 없이 그대로 forward.
- [x] **F-7 pytest 추가**: 위 모든 분기 단위 테스트 (auto-ack on/off, reply prefix on/off, mode dispatch 인자 검증).

### 비기능 요구사항
- **응답 시간**: bot 1초 auto-ack — Gateway 메시지 수신 → 채널 push 까지 P50 ≤ 0.5 초, P99 ≤ 1 초 (asyncio 직렬 호출).
- **Discord rate limit**: 채널당 50 msg/10s 안. ack + thread create + 본답 = 한 turn 당 ≤ 3 msg. helper 가 thread stream 을 도구 5~10개 부르면 5~10 msg 추가 가능 → 한 turn 총 ≤ 15 msg, 안전 마진 충분.
- **호환성**: `discord-reply.sh "<본문>"` 기존 호출 형태 유지 (다른 곳에서 이미 사용 중).
- **보안**: `.env` 의 `DISCORD_BOT_TOKEN` 만 사용. 새 시크릿 도입 X.
- **관측성**: bot.py 로그에 `auto_ack=True/False` on_ready 시 1회 노출. 각 메시지 수신 시 `reply=True/False` flag.

## 4) 범위 / 비범위
### 포함
- bot.py 1초 generic auto-ack (toggle).
- discord-reply.sh thread mode 2종 (`--ack` / `--thread`).
- bot.py reply.referenced_message forwarding (prefix 추가).
- pytest (auto-ack / reply prefix / mode dispatch).
- .env.example 갱신 (`BOT_AUTO_ACK`).
- helper-agent.md 결정 로그 한 줄.

### 제외 (Out of Scope)
- helper 측 룰 (CLAUDE.md §11 갱신, helper turn loop 안에서 thread_id 캐싱 의무 등) — 별 PR. 동시 작업 중인 docs/rename-bonjin-promote-helper-rules-* 와 머지 충돌 회피.
- helper 가 도구마다 자동 stream 호출하는 wrapper — 본 PR 은 인프라만 제공, helper 운영 룰은 후속.
- thread auto-archive 정책 변경 (Discord 기본 24h 유지).
- bot.py daemon systemd 재배포 — nmae 가 별도 수동 deploy.

## 5) 설계
### 5-1) 도메인 모델
없음 (인프라/툴링 레이어 전용).

### 5-2) 외부 연동
- **Discord REST API v10**:
  - `POST /channels/{channel_id}/messages` — 메인 채널 ack / 본답 push.
  - `POST /channels/{channel_id}/messages/{message_id}/threads` — ack 메시지에서 thread 시작. 응답 `.id` = thread id (type 11 TEXTUAL_THREAD).
  - `POST /channels/{thread_id}/messages` — thread 안 push (thread snowflake 가 channel 처럼 동작).
- bot.py 의 discord.py 라이브러리는 변경 X — REST 호출은 모두 `discord-reply.sh` (curl + jq) 가 수행.

### 5-3) 데이터 흐름

```
사용자 Discord 메시지
        │
        ▼
bot.py on_message
   ├── (F-5) reply.referenced_message 있으면 prefix 부착
   ├── (F-1) BOT_AUTO_ACK=1 이면 채널에 generic ack push
   └── tmux send-keys -t helper:0.0 "<prefix+body>" Enter
        │
        ▼
helper turn 시작
   1. (helper) bash discord-reply.sh --ack "<구체 ack>" → THREAD_ID 캐시
   2. (helper) bash discord-reply.sh --thread $THREAD_ID "[tool] ..." (반복)
   3. (helper) bash discord-reply.sh "<본답>"
```

### 5-4) discord-reply.sh CLI

| 모드 | 호출 | Discord 동작 | stdout |
|---|---|---|---|
| 본답 (기존) | `discord-reply.sh "<본문>"` | 메인 채널 push | REST 응답 raw JSON |
| ack + thread | `discord-reply.sh --ack "<ack>"` | 메인 채널 push + 새 thread 생성 | thread id (1줄) |
| thread stream | `discord-reply.sh --thread <id> "<진행 줄>"` | thread 안 push | REST 응답 raw JSON |

helper 가 thread_id 잃지 않도록 `--ack` 모드는 `~/.mobruji/helper-current-thread.txt` 에도 thread id 1줄 저장. 다음 turn 에 환경변수 없어도 `$(cat ~/.mobruji/helper-current-thread.txt)` 로 복구.

### 5-5) bot.py 변경 요약
| 함수 / 위치 | 변경 |
|---|---|
| `BOT_AUTO_ACK_DEFAULT_ENABLED`, `BOT_AUTO_ACK_TEXT` | 신규 상수 |
| `REPLY_CONTEXT_PREVIEW_LEN`, `REPLY_CONTEXT_PREFIX_TEMPLATE` | 신규 상수 |
| `load_env()` | `BOT_AUTO_ACK` env 추가 |
| `build_reply_context_prefix()` | 신규 함수 — 답장 prefix 조립 |
| `on_message()` | (1) referenced_message → prefix, (2) auto-ack push, (3) prefixed body 를 tmux 로 forward |

### 5-6) 프론트엔드 화면
없음 (Discord UX 만).

## 6) 작업 분할 (예상 PR 리스트)
- [x] **PR-1 (이 PR)** — bot.py auto-ack + reply forwarding + discord-reply.sh thread mode + tests. 단일 PR.
- [ ] **PR-2 (후속)** — CLAUDE.md §11 helper 룰 갱신: thread_id 캐싱 의무, ack 매 turn 첫 액션 룰, reply prefix 해석 룰.
- [ ] **PR-3 (후속, 옵션)** — helper 가 모든 bash 도구 호출 전후 자동으로 thread stream push 하는 wrapper / hook.

## 7) 테스트 전략
- **단위 (pytest)**:
  - `build_reply_context_prefix`: None / 빈 문자열 / whitespace / 짧은 ref / 긴 ref (truncate) / 멀티라인 flatten / custom preview_len 7 케이스.
  - `on_message` auto-ack 분기: enabled push 호출 1회 / disabled 0회 / default enabled / reply prefix 가 tmux 로 forward 되는지 4 케이스.
  - `discord-reply.sh` mode dispatch: 인자 없음 / 알 수 없는 옵션 / `--thread` 인자 부족 / `--ack` 메시지 부족 4 케이스.
- **통합**: 운영 중 nmae 가 수동 smoke test (bot.py 재배포 후 사용자 메시지 1건 → ack 즉시 도착 확인 + 답장으로 1건 더 → prefix 확인). 본 PR 에는 포함 X.
- **외부 mock**: discord.py 의 `message.channel.send` 는 AsyncMock. `subprocess` 호출은 직접 venv pytest 로 실행.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 | 우선순위 |
|---|---|---|---|---|
| Q1 | thread auto-archive 24h 후에도 같은 ack message 에 thread 재생성 가능? | (a) 가능 (Discord 정책) / (b) 새 ack 부터 다시 시작 | helper 운영 후 확인 | 하 |
| Q2 | helper 가 thread stream 을 너무 많이 push 하면 reading noise — milestone 만 push 하도록 PR-3 wrapper 필요? | (a) milestone 만 (PR-3) / (b) 모든 bash 도구 (verbose) | helper 운영 1주 후 결정 | 중 |
| Q3 | reply prefix preview_len 30자가 적당? | (a) 30 / (b) 50 / (c) full | 운영 1주 관찰 후 | 하 |

## 9) 결정 로그
- 2026-05-23: 초안 작성 + 단일 PR 구현 (status=draft → shipped 머지 후 갱신). 사용자 직접 요청 3건 (auto-ack 부활 / thread stream / reply 인식) 한 묶음 위임.
- 2026-05-23: `BOT_AUTO_ACK` default = enabled. #807 에서 제거됐던 사유 (helper 구체 ack 가 충분) 가 실제 운영에선 bash chain latency 때문에 부적합 판명.
- 2026-05-23: thread 생성 위치 = ack 메시지 reply. 매 ack 마다 새 thread (이전 thread 는 Discord 자동 24h archive 의존).
- 2026-05-23: reply prefix 형식 = `[답장→ <30자 요약>] <body>`. message id 는 verbose 라 생략 (필요 시 후속 확장).
