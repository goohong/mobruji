# mobruji discord-daemon

`docs/features/discord-daemon-hosting.md` §3 구현체. Discord Gateway 에
24/7 붙어, 지정 채널의 화이트리스트 사용자 메시지를 GitHub
`repository_dispatch` 로 전달한다.

호스트 옵션 2가지를 같은 `bot.py` 로 공유한다.

- **옵션 A — macOS LaunchAgent**: 사용자 Mac 이 켜져 있는 동안 가동. 아래 "macOS LaunchAgent" 절.
- **옵션 B — Linux/GCP systemd**: GCP Always Free e2-micro 등 Ubuntu VM. 아래 "Linux/GCP systemd" 절.

## 구성 파일

| 파일 | 역할 |
|---|---|
| `bot.py` | discord.py 기반 Gateway 데몬 (공통) |
| `requirements.txt` | discord.py / python-dotenv / requests |
| `.env.example` | 환경변수 템플릿 (실제 값은 `.env` 에 채움) |
| `com.mobruji.discord-daemon.plist` | macOS LaunchAgent 템플릿 (sed 치환 대상) |
| `setup-launchagent.sh` | macOS: venv·.env·plist·launchctl 한 번에 처리 |
| `mobruji-discord-daemon.service` | Linux systemd unit 템플릿 |
| `setup-gcp-systemd.sh` | Linux/GCP: venv·.env·systemd unit 한 번에 처리 |

## 환경 변수

`.env.example` 을 `.env` 로 복사한 뒤 채운다. 새 변수가 늘어날 때마다 이 표와 `.env.example` 을 같이 갱신한다.

### 필수

| env | 기본값 | 설명 |
|---|---|---|
| `DISCORD_BOT_TOKEN` | (없음) | Developer Portal → Bot → Reset Token. MESSAGE CONTENT INTENT 활성 필수. |
| `ALLOWED_USER_IDS` | (없음) | 명령을 받아들일 Discord user id CSV. 정수 비교. 본인만 두는 것을 권장. |
| `MOBRUJI_CHANNEL_ID` | (없음) | 메인 채널 id. reply/decision/alert(P1+) 송신 대상. 그 외 채널 메시지는 무시. |
| `GITHUB_PAT` | (없음) | `repository_dispatch` 호출용 classic PAT. 최소 권한 `repo`. |
| `GITHUB_REPO` | (없음) | dispatch 대상 repo (`owner/name`). |

### 채널 분리

| env | 기본값 | 설명 |
|---|---|---|
| `NOTIFY_CHANNEL_ID` | (미설정 → `MOBRUJI_CHANNEL_ID` fallback) | cycle-start/cycle-end/digest/alert/recovery 카테고리 송신 대상. 알림 채널을 분리하려면 설정. spec: `docs/features/discord-message-style.md §5-2`. |

운영 패턴: 메인 채널은 사용자 reply/결정만, 알림 채널은 5분 digest + cycle 이벤트 + 자동 recovery 통지. 두 값을 같게 두면 모든 카테고리가 한 채널에 모인다 (기존 동작 호환).

### Phase 3 tmux bridge (옵션)

| env | 기본값 | 설명 |
|---|---|---|
| `TMUX_BRIDGE_ENABLED` | `0` | `1` 이면 Discord → tmux `send-keys` 분기 활성, `0` 이면 GitHub `repository_dispatch` 만 수행. |
| `TMUX_SESSION_NAME` | `mobruji` | maestro tmux 세션 이름. 없으면 bot 이 `CLAUDE_BIN` 으로 재생성. |
| `TMUX_TARGET_PANE` | `mobruji:0.0` | `send-keys` 타깃 pane. |
| `CLAUDE_BIN` | `claude` | tmux 세션 부재 시 띄울 실행 파일. 절대경로 권장. |
| `DEDUP_LEDGER_PATH` | `~/.mobruji/discord-bridge.sqlite` | dedup ledger SQLite 경로. 24h TTL GC. |

### tmux pane 캡처 (디버깅 / context auto-clear / maestro watcher 의존)

| env | 기본값 | 설명 |
|---|---|---|
| `TMUX_PIPE_PANE_ENABLED` | `0` | `1` 이면 부팅 시 tmux `pipe-pane` 자동 설정 → pane stdout 을 파일로 캡처. context auto-clear loop / maestro response watcher loop 의 전제. |
| `TMUX_PIPE_PANE_PATH` | `~/.mobruji/tmux-pane.log` | 캡처 파일 경로. |
| `TMUX_PIPE_PANE_MAX_BYTES` | `104857600` (100MB) | 회전 임계치. 초과 시 별도 thread 가 truncate. |

### 5분 cron digest (옵션, ABC 가시성 패턴 C)

| env | 기본값 | 설명 |
|---|---|---|
| `DIGEST_ENABLED` | `1` | `1` 이면 on_ready 직후 asyncio task 가 일정 주기로 multi-line digest 1건 push. 노이즈가 크면 `0` 으로 두고 `/status` 슬래시로만 운영. |

### context auto-clear loop (옵션, 이슈 #773)

| env | 기본값 | 설명 |
|---|---|---|
| `CONTEXT_AUTO_CLEAR_ENABLED` | `0` | `1` 이면 5초 간격 pipe-pane log tail → maestro 가 emit 한 `===CTX:NN%===` 마커 추적. trigger pct 도달 시 정리 prompt inject → `===CLEAR_READY===` 감지 시 `/clear` 송신. **전제: `TMUX_BRIDGE_ENABLED=1` + `TMUX_PIPE_PANE_ENABLED=1` + CLAUDE.md §11 룰 적용 maestro.** 1주 dry-run 후 활성 권장. |
| `CONTEXT_CLEAR_TRIGGER_PCT` | `95` | inject 발동 임계치. |
| `CONTEXT_CLEAR_HYSTERESIS_PCT` | `80` | trigger 후 이 값 아래로 떨어졌다 다시 올라와야 재발동 (chattering 방지). |

## 백그라운드 loop / watcher (bot.py)

`on_ready` 시점에 아래 asyncio task 가 조건부로 띄워진다. 운영자가 비활성화하려면 위 env 토글로 끈다.

- **`digest_loop`** (PR #755 디지털 분리) — `DIGEST_ENABLED=1` 일 때 가동. 일정 주기로 PR open / 머지 24h / 신규 bug 등 multi-line 상태 1건을 `NOTIFY_CHANNEL_ID` (없으면 `MOBRUJI_CHANNEL_ID`) 로 push. heartbeat 간격은 cron digest 옆에 별도 1시간 ping.
- **`maestro_response_watcher_loop`** (PR #735 + #761 chunk 재시도) — `MAESTRO_RESPONSE_WATCHER_ENABLED=1` + pipe-pane 캡처 활성일 때 가동. tmux pane log 를 1초 간격 tail → idle 30초 도달 시 누적 buffer 를 한 응답 chunk 로 push. sha256 dedup ledger 로 중복 방지, transient send 실패 시 최대 `MAX_RETRIES=3` 재시도 (`pending_candidate` 보존).
- **`context_auto_clear_loop`** (PR #776) — `CONTEXT_AUTO_CLEAR_ENABLED=1` 일 때 가동. 위 env 표 참조.

## 보안

### 시크릿 마스킹 (PR #742 + #751 + #771)

Discord 로 송신되는 모든 chunk (maestro response watcher 경유) 는 송신 직전 `SECRET_MASK_PATTERNS` 순서로 치환된다. 패턴 추가 시 `bot.py` 의 `SECRET_MASK_PATTERNS` 튜플을 갱신하고 `tests/` 에 회귀 케이스 추가.

기본 커버:

- GitHub fine-grained PAT (`github_pat_…`)
- GitHub classic PAT (`ghp_…`)
- Discord bot token (`[MN]…\.…\.…` 3-segment 패턴)
- env-style 시크릿 (`KEY=value` 형태, 범위 좁힘 — PR #771)

### Mention sanitize (PR #767)

PR 제목 / maestro pane chunk 에 `@everyone` / `@here` / `<@USER_ID>` / `<@&ROLE_ID>` 가 echo 되면 Discord 가 실제 알림으로 해석해 폭주가 발생한다. `sanitize_mentions()` 가 송신 직전 zero-width space 를 삽입해 trigger 를 무력화한다.

### chunk 재시도 (PR #761)

maestro response watcher 가 Discord API 일시 오류 (`HTTPException` / 네트워크) 만났을 때 `MAX_RETRIES=3` 까지 backoff 없이 재시도. 마지막까지 실패하면 `pending_candidate` 에 보존해 다음 idle flush 시점에 재시도 — chunk 유실 방지.

## 사전 준비 (공통)

1. Discord Developer Portal → New Application → Bot 생성 → **Privileged Gateway Intents → MESSAGE CONTENT INTENT 활성화**
2. OAuth2 → URL Generator: scope=`bot`, permissions=`Read Messages/View Channels`, `Send Messages` 정도면 충분. 생성된 URL 로 봇을 mobruji 서버에 초대.
3. GitHub PAT 발급 (Classic 권장). 스코프: **`repo` (private repo 라면 필수)**. `repository_dispatch` 호출에는 `repo` 가 필요하다.
4. mobruji 채널 id 확인 (현재: `1506925497651560458`). 본인 Discord user id 확인.

---

## macOS LaunchAgent

### 셋업 절차

```bash
cd tools/discord-daemon
bash setup-launchagent.sh
# 이후 .env 의 DISCORD_BOT_TOKEN / GITHUB_PAT 채우고
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

로컬 디버깅 시 inbox 백업:

```bash
tail -F tools/discord-daemon/inbox.jsonl
```

### 절전 / sleep 가이드 (macOS)

LaunchAgent 는 사용자 세션이 sleep 들면 함께 멈춘다. 24/7 유지하려면
다음 중 하나가 필요하다.

```bash
# A. 시스템 sleep 영구 차단 (디스플레이는 꺼짐, 권장)
sudo pmset -a disablesleep 1

# B. 임시 (터미널 세션 살아있는 동안만)
caffeinate -dimsu &

# 되돌리기
sudo pmset -a disablesleep 0
```

전원 어댑터 연결 상태에서만 sleep 을 차단하고 싶으면 `pmset -c` 로
프로필 분리.

### 중지 / 제거 (macOS)

```bash
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.mobruji.discord-daemon.plist
rm ~/Library/LaunchAgents/com.mobruji.discord-daemon.plist
rm -rf tools/discord-daemon/venv tools/discord-daemon/.env
```

---

## Linux/GCP systemd

GCP Always Free e2-micro (Ubuntu) 등 Linux 호스트 가동 절차.
Discord WebSocket(443) + GitHub API(443) outbound 만 쓰므로 기본
firewall 설정 그대로 동작한다 (inbound 포트 개방 불필요).

### 1. GCP VM 준비

1. GCP Console → Compute Engine → "VM 인스턴스 만들기"
2. 머신 유형: `e2-micro` (Always Free), 리전: `us-central1` / `us-east1` / `us-west1` 중 하나
3. 부팅 디스크: Ubuntu 22.04 LTS (또는 24.04 LTS), 표준 영구 디스크 30GB 이하
4. 방화벽: HTTP/HTTPS 트래픽 허용 체크 안 해도 됨 (outbound 만 사용)
5. 생성 후 SSH 버튼으로 접속 (브라우저 SSH 또는 `gcloud compute ssh`)

### 2. 한 줄 셋업

VM SSH 안에서:

```bash
cd ~ && git clone https://github.com/goohong/mobruji.git \
  && cd mobruji/tools/discord-daemon && bash setup-gcp-systemd.sh
```

1차 실행은 venv 설치 + `.env` 템플릿 복사 후 종료한다. 안내 따라
`.env` 토큰을 채운 뒤 다시 실행.

```bash
nano ~/mobruji/tools/discord-daemon/.env
# DISCORD_BOT_TOKEN, GITHUB_PAT 채우기
bash ~/mobruji/tools/discord-daemon/setup-gcp-systemd.sh
```

`setup-gcp-systemd.sh` 2차 실행이 하는 일:

- `apt install python3-venv git` (idempotent)
- `tools/discord-daemon/venv` 생성 + `pip install -r requirements.txt`
- `/var/log/mobruji-discord-daemon.{out,err}.log` 준비 (chown ubuntu)
- `mobruji-discord-daemon.service` 를 `/etc/systemd/system/` 로 복사
- `systemctl daemon-reload && enable && restart` 로 즉시 기동
- `systemctl status` 출력으로 정상 기동 확인

### 3. 로그 (Linux)

```bash
# systemd 통합 로그 (가장 편함)
sudo journalctl -u mobruji-discord-daemon -f

# 파일 로그 (service unit 의 StandardOutput/Error append 대상)
sudo tail -F /var/log/mobruji-discord-daemon.out.log
sudo tail -F /var/log/mobruji-discord-daemon.err.log
```

### 4. 운영 명령

```bash
sudo systemctl status mobruji-discord-daemon
sudo systemctl restart mobruji-discord-daemon   # .env 변경 후
sudo systemctl stop mobruji-discord-daemon
sudo systemctl disable mobruji-discord-daemon   # 부팅 자동 시작 해제
```

### 5. 업데이트 (코드 갱신 시)

```bash
cd ~/mobruji && git pull
sudo systemctl restart mobruji-discord-daemon
```

`requirements.txt` 가 변경됐다면 venv 재설치:

```bash
cd ~/mobruji/tools/discord-daemon
source venv/bin/activate
pip install -r requirements.txt
deactivate
sudo systemctl restart mobruji-discord-daemon
```

### 6. 중지 / 제거 (Linux)

```bash
sudo systemctl stop mobruji-discord-daemon
sudo systemctl disable mobruji-discord-daemon
sudo rm /etc/systemd/system/mobruji-discord-daemon.service
sudo systemctl daemon-reload
rm -rf ~/mobruji/tools/discord-daemon/venv ~/mobruji/tools/discord-daemon/.env
```

### 7. GCP firewall 메모

- inbound 포트 개방 불필요. mobruji daemon 은 outbound 만 사용한다.
  - Discord Gateway: `gateway.discord.gg` 443/tcp (WebSocket Secure)
  - GitHub API: `api.github.com` 443/tcp
- GCP 기본 VPC firewall 은 모든 egress 허용이므로 추가 룰 불필요.
- 사내 firewall 환경이면 위 두 호스트 443/tcp egress 만 열어주면 된다.

---

## 동작 확인 (공통)

1. Discord 모바일/데스크탑에서 mobruji 채널에 메시지 입력
2. 로그에 `메시지 수신` + `repository_dispatch 성공` 라인 확인
3. GitHub Actions 의 `discord-dispatch-handler` workflow run 확인

## Troubleshooting

| 증상 | 원인 / 조치 |
|---|---|
| `Privileged intent` 오류 | Developer Portal 에서 MESSAGE CONTENT INTENT 활성화 |
| `401 Unauthorized` (Discord) | 봇 토큰 재발급 후 `.env` 갱신 + 재기동 (macOS: `setup-launchagent.sh` 재실행 / Linux: `sudo systemctl restart mobruji-discord-daemon`) |
| `403` (repository_dispatch) | PAT 스코프 부족 (private repo 면 `repo` 필요) 또는 토큰 만료 |
| `404` (repository_dispatch) | `GITHUB_REPO` 값 오타 또는 repo 접근 권한 없음 |
| 메시지가 무시됨 | `MOBRUJI_CHANNEL_ID` 또는 `ALLOWED_USER_IDS` 불일치. 로그에 `허용되지 않은 사용자` 또는 채널 미스매치 라인 확인 |
| 데몬이 자꾸 죽음 | macOS: `ThrottleInterval=30` 으로 30초 간격 재시작. Linux: `RestartSec=10`. 각 `*.err.log` 의 traceback 확인 |
| 채널 id 가 바뀜 | `.env` 의 `MOBRUJI_CHANNEL_ID` 수정 → 재기동 (macOS: `setup-launchagent.sh` 재실행 / Linux: `sudo systemctl restart mobruji-discord-daemon`) |
| (Linux) `Active: failed` | `sudo journalctl -u mobruji-discord-daemon -n 100` 로 traceback 확인. `.env` 누락 / 경로 오타가 흔함 |
| (Linux) `Permission denied` 로그 파일 | `/var/log/mobruji-discord-daemon.*.log` 가 root 소유. `setup-gcp-systemd.sh` 가 chown 처리하지만 수동으로 만든 경우 `sudo chown ubuntu:ubuntu` 필요 |

## 운영자 체크리스트

- [ ] `.env` 가 `git status` 에 안 잡힌다 (.gitignore 의 `.env` 룰)
- [ ] PAT 은 별도 토큰 분리 (Q2 결정). 만료일 캘린더 등록.
- [ ] 로그 파일 사이즈 점검 (rotate 필요 시 macOS 는 별도 launchd, Linux 는 `logrotate` 설정 추가)
  - macOS: `~/Library/Logs/mobruji-discord-daemon.err.log`
  - Linux: `/var/log/mobruji-discord-daemon.err.log` + `journalctl --vacuum-size`

## 관련

- spec: `docs/features/discord-daemon-hosting.md`
- 워크플로우: `.github/workflows/discord-dispatch-handler.yml`
- 후속 옵션 C (Cloudflare Workers): spec §6 PR 3
