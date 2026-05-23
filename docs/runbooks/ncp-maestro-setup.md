# NCP maestro 셋업 런북

> mobruji **maestro(Claude Code interactive)** 를 NCP c2-g3a VM(공인 IP `101.79.20.94`, Ubuntu 24.04)에 셋업하는 절차.
>
> 본 런북은 [`docs/decisions/0015-hosting-stack.md`](../decisions/0015-hosting-stack.md) 결정의 구현 가이드이며, [`docs/features/discord-driven-mobruji.md`](../features/discord-driven-mobruji.md) Phase 1~2의 인프라 토대를 마련한다.
>
> Phase 1(§B) 셋업 후 사용자는 `tmux attach -t mobruji`로 maestro console에 직접 들어가 수동 검증한다. Phase 2(§C) 진입 시 systemd unit으로 자동 복구, Phase 3(§D) 진입 시 Discord bridge로 외부 양방향.

## A) 사전 준비 (로컬, 사용자 작업)

### A-1) 접근 정보 확인
- **공인 IP**: `101.79.20.94`
- **SSH 키**: 사용자 보유. 경로 예시 `~/workspace/secret/<keyname>` (사용자 환경 기준). 권한 600 확인:
  ```bash
  ls -l ~/workspace/secret/<keyname>
  # -rw------- 가 아니면 chmod 600 ~/workspace/secret/<keyname>
  ```
- **OS**: Ubuntu 24.04, **사양**: c2-g3a (2vCPU/4GB/10GB SSD)

### A-2) Anthropic API key 발급
1. https://console.anthropic.com 로그인.
2. **API Keys** → **Create Key**.
3. 라벨: `mobruji-maestro` (회수/회전 시 식별 편의).
4. 키 문자열은 1회만 표시됨 — 1Password / Bitwarden / Apple Keychain 등 비밀번호 매니저에 저장. 셋업 직후 `~/.bashrc`에 1회 등록 후 콘솔 history는 삭제.
5. **결제수단 등록 확인**: Anthropic Console → **Billing**. 무료 한도 초과 시 자동 청구. 사용량 알람(`Usage Alerts`) 임계치 설정 권장(예: 월 $20).

### A-3) Discord bot 토큰
- **신규 발급 또는 기존 재사용**:
  - 기존: `tools/discord-daemon/.env`의 `DISCORD_BOT_TOKEN` (이미 사용자 워크플로우에 통합된 봇). NCP maestro와 같은 토큰을 재사용하면 로컬 daemon은 비활성화해야 함(같은 토큰 2개 프로세스가 같은 채널을 listen하면 중복 처리).
  - 신규: https://discord.com/developers/applications → New Application → Bot → Reset Token. 권한: `Send Messages`, `Read Message History`, `Use Slash Commands`. OAuth2 URL Generator로 `#모부르지` 채널이 있는 서버에 초대.
- 본 런북은 **기존 토큰 재사용 + 로컬 daemon 종료** 가정. 신규 토큰 발급 시 [`feature-discord-driven-mobruji`](../features/discord-driven-mobruji.md) §5-4 환경변수 갱신 필요.

### A-4) GitHub PAT
- **신규 발급 또는 기존 재사용**:
  - 기존: `tools/discord-daemon/.env`의 `GITHUB_PAT` (repository_dispatch 권한 보유). 재사용 가능.
  - 신규: https://github.com/settings/tokens → Generate new token (classic). 권한: `repo`(전체), `workflow`(repository_dispatch). 만료 90일 권장.
- maestro의 `gh CLI`도 같은 PAT 사용 가능(`gh auth login --with-token`).

## B) Phase 1 — 1회 셋업 (사용자 실행)

> 아래 명령은 **순서대로** 실행. 각 단계는 root 또는 mobruji 유저 컨텍스트가 명시되어 있다.

### B-0) SSH 접근 확인 (이미 완료)
```bash
ssh -i ~/workspace/secret/<keyname> root@101.79.20.94
# 프롬프트 root@<hostname>:~# 가 뜨면 OK
```

### B-1) 시스템 업데이트 + 기본 도구 (root)
```bash
apt update && apt upgrade -y
apt install -y curl git tmux build-essential ca-certificates gnupg python3-venv
```

### B-2) Node.js 22 LTS 설치 (root)
Claude Code CLI는 Node 18+ 필요. 22 LTS로 통일.
```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt install -y nodejs
node -v   # v22.x.x
npm -v    # 10.x.x
```

### B-3) GitHub CLI 설치 (root)
```bash
curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
  | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
  | tee /etc/apt/sources.list.d/github-cli.list > /dev/null
apt update && apt install -y gh
gh --version
```

### B-4) 작업 유저 `mobruji` 생성 (root)
일상 작업은 root 금지(보안 + 실수 방지). sudo 부여로 셋업/유지보수만 root 권한 사용.
```bash
adduser --disabled-password --gecos "" mobruji
usermod -aG sudo mobruji
mkdir -p /home/mobruji/.ssh
cp ~/.ssh/authorized_keys /home/mobruji/.ssh/
chown -R mobruji:mobruji /home/mobruji/.ssh
chmod 700 /home/mobruji/.ssh
chmod 600 /home/mobruji/.ssh/authorized_keys
```

별 터미널에서 `ssh -i ~/workspace/secret/<keyname> mobruji@101.79.20.94` 로 접속 확인.

### B-5) Swap 1GB 활성화 (root)
4GB RAM + maestro + sub-agent 동시 spike 대비. ADR-0015 §Consequences 참조.
```bash
fallocate -l 1G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
free -h   # Swap: 1.0Gi 확인
```

### B-6) 이후 작업은 `mobruji` 유저로
```bash
su - mobruji
cd ~
```

### B-7) Claude Code CLI 설치 (mobruji)
npm global 설치(sudo 없이 가능하도록 prefix 설정).
```bash
mkdir -p ~/.npm-global
npm config set prefix '~/.npm-global'
echo 'export PATH=~/.npm-global/bin:$PATH' >> ~/.bashrc
source ~/.bashrc

npm install -g @anthropic-ai/claude-code
claude --version
```

### B-8) Anthropic API key 등록 (mobruji)
**옵션 B(권장) — 환경변수**. 옵션 A(`claude login` OAuth)는 headless 부적합.
```bash
# 1) ~/.bashrc 에 등록 (셸 로그인마다 자동 export)
cat >> ~/.bashrc <<'EOF'
export ANTHROPIC_API_KEY="<여기에 §A-2에서 발급받은 키>"
EOF
chmod 600 ~/.bashrc   # 다른 유저 읽기 차단

source ~/.bashrc
echo "$ANTHROPIC_API_KEY" | head -c 12   # sk-ant-... 확인 (12자만)
```

**대안 (더 안전, Phase 2 권장)**: `/etc/mobruji/maestro.env` (root:mobruji, 0640)에 두고 systemd unit `EnvironmentFile=` 로 주입. Phase 1은 `~/.bashrc` 로 단순화.

### B-9) git / gh 인증 (mobruji)
```bash
gh auth login
# 선택: GitHub.com → HTTPS → Yes (Git operations 인증) → PAT 붙여넣기 (§A-4)
# 또는 web 브라우저 OAuth (NCP VM에서 URL 받아 로컬 브라우저로 인증)

git config --global user.name "goohong"
git config --global user.email "ppiyaki0304@gmail.com"
git config --global init.defaultBranch develop
git config --global pull.rebase false
```

### B-10) mobruji repo clone (mobruji)
```bash
cd ~
git clone https://github.com/goohong/mobruji.git
cd mobruji
git checkout develop
git pull
```

### B-11) 워크트리 4개 생성 (mobruji)
기존 [멀티 세션 런북](../ai-harness/11-multi-session-runbook.md) 답습. maestro는 `~/mobruji`에서 가동, sub-agent는 워크트리에서.
```bash
cd ~/mobruji
git worktree add ../mobruji-be develop
git worktree add ../mobruji-fe develop
git worktree add ../mobruji-rev develop
git worktree add ../mobruji-plan develop

ls ~/
# mobruji  mobruji-be  mobruji-fe  mobruji-plan  mobruji-rev
```

### B-12) Discord daemon `.env` 셋업 (mobruji)
```bash
cd ~/mobruji/tools/discord-daemon
cp .env.example .env
chmod 600 .env

# 편집기로 .env 열어 다음 채우기:
#   DISCORD_BOT_TOKEN=<§A-3 토큰>
#   ALLOWED_USER_IDS=<사용자 Discord ID, 콤마 구분>
#   MOBRUJI_CHANNEL_ID=1506925497651560458   # #모부르지 채널
#   GITHUB_PAT=<§A-4 PAT>
#   GITHUB_REPO=goohong/mobruji
#   # 신규 (discord-driven-mobruji spec §5-4):
#   TMUX_BRIDGE_ENABLED=0   # Phase 1은 0(수동 검증), Phase 3에서 1로 켬
#   TMUX_SESSION_NAME=mobruji
nano .env
```

### B-13) Discord bot Python venv (mobruji)
```bash
cd ~/mobruji/tools/discord-daemon
python3 -m venv venv
source venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
deactivate
```

### B-14) tmux 세션 + Claude Code 수동 부팅 (mobruji, Phase 1 검증)
```bash
cd ~/mobruji
tmux new-session -d -s mobruji
tmux send-keys -t mobruji "cd ~/mobruji && claude" Enter

# attach 해서 maestro console 확인
tmux attach -t mobruji
# (claude TUI가 떠야 함. 'Hello'라고 입력해서 응답 확인.)
# detach: Ctrl+B → D
```

이 시점에서 maestro는 가동 중. SSH 연결을 끊어도 tmux 세션은 살아있다(`tmux ls`로 확인).

### B-15) 동작 검증 체크리스트
- [ ] `tmux ls` → `mobruji: 1 windows ... (attached/detached)` 표시
- [ ] `tmux attach -t mobruji` → claude TUI 정상 응답
- [ ] `gh pr list --repo goohong/mobruji` → repo 조회 성공
- [ ] `git -C ~/mobruji-be status` → 워크트리 정상
- [ ] `free -h` → Swap 1.0Gi available
- [ ] `df -h /` → 여유 ≥ 5GB

## C) Phase 2 — systemd 자동 시작

> 사용자 개입 없이 재부팅 후 maestro 자동 복구.

### C-1) systemd unit 파일 생성 (root)
**참고**: `ANTHROPIC_API_KEY`를 unit 파일에 평문으로 두는 대신 `EnvironmentFile=`로 분리.

```bash
# 시크릿 파일
sudo mkdir -p /etc/mobruji
sudo tee /etc/mobruji/maestro.env > /dev/null <<'EOF'
ANTHROPIC_API_KEY=<여기에 키>
EOF
sudo chown root:mobruji /etc/mobruji/maestro.env
sudo chmod 640 /etc/mobruji/maestro.env
```

```bash
# systemd unit
sudo tee /etc/systemd/system/mobruji-maestro.service > /dev/null <<'EOF'
[Unit]
Description=Mobruji maestro (Claude Code in tmux)
After=network-online.target
Wants=network-online.target

[Service]
Type=forking
User=mobruji
Group=mobruji
WorkingDirectory=/home/mobruji/mobruji
EnvironmentFile=/etc/mobruji/maestro.env
ExecStart=/usr/bin/tmux new-session -d -s mobruji -c /home/mobruji/mobruji '/home/mobruji/.npm-global/bin/claude'
ExecStop=/usr/bin/tmux kill-session -t mobruji
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now mobruji-maestro
sudo systemctl status mobruji-maestro
```

### C-2) 재부팅 검증
```bash
sudo reboot
# 약 30초 대기 후 재접속
ssh -i ~/workspace/secret/<keyname> mobruji@101.79.20.94
tmux ls
# mobruji: 1 windows ... 자동 부팅 확인
```

### C-3) 기존 수동 tmux 세션 제거
Phase 1에서 띄운 세션이 systemd unit과 충돌 가능. systemd가 띄운 세션이 살아있으면 수동 세션은 kill.
```bash
# systemd가 띄운 세션만 남기기
tmux ls
# 기존 수동 세션이 따로 있으면 kill (현재 세션 이름이 동일하면 systemd가 부팅 시 실패하므로 unit log 확인)
sudo journalctl -u mobruji-maestro -n 50
```

## D) Phase 3 — Discord bridge

> 사용자 외출 중 Discord 메시지로 maestro 양방향 소통. [`discord-driven-mobruji` spec](../features/discord-driven-mobruji.md) PR B/C 머지 후 진입.

### D-1) `.env` flag 활성화 (mobruji)
```bash
cd ~/mobruji/tools/discord-daemon
nano .env
# TMUX_BRIDGE_ENABLED=1 로 변경
```

### D-2) Discord bridge systemd unit (root)
```bash
sudo tee /etc/systemd/system/mobruji-discord-bridge.service > /dev/null <<'EOF'
[Unit]
Description=Mobruji Discord Bridge (Discord → tmux send-keys)
After=network-online.target mobruji-maestro.service
Wants=network-online.target
Requires=mobruji-maestro.service

[Service]
Type=simple
User=mobruji
Group=mobruji
WorkingDirectory=/home/mobruji/mobruji/tools/discord-daemon
EnvironmentFile=/home/mobruji/mobruji/tools/discord-daemon/.env
ExecStart=/home/mobruji/mobruji/tools/discord-daemon/venv/bin/python bot.py
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now mobruji-discord-bridge
sudo systemctl status mobruji-discord-bridge
```

### D-3) Discord → maestro 검증
1. iPhone Discord 앱에서 `#모부르지` 채널에 "테스트" 입력.
2. NCP VM `journalctl -u mobruji-discord-bridge -f` 에서 메시지 수신 + `tmux send-keys` 로그 확인.
3. `tmux attach -t mobruji` 로 maestro console에 "테스트" 입력이 도착했는지 확인.
4. maestro 응답이 `plugin_discord__reply` 로 Discord 채널에 도착하는지 확인.

## E) 운영 명령

| 작업 | 명령 |
|---|---|
| maestro console 보기 | `tmux attach -t mobruji` (detach: `Ctrl+B → D`) |
| maestro 재시작 | `sudo systemctl restart mobruji-maestro` |
| maestro 로그 | `sudo journalctl -u mobruji-maestro -f` |
| Discord bridge 재시작 | `sudo systemctl restart mobruji-discord-bridge` |
| Discord bridge 로그 | `sudo journalctl -u mobruji-discord-bridge -f` |
| 시스템 리소스 | `htop` 또는 `free -h && df -h /` |
| API 사용량 | https://console.anthropic.com → Usage |
| maestro `/compact` 강제 (Discord) | `#모부르지` 채널에서 `/system:/compact` 입력 (Phase 3 활성화 후) |

## F) 트러블슈팅

### F-1) `claude` 시작 시 OAuth 시도 (API key 미인식)
**증상**: `claude` 실행 시 "Sign in with Anthropic" 프롬프트.

**원인**: `ANTHROPIC_API_KEY` env 미설정 또는 systemd unit이 EnvironmentFile을 못 읽음.

**확인**:
```bash
echo "$ANTHROPIC_API_KEY" | head -c 12   # sk-ant-... 확인
sudo systemctl show mobruji-maestro | grep Environment
sudo cat /etc/mobruji/maestro.env   # root 권한
```

**회피**: `/etc/mobruji/maestro.env` 권한(0640 root:mobruji) 확인, `systemctl daemon-reload && systemctl restart mobruji-maestro`.

### F-2) tmux session 없음
**증상**: `tmux attach -t mobruji` → `no server running on /tmp/tmux-1000/default`.

**원인**: systemd unit이 부팅 실패했거나 tmux server 자체가 죽음.

**확인**:
```bash
sudo systemctl status mobruji-maestro
sudo journalctl -u mobruji-maestro -n 100
```

**회피**:
```bash
sudo systemctl restart mobruji-maestro
sleep 5
tmux ls
```

### F-3) 메모리 부족 (OOM)
**증상**: `dmesg | tail` 에 `Out of memory: Killed process ... claude` 또는 maestro 응답 멈춤.

**원인**: maestro + sub-agent 동시 spike(최대 3개) + Discord bot + tmux + OS = 4GB 초과.

**확인**:
```bash
free -h
sudo dmesg | grep -i 'killed process'
```

**회피**:
1. swap 확인: `swapon --show`. 1GB 미만이면 §B-5 절차로 증설.
2. sub-agent 동시 수 제한(maestro 행동 정책): `CLAUDE.md` 또는 maestro system prompt에 "동시 sub-agent ≤ 3" 명시 강화.
3. 계속 부족하면 c2-g3a → c2-g3a 상위 사양(4vCPU/8GB) vertical scale. NCP 콘솔에서 서버 정지 → 사양 변경 → 시작 (다운타임 ≈ 2분).

### F-4) Discord bot 토큰 만료 / 무효
**증상**: bridge 로그에 `Improper token has been passed` 또는 `LoginFailure`.

**확인**:
```bash
sudo journalctl -u mobruji-discord-bridge -n 50
```

**회피**:
1. Discord Developer Portal에서 봇 토큰 재발급.
2. `~/mobruji/tools/discord-daemon/.env` 의 `DISCORD_BOT_TOKEN` 갱신.
3. `sudo systemctl restart mobruji-discord-bridge`.

### F-5) GitHub PAT 만료
**증상**: `gh pr list` → `HTTP 401: Bad credentials`.

**회피**:
```bash
gh auth refresh   # 또는 gh auth login 재실행
# .env GITHUB_PAT 도 갱신 필요 (discord daemon repository_dispatch fallback용)
```

### F-6) Anthropic API quota 초과
**증상**: claude TUI에 `rate_limit_error` 또는 `insufficient_quota`.

**확인**: https://console.anthropic.com → Billing & Usage.

**회피**: 결제수단 등록 / 한도 증액 / maestro 사용 패턴 조정(자동 loop 빈도 ↓).

### F-7) NCP VM 자체 장애 / 접속 불가
**증상**: SSH timeout.

**확인**:
1. NCP 콘솔에서 VM 상태(running/stopped/error) 확인.
2. ACG(방화벽) inbound 22 허용 확인.
3. 공인 IP 변경 여부(`101.79.20.94` 유지인지).

**회피**: NCP 콘솔에서 VM restart. 데이터는 SSD 볼륨에 보존됨.

## G) 보안

### G-1) SSH
- root 비밀번호 인증 비활성(이미 키 인증). `/etc/ssh/sshd_config` 의 `PasswordAuthentication no` 확인:
  ```bash
  sudo grep -E '^(PasswordAuthentication|PermitRootLogin)' /etc/ssh/sshd_config
  # PasswordAuthentication no
  # PermitRootLogin prohibit-password   (또는 no)
  ```
- 변경 시 `sudo systemctl restart ssh`.

### G-2) ACG (NCP 방화벽)
- **Inbound**: TCP 22 (SSH) 만. 사용자 공인 IP 화이트리스트 권장(NCP 콘솔 → ACG → Inbound Rules).
- **Outbound**: 전체 허용 (Discord WebSocket, GitHub API, Anthropic API, apt 업데이트).
- maestro VM에 HTTP/HTTPS inbound 노출 불필요(백/프론트는 별 VM).

### G-3) 시크릿 파일 권한
| 파일 | 소유자 | 권한 |
|---|---|---|
| `/etc/mobruji/maestro.env` | root:mobruji | 0640 |
| `~/.bashrc` (mobruji) | mobruji:mobruji | 0600 |
| `~/mobruji/tools/discord-daemon/.env` | mobruji:mobruji | 0600 |
| `~/.ssh/authorized_keys` | mobruji:mobruji | 0600 |

```bash
# 일괄 점검
ls -l /etc/mobruji/maestro.env ~/.bashrc ~/mobruji/tools/discord-daemon/.env ~/.ssh/authorized_keys
```

### G-4) API key 회전
- Anthropic API key는 6개월마다 회전 권장.
- 회전 절차: 신규 키 발급 → `/etc/mobruji/maestro.env` 갱신 → `sudo systemctl restart mobruji-maestro` → 구 키 폐기.
- Discord bot 토큰 / GitHub PAT도 동일 패턴.

### G-5) 로그 민감정보
- `journalctl -u mobruji-maestro` 에 maestro narration이 일부 노출될 수 있음. 사용자 음역대/기호 등 민감 데이터는 maestro 행동 정책(CLAUDE.md §4)에 따라 원문 노출 금지.
- 로그 보존 기간: systemd journal 기본(시스템 디스크 여유에 따라 자동 회전). 별도 영구 보관 불필요.

## H) Phase 4 — mobruji dev 배포 (docker 격리)

Phase 4 는 maestro VM 안에 mobruji backend + web + MySQL + nginx 를 docker container 로 격리해서 같이 올린다. spec: `docs/features/ncp-dev-deployment.md`.

### H-1) 부트스트랩 (mobruji 또는 root 1회)

```bash
cd ~/mobruji
sudo bash tools/deploy/ncp-bootstrap-dev.sh
```

스크립트가 멱등하게 수행:
- `docker.io + docker-compose-v2` 설치
- `mobruji` user 를 `docker` 그룹에 추가 (재로그인 1회 필요)
- swap 1GB 활성화 + `/etc/fstab` 등록 (이미 §B-5 에서 활성화돼 있으면 skip)
- `.env.dev` 가 없으면 `.env.dev.example` 복사 + `chmod 600`

### H-2) `.env.dev` 토큰 입력 (mobruji)

```bash
nano ~/mobruji/.env.dev
```

필수 (compose `?:` 표기로 부재 시 fail-fast):
- `MYSQL_ROOT_PASSWORD` — 영문/숫자 16자 이상 권장
- `MYSQL_PASSWORD` — 동일
- `MOBRUJI_ADMIN_TOKEN` — 32자 hex 권장 (`openssl rand -hex 16`)

선택 (default 가 있음):
- `MOBRUJI_CORS_ALLOWED_ORIGINS` — dev IP/도메인 (기본 `http://101.79.20.94`)
- `NEXT_PUBLIC_API_BASE_URL` — nginx 가 `/api` proxy 하므로 기본 `/api`

### H-3) 첫 가동 (mobruji)

```bash
cd ~/mobruji
docker compose -f docker-compose.dev.yml --env-file .env.dev up -d --build
docker compose -f docker-compose.dev.yml --env-file .env.dev ps
```

healthcheck 가 모두 `healthy` 되면:
- `curl http://localhost/_nginx_health` → `ok`
- `curl http://localhost/actuator/health/liveness` → `{"status":"UP"}`
- 브라우저: `http://101.79.20.94/`

### H-4) GitHub Actions CD (develop merge → 자동 배포)

develop 머지 시 NCP 자동 배포되도록 secret 3개 등록 (사용자 1회):

```bash
# 로컬에서 (gh CLI)
gh secret set NCP_SSH_HOST --repo goohong/mobruji <<< "101.79.20.94"
gh secret set NCP_SSH_USER --repo goohong/mobruji <<< "mobruji"
gh secret set NCP_SSH_KEY  --repo goohong/mobruji < ~/workspace/secret/mobruji-key.pem
```

이후 흐름 (`.github/workflows/cd-dev.yml`):
- develop push (paths 매치: `backend/**` / `web/**` / `docker-compose.dev.yml` / `nginx/**` / `tools/deploy/**`)
- SSH NCP → `git pull` → `docker compose build --build-arg GIT_SHA=<short>` → `up -d`
- `/actuator/health/liveness` 30회 × 5s polling (총 150s window)
- timeout 시 직전 SHA 로 자동 롤백 + 재빌드 + 재기동
- secret 부재 시 graceful skip

수동 트리거:
```bash
gh workflow run cd-dev.yml --repo goohong/mobruji
```

### H-5) 운영 명령 (NCP, mobruji)

```bash
# 상태
docker compose -f docker-compose.dev.yml --env-file .env.dev ps
docker stats --no-stream

# 로그
docker compose -f docker-compose.dev.yml --env-file .env.dev logs -f backend
docker compose -f docker-compose.dev.yml --env-file .env.dev logs -f web

# 단일 service 재기동 (코드 변경 없이)
docker compose -f docker-compose.dev.yml --env-file .env.dev restart backend

# 강제 재빌드 + 재기동 (드물게 — CD 가 정상 흐름)
docker compose -f docker-compose.dev.yml --env-file .env.dev up -d --build --force-recreate

# 수동 롤백 (CD 자동 롤백 실패 시)
cd ~/mobruji
git reset --hard <PREV_SHA>
docker compose -f docker-compose.dev.yml --env-file .env.dev up -d --build

# 정지 / 볼륨 삭제 (DB 초기화)
docker compose -f docker-compose.dev.yml --env-file .env.dev down
docker volume rm mobruji-mysql-dev  # 신중히
```

### H-6) 트러블슈팅 — Phase 4

#### `mem_limit` 부족 — 컨테이너 OOMKill
증상: `docker compose ps` 에서 backend / web 가 `exited` 또는 `restarting`. `dmesg | grep -i oom`.
원인: maestro Claude 가 평소보다 메모리를 많이 먹는 spike 와 dev container 의 limit 합산이 4GB + swap 1GB 를 넘김.
대응:
1. `docker stats` 로 가장 큰 사용자 확인.
2. 일시적이면 maestro 사이클을 잠시 멈춘 뒤 (`tmux send-keys -t mobruji /exit`) 재기동.
3. 만성이면 spec §5-2 메모리 매트릭스 재조정 — `docker-compose.dev.yml` 의 `mem_limit` 또는 mysql `innodb-buffer-pool-size`.

#### CD 자동 롤백 발동
증상: GitHub Actions workflow `CD Dev — NCP` 가 fail, NCP 에서 컨테이너는 직전 SHA 로 돌고 있음.
원인: 새 SHA 가 부팅에 실패 (DB migration 깨짐, 환경 변수 누락 등).
대응:
1. `gh run view <run-id> --log-failed` 로 healthcheck 실패 직전 backend 로그 확인.
2. develop 에 hotfix 머지 (또는 release 보류) → 다음 CD 가 다시 시도.
3. 수동 검증: `git checkout origin/develop` 후 NCP 에서 동일 명령으로 재현.

#### nginx 502 / 504
- 502: backend 또는 web 가 아직 부팅 중 (start_period 60s/30s) — 잠시 대기.
- 504: backend 응답이 30s 넘음 — `docker compose logs backend` 확인.

#### `.env.dev` 변경했는데 반영 안 됨
docker compose 의 환경 변수는 컨테이너 생성 시 한 번 inline. 변경 후 `up -d --force-recreate` 또는 service 별 `up -d --no-deps backend`.

## I) NCP 추가 블록 스토리지 attach + Docker root 이동

### 배경 / 언제 쓰나
Phase 4 dev 환경을 한동안 굴리면 docker image / build cache / fe node_modules 가 누적되어 root 디스크(50GB)가 차오른다. 본진 실측: dev 가동 약 1주 만에 사용률 **88%** (≈ 44GB) 도달, image pull / `npm ci` 가 `ENOSPC` 직전. NCP 의 root 볼륨 자체는 사후 확장이 번거롭고 단순 정리만으로는 다음 사이클에 다시 찬다.

해법: **추가 블록 스토리지(예: 20GB)를 NCP 콘솔에서 attach 한 뒤, Docker 의 데이터 디렉토리(`/var/lib/docker`)와 fe `node_modules` 를 그 볼륨(`/data`)로 이동.** 본진 실측 결과 **88% → 63%** 로 즉시 해소, 다운타임 **약 30초**(docker 재시작 1회), 추가 비용은 NCP 가격표 기준 20GB 블록.

### I-1) NCP 콘솔에서 블록 스토리지 attach (사용자 작업)
NCP 콘솔 → Server → 해당 VM (`mobruji-maestro`) → "스토리지" 탭 → **"스토리지 생성"** (또는 "기존 스토리지 연결").

- 용량: 20GB 권장 (절차 검증된 값; 사용량에 따라 ↑)
- 디스크 종류: SSD (기본)
- 생성 후 자동으로 VM 에 attach 됨 (서버를 끄지 않아도 hot-attach 지원)
- 본체(VM) 와 분리된 볼륨이므로 VM 을 재생성해도 데이터 유지 가능

콘솔이 attach 완료 알림을 띄우면 SSH 로 VM 에 들어가 다음 단계를 진행한다.

### I-2) 디스크 인식 / 포맷 / mount (root)
새 볼륨은 보통 `/dev/xvdb` 또는 `/dev/vdb` 로 들어온다. 이름은 환경마다 달라 `lsblk` 로 먼저 확인.

```bash
# 1) 새 디스크 식별 (MOUNTPOINT 가 비어 있고, 크기가 attach 한 용량과 같은 device 를 찾는다)
lsblk

# 2) XFS 포맷 (-f 는 새 디스크 한정으로 사용; 기존 데이터가 있는 디스크에 절대 쓰지 말 것)
sudo mkfs.xfs /dev/xvdb

# 3) 마운트 포인트 생성 + 임시 mount
sudo mkdir -p /data
sudo mount /dev/xvdb /data

# 4) UUID 추출 (device 이름은 재부팅 시 바뀔 수 있으므로 fstab 은 반드시 UUID 사용)
sudo blkid /dev/xvdb
# → 예: /dev/xvdb: UUID="abcd-...." TYPE="xfs"

# 5) /etc/fstab 영구 등록 (UUID + nofail 옵션)
echo "UUID=<위 UUID>  /data  xfs  defaults,nofail  0  2" | sudo tee -a /etc/fstab

# 6) fstab 검증 — mount 옵션 오타가 있으면 다음 재부팅에 boot 실패할 수 있다
sudo mount -a
df -h /data
```

> **회귀 가드 — `nofail` 필수**: NCP 콘솔에서 사용자가 디스크를 떼는 등 어떤 이유로든 볼륨이 보이지 않을 때, `nofail` 이 없으면 systemd `local-fs.target` 이 실패해 부팅이 멈춘다. dev 환경이라도 maestro/discord-bridge 가 같은 VM 에 있으므로 boot 실패는 곧 본진 정지다.

### I-3) Docker 정지 → data-root 이동 → 재기동 (root)
실제 다운타임이 발생하는 구간. dev container 4개 + docker 데몬을 한 번에 멈춘다.

```bash
# 1) dev container graceful stop (compose 로 띄운 것)
sudo -u mobruji bash -c "cd ~/mobruji && docker compose -f docker-compose.dev.yml --env-file .env.dev down"

# 2) docker 데몬 + 소켓 동시 정지
#    socket 을 같이 멈추지 않으면 다음 docker 명령이 socket activation 으로 데몬을 다시 깨운다
sudo systemctl stop docker
sudo systemctl stop docker.socket

# 3) /etc/docker/daemon.json 작성 (data-root 지정)
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "data-root": "/data/docker"
}
EOF

# 4) 기존 /var/lib/docker → /data/docker 로 복사 (소유권/ACL/xattr 보존)
sudo mkdir -p /data/docker
sudo rsync -aHAX --info=progress2 /var/lib/docker/ /data/docker/

# 5) docker / socket 재기동
sudo systemctl start docker.socket
sudo systemctl start docker
sudo systemctl status docker --no-pager | head -20
```

### I-4) Healthcheck — 4 container 모두 healthy 확인 후 old 삭제
**중요**: rsync 직후 `/var/lib/docker` 를 바로 지우지 말 것. 새 data-root 로 첫 컨테이너가 정상 부팅되는지 먼저 확인한다.

```bash
# 1) dev container 재기동
sudo -u mobruji bash -c "cd ~/mobruji && docker compose -f docker-compose.dev.yml --env-file .env.dev up -d"

# 2) 모든 service 가 healthy 인지 polling (최대 5분)
for i in {1..60}; do
  sudo -u mobruji docker compose -f /home/mobruji/mobruji/docker-compose.dev.yml --env-file /home/mobruji/mobruji/.env.dev ps
  unhealthy=$(sudo -u mobruji docker compose -f /home/mobruji/mobruji/docker-compose.dev.yml --env-file /home/mobruji/mobruji/.env.dev ps --format json | grep -c -v '"Health":"healthy"' || true)
  [ "$unhealthy" = "0" ] && break
  sleep 5
done

# 3) endpoint 검증
curl -fsS http://localhost/_nginx_health
curl -fsS http://localhost/actuator/health/liveness

# 4) 새 data-root 가 실제로 쓰이는지 확인
docker info | grep "Docker Root Dir"
# → "Docker Root Dir: /data/docker" 가 나와야 한다

# 5) 4개 모두 healthy + endpoint 200 인 것을 눈으로 확인한 다음에만 old 삭제
sudo rm -rf /var/lib/docker
```

> **회귀 가드 — old 삭제는 healthy 확인 후**: 새 data-root 가 깨졌을 때 `/var/lib/docker` 가 남아 있으면 `daemon.json` 만 되돌리고 즉시 회복 가능. 미리 지우면 그 fallback 이 사라진다.

### I-5) Frontend `node_modules` symlink (다운타임 0)
fe 의 `node_modules` (수백 MB) 만 따로 옮기면 dev 디스크가 더 가벼워진다. fe dev 가 안 도는 시점(또는 무관)에 진행 가능.

```bash
# 1) 옮길 대상 만들기
sudo mkdir -p /data/web-node_modules
sudo chown mobruji:mobruji /data/web-node_modules

# 2) 기존 node_modules 가 있으면 옮기고, 없으면 (대상이 빈 상태로) 다음 install 이 채움
sudo -u mobruji bash <<'EOF'
cd ~/mobruji/web
if [ -d node_modules ] && [ ! -L node_modules ]; then
  mv node_modules/* /data/web-node_modules/ 2>/dev/null || true
  mv node_modules/.[!.]* /data/web-node_modules/ 2>/dev/null || true
  rmdir node_modules
fi
ln -s /data/web-node_modules node_modules
ls -la node_modules
EOF
```

이후 `npm ci` / `npm install` 은 자동으로 `/data` 디스크에 쌓이고 root 디스크는 영향 없다. CD 가 컨테이너 안 빌드만 쓴다면(현재 dev 흐름) 본 단계는 호스트 fe 디버깅용으로만 의미가 있고 생략 가능.

### I-6) 정량 결과 / 검증 체크리스트
- [ ] `df -h /` → 사용률 **88% → 63%** 수준으로 떨어졌다 (Docker overlay 가 옮겨졌으므로)
- [ ] `df -h /data` → 새 볼륨이 실제로 쓰이고 있다 (사용량 > 0)
- [ ] `docker info | grep "Docker Root Dir"` → `/data/docker`
- [ ] `docker compose ps` → 4 container 모두 `(healthy)`
- [ ] `curl http://localhost/_nginx_health` → `ok`
- [ ] `curl http://localhost/actuator/health/liveness` → `{"status":"UP"}`
- [ ] 다운타임 측정: docker stop → up healthy 까지 **약 30초** (rsync 시간 + 컨테이너 부팅) 수준
- [ ] (재부팅 회귀 테스트) `sudo reboot` 후 `df -h /data` 가 그대로 mount 되어 있다 — fstab UUID + nofail 검증

### I-7) 트러블슈팅 — Phase 4 보강

#### `docker info` 가 여전히 `/var/lib/docker` 로 보임
원인: `daemon.json` 작성 후 `systemctl restart docker` 만 하고 `docker.socket` 은 재시작 안 함. socket activation 으로 데몬이 이전 설정으로 깨어났다.
대응: `sudo systemctl stop docker docker.socket` 후 socket → docker 순으로 다시 start.

#### 재부팅 후 `/data` 가 비어 보임
원인: fstab UUID 오타 또는 device 이름(`/dev/xvdb`) 으로 직접 등록 후 디스크 순서가 바뀜.
대응:
1. `sudo blkid` 로 현재 UUID 재확인.
2. `/etc/fstab` 의 UUID 라인 수정.
3. `sudo mount -a` → 에러 없으면 정상.
4. `nofail` 옵션이 있어서 부팅 자체는 정상 진행됐을 것.

#### rsync 중 `/var/lib/docker` 가 너무 크다
원인: 그동안 쌓인 image / build cache.
대응 (rsync 전 1회):
```bash
sudo -u mobruji docker system prune -af --volumes
```
주의: `--volumes` 는 unnamed volume 까지 지운다. dev MySQL 은 named volume(`mobruji-mysql-dev`) 이므로 영향 없으나, 다른 named 가 있으면 먼저 확인.

#### 옮긴 직후 컨테이너 부팅 실패
원인: 권한/SELinux/ACL 손상.
대응:
1. `sudo journalctl -u docker -n 100 --no-pager` 로 데몬 로그 확인.
2. rsync 가 `-aHAX` 였는지 (소유권/링크/ACL/xattr 모두 보존) 확인.
3. 회복: `daemon.json` 의 `data-root` 라인을 지우거나 원래 경로로 되돌린 뒤 `systemctl restart docker docker.socket`. `/var/lib/docker` 가 살아 있으면 즉시 회복.

### I-8) 운영 메모
- **언제 또 attach 하나**: `/data` 사용률이 80% 를 넘으면 또 한 번 NCP 콘솔에서 볼륨을 키우거나(online resize 가능) 새 볼륨 추가.
- **백업**: dev 환경은 휘발성 허용. `/data/docker` 자체는 image cache 이므로 백업 가치 낮음. 단 `mobruji-mysql-dev` volume 은 dev DB 본체 → 별도 dump 정책은 상위 spec(`docs/features/ncp-maestro-resilience.md`) 참고.
- **prod 환경 확장 시**: 같은 절차를 그대로 쓰되 다운타임을 사전 공지하고, healthcheck polling 임계값을 길게(예: 10분) 잡는다.

## J) 다음 단계

본 런북 §B(Phase 1) ~ §H(Phase 4) 완료 후 (필요 시 §I 디스크 확장 포함):
- Phase 4 dev 환경 사용성 1주 운영 데이터 보고 — `docs/features/deployment-infrastructure.md` (Hetzner CX22 prod) 진행 결정.
- rev sub-agent 가 dev URL 을 통합 시나리오 QA 에 사용 — `docs/features/rev-qa-protocol.md` §5-4 갱신 (별 PR).
- (선택) maestro transcript 무게 모니터 → 자동 `/compact` 트리거.
- (선택) Grafana Cloud remote_write — CPU/RAM/swap/container memory.

## K) 관련 문서

- [`docs/decisions/0015-hosting-stack.md`](../decisions/0015-hosting-stack.md) — 본 런북의 결정 ADR
- [`docs/features/discord-driven-mobruji.md`](../features/discord-driven-mobruji.md) — Discord-driven maestro spec (#338)
- [`docs/features/deployment-infrastructure.md`](../features/deployment-infrastructure.md) — 백/프론트 배포 spec (별 트랙)
- [`docs/features/discord-daemon-hosting.md`](../features/discord-daemon-hosting.md) — Discord daemon 호스트 (본 ADR로 NCP 동거 갱신됨)
- [`docs/ai-harness/11-multi-session-runbook.md`](../ai-harness/11-multi-session-runbook.md) — 멀티 세션 워크트리 운영
- [`docs/runbooks/local-3tier-setup.md`](./local-3tier-setup.md) — 로컬 3-tier 가동 (대조군)
- [`docs/ai-harness/04-security-policy.md`](../ai-harness/04-security-policy.md) — 시크릿/민감정보 정책
