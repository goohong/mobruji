---
feature: Helper Role Enforcement (relay-only 정의 + system prompt 강제 메커니즘)
slug: helper-role-enforcement
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-26
---

# Helper Role Enforcement — relay-only 정의 + system prompt 강제 메커니즘

> 본 spec 은 `helper-agent` (helper 도입 spec, `docs/features/helper-agent.md`) 의 후속이다.
> helper 가 **사용자 ↔ nmae 양방향 relay 전담** 이라는 역할을 학습 의존 없이
> **systemd ExecStart + Claude `--append-system-prompt`** 로 **강제** 한다.

## 1) 배경 (Why)

- helper 도입 spec(`docs/features/helper-agent.md`) 은 helper 의 책임을 정의했지만,
  실제 운영에서 helper 가 "사용자가 시킨 작업" 을 자기 본체 reasoning 으로 처리하려는
  경향이 반복되었다. 메모리/CLAUDE.md/`docs/ai-harness/actors/helper.md` 에 룰을 박제했으나
  **학습 의존** 이라는 한계가 남아 있었다.
- 사용자 정정 (2026-05-26):
  > "helper 본체는 코드/머지/조사/스크립트 실행 일체 금지. 무조건 nmae 위임. 메모리
  > 박제 다 했어도 또 반복하니, system prompt 로 강제해라."
- 본 spec 은 이 정정을 강제 메커니즘 단으로 끌어올린다.

## 2) 정의 — helper 의 relay-only 책임

helper 본체의 책임을 다음 4 종으로 **한정** 한다. 그 밖의 모든 행위는 nmae 또는
sub-agent 의 영역이며, helper 가 직접 수행할 경우 본 spec 위반이다.

| 책임 | 내용 |
|---|---|
| (a) 인바운드 relay | 사용자 Discord 메시지를 nmae(`tmux mobruji`) 로 전달 |
| (b) 아웃바운드 relay | nmae / sub-agent 결과를 사용자에게 `discord-reply.sh` 로 전달 |
| (c) 상태/진행 답변 | 사용자 질의에 대한 사실 답변 (작업 없음) |
| (d) Discord push 운영 | `discord-reply.sh` 호출, thread 생성, writing marker, queue 갱신 |

### 절대 금지 (= "작업" 으로 간주)

- 코드 구현/수정, 파일 편집, PR 생성/머지, git/branch/worktree 조작
- cleanup, 테스트나 스크립트 실행, 배포
- 로그/evidence 수집·분석 (사용자가 직접 helper 에 요청해도 nmae 로 라우팅)
- "단순해 보이는 한 줄 수정" 도 helper 가 직접 수행 금지

**원칙**: 작업인지 애매하면 "작업" 으로 간주하고 nmae 에 위임한다.

## 3) 강제 메커니즘 (How)

### 3-1) 강제의 4 채널

| 채널 | 역할 | 파일/위치 |
|---|---|---|
| **system prompt append (강제 1)** | Claude CLI 가 helper 세션 시작 시점에 system prompt 끝에 relay-only 정의를 append. helper 가 학습 안 했어도 매 turn 마다 룰을 보게 됨. | `~/.mobruji/helper-role.md` (SoT), `tools/discord-daemon/helper-role.md` (reference 사본) |
| **launcher script (강제 2)** | `systemd ExecStart` 가 직접 `claude` 를 호출하지 않고 본 런처를 호출. 런처가 `--append-system-prompt "$(cat ~/.mobruji/helper-role.md)"` 를 항상 붙임. | `~/.mobruji/helper-launch.sh` (SoT), `tools/discord-daemon/helper-launch.sh` (reference 사본) |
| **systemd unit (강제 3)** | `mobruji-helper.service` 의 `ExecStart` 가 위 런처 경로를 hardcode. 서비스 재시작 시점에 늘 동일 진입점. | `/etc/systemd/system/mobruji-helper.service` (운영), `tools/discord-daemon/mobruji-helper.service` (reference 사본) |
| **메모리/CLAUDE.md (강제 4, 보조)** | `[[feedback-helper-role-boundary]]`, `[[feedback-helper-relay-scope]]`. 학습 의존 보조. | `memory/helper/*.md`, `CLAUDE.md §12` |

본 spec 의 차별점은 (1)·(2)·(3) 채널 — helper 가 자율 학습 못 한 상태로 재시작돼도
session 첫 turn 부터 룰이 prompt 안에 들어가 있다는 점이다.

### 3-2) 런처 본체 (참고용 inline)

```bash
#!/bin/bash
# mobruji-helper.service 가 호출하는 런처 — 안정 경로 역할 프롬프트를 system prompt 로 주입.
# 브랜치 전환에 안 흔들리도록 repo 밖(~/.mobruji)에 둔다.
exec /usr/bin/claude --dangerously-skip-permissions --append-system-prompt "$(cat /home/mobruji/.mobruji/helper-role.md)"
```

### 3-3) systemd unit 본체 (참고용 inline)

`ExecStart` 의 `helper-launch.sh` 경로가 핵심.

```ini
ExecStart=/bin/bash -c 'if ! /usr/bin/tmux has-session -t helper 2>/dev/null; then /usr/bin/tmux new-session -d -s helper -c /home/mobruji/mobruji "/home/mobruji/.mobruji/helper-launch.sh"; fi'
```

(전체: `tools/discord-daemon/mobruji-helper.service`)

## 4) SoT 와 repo 사본의 관계

- **운영 SoT** = `~/.mobruji/helper-role.md`, `~/.mobruji/helper-launch.sh`,
  `/etc/systemd/system/mobruji-helper.service`. 브랜치 / `git checkout` 에 안 흔들리도록
  repo 밖에 둔다.
- **repo reference 사본** = `tools/discord-daemon/helper-role.md`,
  `tools/discord-daemon/helper-launch.sh`, `tools/discord-daemon/mobruji-helper.service`.
  PR 리뷰·감사·복구용. 변경 시 SoT 와 동시 갱신해야 한다.
- 두 채널이 **mutual mirror** — 어느 한쪽만 변경되면 본 spec 위반.

### 운영 변경 절차

1. PR 에서 repo 사본(`tools/discord-daemon/*`) 갱신·머지.
2. NCP 호스트에서 SoT 동기화:
   ```bash
   sudo cp /home/mobruji/mobruji/tools/discord-daemon/helper-role.md /home/mobruji/.mobruji/helper-role.md
   sudo cp /home/mobruji/mobruji/tools/discord-daemon/helper-launch.sh /home/mobruji/.mobruji/helper-launch.sh
   sudo chmod +x /home/mobruji/.mobruji/helper-launch.sh
   sudo cp /home/mobruji/mobruji/tools/discord-daemon/mobruji-helper.service /etc/systemd/system/mobruji-helper.service
   sudo systemctl daemon-reload
   sudo systemctl restart mobruji-helper.service
   ```
3. 재시작 후 helper tmux pane 첫 turn 에 system prompt 가 적용되었는지 확인 —
   사용자가 "작업" 질의를 보내면 helper 가 nmae 로 라우팅하는지 sanity 체크.

## 5) 검증 (Verification)

- `systemctl cat mobruji-helper.service | grep helper-launch.sh` → 런처 경로 확인.
- `head -1 ~/.mobruji/helper-launch.sh` → shebang 확인 / `--append-system-prompt` 토큰 grep.
- `ps -ef | grep claude | grep helper` → 실제 프로세스가 `--append-system-prompt` 인자
  포함하는지 확인.
- helper 가 "작업" 질의를 nmae 로 라우팅하는지 — 사용자 sanity check.

## 6) 위반 사고 박제

| 일자 | 사고 | 대응 |
|---|---|---|
| 2026-05-26 | helper 본체가 사용자 지시("PR 머지")를 자기 reasoning 으로 처리하려 함 | 본 spec 으로 system prompt 강제 |

## 7) 오픈 이슈

- helper 의 system prompt 가 변경되었을 때 cron digest 에 표시할지 (drift 감지).
- repo 사본과 SoT 간 sha256 mismatch detection — `bot.py` 보조 task 후보.
