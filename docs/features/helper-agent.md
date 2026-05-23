---
feature: Helper Agent (NCP 영구 가동으로 mac maestro 대체)
slug: helper-agent
status: draft
owner: @goohong
scope: infra
related_issues: []
related_prs: []
last_reviewed: 2026-05-23
---

# Helper Agent — NCP 영구 가동으로 mac maestro 대체

## 1) 개요 (What / Why)
- 현재 운영 모델은 **mac maestro 본진**이 켜져 있어야만 사용자가 폰만으로 모부르지 운영을 지속할 수 있다. mac 종료/sleep/네트워크 단절 시 사용자 응답 채널이 끊긴다.
- 사용자 요청 원문 (2026-05-23): *"폰만으로 운영하려면 mac maestro 역할을 NCP helper agent 가 영구 대체."*
- 본 spec 은 NCP 상주 `helper` Claude CLI 세션을 신설하여 mac maestro 의 4가지 역할(사용자 query 직접 답 / maestro 위임 / sub-agent 진척 정리 / Discord push 정중체 변환) 모두 NCP 에서 영구 가동하도록 한다.
- 대상 액터: 오너 (이동 중 폰 Discord 단독 사용), NCP 상주 `helper` 세션, NCP 상주 `maestro` 실 작업 세션, 백그라운드 sub-agent (be/fe/rev/plan).

## 2) 사용자 시나리오
- **시나리오 1 — mac 부재, 단순 query**: 오너가 외출 중 폰 Discord 로 "오늘 머지된 PR 몇 개야?" 를 보낸다. bot.py 가 helper tmux 로 send-keys. helper 가 자체 reasoning 후 정중체 답을 bot.py 로 출력 → Discord push. mac 개입 0.
- **시나리오 2 — mac 부재, 실 작업 위임**: 오너가 "PR #790 머지해줘" 를 보낸다. helper 가 의도 파악 후 maestro tmux 로 self-contained prompt send-keys. maestro 가 sub-agent launch / 머지 / 통지. helper 가 maestro stdout(pipe-pane 캡처) 을 폴링·정리해 "머지 완료" 정중체로 Discord push.
- **시나리오 3 — mac 재기동 후 충돌 회피**: mac maestro 가 다시 켜져도 사용자 Discord 메시지는 helper 가 1차 수신하므로 mac maestro 는 sub-agent 오케스트레이션만 담당. 동일 메시지 중복 응답 방지를 위해 mac maestro 의 Discord 직접 polling 은 비활성화 또는 read-only 로 강등.

## 3) 요구사항
### 기능 요구사항
- [ ] NCP 에 tmux session `helper` 신설 (claude CLI 상주, Restart=always)
- [ ] bot.py routing 변경: 사용자 Discord 메시지는 **helper tmux 로 우선 send-keys** (현재 maestro 직접 send 폐지 또는 후순위)
- [ ] helper 가 사용자 query 의도 분기:
  - (a) 단순 응답 → 자체 reasoning → bot.py 로 stdout → Discord push
  - (b) 실 작업 필요 → `tmux send-keys -t mobruji:0.0 "<prompt>" Enter` 로 maestro 위임
- [ ] helper 가 maestro stdout 캡처 (`pipe-pane` → 로그 파일 `tail -F`) → LLM 으로 정중체 재작성 → bot.py → Discord push
- [ ] helper LLM 출력은 ANSI escape 자동 제거 (LLM 재작성 단계에서 자연 흡수)
- [ ] helper crash 시 systemd Restart=always 로 자동 복구
- [ ] 무한 위임 루프 방어 (helper → maestro → helper 응답 → helper 다시 위임…) — turn counter 또는 origin tag 로 차단

### 비기능 요구사항
- 사용자 메시지 수신부터 helper 첫 응답까지 P50 ≤ 30 초, P99 ≤ 2 분
- helper 가용성 99% (systemd Restart=always + NCP 인스턴스 가용성)
- LLM 비용: Max OAuth multi-device 우선 (월 추가 비용 0), fail 시 Anthropic API key (Haiku, 월 $5 한도 가정)
- 보안: helper 가 사용하는 OAuth 토큰 / API key 는 `.env` (mode 0600) + systemd `EnvironmentFile=`. 평문 저장 / 코드 하드코딩 금지 (`docs/ai-harness/04-security-policy.md` 준수)
- 관측성: helper stdout 을 `journalctl -u mobruji-helper-tmux` 로 노출. 위임 이벤트는 `[helper→maestro]` prefix 로 grep 가능

## 4) 범위 / 비범위
### 포함
- helper 세션 아키텍처 설계 (tmux + claude CLI + bot.py routing)
- 인증 옵션 비교 (Max OAuth multi-device vs Anthropic API key)
- mac maestro 와의 역할 분담 재정의
- 무한 위임 루프 방어 설계
- 후속 구현 PR 5건 분할

### 제외 (Out of Scope)
- 구현 코드 (스크립트 / systemd unit / bot.py patch) — 후속 PR 에서 진행
- helper 가 직접 sub-agent (be/fe/rev/plan) launch 하는 기능 — 본 spec 에서는 maestro 경유만
- 다중 오너 / 다중 채널 라우팅 (현재 #모부르지 단일 채널)
- helper LLM 모델 다중화 (단일 모델 가정, 추후 cost optimize 시 재설계)

## 5) 설계
### 5-1) 도메인 모델
- 도메인 엔티티 변경 없음. 인프라/툴링 레이어 전용.
- 외부 컴포넌트: Discord Gateway, bot.py (NCP), tmux (NCP), claude CLI (NCP), Anthropic API (Max OAuth or key), 기존 maestro tmux session.

### 5-2) 구조 다이어그램
```
사용자 (폰 Discord)
   │
   ▼
bot.py  (NCP, 기존)
   │  tmux send-keys -t helper
   ▼
helper tmux session  (NCP, 신설, claude CLI)
   │  reasoning
   ├── 직접 응답 ──────────────► bot.py ──► Discord push (정중체)
   │
   └── maestro 위임 시
        │  tmux send-keys -t mobruji:0.0
        ▼
        maestro tmux session  (기존)
           │  stdout (pipe-pane → /tmp/maestro-pane.log)
           ▼
        helper 캡처 (tail -F + LLM 재작성)
           │
           ▼
        bot.py ──► Discord push (정중체)
```

### 5-3) 외부 연동
- **Anthropic API** (helper LLM): 두 옵션
  - 옵션 A — Max OAuth multi-device: 본진 mac maestro 와 동일 계정, `claude --login` 으로 NCP 에 별도 device 로 로그인. **가성비 1차 시도**. 한계: Max quota 공유, multi-device 정책 변경 위험.
  - 옵션 B — Anthropic API key (Haiku): `ANTHROPIC_API_KEY` env 로 별도 결제. **안정 fallback**. 한계: 월 비용 발생.
  - 권장: A 시도 → quota / 정책 fail 시 B 즉시 전환.
- **Discord Gateway**: bot.py 가 기존 그대로 사용. helper 는 Discord 직접 접근하지 않음 (bot.py 가 유일 채널).
- **tmux**: NCP 에 `helper`, `mobruji` 두 세션 병행. send-keys 는 단방향 텍스트 주입, 출력은 pipe-pane 으로 파일 캡처.

### 5-4) 데이터 흐름 / 시퀀스
1. 사용자 → Discord 메시지
2. bot.py on_message → `tmux send-keys -t helper "<user-msg>" Enter`
3. helper Claude CLI reasoning:
   - 단순 query → 자체 답 stdout
   - 실 작업 query → `tmux send-keys -t mobruji:0.0 "<self-contained prompt>" Enter` + maestro 응답 polling 모드 진입
4. helper stdout 은 별도 pipe-pane 파일로 캡처 → bot.py 가 tail 하여 Discord push
5. maestro 위임 시: helper 가 maestro pipe-pane 로그 tail → 진척 메시지 LLM 정리 → bot.py → Discord
6. 무한 루프 방어: helper prompt 에 `[origin=maestro]` tag 가 있으면 사용자 응답으로 재해석하지 않음

### 5-5) DB 마이그레이션
- 없음.

### 5-6) 프론트엔드 화면
- 없음 (Discord UX 만).

## 6) 작업 분할 (예상 PR 리스트)
- [ ] **PR-A** — NCP tmux helper session 신설 스크립트 `tools/deploy/setup-helper-tmux.sh` (idempotent, scope:infra)
- [ ] **PR-B** — helper 초기 프롬프트 파일 `tools/helper/initial-prompt.md` (정중체 / 위임 패턴 / 루프 방어 룰 포함, scope:infra)
- [ ] **PR-C** — bot.py routing 변경: 사용자 Discord → helper tmux 우선, maestro 직접 send 폐지 (scope:infra)
- [ ] **PR-D** — systemd unit `mobruji-helper-tmux.service` (Restart=always, EnvironmentFile, scope:infra, `needs-human-review`)
- [ ] **PR-E** — 검증 시나리오 문서 `docs/ai-harness/16-helper-agent-runbook.md` + smoke test (사용자 query → helper 응답 → maestro 위임 → 사용자 응답 4단계, scope:infra)

## 7) 테스트 전략
- **단위**: bot.py routing 함수 (helper vs maestro 분기) pytest 케이스
- **통합**: NCP 스테이징에서 tmux helper / maestro 동시 가동 + mock Discord 메시지 5종 (단순 query 2, 위임 1, 루프 시도 1, helper crash 1)
- **E2E**: 실제 #모부르지 채널에서 PR-E smoke test 4단계 시나리오 수동 실행 (1회/배포 전)
- **장기 모니터링**: `journalctl -u mobruji-helper-tmux --since "1 hour ago"` 매시간 cron digest 에 포함

## 8) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | Max OAuth multi-device 정책상 같은 계정 2 device 동시 사용 허용 여부? | (a) 허용 → 옵션 A 채택 / (b) 차단 → 옵션 B (API key) 즉시 채택 | @goohong / PR-A 직전 |
| Q2 | mac maestro 의 Discord polling 을 완전 비활성화할지, read-only fallback 으로 둘지? | (a) 완전 비활성화 (단일 channel owner = helper) / (b) helper down 시 fallback | @goohong / PR-C 직전 |
| Q3 | helper 가 maestro stdout 을 polling 하는 주기? | (a) 2초 tail -F / (b) 이벤트 기반 (inotify) | @goohong / PR-A |
| Q4 | helper 별도 CLAUDE.md 둘지, 기존 메모리 공유할지? | (a) 별도 `docs/ai-harness/12-helper-agent.md` / (b) 기존 `/home/mobruji/.claude/projects/.../memory/` 공유 + helper 전용 룰만 추가 | @goohong / PR-B |

## 9) 결정 로그
- 2026-05-23: 초안 작성 (status=draft). 사용자 긴급 위임 — 폰만으로 운영하려면 helper 필수. 구현은 후속 PR 5건 분할.

## 부록 A — 위험 / 한계
- helper LLM 비용 (Max OAuth quota 또는 API key 결제)
- maestro 와 helper 동시 작업 시 conflict (tmux pane 환경 변수 / git worktree 공유)
- helper crash 시 사용자 응답 끊김 → systemd Restart=always 로 완화하나 코드 버그 시 무한 재기동 루프 위험 → `RestartSec=5`, `StartLimitBurst=10`
- 무한 위임 루프 (helper → maestro → helper 응답 → helper 다시 위임…) → helper 프롬프트에 `[origin=maestro]` tag 검사 룰 강제
- bot.py 가 helper 와 maestro 양쪽 stdout 을 모두 push 하면 중복 → bot.py 가 helper 출력만 push, maestro 출력은 helper 가 정리 후 우회 전달

## 부록 B — 자연 흡수되는 기존 문제
- ANSI escape sequence: helper LLM 재작성 단계에서 자동 제거 → 기존 watcher 의 ANSI strip 로직 불필요
- 정중체 변환: helper prompt 에 정중체 강제 → bot.py 정형 generator 보강 작업 폐기 가능 (다만 helper down 시 fallback 으로 유지)
- 메시지 분할: helper 가 LLM 단계에서 Discord 2000자 제한 인지하여 분할 출력
