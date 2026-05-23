# mobruji discord-daemon

`docs/features/discord-daemon-hosting.md` §3 구현체 — 단순화본 (이슈 #807).
Discord Gateway 에 24/7 붙어 지정 채널의 화이트리스트 사용자 메시지를
helper tmux pane 에 단순 `send-keys` 로 전달하고, `~/.mobruji/cycle-status.json`
기반 digest cron 을 일정 주기로 push 합니다.

helper 응답은 helper 측에서 신규 script `discord-reply.sh "<메시지>"` 가
직접 Discord REST API 로 push 합니다. bot.py 안에서 응답 watcher 가 가동되지
않습니다 (사용자 결정 2026-05-23 큰 단순화 위임).

호스트 옵션 2가지를 같은 `bot.py` 로 공유합니다.

- **옵션 A — macOS LaunchAgent**: 사용자 Mac 이 켜져 있는 동안 가동.
- **옵션 B — Linux/GCP/NCP systemd**: Ubuntu VM. 아래 "Linux/GCP systemd" 절.

## 구성 파일

| 파일 | 역할 |
|---|---|
| `bot.py` | discord.py 기반 Gateway 데몬. 단순화본 — routing + digest 만. |
| `discord-reply.sh` | helper 가 직접 호출하는 응답 push script. |
| `requirements.txt` | discord.py / python-dotenv |
| `.env.example` | 환경변수 템플릿 (실제 값은 `.env` 에 채움) |
| `com.mobruji.discord-daemon.plist` | macOS LaunchAgent 템플릿 (sed 치환 대상) |
| `setup-launchagent.sh` | macOS: venv·.env·plist·launchctl 한 번에 처리 |
| `mobruji-discord-daemon.service` | Linux systemd unit 템플릿 |
| `setup-gcp-systemd.sh` | Linux/GCP: venv·.env·systemd unit 한 번에 처리 |

## 환경 변수

`.env.example` 을 `.env` 로 복사한 뒤 채웁니다. 새 변수가 늘어날 때마다 이 표와
`.env.example` 을 같이 갱신합니다.

### 필수

| env | 기본값 | 설명 |
|---|---|---|
| `DISCORD_BOT_TOKEN` | (없음) | Developer Portal → Bot → Reset Token. MESSAGE CONTENT INTENT 활성 필수. |
| `ALLOWED_USER_IDS` | (없음) | 명령을 받아들일 Discord user id CSV. 정수 비교. 본인만 두는 것을 권장. |
| `MOBRUJI_CHANNEL_ID` | (없음) | 메인 채널 id. 사용자 메시지 / helper 응답 송신 대상. 그 외 채널 메시지는 무시. |

### 옵션

| env | 기본값 | 설명 |
|---|---|---|
| `NOTIFY_CHANNEL_ID` | (미설정 → `MOBRUJI_CHANNEL_ID` fallback) | cycle digest 송신 대상. 알림 채널 분리 시 설정. |
| `TMUX_SESSION_NAME` | `helper` | helper tmux 세션 이름. 없으면 bot 이 `CLAUDE_BIN` 으로 재생성. |
| `TMUX_TARGET_PANE` | `helper:0.0` | `send-keys` 타깃 pane. |
| `CLAUDE_BIN` | `claude` | tmux 세션 부재 시 띄울 실행 파일. 절대경로 권장. |
| `DEDUP_LEDGER_PATH` | `~/.mobruji/discord-bridge.sqlite` | dedup ledger SQLite 경로. 24h TTL GC. |
| `DIGEST_ENABLED` | `1` | `1` 이면 on_ready 직후 asyncio task 가 일정 주기로 cycle-status digest 1건 push. |
| `DIGEST_INTERVAL_SECONDS` | `900` (15분) | digest 주기. 양수 정수만 유효. |
| `CYCLE_STATUS_PATH` | `~/.mobruji/cycle-status.json` | digest 가 읽는 cycle-status JSON 경로. 호스트별 home 디렉토리에 맞춰 `~` 가 자동 확장됩니다. mac/linux 호환 위해 환경별 경로 분리 시 override. |
| `CONTEXT_AUTO_CLEAR_ENABLED` | `0` (opt-in) | `1` 이면 on_ready 직후 `context_auto_clear_loop` 가동. maestro pane 의 `===CTX:NN%===` marker 를 5초 polling 해서 trigger 초과 시 정리 사이클 + `===CLEAR_READY===` 감지 시 `/clear` 전송. 출처: PR #810 (#809 후속, spec `docs/features/context-auto-clear.md §5-2`). |
| `CONTEXT_CLEAR_TRIGGER_PCT` | `95` | auto-clear 트리거 임계치(%). 정수. `CONTEXT_AUTO_CLEAR_ENABLED=1` 일 때만 의미. |
| `CONTEXT_CLEAR_HYSTERESIS_PCT` | `80` | trigger 후 다음 사이클 재무장(rearm) 하한 임계치(%). 정수. trigger 보다 낮아야 함. |
| `TMUX_PANE_TARGET` | `mobruji:0.0` | auto-clear 가 polling/제어할 maestro pane. 세션 부재 시 loop launch 자체를 skip. |

### 폐기된 env (이슈 #807 단순화)

다음 env 는 본 단순화본에서 더 이상 인식되지 않습니다. 운영 `.env` 에서
삭제해도 무방하며, 값이 남아 있어도 무시됩니다.

- `GITHUB_PAT` / `GITHUB_REPO` (repository_dispatch fallback 폐기)
- `TMUX_BRIDGE_ENABLED` (tmux routing 단일화 — 항상 ON)
- `MAESTRO_RESPONSE_WATCHER_ENABLED`, `MAESTRO_WATCHER_*`
- `TMUX_PIPE_PANE_ENABLED`, `TMUX_PIPE_PANE_PATH`, `TMUX_PIPE_PANE_MAX_BYTES`
- `CONTEXT_REFRESH_ENABLED`, `CONTEXT_REFRESH_INTERVAL_SEC`

> `CONTEXT_AUTO_CLEAR_*` 3건은 PR #810 (#809 후속) 에서 `context_auto_clear_loop`
> 와 함께 복구되어 다시 인식됩니다. 위 "옵션" 표 참조.

## helper 응답 push — `discord-reply.sh`

단순화본은 helper(maestro/sub-agent) 응답을 watcher 로 캡처하지 않습니다.
helper 가 직접 다음 script 를 호출해 채널에 push 합니다.

```bash
# 사용
~/.mobruji/discord-reply.sh "응답 메시지 본문"
```

### 배포 위치

- 워크트리 소스: `tools/discord-daemon/discord-reply.sh`
- 운영 진입점: `~/.mobruji/discord-reply.sh` (심볼릭 링크 권장)

심볼릭 링크 예:

```bash
mkdir -p ~/.mobruji
ln -sf "$HOME/mobruji/tools/discord-daemon/discord-reply.sh" ~/.mobruji/discord-reply.sh
```

`.env` 는 `bot.py` 와 동일 파일을 공유합니다 (`DISCORD_BOT_TOKEN`,
`NOTIFY_CHANNEL_ID` / `MOBRUJI_CHANNEL_ID`). `DISCORD_DAEMON_ENV_PATH` 환경
변수로 override 가능합니다.

종속: `curl`, `jq`, `grep`, `cut` (bot 호스트에 기본 설치).

## 사전 준비 (공통)

1. Discord Developer Portal → New Application → Bot 생성 → **Privileged Gateway Intents → MESSAGE CONTENT INTENT 활성화**
2. OAuth2 → URL Generator: scope=`bot`, permissions=`Read Messages/View Channels`, `Send Messages` 정도면 충분.
3. mobruji 채널 id 확인 (현재: `1506925497651560458`). 본인 Discord user id 확인.

---

## Linux/GCP/NCP systemd

Ubuntu VM 등 Linux 호스트 가동 절차. Discord WebSocket(443) outbound 만
쓰므로 기본 firewall 설정 그대로 동작합니다 (inbound 포트 개방 불필요).

### 한 줄 셋업

VM SSH 안에서:

```bash
cd ~ && git clone https://github.com/goohong/mobruji.git \
  && cd mobruji/tools/discord-daemon && bash setup-gcp-systemd.sh
```

1차 실행은 venv 설치 + `.env` 템플릿 복사 후 종료합니다. 안내 따라
`.env` 토큰을 채운 뒤 다시 실행하면 systemd 등록 + 즉시 기동까지 진행됩니다.

```bash
nano ~/mobruji/tools/discord-daemon/.env
# DISCORD_BOT_TOKEN 채우기
bash ~/mobruji/tools/discord-daemon/setup-gcp-systemd.sh
```

### 로그

```bash
# systemd 통합 로그
sudo journalctl -u mobruji-discord-daemon -f

# 파일 로그
sudo tail -F /var/log/mobruji-discord-daemon.out.log
sudo tail -F /var/log/mobruji-discord-daemon.err.log
```

### 운영 명령

```bash
sudo systemctl status mobruji-discord-daemon
sudo systemctl restart mobruji-discord-daemon   # .env 변경 후
sudo systemctl stop mobruji-discord-daemon
sudo systemctl disable mobruji-discord-daemon   # 부팅 자동 시작 해제
```

### 단순화본 마이그레이션 (이슈 #807)

기존에 watcher / pipe-pane / context auto-clear 등을 켜고 운영하던 호스트는
다음 절차로 단순화본으로 전환합니다.

```bash
cd ~/mobruji && git pull

# .env 에서 폐기 env 제거 (또는 그대로 두어도 무시됩니다):
#   MAESTRO_RESPONSE_WATCHER_ENABLED, TMUX_PIPE_PANE_*,
#   CONTEXT_REFRESH_*, GITHUB_PAT, GITHUB_REPO,
#   TMUX_BRIDGE_ENABLED
# CONTEXT_AUTO_CLEAR_* 3건은 PR #810 (#809 후속) 에서 복구 — 삭제하지 말 것.
# 끄려면 CONTEXT_AUTO_CLEAR_ENABLED=0 (default) 유지.

# helper 응답 진입점 심볼릭 링크
mkdir -p ~/.mobruji
ln -sf "$HOME/mobruji/tools/discord-daemon/discord-reply.sh" ~/.mobruji/discord-reply.sh

sudo systemctl restart mobruji-discord-daemon
sudo systemctl restart mobruji-discord-bridge   # bridge unit 도 함께 restart
```

---

## macOS LaunchAgent

### 셋업 절차

```bash
cd tools/discord-daemon
bash setup-launchagent.sh
# 이후 .env 의 DISCORD_BOT_TOKEN 채우고
bash setup-launchagent.sh    # 다시 실행하면 자동 reload
```

`setup-launchagent.sh` 가 하는 일:

- `tools/discord-daemon/venv` 생성 + `pip install -r requirements.txt`
- `.env` 가 없으면 `.env.example` 복사 (chmod 600)
- `com.mobruji.discord-daemon.plist` 토큰 치환 후 `~/Library/LaunchAgents/` 로 복사
- `launchctl bootstrap gui/$(id -u)` 로 등록 + `kickstart` 로 즉시 기동

### 로그 (macOS)

```bash
tail -F ~/Library/Logs/mobruji-discord-daemon.out.log \
       ~/Library/Logs/mobruji-discord-daemon.err.log
```

### 절전 / sleep 가이드 (macOS)

```bash
# A. 시스템 sleep 영구 차단 (권장)
sudo pmset -a disablesleep 1

# B. 임시 (터미널 세션 살아있는 동안만)
caffeinate -dimsu &

# 되돌리기
sudo pmset -a disablesleep 0
```

---

## 동작 확인 (공통)

1. Discord 모바일/데스크탑에서 mobruji 채널에 메시지 입력
2. 로그에 `메시지 수신` + `tmux send-keys` 라인 확인
3. helper pane 에 사용자 메시지가 들어가 있는지 확인
4. helper 가 `~/.mobruji/discord-reply.sh "<응답>"` 호출 시 채널에 응답 push 되는지 확인

## Troubleshooting

| 증상 | 원인 / 조치 |
|---|---|
| `Privileged intent` 오류 | Developer Portal 에서 MESSAGE CONTENT INTENT 활성화 |
| `401 Unauthorized` (Discord) | 봇 토큰 재발급 후 `.env` 갱신 + 재기동 |
| 메시지가 무시됨 | `MOBRUJI_CHANNEL_ID` 또는 `ALLOWED_USER_IDS` 불일치. 로그에 `허용되지 않은 사용자` 또는 채널 미스매치 라인 확인 |
| helper pane 에 메시지가 안 들어감 | tmux 세션 부재 — `tmux ls` 확인. `CLAUDE_BIN` 절대경로 확인 |
| `discord-reply.sh` 실패 | `.env` 경로 확인 (`DISCORD_DAEMON_ENV_PATH`). `jq` / `curl` 설치 확인 |
| 데몬이 자꾸 죽음 | macOS: `ThrottleInterval=30`. Linux: `RestartSec=10`. `*.err.log` traceback 확인 |
| (Linux) `Active: failed` | `sudo journalctl -u mobruji-discord-daemon -n 100` 로 traceback 확인 |

## 운영자 체크리스트

- [ ] `.env` 가 `git status` 에 안 잡힌다 (.gitignore 의 `.env` 룰)
- [ ] `discord-reply.sh` 가 `~/.mobruji/discord-reply.sh` 로 링크/배포되어 있다
- [ ] 로그 파일 사이즈 점검
  - macOS: `~/Library/Logs/mobruji-discord-daemon.err.log`
  - Linux: `/var/log/mobruji-discord-daemon.err.log` + `journalctl --vacuum-size`

## 관련

- spec: `docs/features/discord-daemon-hosting.md`
- 단순화 이슈: #807
- bridge unit: `tools/discord-daemon/mobruji-discord-bridge.service`
