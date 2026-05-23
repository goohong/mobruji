---
feature: Helper Agent (nmae 영구 가동으로 mmae 대체 + 사용자 양방향 전담)
slug: helper-agent
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-23
---

# Helper Agent — nmae 영구 가동으로 mmae 대체 + 사용자 양방향 전담

> **약어 (전 문서 일관)**
> - **mmae** = mac maestro (오너 mac 본진, 기존 오케스트레이션 세션)
> - **nmae** = ncp maestro (NCP 상주 본진, sub-agent be/fe/rev/plan 오케스트레이션)
> - **helper** = NCP 상주 신설 세션 (사용자 양방향 전담)

## 1) 개요 (What / Why)
- 현재 운영 모델은 **mmae 본진**이 켜져 있어야만 사용자가 폰만으로 모부르지 운영을 지속할 수 있다. mac 종료/sleep/네트워크 단절 시 사용자 응답 채널이 끊긴다.
- 동시에 **nmae** 가 도입되어 sub-agent 오케스트레이션은 NCP 에서 영구 가동되지만, nmae 는 작업·digest 에 집중해야 하므로 사용자 query 즉시 응답 책임을 분리할 필요가 있다.
- 사용자 요청 원문 (2026-05-23): *"폰만으로 운영하려면 mac maestro 역할을 NCP helper agent 가 영구 대체."* + 후속 *"helper 진짜 가치 = nmae 바빠도 사용자 query 즉시 답."*
- 본 spec 은 NCP 상주 `helper` Claude CLI 세션을 신설하여 다음 책임을 전담시킨다:
  1. 사용자 Discord query 즉시 응답 (1초 ack / 10초 자체 답)
  2. 실 작업 필요 시 nmae 위임 (helper 답과 병행)
  3. nmae 응답 도착 시 보강 push
- 대상 액터: 오너 (이동 중 폰 Discord 단독 사용), NCP 상주 `helper` 세션, NCP 상주 `nmae` 세션, 백그라운드 sub-agent (be/fe/rev/plan).

## 2) 사용자 시나리오
- **시나리오 1 — 단순 query, 즉시 답**: 오너가 외출 중 폰 Discord 로 "오늘 머지된 PR 몇 개야?" 를 보낸다. bot.py 가 helper tmux 로 send-keys. helper 가 1초 안 ack push, 10초 안 `gh pr list --state merged --search "merged:>=$(date +%F)"` 으로 자체 답 push. nmae 개입 0.
- **시나리오 2 — nmae 바쁨, 사용자 query 도착 (핵심 가치)**: nmae 가 sub-agent 위임 중이라 사용자 응답이 막혀 있다. 오너가 "지금 뭐 하고 있어?" 를 보낸다. helper 가:
  1. **1초 안 ack** push ("확인하고 있습니다")
  2. **10초 안 자체 답** — `tmux capture-pane -t mobruji:0.0 -p` (nmae pane raw) + `gh pr list --state open` + 최근 `git log` → 정중체로 요약해 push
  3. nmae 답 기다리지 않음 — helper 가 보는 정보로 충분히 답
- **시나리오 3 — 실 작업 위임 (병행)**: 오너가 "PR #790 머지해줘" 를 보낸다. helper 가:
  1. 1초 안 ack push
  2. 10초 안 자체 답 push (PR #790 현재 상태 — CI / 라벨 / 충돌 여부 등 helper 가 직접 조회)
  3. **병행** 으로 `tmux send-keys -t mobruji:0.0 "<self-contained merge prompt>" Enter` 로 nmae 위임
  4. nmae pipe-pane 캡처 → 머지 완료 stdout 도착 시 helper 가 다시 작성 → "머지 완료" 정중체로 보강 push
- **시나리오 4 — mac 부재 / mmae 종료**: mmae 가 꺼져 있어도 (1)~(3) 모두 NCP 에서 영구 가동되므로 사용자 폰 단독 운영 가능.
- **시나리오 5 — mac 재기동 후 충돌 회피**: mmae 가 다시 켜져도 사용자 Discord 메시지는 helper 가 1차 수신하므로 mmae 는 sub-agent 오케스트레이션만 담당. 동일 메시지 중복 응답 방지를 위해 mmae 의 Discord 직접 polling 은 비활성화 또는 read-only 로 강등.

## 3) 요구사항
### 기능 요구사항
- [ ] NCP 에 tmux session `helper` 신설 (claude CLI 상주, Restart=always)
- [ ] bot.py routing 변경: 사용자 Discord 메시지는 **helper tmux 로 우선 send-keys** (현재 nmae 직접 send 폐지 또는 후순위)
- [ ] **즉시 답 4단계 패턴 강제** (helper 초기 프롬프트에 명시):
  1. **1초 안 ack** — 사용자 메시지 수신 직후 짧은 정중체 ack 즉시 push ("확인하고 있습니다" 등)
  2. **10초 안 자체 답** — 다음 정보 소스로 자체 reasoning 후 정중체 답 push:
     - `tmux capture-pane -t mobruji:0.0 -p` (nmae pane raw 상태)
     - `gh pr list --state open` / `gh pr view <num>`
     - `git log --oneline -20` / `git status`
  3. **nmae 위임 병행** — 실 작업 필요 시 helper 답과 **독립** 으로 `tmux send-keys -t mobruji:0.0` 로 nmae 위임. helper 는 nmae 응답을 절대 기다리지 않음.
  4. **nmae 응답 도착 시 보강 push** — nmae pipe-pane 캡처 → helper 가 다시 작성 → 추가 push (1차 자체 답 위에 덧붙이는 형태)
- [ ] **helper 절대 룰**: nmae 답 기다리며 사용자 응답 지연 금지. 자기 답 먼저 push.
- [ ] helper LLM 출력은 **Discord raw push** (bot.py 가 정형 변환하지 않음, §5-3 참조)
- [ ] helper crash 시 systemd Restart=always 로 자동 복구
- [ ] 무한 위임 루프 방어 (helper → nmae → helper 응답 → helper 다시 위임…) — turn counter 또는 `[origin=nmae]` tag 로 차단

### 비기능 요구사항
- **응답 시간** (사용자 메시지 수신 → Discord push):
  - ack: P50 ≤ 1 초, P99 ≤ 3 초
  - 자체 답: P50 ≤ 10 초, P99 ≤ 30 초
  - nmae 위임 결과 보강: P50 ≤ 2 분, P99 ≤ 5 분
- helper 가용성 99% (systemd Restart=always + NCP 인스턴스 가용성)
- LLM 비용: Max OAuth multi-device 우선 (월 추가 비용 0), fail 시 Anthropic API key (Haiku, 월 $5 한도 가정)
- 보안: helper 가 사용하는 OAuth 토큰 / API key 는 `.env` (mode 0600) + systemd `EnvironmentFile=`. 평문 저장 / 코드 하드코딩 금지 (`docs/ai-harness/04-security-policy.md` 준수)
- 관측성: helper stdout 을 `journalctl -u mobruji-helper-tmux` 로 노출. 위임 이벤트는 `[helper→nmae]` prefix 로 grep 가능

## 4) 역할 분리 (nmae vs helper)
> 본 spec 의 핵심 결정. 두 NCP 세션의 책임을 명확히 분리하여 안티패턴 (nmae 가 사용자 query 무시하고 작업만 진행) 을 구조적으로 해소한다.

| 항목 | **nmae** (ncp maestro) | **helper** |
|---|---|---|
| **주 책임** | sub-agent 오케스트레이션 (be/fe/rev/plan launch / 머지 / 라벨 / 사이클 진행) | 사용자 양방향 응답 |
| **Discord push 권한** | digest / 작업 보고 / 사이클 launch event 직접 push (기존 그대로) | 사용자 query 응답 raw push |
| **사용자 query 수신** | X — bot.py 가 더 이상 nmae 로 직접 send-keys 하지 않음 | O — bot.py 의 유일한 1차 라우팅 대상 |
| **응답 형태** | 작업 결과 정형 (formal_ack / digest 등 form generator 사용) | terminal raw (helper LLM stdout 그대로) |
| **상대 위임** | 사용자 query 도착해도 무시 — 작업 집중 | nmae 에 send-keys 위임 (시나리오 3) |
| **wake 주기** | sub-agent 완료 통지 / cron / 사용자 결정 | 사용자 메시지 수신 / nmae pane 변화 감지 |

**핵심 효과**:
- nmae 는 작업 흐름이 사용자 메시지에 끊기지 않음 → wall-clock 최소화
- 사용자는 nmae 가 바빠도 helper 가 항상 응답 → "응답 끊김" 체감 해소
- 두 역할이 같은 채널 (#모부르지) 에 push 하지만 message origin 으로 구분 가능 (helper raw vs nmae 정형)

## 5) 설계
### 5-1) 도메인 모델
- 도메인 엔티티 변경 없음. 인프라/툴링 레이어 전용.
- 외부 컴포넌트: Discord Gateway, bot.py (NCP), tmux (NCP), claude CLI (NCP), Anthropic API (Max OAuth or key), 기존 nmae tmux session.

### 5-2) 구조 다이어그램
```
사용자 (폰 Discord)
   │
   ▼
bot.py  (NCP, 기존)
   │  tmux send-keys -t helper:0.0    (사용자 query 1차 라우팅)
   ▼
helper tmux session  (NCP, 신설, claude CLI)
   │  4단계 패턴
   ├── [1초] ack push                   ──► bot.py ──► Discord raw push
   │
   ├── [10초] 자체 답 (pane capture + gh + git) ──► bot.py ──► Discord raw push
   │
   └── [병행] nmae 위임 시
        │  tmux send-keys -t mobruji:0.0
        ▼
        nmae tmux session  (NCP, 기존)
           │  작업 후 stdout
           │  (pipe-pane → /tmp/nmae-pane.log)
           │
           ├── digest / 사이클 launch event ──► bot.py ──► Discord 정형 push (기존)
           │
           └── 사용자 위임 작업 결과
                │
                ▼
             helper 캡처 (tail -F + LLM 재작성)
                │
                ▼
             bot.py ──► Discord raw push (보강)
```

### 5-3) 외부 연동
- **Anthropic API** (helper LLM): 두 옵션
  - 옵션 A — Max OAuth multi-device: 본진 mmae 와 동일 계정, `claude --login` 으로 NCP 에 별도 device 로 로그인. **가성비 1차 시도**. 한계: Max quota 공유, multi-device 정책 변경 위험.
  - 옵션 B — Anthropic API key (Haiku): `ANTHROPIC_API_KEY` env 로 별도 결제. **안정 fallback**. 한계: 월 비용 발생.
  - 권장: A 시도 → quota / 정책 fail 시 B 즉시 전환. (§8 Q1 우선순위 최상)
- **Discord Gateway**: bot.py 가 기존 그대로 사용. helper 는 Discord 직접 접근하지 않음 (bot.py 가 유일 채널).
- **bot.py 책임 (helper terminal raw push)**:
  - helper pipe-pane stdout 캡처 (`/tmp/helper-pane.log` 또는 동등 경로)
  - ANSI escape strip (라이브러리 또는 정규식)
  - Discord 2000자 split (긴 응답 자동 분할)
  - **변환 없이 그대로 push** — helper LLM 응답 = Discord 메시지 1:1
  - bot.py 의 기존 form generator (formal_ack 등) 는 **nmae 전용** 으로 유지 (helper 와 병존)
- **tmux**: NCP 에 `helper`, `mobruji` 두 세션 병행. send-keys 는 단방향 텍스트 주입, 출력은 pipe-pane 으로 파일 캡처.

### 5-4) 데이터 흐름 / 시퀀스
1. 사용자 → Discord 메시지
2. bot.py on_message → `tmux send-keys -t helper:0.0 "<user-msg>" Enter`
3. helper Claude CLI 4단계 패턴 진입:
   - **t=0~1s**: ack push (짧은 정중체)
   - **t=1~10s**: 자체 reasoning
     - `tmux capture-pane -t mobruji:0.0 -p` 로 nmae 현재 상태 raw 확인
     - `gh pr list` / `gh pr view` 로 GitHub 상태 확인
     - `git log` / `git status` 로 working tree 확인
     - → 정중체 답 stdout
   - **t=병행**: nmae 위임 필요 판단 시 `tmux send-keys -t mobruji:0.0 "<self-contained prompt>" Enter`. helper 는 즉시 자체 답 단계로 복귀.
4. helper stdout → bot.py 가 pipe-pane 파일 tail → ANSI strip → 2000자 split → Discord raw push
5. nmae 위임 시: helper 가 nmae pipe-pane 로그 tail (별 폴링) → 작업 완료 stdout 도착 시 LLM 재작성 → bot.py → Discord 보강 push
6. 무한 루프 방어: helper prompt 에 `[origin=nmae]` tag 가 있는 입력은 사용자 응답으로 재해석하지 않음

### 5-5) DB 마이그레이션
- 없음.

### 5-6) 프론트엔드 화면
- 없음 (Discord UX 만).

## 6) 작업 분할 (예상 PR 리스트)
- [ ] **PR-A** — NCP tmux helper session 신설 스크립트 `tools/deploy/setup-helper-tmux.sh` (idempotent, scope:infra)
- [ ] **PR-B** — helper 초기 프롬프트 파일 `tools/helper/initial-prompt.md` (정중체 / 4단계 패턴 / 위임 패턴 / 루프 방어 룰 / 약어 nmae/mmae 명시, scope:infra)
- [ ] **PR-C** — bot.py routing 변경: 사용자 Discord → helper tmux 우선, nmae 직접 send 폐지. helper pipe-pane raw push 로직 추가 (scope:infra)
- [ ] **PR-D** — systemd unit `mobruji-helper-tmux.service` (Restart=always, EnvironmentFile, scope:infra, `needs-human-review`)
- [ ] **PR-E** — 검증 시나리오 문서 `docs/ai-harness/16-helper-agent-runbook.md` + smoke test (사용자 query → helper ack → helper 자체 답 → nmae 위임 → nmae 응답 → helper 보강 push 6단계, scope:infra)

## 7) 테스트 전략
- **단위**: bot.py routing 함수 (helper vs nmae 분기) + helper pipe-pane raw push (ANSI strip / 2000자 split) pytest 케이스
- **통합**: NCP 스테이징에서 tmux helper / nmae 동시 가동 + mock Discord 메시지 6종 (즉시 답 단순 query 2, nmae 바쁨 시 query 1, 위임 1, 루프 시도 1, helper crash 1)
- **E2E**: 실제 #모부르지 채널에서 PR-E smoke test 6단계 시나리오 수동 실행 (1회/배포 전). 응답 시간 P50/P99 측정 포함.
- **장기 모니터링**: `journalctl -u mobruji-helper-tmux --since "1 hour ago"` 매시간 cron digest 에 포함. 응답 시간 SLO 위반 시 alert.

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 | 우선순위 |
|---|---|---|---|---|
| Q1 | Max OAuth multi-device 정책상 같은 계정 2 device 동시 사용 허용 여부? | (a) 허용 → 옵션 A 채택 / (b) 차단 → 옵션 B (API key) 즉시 채택 | @goohong / PR-A 직전 | **최상 (blocker)** |
| Q2 | mmae 의 Discord polling 을 완전 비활성화할지, read-only fallback 으로 둘지? | (a) 완전 비활성화 (단일 channel owner = helper) / (b) helper down 시 fallback | @goohong / PR-C 직전 | 중 |
| Q3 | helper 가 nmae stdout 을 polling 하는 주기? | (a) 2초 tail -F / (b) 이벤트 기반 (inotify) | @goohong / PR-A | 중 |
| Q4 | helper 별도 CLAUDE.md 둘지, 기존 메모리 공유할지? | (a) 별도 `docs/ai-harness/12-helper-agent.md` / (b) 기존 `/home/mobruji/.claude/projects/.../memory/` 공유 + helper 전용 룰만 추가 | @goohong / PR-B | 중 |
| Q5 | helper 의 "10초 자체 답" 단계에서 정보 부족 판단 시 행동? | (a) 부족함을 솔직히 push 후 nmae 위임 / (b) 더 깊이 reasoning (시간 초과 허용) | @goohong / PR-B | 하 |

## 9) 결정 로그
- 2026-05-23: 초안 작성 (status=draft). 사용자 긴급 위임 — 폰만으로 운영하려면 helper 필수. 구현은 후속 PR 5건 분할.
- 2026-05-23 (patch): 사용자 추가 결정 4건 반영
  - 즉시 답 4단계 패턴 (1초 ack / 10초 자체 답 / 위임 병행 / 보강 push) 명문화
  - helper Discord = terminal raw push (bot.py 변환 X, form generator 는 nmae 전용)
  - nmae 작업/digest + helper 양방향 역할 분리 §4 신설
  - 약어 통일 (mac maestro → mmae, ncp maestro → nmae) 전 문서 적용
  - Q1 (Max OAuth multi-device) 우선순위 최상 blocker 로 상향
- 2026-05-23 (patch #880): ack/thread/reply UX 묶음 PR. 상세 spec: `docs/features/helper-thread-stream.md`.
  - **bot.py 1초 generic auto-ack 부활** — `BOT_AUTO_ACK=1` (default). #807 제거됐던 것 복구. helper 자체 ack 까지 bash chain latency 5+초 깜깜이 해소.
  - **Discord thread stream** — `discord-reply.sh --ack`/`--thread` 모드 추가. helper 가 매 도구 milestone 1줄씩 thread 에 stream → 메인 채널 잡음 없이 실시간 가시화.
  - **reply.referenced_message forward** — bot.py 가 사용자 답장의 reference 본문을 30자 요약해 prefix `[답장→ ...] <body>` 형태로 helper 에 전달.
  - CLAUDE.md §11 helper 룰 갱신은 후속 PR.

## 부록 A — 위험 / 한계
- helper LLM 비용 (Max OAuth quota 또는 API key 결제)
- nmae 와 helper 동시 작업 시 conflict (tmux pane 환경 변수 / git worktree 공유)
- helper crash 시 사용자 응답 끊김 → systemd Restart=always 로 완화하나 코드 버그 시 무한 재기동 루프 위험 → `RestartSec=5`, `StartLimitBurst=10`
- 무한 위임 루프 (helper → nmae → helper 응답 → helper 다시 위임…) → helper 프롬프트에 `[origin=nmae]` tag 검사 룰 강제
- 10초 자체 답 SLO 위반 시 (LLM 지연 / pane capture 실패) → ack 와 자체 답 사이 침묵 → 사용자 답답함 재발 위험. 모니터링 alert 필수.
- helper raw push 와 nmae 정형 push 가 같은 채널에 섞임 → 메시지 origin 구분 prefix (`[helper]` / `[nmae]`) 또는 Discord 이모지 prefix 검토

## 부록 B — 자연 흡수되는 기존 문제
- ANSI escape sequence: bot.py 의 strip 단계에서 일괄 처리 (helper LLM 재작성으로 자동 흡수되지 않음 — raw push 결정 때문)
- 정중체 변환: helper prompt 에 정중체 강제 → bot.py 정형 generator 보강 작업 폐기 가능 (다만 nmae 정형 push 용으로는 유지)
- 메시지 분할: bot.py 2000자 split 단계에서 처리 (helper 가 LLM 단계에서 인지하지 않아도 안전)
- 사용자 응답 지연 (nmae 바쁨): 4단계 패턴의 1~2단계가 구조적 해소
