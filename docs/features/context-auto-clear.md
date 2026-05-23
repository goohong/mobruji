---
feature: maestro/helper context auto-clear 자동화 (95% 임계 → 자율 핸드오프 + /clear)
slug: context-auto-clear
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-24
---

# maestro/helper context auto-clear 자동화 (95% 임계 → 자율 핸드오프 + /clear)

## 1) 개요 (What / Why)
polling 대상 tmux pane (nmae `mobruji:0.0`, helper `helper:0.0`) 이 컨텍스트 사용량 95%에 도달하면 **bot.py가 해당 pane에 핸드오프 정리 prompt를 inject**하고, 그 안의 Claude 세션이 정리를 마치고 `===CLEAR_READY===` marker를 stdout으로 출력하면 **bot.py가 `tmux send-keys "/clear" Enter`로 컨텍스트를 비운다**. 다음 wake 시 첫 turn에서 MEMORY.md 자동 로드 → 자율 회복.

문제: v6 사이클에서 컨텍스트 97% 도달 후 maestro가 사실상 멈춤. 사용자가 부재이면 회복 불가. 기존 `maestro-auto-wake` (60분 wake) 와는 다른 axis — wake는 idle 회복, 본 spec은 **context 포화 회복**.

[[feedback-autonomous-wake-pattern]] + [[project-session-handoff-2026-05-23-v6]] 메모리에 명시된 위험을 spec으로 정형화.

## 2) 사용자 시나리오
- **(S1) 사용자 부재 + maestro 폭주 사이클**: 6시간 동안 다중 sub-agent launch + PR 머지 + Discord push 누적. context 95% 도달 → bot.py inject "🧠 정리 prompt". maestro가 sub-agent 완료 대기 + 핸드오프 메모리 갱신 + marker emit → bot.py `/clear` 전송 → 다음 wake 시 자율 복귀. 사용자 깨면 채널에 "🧹 컨텍스트 정리 완료 (v7 핸드오프 작성)" 한 줄만 보임.
- **(S2) 정리 도중 sub-agent 미완료**: 95% 트리거 시 fe sub-agent 진행 중. maestro는 sub-agent 완료 대기 → 완료 후 핸드오프 정리 → marker. bot.py는 marker 도착까지 timeout (예: 30분) 없이 대기 (sub-agent 잃기 방지).
- **(S3) hysteresis 미작동 폭주 방지**: /clear 직후 컨텍스트 5% → 곧 다시 사용량 증가. 95% 재도달 시 정상 트리거, 80% 이하로 떨어진 적 없으면 트리거 안 함 (debounce).

## 3) 요구사항
### 기능 요구사항
- [ ] **컨텍스트 사용량 polling (multi-pane)**: bot.py가 설정된 모든 polling pane (default: nmae `mobruji:0.0` + helper `helper:0.0`) 의 stdout (`tmux capture-pane -t <pane> -p`) 을 5~10초 간격 tail. pane 별 독립 state 추적. marker 패턴 `===CTX:NN%===` (§5-6 옵션 A) 파싱.
- [ ] **95% 트리거 + hysteresis (pane 별 독립)**: 한 pane 의 사용량 ≥ 95% 도달 시 1회 trigger. 트리거 후 사용량 ≤ 80% 떨어질 때까지 재트리거 차단 (pane 별 debounce flag).
- [ ] **inject prompt 표준 (pane 무관 공통)**:
  ```
  🧠 컨텍스트 95% 도달. 다음 절차로 자율 정리하라:
  1. 진행 중 작업 모두 완료 대기 (sub-agent launch / 새 요청 추가 금지)
  2. 다음 핸드오프 메모리 `project_session_handoff_<YYYY-MM-DD>_v<N+1>.md` 작성:
     - 사이클 카운트 / 진행 중 PR / 사용자 결정 대기 / 첫 액션
  3. MEMORY.md 인덱스에 새 핸드오프 한 줄 추가
  4. 정리 완료 후 stdout 에 marker 출력: `===CLEAR_READY===`
  5. 그 후 정지 (ScheduleWakeup 재호출 안 함 — /clear 후 다음 wake가 처리)
  ```
  bot.py가 `tmux send-keys -t <pane> "<위 prompt>" Enter` 로 inject. "진행 중 작업" 은 nmae(sub-agent)·helper(현재 turn) 양쪽 케이스를 포괄.
- [ ] **marker 감지 → /clear 전송 (pane 별)**: bot.py 가 각 polling pane 의 `tmux capture-pane` tail 에서 `===CLEAR_READY===` 발견 시:
  - Discord 채널에 push "🧹 `<pane>` 컨텍스트 정리 완료. /clear 전송."
  - `tmux send-keys -t <pane> "/clear" Enter`
  - 해당 pane 의 debounce flag 는 사용량 80% 이하 자연 falloff 까지 유지
- [ ] **자율 wake 재개**: /clear 후 해당 pane Claude 는 빈 컨텍스트. 첫 turn 시 CLAUDE.md + MEMORY.md auto-load (Claude Code 기본 동작) → 최신 핸드오프 메모리 Read → 진행 재개.
- [ ] **사용자 중단 hook**: 채널 `/stop autoclear` → `.mobruji/autoclear-paused` 파일 → bot.py polling 시 flag 확인하면 모든 pane 에 대해 inject 안 함. `/start autoclear` 로 재개.

### 비기능 요구사항
- **결정성**: 95% 정확 트리거 (off-by-one 금지, ≥ 95 임계). hysteresis 80% 이하 재방문 필수. 같은 96% 유지 상태에서 한 사이클당 1회만 trigger.
- **관측성**: trigger / marker 감지 / /clear 전송 / wake 회복 4단계 모두 Discord 채널 1줄 push. wake log 별 (`project_context_clear_log.md`) 에 append (timestamp / context% / handoff file / duration).
- **안전성 (sub-agent 보호)**: marker 도착까지 무제한 대기 (sub-agent 완료가 우선). bot.py는 timeout 두지 않음. 사용자가 명시적으로 `/force clear` 시에만 강제 /clear (sub-agent 잃기 수용).
- **응답시간**: polling 5s 간격. 95% → inject 5s 이내. inject → marker는 maestro 정리 시간 (5~15분 예상). marker → /clear 1s 이내.
- **설정 외부화**: `.env` 토글 — `CONTEXT_AUTO_CLEAR_ENABLED=1` (default 0, opt-in. `WAKE_HEALTH_MONITOR_ENABLED` 와 같은 패턴). 임계값 (`CONTEXT_CLEAR_TRIGGER_PCT=95`, `CONTEXT_CLEAR_HYSTERESIS_PCT=80`) 도 env.
- **무한 루프 방지**: hysteresis (80% 이하 떨어진 적 있어야 재트리거). 같은 사이클 (1 wake 주기) 내 2회 이상 트리거 시 alert push.

## 4) 범위 / 비범위
### 포함
- bot.py 에 multi-pane polling loop + pane 별 독립 state + inject + marker 감지 + /clear send-keys
- polling 대상 pane: nmae `mobruji:0.0` + helper `helper:0.0` (default). `TMUX_PANE_TARGETS` env 로 외부화 (콤마 구분).
- pane 별 debounce / awaiting_marker state 독립 관리. 한 pane 트리거가 다른 pane 에 영향 없음.
- 세션 부재 pane 은 graceful skip (`tmux has-session` 검사 — 한쪽만 켜진 환경도 정상 동작).
- 새 메모리 `project_context_clear_log.md` 구조
- `.env` 토글 + 임계값
- 사용자 중단 hook (`/stop autoclear` / `/start autoclear`)
- inject prompt 표준화 (pane 무관 공통 문구)

### 제외 (Out of Scope)
- **사전 압축 (auto compact)** — Claude Code 자체 /compact 호출은 OOS. 본 spec은 /clear (전부 비움) 만. /compact 보존 부분 자동화는 후속.
- **plan/be/fe/rev 워크트리 sub-agent 자체 context auto-clear** — sub-agent는 한 사이클 후 종료라 누적 안 됨. 본 spec은 maestro (nmae) + helper 인터랙티브 pane 한정.
- **마커 패턴 자동 감지 (footer / status bar 변형)** — Claude Code의 context% 표시 포맷 변경 시 spec 갱신 필수. 자동 적응 OOS.

## 5) 설계
### 5-1) 흐름 다이어그램
```
bot.py polling loop (5s 간격)
  ↓
tmux capture-pane -t mobruji:0.0 -p → context% 파싱
  ↓
context >= 95% AND not debounced ?
  ├─ no → continue
  └─ yes
       ↓
       Discord push "🧠 context 95% → 정리 시작"
       ↓
       tmux send-keys "<정리 prompt>" Enter
       ↓
       debounce flag SET (80% 이하 떨어질 때까지 재트리거 차단)
       ↓
       (maestro 자율 정리 — sub-agent 완료 대기 + 핸드오프 작성)
       ↓
       polling tail에서 ===CLEAR_READY=== 감지
       ↓
       Discord push "🧹 정리 완료 (handoff: <file>) → /clear 전송"
       ↓
       tmux send-keys "/clear" Enter
       ↓
       (다음 wake가 빈 컨텍스트 + MEMORY.md 자동 로드 → 자율 복귀)
       ↓
       polling 계속 — context% < 80 떨어지면 debounce CLEAR
       ↓ (사이클 반복)
```

### 5-2) bot.py 신규 함수/task
```python
CONTEXT_TRIGGER_PCT: Final[int] = int(os.getenv("CONTEXT_CLEAR_TRIGGER_PCT", "95"))
CONTEXT_HYSTERESIS_PCT: Final[int] = int(os.getenv("CONTEXT_CLEAR_HYSTERESIS_PCT", "80"))
CONTEXT_AUTO_CLEAR_ENABLED: Final[bool] = os.getenv("CONTEXT_AUTO_CLEAR_ENABLED", "0") == "1"
# 복수 pane 지원 — 콤마 구분. 단일 TMUX_PANE_TARGET 도 후방호환 (plural 미지정 시 사용).
TMUX_PANE_TARGETS_DEFAULT: Final[str] = "mobruji:0.0,helper:0.0"
CLEAR_READY_MARKER: Final[str] = "===CLEAR_READY==="
AUTOCLEAR_PAUSED_FLAG: Final[Path] = Path("~/.mobruji/autoclear-paused").expanduser()
CONTEXT_CLEAR_LOG_PATH: Final[Path] = Path("~/.claude/projects/-home-mobruji-mobruji/memory/project_context_clear_log.md").expanduser()

def resolve_pane_targets(env: dict[str, str]) -> list[str]:
    """`TMUX_PANE_TARGETS` (plural, CSV) 우선. 부재 시 `TMUX_PANE_TARGET` (singular) 후방호환.
    둘 다 없으면 default ("mobruji:0.0,helper:0.0"). 빈 문자열 토큰 제거."""
    ...

def capture_pane_text(pane_target: str) -> str | None:
    """tmux capture-pane -t <pane> -p -S -<N> 전체 출력. 실패 시 None."""
    ...

def parse_context_pct(pane_text: str) -> int | None:
    """`===CTX:NN%===` 마지막 occurrence 파싱."""
    ...

def inject_cleanup_prompt(pane_target: str) -> bool:
    """tmux send-keys 로 정리 prompt 한 줄 inject + Enter."""
    ...

def send_clear_command(pane_target: str) -> bool:
    """tmux send-keys '/clear' Enter."""
    ...

async def context_auto_clear_loop(client, channel_id, *, pane_targets, ...):
    """pane 별 독립 state. 5초 간격 polling. 95% 트리거 + marker 감지 + /clear + hysteresis.

    각 pane 마다 (debounced, awaiting_marker) 튜플을 dict 로 관리. 한 pane 트리거가
    다른 pane 에 영향 없음. pane 별 tmux has-session 확인 후 missing pane 은 매 iter 마다
    silently skip (운영 환경에서 helper 세션 없을 때 crash 금지)."""
    state: dict[str, dict] = {p: {"debounced": False, "awaiting_marker": False} for p in pane_targets}
    while True:
        await asyncio.sleep(poll_interval)
        if AUTOCLEAR_PAUSED_FLAG.exists():
            continue
        for pane in pane_targets:
            # tmux has-session per pane (graceful skip)
            session = pane.split(":", 1)[0]
            if not tmux_has_session(session):
                continue
            pane_text = capture_pane_text(pane)
            if pane_text is None:
                continue
            pct = parse_context_pct(pane_text)
            st = state[pane]
            # marker 우선
            if st["awaiting_marker"] and CLEAR_READY_MARKER in pane_text:
                await channel.send(f"🧹 {pane} 정리 완료 → /clear 전송 ...")
                send_clear_command(pane)
                append_clear_log(pct or -1, "cleared", pane=pane)
                st["awaiting_marker"] = False
                continue
            if pct is None:
                continue
            if st["debounced"] and pct <= CONTEXT_HYSTERESIS_PCT:
                st["debounced"] = False
            if not st["debounced"] and pct >= CONTEXT_TRIGGER_PCT:
                await channel.send(f"🧠 {pane} context {pct}% → 자율 정리 시작")
                inject_cleanup_prompt(pane)
                append_clear_log(pct, "triggered", pane=pane)
                st["debounced"] = True
                st["awaiting_marker"] = True
```

Audit log line 은 pane 이름 포함: `pane=helper:0.0 trigger=...` / `pane=mobruji:0.0 cleared=...`.

### 5-3) 메모리 구조 (`project_context_clear_log.md`)
```markdown
---
name: project-context-clear-log
description: maestro/helper context auto-clear 사이클 결과 누적 (trigger / marker / clear)
metadata:
  type: project
---

# context auto-clear log (역시간순)

| timestamp | pane | event | context% | handoff file | duration |
|---|---|---|---|---|---|
| 2026-05-24T03:10+09 | mobruji:0.0 | cleared | 96 | project_session_handoff_2026-05-24-v1.md | 12m |
| 2026-05-24T02:58+09 | mobruji:0.0 | triggered | 95 | - | - |
| 2026-05-24T02:50+09 | helper:0.0 | cleared | 96 | - | 3m |
| 2026-05-24T02:47+09 | helper:0.0 | triggered | 95 | - | - |
```

마지막 50줄 유지. 동일 사이클 (triggered ↔ cleared) 쌍 누적 분석으로 정리 소요 시간 측정. pane 컬럼은 어느 pane 의 사이클인지 식별 — multi-pane 환경에서 nmae/helper 추세 분리.

### 5-4) 사용자 중단 hook
- `/stop autoclear` → `touch ~/.mobruji/autoclear-paused` + 채널 ack "⏸️ context auto-clear paused"
- `/start autoclear` → `rm -f ~/.mobruji/autoclear-paused` + 채널 ack "▶️ context auto-clear 재개"
- `/force clear` → debounce 무시 강제 /clear (sub-agent 잃기 수용. 사용자 명시)

### 5-5) tmux pane 검증
- polling pane 별 세션 존재 확인: bot.py 시작 시 각 pane 의 session 부 (`<session>:<window>.<pane>` 의 `<session>`) 에 대해 `tmux has-session -t <session>` 호출.
  - 한 pane 도 없으면 `context_auto_clear_loop` 자체를 띄우지 않음 (warn log + opt-in 무시).
  - 일부만 부재 시 (예: nmae 만 존재, helper 부재) loop 는 띄우되 존재하는 pane 만 polling. log 에 `skip pane=helper:0.0 (session not found)` 한 줄.
- 매 iter 마다 pane 별 `tmux has-session` 재확인 → 운영 도중 한쪽 pane 이 종료/재생성 되어도 다음 iter 부터 자동 반영. 부재 pane 은 silently skip.
- pane 이름 변경 시 `TMUX_PANE_TARGETS` env 갱신 필요 (운영 문서에 명시). 단일 pane 환경은 `TMUX_PANE_TARGET` (singular) 후방호환으로 1개만 지정 가능.

### 5-6) context% 파싱 패턴 (실측 결과 — 2026-05-23, #760)
**실측 결론**: Claude Code TUI footer/status-bar 에 context% **상시 표시 없음**. spec 초안의 두 가정 패턴 (`[NN% context used]`, `Context: NN%`) 모두 화면 캡처에 안 보임.

**실측 sample** (mobruji:0.0, `tmux capture-pane -p -S -2000`, 2026-05-23 17:43 KST):
```
✻ auto-clear 구현 위임 중… (1m 57s · ↓ 4.7k tokens · almost done thinking)
  ⎿  ◼ context auto-clear 자동화 spec/구현
  ◯ claude  BE digest mention sanitize (#754)               49s · ↓ 33.2k tokens
  ◯ claude  FE 다음 백로그 a11y/refactor                    35s · ↓ 33.6k tokens
  ◯ claude  PLAN footer context% 실측 + spec 갱신 (#760)    21s · ↓ 24.6k tokens

⏵⏵ bypass permissions on · 3 local agents · esc to interrupt · ctrl+t to hid…
```
- footer line: `⏵⏵ bypass permissions on · N local agents · esc to interrupt · ctrl+t to hid…` — context% 없음.
- 사이드바 (`◯ claude ... ↓ Nk tokens`) 는 sub-agent download 누적 — context% 직접 매핑 불가 (input/output/cache 구분 없음).
- context% 정보는 `/context` slash 명령 호출 시에만 별도 화면에 출력 — 상시 polling 대상 아님.
- pipe-pane 로그 (`~/.mobruji/tmux-pane.log`) 는 ANSI escape sequence 덤프 (cursor 위치 + 컬러 코드 폭주) — 평문 grep 불가능 확인.

**결정**: 초안 footer-scrape 전략 **현 Claude Code 버전(2026-05-23) 에서 작동 불가**. 다음 두 옵션 중 택일:

| 옵션 | 방식 | 장점 | 단점 |
|---|---|---|---|
| **A (권장)** | maestro self-emit marker — 매 turn 종료 직전 `===CTX:NN%===` 한 줄 stdout 출력. bot.py 는 marker 만 추적. | 결정적. inject 부작용 없음. 단일 regex. | maestro CLAUDE.md 룰 추가 + 매 turn 1줄 오버헤드. context% 자체는 maestro 가 자가 측정 (Claude API usage 또는 추정치) 필요. |
| **B (fallback)** | bot.py 가 30분 간격 `/context` slash 자동 inject + 응답 화면 scrape. | maestro 측 변경 없음. | inject 가 작업 중 maestro turn 끼어들 위험. slash 응답 화면 형식 자체도 별도 검증 필요. |

**spec 결정 (이번 PR)**: **옵션 A 채택**. PR C 구현 시:
- maestro 자기 룰 (CLAUDE.md 또는 user-memory) 추가 — 매 turn 끝에 `===CTX:NN%===` emit. context% 측정은 (i) Claude API response usage 추적이 가능한 경우 정확값, (ii) 불가 시 maestro 자기 추정치 (예: 진행한 작업량 + turn 카운트 휴리스틱). PR C kickoff 전 별도 spec/ADR 로 측정 방식 확정.
- bot.py regex: `r'===CTX:(\d{1,3})%==='` — 단순/유일 패턴. capture-pane scrape 후 마지막 occurrence 사용.
- footer scrape 코드 작성 금지 (실측상 footer 에 context% 없음).
- 옵션 B 는 옵션 A 가 비현실적으로 판명 시 fallback. 그 경우 본 spec 재갱신.

**regex 최종 후보** (옵션 A): `r'===CTX:(\d{1,3})%==='`. PR C 단위 테스트로 고정.

**maestro/helper 측 룰 (확정)**: `CLAUDE.md §12 maestro context% 자기 emit` 에서 marker 형식·추정 방법·적용 대상(maestro mmae/nmae + helper) 명시. PR C 는 이 룰을 전제로 구현한다. helper pane 도 polling 대상이므로 helper agent 역시 turn 마지막에 `===CTX:NN%===` emit 필요.

## 6) 작업 분할 (예상 PR 리스트)
- [x] **PR A (본 PR)**: spec 신설.
- [ ] **PR B**: `project_context_clear_log.md` 메모리 초기화 (빈 표). 본 spec 머지 후 plan 또는 maestro가 작성.
- [x] **PR C**: `bot.py` 에 `context_auto_clear_loop` + 파싱 + send_keys + `.env.example` 토글 + `tests/test_context_auto_clear.py`. be 사이클 후속. **전제**: `CLAUDE.md §12` (maestro/helper self-emit `===CTX:NN%===` 룰) 머지됨.
- [ ] **PR D**: `bot.py` on_message 에 `/stop autoclear` / `/start autoclear` / `/force clear` 핸들러 + 테스트. PR C 후 또는 동시.
- [ ] **PR E (운영 시험)**: PR C 머지 후 1주 dry-run (CONTEXT_AUTO_CLEAR_ENABLED=0 로 polling만 + log 만 append). hysteresis/패턴 검증 후 default ENABLED=1 전환.
- [x] **PR F (#855)**: `context_auto_clear_loop` multi-pane 확장 — nmae `mobruji:0.0` 단일 → nmae + helper `helper:0.0`. pane 별 독립 state (debounced/awaiting_marker). `TMUX_PANE_TARGETS` (plural CSV) env 신설, `TMUX_PANE_TARGET` (singular) 후방호환. graceful skip per pane (`tmux has-session`). audit log + Discord push 에 pane 이름 포함. helper pane 도 turn 끝 `===CTX:NN%===` emit 의무 (CLAUDE.md §12 갱신).

## 7) 테스트 전략
- **PR A**: 문서 spec only.
- **PR C**:
  - `parse_context_pct` 단위 (footer 패턴 / 다중 occurrence / 잘못된 형식 / 없음 → None).
  - `context_auto_clear_loop` asyncio mock:
    - 트리거: pct 96 → inject 호출 검증 + debounced True
    - hysteresis: pct 96 (debounced) → 추가 inject 없음
    - 회복: pct 75 → debounced False
    - marker: stdout에 `===CLEAR_READY===` → send_clear_command 호출 검증
    - paused flag: 파일 존재 시 polling skip
  - tmux send_keys mock (subprocess.run patched, 인자 검증).
- **PR D**: `/stop autoclear` → flag 파일 생성 / `/start autoclear` → 삭제 / `/force clear` → debounce 무시 검증.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | ~~context% 표시 정확한 footer 패턴?~~ → **해소 (2026-05-23, #760)**: Claude TUI footer 에 context% 상시 표시 없음 확인. §5-6 옵션 A (maestro self-emit marker `===CTX:NN%===`) 채택. PR C kickoff 전 측정 방식 (Claude API usage vs 자기 추정) 만 ADR/spec 1건 더 필요. | (해소) | @plan #760 ✅ |
| Q2 | 95% 트리거 적정한가, 90% 더 보수적? | (a) 95% (현재) — 정리 여유 짧음 / (b) 90% — 여유 ↑ 노이즈 ↑ | @user / 운영 1주 데이터 |
| Q3 | marker `===CLEAR_READY===` 외 다른 형식 필요? (다중 마커?) | 단일 marker 권장 (단순성). 변경 시 spec 갱신. | @maestro / PR C kickoff |
| Q4 | PR C 후 즉시 ENABLED=1 vs 1주 dry-run? | (a) 즉시 — 빠른 검증 / (b) 1주 dry-run — 안전 (권장) | @user / PR C merge 시 |
| Q5 | sub-agent 미완료 시 marker 도착 무제한 대기 vs timeout? | (a) 무제한 (현재 spec) — sub-agent 보호 / (b) 30분 timeout → 강제 /clear | @user / 운영 데이터 후 |

## 9) 결정 로그
- 2026-05-23: 초안 작성 (status=draft). v6 핸드오프 메모리에서 사용자 위임. tmux send-keys + capture-pane polling 채택 (별 IPC 없이 단순). hysteresis 80% 채택 (95→80 gap 15%p — debounce 충분). 사용자 중단 hook `/stop autoclear` Discord prefix ([[maestro-auto-wake]] `/stop wake` 와 동일 패턴). 컨텍스트% 파싱 패턴은 PR C kickoff 시 실제 sample 로 확정.
- 2026-05-23 (#760): footer-scrape 가정 폐기. 실측상 Claude Code TUI footer 에 context% 상시 표시 없음 확인 (capture-pane sample). 옵션 A — maestro self-emit marker `===CTX:NN%===` 채택. regex `r'===CTX:(\d{1,3})%==='` 단순화. PR C 차단 해소. 측정 방식 (Claude API usage vs 자기 추정) 은 후속 ADR/spec 1건으로 분리.
