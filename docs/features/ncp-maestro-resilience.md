---
feature: NCP maestro 전체 사이클 멈춤 위험 점검 + 회복 자동화
slug: ncp-maestro-resilience
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: [#384]
related_prs: []
last_reviewed: 2026-05-23
---

# NCP maestro 전체 사이클 멈춤 위험 점검 (15 + 회복 자동화)

## 1) 개요 (What / Why)
NCP maestro 자율 사이클이 멈추는 위험을 15가지로 정리. 각 위험에 (a) 발생 메커니즘, (b) 감지 방법, (c) 자동 회복/완화, (d) 우선순위를 명시. 운영자가 SSH로 들어가지 않아도 시스템이 회복 시도하거나 사용자에게 alert push하도록 설계.

본 spec 의 우선순위로 후속 PR 분할 — P0/P1 먼저, P2/P3 백로그.

## 2) 사용자 시나리오
- (S1) 사용자 외출 6시간 — 위험 발생 시 Discord 채널에 즉시 alert + 자동 회복 시도. 사용자 액션은 결정 분기만.
- (S2) 운영자 깬 직후 — `/status` 슬래시 또는 cron digest로 누적 위험 가시화. P0/P1 위험 표시.
- (S3) 위험 회복 시 — Discord에 1회 "🟢 회복" push. silent on stable.

## 3) 요구사항
### 기능 요구사항
- [ ] 각 위험에 감지 검사 1개 — shell command 또는 health endpoint
- [ ] P0 위험은 5분 cycle 검사 + 즉시 push
- [ ] P1 위험은 15분 cycle 검사 + 발생 시 push
- [ ] P2/P3 위험은 60분 wake 사이클에서 검사 + 변화 시 push
- [ ] 회복 시 1회 "🟢 회복" push (stuck alert 와 짝)
- [ ] 위험별 회복 자동화 — 가능한 것은 systemd/script로, 사람 결정 필요한 것은 push로 위임

### 비기능 요구사항
- 검사 비용 < $0.001 / cycle (shell only, LLM X)
- 알람 노이즈 < 시간당 3회 (동일 위험 발생 alert는 1회만, 회복 시 1회만)
- self-protection — 검사 코드 자체가 maestro 또는 bot.py를 죽이지 않도록 try/except로 감쌈

## 4) 범위 / 비범위
### 포함
- 15 위험 매트릭스 (§5-1)
- 회복 자동화 정책 (§5-2)
- 후속 PR 분할 (§6)

### 제외
- NCP VM 자체 multi-region 이중화 — v0.4 prod 진입 후
- 본진 외부 모니터링 (Grafana Cloud 등) — observability-baseline.md 따로
- Anthropic API 자체 outage — third-party SLO 불가

## 5) 설계

### 5-1) 15 위험 매트릭스
| # | 위험 | (a) 발생 메커니즘 | (b) 감지 | (c) 자동 회복 / 완화 | (d) P |
|---|---|---|---|---|---|
| 1 | wake 부재 | maestro idle인데 ScheduleWakeup 미잡힘 → 사용자 다음 메시지까지 영원 idle. context lost (다음 사이클 콜드스타트). | wake_log 마지막 timestamp ≥ 90분 (bot.py wake_health_monitor) | bot.py가 Discord push "🚨 wake stuck — N분 전 마지막 활동" → 사용자 SSH 또는 메시지 trigger | **P1** |
| 2 | discord-reply workflow main 의존 | `workflow_dispatch` 는 default branch (main) 에 workflow 파일이 있어야 trigger. main 미도달 시 maestro reply 발사 불가. | release 안 끼면 main에 workflow 없음 — 매 새 workflow PR 머지 → 자동 release 사이클 없음 | (a) 자동 daily release script (b) maestro 우회: 발사 못 하면 메모리에 메시지 누적 → 사용자 메시지 도착 시 일괄 보고 | **P1** |
| 3 | auto-ack / cron digest .env 누락 | `DIGEST_ENABLED` env 미설정 시 digest_loop launch 안 됨. .env 변경 후 bridge restart 누락. | journal에 `digest=False`, 또는 채널 5분 간격 메시지 부재 | bot.py default `DIGEST_ENABLED=1` 변경 + .env 와 .env.example sync 검증 step (deploy 시점) | **P2** |
| 4 | context 무거움 (/clear timing) | maestro 세션 context >300k tokens → LLM 비용/지연 증가. /clear 놓치면 다음 메시지 처리 long | 매 wake 시 메모리 size 임계 또는 maestro UI 표시 | wake 시 transcript size 모니터 → 임계 초과 시 핸드오프 메모리 갱신 + Discord 1회 push "context N k — /clear 권장" | **P1** |
| 5 | bypassPermissions partial | `--dangerously-skip-permissions` 가 일부 도구만 bypass. 새 도구 prompt 발생 시 maestro 멈춤 | tmux pipe-pane log 에 prompt 패턴 (`Do you want to allow`) | secondary cron 이 5분 마다 log 패턴 grep → Discord alert "maestro 권한 prompt — SSH 후 응답" | **P2** |
| 6 | sub-agent 분류기 차단 (be 33 stall 패턴) | sub-agent 의 Bash 권한이 maestro 와 분리. 분류기가 거부 시 stall | TaskList 에 in_progress 30분+ 머무는 sub-agent | TaskStop + maestro 직접 작업 또는 sub-agent prompt 에 권한 명시 (메모리 [[feedback-worktree-lock]] 보강) | **P2** |
| 7 | maestro long reasoning queue 지연 | maestro 가 한 reasoning 에 5분+ → 외부 메시지 처리 지연 | tmux pipe-pane 입력 → 응답 latency | maestro reasoning 호흡당 1~2 도구로 쪼개기 (이미 룰). 지연 임계 시 사용자 push | **P2** |
| 8 | mac/NCP token 충돌 | Claude Max OAuth multi-device 회색지대 — mac + NCP 동시 가동 시 quota 초과 또는 token 분쟁 | API 응답 quota / rate-limit | mac maestro 종료 권장 (NCP 단일). secondary cron 이 OAuth refresh fail 감지 | **P1** |
| 9 | workflow rate limit | GitHub Actions concurrent run 제한 — 동시 발사 너무 많으면 queue | `gh run list` queued 누적 | concurrency group 직렬화 (이미 `cd-dev-ncp`). 추가 workflow 도입 시 group 명시 | **P3** |
| 10 | systemd auto-restart infinite loop | mobruji-discord-bridge 가 fail 후 즉시 restart 반복 → StartLimit 5min/5회 후 stop | journal "Start request repeated too quickly" | StartLimitInterval=300/Burst=5 이미 (PR #351 mobruji-maestro 단위 동일). secondary 가 stopped 감지 시 Discord alert | **P2** |
| 11 | bridge queue overflow | 사용자가 빠르게 N 개 메시지 → bot.py 직렬 tmux send-keys → maestro queue 누적 | DedupLedger.count_since(300) 이상 임계 | auto-ack 에 queue 표시 (이미 #361). bot.py 가 rate limit (5/min) 또는 throttle 도입 | **P3** |
| 12 | gh push / setup-git 인증 만료 | NCP gh CLI credential 만료 또는 push 권한 잃음 | git push fail with auth error | gh auth refresh 또는 token regenerate — 사용자 액션. secondary 가 push fail 시 Discord alert | **P2** |
| 13 | Discord 단방향 가시성 (bridge 부재) | bot.py 가동 안 되면 사용자 메시지 미수신 + maestro reply 불가 | `systemctl is-active mobruji-discord-bridge.service` ≠ active | systemd auto-restart (Restart=always, 이미). secondary 가 inactive 감지 시 SSH로 alert (별 경로 필요 — webhook 또는 mac maestro 경유) | **P1** |
| 14 | OAuth max token 만료 + `.claude.json` corruption | Claude Max OAuth 만료 시 maestro Claude TUI 인증 실패. 또는 `.claude.json` (Claude TUI state file) 이 partial-write / disk-full / SIGKILL 로 깨져 maestro 가 콜드스타트 불가. | claude TUI error "auth required" / `jq . ~/.claude.json` non-zero exit / maestro 응답 부재 | (a) mac 에서 새 OAuth → NCP `.credentials.json` 복사. (b) `.claude.json` corruption: **hourly cron backup → 손상 감지 시 직전 백업 자동 복원** (2026-05-23 본진 적용 완료). wake-stuck alert 가 이 케이스를 cover | **P1** |
| 15 | NCP VM OOM / disk / network | VM 4GB RAM full, 10G disk full, 네트워크 down | `free -h` 음수, `df -h` 90%+, ping fail | swap 1GB 보강 (이미). image cleanup (docker system prune). NCP support ticket. secondary 가 임계 감지 시 Discord alert | **P2** |

### 5-2) 회복 자동화 정책
- **P0 (해당 없음 현재)**: 5분 cycle 검사 + 즉시 push + 자동 복구 시도
- **P1**: 15분 cycle 검사 + 발생 시 1회 push + 회복 시 1회 push. 자동 복구 가능한 것 (e.g. systemd restart) 은 즉시. 사람 결정 필요 (OAuth 갱신 등) 는 push만.
- **P2**: 60분 wake 사이클에서 검사 + 변화 push. 회복 자동화는 nice-to-have.
- **P3**: 사용자 명시적 요청 시만 검사.

### 5-2-a) P1 위험 5건 상세 (감지 / 복구 / 자동화 후보)
PR #385 후속 — 매트릭스(§5-1)에서 P1로 표시된 5건의 운영 핸드북 수준 상세. PR B (`resilience_monitor_loop`) 구현의 입력값으로 사용.

#### 위험 #1 — wake stuck (maestro idle 인데 다음 wake 미스케줄)
- **감지 방법**
  - 1차 (bot.py 내장): `wake_log` 파일 (또는 maestro state 파일) 의 마지막 timestamp ≥ 90분 → 임계 위반.
  - 2차 (shell fallback, watchdog): `tmux capture-pane -p -t mobruji-maestro:0 -S -200 | grep -E 'ScheduleWakeup|wake'` 의 최근 매치 timestamp 비교.
  - 메트릭: `time.time() - last_wake_ts` (초). 임계 5400s.
- **복구 절차**
  - 자동 (1단계): bot.py 가 `tmux send-keys -t mobruji-maestro:0 '/wake' Enter` 로 외부 trigger 시도. 5분 후 재검사.
  - 자동 (2단계, 1단계 실패 시): Discord 채널에 `🚨 wake stuck — 마지막 활동 N분 전. 자동 trigger 실패. SSH 또는 메시지 부탁` push (1회만, dedup ledger).
  - 수동: 사용자가 `#모부르지` 채널에 아무 메시지 → bridge 가 maestro pipe 로 전달 → context 복귀.
- **자동화 후보**
  - bot.py `resilience_monitor_loop` (5분 cycle) — 1차/2차 감지 + 자동 trigger + push.
  - systemd timer 불필요 (bot.py 가 down 이면 위험 #13 으로 흡수됨).

#### 위험 #4 — context 무거움 (/clear timing 누락)
- **감지 방법**
  - 1차: maestro transcript 파일 size (`~/.claude/projects/<slug>/<session>.jsonl`) 가 임계 (200MB 또는 라인수 50000) 초과.
  - 2차: `~/.claude/projects/<slug>/memory/MEMORY.md` 가 100KB 초과 (메모리 디렉토리 과적재 proxy).
  - 메트릭: `du -sb` 결과 vs 임계. context 토큰 추정 = file size / 4 (roughly).
- **복구 절차**
  - 자동: Discord 1회 push `🟡 context 약 Nk tokens — /clear 권장 + 핸드오프 메모리 갱신 후 새 세션 시작`. push 후 24h cooldown.
  - 수동: 사용자 또는 maestro 자체가 `/clear` 입력 + 사전에 `project_session_handoff_YYYY-MM-DD.md` 메모리 갱신.
- **자동화 후보**
  - bot.py 가 직접 `/clear` 를 발사하지 **않음** — context 손실 결정은 사용자/maestro 권한.
  - 메모리 디렉토리 6h tar snapshot cron (2026-05-23 본진 적용 완료) 으로 갱신 누락 시 롤백 가능.

#### 위험 #8 — mac/NCP Claude Max OAuth token 충돌
- **감지 방법**
  - 1차: maestro tmux pane 에서 `grep -E '(rate_limit|429|quota|invalid_api_key)' ` 매칭 빈도 ≥ 시간당 5회.
  - 2차: `anthropic` SDK 응답 5xx / 401 누적 (bot.py 로 직접 API 호출 시).
  - 메트릭: 최근 1h 내 API 오류 카운트.
- **복구 절차**
  - 자동: Discord push `🚨 OAuth 충돌 의심 — mac maestro 종료 권장. 최근 1h API 오류 N회`. push 후 30분 cooldown.
  - 수동: 사용자가 mac maestro 종료 (NCP 단일화) 또는 mac/NCP 동시 작업 일정 분리.
- **자동화 후보**
  - bot.py `resilience_monitor_loop` — tmux pane grep + 카운트.
  - 향후 (P3): mac maestro heartbeat 채널에 push, NCP 가 mac 활성 감지 시 자기 작업 자제.

#### 위험 #13 — Discord bridge 부재 (bot.py 자체 down)
- **감지 방법**
  - bot.py self-check 불가 (자기 자신이 죽었으면 감지 못함) — **외부 감시자 필수**.
  - 1차 (systemd 내장): `Restart=always` + `StartLimitInterval=300/Burst=5` (이미 적용).
  - 2차 (watchdog): `tools/deploy/mobruji-bridge-watchdog.sh` 가 systemd timer 로 매 15분 `systemctl is-active mobruji-discord-bridge.service` 확인.
- **복구 절차**
  - 자동 (1단계): systemd 가 즉시 restart (Restart=always).
  - 자동 (2단계): watchdog 가 inactive 감지 시 `systemctl restart` 시도. 3회 fail 시 last-resort alert.
  - last-resort alert: PR #385 spec §8 Q1 미결 — (a) SMS / (b) 메일 / (c) mac maestro webhook. 사용자 결정 대기.
- **자동화 후보**
  - PR C 범위 — `mobruji-bridge-watchdog.sh` + `mobruji-bridge-watchdog.timer` (`OnCalendar=*:0/15`).
  - watchdog 자체가 down 일 가능성 — systemd timer 는 OS 가 살아 있으면 동작하므로 OK.

#### 위험 #14 — OAuth Max token 만료 + `.claude.json` corruption
- **감지 방법**
  - OAuth: maestro tmux pane 에서 `grep -E '(auth required|OAuth.*expired|token.*invalid)' `. 또는 maestro 30분 무응답 + 위험 #1 alert 와 동시 발생.
  - `.claude.json` corruption: `jq -e . ~/.claude.json > /dev/null 2>&1` 의 exit code ≠ 0, 또는 파일 size 0 bytes / size 가 평소 (~수 KB) 에서 급변.
  - 메트릭: `stat -c '%s' ~/.claude.json` + `jq` 검증 결과 + 백업 디렉토리 mtime.
- **복구 절차**
  - OAuth 만료: 사용자가 mac 에서 새 OAuth → NCP `.credentials.json` SCP 복사. 자동화 불가 (대화형 인증).
  - `.claude.json` corruption: **자동 복구** — hourly cron (`crontab -l` 기준) 이 `~/.claude.json` → `~/.claude.json.bak.<HH>` 으로 백업. 손상 감지 시 `cp ~/.claude.json.bak.<직전 시각> ~/.claude.json` + maestro 재기동.
- **자동화 후보**
  - hourly cron backup (2026-05-23 적용 완료) — `0 * * * * cp ~/.claude.json ~/.claude.json.bak.$(date +\%H)`.
  - bot.py `resilience_monitor_loop` 에 `.claude.json` jq 검증 + 손상 시 자동 롤백 후보 등록 (PR B 에서 dry-run 옵션부터). 실제 자동 롤백은 위험 검토 후 별도 PR.
  - 메모리 dir 6h tar snapshot cron (2026-05-23 적용 완료) 으로 메모리 corruption 도 같은 패턴 cover.

### 5-3) secondary 백업 위치
대부분의 감지/회복은 `bot.py` 의 새 asyncio task `resilience_monitor_loop` 에 통합. systemd timer 는 bot.py 자체가 down 일 때를 위한 last-resort:
```
[Timer] OnCalendar=*:0/15  # 매 15분
[Service] ExecStart=/usr/local/bin/mobruji-bridge-watchdog.sh
```
watchdog 은 `systemctl is-active mobruji-discord-bridge` 확인 + 죽었으면 systemd restart 시도 + 5회 fail 시 alert (SMS/메일 — 사용자 설정 필요).

### 5-4) 메모리 룰
[[feedback-autonomous-wake-pattern]] + [[feedback-discord-status-push]] 와 cross-ref. 본 spec 머지 후 [[project-ncp-resilience-log]] 메모리 신설 — 위험 발생/회복 timestamp 누적.

## 6) 작업 분할 (PR 리스트)
P 우선순위로:

**P1 묶음 (즉시):**
- [x] **PR A (본 PR)**: spec 신설
- [ ] **PR B**: bot.py `resilience_monitor_loop` — 위험 1 (wake stuck), 4 (context size — 메모리 file size proxy), 8 (token 충돌 — API 5xx grep), 13 (bridge inactive — self-check 불가, watchdog 필요), 14 (OAuth fail — Claude TUI error grep tmux log)
- [ ] **PR C**: `tools/deploy/mobruji-bridge-watchdog.sh` + systemd timer — 위험 13 last-resort
- [ ] **PR D**: 위험 2 (workflow main 의존) — `daily-auto-release.yml` cron + spec 보강

**P2 묶음 (백로그):**
- [ ] **PR E**: 위험 3, 5, 6, 7, 10, 12, 15 — runbook §H 트러블슈팅 보강
- [ ] **PR F**: 위험 11 — bot.py rate limit (5/min throttle)

**P3 (필요 시):**
- 위험 9 — concurrency group 추가 검토

## 7) 테스트 전략
- 각 감지 검사 — shell command unit (mock 또는 실 NCP run)
- `resilience_monitor_loop` — asyncio mock + 위험 상태 시뮬레이션 + push 시퀀스 검증
- watchdog 스크립트 — systemd timer dry-run

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | last-resort alert 경로 (위험 13 bot.py down) | (a) SMS / (b) 메일 / (c) mac maestro 경유 (multi-device 회색지대) | @user / PR C |
| Q2 | daily auto-release (위험 2 회피) | (a) 매일 자동 / (b) 머지 PR 5+개 누적 시 자동 / (c) 사용자 명시 시만 | @user / PR D |
| Q3 | resilience_monitor cycle 비용 | bot.py 5분마다 5 검사 × 24시간 = 1440 회/일 = 비용 무시 가능 (LLM X) | 답: 무시 가능 |

## 9) 결정 로그
- 2026-05-23: 초안 작성 + 즉시 운영 적용 (status=approved). 사용자 위임 — NCP 멈춤 위험 15 정리. P1 묶음 즉시 진행 권장. secondary 는 bot.py 통합 + watchdog systemd timer 이중화.
- 2026-05-23 (PR #385 후속): §5-2-a P1 5건 (감지/복구/자동화 후보) detail 보강 + §10 `resilience_monitor_loop` 의사코드 추가. 위험 #14 행에 `.claude.json` corruption + hourly cron backup (본진 적용 완료) 반영.

## 10) `resilience_monitor_loop` 의사코드 (PR B 입력)
PR B 구현 가이드. **본 절은 의사코드 — 실제 구현은 후속 PR.** bot.py 의 새 asyncio task 로 동작. 5분 cycle. 검사 5건 (위험 #1, #4, #8, #13(self-skip), #14).

### 10-1) 임계값 / 상수
```python
# tools/discord-daemon/bot.py 추가 상수 (의사코드)
RESILIENCE_INTERVAL_SEC = 300              # 5분
WAKE_STUCK_THRESHOLD_SEC = 5400            # 90분
TRANSCRIPT_SIZE_THRESHOLD_BYTES = 200 * 1024 * 1024   # 200MB
MEMORY_DIR_THRESHOLD_BYTES = 100 * 1024            # 100KB
OAUTH_ERROR_PER_HOUR_THRESHOLD = 5
CLAUDE_JSON_PATH = Path.home() / ".claude.json"
CLAUDE_JSON_BACKUP_GLOB = ".claude.json.bak.*"

# alert dedup — 같은 위험 1회만 push, 회복 시 1회 push
ALERT_COOLDOWN_SEC = {
    "wake_stuck": 1800,        # 30분
    "context_heavy": 86400,    # 24h
    "oauth_conflict": 1800,    # 30분
    "claude_json_corrupt": 300,  # 5분 (자동 복구 직후 재검사)
}

# 알람 채널 — NOTIFY_CHANNEL_ID 가 우선, 없으면 MOBRUJI_CHANNEL_ID fallback
# (NOTIFY_CHANNEL_ID 변경분이 본진에서 stash 보관 중이라 fallback 필수)
NOTIFY_CHANNEL_ID = int(os.getenv("NOTIFY_CHANNEL_ID", os.getenv("MOBRUJI_CHANNEL_ID", "0")))
```

### 10-2) 메인 루프
```python
async def resilience_monitor_loop(bot: discord.Client) -> None:
    """5분 cycle. 위험 #1/#4/#8/#14 점검 + 위험 #13 은 외부 watchdog 영역.

    self-protection: 각 검사 try/except 로 감싸 bot 죽이지 않음.
    """
    await bot.wait_until_ready()
    channel = bot.get_channel(NOTIFY_CHANNEL_ID)
    if channel is None:
        log.warning("resilience_monitor: notify channel 미설정 — loop 종료")
        return

    last_alert_ts: dict[str, float] = {}

    while not bot.is_closed():
        try:
            await _check_wake_stuck(channel, last_alert_ts)
            await _check_context_heavy(channel, last_alert_ts)
            await _check_oauth_conflict(channel, last_alert_ts)
            await _check_claude_json_corruption(channel, last_alert_ts)
            # 위험 #13 (bridge inactive) 는 self-check 불가 — watchdog (PR C)
        except Exception:
            log.exception("resilience_monitor: cycle 실패 (계속 진행)")
        await asyncio.sleep(RESILIENCE_INTERVAL_SEC)
```

### 10-3) 검사 함수 시그니처 (스텁만)
```python
async def _check_wake_stuck(channel, last_alert_ts) -> None:
    """tmux capture-pane 로 마지막 wake timestamp 추출. 임계 초과 시 push + (옵션) /wake trigger."""
    ...

async def _check_context_heavy(channel, last_alert_ts) -> None:
    """maestro 세션 transcript file size + memory dir size. 임계 초과 시 /clear 권장 push."""
    ...

async def _check_oauth_conflict(channel, last_alert_ts) -> None:
    """tmux pane grep — rate_limit / 429 / quota / invalid_api_key 최근 1h 카운트."""
    ...

async def _check_claude_json_corruption(channel, last_alert_ts) -> None:
    """jq -e . ~/.claude.json 검증. 손상 시 push + (dry-run 옵션) 직전 .bak 으로 자동 롤백 후보 등록."""
    ...

def _maybe_alert(channel, last_alert_ts, key: str, message: str) -> None:
    """cooldown 체크 + push + ts 갱신. 회복 시 '🟢 회복' push 도 같은 ledger 사용."""
    ...
```

### 10-4) 미해결 (구현 시 결정)
- `wake_log` 파일 위치 — maestro state 의 어디서 읽을지 (PR B 시점에 확정).
- 자동 `/wake` trigger — 1단계 자동화 도입 여부 (사용자 결정).
- `.claude.json` 자동 롤백 — dry-run 우선, 실제 cp 는 별도 flag.
- last-resort alert (위험 #13) 채널 — Q1 답 후 PR C.
