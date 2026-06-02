---
feature: Helper cwd 를 git repo 아닌 dir 로 변경 (relay-only 룰 강제)
slug: helper-cwd-not-git-repo
status: shipped
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-29
---

# Feature — Helper cwd 를 git repo 아닌 dir 로 변경 (relay-only 룰 강제)

- **status**: shipping (2026-05-29, PR fix/helper-cwd-not-git-repo)
- **scope**: infra
- **owner**: nmae
- **메모리**: [[feedback-helper-relay-only]] [[feedback-evidence-based-root-cause]]

## 1) 배경 (사고)

2026-05-29: 사용자가 helper 에 "사이클 재개" 메시지 보냄. helper 가 **직접 git 작업 시도** 시작 (룰 위반):

```
* Cogitating… (3m 26s · ↓ 11.6k tokens)
  ⎿  Error: Failed to resolve base branch "origin/main": git rev-parse failed
  ⎿  $ cd /home/mobruji/mobruji-bridge && git remote -v && git branch -r
```

helper 본체 = relay only ([[feedback-helper-relay-only]]) 룰이 명시돼 있음에도 위반. 원인 추적:

1. helper 의 cwd = `/home/mobruji/mobruji-bridge` (git repo, bridge deploy dir).
2. Claude Code 의 default behavior — cwd 가 git repo 면 자동 context 로드 (CLAUDE.md auto-discovery, git remote, branch).
3. 사용자 "사이클 재개" 메시지 + cwd 의 git context = claude 가 "git 작업" 으로 해석.
4. `helper-role.md` 의 STRICT 룰은 `--append-system-prompt` 로 추가됐지만 **cwd context 가중치를 이기지 못함**.

## 2) Fix

cwd 를 git repo 가 아닌 dir 로 변경. claude code 가 git context 자동 로드 못 함 → 사용자 명령을 git 작업으로 해석할 가중치 자체 사라짐.

- `WorkingDirectory=/home/mobruji` (was `/home/mobruji/mobruji`)
- `tmux new-session -c /home/mobruji` (was `-c /home/mobruji/mobruji`)

`/home/mobruji` 는 사용자 home root — `.mobruji/`, `mobruji/`, `mobruji-bridge/` 등 dir 가 있지만 자체는 git repo 아님.

## 3) 영향

### 긍정
- 룰 강제력 ↑ — system-prompt vs cwd context 다툼 자체 X.
- bridge dir 의 git 상태 깨짐 위험 ↓ (helper 가 직접 rebase/pull 못 함).
- workspace trust prompt 재발 가능성 ↓ (사용자 home 은 보통 이미 trusted).

### 잠재 부작용
- helper-launch.sh 가 호출하는 다른 script (예: discord-reply.sh) 는 ~/.mobruji 안 — cwd 무관, 영향 X.
- helper claude 가 사용자 자유 질의 시 repo 코드 참조 못 함 — 하지만 helper = relay only 라 자유 질의 자체 X.

## 4) 운영

- 운영 본체 `/etc/systemd/system/mobruji-helper.service` 동시 갱신 + `daemon-reload` + helper kill + restart.
- 검증: tmux pane 의 prompt — workspace 가 `/home/mobruji` 로 표시되는지 확인.

## 5) follow-up (검토 후 필요 시 별 PR)

- **B**: helper-role.md 첫 줄 STRICT 강화 — 본 PR 만으로 부족 시.
- **nmae cwd**: nmae 는 `/home/mobruji/mobruji` 유지 (sub-agent launch 시 repo cwd 필요). 본 PR scope 아님.
