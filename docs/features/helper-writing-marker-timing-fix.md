---
feature: helper writing marker timing fix
slug: helper-writing-marker-timing-fix
status: implementing
owner: plan
scope: infra
related_issues: [1128]
related_prs: [1139, 1143, 1248]
last_reviewed: 2026-05-29
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
- PR #1139 — 본 spec 신설 (docs)
- PR #1143 — `helper-turn-start.sh` impl (turn-start ON 호출 추가)
- PR #1248 — `BOT_WRITING_AUTO_HOOK_ENABLED` default ON 전환 spec (본 결정 로그 §10 참조)
- CLAUDE.md §12-3 — helper turn 절차
- docs/ai-harness/actors/helper.md §3 — helper 강제 룰
- 메모리 `helper/feedback_helper_writing_marker_timing.md` (신설 권고)

## §10 결정 로그

> 연대기 순. "YYYY-MM-DD: 결정 / 이유 / 출처(PR 번호 등)"

- 2026-05-26: 초안 작성 (status=draft). 사용자 16:21-24 directive. (PR #1139)
- 2026-05-26: helper-turn-start.sh wrapper 가 step 2 (target freeze) 직후 `--writing-marker` 호출 자동 추가. (PR #1143 impl)
- 2026-05-29: **`BOT_WRITING_AUTO_HOOK_ENABLED` default OFF → ON 전환 결정**.

### §10-1 default ON 전환 (2026-05-29, PR #1248)

#### AS-IS

`tools/discord-daemon/discord-reply.sh:388`:

```bash
BOT_WRITING_AUTO_HOOK_ENABLED="${BOT_WRITING_AUTO_HOOK_ENABLED:-0}"
```

- default OFF — 옵트인 시 (`BOT_WRITING_AUTO_HOOK_ENABLED=1`) 만 bare body 본답 push hook 으로 ✍️ 자동 OFF 강제.
- 도입 시점 (2026-05-26 §3 Edge cases) 사유: "룰 학습 안정화 후 default ON 전환".

#### 전환 trigger 충족 (2026-05-29)

1. **wrapper turn-start ON 안정화** — PR #1143 머지 후 helper-turn-start.sh 가 정상 동작. turn 시작 ON 호출 회귀 없음.
2. **OFF 누락 사고 박제 가능** — turn 종료 후 ✍️ 잔존 사고가 발생할 경우 default OFF 환경에서는 메모리 박제 + 룰 학습에만 의존. default ON 환경에서는 bot.py 자동 hook 가 fallback OFF 보장 → 사고 0건.
3. **NCP / mac 환경 모두 systemd / launchd 환경변수 일관** — default 값 변경 시 환경별 분기 없이 일관 적용 가능.

#### TO-BE

`tools/discord-daemon/discord-reply.sh:388` default 값 `0` → `1`.

```bash
BOT_WRITING_AUTO_HOOK_ENABLED="${BOT_WRITING_AUTO_HOOK_ENABLED:-1}"
```

- 명시 `=0` 설정 시 OFF 유지 (회귀 escape hatch — production 사고 시 즉시 roll-back 경로).
- env 미설정 = default ON.

#### 회귀 가드

| 항목 | 가드 |
|---|---|
| 본답 push 에 ✍️ OFF 호출 부재 시 | bot.py 자동 hook 가 fallback OFF → ✍️ 잔존 0건 |
| 의도적 OFF 누락 (예: writing marker 안 쓰는 helper sub-agent 우회) | env `BOT_WRITING_AUTO_HOOK_ENABLED=0` 명시 부여로 roll-back |
| test_helper_ux.py `test_writing_auto_hook_default_off` 가 default OFF 가정 | **테스트도 default ON 가정으로 갱신 (impl PR 동시)** — `extra_env` 에서 명시 `=0` 옵트아웃 케이스로 분리 |
| systemd / launchd 서비스 unit env 분기 | 변경 없음 (default 값만 swap, 명시 env override 그대로) |

#### impl PR 분담 (후속)

| PR | 작업 | 담당 |
|---|---|---|
| 본 spec PR (§10-1 결정 로그) | docs 신설 + 결정 사유 박제 + 회귀 가드 명시 | plan (본 사이클) |
| impl PR (별도) | `tools/discord-daemon/discord-reply.sh:388` default `0`→`1` + `tools/discord-daemon/tests/test_helper_ux.py` default ON 가정으로 갱신 | be 또는 helper-launched (후속 사이클) |

impl PR 변경 = `discord-reply.sh` 1 줄 + 관련 테스트 갱신. PR base = develop, scope=infra, type=fix (default 동작 변경이라 fix).

#### 검증 (impl PR 시)

```bash
# 1. default ON 확인 (env 미설정)
unset BOT_WRITING_AUTO_HOOK_ENABLED
bash tools/discord-daemon/discord-reply.sh "테스트 본답" --target-id <msg_id>
# → journal 에 bot.py 가 ✍️ 자동 OFF 호출 라인 확인

# 2. opt-out 회귀 가드 확인
BOT_WRITING_AUTO_HOOK_ENABLED=0 bash tools/discord-daemon/discord-reply.sh "테스트 본답" --target-id <msg_id>
# → journal 에 자동 OFF 호출 부재 (기존 동작 보존)

# 3. 테스트 swap 검증
cd tools/discord-daemon && python3 -m pytest tests/test_helper_ux.py::test_writing_auto_hook_default_on -xvs
```

#### Roll-back 경로

production 사고 발생 시 즉시 roll-back 2 방법:

1. **runtime env override** — systemd / launchd unit 에 `BOT_WRITING_AUTO_HOOK_ENABLED=0` 명시 → daemon 재시작. 영구 default 복귀 없이 즉시 안정화.
2. **default revert** — `discord-reply.sh:388` 1 줄 revert PR. 모든 사이트 동기.

선택 기준: 사고 원인이 자동 hook 자체면 (1) + 본 spec 보강. 사고 원인이 default ON 가정 자체면 (2) + 본 spec status=blocked 전환.
