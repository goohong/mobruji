---
id: 0016
title: maestro context% 추정 방식 — `/context` slash + bot.py 5분 inject hybrid
status: accepted
date: 2026-05-23
deciders: [@goohong]
---

# 0016. maestro context% 추정 방식 — `/context` slash + bot.py 5분 inject hybrid

## Context

PR #772 (CLAUDE.md §11) 에서 maestro 본진이 매 turn 마지막에 `===CTX:NN%===` marker 를 emit 하고 bot.py `context_auto_clear_loop` (spec: `docs/features/context-auto-clear.md`) 가 이를 polling 하여 95% 임계 시 자율 정리를 트리거하는 룰이 채택되었다. CLAUDE.md §11 "추정 방법" 은 다음 3-step fallback 만 1줄로 명시한다:

1. `/context` slash 결과를 직전에 본 경우 → 그 수치 사용
2. 본인이 알고 있는 input/output token 누적 ÷ 1M window × 100
3. 둘 다 모르면 `===CTX:?===`

그러나 **정확도/오버헤드/실현 가능성**에 대한 결정 근거는 §11 본문에 없다. 특히 (2) "본인이 알고 있는 token 누적" 은 Claude Code TUI 가 turn 마다 token usage 를 본진 stdout 에 노출하지 않으므로 사실상 불가능에 가까우며, (3) "모르면 ?" 만으로는 bot.py auto-clear 가 영구 작동 안 할 수 있다. 본 ADR 은 §11 "추정 방법" 의 운영 실체를 확정한다.

`feature/context-auto-clear.md §5-6` 실측(2026-05-23, #760) 에서 footer/status-bar scrape 가 불가함이 이미 확인되어, 측정 정확도를 maestro 자가 emit 정확성에 위임한 상태다. 따라서 maestro 가 `===CTX:NN%===` 의 `NN` 을 어떻게 얻는지가 본 ADR 의 단일 결정 사항이다.

## Decision

**옵션 A (slash 직접 호출 cache) + 옵션 D (bot.py 5분 정기 inject) hybrid** 를 채택한다.

1. **maestro turn 종료 시 cache 사용**: maestro 는 자기 transcript 에 마지막으로 등장한 `/context` slash 결과(예: `Context: 73% used`)를 직접 읽어 `===CTX:73%===` 형식으로 emit. cache 가 한 시간 이상 오래된 경우 `===CTX:?===` 로 fallback 하며 turn 끝낸다.
2. **bot.py 가 5분 간격 `/context` 자동 inject**: `context_auto_clear_loop` (PR #776) 에 `/context refresh sub-loop` 추가. 5분 cooldown 으로 `tmux send-keys -t mobruji:0.0 "/context" Enter` 를 보내 maestro 가 turn 직후 자기 cache 를 갱신할 수 있게 한다. **maestro 작업 중 (sub-agent 대기 중) 에는 inject 보류** — pane tail 마지막 줄이 maestro prompt 입력 대기(`▌` cursor) 인지 확인 후만 inject.
3. **maestro 본인이 `/context` 직접 호출 금지** — 매 turn 호출 시 1 tool-call overhead + cost. 본진은 cache 만 읽는다. 갱신은 bot.py 책임.

이 방식은 CLAUDE.md §11 "추정 방법" 항목 (1) 을 운영 정확도의 단일 출처로 만들고, (2) "token 누적 자가 계산" 은 폐기, (3) "모르면 ?" 은 bot.py 미작동 시 fallback 으로만 둔다.

### 적용 범위

- maestro 본진 (`tmux mobruji:0.0`) 한정. sub-agent 는 marker emit 안 함 (§11 명시).
- bot.py 신규 task `context_refresh_loop` 는 `context_auto_clear_loop` 와 같은 `.env` 토글 (`CONTEXT_AUTO_CLEAR_ENABLED=1`) 로 함께 on/off.

## Consequences

### 긍정적

- **정확도 ≥ 95%**: `/context` slash 는 Claude Code 가 직접 출력하는 공식 수치. 추정 오차 거의 없음.
- **maestro turn overhead 0**: cache 읽기만 — turn 당 별 tool-call 없음. emit 1줄만 추가.
- **PR #776 구현 단순화**: bot.py 가 inject 책임 단일 보유 → cron-style 5분 loop 만 추가하면 됨. maestro 측 룰 변경 없음.
- **fallback 명확**: bot.py 죽거나 cache 오래되면 `===CTX:?===` → bot.py auto-clear trigger 안 함 → 사용자 부재 시 95% 도달해도 무사고 (정리 안 되는 것 뿐).

### 부정적

- **5분 cache stale**: 5분 사이 sub-agent 폭주로 50% → 95% 점프 시 다음 inject 까지 정리 트리거 지연. 실측 95% 도달 시 maestro 가 추가 emit 못 함 (cache=마지막 /context 값). 임계가 무거우면 inject 간격 1분으로 단축.
- **inject 타이밍 충돌 위험**: maestro 가 prompt 입력 중인데 bot.py 가 `/context` 보내면 입력 끊김. 따라서 §Decision 2 의 "prompt 입력 대기 cursor 확인" 가드 필수. 가드 실패 시 maestro turn 손상 가능 — PR F 단위 테스트로 cursor regex 검증.
- **5분 추가 transcript 누적**: `/context` 응답이 매 5분 maestro pane 에 쌓임 → context% 자체에 미세 기여 (≈ 0.1%/회 추정). 1시간 12회 = 1.2%. 95% 임계 도달 자체를 가속하나 무시 수준.
- **bot.py 5분 loop 의존성 추가**: bot.py 가 안정성 회귀 시 cache 갱신 안 됨 → `===CTX:?===` 만 emit → auto-clear 무력화. bot.py 자체 health check 가 ADR-0015 운영 룰로 분리 필요 (후속).

## Alternatives (considered)

- **(A) `/context` slash 매 turn 호출** — maestro 가 매 turn 자기 `/context` 직접 호출 후 결과 파싱.
  - 정확도 최상. 단 1 tool-call/turn 오버헤드. 대화량 많은 본진에서 누적 비용/지연 큼.
  - 거절: turn 당 cost ↑, 본진 응답 latency ↑.
- **(B) maestro 자기 token 누적 계산** — Claude API response usage (input/output token) 를 본진이 추적해 ÷ 1M.
  - 거절: Claude Code TUI 가 turn 별 token usage 를 본진 stdout 에 노출하지 않음. 본진이 자기 API response 를 raw 로 받지 못함. 사실상 구현 불가.
- **(C) heuristic (conversation 길이 + tool call 갯수 weighted)** — 본진이 자기 transcript 라인 수 / tool call 갯수 로 추정.
  - 거절: 부정확. tool result 크기 (예: 큰 파일 Read 1회 vs grep 50회) 가중치 자가 산정 불가. ±15%p 오차 예상 → 95% 임계 판정 신뢰성 없음.
- **(D) bot.py 5분 `/context` inject (단독)** — maestro 측 cache 없이 bot.py 가 직접 `/context` 결과 화면 scrape.
  - 거절: scrape 패턴 (Claude Code TUI 응답 화면 형식) 별도 검증 필요 + context-auto-clear.md §5-6 의 "footer scrape 불가" 와 같은 위험. 본진이 cache 만 emit 하는 게 단순.
- **(A+D) hybrid — 채택**: maestro cache emit + bot.py 5분 갱신. (A) 정확도 + (D) overhead 분산 의 최선 조합.

## References

- Feature Spec: [`docs/features/context-auto-clear.md`](../features/context-auto-clear.md) (§5-6 footer-scrape 폐기 + §5-2 `context_auto_clear_loop` 구조).
- CLAUDE.md §11 "context 사용량 자기 emit" (PR #772) — 본 ADR 이 §11 "추정 방법" 의 운영 실체를 확정.
- 선행 PR: #760 (footer 실측), #772 (CLAUDE.md §11 룰), #776 (`context_auto_clear_loop` 구현).
- 후속: be 사이클이 PR F 로 옵션 D (`context_refresh_loop` 5분 inject + cursor 가드 + 테스트) 구현. PR C/D (auto-clear 본체) 는 본 ADR 머지 후 kickoff.
- 관련 ADR: ADR-0015 (NCP maestro VM — bot.py 실행 호스트).
