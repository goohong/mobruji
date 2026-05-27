---
name: sub-agent-channels
description: "자율 사이클 sub-agent(be/fe/rev/plan) 출력은 워크트리별 영속 Discord 채널 4개로 분리하고, 사용자가 helper 에 지시해 spawn 한 sub-agent 는 기존 thread 패턴(#1011) 그대로 유지. NOTIFY_CHANNEL_ID 책임을 cycle digest/heartbeat 만으로 좁히고 bot.py 채널 routing 을 워크트리 기반으로 일반화."
status: proposed
metadata:
  type: feature-spec
  scope: infra
  owner: "@goohong"
  related_runbooks:
    - docs/runbooks/discord-only-operation.md
  related_features:
    - docs/features/helper-agent.md
    - docs/features/discord-driven-mobruji.md
    - docs/features/discord-status-push.md
  related_prs:
    - "#1011 (sub-agent launch 별 thread + LAUNCH_THREAD_ID env)"
  last_reviewed: 2026-05-24
---

# Sub-agent Channels — 자율 사이클 sub-agent 출력의 워크트리별 영속 채널 분리

> **약어 (helper-agent.md / discord-only-operation.md 와 일관)**
> - **mmae** = mac maestro (오너 mac 본진)
> - **nmae** = ncp maestro (NCP 상주 본진, 자율 사이클 오케스트레이터)
> - **helper** = NCP 상주 사용자 양방향 전담 세션
> - **자율 사이클 sub-agent** = nmae 가 사용자 지시 없이 launch 하는 be/fe/rev/plan sub-agent (`항시 가동 룰`, ADR-0014)
> - **helper sub-agent** = helper 가 사용자 응답 보조용으로 spawn 하는 sub-agent (사용자 메시지 starter 가 있는 turn 안)

## 1) 개요 (What / Why)

### 1-1) 배경
- 최근 도입된 패턴(PR #1011): nmae 가 sub-agent launch 할 때마다 Discord **thread** 를 생성하고, `LAUNCH_THREAD_ID` env 를 sub-agent 에 전달해 그 thread 로 push 하게 만든다. `thread_cleanup_loop` 가 1h age 로 archive.
- 채널은 현재 2개 (`tools/discord-daemon/.env.example` 기준):
  - `MOBRUJI_CHANNEL_ID` — 사용자 ↔ helper 양방향 (`bot.py` on_message 가 이 채널만 처리)
  - `NOTIFY_CHANNEL_ID` — `digest_loop` (`📊 사이클 상태` 5분 cron) 및 일부 cycle 통지 push. 미설정 시 `MOBRUJI_CHANNEL_ID` 로 fallback.

### 1-2) 문제 — 현재 thread 기반의 한계
- **자율 사이클은 사용자 starter 가 없다**. nmae 가 백로그를 기반으로 자율 launch 하는 sub-agent 는 사용자 메시지로 시작되지 않으므로 thread starter 가 부자연(Discord UX 기대: thread = 사용자 메시지에서 가지치기).
- **1h archive 로 영속성 X**. be/fe/rev/plan 4 워크트리는 ADR-0014 §3 "항시 가동" 룰로 사실상 무한 루프인데, 각 사이클이 끝나면 thread 가 닫혀 워크트리 단위 흐름을 시계열로 따라가기 어렵다.
- **channel listing 폭증**. 1h 안에도 4 워크트리 × 다수 사이클 → thread 가 채널 hierarchy 를 더럽힌다.
- **digest 와 progress push 가 같은 NOTIFY 채널 또는 thread 에 섞인다**. cycle 단위 진행 push 와 5분 cron digest 의 책임이 모호.

### 1-3) 본 spec 의 결정 (한 줄)
**자율 사이클 sub-agent 출력은 워크트리별 영속 채널 4개로 분리. 사용자가 helper 에 지시해 spawn 한 sub-agent (helper sub-agent) 는 기존 thread 패턴(#1011) 그대로 유지. NOTIFY_CHANNEL_ID 는 cycle digest/heartbeat 전용으로 책임을 좁힌다.**

## 2) 사용자 시나리오
- **시나리오 1 — nmae 가 be 사이클 launch (자율)**: nmae 가 백로그에서 be 워크트리 다음 작업을 launch. sub-agent 는 `WORKSPACE_CHANNEL_ID=<be_channel>` env 로 자기 진행/완료를 `#모부르지-be` 채널에 push. thread 생성 X.
- **시나리오 2 — rev sub-agent 자율 launch**: 머지된 PR 발견 → rev sub-agent 자율 launch → `#모부르지-rev` 에 QA 진행 push. 사용자 부재 시에도 워크트리 흐름이 채널 history 에 영속.
- **시나리오 3 — 사용자 질문 → helper 답 (thread X, 채널 raw push)**: 사용자가 `#모부르지` 메인 채널에 "지금 뭐 해?" 보냄. helper 가 helper-agent.md §2 4단계 패턴으로 답. **thread 생성 안 함** (helper 응답은 메인 채널 raw push 가 기본 — helper-agent.md §5-3 결정 유지).
- **시나리오 4 — 사용자가 helper 에 작업 지시 (helper sub-agent → thread)**: 사용자가 "PR #790 코드 리뷰 좀" 메시지 → helper 가 sub-agent spawn 시 PR #1011 패턴으로 thread 생성 + `LAUNCH_THREAD_ID` 전달 → sub-agent 가 그 thread 안에서 진행/완료 push. 사용자 메시지가 명확한 starter 라 thread UX 가 자연스럽다.
- **시나리오 5 — digest 채널**: `NOTIFY_CHANNEL_ID = #모부르지-digest` 에 5분마다 `📊 사이클 상태` (be/fe/rev/plan 한 줄씩) 만 받는다. 4개 워크트리 진행 push 는 각자 채널에 분산되어 알림 noise 분리.

## 3) 요구사항

### 기능 요구사항
- [ ] 자율 사이클 sub-agent (nmae launch) 는 **`WORKSPACE_CHANNEL_ID` env 한 개**만 받아 그 채널에 push. `LAUNCH_THREAD_ID` 는 박지 않음.
- [ ] helper sub-agent (helper 가 사용자 turn 안에서 spawn) 는 **기존 PR #1011 thread 패턴 유지** — helper 가 thread 생성 후 `LAUNCH_THREAD_ID` env 전달.
- [ ] bot.py routing 일반화: 워크트리(`be`/`fe`/`rev`/`plan`) → 채널 ID 매핑 함수. 누락 시 메인 채널 fallback + warning log.
- [ ] nmae 가 sub-agent launch 시 워크트리 라벨로부터 적절한 `WORKSPACE_CHANNEL_ID` 자동 주입. nmae prompt template (`docs/ai-harness/12-sub-agent-prompt-template.md`) 에 룰 박제.
- [ ] `thread_cleanup_loop` 는 그대로 두되 대상 범위 명문화: **helper sub-agent thread 만** 1h archive. 영속 채널은 archive 대상 아님.
- [ ] `NOTIFY_CHANNEL_ID` 책임 좁히기: `digest_loop` 와 `heartbeat`/`cycle launch event` 만. **sub-agent 진행/완료 push 는 NOTIFY 에 보내지 않는다**.
- [ ] 채널 ID 누락(env 미설정) 시 fallback 순서: workspace 채널 → `MOBRUJI_CHANNEL_ID` (메인) → 그래도 실패면 stderr warning. 메시지 drop 금지.

### 비기능 요구사항
- **Discord rate limit**: global 50/s, 채널당 5/5s. 본 spec 으로 4 채널 합쳐도 sub-agent push 빈도는 turn 끝 단위(분 단위) → 1/s 미만. 사전 검토 통과(2026-05-24 사용자 합의). bot.py 가 채널별 push 카운터 metric 로그(`channel_push_count{workspace=be}`) emit → 비정상 burst 감지.
- **관측성**: 메시지 push 시 `[workspace=<be|fe|rev|plan|helper-thread|notify|main>]` prefix 를 logger 에 박는다 (Discord 메시지 본문에는 미박제 — UX 깨끗).
- **fallback alert**: 워크트리 채널 ID 누락이 N(default 5)회 이상이면 NOTIFY 채널에 운영 alert 1회 push.
- **보안**: 새 채널 ID 도 기존 env 와 동등하게 mode 0600 `.env` + systemd `EnvironmentFile=` (`docs/ai-harness/04-security-policy.md` 준수).

## 4) 범위 / 비범위

### 포함
- 자율 사이클 sub-agent 출력의 워크트리별 영속 채널 분리 (be/fe/rev/plan 4개)
- bot.py 의 channel routing 함수 신설 + `WORKSPACE_CHANNEL_ID` env 전달 룰
- `NOTIFY_CHANNEL_ID` 책임 재정의 (digest/heartbeat 만)
- nmae prompt template (sub-agent launch 시 `WORKSPACE_CHANNEL_ID` 주입 룰) 박제
- helper sub-agent thread 패턴(PR #1011) 명시적 보존 + cleanup 범위 좁히기

### 제외 (Out of Scope)
- helper 본인 응답 채널 변경 (helper 는 계속 `MOBRUJI_CHANNEL_ID` 메인 채널 raw push — helper-agent.md §5-3 그대로)
- thread 패턴(#1011) 자체 폐지 (helper sub-agent 용으로 유지)
- 채널별 권한 정책 자동화(R/W ACL) — 운영자 수동(§8 Q5)
- Discord 카테고리(channel group) UI 정리
- digest 포맷 변경 (`format_cycle_digest` 는 그대로)
- 별 yaml workspace registry 도입 (env 1차 — §8 Q1)

## 5) 설계

### 5-1) 도메인 모델
- 도메인 엔티티 변경 없음. infra/Discord routing 레이어 전용.
- 새 개념: **"workspace"** = `be|fe|rev|plan|helper-thread|notify|main` 의 채널 routing key. 워크트리 4개 + helper-thread + notify + main fallback.

### 5-2) 아키텍처 — 채널 ID 어디서 관리

**1차 결정: `.env` 로 관리** (yaml registry 는 §8 Q1).

`tools/discord-daemon/.env.example` 추가:
```
# --- 워크트리별 자율 사이클 채널 (sub-agent push 분산) ---
# nmae 가 자율 launch 하는 be/fe/rev/plan sub-agent 의 진행/완료 push 가
# 각자 워크트리 채널에 영속. 미설정 시 MOBRUJI_CHANNEL_ID 로 fallback.
WORKSPACE_CHANNEL_ID_BE=
WORKSPACE_CHANNEL_ID_FE=
WORKSPACE_CHANNEL_ID_REV=
WORKSPACE_CHANNEL_ID_PLAN=

# NOTIFY 책임 재정의: digest_loop + cycle launch heartbeat 만.
# 사용자 메시지 응답 및 sub-agent 진행 push 는 별 채널로 분산되었음.
NOTIFY_CHANNEL_ID=
```

bot.py 내부에서 routing dict 구성:
```python
WORKSPACE_CHANNELS = {
    "be":   env.get("WORKSPACE_CHANNEL_ID_BE")   or env["MOBRUJI_CHANNEL_ID"],
    "fe":   env.get("WORKSPACE_CHANNEL_ID_FE")   or env["MOBRUJI_CHANNEL_ID"],
    "rev":  env.get("WORKSPACE_CHANNEL_ID_REV")  or env["MOBRUJI_CHANNEL_ID"],
    "plan": env.get("WORKSPACE_CHANNEL_ID_PLAN") or env["MOBRUJI_CHANNEL_ID"],
}
```

### 5-3) sub-agent launch 시 env 전달 룰

**nmae 의 sub-agent launch 패턴 (자율 사이클)**:
- 기존: maestro 가 `claude --print "<prompt>"` 로 sub-agent launch (run_in_background) + `LAUNCH_THREAD_ID` env (PR #1011)
- 신: `LAUNCH_THREAD_ID` 대신 **`WORKSPACE_CHANNEL_ID`** 박는다. 값은 워크트리 라벨에서 lookup:

```bash
# nmae 가 be 사이클 launch (예시)
WORKSPACE_CHANNEL_ID="$WORKSPACE_CHANNEL_ID_BE" \
  claude --print "<be-cycle-prompt>" &
```

**helper 의 sub-agent launch 패턴 (helper sub-agent)**:
- 변경 없음. helper 가 사용자 메시지에 reply 하는 turn 안에서 thread 생성 → `LAUNCH_THREAD_ID` 그대로 박는다 (PR #1011 패턴).

sub-agent 내부의 Discord push wrapper(`~/.mobruji/discord-reply.sh`) 는 우선순위:
1. `LAUNCH_THREAD_ID` 가 있으면 그 thread 로 push (helper sub-agent path)
2. 없고 `WORKSPACE_CHANNEL_ID` 가 있으면 그 채널로 push (자율 사이클 path)
3. 둘 다 없으면 `MOBRUJI_CHANNEL_ID` fallback + stderr warning

### 5-4) bot.py 변경점

**현 코드(`tools/discord-daemon/bot.py`) 기준 변경 지점**:

1. **`load_env()` (L41-75)** — `WORKSPACE_CHANNEL_ID_*` 4건 옵션 로딩 추가. 누락 시 `MOBRUJI_CHANNEL_ID` fallback + INFO log.
2. **`build_client()` (L203-251)** — `WORKSPACE_CHANNELS` dict 빌드 + `on_ready` 에서 채널 4개 lookup 검증 후 log:
   ```
   workspace 채널 매핑: be=#XXX fe=#YYY rev=#ZZZ plan=#WWW (누락 N개 → main fallback)
   ```
3. **신규 helper 함수 `channel_for_workspace(workspace: str) -> int`** — workspace key → channel id 매핑.
4. **`on_message` (L233-249)** — 변경 없음. 사용자 메시지 수신은 메인 채널만 처리 (다른 워크트리 채널은 read-only push 전용).
5. **`digest_loop` (L171-195)** — 변경 없음. NOTIFY 채널에 그대로 push. **단 docstring 에 책임 좁히기 명문화** ("이 loop 는 cycle digest 전용 — sub-agent 진행 push 는 워크트리 채널로 분리됨").
6. **신규: `pipe_pane` 처리 함수 분리 (개념적)** — 본 spec 은 bot.py 가 pipe-pane 캡처를 직접 하지 않는 단순화된 현 구조(wrapper script 가 직접 push) 를 유지. 따라서 "helper_pipe_pane vs sub-agent pipe-pane 분리"의 실체는 wrapper 가 env(`LAUNCH_THREAD_ID` / `WORKSPACE_CHANNEL_ID`) 로 분기하는 코드 한 줄.

**충돌 지점 — helper-agent.md / discord-only-operation.md**:
- helper-agent.md §5-3 는 "bot.py 가 pipe-pane 캡처" 가정. 현 bot.py 는 단순화됨(wrapper 직접 push, 2026-05-23 사용자 결정). 본 spec 도 현 구조(wrapper 분기) 기준 — helper-agent.md §5-3 는 향후 보정 필요.
- discord-only-operation.md §2-2 는 "#모부르지-digest" 1개 보조 채널만 가정 — 본 spec 도입 후 워크트리 4채널 추가로 sync 필요(§7 마이그레이션).

### 5-5) 데이터 흐름 / 시퀀스 (자율 사이클 path)

```
nmae (tmux mobruji)
  │  WORKSPACE_CHANNEL_ID_BE=#be-id ./launch-be-cycle.sh
  ▼
be sub-agent (claude --print, run_in_background)
  │  작업 수행 + turn 끝 push
  │  ~/.mobruji/discord-reply.sh "<message>"
  │    └─ env WORKSPACE_CHANNEL_ID 감지 → 그 채널 ID 로 POST
  ▼
Discord API
  ▼
#모부르지-be 채널 (영속, archive 대상 아님)
```

### 5-6) 데이터 흐름 / 시퀀스 (helper sub-agent path — PR #1011 보존)

```
사용자 메시지 → #모부르지 메인
  ▼
bot.py on_message → helper tmux send-keys
  ▼
helper
  ├─ 메인 채널 raw 답 (변경 없음, helper-agent.md §5-3)
  └─ 필요 시 sub-agent spawn
        │  thread 생성 + LAUNCH_THREAD_ID=<thread-id>
        ▼
        helper sub-agent
          │  discord-reply.sh
          │    └─ LAUNCH_THREAD_ID 우선 → 그 thread 로 POST
          ▼
        Discord thread (1h archive by thread_cleanup_loop)
```

### 5-7) DB 마이그레이션
- 없음.

### 5-8) 프론트엔드 화면
- 없음.

## 6) 운영 / 검증 시나리오

### 6-1) AS-IS vs TO-BE 매트릭스

| 케이스 | AS-IS | TO-BE |
|---|---|---|
| nmae 가 be sub-agent 자율 launch | thread 생성(#1011) → 1h archive | `#모부르지-be` 영속 채널에 push |
| nmae 가 rev sub-agent 자율 launch | thread 생성(#1011) → 1h archive | `#모부르지-rev` 영속 채널에 push |
| 사용자 → 메인 채널 질문 | helper 가 메인 채널 raw 답 | 변경 없음 |
| 사용자 지시 → helper sub-agent | (기존 동작 모호) | thread 생성(#1011) + thread 안에서 push, 1h archive |
| nmae digest (`📊 사이클 상태`) | NOTIFY 채널 (`digest_loop`) | 변경 없음 (NOTIFY 책임은 이것 + heartbeat 로 좁힘) |
| sub-agent cycle 진행 push 가 NOTIFY 에 섞임 | 발생 (혼동) | **금지** — 워크트리 채널로만 push |

### 6-2) 검증 절차 (운영자 manual smoke)
1. 새 `.env` 4개 채널 ID 셋업 → systemctl restart mobruji-discord-bridge
2. bot.py log 에 "workspace 채널 매핑: be=#... fe=#... rev=#... plan=#..." 1줄 확인
3. nmae 에 be 사이클 launch 수동 trigger → `#모부르지-be` 에 진행 push 도착 확인 (메인 채널에는 X)
4. 사용자가 메인 채널에 "rev sub-agent 하나 띄워줘" → helper 가 thread 생성 → thread 안에 sub-agent 진행 push 확인 + 1h 후 archive 확인
5. 5분 후 NOTIFY 채널에 `📊 사이클 상태` digest 1건 push (sub-agent 진행 push 없음) 확인
6. 일부러 `WORKSPACE_CHANNEL_ID_REV` 누락 → rev sub-agent push 가 메인 채널 fallback + bot.py warning log 확인

### 6-3) Rate limit 안전 margin 검증 룰
- bot.py 에 `channel_push_count_per_5s` rolling counter — 각 워크트리 채널별 5초 윈도에 5건 초과 시 warning log + NOTIFY 채널 운영 alert. (Discord 채널당 5/5s 한계의 80% 인 4/5s 를 soft alert threshold 로.)
- global 50/s 는 4 채널 × 평균 1/min push 기준 0.07/s 로 무시 가능. 단 sub-agent burst (예: rev 가 다수 PR 동시 평가) 대비 monitoring 만 둔다.

## 7) 마이그레이션 (단계적 전환)

PR #1011 thread 패턴과 **공존** 하면서 단계적으로 전환:

- **단계 0 (이미 도입됨)**: 모든 sub-agent push 가 thread (PR #1011)
- **단계 1 (본 spec PR-A/B)**: bot.py 가 `WORKSPACE_CHANNEL_ID_*` env 읽고 routing 함수 신설. 단 nmae 는 아직 thread 패턴 사용 → push 결과 무변화.
- **단계 2 (PR-C)**: wrapper script(`~/.mobruji/discord-reply.sh`) 가 `LAUNCH_THREAD_ID` / `WORKSPACE_CHANNEL_ID` 분기 처리.
- **단계 3 (PR-D)**: nmae prompt template(`docs/ai-harness/12-sub-agent-prompt-template.md`) 에 "자율 사이클 sub-agent launch 시 `WORKSPACE_CHANNEL_ID` 박기" 룰 박제. nmae 는 다음 사이클부터 thread 대신 채널 사용.
- **단계 4 (PR-E)**: helper sub-agent path 에서 thread 패턴이 유지되는지 smoke test. `thread_cleanup_loop` archive 대상이 helper sub-agent thread 로 좁혀졌는지 검증.
- **단계 5 (PR-F)**: discord-only-operation.md §2-2 보정 — "#모부르지-digest" 외 워크트리 채널 4개 가정 박제.

롤백: 채널 4개 env 모두 unset → 모든 push 가 `MOBRUJI_CHANNEL_ID` fallback. helper sub-agent thread 패턴은 영향 없음.

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 | 우선순위 |
|---|---|---|---|---|
| Q1 | 채널 ID 관리 매체 — `.env` 단독 vs 별 yaml(`tools/discord-daemon/workspaces.yml`) | (a) .env 단독 (단순, 본 spec 1차 결정) / (b) yaml (워크트리 확장/메타 첨부 시 유리) | @goohong / PR-A 직전 | 중 |
| Q2 | 채널 신설은 운영자 수동 vs bot 자동 생성 | (a) 운영자 수동 (현재 권장 — Discord bot 권한 최소) / (b) bot 자동 (`Manage Channels` 권한 필요, 실수 시 채널 폭증) | @goohong / 운영 결정 | 중 |
| Q3 | 채널별 권한 정책 (메시지 쓰기 가능자) | (a) 모두 bot + 운영자 / (b) 워크트리별 인원 다름 (예: rev = QA 인원) | @goohong / 운영자 정책 | 하 |
| Q4 | 본 spec 도입 후 PR #1011 thread 패턴을 helper sub-agent 외 케이스에도 남길지 | (a) helper sub-agent 전용으로 좁히기 (본 spec 결정) / (b) 사용자 trigger sub-agent 일반에 유지 | @goohong / PR-D | 중 |
| Q5 | 채널 ID 누락 시 fallback alert 임계치 | (a) 누락 1회마다 alert / (b) 5회 누적 후 1회 / (c) 무알림 + 로그만 | @goohong / PR-B | 하 |
| Q6 | sub-agent 가 자기 워크트리 채널 외 다른 채널로 push 시도 시 | (a) bot.py 가 거절 / (b) 허용 (sub-agent 자율 판단) | @goohong / PR-C | 하 |

## 9) 결정 로그
- **2026-05-24 (본 spec 초안)**: 자율 사이클 sub-agent = 영속 채널 4개 / helper 가 사용자 turn 안에서 spawn 하는 sub-agent = thread 패턴(#1011) 유지.
  - 근거 1: Discord 자원 비싸지 않음 (rate limit margin 충분, 사용자 합의 — 사전 검토)
  - 근거 2: thread starter 가 부자연 (자율 사이클은 사용자 메시지 없음)
  - 근거 3: 1h archive 는 워크트리 항시 가동(ADR-0014 §3) 흐름과 부적합 — 영속 채널이 fit
  - 근거 4: NOTIFY 채널이 digest + sub-agent 진행 혼재로 책임 모호 → 본 spec 으로 분리
- 관련 메모리: `[[feedback-discord-polling]]`, `[[feedback-keep-4-cycles-active]]`, `[[feedback-worktree-lock]]`, `[[feedback-autonomous-wake-pattern]]`

## 10) 작업 분할 (예상 PR 리스트)
- [ ] **PR-A** — `tools/discord-daemon/.env.example` 4건 `WORKSPACE_CHANNEL_ID_*` 추가 + `bot.py load_env()` 옵션 로딩 + `channel_for_workspace()` 함수 (scope:infra)
- [ ] **PR-B** — `bot.py` warning/alert: 워크트리 채널 누락 시 fallback 카운터 + NOTIFY alert (scope:infra)
- [ ] **PR-C** — `~/.mobruji/discord-reply.sh` wrapper 분기: `LAUNCH_THREAD_ID` 우선 / `WORKSPACE_CHANNEL_ID` 2순위 / `MOBRUJI_CHANNEL_ID` fallback (scope:infra, `needs-human-review` — 운영 wrapper 변경)
- [ ] **PR-D** — `docs/ai-harness/12-sub-agent-prompt-template.md` 자율 사이클 launch 시 `WORKSPACE_CHANNEL_ID` 룰 박제 + 예시 prompt (scope:infra-docs)
- [ ] **PR-E** — `docs/runbooks/discord-only-operation.md` §2-2 보정 (워크트리 4채널 + helper sub-agent thread 패턴 공존 명시) + smoke test 절차 6단계 (scope:infra-docs)
- [ ] **PR-F** — `bot.py` push rate counter metric + 5/5s warning threshold (scope:infra, 관측성)

## 11) 테스트 전략
- **단위**: bot.py `channel_for_workspace()` 매핑 + fallback (env 누락 케이스 3종) pytest 케이스. wrapper script bash test (env 분기 우선순위 3종).
- **통합**: NCP 스테이징에서 mock sub-agent 4종 launch → 각 채널에 push 도달 확인. helper sub-agent thread path 동시 가동 → cleanup loop 가 thread 만 archive 확인.
- **E2E (운영자 수동)**: §6-2 smoke 6단계 1회 실행 (배포 직후). rate counter 가 정상 범위(< 1/s) 인지 1시간 관측.
- **회귀**: 기존 helper 메인 채널 raw push 가 영향 없는지 (helper-agent.md §2 시나리오 1~3 재실행).

## 12) 충돌/sync 필요 문서
- `docs/runbooks/discord-only-operation.md` §2-2 — 채널 분리 모델 보정 (#모부르지-be/fe/rev/plan 추가, helper sub-agent thread 패턴 명시)
- `docs/features/helper-agent.md` §5-3 — pipe-pane 캡처 가정이 현 bot.py 단순화 구조와 어긋남 — 본 spec 과는 직접 충돌 없으나 차후 보정 권장
- `docs/ai-harness/12-sub-agent-prompt-template.md` — 자율 사이클 launch 시 `WORKSPACE_CHANNEL_ID` 박기 룰 박제 (PR-D)
- `docs/features/discord-driven-mobruji.md` Q2 (thread reply vs 새 메시지) — 본 spec 으로 "케이스에 따라 분리" 라는 답이 정해짐 (자율 = 채널, helper sub-agent = thread)
- `docs/features/discord-status-push.md` — sub-agent push 책임이 본 spec 으로 분산되었음을 명시할 필요

## 부록 A — 위험 / 한계
- 운영자가 채널 4개 신설을 까먹으면 모두 메인 채널 fallback → 메인 채널이 혼잡해질 수 있음. PR-B alert 가 첫 누락에 운영자에게 push 하므로 mitigation.
- nmae 가 워크트리 라벨링을 잘못해 sub-agent 가 엉뚱한 채널에 push (예: be 작업인데 `WORKSPACE_CHANNEL_ID_FE` 박힘). PR-D 의 prompt template 룰 박제로 1차 방어.
- helper sub-agent thread 패턴을 보존하므로 `thread_cleanup_loop` 의 archive 대상 식별이 정확해야 함 — thread parent channel 이 메인이면 cleanup 대상, 워크트리 채널이면 제외하는 룰 필요. (구현 디테일은 PR-C/E 에서 확정)
