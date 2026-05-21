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
