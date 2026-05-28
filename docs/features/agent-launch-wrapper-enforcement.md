---
feature: Agent Launch Wrapper Enforcement (사이클 가시화 + wrapper 호출 강제)
slug: agent-launch-wrapper-enforcement
status: draft
owner: @goohong
scope: infra
related_issues: [1008, 1106, 1117]
related_prs: [1181]
last_reviewed: 2026-05-28
---

# Agent Launch Wrapper Enforcement — 사이클 가시화 + wrapper 호출 강제

> 본 spec 은 `tools/agent-launch-wrapper.sh` (#1008 + B2 2026-05-24 + #1106) 가 이미
> 구현된 강제 진입점임을 전제로, **wrapper 호출 자체를 학습 의존이 아닌
> 메커니즘 단** 에서 강제하는 설계를 명문화한다.

## 1) 배경 (Why)

- `tools/agent-launch-wrapper.sh` 는 nmae 가 sub-agent launch 직전 호출해야 하는
  강제 진입점이다. 다음 5 책임을 한 번에 수행한다:
  1. `cycle-status/update.sh <ws> set-active` (idle 분류 탈출)
  2. per-cycle 채널 launch 알림 + thread 생성 (BE/FE/REV/PLAN_CHANNEL_ID)
  3. forum 채널 fallback chain (#1106)
  4. 백로그 thread upsert (`--refresh-backlog`)
  5. launch prompt emit (`--echo-prompt`)
- 그러나 nmae 가 wrapper 호출을 **누락** 하는 사고가 반복되었다:
  - 사용자 2026-05-26 15:20-22 정정: "nmae sub-agent launch 시 wrapper 누락 →
    cycle forum 0 메시지 + #모부르지 채널 사이클 가시화 0 = 사용자 깜깜이."
- 메모리 (`nmae/feedback_per_cycle_channel.md` 등) 박제만으로는 학습 안 한 turn 에서
  여전히 우회 가능 — **메커니즘 단 강제** 필요.

## 2) 강제 메커니즘 (How) — 5 레이어

| 레이어 | 강도 | 위치 | 효과 |
|---|---|---|---|
| (a) PreToolUse hook (Agent) | 차단 | `~/.claude/settings.json` 의 `hooks.PreToolUse[].matcher: "Agent"` | nmae 가 `Agent` 도구 호출 직전 hook 가 `tools/agent-launch-wrapper.sh` 호출 흔적(예: 최근 N 초 안에 `~/.mobruji/last-launch-thread.txt` mtime 또는 `cycle-status.json in_progress` 갱신)을 검증, 미충족 시 차단 또는 warning emit |
| (b) wrapper script self-check | 자기 진단 | `tools/agent-launch-wrapper.sh` | 호출되지 않은 channel/thread 가 없으면 stderr warning + non-zero exit (단, graceful 모드 유지로 launch 자체는 안 막음) |
| (c) nmae system prompt | 학습 강제 | nmae launcher (`tools/discord-daemon/`에 helper-launch.sh 같은 nmae launcher 신설 고려) | `--append-system-prompt` 로 "Agent tool 호출 직전 wrapper 의무" 명문화. helper 의 relay-only 강제 (`docs/features/helper-role-enforcement.md`) 와 같은 패턴 |
| (d) #모부르지 채널 가시화 push | 사용자 감지 | bot.py `cycle_idle_watch_loop` 또는 별도 sync loop | cycle-status `in_progress` 변경 발생했는데 동일 시점 per-cycle channel push 가 없으면 #모부르지 채널에 "<ws> launch wrapper 누락 의심" warning push (cron digest 보완) |
| (e) 위반 박제 | 회고 | `docs/features/agent-launch-wrapper-enforcement.md §6` + 메모리 | 사고 1건마다 박제. 박제 자체로는 강제 아니지만 (a)~(d) 설계 근거 보존 |

### 2-1) PreToolUse hook 검토 (레이어 a)

- Claude Code `hooks.PreToolUse` 에 `matcher: "Agent"` 등록하면 Agent 도구 사용
  직전 임의 명령 실행 가능 (`docs/ai-harness/` 또는 [Anthropic 공식 hook 문서] 참조).
- 명령 종료 코드가 비제로면 Agent 도구 호출 차단.
- 검증 명령 예 (의사 코드):
  ```bash
  ws=$(echo "$TOOL_INPUT" | jq -r '.prompt' | grep -oE 'mobruji-(be|fe|rev|plan)' | head -1 | sed 's/mobruji-//')
  [ -z "$ws" ] && exit 0  # workspace 추출 실패 = sub-agent launch 아님, 통과
  last_active_ts=$(jq -r ".$ws.in_progress.started_at // empty" ~/.mobruji/cycle-status.json)
  last_thread_mtime=$(stat -c %Y ~/.mobruji/last-launch-thread.txt 2>/dev/null || echo 0)
  now=$(date +%s)
  # 최근 5분 안에 wrapper 흔적이 있어야 통과
  if [ $((now - last_thread_mtime)) -gt 300 ]; then
    echo "[hook] wrapper 호출 누락 의심 — tools/agent-launch-wrapper.sh 먼저 호출" >&2
    exit 1
  fi
  ```
- 트레이드오프: nmae 가 wrapper 우회 의도 시 false positive 위험 — 환경변수
  `AGENT_LAUNCH_HOOK_BYPASS=1` 으로 일회성 우회 허용 (감사 trail Discord push).

### 2-2) wrapper self-check (레이어 b)

- wrapper 가 자기 단계 별 성공/실패를 `~/.mobruji/agent-launch-wrapper.log` 에 append.
- 각 launch 시점에 (1) set-active 성공 / (2) per-cycle push 성공 / (3) backlog upsert
  성공 여부를 한 줄 jsonl 로 기록.
- 다음 launch 시점에 직전 launch 의 (1)~(3) 모두 success 인지 확인 후 미충족이면
  warning. cron digest 가 본 log 를 5분 polling 해 mismatch 가시화.

### 2-3) nmae system prompt append (레이어 c)

- helper 가 `~/.mobruji/helper-role.md` + `~/.mobruji/helper-launch.sh` 패턴으로
  강제하는 것과 동일하게, nmae 도 `~/.mobruji/nmae-role.md` +
  `~/.mobruji/nmae-launch.sh` 패턴 도입 검토.
- `nmae-role.md` 안에 "Agent tool 호출 직전 `tools/agent-launch-wrapper.sh <ws>
  --title \"...\" --echo-prompt \"...\"` 호출 의무" 명시.
- nmae 가 매 session 시작 시점에 system prompt 로 룰을 보게 됨 — `/clear` 후에도
  학습 휘발 없음.

### 2-4) #모부르지 채널 가시화 push (레이어 d)

- 사용자 정정 인용 (2026-05-26): "사이클 launch 가 #모부르지 채널에서도 한 줄
  보였으면 좋겠다. 디테일은 per-cycle channel 이지만 가시화는 사용자 메인 채널에."
- 본 룰은 `actors/nmae.md` §11-6 (`[[feedback-nmae-status-channel]]`) "#모부르지 leak 금지" 와 충돌
  같지만 차이가 있다 — leak 금지는 **디테일** 디테일, 가시화는 **한 줄 요약**.
  본 spec 은 다음 규칙으로 분리한다:
  - per-cycle channel: launch 본문 (title / task / thread 생성 / milestone stream)
  - #모부르지 채널: **하루 1-2 회 본진 활동 요약** 만 (cron digest 가 cover)
  - 또는 nmae 가 wrapper 호출 직후 **명시적으로 사용자에게 보고할 일** 이 있을 때
    "<ws> 사이클 — <한 줄>" 만 1회 push (`actors/nmae.md` §11-6 예외 2 와 일치)
- bot.py 가 wrapper log mismatch 감지 시 #모부르지 채널에 warning push 하는 것이
  본 레이어의 핵심.

### 2-5) 위반 박제 (레이어 e)

§6 참조.

## 3) `actors/nmae.md` 연결 (§11-2 / §11-5)

- §11-2 "watchdog inject 대응 의무 절차" 의 단계 2 행이 이미 wrapper 를 "권장" 으로
  명시한다. 본 spec 은 "권장" → **"의무"** 로 격상하는 근거를 제공한다.
- §11-5 "launch / 통지 / autonomous wake" 의 "launch 까먹기 절대 금지" 와
  "launch 직후 즉시 discord-reply.sh push" 룰을 wrapper 가 한 번에 강제한다 —
  학습 의존 영역을 wrapper 가 흡수.
- `actors/nmae.md` 본문 갱신은 별도 PR 로 처리 (본 spec 머지 후 wrapper 의무 격상 PR).

## 4) 사용자 정정 인용

- 2026-05-26 15:20: "사이클 채널이 너무 조용해. wrapper 호출 안 한 거 같은데."
- 2026-05-26 15:22: "강제 메커니즘 만들어. 메모리 또 박제하지 말고 hook 이든
  wrapper self-check 든. #모부르지 채널에서도 사이클 launch 한 줄 보이게."

## 5) 검증 (Verification)

- PreToolUse hook 추가 후 — nmae 가 wrapper 호출 없이 Agent tool 호출 시 차단 메시지
  emit 확인.
- wrapper log 가 jsonl 로 기록되고 cron digest 가 mismatch 표시하는지 확인.
- #모부르지 채널에 wrapper 누락 warning push 확인.
- nmae system prompt append 후 `/clear` → 새 session 첫 turn 에 wrapper 호출 의무
  인지하는지 sanity check.

## 6) 위반 사고 박제

| 일자 | 사고 | 대응 후보 |
|---|---|---|
| 2026-05-24 | nmae 가 per-cycle channel push 누락 → digest 채널 leak | (c) nmae system prompt, (b) wrapper self-check |
| 2026-05-26 15:20 | nmae 가 wrapper 호출 누락 → cycle forum 0 메시지 + #모부르지 가시화 0 | (a) PreToolUse hook, (d) #모부르지 가시화 push |

## 7) 오픈 이슈

- PreToolUse hook 의 false positive 처리 — `AGENT_LAUNCH_HOOK_BYPASS=1` 일회성
  우회 시 자동 감사 trail Discord push 가 필요한가?
- nmae launcher (`~/.mobruji/nmae-launch.sh`) 신설 결정 — 현재 nmae 는 `claude`
  직접 호출. helper 와 같은 패턴으로 전환 시 운영 절차 변경 영향 평가 필요.
- wrapper log 보존 정책 — `~/.mobruji/agent-launch-wrapper.log` 무한 grow 방지
  rotation 필요.

## 8) 관련 메모리

- `[[feedback-per-cycle-channel]]` (nmae 사이클 별 채널 라우팅)
- `[[feedback-subagent-launch-report]]` (launch 직후 push 의무)
- `[[feedback-nmae-status-channel]]` (status push 채널 분리)
- `[[feedback-helper-role-boundary]]` (system prompt 강제 패턴 참조)
