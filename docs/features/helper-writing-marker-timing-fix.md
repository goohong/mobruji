---
feature: helper writing marker timing fix
slug: helper-writing-marker-timing-fix
status: implementing
owner: plan
scope: infra
related_issues: [1128]
related_prs: [1139, 1143]
last_reviewed: 2026-05-27
---

# helper writing marker timing fix

> 2026-05-26 사용자 16:21-24 directive 박제. helper 본체 ✍️ reaction marker 가 본답
> push 직전/직후만 ON/OFF 되어 사용자 시점 가시화 시간이 ~0초 → turn 시작 시점
> 부터 ON 유지하도록 timing 정정.
>
> **PR 분담**: 본 SPEC PR (plan) + impl PR (be 또는 helper-launched, `helper-turn-start.sh` 1 파일 수정).
>
> **사용자 16:21-24 directive — 사용자 결정 대기 없이 PR 머지까지 자율 완료 목표**.

## §1 현상

helper 본체 응답 생성 흐름에서 ✍️ writing marker (typing indicator + reaction)
의 ON/OFF timing 이 사용자 가시성 측면에서 0초에 가깝습니다.

현재 룰 (CLAUDE.md §12-3 step 6, PR #1095):

| 시점 | 동작 |
|---|---|
| 본답 push 직전 | `discord-reply.sh --writing-marker <msg_id>` → ✍️ ON |
| 본답 push 직후 | `discord-reply.sh --writing-done <msg_id>` → ✍️ OFF |

사용자 입장에서 ✍️ ON 표시 윈도우는 본답 push HTTP latency (~수백 ms) 에 불과
하며, 그 이전 reasoning / 작업 단계 (수 분 ~ 수십 분) 동안에는 marker 가 안 보입
니다. 사용자 정정 (2026-05-26 16:21-24): "helper 가 답 만들고 있는 동안에
✍️ 가 떠 있어야 답 작성 중인 거 인지 됨".

## §2 목표

helper turn 시작 직후부터 본답 push 완료 직후까지 ✍️ marker 가 가시화되도록
timing 을 정정합니다. 사용자가 helper 가 응답 작성 중임을 한 turn 내내 인지
가능해야 합니다.

비목표:
- discord-reply.sh CLI flag 변경 (기존 `--writing-marker` / `--writing-done` 그
  대로 사용)
- bot.py auto hook 동작 변경 (기존 `BOT_WRITING_AUTO_HOOK_ENABLED` env 그대로)

## §3 변경 방향

### TO-BE timing

| 시점 | 동작 |
|---|---|
| helper turn 시작 (`helper-turn-start.sh` wrapper) | `discord-reply.sh --writing-marker <target>` → ✍️ ON |
| 본답 push 직후 (기존 룰 유지) | `discord-reply.sh --writing-done <target>` → ✍️ OFF |

### 핵심 변경

`~/.mobruji/helper-turn-start.sh` wrapper 가 step 2 (target freeze) 직후에
`--writing-marker` 호출을 추가합니다. helper-current-target.txt 가 freeze 된
직후이므로 정확한 target msg id 로 ON 호출이 가능합니다.

본답 push 직후 OFF 호출은 기존 룰 그대로 (CLAUDE.md §12-3 step 6 변경 없음).

### Edge cases

- **turn 종료 전 OFF 누락**: helper 본체가 본답 push 후 `--writing-done` 호출
  안 한 채 turn 끝나면 ✍️ ON 잔존. bot.py `BOT_WRITING_AUTO_HOOK_ENABLED=1` 옵
  트인이 본답 push 자동 hook 으로 OFF 강제 (기존 hook 동작 그대로) — 룰 학습
  안정화 후 default ON 전환.
- **빈 메시지 / 분류 단계 종료**: turn 시작 ON 후 사용자 메시지 분류 결과가
  "응답 불필요" 인 경우에도 turn 종료 직전 OFF 호출 명시. wrapper 가 trap
  EXIT 으로 fallback OFF 호출 (옵션).

## §4 영향 파일

| 파일 | 변경 |
|---|---|
| `~/.mobruji/helper-turn-start.sh` | step 2 (target freeze) 직후 `--writing-marker` ON 호출 추가 |
| `~/.mobruji/discord-reply.sh` | 변경 없음 (`--writing-marker` / `--writing-done` flag 기존 그대로) |
| `bot.py` `BOT_WRITING_AUTO_HOOK_ENABLED` hook | 변경 없음 (기존 동작 유지) |
| `docs/ai-harness/actors/helper.md` | §3 step 6 → step 0 (turn-start wrapper) 안 ON 호출 명시. step 6 의 ON 호출은 wrapper 미사용 fallback 으로만 표기 |
| `CLAUDE.md` §12-3 | step 0 wrapper 의 5 액션 목록에 "✍️ ON" 추가. step 6 본답 push 직전 ON 부분은 wrapper 미사용 fallback 으로만 표기 |
| 메모리 `helper/feedback_helper_writing_marker_timing.md` | 신설 (사용자 16:21-24 정정 박제). nmae 가 갱신 |

## §5 PR 분담

| PR | 작업 | 담당 |
|---|---|---|
| 본 SPEC PR | docs/features 신설 + CLAUDE.md §12-3 + docs/ai-harness/actors/helper.md §3 갱신 + 메모리 박제 권고 | plan (본 사이클) |
| impl PR | `~/.mobruji/helper-turn-start.sh` 수정 (1 파일) | be 또는 helper-launched (후속 사이클) |

impl 변경 = `helper-turn-start.sh` 단 한 파일. PR base = develop.

## §6 검증

| 단계 | 방법 |
|---|---|
| helper turn 시작 직후 ON 호출 확인 | `sudo journalctl -u mobruji-helper -n 30` 또는 `sudo journalctl -u helper-watchdog -n 30` (서비스 명 환경에 따라) — `--writing-marker` 호출 라인 확인 |
| Discord ✍️ reaction 가시 확인 | helper 가 응답 작성 중인 시간 동안 사용자 메시지에 ✍️ reaction 부착 여부 확인 |
| 본답 push 직후 OFF 동작 확인 | 동일 journalctl `--writing-done` 호출 라인 확인 + Discord reaction 제거 확인 |
| turn 종료 후 ✍️ 잔존 사고 회복 | `BOT_WRITING_AUTO_HOOK_ENABLED=1` 옵트인 시 fallback hook 으로 자동 OFF — 잔존 0건 |

## §7 의존

없음. independent change. `helper-turn-start.sh` wrapper 만 수정하면 됨.

## §8 미해결 질문

없음. 사용자 결정 완료 (2026-05-26 16:21-24).

## §9 관련

- PR #1095 — writing marker 도입 PR (본답 push 직전/직후 ON/OFF)
- CLAUDE.md §12-3 — helper turn 절차
- docs/ai-harness/actors/helper.md §3 — helper 강제 룰
- 메모리 `helper/feedback_helper_writing_marker_timing.md` (신설 권고)
