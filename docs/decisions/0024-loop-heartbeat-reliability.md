---
id: 0024
title: Loop heartbeat reliability — `try/finally` 단일 종점 record 패턴
status: proposed
date: 2026-05-28
deciders: [@goohong]
---

# 0024. Loop heartbeat reliability — `try/finally` 단일 종점 record 패턴

## Context

`bot.py` watchdog 패밀리 (#1087) 는 모든 polling `*_loop` 함수가 매 iter 끝에
`record_loop_heartbeat(<name>)` 를 호출한다는 전제 위에 설계되었으나, 현 시점
8 loop 중 4 loop 가 조건 분기 `continue` / `return` 으로 record 호출을 skip
하거나 try 블록 안에 record 를 두어 `except` 발생 시 skip 하는 패턴을 보유한다
(상세: `docs/features/loop-heartbeat-reliability.md §5-1`).

결과적으로 daemon 운영 phase 에서도 watchdog 가 false-positive `🚨 loop
heartbeat stale` 를 DIGEST 채널에 push 해 사용자 / rev sub-agent 가 incident 로
오인하는 사고가 박제됨 (PR #1215 §8 Q1 후속). 신규 `*_loop` 추가 시 같은 사고
재발을 막기 위한 **표준 패턴** 이 필요하다.

## Decision

모든 bot.py `async def *_loop` 함수는 다음 단일 종점 record 패턴을 따른다:

```python
async def example_loop(*args, **kwargs) -> None:
    if poll_interval <= 0:
        logger.info("example_loop disabled (poll_interval<=0)")
        return
    await sleeper(initial_delay)
    while True:
        try:
            # … iter 본체 (continue 자유) …
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("example_loop iter 실패: %s", exc)
        finally:
            record_loop_heartbeat("example_loop")
        await sleeper(poll_interval)
```

핵심 룰:

1. **record 위치 = `finally` 안**. 모든 분기 (`try` 본체 / `continue` / `except`)
   에서 record 호출이 보장됨.
2. **sleep 위치 = `try/finally` 밖**. record 완료 후 다음 iter 진입 보장.
3. **`asyncio.CancelledError` 는 외부 전파**. finally 가 마지막 record 1회 보장.
4. **신규 `*_loop` 추가 시 동일 패턴 강제**. `tests/test_bot_loop_heartbeat_pattern.py`
   ast walk 회귀 가드 (선택 — Q1).

본 ADR 의 적용 단위는 `tools/discord-daemon/bot.py` 만. 다른 모듈의 async
loop 는 watchdog 등록 안 되어 있으므로 본 ADR 대상 아님.

## Consequences

### 긍정적
- DIGEST `🚨 loop heartbeat stale` push 가 진짜 incident 일 때만 발화 — 신뢰성
  회복.
- 신규 loop 추가 시 표준 패턴 학습 비용 미미 (Python 관용 try/finally).
- ast walk 회귀 가드로 패턴 위반 자동 검출 가능 (선택).
- except 도 cover — context_auto_clear_loop 류 silent record skip 일소.

### 부정적
- 기존 4 loop refactor 필요 (~30줄). 후속 be impl PR 1건.
- try/finally + sleep 위치 학습 비용 (sub-agent 메모리 추가). 그러나 ADR 박제
  + `12-template` 한 줄 추가로 회수 가능.
- `cycle_idle_watch_loop` 처럼 sleep 위치가 try 본체 첫 줄인 loop 는 구조
  조정 필요 — spec Q3 결정 후 적용.

## Alternatives (considered)

- **(A) 매 iter unconditional record (모든 `continue` 전 명시 호출)** — 명시적이나
  `continue` 위치가 늘어나면 누락 risk. `try/finally` 가 구조적 강제.
- **(B) record 책임을 wrapper 로 분리 (`async def _heartbeat_wrap(name, body)`)** —
  bot.py 대수술 + 기존 pytest 자산 영향 큼. 본 spec 목적 = 신뢰성 회복, 구조
  변경 X.
- **(C) `record_loop_heartbeat` 데코레이터 / context manager** — Python
  `continue` 가 함수 경계 못 넘어 `with` 안 보장 불완전. `try/finally` 와 의미상
  동치.
- **(D) 현행 유지 + watchdog `reason="missing"` 과 `reason="stale"` 분리만** —
  근본 원인 (record 누락) 유지. 부분 개선 — `docs/features/heartbeat-watchdog-initial-delay.md §8 Q2`
  로 후속 spec 분리 가능.

## References

- `docs/features/loop-heartbeat-reliability.md` — 본 ADR 의 짝 feature spec
  (회귀 시퀀스 / 옵션 비교 / loop 별 변경 diff / pytest 회귀 가드)
- `docs/features/heartbeat-watchdog-initial-delay.md` — PR #1215, `initial_delay`
  옵션 D (420s) 권장 — 부팅 윈도우 false-positive 가림 (본 ADR 은 운영 phase 까지 cover)
- 이슈 #1087 — loop heartbeat hook + heartbeat_watch_loop 신설 (#971 회귀 fix)
- PR #1215 — `docs/features/heartbeat-watchdog-initial-delay.md` 본문 §8 Q1 ("record on continue skip" 리팩토링) 후속
- `tools/discord-daemon/bot.py` L440-486 — `record_loop_heartbeat` 함수 정의
- `tools/discord-daemon/bot.py` L415-426 — `LOOP_HEARTBEAT_EXPECTED_INTERVALS`
- `docs/ai-harness/actors/sub-agent.md` §1-3 — sub-agent 검증 의무 ([[feedback-verify-and-iterate]])
