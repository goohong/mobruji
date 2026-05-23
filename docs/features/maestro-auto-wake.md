---
feature: maestro 자동 wake 사이클 (idle 시 self-perpetuating + secondary backup)
slug: maestro-auto-wake
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: [#381]
related_prs: []
last_reviewed: 2026-05-23
---

# maestro 자동 wake 사이클 (idle perpetuating + secondary backup)

## 1) 개요 (What / Why)
NCP maestro가 사용자 부재 또는 idle 상태에서 컨텍스트/가시성을 lost하지 않도록 **ScheduleWakeup 도구로 60분마다 self-wake** 한다. wake 시 표준 점검 5개 → 변경 발견 시 Discord push → 다시 wake 잡기 (perpetuating).

문제: ScheduleWakeup primary가 죽거나 (maestro process crash, OAuth 만료, Anthropic API quota) 호출 안 되면 사이클 멈춤. → **secondary backup** 필요: bot.py 또는 별 cron이 primary wake 마지막 timestamp 모니터 + 임계 초과 시 Discord alert.

본 spec은 [[feedback-autonomous-wake-pattern]] 메모리를 spec으로 정형화 + secondary backup 메커니즘 명시.

## 2) 사용자 시나리오
- **(S1) 사용자 부재 6시간**: 잠/외출 동안 NCP maestro가 매 시간 점검 → 변경 (PR 머지, ACG open, container down) 발견 시 채널 1줄 push. 사용자 깨면 채널에서 누적 진척 확인.
- **(S2) primary wake 죽음**: maestro OAuth 만료로 wake 실패. bot.py가 5분마다 maestro 메모리의 `last_wake_timestamp` 확인 → 90분+ 무 변경 감지 → Discord push "🚨 maestro wake stuck — 마지막 활동 N분 전. SSH 확인 권장."
- **(S3) 사용자 명시적 중단**: "stop wake" 메시지 → maestro가 다음 ScheduleWakeup 안 잡고 종료.

## 3) 요구사항
### 기능 요구사항
- [ ] **Primary wake**: maestro 호흡 끝 idle 시 `ScheduleWakeup(delaySeconds=3600, prompt=<self-perpetuating wake prompt>)` 호출.
- [ ] **wake prompt 표준화** (다음 wake 시 user message로 실행되는 본문):
  - 점검 5개 — container ps / bridge active / 새 PR 머지 / ACG open / 메모리
  - 변경 발견 시 `discord-reply.yml dispatch`로 1줄 push
  - 마지막에 ScheduleWakeup 재호출 (perpetuating)
- [ ] **last_wake_timestamp 기록**: 매 wake 마다 메모리 `project-maestro-wake-log.md`에 `{ts, observations, push_sent}` 한 줄 append.
- [ ] **Secondary backup (bot.py 통합)**: bot.py digest_loop 안 또는 별 task가 5분마다 `last_wake_timestamp` 읽음. 현재 시각 - last > 90분이면 Discord push (1회만, 회복 시 다시 silent).
- [ ] **사용자 중단 hook**: 채널에 `/stop wake` 또는 `/pause wake` 메시지 시 bot.py가 maestro 메모리에 `wake_paused=true` flag 기록. maestro wake 호출 직전 flag 확인 → 잡지 않고 종료.

### 비기능 요구사항
- **결정성**: 점검 5개는 LLM 호출 없이 shell command만. wake 마다 비용 < $0.01.
- **delay clamp**: ScheduleWakeup max 3600s (60분). 사용자가 60~120분 의도했으나 시스템 제약. 두 번 연속 wake로 2시간 cadence 효과 가능.
- **cache window**: 60분 wake는 5분 cache TTL 넘김 — cache miss 1회 비용 수용 (사용자가 명시한 cadence).
- **silent on no change**: 점검 결과 동일하면 push X. 노이즈 최소.
- **secondary alert 회복 신호**: 90분+ stuck 알람 후 wake 재개 감지 시 1회 "🟢 maestro wake 회복" push.

## 4) 범위 / 비범위
### 포함
- Primary self-wake (maestro ScheduleWakeup)
- Secondary backup (bot.py 또는 systemd timer)
- wake log 메모리
- 사용자 중단 hook

### 제외 (Out of Scope)
- **수동 wake 트리거** — Discord에서 `/wake now` 명령 가능 (양방향 spec `discord-driven-mobruji.md` §4에 포함된 명령 확장)이지만 본 spec OOS. 후속.
- **multi-region resilience** — NCP VM 자체 down 시는 [[ncp-maestro-resilience]] spec이 담당.
- **wake 주기 동적 조정** — 활동 빈도에 따라 30~120분 자동 조정. v0.4 후 도입.

## 5) 설계
### 5-1) 흐름 다이어그램
```
사용자 메시지/지시
  ↓
maestro 작업 처리
  ↓
idle 도달
  ↓
ScheduleWakeup(3600s, prompt=<wake-cycle-prompt>)
  ↓ (60분)
wake 발사 → maestro re-invoke with wake-cycle-prompt
  ├─ 점검 5개 (shell only)
  ├─ wake log 메모리 append
  ├─ 변경 시 discord-reply.yml dispatch
  └─ ScheduleWakeup(3600s, 같은 prompt)  # 재 잡기
  ↓ (60분)
... 반복

(병행) bot.py digest_loop 5분 간격
  ├─ last_wake_timestamp 읽기
  ├─ now - last > 90분이면 stuck alert 1회 push
  └─ stuck 회복 시 1회 회복 push
```

### 5-2) wake-cycle-prompt 표준
다음 wake 시 user message로 실행될 본문:

```
자율 wake 사이클 (maestro-auto-wake.md). 점검 5개 → 변경 시 push → 재 wake.

표준 점검:
1. sudo docker compose -f /home/mobruji/mobruji/docker-compose.dev.yml --env-file /home/mobruji/mobruji/.env.dev ps
2. systemctl is-active mobruji-discord-bridge.service
3. gh pr list --state merged --limit 5 --json number,title,mergedAt
4. curl -m 5 -o /dev/null -w "%{http_code}\n" http://101.79.20.94/_nginx_health
5. free -h | head -2

wake log: ~/.claude/projects/-home-mobruji-mobruji/memory/project_maestro_wake_log.md 에 한 줄 append
변경 발견 시 gh workflow run discord-reply.yml -f message="..." 1줄 push.
마지막에 ScheduleWakeup 재호출 (3600s + 같은 prompt). 자율 진행, bypass.

중단 신호: ~/.mobruji/wake-paused 파일 존재 시 재 wake 안 잡고 종료.
```

### 5-3) wake log 메모리 구조
파일: `project_maestro_wake_log.md`

```markdown
---
name: project-maestro-wake-log
description: maestro 자동 wake 사이클 결과 누적 (last_wake_timestamp + 점검 결과 한 줄)
metadata:
  type: project
---

# 자동 wake log (역시간순, 최신이 위)

| timestamp | container | bridge | merged PR | ACG | memory | push |
|---|---|---|---|---|---|---|
| 2026-05-23T09:32+09 | 4/4 healthy | active | (변경 X) | 000 | 1.6G | silent |
| 2026-05-23T08:32+09 | 4/4 healthy | active | #380 | 000 | 1.6G | "✅ #380 merged" |

자동 wake 외 다른 변경 (사용자 메시지 처리)은 본 log에 기록하지 않음. wake 사이클 점검 결과만.
```

마지막 50줄만 유지 (오래된 줄은 GC). 다음 wake가 이전 row와 비교해 변경 감지.

### 5-4) Secondary backup (bot.py 통합)
`bot.py` 에 신규 함수 + asyncio task:

```python
WAKE_STUCK_THRESHOLD_SECONDS: Final[int] = 5400  # 90분 (60분 cadence + 안전마진)
WAKE_LOG_PATH: Final[Path] = Path("~/.claude/projects/-home-mobruji-mobruji/memory/project_maestro_wake_log.md").expanduser()

def parse_last_wake_timestamp() -> datetime | None:
    """wake log 의 가장 최근 row 의 timestamp."""
    ...

async def wake_health_monitor_loop(client, channel_id):
    """5분 마다 last_wake_timestamp 점검. 90분+ 무 변경 시 1회 alert."""
    stuck_alerted = False
    while True:
        await asyncio.sleep(300)
        last = parse_last_wake_timestamp()
        if last is None:
            continue
        age_seconds = (datetime.now(timezone.utc) - last).total_seconds()
        if age_seconds > WAKE_STUCK_THRESHOLD_SECONDS:
            if not stuck_alerted:
                await channel.send(f"🚨 maestro wake stuck — 마지막 활동 {int(age_seconds/60)}분 전")
                stuck_alerted = True
        else:
            if stuck_alerted:
                await channel.send("🟢 maestro wake 회복")
                stuck_alerted = False
```

토글: `.env` 의 `WAKE_HEALTH_MONITOR_ENABLED=1` (default 0 — opt-in, digest 와 같은 패턴).

### 5-5) 사용자 중단 hook
사용자가 채널에 `/stop wake` 보내면:
- bot.py on_message에서 `/stop wake` prefix 감지
- `touch ~/.mobruji/wake-paused` 파일 생성
- 채널에 ack "⏸️ wake paused — 재개는 `/start wake`"

maestro wake 사이클 prompt 안 마지막에:
```bash
if [ -f ~/.mobruji/wake-paused ]; then
    echo "wake paused — ScheduleWakeup 미잡음"
else
    # ScheduleWakeup 재호출
fi
```

## 6) 작업 분할 (PR 리스트)
- [x] **PR A (본 PR)**: spec 신설 + memory `feedback-autonomous-wake-pattern` cross-ref
- [ ] **PR B**: `project_maestro_wake_log.md` 메모리 초기화 + 첫 wake 시 append 로직 (maestro 자체 작업 — 본 spec 머지 후 다음 wake 사이클부터)
- [ ] **PR C**: `bot.py` 에 `wake_health_monitor_loop` + `parse_last_wake_timestamp` 추가 + `.env.example` 에 `WAKE_HEALTH_MONITOR_ENABLED` 추가 + test_bot.py
- [ ] **PR D**: `bot.py` on_message 에 `/stop wake` + `/start wake` 핸들러 + test_bot.py

## 7) 테스트 전략
- **PR A**: 문서 spec only.
- **PR C**: `parse_last_wake_timestamp` 단위 (markdown row 파싱 / 빈 파일 / 잘못된 형식). `wake_health_monitor_loop` asyncio mock (FakeChannel + 시각 조작 → stuck/회복 알림 시퀀스).
- **PR D**: `/stop wake` prefix → wake-paused 파일 생성 검증.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | secondary backup을 bot.py 안에 두나, 별 systemd timer? | (a) bot.py 통합 (단일 process) / (b) systemd timer + shell script | @user / PR C 시작 시 |
| Q2 | wake 주기 60분 고정 vs 활동 빈도 적응 | (a) 60분 고정 (v0.3) / (b) 30~120분 동적 (v0.4) | @user / 1주 운영 데이터 |
| Q3 | wake 시 LLM 호출 비용 누적 무시 가능한가? | wake 60회/일 × $0.01 = $0.6/일 = $18/월 — 임계 | @user / 매주 비용 리뷰 |

## 9) 결정 로그
- 2026-05-23: 초안 작성 + 즉시 운영 적용 (status=approved). 사용자 위임 — auto-wake 정형화. ScheduleWakeup max 3600s 제약 수용 (clamp). secondary backup은 bot.py 통합 (PR C 후속) — single process, 운영 부담 최소. 사용자 중단 hook은 `/stop wake` Discord prefix.
