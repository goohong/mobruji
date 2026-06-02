# 2026-05-29 — Legacy NCP 운영 본체 inventory (addendum)

> **status**: archived (companion to `2026-05-29-legacy-cli-tmux-snapshot.md`)
> **git tag**: `pre-claude-sdk-2026-05-29`

본 문서는 base snapshot 의 보강. NCP 운영 본체 파일 / systemd unit / .env keys / file path 구조 full inventory.

## 1) NCP server

| 항목 | 값 |
|---|---|
| host | `mobruji-ncp` (alias) = `root@101.79.20.94` |
| SSH key | `workspace/secret/mobruji-key.pem` |
| user | `mobruji` (uid: discord bot daemon + helper + nmae 실행) |
| home | `/home/mobruji` |
| OS | Ubuntu Linux |

## 2) /home/mobruji top-level dirs

```
/home/mobruji/
├── .mobruji/                # 운영 state + symlinks + ledger (SoT)
├── .claude/                 # Claude Code 사용자 config (subscription credentials)
├── mobruji/                 # repo SoT (nmae 의 work tree + sub-agent 의 develop 기준)
├── mobruji-bridge/          # bot.py daemon 의 격리 deploy dir (브랜치 전환 충돌 방어)
├── mobruji-be/              # be sub-agent worktree
├── mobruji-fe/              # fe sub-agent worktree
├── mobruji-rev/             # rev sub-agent worktree
├── mobruji-plan/            # plan sub-agent worktree
└── wt-be-polish-filter/     # 추가 worktree (특정 작업)
```

## 3) ~/.mobruji/ 안 파일 (SoT)

### 3-1) symlink (repo → 안정 경로) — code 변경 즉시 반영

| symlink | target |
|---|---|
| `discord-reply.sh` | `mobruji/tools/discord-daemon/discord-reply.sh` |
| `helper-direct-work-guard.sh` | `mobruji/tools/discord-daemon/helper-direct-work-guard.sh` |
| `helper-launch.sh` | `mobruji/tools/discord-daemon/helper-launch.sh` |
| `helper-role.md` | `mobruji/tools/discord-daemon/helper-role.md` |
| `helper-tool-progress.sh` | `mobruji/tools/discord-daemon/helper-tool-progress.sh` |
| `helper-turn-start.sh` | `mobruji/tools/discord-daemon/helper-turn-start.sh` |
| `maestro-launch.sh` | `mobruji/tools/discord-daemon/maestro-launch.sh` |
| `nmae-discord-push.sh` | `mobruji/tools/discord-daemon/nmae-discord-push.sh` |
| `directive_append.sh` | `mobruji/tools/discord-daemon/directive_append.sh` |
| `directive_status.sh` | `mobruji/tools/discord-daemon/directive_status.sh` |

### 3-2) NCP-only 파일 (repo 가 SoT 가 아닌 file)

| 파일 | 역할 |
|---|---|
| `maestro-bootstrap.sh` | nmae 재시작 시 handoff resume 자동 inject (setsid detach watcher). 단 maestro-launch.sh 가 호출. |
| `nmae-role.md` | nmae 의 system prompt (repo 안 사본 없음 — NCP 만) |
| `nmae-handoff-*.md` | nmae handoff context (재시작 시 inject) |
| `handoff-2026-05-26-server-restart.md` | 서버 재시작 시 nmae 컨텍스트 복구 |

### 3-3) ledger / state file

| 파일 | 역할 |
|---|---|
| `cycle-status.json` | 4 cycle (be/fe/rev/plan) state — owner=nmae |
| `cycle-counter.json` | 사이클 횟수 누적 |
| `directive-board.jsonl` | directive 백로그 (line-delimited JSON) |
| `directive-detect.jsonl` | bot.py 가 메시지 분류 결과 dump (📌 후보) |
| `helper-queue.jsonl` | helper inbox + `directive_polish` task queue |
| `discord-bridge.sqlite` | bot.py dedup ledger (ledger.claim path) |
| `last-user-msg-id.txt` | 사용자 마지막 메시지 message_id (bot.py write, discord-reply.sh read) |
| `helper-current-target.txt` | helper turn 시 reply target (PR #1246 부터 bot.py 자동 갱신) |
| `helper-current-thread.txt` | helper turn 별 thread_id (discord-reply.sh --auto-thread) |
| `last-launch-thread.txt` | sub-agent launch 시 LAUNCH_THREAD_ID cache |
| `user-presence.json` | 사용자 presence detect |
| `claude-usage.json` | Claude token 사용량 tracker |

### 3-4) backup / archive

- `directive-board-archive-2026-05-28.jsonl` (cleanup 기준)
- `directive-board.jsonl.bak-*` / `directive-board.jsonl.bak.*`
- `helper-role.md.bak.*` / `helper-launch.sh.bak.*` / `maestro-launch.sh.bak.*` / `helper-direct-work-guard.sh.bak.*`
- `tmux-pane.log` (옛 capture, 11 MB)
- `launch-threads-*.json`
- `hook-bypass.log` / `maestro-bootstrap.log`

### 3-5) directory

- `cycle-pending-thread/` (PR #1250 부터 wrapper PENDING_THREAD_ID cache)
- `heartbeat/` (loop heartbeat tracker)

## 4) systemd unit (운영 본체 `/etc/systemd/system/*.service`)

| service | repo reference | Type | User | WorkingDirectory |
|---|---|---|---|---|
| `mobruji-helper.service` | `tools/discord-daemon/mobruji-helper.service` | oneshot + RemainAfterExit | mobruji | `/home/mobruji` (PR #1244 부터 git repo 아닌 dir) |
| `mobruji-maestro.service` | `tools/discord-daemon/mobruji-maestro.service` | forking | mobruji | `/home/mobruji/mobruji` |
| `mobruji-discord-bridge.service` | (운영 only, repo 안 reference 없음) | simple | mobruji | `/home/mobruji/mobruji-bridge/tools/discord-daemon` |
| `mobruji-metric.service` | (운영 only, `/usr/local/bin/mobruji-metric-collector.sh`) | simple | root | (default) |

운영 본체 변경 절차: repo PR 머지 → NCP `sudo cp tools/discord-daemon/<service> /etc/systemd/system/` → `sudo systemctl daemon-reload` → restart.

## 5) `~/.claude/` (Claude Code subscription config)

| 파일 / dir | 역할 |
|---|---|
| `.credentials.json` | Claude Max OAuth credentials (mobruji user 의 인증, PII) |
| `agents/` | sub-agent type 정의 (be/fe/rev/plan agent yaml 또는 md) |
| `backups/` | Claude Code 자체 backup |
| `cache/` | Claude Code TUI cache |
| `channels/` | (Claude Code 내부) |
| `file-history/` | Claude Code file edit history |
| `.last-cleanup` | Claude Code 의 마지막 self-cleanup timestamp |

## 6) `/home/mobruji/mobruji-bridge/tools/discord-daemon/.env` keys (값 X — 보안)

```
ALLOWED_USER_IDS                       # 화이트리스트 (사용자 Discord ID)
BE_CHANNEL_ID / BE_FORUM_ID
FE_CHANNEL_ID / FE_FORUM_ID
REV_CHANNEL_ID / REV_FORUM_ID
PLAN_CHANNEL_ID / PLAN_FORUM_ID
DIRECTIVE_BOARD_CHANNEL_ID / DIRECTIVE_BOARD_FORUM_ID
MOBRUJI_CHANNEL_ID                     # 사용자 메시지 채널
NOTIFY_CHANNEL_ID                      # legacy fallback (DIGEST 으로 대체)
DIGEST_CHANNEL_ID                      # cycle-status digest target
DIGEST_ENABLED / DIGEST_INTERVAL_SECONDS
DISCORD_BOT_TOKEN                      # Bot token (secret)
CLAUDE_BIN                             # multi-token: "claude --dangerously-skip-permissions"
CLAUDE_USAGE_LOOP                      # 0=off (PR #1228 후 비활성)
CONTEXT_AUTO_CLEAR_ENABLED
DEDUP_LEDGER_PATH                      # ~/.mobruji/discord-bridge.sqlite
DIRECTIVE_DETECT_WATCH_ENABLED
DIRECTIVE_DETECT_WATCH_GRACE_COUNT
DIRECTIVE_DETECT_WATCH_INTERVAL
DIRECTIVE_DETECT_WATCH_WINDOW_MINUTES
GITHUB_REPO                            # goohong/mobruji
TMUX_SESSION_NAME                      # mobruji (nmae)
TMUX_TARGET_PANE                       # mobruji:0.0
```

## 7) NCP-only 파일 — repo 가 SoT 가 아닌 path

마이그레이션 시 별도 backup 필요:

1. `~/.mobruji/maestro-bootstrap.sh` (NCP-only watcher)
2. `~/.mobruji/nmae-role.md` (system prompt, repo 안 사본 없음)
3. `~/.mobruji/nmae-handoff-*.md` / `handoff-*.md`
4. `~/.mobruji/cycle-status.json` (4 cycle state)
5. `~/.mobruji/directive-board.jsonl` (백로그 SoT)
6. `~/.mobruji/discord-bridge.sqlite` (dedup ledger)
7. `~/.mobruji/claude-usage.json`
8. `/etc/systemd/system/mobruji-{helper,maestro,discord-bridge,metric}.service` (운영 본체)
9. `/home/mobruji/mobruji-bridge/tools/discord-daemon/.env` (secret)
10. `~/.claude/.credentials.json` (Claude Max OAuth)
11. `/usr/local/bin/mobruji-metric-collector.sh` (metric collector)

## 8) Discord 채널 / forum ID list

`.env` 의 ID 가 SoT. 일부:
- `mobruji 채널 (1506925497651560458)` — 사용자 메시지 입출력
- `BE_FORUM_ID / FE_FORUM_ID / REV_FORUM_ID / PLAN_FORUM_ID` — cycle 별 작업 thread (4 forum)
- `DIRECTIVE_BOARD_FORUM_ID (1507992370044600442)` — 사용자 directive 등록
- `DIGEST_CHANNEL_ID (1507617571384328312)` — 5분 heartbeat digest

## 9) 마이그레이션 시 필요한 SoT 보존 path

base snapshot + 본 addendum 을 통해 다음 path 가 보존:
- repo (git tag `pre-claude-sdk-2026-05-29`) — code SoT
- 본 문서 — 운영 본체 inventory (NCP-only file + .env keys + systemd unit)
- 사용자 NCP backup (별도, secret 포함)

Phase 1 POC 진행 시 위 path 를 reference 로 SDK 안 동등 구조 설계.
