---
feature: NCP maestro 전체 사이클 멈춤 위험 점검 + 회복 자동화
slug: ncp-maestro-resilience
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: [384]
related_prs: [385, 391, 871, 875]
last_reviewed: 2026-05-24
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
- maestro 외부 모니터링 (Grafana Cloud 등) — observability-baseline.md 따로
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
| 14 | OAuth max token 만료 + `.claude.json` corruption | Claude Max OAuth 만료 시 maestro Claude TUI 인증 실패. 또는 `.claude.json` (Claude TUI state file) 이 partial-write / disk-full / SIGKILL 로 깨져 maestro 가 콜드스타트 불가. | claude TUI error "auth required" / `jq . ~/.claude.json` non-zero exit / maestro 응답 부재 | (a) mac 에서 새 OAuth → NCP `.credentials.json` 복사. (b) `.claude.json` corruption: **hourly cron backup → 손상 감지 시 직전 백업 자동 복원** (2026-05-23 maestro 적용 완료). wake-stuck alert 가 이 케이스를 cover | **P1** |
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
  - 메모리 디렉토리 6h tar snapshot cron (2026-05-23 maestro 적용 완료) 으로 갱신 누락 시 롤백 가능.

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
- [ ] **PR B**: bot.py `resilience_monitor_loop` — 위험 1 (wake stuck), 4 (context size — 메모리 file size proxy), 8 (token 충돌 — API 5xx grep), 13 (bridge inactive — self-check 불가, watchdog 필요), 14 (OAuth fail — Claude TUI error grep tmux log). **실 구현 spec → §11 (분할 1차 §11-1~§11-3, 분할 2차 §11-4~§11-6).**
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
- 2026-05-23 (PR #385 후속): §5-2-a P1 5건 (감지/복구/자동화 후보) detail 보강 + §10 `resilience_monitor_loop` 의사코드 추가. 위험 #14 행에 `.claude.json` corruption + hourly cron backup (maestro 적용 완료) 반영.
- 2026-05-23 (PR #385/#391 후속, 분할 1차): §11-1~§11-3 신설 — 의사코드(§10) 한 단계 더 내려간 실 구현 spec (함수 signature + state 관리 / 5건 점검 명령·임계·비용 / 푸시 메시지 템플릿). §11-4~§11-6 (회복 자동화 / systemd 통합 / 회귀 가드) 는 stub. be 사이클 입력으로 전달.

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
# (NOTIFY_CHANNEL_ID 변경분이 maestro에서 stash 보관 중이라 fallback 필수)
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

## 11) `resilience_monitor_loop` 실 구현 spec (PR B 입력, 의사코드 §10 → 실 구현)
§10 의사코드를 한 단계 더 내려간 구현 계약. be 사이클이 본 절(§11-1~§11-3 1차, §11-4~§11-6 2차)을 그대로 코드로 옮길 수 있도록 함수 시그니처/state 스키마/임계/명령/메시지 템플릿을 모두 확정.

**분할**: 본 PR 은 §11-1~§11-3 (시그니처 + state + 점검 매트릭스 + 푸시 템플릿). §11-4~§11-6 (회복 자동화 / systemd 통합 / 회귀 가드) 는 stub — 다음 PR 에서 채움.

### 11-1) 함수 signature + state 관리
`tools/discord-daemon/bot.py` 에 신규 asyncio task. state 는 JSON 파일 1개로 외부화 — bot 재기동 시 dedup ledger 유지 + 같은 메시지 5분마다 재push 회피.

```python
# tools/discord-daemon/bot.py — 신규 함수 (§10 의 main loop 와 1:1 대응)
async def resilience_monitor_loop(
    client: discord.Client,
    notify_channel_id: int,
    state_path: Path,
) -> None:
    """5분 cycle. 위험 #1/#4/#8/#14 점검 + delta push. heartbeat 1h.

    Args:
        client: discord.Client (bot 본체).
        notify_channel_id: alert 보낼 채널 id (NOTIFY_CHANNEL_ID 또는 MOBRUJI_CHANNEL_ID).
        state_path: dedup ledger / heartbeat 저장 위치. 기본 ``/home/mobruji/.claude/state/resilience.json``.

    self-protection: 각 검사 try/except 로 감싸 bot 죽이지 않음. 외부 IO (subprocess/file) 모두 timeout 명시.
    """
```

**state 파일 스키마** (`/home/mobruji/.claude/state/resilience.json`, 0600 권한):
```json
{
  "schema_version": 1,
  "last_heartbeat_ts": 1716440000.0,
  "risks": {
    "wake_stuck":         {"last_triggered_ts": 1716438000.0, "last_recovered_ts": 1716439500.0, "active": false},
    "context_heavy":      {"last_triggered_ts": 0,            "last_recovered_ts": 0,            "active": false},
    "oauth_conflict":     {"last_triggered_ts": 0,            "last_recovered_ts": 0,            "active": false},
    "claude_json_corrupt":{"last_triggered_ts": 0,            "last_recovered_ts": 0,            "active": false}
  }
}
```

**push 규칙**:
- `active=False → True` 전이: 🚨/🟡 alert 1회 push, `last_triggered_ts` 갱신.
- `active=True → False` 전이: 🟢 회복 push 1회.
- 같은 상태 유지: push 없음 (delta-only).
- 1h 동안 어떤 alert 도 없으면 heartbeat 1줄 push (`🫧 resilience OK — N분 idle`). `last_heartbeat_ts` 갱신.
- bot 재기동: state 파일 읽어 `active` 복원 → 직전 상태와 동일하면 silent.

**디렉토리/권한**:
- `state_path.parent.mkdir(parents=True, exist_ok=True)` 첫 진입 시.
- 파일 write 는 `tmp + os.replace` atomic — partial-write 방지.

### 11-2) P1 5건 점검 매트릭스 (임계 / 명령 / graceful skip / 비용)

| 위험 | 임계 | 점검 명령 (NCP 셸) | graceful skip | 실 호출 비용 (per check) |
|---|---|---|---|---|
| #1 wake_stuck | `time.time() - last_wake_ts > 5400` (90분) | `tmux capture-pane -p -t mobruji-maestro:0 -S -200` → grep `ScheduleWakeup|wake` 마지막 timestamp 추출 | tmux 세션 부재 (`tmux has-session -t mobruji-maestro` exit≠0) → skip + 로그 `WARN tmux session missing` | tmux pipe ~10ms, regex parse 무시 |
| #4 context_heavy | transcript jsonl > 200MB **또는** memory dir > 100KB | `du -sb ~/.claude/projects/-home-mobruji-mobruji/<session>.jsonl` + `du -sb ~/.claude/projects/-home-mobruji-mobruji/memory` | 세션 파일 glob 결과 0건 → skip (콜드스타트 직후) | stat 2회 ~5ms |
| #8 oauth_conflict | 최근 1h 내 `rate_limit\|429\|quota\|invalid_api_key` 매치 ≥ 5건 | `tmux capture-pane -p -t mobruji-maestro:0 -S -2000` → regex count | tmux 부재 → skip | tmux pipe ~15ms |
| #13 bridge_inactive | (self-check 불가 — §11-2 에서는 placeholder 만, 실제 감지는 watchdog PR C) | (skip — `_check_bridge_inactive` 는 항상 graceful skip + 1회 로그 `INFO bridge self-check skipped (watchdog 영역)`) | 항상 skip | 0 |
| #14 claude_json_corrupt | `jq -e . ~/.claude.json` exit≠0 **또는** size=0 **또는** size 가 직전 hourly backup 대비 90% 미만 | `jq -e . ~/.claude.json` + `stat -c '%s' ~/.claude.json` + `ls -1t ~/.claude.json.bak.* | head -1` 비교 | 백업 파일 0건 (`ls` 결과 비음) → 손상 감지만 push, 자동 롤백 보류 | jq ~50ms, stat ~5ms |

**cycle 총 비용**: 약 90ms / 5분 = 무시 가능 (LLM X, shell only).

**timeout 룰**:
- `asyncio.wait_for(subprocess, timeout=10.0)` — 모든 subprocess 호출.
- timeout 초과 → graceful skip + `WARN check <name> timeout` 로그. push 안 함 (false alarm 방지).

### 11-3) 푸시 메시지 템플릿 (delta-only)

각 위험별 alert 1회 + 회복 1회. 본문은 1-3줄, 한국어 + 이모지 등급.

#### 위험 #1 wake_stuck — 🔴 시급
```
🚨 wake stuck — 마지막 활동 {minutes}분 전 (임계 90분 초과)
원인 후보: ScheduleWakeup 미스케줄 / Claude TUI 무응답
회복 시도: 자동 `/wake` trigger 발사 — N초 후 재검사
```
회복 시:
```
🟢 wake 회복 — 마지막 활동 {minutes}분 전 (정상 범위 복귀)
```
멘션: 채널 멘션 없음 (사용자 외출 가정, 메시지만 누적). 향후 P0 도입 시 `@here`.

#### 위험 #4 context_heavy — 🟡 보강
```
🟡 context 약 {tokens_estimate}k tokens — /clear 권장
transcript: {transcript_mb}MB, memory: {memory_kb}KB (임계 200MB / 100KB)
대응: 핸드오프 메모리(`project_session_handoff_*.md`) 갱신 후 새 세션
```
회복 시: (push 없음 — `/clear` 직후 자연 회복, 다음 cycle 에서 active=False 로 silent 전환)

#### 위험 #8 oauth_conflict — 🔴 시급
```
🚨 OAuth 충돌 의심 — 최근 1h API 오류 {count}회 (임계 5회)
원인 후보: mac + NCP 동시 가동 (Claude Max multi-device 회색지대)
회복 시도: 사용자 결정 필요 — mac maestro 종료 또는 시간대 분리
```
회복 시:
```
🟢 OAuth 회복 — 최근 1h API 오류 {count}회 (정상 범위)
```

#### 위험 #13 bridge_inactive — placeholder (watchdog 영역)
```
(self-check 불가 — alert 발사 안 함. watchdog PR C 가 systemd timer 로 외부 감시.)
```

#### 위험 #14 claude_json_corrupt — 🔴 시급
```
🚨 .claude.json 손상 의심 — jq 검증 실패 또는 size {actual}B (직전 backup {prev}B)
회복 시도: hourly backup `~/.claude.json.bak.{hh}` 로 자동 롤백 후보 등록 (dry-run, §11-4 에서 실 적용)
사용자 액션: SSH 후 백업 검증 + 수동 cp
```
회복 시:
```
🟢 .claude.json 회복 — jq 검증 성공, size {actual}B
```

**heartbeat (1h idle 시)**:
```
🫧 resilience OK — {idle_minutes}분 idle (위험 4건 모두 정상)
```

**delta 판정**:
- 매 cycle 끝에 `risks[*].active` 와 직전 state 비교.
- 변화 있으면 push + state write. 없으면 push 안 함.
- heartbeat 는 별도 ledger (`last_heartbeat_ts`) — alert push 가 있으면 heartbeat reset (alert 가 곧 살아있음 신호).

### 11-4) 회복 자동화

P1 5건 별 자동 회복 시도. 회복 후 검증 → 결과 push (성공 시 🟢 / 실패 시 🔴 escalation).

#### 11-4-a) 회복 매트릭스

| 위험 | trigger 조건 | 회복 command | 검증 방법 | 실패 시 |
|---|---|---|---|---|
| 디스크 90%+ | `disk_pct >= 90` | `docker system prune -af --volumes` → `journalctl --vacuum-time=7d` → `npm cache clean --force` (best-effort) | 재측정 `disk_pct < 85` | escalation (사용자 멘션) |
| heap 95%+ | `heap_pct >= 95` (backend JVM) | `docker compose restart backend` | 60s 후 `/actuator/health` UP + heap_pct < 80 | escalation |
| container exit | `docker ps -a` 에서 `Exited` 상태인 mobruji-* 컨테이너 존재 | `docker compose up -d <name>` (최대 3회 retry, 각 retry 간 10s 백오프) | `docker ps` 에서 해당 컨테이너 `Up` | escalation |
| bridge inactive | `systemctl is-active mobruji-discord-bridge` ≠ `active` | `sudo systemctl restart mobruji-discord-bridge` | 30s 후 `systemctl is-active` = `active` + heartbeat ledger 갱신 | escalation |
| mysql connection denied | backend log 에 `Access denied` / `Communications link failure` 5회 이상 (1분 window) | `docker compose restart mysql` → 30s 대기 → backend connection pool refresh (`docker compose restart backend`) | `mysql -e "SELECT 1"` 성공 + backend `/actuator/health` UP | escalation |

#### 11-4-b) 공통 동작

- (개) `--dry-run` flag: command 실행 대신 `[DRY-RUN] would execute: <cmd>` 로그만. 회귀 테스트 + 초기 배포 검증용.
- (개) `--auto-recover` flag (default `False`): 명시적 opt-in. 미설정 시 §11-3 alert push 만, 회복 시도 안 함.
- (개) 회복 시도마다 별 push 발사 (alert push 와 분리):
  - 시작: `🔧 [<위험명>] 회복 시도 중 (<command>)`
  - 성공: `🟢 [<위험명>] 회복 완료 — <검증 결과>`
  - 실패: `🔴 [<위험명>] 회복 실패 — <ERR> @모부르지` (사용자 멘션 포함)
- (개) `recovery_attempts` ledger: 위험별 마지막 회복 시도 ts + 결과. 동일 위험 1시간 내 3회 실패 시 cooldown (이후 1시간 동안 회복 안 함, alert push 만).
- (개) 회복 command 는 sudo 필요 항목 → `/etc/sudoers.d/mobruji-resilience` 별 entry 추가 (구현 PR 에서 명세).

#### 11-4-c) 회복 함수 signature

```python
def attempt_recovery(
    risk_name: str,
    *,
    dry_run: bool = False,
) -> dict:
    """
    returns: {
        "attempted": bool,
        "command": str,
        "stdout": str,
        "stderr": str,
        "recovered": bool,  # 검증 결과
        "escalated": bool,  # 실패 시 멘션 push 여부
    }
    """
```

### 11-5) systemd 통합

#### 11-5-a) trade-off

| 항목 | (a) bot.py 내장 | (b) 별 service (`mobruji-resilience-monitor.service`) |
|---|---|---|
| 의존성 | discord.py (이미 bot 에 있음) | 없음 (subprocess + requests 만) |
| Discord 푸시 | client 인스턴스 직접 사용 (즉시) | webhook URL 호출 (env 필요) |
| 가시성 | Discord 자체 push + bot journalctl | journalctl 단독 |
| crash 영향 | bot down 시 monitor 도 down (단일 실패점) | bot 와 독립 |
| 코드 중복 | 없음 — bot 의 utility 재사용 | 일부 중복 (push helper 등) |
| 배포 복잡도 | bot 재배포 = monitor 재배포 | service unit 별도 관리 + systemd Timer |
| 회복 권한 | bot user (sudoers 필요) | 전용 user (격리 가능, 더 안전) |

#### 11-5-b) 권장: (a) bot.py 내장

- 이유:
  - Discord 푸시가 핵심 가치 — 같은 프로세스에서 client 재사용이 가장 신뢰성 높음 (webhook 실패 케이스 회피).
  - 현재 bot.py 가 이미 `digest_loop` 등 background loop 패턴을 가짐 → `resilience_monitor_loop` 추가는 자연스러움.
  - 배포 단순 (bot 만 관리).
- 단점 보완:
  - bot down 시 monitor 도 down → **secondary backup**: cron 30분 간격 `resilience_check_minimal.sh` (별 PR 로 분리). bot 살아있음 자체를 watchdog 으로 확인하고, dead 면 systemctl restart 시도 + Discord webhook 푸시 (env 필요).
  - secondary 는 임계값 검사 안 함 — 오직 "bot 살아있나" + "디스크 95%+ emergency" 만.

#### 11-5-c) 후속 작업

- 본 PR 범위 외:
  - (a) bot.py 내장 구현은 §11-1~§11-3 + §11-4 구현 PR (be 사이클) 에서 함께.
  - secondary backup cron 은 별 PR (`infra/resilience-secondary-backup-cron`).
  - (b) 옵션이 다시 매력적이 되는 트리거: bot 가 OAuth/discord.py 이슈로 자주 죽거나, 회복 권한을 bot user 에서 분리해야 할 보안 요구가 생기면 재검토.

### 11-6) 회귀 가드

#### 11-6-a) 테스트 프레임워크

- Python `unittest` + `unittest.mock` (`pytest` + `pytest-mock` 도 허용).
- mock 대상:
  - `subprocess.run` (docker / systemctl / mysql / journalctl)
  - `psutil` (디스크 / heap proxy)
  - `requests.get` (`/actuator/health`)
  - `discord.Client.get_channel(...).send` (push)
  - 시계 (`time.time`, `datetime.now`) — ledger cooldown 검증용

#### 11-6-b) 시나리오 매트릭스 (5 위험 × 4 시나리오 = 20 케이스)

| 시나리오 | 기대 동작 |
|---|---|
| 정상 (임계 미만) | active=False, push 없음, 회복 시도 없음 |
| 임계 직전 (경고선) | active=False, push 없음 (임계 ≠ 경고) |
| 임계 초과 (회복 성공) | active=True → alert push → 회복 시도 push → 검증 성공 → 🟢 push → ledger 갱신 |
| 임계 초과 (회복 실패) | active=True → alert push → 회복 시도 push → 검증 실패 → 🔴 escalation push → cooldown ledger |

#### 11-6-c) 위험별 mock 예시

- 디스크: `psutil.disk_usage('/').percent = 92` → `subprocess.run(['docker','system','prune',...])` 호출 검증 → 재호출 시 `percent = 80` 반환 → recovered=True.
- bridge inactive: `subprocess.run(['systemctl','is-active','mobruji-discord-bridge'])` returncode=3 → restart 호출 검증 → 재호출 시 returncode=0 → recovered=True.
- mysql denied: backend log mock 에서 `Access denied` 5회 주입 → mysql restart 호출 검증 → `SELECT 1` mock 성공 → recovered=True.

#### 11-6-d) cooldown 검증

- 동일 위험 1시간 내 3회 회복 실패 → 4번째 cycle 에서 회복 시도 skip (alert push 만), ledger 검증.
- 1시간 경과 후 (시계 mock 으로 +3601s) → 회복 시도 재개 검증.

#### 11-6-e) 의존성

- `backend/` 가 아닌 `tools/discord-daemon/tests/` 하위에 위치 (bot.py 내장 안 — §11-5 권장).
- `requirements-dev.txt` 에 `pytest`, `pytest-mock` 추가.
- CI 게이트: 별 GitHub Actions job (`resilience-tests`) — 본 PR 범위 외, 구현 PR 에서 추가.

### 11-7) 구현 가이드 (be 사이클 입력)

§11-1~§11-6 을 실제 코드로 옮길 때 막힐 가능성이 높은 4개 지점에 대한 보일러플레이트/디렉토리 구조/권한 매핑. 본 절은 spec 이지만 코드 스니펫을 그대로 복붙해도 동작하도록 작성한다.

#### 11-7-a) 디렉토리 구조

`tools/discord-daemon/` 에 신규 서브패키지 `resilience/` 를 둔다. bot.py 의 라인수 증가(현재 850) 를 막고, 회귀 테스트 mock target import path 를 안정화하기 위함.

```
tools/discord-daemon/
├── bot.py                          # resilience_monitor_loop launch 만 추가 (§11-7-b)
├── resilience/
│   ├── __init__.py                 # public API: resilience_monitor_loop, attempt_recovery
│   ├── monitor.py                  # resilience_monitor_loop 본체 (§11-1)
│   ├── checks.py                   # _check_wake_stuck / _check_context_heavy / ... (§11-2)
│   ├── recovery.py                 # attempt_recovery + 회복 매트릭스 (§11-4)
│   ├── state.py                    # JSON state 로드/저장 (atomic write) (§11-1)
│   ├── push.py                     # delta-only push helper + heartbeat (§11-3)
│   └── constants.py                # 임계/타임아웃/메시지 템플릿 — 외부화 (§11-2/§11-3)
└── tests/
    ├── __init__.py
    ├── conftest.py                 # fixture: tmp state_path, mock channel, mock subprocess
    ├── test_checks.py              # §11-6-b 시나리오 매트릭스 (5 × 4 = 20 케이스)
    ├── test_recovery.py            # §11-6-c 위험별 회복 mock
    ├── test_state.py               # ledger 직렬화 / atomic write / cooldown
    └── test_push.py                # delta 판정 / heartbeat / 멘션 포함 여부
```

원칙: **`resilience/` 는 discord.py 에 직접 의존 안 함** — `channel.send` 는 `push.py` 의 `PushSink` 추상화 1단계 뒤로 둔다 (테스트가 Discord client mock 없이 동작 가능). bot.py 만 `discord.Client.get_channel(...)` 으로 `PushSink` 인스턴스를 만들어 inject.

```python
# resilience/push.py
class PushSink(Protocol):
    async def send(self, message: str) -> None: ...

# bot.py 에서
sink = DiscordChannelSink(client.get_channel(notify_channel_id))
client.loop.create_task(resilience_monitor_loop(sink, state_path=...))
```

#### 11-7-b) bot.py 진입점 (digest_loop 패턴 답습)

`digest_loop` 와 동일한 launch 패턴 — `on_ready` 에서 `client.loop.create_task(...)`. env 변수 검증은 `resolve_digest_interval` 처럼 별 함수로 분리해 회귀 테스트 가능하게.

```python
# bot.py (추가 영역만 — 기존 build_client/digest_loop 는 손대지 않음)
RESILIENCE_INTERVAL_SEC_DEFAULT = 300
RESILIENCE_STATE_PATH_DEFAULT = Path.home() / ".claude/state/resilience.json"

def resolve_resilience_interval(env_value: str | None) -> int:
    """RESILIENCE_INTERVAL_SECONDS env. resolve_digest_interval 와 동일 패턴."""
    # digest 와 같은 검증 로직 (정수 / 양수 / 부재 → default).
    ...

def resolve_resilience_auto_recover(env_value: str | None) -> bool:
    """RESILIENCE_AUTO_RECOVER env. truthy string ('1','true','yes') 만 True. 기본 False (§11-4-b opt-in)."""
    ...

# on_ready 핸들러 내부 (digest_loop launch 직후)
if env.get("RESILIENCE_ENABLED", "0") in ("1", "true", "yes"):
    from resilience import resilience_monitor_loop, DiscordChannelSink
    sink = DiscordChannelSink(client.get_channel(target_channel_id))
    client.loop.create_task(
        resilience_monitor_loop(
            sink,
            state_path=Path(env.get("RESILIENCE_STATE_PATH", str(RESILIENCE_STATE_PATH_DEFAULT))),
            interval_sec=resolve_resilience_interval(env.get("RESILIENCE_INTERVAL_SECONDS")),
            auto_recover=resolve_resilience_auto_recover(env.get("RESILIENCE_AUTO_RECOVER")),
            dry_run=env.get("RESILIENCE_DRY_RUN", "0") in ("1", "true", "yes"),
        )
    )
    logger.info("resilience_monitor_loop launched (auto_recover=%s, dry_run=%s)", ...)
```

기본값 = **disabled**. opt-in 명시적 — 초기 배포 시 `RESILIENCE_ENABLED=1 RESILIENCE_DRY_RUN=1` 로 1일 관찰 → `RESILIENCE_DRY_RUN=0` 전환 → 1주 후 `RESILIENCE_AUTO_RECOVER=1`.

#### 11-7-c) sudoers.d/mobruji-resilience whitelist

§11-4-a 의 회복 command 중 sudo 필요 항목만 정확히 화이트리스트. **wildcards 최소화** — `*` 는 mobruji-* 컨테이너에만 한정.

```
# /etc/sudoers.d/mobruji-resilience (0440 root:root)
# bot 이 사용하는 ubuntu user 가 회복 command 만 NOPASSWD 로 실행 가능.
# 본 파일은 systemd setup-gcp-systemd.sh 와 동일한 setup script 에서 visudo -c 검증 후 설치.

# docker compose 제어 (mobruji 프로젝트 디렉토리 한정)
ubuntu ALL=(root) NOPASSWD: /usr/bin/docker compose -f /home/ubuntu/mobruji/docker-compose.yml restart mysql
ubuntu ALL=(root) NOPASSWD: /usr/bin/docker compose -f /home/ubuntu/mobruji/docker-compose.yml restart backend
ubuntu ALL=(root) NOPASSWD: /usr/bin/docker compose -f /home/ubuntu/mobruji/docker-compose.yml up -d mobruji-backend
ubuntu ALL=(root) NOPASSWD: /usr/bin/docker compose -f /home/ubuntu/mobruji/docker-compose.yml up -d mobruji-mysql
ubuntu ALL=(root) NOPASSWD: /usr/bin/docker compose -f /home/ubuntu/mobruji/docker-compose.yml up -d mobruji-discord-bridge

# docker prune (디스크 위험 #1)
ubuntu ALL=(root) NOPASSWD: /usr/bin/docker system prune -af --volumes

# journalctl rotation (디스크 위험 #1)
ubuntu ALL=(root) NOPASSWD: /usr/bin/journalctl --vacuum-time=7d

# systemd 단위 제어 (bridge 한정)
ubuntu ALL=(root) NOPASSWD: /usr/bin/systemctl restart mobruji-discord-bridge
ubuntu ALL=(root) NOPASSWD: /usr/bin/systemctl is-active mobruji-discord-bridge
```

**보안 주의**:
- 절대 `ubuntu ALL=(root) NOPASSWD: /usr/bin/docker *` 같은 broad 룰 금지 — 컨테이너 escape 위험.
- `npm cache clean --force` 는 sudo 불필요 (ubuntu user 권한으로 충분) → whitelist 제외.
- `mysql -e "SELECT 1"` 검증은 docker exec 통해 컨테이너 내부에서 → sudo 불필요.
- 설치 검증: `sudo visudo -c -f /etc/sudoers.d/mobruji-resilience` → exit 0 확인 필수.

#### 11-7-d) 회복 command 실행 helper

`recovery.py` 내부 공통 helper. timeout / dry-run / stdout-stderr 캡처 / Discord push 시점 분리.

```python
# resilience/recovery.py
@dataclass(frozen=True)
class RecoveryResult:
    attempted: bool
    command: list[str]
    returncode: int | None
    stdout: str
    stderr: str
    duration_sec: float
    recovered: bool  # 후속 검증 결과 (caller 가 채움)
    escalated: bool  # 실패 시 사용자 멘션 push 여부

async def run_recovery_command(
    cmd: list[str],
    *,
    timeout_sec: float = 30.0,
    dry_run: bool = False,
) -> RecoveryResult:
    """sudo 포함 command 1건 실행. timeout / 캡처 / dry-run 일원화."""
    if dry_run:
        return RecoveryResult(attempted=False, command=cmd, returncode=None,
                              stdout=f"[DRY-RUN] would execute: {' '.join(cmd)}",
                              stderr="", duration_sec=0.0, recovered=False, escalated=False)
    start = time.monotonic()
    proc = await asyncio.create_subprocess_exec(
        *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout_b, stderr_b = await asyncio.wait_for(proc.communicate(), timeout=timeout_sec)
    except asyncio.TimeoutError:
        proc.kill()
        await proc.wait()
        return RecoveryResult(attempted=True, command=cmd, returncode=None,
                              stdout="", stderr=f"timeout after {timeout_sec}s",
                              duration_sec=time.monotonic() - start, recovered=False, escalated=True)
    return RecoveryResult(
        attempted=True, command=cmd, returncode=proc.returncode,
        stdout=stdout_b.decode("utf-8", errors="replace")[:2000],
        stderr=stderr_b.decode("utf-8", errors="replace")[:2000],
        duration_sec=time.monotonic() - start, recovered=False, escalated=False,
    )
```

#### 11-7-e) unittest 보일러플레이트

`conftest.py` 1개 + 시나리오 1건만 spec 에 둔다. 나머지 19건은 같은 패턴 반복 — be 사이클이 작성.

```python
# tests/conftest.py
import json
from pathlib import Path
from unittest.mock import AsyncMock
import pytest

@pytest.fixture
def state_path(tmp_path: Path) -> Path:
    return tmp_path / "resilience.json"

@pytest.fixture
def fresh_state(state_path: Path) -> dict:
    state = {
        "schema_version": 1,
        "last_heartbeat_ts": 0.0,
        "risks": {
            "wake_stuck":          {"last_triggered_ts": 0, "last_recovered_ts": 0, "active": False},
            "context_heavy":       {"last_triggered_ts": 0, "last_recovered_ts": 0, "active": False},
            "oauth_conflict":      {"last_triggered_ts": 0, "last_recovered_ts": 0, "active": False},
            "claude_json_corrupt": {"last_triggered_ts": 0, "last_recovered_ts": 0, "active": False},
        },
    }
    state_path.write_text(json.dumps(state))
    return state

@pytest.fixture
def mock_sink():
    sink = AsyncMock()
    sink.send = AsyncMock(return_value=None)
    return sink

@pytest.fixture
def mock_subprocess(monkeypatch):
    """asyncio.create_subprocess_exec 를 mock. tests 마다 returncode/stdout 주입."""
    mock = AsyncMock()
    monkeypatch.setattr("asyncio.create_subprocess_exec", mock)
    return mock
```

```python
# tests/test_checks.py (1건만 예시)
import pytest
from resilience.checks import check_wake_stuck

@pytest.mark.asyncio
async def test_wake_stuck_under_threshold_no_push(mock_sink, fresh_state, monkeypatch):
    """given 마지막 wake 30분 전 (임계 90분 미만) when check 실행 then push 없음 + state 불변."""
    monkeypatch.setattr("resilience.checks._extract_last_wake_ts",
                        AsyncMock(return_value=time.time() - 1800))
    result = await check_wake_stuck(mock_sink, state=fresh_state, dry_run=True)
    assert result.active is False
    mock_sink.send.assert_not_awaited()
```

#### 11-7-f) docker / subprocess mock 패턴

§11-6-c 의 mock 예시를 unittest 코드로 1:1 매핑. 핵심: `asyncio.create_subprocess_exec` 가 반환하는 proc 객체의 `communicate()` 를 AsyncMock 으로 교체.

```python
# tests/test_recovery.py 일부
@pytest.mark.asyncio
async def test_docker_prune_success(mock_subprocess):
    """given disk_pct=92 when docker prune when 재측정 80 then recovered=True."""
    proc = AsyncMock()
    proc.communicate = AsyncMock(return_value=(b"reclaimed 12GB", b""))
    proc.returncode = 0
    mock_subprocess.return_value = proc

    result = await run_recovery_command(
        ["sudo", "/usr/bin/docker", "system", "prune", "-af", "--volumes"],
        timeout_sec=60.0,
    )
    assert result.attempted is True
    assert result.returncode == 0
    assert "reclaimed" in result.stdout
```

#### 11-7-g) 구현 PR 분할 권장

§11-4~§11-6 + §11-7 을 단일 PR 로 하면 600~800 LOC. 다음 3개로 분할 권장:

1. **PR B-1**: `resilience/` 디렉토리 + `state.py` + `push.py` + `monitor.py` 골격 + `_check_wake_stuck` 1건 + 회복 X (alert push 만). 회귀 테스트 5건. **~250 LOC.**
2. **PR B-2**: 나머지 4개 check 함수 + heartbeat + cooldown ledger. 회귀 테스트 15건 추가. **~300 LOC.**
3. **PR B-3**: `recovery.py` + sudoers.d 파일 + setup script 갱신 + `RESILIENCE_AUTO_RECOVER` env. 회귀 테스트 회복 매트릭스 20건. **~350 LOC.**

각 PR 라벨: `type:feat` `scope:infra` `ai-generated` `needs-human-review` (sudoers / docker compose 변경 → 보호 영역).

#### 11-7-h) 구현 시 막힐 가능성이 높은 지점 사전 답변

| 막힘 | 해결 |
|---|---|
| `tmux capture-pane` 가 maestro 외 다른 user 의 세션을 보지 못함 | bot 가 `ubuntu` user 로 실행 — maestro tmux 도 `ubuntu` 소유면 OK. 다른 user 면 `sudo -u <maestro_user> tmux capture-pane ...` 필요 → sudoers 추가. |
| `psutil.virtual_memory()` 가 컨테이너 내부 메모리만 반환 | bot 가 host 에서 실행 (systemd) 이므로 host 메모리 정상 측정. heap_pct 는 backend `/actuator/metrics/jvm.memory.used` 로 별도 측정 (docker exec 불필요). |
| state 파일 race (digest_loop 와 동시 write) | `digest_loop` 는 state 파일 안 씀 — 충돌 없음. 단일 task 단일 writer 보장. |
| `discord.Client.get_channel` 이 `on_ready` 이전엔 None 반환 | `on_ready` 핸들러 내부에서만 launch (digest_loop 와 동일) — None 보장 안 됨. |
| dry-run 으로 1일 관찰 시 false alarm 분류 | push 본문에 `[DRY-RUN]` prefix 추가 → 사용자가 silent 학습 후 enabled 전환. |
