---
feature: Agent Role Enforcement
slug: agent-role-enforcement
status: approved
owner: plan
scope: infra
related_issues: [1124]
related_prs: [1129, 1135]
last_reviewed: 2026-05-26
---

# Agent Role Enforcement — system prompt 강제 메커니즘

> Status: active
> Owner: mobruji nmae + plan sub-agent
> Created: 2026-05-26 (issue #1124, PR docs/agent-role-enforcement-#1124)
> Related: `docs/features/helper-role-enforcement.md` (PR #1129)
>
> CLAUDE.md / 메모리 학습 의존 룰을 system prompt 강제 채널로 옮긴다. 본 spec 은 4 sub-agent (be/fe/rev/plan) + 1 nmae 본진 = 5 actor 의 role 강제 메커니즘을 정의한다.

---

## 1) 배경 — 학습 의존의 한계 (2026-05-26 사고)

CLAUDE.md / 메모리에 룰을 적어두는 방식은 LLM "학습" 에 의존한다. 두 가지 실패가 반복됐다.

1. **본진(nmae) 이 직접 구현 / 머지 / 긴 작업** 을 떠안아 context 폭증 → cycle-status 갱신 정지. 메모리 [[orchestration_pattern]] / [[keep_4_cycles_active]] 가 있어도 학습 안정성 부족.
2. **sub-agent 가 AskUserQuestion 으로 사용자 대기** 진입 → 메모리 [[sub_agent_no_user_wait]] 가 있어도 같은 사고 반복.

해결 방향: **system prompt 강제**. Claude Code CLI `--append-system-prompt` flag 와 `.claude/agents/<role>.md` frontmatter 메커니즘으로 룰을 학습 의존 없이 강제.

---

## 2) 5 actor 의 role 강제 채널

### 2-1) nmae 본진 — `mobruji-maestro.service` → `maestro-launch.sh` → `nmae-role.md`

systemd 가 maestro 본진 tmux 세션을 띄울 때 launcher 경유로 system prompt 주입.

```
[mobruji-maestro.service]
  ExecStart=/usr/bin/tmux new-session -d -s mobruji \
            -c /home/mobruji/mobruji \
            "/home/mobruji/.mobruji/maestro-launch.sh"

[maestro-launch.sh]
  exec /usr/bin/claude --dangerously-skip-permissions \
       --append-system-prompt "$(cat /home/mobruji/.mobruji/nmae-role.md)"
```

`nmae-role.md` 내용 핵심:
- "직접 구현/머지/긴 작업 금지" — 모든 작업 be/fe/rev/plan 위임
- 매 턴 `===CTX:NN%===` marker emit
- cycle-status.json 갱신 의무
- 4 사이클 (be/fe/rev/plan) 유지
- launch 는 `agent-launch-wrapper.sh` 의무 통과 (forum-post + thread)
- release/태그/배포는 사용자 결정 없이 진행 금지
- 보호 영역 변경 PR 은 `needs-human-review + infra` 라벨

### 2-2) 4 sub-agent — `.claude/agents/<role>.md` frontmatter

Claude Code 가 `Agent` tool 호출 시 `subagent_type` 으로 매칭되는 정의 파일을 자동 system prompt 로 주입.

| Role | 파일 | 핵심 강제 |
|---|---|---|
| be | `.claude/agents/be.md` | Spring Boot/Java 21 구현. 품질 게이트 `./gradlew checkstyleMain spotlessCheck test`. AskUserQuestion 금지. |
| fe | `.claude/agents/fe.md` | Next.js/TypeScript 구현. `npm run lint && typecheck && test`. AskUserQuestion 금지. |
| rev | `.claude/agents/rev.md` | 코드 감사 + QA. **구현 금지** — 발견은 후속 이슈/코멘트. release gate `reviewed:claude`. AskUserQuestion 금지. |
| plan | `.claude/agents/plan.md` | docs/ADR/Feature Spec. **프로덕션 코드 금지**. 06-domain-model §7 canonical. AskUserQuestion 금지. |

각 파일 frontmatter `name` + `description` 으로 Claude Code 가 매칭. 본문이 system prompt 로 append.

### 2-3) helper — `docs/ai-harness/actors/helper.md` + `tools/discord-daemon/helper-turn-start.sh`

별도 spec `docs/features/helper-role-enforcement.md` (PR #1129) 참조.

---

## 3) 효과 — 학습 의존 ↓, 강제 ↑

| 채널 | 학습 의존 | 강제력 | 비고 |
|---|---|---|---|
| CLAUDE.md / 메모리 (기존) | 높음 | 낮음 | LLM 이 안 읽거나 안 적용하면 무효 |
| system prompt (`--append-system-prompt` / `.claude/agents/*.md`) | 낮음 | 높음 | turn 마다 자동 주입 — 망각 불가 |
| wrapper script (`agent-launch-wrapper.sh`, `helper-turn-start.sh`) | 낮음 | 중간 | 호출은 의무지만 LLM 이 호출 자체를 까먹을 수 있음 |

**조합 효과**: 룰을 (1) system prompt 로 강제 + (2) wrapper script 로 의무 수행 + (3) 메모리/CLAUDE.md 로 nuance/사고 박제 → 학습 의존 최소화.

---

## 4) 검증 방법

### 4-1) nmae 본진 system prompt 확인

```bash
# nmae 본진 프로세스에 --append-system-prompt flag 있는지 확인
ps aux | grep -E 'claude.*append-system-prompt' | grep -v grep
# 기대 output: /usr/bin/claude --dangerously-skip-permissions --append-system-prompt <nmae-role.md 내용>
```

### 4-2) sub-agent system prompt 확인

```bash
# Agent tool 호출 시 .claude/agents/<role>.md 가 system prompt 에 들어갔는지
# (Claude Code 내부 로직 — 직접 ps 로 안 보이지만 sub-agent turn 첫 응답에서 role 인식 여부로 검증)
# launch prompt 에 "너는 누구냐?" 같은 sanity check 한 줄 넣으면 확인 가능
```

### 4-3) 룰 위반 detect

| 위반 | detect 채널 |
|---|---|
| nmae 직접 코드 변경 | git log author + cycle-status.json 갱신 누락 |
| sub-agent AskUserQuestion 사용 | helper-current-target.txt 변경 없이 helper-queue.jsonl 에 pending 누적 |
| rev 가 파일 수정 | git diff stat — rev 워크트리 commit 발견 |
| plan 이 코드 변경 | git diff — `backend/**` / `web/**` 경로 plan 워크트리 commit 발견 |

---

## 5) 운영 절차 — repo ↔ deploy 동기화

| 파일 | repo 경로 | deploy 경로 | 동기화 |
|---|---|---|---|
| nmae-role.md | `tools/discord-daemon/nmae-role.md` | `~/.mobruji/nmae-role.md` | 수동 (변경 PR 머지 후 NCP 호스트 `cp` + nmae 재시작) |
| maestro-launch.sh | `tools/discord-daemon/maestro-launch.sh` | `~/.mobruji/maestro-launch.sh` | 수동 (변경 후 `chmod +x` 확인) |
| mobruji-maestro.service | `tools/discord-daemon/mobruji-maestro.service` | `/etc/systemd/system/mobruji-maestro.service` | 수동 (`sudo cp` + `sudo systemctl daemon-reload` + `sudo systemctl restart mobruji-maestro`) |
| be/fe/rev/plan.md | `.claude/agents/<role>.md` | `~/.claude/agents/<role>.md` | 수동 (브랜치 전환에 안 흔들리도록 `~/.claude/agents/` 별도 유지) |

**보호 영역**: `mobruji-maestro.service` 는 `*.service` 패턴 — `needs-human-review` 라벨 필수. `.claude/agents/*.md` 는 본 spec 도입 이후 사실상 "AI 보호 영역 행동 룰" — 변경 시 `needs-human-review` 라벨 권고.

---

## 6) 트레이드오프 / 미해결

### 6-1) repo ↔ deploy 경로 불일치
`maestro-launch.sh` 가 절대 경로 `/home/mobruji/.mobruji/nmae-role.md` 를 hardcode. 브랜치 전환에 안 흔들리는 장점, 그러나 repo 안 본체와 deploy 본체가 desync 가능. 변경 후 수동 sync 의무.

### 6-2) `.claude/agents/` 변경 시 즉시 적용
sub-agent 정의 파일은 다음 `Agent` tool 호출부터 즉시 적용 (Claude Code 가 매번 read). nmae 본진은 재시작 필요.

### 6-3) CLAUDE.md / 메모리 redundant 정리
본 spec 도입으로 CLAUDE.md / 메모리의 일부 룰이 redundant. 별도 audit PR (`docs/redundant-rules-audit-#1124`) 에서 후보 식별. 삭제 자체는 사용자 review 후 별도 PR.

---

## 7) 참고

- 코드/시스템 파일:
  - `tools/discord-daemon/nmae-role.md`
  - `tools/discord-daemon/maestro-launch.sh`
  - `tools/discord-daemon/mobruji-maestro.service`
  - `.claude/agents/be.md` / `fe.md` / `rev.md` / `plan.md`
- 관련 spec: `docs/features/helper-role-enforcement.md` (PR #1129)
- 관련 PR: #1129 (helper-role-enforcement), #1130 (evidence-based root cause), #1133 (wrapper enforcement), #1134 (4 사이클 정정)
- 메모리: [[orchestration_pattern]] [[keep_4_cycles_active]] [[sub_agent_no_user_wait]] [[helper_role_boundary]] [[role_expansion]]
