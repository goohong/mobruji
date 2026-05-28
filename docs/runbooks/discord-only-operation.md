---
name: discord-only-operation
description: "Discord-only 운영 — helper + nmae 분리 아키텍처. 사용자가 폰 Discord만으로 mmae 종료 후에도 mobruji 를 운영하는 전체 셋업 가이드"
status: implementing
metadata:
  type: runbook
---

# Discord-only 운영 런북 — helper + nmae 분리

mac maestro(mmae) 종료 후 사용자가 **폰 Discord 만으로 mobruji 운영**을 가능하게 하는 셋업 가이드입니다. 다른 서버/환경에 그대로 복제할 수 있도록 1회 명령 시퀀스 + 검증 + 트러블슈팅을 정리했습니다.

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
NCP bot.py (Python, discord.py)  — 이슈 #807 단순화본
  ├─ 사용자 메시지 → tmux send-keys → helper session (TMUX_SESSION_NAME=helper)
  └─ cycle-status.json digest cron → DIGEST_CHANNEL_ID push (be/fe/rev/plan 4 워크트리) [#1019 rename — 기존 NOTIFY_CHANNEL_ID backward-compat]
        ↓
   NCP tmux session "helper" (claude CLI, bypass permissions)
      ├─ 자체 답 → ~/.mobruji/discord-reply.sh "<msg>" → Discord REST API 로 직접 push
      └─ 필요 시 nmae(tmux session "mobruji")에 send-keys 로 위임
            ↓
       NCP tmux session "mobruji" (claude CLI, bypass permissions, nmae)
          ├─ 작업 (sub-agent launch, PR 머지, 사이클)
          └─ ~/.mobruji/cycle-status.json 갱신 → digest cron 이 push 로 노출
```

> 본 spec 의 옛 모델 (bot.py 가 helper pane 의 stdout 을 pipe-pane 으로 캡처해 push) 은 미구현이며, helper 가 응답을 직접 push 하는 방식으로 단순화됐다 (#807). §4 참조.

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

> **현재 상태 (2026-05-23)**: `mobruji-helper.service` systemd unit 은 **아직 운영 호스트에 등록되지 않았다**. 운영 호스트(`/etc/systemd/system/`) 에는 `mobruji-maestro.service`, `mobruji-discord-bridge.service` 두 개만 활성. helper 는 현재 사람이 `tmux new-session -d -s helper ...` 로 ad-hoc 가동 중이며, 재부팅 시 수동 재기동이 필요하다.
>
> 본 절차는 helper 자동 가동을 목표로 한 **권장 설정**이다. 운영 호스트에 unit 파일 작성/`systemctl enable --now` 는 보호 영역(systemd) 변경이므로 별 운영 사이클에서 사람 사후 리뷰와 함께 적용한다.

`/etc/systemd/system/mobruji-helper.service` (권장 unit, 미적용):

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

활성화 (운영 호스트 적용 시, 보호 영역이므로 사람 사후 리뷰 권장):
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mobruji-helper
```

대안 — ad-hoc 가동 (현재 운영 모드):
```bash
# helper systemd unit 없을 때 수동 가동
tmux new-session -d -s helper -c /home/mobruji/mobruji \
  "HOME=/home/mobruji XDG_CONFIG_HOME=/home/mobruji/.claude-helper /usr/bin/claude --dangerously-skip-permissions"

# 재부팅 후 매번 다시 실행 필요 (자동 복구 X)
```

## 4) bot.py routing (현재 구현 상태)

### 4-1) 안티패턴 (이 spec 이전, 사실상의 기록)
사용자 메시지를 nmae(`mobruji` session) 에 직접 send-keys 로 보냈음. nmae 가 작업 중이면 응답이 끊겨 사용자 깜깜이.

### 4-2) 현재 구현 (이슈 #807 단순화본)
실 `tools/discord-daemon/bot.py` 는 단일 `TMUX_SESSION_NAME` env 로 routing 한다. 본 런북의 helper 분리 모델에 맞춰 운영 시 `TMUX_SESSION_NAME=helper` 로 지정하면 사용자 메시지가 helper pane 으로 들어간다.

```python
# bot.py (요지 — 실 코드는 tools/discord-daemon/bot.py 참조)
env["TMUX_SESSION_NAME"] = os.environ.get("TMUX_SESSION_NAME", "helper")
env["TMUX_TARGET_PANE"]  = os.environ.get("TMUX_TARGET_PANE", "helper:0.0")

# 사용자 메시지 → 단일 tmux pane 으로 단순 fan-out (분기 없음)
tmux_send_payload(env["TMUX_TARGET_PANE"], user_msg)
```

- helper 응답은 helper 측에서 별도 스크립트 `~/.mobruji/discord-reply.sh "<msg>"` 로 Discord REST API 에 직접 push (bot.py 안에 응답 watcher 없음). spec: [`docs/features/discord-driven-mobruji.md`](../features/discord-driven-mobruji.md), 룰 요지: [`CLAUDE.md` §11-pre](../../CLAUDE.md).
- nmae digest 보고는 bot.py 의 cycle-status.json digest cron 이 `~/.mobruji/cycle-status.json` 을 polling 해서 `DIGEST_CHANNEL_ID` 채널에 push (#1019 rename — 기존 `NOTIFY_CHANNEL_ID` 도 backward-compat 으로 fallback 인식. helper/maestro 가 status JSON 을 갱신).
- 본 spec 의 §4-2/§4-3 옛 의사 코드 (`USER_TARGET_TMUX`, `pipe-pane` 분기, `DIGEST_CHANNEL_ID` 별 채널 watcher) 는 **현재 구현되지 않은 미래 모델**이며, 이슈 #807 단순화로 의도적으로 폐기됐다. 향후 다시 도입 시 본 런북을 갱신한다 (spec status: `implementing`).

### 4-3) `.env` 키 (실 운영)
실제 bot.py 가 읽는 env 는 다음과 같다 (`bot.py` build_env 참조):

| 키 | 필수 | 비고 |
|---|---|---|
| `DISCORD_BOT_TOKEN` | 필수 | Discord Developer Portal |
| `ALLOWED_USER_IDS` | 필수 | 콤마 구분 user id 화이트리스트 |
| `MOBRUJI_CHANNEL_ID` | 필수 | 사용자 양방향 채널 (#모부르지) |
| `TMUX_SESSION_NAME` | 옵션 (기본 `helper`) | 사용자 메시지 routing 대상 세션 |
| `TMUX_TARGET_PANE`  | 옵션 (기본 `helper:0.0`) | tmux send-keys target |
| `CLAUDE_BIN`        | 옵션 (기본 `claude`)    | tmux 세션 부트 시 실행 명령 |
| `DIGEST_CHANNEL_ID` | 옵션 (기본 = `MOBRUJI_CHANNEL_ID`) | digest cron 전용 채널. #1019 rename — 기존 `NOTIFY_CHANNEL_ID` 도 backward-compat 으로 fallback 인식 (deprecation warning 1회). |
| `DIGEST_ENABLED`    | 옵션 (기본 `1`) | cycle-status digest cron on/off |
| `CYCLE_STATUS_PATH` | 옵션 (기본 `~/.mobruji/cycle-status.json`) | digest 입력 JSON |
| `DEDUP_LEDGER_PATH` | 옵션 | SQLite dedup ledger |
| `GITHUB_PAT` / `GITHUB_REPO` | 옵션 | repository_dispatch fallback |

옛 spec 의 `USER_TARGET_TMUX`, `HELPER_PIPE_PANE_PATH`, `NMAE_PIPE_PANE_PATH` 는 현재 bot.py 가 인식하지 않는다. helper/nmae 채널 분리는 `DIGEST_CHANNEL_ID` (digest 전용, #1019 에서 기존 `NOTIFY_CHANNEL_ID` 를 rename — backward-compat 인식) 와 `MOBRUJI_CHANNEL_ID` (사용자 양방향) 로 갈음한다.

## 5) 검증 시나리오

### 5-1) 사용자 query → helper 즉시 답
1. 사용자가 Discord에 "지금 뭐 하고 있어?" 보냄
2. bot.py 1초 안 auto-ack: 사용자 메시지에 👀 emoji reaction add (`BOT_AUTO_ACK_EMOJI`, default `👀`). 2026-05-28 (#1175) reaction-only — 별도 채팅 ack push 폐기.
3. bot.py 가 helper pane(`TMUX_TARGET_PANE`) 에 send-keys 로 메시지 inject
4. helper 가 tmux capture-pane 으로 nmae 상태 + `gh pr list` 수집 후 정중체 응답
5. helper 가 `bash ~/.mobruji/discord-reply.sh "<응답 본문>"` 호출로 Discord 채널에 직접 push (10~30 초 안)

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
- fix:
  1. **systemd unit 적용 호스트**: `sudo systemctl restart mobruji-helper`
  2. **ad-hoc 가동 호스트 (현재 운영 모드)**: `tmux kill-session -t helper` → §3-3 절차로 helper 재기동
  3. context 한계만의 문제면 `tmux send-keys -t helper:0.0 "/clear" Enter` (단 핸드오프 메모리 먼저 갱신)

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
- [ ] bot.py 환경 변수 셋업 (.env, §4-3 표 참조): 필수 — `DISCORD_BOT_TOKEN`, `ALLOWED_USER_IDS`, `MOBRUJI_CHANNEL_ID`. 권장 — `TMUX_SESSION_NAME=helper`, `TMUX_TARGET_PANE=helper:0.0`, `DIGEST_CHANNEL_ID=<digest 별 채널>` (#1019 rename — 기존 `NOTIFY_CHANNEL_ID` 도 backward-compat)
- [ ] systemd unit — 현재 적용 2개 (`mobruji-maestro.service`, `mobruji-discord-bridge.service`). helper 자동 가동을 원하면 `mobruji-helper.service` 추가 (§3-4 보호 영역, 사람 사후 리뷰). 미적용 시 §3-4 대안 절차로 ad-hoc 가동.
- [ ] 디스크 확장 (Phase 4 dev container 가동 시 추가 필요)
- [ ] GitHub Secrets 3건 (`NCP_SSH_HOST`, `NCP_SSH_USER`, `NCP_SSH_KEY`) — CD workflow용
- [ ] Discord bot 권한 (메시지 읽기/쓰기, 이 채널 추가)
- [ ] 메모리 디렉터리 scp (`~/.claude/projects/<cwd-prefix>/memory/`)
- [ ] 핸드오프 메모리 v_N 신규 작성

## 8) 관련 spec/runbook
- `docs/runbooks/ncp-maestro-setup.md` — nmae 기본 셋업
- `docs/features/discord-driven-mobruji.md` — Discord bridge 원본 spec
- `docs/features/rev-qa-protocol.md` — rev sub-agent QA 룰
- `docs/ai-harness/11-multi-session-runbook.md` — be/fe/rev/plan 세션 룰

## 9) 결정 로그
- **2026-05-23 (사용자 결정 — 본 spec 도입 사유)**: 폰 Discord-only 운영. mac maestro(mmae) 종료해도 시스템 가동. nmae 단독 운영 안티패턴(사용자 query 무시) 해소 위해 helper 분리 도입.
- **helper 호칭**: nmae와 같은 maestro 계열이 아니라 `helper`로 별도 명명 (역할 분리 명확).
- **maestro alias**: mmae = mac maestro / nmae = ncp maestro (사용자 결정, [[feedback-maestro-aliases]]).
