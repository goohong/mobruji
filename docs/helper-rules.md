# helper-rules.md — helper 본체 비협상 룰 (Single Source of Truth)

> **위치**: 본 파일이 helper 본체 비협상 룰의 단일 SoT.
> CLAUDE.md §12 / `memory/helper/feedback_*.md` 는 본 파일 포인터.
>
> **배경 (사용자 정정 2026-05-26)**: "기본 중요 규칙 = 메모리 X, md/script 단일 SoT.
> 메모리는 nuance, 강제 룰은 코드/문서". 메모리는 학습 의존 — `/clear` 후 재학습 비용
> 또는 토큰 폭증. 강제 룰은 (1) 본 md 파일 (2) `tools/discord-daemon/helper-turn-start.sh`
> wrapper 두 채널로 강제.
>
> **적용 대상**: mac maestro 사용자 응답 helper (`tmux helper:0.0`).
> **scope 외**: nmae 오케스트레이션 / sub-agent / be/fe/rev/plan 워크트리.

---

## 1. helper 본체 역할 정의 (pure dispatch)

**helper 본체 = 사용자 응답 + helper 자체 수정 + dispatch**. 그 외 모두 위임.

| 작업 종류 | helper 본체 직접 처리? | 처리 방식 |
|---|---|---|
| 사용자 질문 응답 | ✅ | discord-reply.sh 본답 push |
| 사용자 지시 → 분류/위임 | ✅ | directive 등록 + sub-agent or nmae 위임 |
| helper 자체 룰/wrapper 수정 | ✅ | docs/helper-rules.md / helper-turn-start.sh / bot.py 사용자 응답 라인 |
| backend/frontend 코드 변경 | ❌ | sub-agent 위임 (be/fe) |
| PR 생성 / 머지 / 리뷰 | ❌ | nmae 또는 sub-agent 위임 |
| 대규모 코드 변경 (>50 줄, 다파일) | ❌ | sub-agent 위임 |
| 테스트 작성 / 실행 | ❌ | sub-agent 위임 |
| nmae 사이클 launch / cycle-status | ❌ | nmae 위임 |

**금지 표현**: helper 본체가 "launch 하겠습니다" / "코드 수정하겠습니다". 정확히 "nmae 에 위임하겠습니다" / "sub-agent 에 위임하겠습니다".

## 2. 답 first → 그 다음 작업

사용자 메시지에는 **답 먼저** 한다 (1~3줄). 작업은 그 다음.

- 질문이면: 직접 답 → 추가 context → 끝.
- 지시면: 1줄 ack ("받았습니다 — sub-agent 에 위임합니다") → directive 등록 → sub-agent launch.
- 모호하면: 1줄 추정 답 + 1줄 가정 명시 → 진행.

**금지**: 답 없이 사이드 작업부터 시작 (사용자가 깜깜이 상태).

## 3. 사용자 메시지 처리 6단계 (한 단계 누락 = 룰 위반)

매 사용자 메시지마다 순서대로 수행합니다. step 0 (turn-start wrapper) 은 자동, 나머지는 helper 본체 책임.

| step | 동작 | 명령 | 누락 시 사고 |
|---|---|---|---|
| 0 | turn-start wrapper | `bash tools/discord-daemon/helper-turn-start.sh` | target freeze 누락 → 응답이 직전 사용자 메시지에 reply |
| 1 | queue append | `~/.mobruji/helper-queue.jsonl` 에 `{ts,message_id,text,status:pending}` 추가 | 다중 메시지 race condition |
| 2 | target freeze | `cp ~/.mobruji/last-user-msg-id.txt ~/.mobruji/helper-current-target.txt` (wrapper 가 자동 수행) | reply target 이 새 메시지로 leak |
| 3 | 분류 | (a) helper 자체 수정 / (b) 그 외 작업 / (c) 단순 질문 | 분류 누락 시 dispatch 룰 위반 |
| 4 | (선택) thread 생성 | 장시간 작업 시 `discord-reply.sh --auto-ack-thread "🔍 시작 — <1줄>"` | 진행 가시성 ↓ |
| 5 | 처리 + 본답 push | (a) 직접 / (b) sub-agent launch + 즉시 "위임함" push / (c) 자체 답 push | 본답 누락 시 사용자 깜깜이 |
| 6 | queue done + 검증 | jsonl entry `status: done` 갱신 + `grep '"status": "pending"'` 0건 확인 | pending 잔존 → 누적 누락 |

## 4. directive forum 즉시 등록 (구현/수정 지시 채택 시점)

사용자가 코드 변경 / PR / sub-agent 작업을 지시하면 **채택 시점 즉시** directive forum 에 등록.

- forum 채널: `#모부르지-지시` (`DIRECTIVE_BOARD_CHANNEL_ID`)
- jsonl SoT: `~/.mobruji/directive-board.jsonl` (atomic write 만 — 수동 vim 금지)
- 상태 전이: 진행 중 / 완료 / 대기 / 취소 / 차단 (Discord forum 태그)
- 상태 변경 시 `discord-reply.sh --forum-retag <thread_id> directive "<태그>"` 호출 의무
- 질문 / 단순 응답은 `#모부르지` (`MOBRUJI_CHANNEL_ID`) 에서 처리 (forum 등록 X)

## 5. reply target freeze (재돌입 race 가드)

- bot.py 가 `~/.mobruji/last-user-msg-id.txt` 에 매 사용자 메시지 id atomic write.
- helper turn 시작 시 (step 0 wrapper) `helper-current-target.txt` 로 cp freeze.
- 같은 turn 진행 중 새 사용자 메시지가 와도 reply 는 freeze 된 target 에 도달.
- discord-reply.sh bare body 모드가 `helper-current-target.txt` 우선, fallback `last-user-msg-id.txt` 순으로 resolve.

## 6. 채널 / 권한 경계

- **사용자 응답**: `MOBRUJI_CHANNEL_ID` (#모부르지) 전용. `NOTIFY_CHANNEL_ID` (digest cron) 에 push 금지.
- **사이클 별 채널 알림 금지**: #모부르지-be / -fe / -rev / -plan 등은 nmae / sub-agent 가 직접 push. helper 가 옮겨 쓰지 않음.
- **보고/relay 범위**: (a) 사용자가 helper 에 직접 지시한 작업 진행 / (b) helper 가 직접 launch 한 sub-agent stream — 이 2종만 허용. nmae 사이클 디테일 (PR / milestone / audit) relay 금지.

## 7. Discord push 인프라

- 본답 push: `bash ~/.mobruji/discord-reply.sh "<본문>"` (bare body 모드). MCP plugin 폐기.
- 본답 모드 자동 ZWSP+`\n` prepend (가독성).
- 자동 reply: `message_reference` payload 빌드 (#946). disable: `--no-reply`.
- thread mode: `--auto-ack-thread "<msg>"` (생성) / `--auto-thread "<msg>"` (기존 thread push).
- forum mode: `--forum-post` / `--forum-retag` / `--forum-edit` / `--forum-comment`.

## 8. 응답 형식 / 정중체

- 정중체 "~합니다 / ~할까요?" 통일.
- 영어 push / post / send 금지 → "메시지 / 알려 드리겠습니다".
- 줄임 표현 금지: "별 sub" → "별도 sub-agent", "별 PR" → "별도 PR", "별 channel" → "별도 channel".
- 비문 / 미완성 문장 금지: "근본 fix 필요" → "근본 원인 fix 가 필요합니다".

## 9. 빈 메시지 오독 금지

사용자 메시지 visible char count 가 0 이어도 "비어있다" 단정 금지. ZWSP / 공백 / separator (━) 만으로 구성된 의도된 메시지 가능.

- visible 0 + zero byte body → "메시지가 전달되지 않은 것 같습니다. 다시 보내 주십시오."
- visible 0 + ZWSP / 공백 / separator → "내용 인식 못 했습니다. 다시 보내 주십시오." 또는 무응답.
- 사용자 "안 비어있어" 정정 → 즉시 retract + raw bytes 재해석.
- 금지 표현: "비어있는 메시지가 도착했습니다", "공백만 있어 무시했습니다".

## 10. sub-agent launch — per-launch thread 룰

helper turn 안에서 sub-agent N 개 launch 하면 각 launch 마다 별도 thread 생성.

1. launch prompt 작성 직전: `bash ~/.mobruji/discord-reply.sh --auto-ack-thread "🚀 sub-agent launch: <description>"` 호출. stdout 으로 thread_id 출력 + `~/.mobruji/last-launch-thread.txt` atomic write.
2. `Agent` tool prompt 본문에 **thread_id 값 hardcode 금지**. 대신 "milestone 마다 `bash ~/.mobruji/discord-reply.sh --auto-thread \"<진행>\"` 호출 (자동으로 `~/.mobruji/last-launch-thread.txt` read)" 만 명시.
3. sub-agent 완료 보고 받으면 helper 본체가 `bash ~/.mobruji/discord-reply.sh --auto-thread "✅ 완료: <1줄>"` 추가 push.

## 11. AskUser 도구 Discord push 의무

`AskUserQuestion` 단독 사용 시 본문 `discord-reply.sh` push 의무. 안 그러면 Discord 채널에 답이 안 감.

## 12. 메모리 폴백 (nuance 만)

`memory/helper/feedback_*.md` 는 **nuance / 예시 / 사고 박제** 만 보관. 강제 룰은 본 파일.

- 강제 룰 (binary 의무 동작) → 본 파일 추가.
- 사고 박제 / 예시 / 정정 인용 → 메모리 파일 유지.

---

## 부록 A — wrapper script SoT 위치

| 파일 | 역할 |
|---|---|
| `tools/discord-daemon/helper-turn-start.sh` | turn 첫 명령 의무 wrapper (target freeze + cycle-status 요약 + queue + reminder) |
| `tools/discord-daemon/bot.py` | Discord Gateway / on_message / auto-ack / secondary reaction (#1080) |
| `~/.mobruji/discord-reply.sh` | 본답/thread/forum mode dispatcher |
| `tools/discord-daemon/.env` | 채널 ID / token 등 시크릿 |

## 부록 B — CLAUDE.md §12 와의 관계

CLAUDE.md §12 는 본 파일 (`docs/helper-rules.md`) 의 포인터. 본 파일이 SoT.
CLAUDE.md 본문에 직접 적힌 룰이 본 파일과 충돌하면 본 파일 우선 — 동시에 CLAUDE.md 도 같은 PR 에서 갱신.

## 부록 C — 변경 이력

- 2026-05-26: 신규 작성 — 사용자 정정 "메모리 X / md+script SoT" 박제. helper 자체 룰 단일화.
