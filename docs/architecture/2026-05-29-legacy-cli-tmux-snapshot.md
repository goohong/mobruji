# 2026-05-29 — Legacy CLI + tmux + bash daemon system snapshot

> **status**: archived (pre-Claude-Agent-SDK migration)
> **git tag**: `pre-claude-sdk-2026-05-29`
> **last commit at snapshot**: develop HEAD 2026-05-29 (PR #1250 머지 후)

본 문서는 Claude Agent SDK 마이그레이션 직전 시스템 구조 / 사고 history / 학습을 보존하기 위한 snapshot. 향후 마이그레이션 결과 회귀 / 비교 / 결정 backref 시 참고.

## 1) 시스템 구조 (마이그레이션 직전)

```
┌─────────────────────────────────────────────────────────────────────┐
│  Discord (mobruji 채널 + 5 forum 채널)                              │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ user message / reaction / forum thread
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  bot.py (mobruji-discord-bridge.service)                            │
│  - Python discord.py 2.x daemon                                     │
│  - on_message → helper-queue.jsonl append + tmux inject             │
│  - on_raw_reaction_add → 📌 / ⏹ / ❓ / keycap handler              │
│  - 16 async loops (digest, watchdog, polish, cycle_complete, etc)   │
└────┬────────────────────────────────────────────────────────────────┘
     │ tmux send-keys -t helper:0.0 / mobruji:0.0
     ▼
┌─────────────────────┐  ┌────────────────────────────────────────────┐
│  helper (tmux)      │  │  nmae (tmux mobruji)                       │
│  /usr/bin/claude    │  │  /usr/bin/claude --append-system-prompt    │
│  (relay only)       │◄─┤  (orchestration)                           │
│                     │  │                                            │
│  cwd=/home/mobruji  │  │  cwd=/home/mobruji/mobruji                 │
│                     │  │                                            │
│  - helper-role.md   │  │  - nmae-role.md (system prompt)            │
│  - turn-start       │  │  - cycle-status.json state                 │
│    wrapper          │  │  - watchdog loop                           │
└─────────────────────┘  └──────────┬─────────────────────────────────┘
                                    │ Agent tool launch
                                    │ subagent_type=be/fe/rev/plan
                                    ▼
                         ┌──────────────────────────────────┐
                         │  4 sub-agent (be/fe/rev/plan)    │
                         │  - 각 worktree 분리              │
                         │  - claude subagent (내장)        │
                         │  - tools: 모든 도구              │
                         │  - cycle forum thread 안 stream  │
                         └──────────────────────────────────┘
```

### 핵심 파일

- `tools/discord-daemon/bot.py` (5500+ LOC) — Discord daemon
- `tools/discord-daemon/discord-reply.sh` (2200+ LOC) — helper / nmae / sub-agent 의 Discord push wrapper
- `tools/discord-daemon/helper-launch.sh` / `maestro-launch.sh` — tmux 안 claude 인스턴스 launch
- `tools/discord-daemon/helper-turn-start.sh` — helper LLM 매 turn 첫 명령 (target freeze + queue 표시)
- `tools/agent-launch-wrapper.sh` — nmae 가 sub-agent launch 직전 호출 (cycle-status set-active + forum push)
- `tools/discord-daemon/helper-role.md` / `nmae-role.md` — system prompt (룰 명시)
- `~/.mobruji/cycle-status.json` — 4 cycle (be/fe/rev/plan) 의 state
- `~/.mobruji/directive-board.jsonl` — directive 백로그 (line-delimited JSON)
- `~/.mobruji/helper-queue.jsonl` — helper inbox + directive_polish task queue

### 5 Discord forum 채널

- DIRECTIVE_BOARD_FORUM_ID — 사용자 directive 등록 (📌 pin)
- BE_FORUM_ID / FE_FORUM_ID / REV_FORUM_ID / PLAN_FORUM_ID — cycle 별 작업 thread

## 2) 설계 의도 (좋은 점)

1. **분리된 책임** — nmae (orchestration) / helper (relay) / sub-agent (worker). 사용자 → helper → nmae → sub-agent 흐름.
2. **Claude Code subscription 활용** — 추가 결제 없이 4 사이클 + helper + nmae LLM 호출.
3. **Discord 가 사용자 인터페이스** — IDE / CLI 안 켜도 모바일에서 사용 가능.
4. **cycle forum 가시화** — sub-agent 작업이 thread 안 stream + 4-tag lifecycle (🟡 → ⏳ → ✅).
5. **PR 머지 webhook 자동화** — directive_complete_on_merge_loop (5분 polling) 가 PR body 의 `directive: <id>` 매칭 시 자동 completed 전이.

## 3) 사고 history + 학습 (마이그레이션 trigger)

### 3-1) 학습 의존 사고 패턴

| 사고 | path |
|---|---|
| helper 가 git 작업 직접 시도 | cwd 가 git repo → claude TUI 가 git context 자동 로드 + 사용자 명령을 git 작업으로 해석. helper-role.md (system prompt) 의 룰이 cwd context 가중치를 못 이김. |
| helper 가 답 push 안 함 | helper-role.md 에 push 의무 명시 안 됐을 때 LLM 이 답 텍스트만 출력하고 turn 종료. |
| helper-turn-start.sh 호출 누락 | LLM 의 매 turn 첫 명령 학습 의존. 호출 안 하면 helper-current-target.txt 옛 값 frozen → 옛 메시지에 reply. |
| nmae 가 사이클 정지 명령 무시 | cycle-status.json 의 paused flag 부재. nmae LLM 이 watchdog 의 idle 보고 자동 launch. |
| nmae 가 wrapper launch 시 pending-thread-id 인자 누락 | register-pending 후 launch mode 호출 시 명시 인자 학습 의존. |
| sub-agent 가 결과 push 시 별 thread 생성 | sub-agent.md spec 에 LAUNCH_THREAD_ID 안 stream 명시 됐지만 학습 의존, 매 사이클 결정 변동. |

### 3-2) 사고 fix path 패턴 (반복)

매 사고 → system prompt 룰 추가 → 학습 의존 → 다음 사이클 또 사고. patch 의 길.

### 3-3) Root cause (마이그레이션 결정)

- **agent = 자유 LLM (Claude Code CLI 인스턴스)**. tools 사용 / 작업 결정이 LLM 의 자유 reasoning.
- **통신 = tmux pane text**. 구조화 안 됨. message passing schema 없음.
- **state machine 없음**. cycle-status.json 가 있지만 wrapper / agent 가 강제 enforce 안 함.
- **룰 강제 = system prompt 의 자연어**. LLM 의 학습 의존.

## 4) 마이그레이션 결정 (2026-05-29)

**Claude Agent SDK** 채택. 근거:
- Claude Max subscription 그대로 (추가 결제 X)
- subagent / tools / hooks API 가 명시 — 학습 의존 X
- 우리 stack (Claude Code) 과 그대로 호환
- 학습 곡선 낮음

vendor agnostic 우선시는 LangGraph 도 고려했으나:
- LangGraph 표준 path = Anthropic API key (per-token, 별도 결제)
- self-hosted 무료지만 LLM 호출 비용 추가
- Claude SDK 가 비용 효율 우위

**계획**:
- Phase 1 (3-5일): nmae 1 agent SDK POC — 사이클 state machine + sub-agent handoff + Discord I/O
- Phase 2 (3-5일): helper + sub-agent 마이그레이션
- Phase 3 (2-3일): bot.py 통합
- Phase 4 (1-2일): legacy 폐기 (helper.service / maestro.service / 일부 file)

총 ~2주 (현재 시스템 병행 운영).

## 5) Legacy preservation policy

- git tag `pre-claude-sdk-2026-05-29` (snapshot 자동 보존, develop HEAD 시점)
- 본 문서 (`docs/architecture/2026-05-29-legacy-cli-tmux-snapshot.md`) = 구조 / 사고 / 결정 명시
- legacy 코드 폐기 시 PR description 에 본 snapshot 참조

## 6) 마이그레이션 후 측정 (KPI)

- 학습 의존 사고 발생 횟수 (helper / nmae 룰 위반)
- 매 사고 → 룰 추가 patch 사이클 / 주
- 사용자 frustration (Discord 정정 메시지 수)
- 사용량 (Claude token / 월)
- 사고 발견 → fix → 배포 turnaround time
