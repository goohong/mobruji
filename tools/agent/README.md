# mobruji-agent

Claude Agent SDK orchestration — **legacy CLI+tmux+bash daemon 시스템을 대체** (2026-05-29 결정).

## 상태 — Phase 1.1 (skeleton)

- ✅ dir + pyproject.toml + main.py skeleton
- ⏳ Phase 1.2 — state.py + events.py (SQLite event log + state machine)
- ⏳ Phase 1.3 — tools_*.py (12 tool)
- ⏳ Phase 1.4 — agent.py (SDK query loop)
- ⏳ Phase 1.5 — tests + CI
- ⏳ Phase 2 — bot.py 변경 (tmux send → SQLite event INSERT)
- ⏳ Phase 3 — NCP 배포 + 운영 확인
- ⏳ Phase 4 — legacy systemd disable
- ⏳ Phase 5 — 1-2주 안정 운영 확인
- ⏳ Phase 6 — legacy 코드 폐기

## 마이그레이션 결정 (2026-05-29)

| 결정 항목 | 선택 |
|---|---|
| IPC | event log + state table (SQLite, append-only) |
| State machine | 계층 hierarchical (GlobalState dataclass) |
| Subagent handoff | SDK subagent primitive |
| Tools | 12개 최소 (옵션 A) |
| Scope | 정식 마이그레이션 (Phase 단계) |
| 위치 | `tools/agent/` |
| 내부 구조 | flat + tools_*.py 분리 |
| systemd | 단순 simple + Restart=always |
| venv | `tools/agent/venv/` |

## 폴더 구조 (Phase 1 완료 시점)

```
tools/agent/
├── main.py              # entry point (systemd ExecStart)
├── agent.py             # SDK query() loop
├── state.py             # GlobalState dataclass + transitions
├── tools_discord.py     # post_discord_message, forum_*
├── tools_cycle.py       # get/set_cycle_state, register_directive_pending, update_directive_status
├── tools_subagent.py    # launch_subagent
├── tools_pause.py       # pause_global, resume_global
├── events.py            # SQLite event log read/write
├── pyproject.toml
├── README.md
├── .gitignore
├── venv/                # (gitignored)
└── tests/
```

## 12 tool (옵션 A)

1. `post_discord_message(channel_id, body, reply_to?, thread_id?)`
2. `forum_create_thread(forum_id, title, body, tags?)`
3. `forum_comment(thread_id, body)`
4. `forum_retag(thread_id, tag_name)`
5. `forum_edit_starter(thread_id, body)`
6. `launch_subagent(cycle, title, task, pending_thread_id)` — **pending_thread_id 인자 강제** (legacy 사고 path 차단)
7. `register_directive_pending(directive_id, summary, cycle_hint?)`
8. `update_directive_status(directive_id, new_status, pr_url?, closed_reason?)`
9. `get_cycle_state(cycle)`
10. `set_cycle_state(cycle, status, ...)`
11. `pause_global()`
12. `resume_global()`

## architecture reference

- `docs/architecture/2026-05-29-legacy-cli-tmux-snapshot.md` — legacy 시스템 + 사고 history
- `docs/architecture/2026-05-29-legacy-ncp-inventory.md` — NCP 운영 본체 inventory
- git tag `pre-claude-sdk-2026-05-29` — 마이그레이션 직전 코드 snapshot

## 실행 (Phase 1.5 후)

```bash
cd tools/agent
python3 -m venv venv
./venv/bin/pip install -e .
./venv/bin/python -m main
```

NCP systemd (Phase 2 후):
```bash
sudo systemctl start mobruji-agent.service
```
