# Feature — helper-current-target.txt bot.py 측 동시 갱신

- **status**: shipping (2026-05-29, PR fix/helper-current-target-bot-side-write)
- **scope**: infra
- **owner**: bot.py
- **메모리**: [[feedback-evidence-based-root-cause]] [[feedback-helper-relay-only]]

## 1) 배경 — 사고

2026-05-29 사용자 정정:
> "왜 쫀득 ui 조사 후 반영 작업 어떻게 됐어?에다가 답장을 다냐고. 이거 전에 고쳤다며?"

helper 답이 사용자 직전 메시지가 아닌 옛 메시지 ("왜 쫀득 ui ...") 에 reply.

### Evidence
- `last-user-msg-id.txt = 1509736582842683392` (10:54 "뭐하니")
- `helper-current-target.txt = 1509588393196126378` (옛, 약 10:50 이전)
- 두 값 불일치 → `helper-turn-start.sh` 호출 안 됐음 (`cp last-user-msg-id helper-current-target` 안 됨)

### Root cause
- `helper-current-target.txt` 갱신 = `helper-turn-start.sh` 가 helper LLM 의 매 turn 첫 명령으로 호출돼야 갱신.
- helper LLM 의 학습 의존 — wrapper 호출 누락 시 옛 target 그대로.
- helper-role.md (system prompt) 에 helper-turn-start.sh 호출 의무 명시 X → restart 직후 첫 turn 에 호출 누락 → 옛 target frozen.
- discord-reply.sh 의 resolve_reply_to_id chain 이 helper-current-target.txt 우선시 → 옛 target 에 reply.

PR #1234 의 ❓ branch write_last 제거 fix 와는 **다른 path**.

## 2) Fix — code 강제 (학습 의존 폐기)

`bot.py` 의 `write_last_user_msg_id` 가 `last-user-msg-id.txt` 갱신 시 `helper-current-target.txt` 도 동시 atomic write. helper LLM wrapper 호출 의존 폐기.

- 추가 path: 같은 message_id, atomic mktemp + os.replace, 동일 0o600 권한.
- `helper-turn-start.sh` 의 freeze 단계는 그대로 유지 (다른 단계: ✍️ marker, queue 표시 등). 단 last-user-msg-id → helper-current-target cp 는 bot.py 가 미리 처리.

## 3) 영향

### 긍정
- helper restart / wrapper 호출 누락 시에도 reply target stale 없음.
- 학습 의존 ↓ — system prompt 룰 추가 없이 code 가 강제.
- 사용자 새 메시지 수신 시점에 즉시 갱신 (race 없음).

### 잠재 부작용
- helper-turn-start.sh 의 cp 단계가 idempotent — bot.py 의 갱신과 race 없음 (마지막 user 메시지 = 같은 값).

## 4) 검증
- 사용자 메시지 보낸 후 helper-current-target.txt 즉시 갱신 확인.
- helper 답이 새 message_id 에 reply 되는지 확인.

## 5) follow-up

- helper-turn-start.sh 의 cp 단계 제거 (bot.py 가 갱신하므로 중복) — 다음 사이클.
