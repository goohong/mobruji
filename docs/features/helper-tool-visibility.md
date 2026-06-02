---
feature: Helper 도구 호출 가시화 (PreToolUse Hook)
slug: helper-tool-visibility
status: shipped
owner: @goohong
scope: infra
related_issues: []
related_prs: [1230]
last_reviewed: 2026-05-29
---

# Feature Spec — Helper 도구 호출 가시화 (PreToolUse Hook)

- **status**: shipping (2026-05-29, PR feat/helper-tool-visibility-hooks)
- **scope**: infra
- **owner**: nmae
- **관련 PR**: #1230 (helper control emoji ⏹/❓ 와 짝)
- **메모리**: [[feedback-discord-tone-formal]] [[feedback-autonomous-default]] [[feedback-helper-relay-only]]

## 1) 배경

사용자 요청 (2026-05-29):
> "헬퍼가 내말을 듣고 무슨 작업을 내부적으로 하고있는지 알면 좋겠는데. 터미널처럼 그러다 뭐 이모지로 중단시킨다던지. 그런것도 가능한가?"
> "좋아 터미널처럼 보이는거지? 뭘호출하느냐를 넘어서 ~라는 것이군."
> "그럼 중지, ?랑 도구호출 가시화."
> "어쨌든 부가 응답은 다 스레드로정리."

helper claude 인스턴스가 사용자 메시지 받은 후 도구 호출 (Bash / Edit / Agent 등) 하면서 작업한다. 사용자는 현재 helper 본답 push 직전까지 어떤 도구를 호출하는지 모름 — "터미널처럼" 가시화 요청.

## 2) 목표

- helper 본체가 도구 호출 직전 그 도구 이름 + brief description 을 Discord 의 그 메시지 thread 에 push.
- helper 외 다른 actor (sub-agent / nmae) 의 도구 호출은 push 하지 않음 (noise 차단).
- 도구 호출 실패 차단 절대 금지 (graceful exit).
- 학습 의존 ↓ — 코드 강제 (Claude Code PreToolUse hook).

## 3) 비목표

- 도구 호출 결과 (PostToolUse) push 는 본 spec scope 아님 — follow-up.
- sub-agent 의 도구 호출 가시화는 본 spec scope 아님 (forum 모델로 별도 정리).
- 도구 호출 차단 / approval 기능 없음 — 사용자 control 은 ⏹/❓ emoji 만 (PR #1230).

## 4) 설계

### 4-1) Component

| 파일 | 역할 |
|---|---|
| `.claude/settings.json` | PreToolUse hook 등록 — 모든 도구 호출 (`matcher: ""`) → `$HOME/.mobruji/helper-tool-progress.sh` |
| `tools/discord-daemon/helper-tool-progress.sh` | hook script. stdin JSON parse → discord-reply.sh --auto-thread push |
| `tools/discord-daemon/helper-launch.sh` | `export MOBRUJI_HOOK_ACTOR=helper` 추가 — hook script marker check 용 |
| `tools/discord-daemon/lib/hook_symlinks.sh` | HOOK_NAMES 에 `helper-tool-progress.sh` 추가 — deploy.sh 가 ~/.mobruji 에 symlink |

### 4-2) 실행 흐름

```
사용자 메시지 → helper turn 시작
  → helper-turn-start.sh (✍️ writing marker + helper-current-thread.txt 작성)
  → helper claude 가 도구 호출 (예: Bash)
     → [PreToolUse hook 발동]
        → helper-tool-progress.sh (stdin JSON)
           → MOBRUJI_HOOK_ACTOR=helper 확인
           → tool_name + tool_input 추출
           → discord-reply.sh --auto-thread "💬 Bash: <command>" push
     → 도구 실행
  → helper 답 작성 → discord-reply.sh push
```

### 4-3) helper 본체 marker 차단 (noise)

같은 mobruji user 의 home 의 `~/.claude/settings.json` 도 동일 hook 인식. 즉 nmae / sub-agent claude 호출 시도 같은 hook 실행. helper 본체만 push 하도록 차단 필요.

- `helper-launch.sh` 가 `export MOBRUJI_HOOK_ACTOR=helper` set → exec /usr/bin/claude → 자식 process 가 env 상속.
- `helper-tool-progress.sh` 첫 줄에서 `[[ "${MOBRUJI_HOOK_ACTOR:-}" != "helper" ]] && exit 0` — sub-agent / nmae 호출은 skip.
- sub-agent 는 helper-launch.sh 거치지 않고 별도 wrapper 거쳐서 환경 marker 없음.

### 4-4) 도구별 description 양식

| 도구 | emoji | description |
|---|---|---|
| `Bash` | 💬 | `Bash: <command 첫 100자>` |
| `Edit` / `Write` / `NotebookEdit` | ✏️ | `<Tool>: <file basename>` |
| `WebFetch` / `WebSearch` | 🌐 | `<Tool>: <url 또는 query 100자>` |
| `Agent` / `Task` | 🤖 | `<Tool>: <description>` |
| 기타 | 🛠️ | `<Tool>` |
| `Read` / `Glob` / `Grep` / `TaskList` / `TaskGet` | (skip) | 매 turn 다수 발생 — noise 차단. `MOBRUJI_TOOL_PROGRESS_READ=1` 로 ON 가능 |

### 4-5) thread 정리

discord-reply.sh `--auto-thread` mode 는 자동 chain 으로 thread 해결 (LAUNCH_THREAD_ID env → ~/.mobruji/helper-current-thread.txt). helper turn 시작 시 thread 가 미리 생성돼 있어야 그 thread 안에 push.

- 일반 사용자 메시지 turn: helper 가 답 작성 전 `--auto-ack-thread` 로 thread 생성 → `helper-current-thread.txt` 작성 → 그 turn 의 모든 도구 호출 push 가 그 thread 로.
- ❓ emoji turn: bot.py 가 thread 생성 + thread_id 를 inject text marker `[reply_thread=ID]` 로 전달 → helper 가 marker 인식 → helper-current-thread.txt 작성 → 그 thread 로.

## 5) 운영

### 5-1) 임시 OFF

`export MOBRUJI_TOOL_PROGRESS=0` — hook script 가 즉시 exit. 디버깅 / 일시 noise 차단용.

### 5-2) Read 도구 ON

`export MOBRUJI_TOOL_PROGRESS_READ=1` — Read/Glob/Grep/TaskList/TaskGet 도 push.

### 5-3) 검증

1. helper 본체 메시지 받음 → 도구 호출 → 그 메시지 thread 에 `💬 Bash: ...` push 확인.
2. 같은 NCP 환경에서 sub-agent 호출 → push 가지 않음 (MOBRUJI_HOOK_ACTOR 부재).
3. hook script subprocess 실패 → helper 도구 호출 자체는 정상 (graceful exit 0).

## 6) follow-up

- `PostToolUse` hook 으로 도구 호출 결과 (성공/실패 + 짧은 결과) push.
- `Stop` hook 으로 turn 종료 시 thread 마무리 (자동 archive 등).
- mode toggle button UI (PR #1203 CLOSED 후 재포팅).
- sub-agent forum 의 도구 호출 가시화는 4-tag 모델 (🟡⏳✅❌) 과 별 path 결정 필요.
