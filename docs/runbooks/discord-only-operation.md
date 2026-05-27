---
name: discord-only-operation
description: "Discord-only 운영 — helper + nmae 분리 아키텍처. 사용자가 폰 Discord만으로 mmae 종료 후에도 mobruji 본진을 운영하는 전체 셋업 가이드"
status: implementing
metadata:
  type: runbook
---

# Discord-only 운영 런북 — helper + nmae 분리

mac maestro(mmae) 종료 후 사용자가 **폰 Discord만으로 mobruji 본진 운영**을 가능하게 하는 셋업 가이드입니다. 다른 서버/환경에 그대로 복제할 수 있도록 1회 명령 시퀀스 + 검증 + 트러블슈팅을 정리했습니다.

## 1) 배경 / 동기

### 1-1) 안티패턴 (이 spec 이전)
- **단일 nmae 운영**: NCP VM tmux에 `claude` 1개 가동. 사용자가 Discord 메시지를 보내면 bot.py가 tmux send-keys로 nmae에 전달.
- **문제**:
  1. nmae가 작업(코드 변경, sub-agent launch, reasoning 5~10분) 중에는 사용자 query에 답하지 않음. 사용자는 "왜 답이 없냐" 답답.
  2. nmae 응답이 tmux pane에만 출력되고 Discord push는 누락 (LLM 의도 의존, 일관성 X).
  3. nmae가 답해도 자체 status(release/PR/사이클)만 답하고 사용자 query에 직접 답 X.
  4. tmux pane ANSI escape code가 Discord push 시 깨짐.

### 1-2) 본 spec의 해결
- **역할 분리**: nmae = 작업 + digest/보고 / **helper = 사용자 양방향 전담**.
- **helper는 nmae 안 기다림**: tmux capture-pane으로 nmae 상태 즉시 파악 + gh/git 정보 수집 + 정중체 답.
- **helper LLM 응답 = Discord raw 그대로** (별도 form 변환 X). 폰 Discord에서 mac terminal 대화 그대로 경험.

## 2) 아키텍처

```
사용자 (폰 Discord)
  ↓ Discord Gateway WebSocket
NCP bot.py (Python, discord.py)
  ├─ 사용자 메시지 → tmux send-keys → helper session
  └─ helper stdout pipe-pane 캡처 → ANSI strip + Discord raw push
        ↓
   NCP tmux session "helper" (claude CLI, bypass permissions)
      ├─ 자체 답 (tmux capture + gh/git 정보)
      └─ 필요 시 nmae(다른 tmux session)에 send-keys로 위임
            ↓
       NCP tmux session "mobruji" (claude CLI, bypass permissions, nmae)
          ├─ 작업 (sub-agent launch, PR 머지, 사이클)
          └─ digest/보고 → Discord (별 channel 또는 메인) 직접 push
```

### 2-1) tmux session 분리
- **`helper`**: 사용자 응답 전담. 작업 X.
- **`mobruji`**: nmae. 작업 전담. 사용자 직접 응답 X.

### 2-2) Discord 채널 분리 (옵션)
- **#모부르지** (메인): helper ↔ 사용자 양방향 대화
- **#모부르지-digest** (보조): nmae digest/보고 자동 push

## 3) 1회 셋업 절차 (NCP 또는 다른 VM)

### 3-1) 사전 조건
- VM: Ubuntu 22.04+ / 2 vCPU / 4 GB RAM / 30 GB+ 디스크
- 도메인/공인 IP (SSH 접근 필요)
- Claude Max 구독 또는 Anthropic API key
- Discord bot 토큰 (Developer Portal에서 발급)
- GitHub PAT (또는 OAuth)

### 3-2) 기본 셋업 (nmae)
[기존 `docs/runbooks/ncp-maestro-setup.md` 참조 — Node 22, gh CLI, claude CLI, tmux, repo clone, 워크트리, systemd unit]

### 3-3) helper 추가 셋업 (본 spec 핵심)

```bash
# 1. helper용 디렉터리 + credentials 공유 (Max OAuth multi-device)
mkdir -p /home/mobruji/.claude-helper
cp /home/mobruji/.claude/.credentials.json /home/mobruji/.claude-helper/.credentials.json

# 2. helper용 gh auth (사용자 token 또는 별도 PAT)
HOME=/home/mobruji XDG_CONFIG_HOME=/home/mobruji/.claude-helper gh auth login --with-token < /path/to/token.txt
HOME=/home/mobruji XDG_CONFIG_HOME=/home/mobruji/.claude-helper gh auth setup-git

# 3. helper tmux session 신설 + claude 가동
tmux new-session -d -s helper -c /home/mobruji/mobruji \
  "HOME=/home/mobruji XDG_CONFIG_HOME=/home/mobruji/.claude-helper /usr/bin/claude --dangerously-skip-permissions"

# 4. helper 초기 prompt (역할 정의 + 메모리 로드 trigger)
tmux send-keys -t helper "안녕하세요 helper. 당신은 mobruji 프로젝트의 사용자 ↔ nmae 중재자입니다. 역할: (1) Discord #모부르지에서 사용자 메시지 받으면 즉시 답변 (tmux capture-pane으로 nmae 상태 파악 + gh pr list + git status). (2) nmae 작업이 길어도 사용자를 기다리게 두지 않습니다. (3) 필요 시 nmae(tmux session mobruji)에 send-keys로 위임. (4) 정중체(~합니다/~할까요?)로 응답. (5) 응답이 길면 줄바꿈으로 가독성 확보. 메모리: ~/.claude/projects/-home-mobruji-mobruji/memory/MEMORY.md 읽어주세요." Enter
sleep 5
tmux send-keys -t helper Enter
```

### 3-4) helper systemd unit (재부팅 시 자동 시작)

`/etc/systemd/system/mobruji-helper.service`:

```ini
[Unit]
Description=mobruji helper (사용자 ↔ nmae 중재자)
After=network-online.target mobruji-maestro.service
Wants=network-online.target

[Service]
Type=forking
User=mobruji
WorkingDirectory=/home/mobruji/mobruji
Environment=HOME=/home/mobruji
Environment=XDG_CONFIG_HOME=/home/mobruji/.claude-helper
Environment=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ExecStart=/usr/bin/tmux new-session -d -s helper -c /home/mobruji/mobruji "/usr/bin/claude --dangerously-skip-permissions"
ExecStop=/usr/bin/tmux kill-session -t helper
Restart=on-failure
RestartSec=10s

[Install]
WantedBy=multi-user.target
```

활성화:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mobruji-helper
```

## 4) bot.py routing 변경 (코드 fix)

### 4-1) 기존 (anti-pattern)
```python
# 사용자 메시지 → nmae tmux send-keys
TMUX_SESSION = "mobruji"
tmux_send(TMUX_SESSION, user_msg)
```

### 4-2) 신 routing (helper 우선)
```python
# 사용자 메시지 → helper tmux send-keys
USER_TARGET_TMUX = os.environ.get("USER_TARGET_TMUX", "helper")  # helper 또는 nmae fallback
tmux_send(USER_TARGET_TMUX, user_msg)

# helper stdout pipe-pane → Discord raw push (ANSI strip)
helper_pipe_pane(USER_TARGET_TMUX, "/tmp/helper-pane.log")
watch_and_push(log_file="/tmp/helper-pane.log", channel_id=MAIN_CHANNEL_ID)

# nmae stdout pipe-pane → Discord digest 채널 (옵션)
nmae_pipe_pane("mobruji", "/tmp/nmae-pane.log")
watch_and_push_digest(log_file="/tmp/nmae-pane.log", channel_id=DIGEST_CHANNEL_ID)
```

### 4-3) `.env` 추가
```
USER_TARGET_TMUX=helper
HELPER_PIPE_PANE_PATH=/tmp/helper-pane.log
NMAE_PIPE_PANE_PATH=/tmp/nmae-pane.log
DIGEST_CHANNEL_ID=<별 채널 ID, 옵션>
```

## 5) 검증 시나리오

### 5-1) 사용자 query → helper 즉시 답
1. 사용자가 Discord에 "지금 뭐 하고 있어?" 보냄
2. bot.py 1초 안 auto-ack: "📥 받음, helper 처리 중"
3. helper가 tmux capture-pane으로 nmae 상태 + `gh pr list` 수집
4. helper가 정중체 응답: "현재 PR #XXX 머지 중이고, sub-agent 4개 가동 중입니다..."
5. bot.py가 helper stdout pipe-pane 캡처 → Discord raw push (10~30초 안)

### 5-2) helper가 nmae에 위임
1. 사용자가 Discord에 "release v0.4.0 진행해" 보냄
2. helper가 자체 답: "release v0.4.0 진행하겠습니다. nmae에 위임합니다."
3. helper가 nmae(tmux session mobruji)에 send-keys: "release v0.4.0 진행"
4. nmae가 release 작업 + digest 채널에 보고
5. helper가 nmae 진행 monitor + 완료 시 사용자에게 push

### 5-3) nmae 작업 중에도 helper 응답
1. nmae가 5분 reasoning 중
2. 사용자가 Discord에 "디스크 상태?" 보냄
3. helper가 nmae 안 기다리고 즉시 `df -h` 실행 + 답
4. 사용자는 30초 안에 답 받음

## 6) 트러블슈팅

### 6-1) helper claude 가동 시 OAuth 요구
- 원인: `~/.claude-helper/.credentials.json` 누락 또는 만료
- fix: nmae의 `~/.claude/.credentials.json`을 다시 복사. 만료 시 nmae도 재인증 필요.

### 6-2) Max OAuth multi-device 충돌
- 증상: helper 또는 nmae 중 하나가 Discord/API 호출 시 401
- 원인: Anthropic이 동시 active session 제한할 수도
- fix: helper에 별 OAuth (사용자가 새 Max 또는 별 계정) 또는 Anthropic API key 사용

### 6-3) Discord push 누락 (helper 답이 raw로 안 옴)
- 원인: bot.py의 pipe-pane watcher가 ANSI strip 못 함 또는 marker 룰 부정확
- fix: `bot.py`의 ANSI regex 확장 + line buffering 검토 (PR #735 등 참조)

### 6-4) helper가 답을 안 함 (idle)
- 원인: helper context 한계 (~95% used) 또는 stall
- fix: tmux send-keys "/clear" Enter (단 핸드오프 메모리 먼저 갱신) 또는 systemctl restart mobruji-helper

### 6-5) nmae 작업과 helper 작업이 같은 워크트리에서 충돌
- 원인: helper가 git 작업하면 nmae sub-agent와 같은 워크트리 점유
- fix: helper는 read-only 작업만 (gh pr list, tmux capture, df). 코드 변경은 절대 nmae에 위임.

## 7) 다른 환경에 복제 시 체크리스트

새 서버/VM에 같은 구조 만들 때:

- [ ] VM 사양 충족 (4GB RAM, 30GB+ 디스크)
- [ ] Node 22 + tmux + gh CLI + claude CLI 설치
- [ ] mobruji repo clone + 워크트리 4개 (be/fe/rev/plan)
- [ ] nmae credentials (.credentials.json) — OAuth 1회 또는 기존 NCP에서 scp
- [ ] helper credentials 복사 (multi-device 또는 별 OAuth)
- [ ] bot.py 환경 변수 셋업 (.env): `DISCORD_BOT_TOKEN`, `GITHUB_PAT`, `USER_TARGET_TMUX=helper`, `MOBRUJI_CHANNEL_ID`, (옵션) `DIGEST_CHANNEL_ID`
- [ ] systemd unit 2개 (`mobruji-maestro.service`, `mobruji-helper.service`, `mobruji-discord-bridge.service`)
- [ ] 디스크 확장 (Phase 4 dev container 가동 시 추가 필요)
- [ ] GitHub Secrets 3건 (`NCP_SSH_HOST`, `NCP_SSH_USER`, `NCP_SSH_KEY`) — CD workflow용
- [ ] Discord bot 권한 (메시지 읽기/쓰기, 이 채널 추가)
- [ ] 메모리 디렉터리 scp (`~/.claude/projects/<cwd-prefix>/memory/`)
- [ ] 핸드오프 메모리 v_N 신규 작성

## 8) 관련 spec/runbook
- `docs/runbooks/ncp-maestro-setup.md` — nmae 기본 셋업
- `docs/features/discord-driven-mobruji.md` — Discord bridge 원본 spec
- `docs/features/rev-qa-protocol.md` — rev sub-agent QA 룰
- `docs/ai-harness/10-multi-session-runbook.md` — be/fe/rev/plan 세션 룰

## 9) 결정 로그
- **2026-05-23 (사용자 결정 — 본 spec 도입 사유)**: 폰 Discord-only 운영. mac maestro(mmae) 종료해도 시스템 가동. nmae 단독 운영 안티패턴(사용자 query 무시) 해소 위해 helper 분리 도입.
- **helper 호칭**: nmae와 같은 maestro 계열이 아니라 `helper`로 별도 명명 (역할 분리 명확).
- **maestro alias**: mmae = mac maestro / nmae = ncp maestro (사용자 결정, [[feedback-maestro-aliases]]).
