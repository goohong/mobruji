---
feature: 자율 사이클 오케스트레이션 (4 워크트리 동시 가동 + cycle-status digest + worktree lock + helper boundary)
slug: autonomous-cycle-orchestration
status: implementing
owner: @mobruji-maestro
scope: infra
related_issues: []
related_prs: [811, 822, 826, 830, 832, 839, 842, 843, 844, 852, 856]
last_reviewed: 2026-05-23
---

# 자율 사이클 오케스트레이션 — 4 워크트리 동시 가동 + cycle-status digest

## 1) 개요 (What / Why)
지난 ~3시간 nmae (NCP maestro) 자율 사이클 운영에서 **반복 패턴 6종** 이 누적 발견되었으나 spec 화 부재로 매 turn maestro ad-hoc 판단에 의존했다. 본 spec 은 이를 정형화하여 nmae 가 사용자 부재 시에도 결정적으로 4 워크트리를 동시 가동·관측·복구할 수 있게 한다.

**대상 액터**: nmae (오케스트레이터), be/fe/rev/plan 4 워크트리 sub-agent, helper (사용자 양방향), bot.py (digest cron), 오너 (Discord 단방향 observer).

**해결 문제**:
1. 4 워크트리 동시 사이클 시 같은 도메인 lock / branch checkout 충돌 (worktree 한 곳 = 동시 sub-agent 1) 룰 부재
2. Stale 이슈 다수 (fe 6/7 stale 사례) — verification 없이 사이클 launch 시 wasted compute
3. helper 가 PR/bot.py/구현 작업까지 떠안아 nmae 와 충돌 (역할 경계 모호)
4. rev audit follow-up 이슈가 다음 사이클로 자동 연결되지 않음 → 🔴 시급 항목 지연
5. cycle-status 외부 노출 부재 → 사용자 폰만 운영 시 4 워크트리 상태 파악 불가

## 2) 용어 / 약어
| 약어 | 의미 |
|---|---|
| **mmae** | mac maestro (오너 mac 호스트, 옵션) |
| **nmae** | ncp maestro (NCP 상주 오케스트레이터 — 본 spec 주체) |
| **helper** | NCP 상주 사용자 양방향 전담 (참고: `helper-agent.md`) |
| **sub-agent** | nmae 가 Agent 도구로 launch 하는 워크트리 1회용 워커 |
| **worktree** | git worktree 분리 (`mobruji-be` / `mobruji-fe` / `mobruji-rev` / `mobruji-plan`) — 각 1 도메인 |
| **worktree lock** | 한 워크트리 = 동시 sub-agent 1 (브랜치/working tree 공유 불가) |
| **stale verification** | 이슈/PR 백로그 picking 직전 현재성 검증 (`gh pr list --search` + grep) |
| **cycle-status** | `~/.mobruji/cycle-status.json` — 4 워크트리 현재 상태 단일 source-of-truth |

## 3) 요구사항
### 기능 요구사항
- [x] **4 워크트리 동시 사이클** — be/fe/rev/plan 4 분리 워크트리. 각 워크트리 1 sub-agent launch. 4 동시 가동 기본 모드. ([[feedback-keep-4-cycles-active]] 메모리 반영)
- [x] **cycle-status.json schema + bot.py digest cron 연동** — nmae 매 launch / 완료 / 머지 이벤트 시 JSON 갱신. bot.py digest cron 4 워크트리 진행 + 최근 한 줄씩 push (관련 PR #804/#806/#808)
- [x] **Sub-agent launch 시 stale verification 의무** — `gh pr list --search "..." --state all` + grep 으로 이미 처리된 이슈/PR 인지 확인. stale 발견 시 close + 다음 백로그 picking
- [x] **worktree branch checkout 충돌 회피** — `git checkout -b <new-branch> origin/develop` 패턴 강제 (다른 워크트리가 develop 점유 시 conflict 회피). 기존 작업 브랜치 잔재는 `git reset --hard HEAD` 로 정리
- [x] **helper boundary** — helper 는 (i) 사용자 응답 + (ii) helper 자체 룰/메모리/스크립트 수정만. 그 외 (PR / bot.py / sub-agent launch / 이슈 등록 / release) 는 nmae 위임. (CLAUDE.md §11-pre-pre + [[feedback-helper-role-boundary]] 메모리)
- [x] **rev audit → follow-up 이슈 자동 등록 → 다음 사이클 위임** — rev sub-agent 가 audit 결과 코멘트 작성 후 nmae가 🔴 시급 항목을 같은 사이클 내 이슈 등록 + 다음 사이클 fe/be/plan 위임 ([[feedback-auto-register-rev-findings]] + [[feedback-rev-release-gate]] 메모리)
- [ ] **release cut 자동 트리거** — 현재 미구현. 사용자 결정 의존 (D12, §7 오픈 이슈)

### 비기능 요구사항
- [ ] **결정성**: 사이클 picking 순서 재현 가능 (현재 ad-hoc — picking 우선순위 정형화 필요)
- [x] **관측성**: cycle-status.json + bot.py digest Discord embed (워크트리 emoji 구분, 관련 PR #850)
- [ ] **context 폭주 방지**: 1 turn 1 launch 룰 (현재 ad-hoc, 정형화 필요. [[feedback-reasoning-chunk-limit]] 메모리 보완)
- [x] **격리**: worktree lock — 한 워크트리 = 동시 sub-agent 1. 같은 도메인 백로그 2 건 동시 launch 금지 ([[feedback-worktree-lock]] 메모리)
- [x] **안전성 (helper boundary)**: helper 본체가 코드/PR 직접 수정 금지 (CLAUDE.md §11-pre-pre 룰)
- [x] **fail-safe (worktree 복구)**: stash pop conflict 후 drop 시 working tree 잔재 → service crash 방지 패턴 ([[feedback-stash-drop-unmerged-file]] 메모리). npm install 시 node_modules symlink 풀림 → install 후 즉시 swap 복구 ([[feedback-npm-install-symlink-swap]] 메모리)

## 4) 범위 / 비범위
### 포함
- 4 워크트리 동시 가동 운영 룰 정형화
- cycle-status.json schema (JSON 구조 + 갱신 시점 + bot.py 소비)
- worktree state machine (idle / launched / merged / locked)
- stale verification 표준 명령
- helper boundary 강제 (현재는 룰 — 자동 enforce 는 §7 오픈 이슈)
- rev audit → follow-up 자동 등록 사슬

### 제외 (Out of Scope)
- **release cut 자동화** — 사용자 결정 (D12) 으로 미루어짐. 본 spec 은 자동 트리거 후크만 명시, 실 구현은 별 spec.
- **helper boundary 자동 enforce** — 본 spec 은 룰 명시 / monitoring 패턴까지. tmux pre-exec hook 또는 git pre-commit 자동 차단은 별 spec.
- **sub-agent 자체의 자율 사이클** — sub-agent 는 1회용 워커, 본 spec 은 nmae (오케스트레이터) 만 대상.
- **다중 nmae 인스턴스** — NCP 단일 nmae 전제. multi-NCP horizontal scaling 은 OOS.
- **context auto-clear / helper agent** — 별 spec (`context-auto-clear.md`, `helper-agent.md`) 에서 상세 다룸. 본 spec 은 cross-reference 만.

## 5) 설계
### 5-1) 도메인 모델 — cycle-status.json schema
**경로**: `~/.mobruji/cycle-status.json` (NCP nmae 가 단일 writer, bot.py 가 reader)

```json
{
  "updated_at": "2026-05-23T14:23:00+09:00",
  "worktrees": {
    "be": {
      "state": "launched",
      "current_sub_agent": "issue #890 BE digest sanitize",
      "started_at": "2026-05-23T14:18:00+09:00",
      "last_merged_pr": 856,
      "recent_line": "spotless 통과, gradle test 진행 중"
    },
    "fe": {
      "state": "idle",
      "current_sub_agent": null,
      "started_at": null,
      "last_merged_pr": 852,
      "recent_line": "Round 7 머지 완료, 다음 백로그 picking 대기"
    },
    "rev": {
      "state": "launched",
      "current_sub_agent": "v0.4.0-rc release audit",
      "started_at": "2026-05-23T14:20:00+09:00",
      "last_merged_pr": null,
      "recent_line": "PR #844 audit 진행 중"
    },
    "plan": {
      "state": "launched",
      "current_sub_agent": "autonomous-cycle-orchestration spec",
      "started_at": "2026-05-23T14:22:00+09:00",
      "last_merged_pr": 852,
      "recent_line": "spec frontmatter 작성 중"
    }
  }
}
```

**state 값**:
- `idle` — sub-agent 없음. 다음 백로그 picking 대기
- `launched` — sub-agent 가동 중. 새 launch 금지 (worktree lock)
- `merged` — sub-agent 완료 + PR 머지. cycle-status 다음 갱신 직전 transient state
- `locked` — helper-launched 또는 외부 점유 (예: 사용자가 워크트리 점검 중). nmae 자동 launch 금지

### 5-2) worktree state machine
```
        ┌──────────────────────────────────────────────────────┐
        │                                                      │
        ▼                                                      │
     idle  ──(picking + launch)──► launched ──(완료+머지)──► merged
        │                              │                       │
        │                              │                       │
        │                              ▼                       │
        └────(stale 발견 시 close + 재 picking)         (status갱신 → idle)
                                       
     idle  ──(helper-launched 또는 외부 점유)──► locked
                                                  │
                                                  ▼
                                              (해제 시 → idle)
```

전이 트리거 (nmae 책임):
- `idle → launched`: nmae Agent 도구 launch 직후 cycle-status `state=launched` + `current_sub_agent` 기록
- `launched → merged`: sub-agent 결과 returned + nmae 가 PR squash merge 완료
- `merged → idle`: cycle-status `last_merged_pr` 갱신 + `state=idle` 전환
- `* → locked`: helper 가 `cycle-status.json` 의 해당 워크트리 `state=locked` 로 직접 갱신 (helper 자체 작업이므로 §11-pre-pre boundary 룰 예외)
- `locked → idle`: helper 작업 완료 후 helper 가 `state=idle` 로 복귀

### 5-3) 시퀀스 — 정상 1 사이클
```
nmae turn 시작
  │
  ├─ cycle-status.json read → 4 워크트리 state 확인
  │
  ├─ idle 워크트리 발견 (예: fe)
  │
  ├─ 백로그 picking (gh issue list --label "scope:web" --state open)
  │
  ├─ stale verification ─► `gh pr list --search "<issue#> in:title" --state all`
  │     │
  │     ├─ stale 발견 → 이슈 close + 다음 백로그
  │     └─ fresh → 다음 단계
  │
  ├─ cycle-status.json 갱신 (fe state=launched)
  │
  ├─ Agent 도구 launch (fe sub-agent, self-contained prompt)
  │
  ├─ (병행) 다른 idle 워크트리 같은 절차 반복 (be / rev / plan)
  │
  └─ turn 종료 (===CTX:NN%===)
                  │
                  ▼
        (sub-agent 작업 진행)
                  │
                  ▼
        sub-agent 완료 통지 → nmae 다음 turn 깨움
                  │
                  ▼
        nmae: PR squash merge + cycle-status 갱신 (fe state=idle + last_merged_pr)
                  │
                  ▼
        (다음 백로그 picking → 위 사이클 반복)
```

### 5-4) bot.py digest 연동
- bot.py `digest_loop` (이미 #804/#806/#808 에서 구현됨) 매 N분 주기:
  1. `~/.mobruji/cycle-status.json` read
  2. 4 워크트리 state + recent_line 한 줄씩 추출
  3. Discord embed 4 field (워크트리별 emoji prefix — be ⚙️ / fe 🎨 / rev 🔍 / plan 📋, PR #850 참조)
  4. DIGEST_CHANNEL_ID (디제스트 전용, #1019 rename — 기존 `NOTIFY_CHANNEL_ID` backward-compat) push. MOBRUJI_CHANNEL_ID (사용자 양방향) 와 분리 ([[project-discord-channel]] + CLAUDE.md §11-pre)

- nmae 측 의무: cycle-status.json 은 **반드시 모든 launch/완료/머지 시점 갱신**. 갱신 누락 시 bot.py digest 가 stale data 노출 → 사용자 오해 ([[feedback-cycle-status-json]] 메모리)

### 5-5) stale verification 표준 명령
nmae sub-agent launch 직전 picking 한 이슈에 대해 다음 명령으로 verification:

```bash
# 이슈 # 가 이미 PR 로 처리되었거나 closed 인지 확인
gh issue view <num> --json state,closedAt -q '.state'
gh pr list --search "fixes #<num> OR closes #<num> OR resolves #<num>" --state all --json number,state

# 라벨 기반 picking 시 (예: fe 백로그)
gh issue list --label "scope:web" --state open --json number,title,labels,updatedAt \
  | jq -r '.[] | select(.updatedAt < "2026-05-20") | .number'  # 7일 이상 stale
```

stale 결정 룰:
- 이슈에 머지된 PR 가 이미 매핑되어 있음 → close (`Already implemented by PR #<n>`)
- 7일 이상 update 없고 라벨/제목 명확하지 않음 → close (`stale, no clear scope`)
- 위 둘 다 아님 → fresh, launch 진행

### 5-6) helper boundary 강제 (CLAUDE.md §11-pre-pre 재기재)
helper 는 다음만 직접 수정:
1. helper 자체 ack 룰 (`/home/mobruji/.mobruji/discord-reply.sh`)
2. helper CLAUDE.md §11-pre / §11-pre-pre
3. helper 자기 메모리 (`feedback_helper_*.md`)

그 외 (PR 머지 / bot.py 수정 / sub-agent launch / 이슈 등록 / release 준비 / 코드 수정) 는 **무조건 nmae 위임**:
- 위임 경로 A (기본): `tmux send-keys -t mobruji:0.0 "<지시문>" Enter`
- 위임 경로 B (helper 가 sub-agent 띄워야 한다고 판단 시): Agent 도구로 sub-agent 1회 띄움. helper 본체는 결과 대기만.

**위반 시 영향**: helper 와 nmae 가 같은 파일/PR 을 동시 점유 → working tree 충돌 + cycle-status 갱신 race. 시스템 안정성 핵심 룰.

## 6) 운영 시나리오
### 시나리오 A — stale 발견 → 이슈 close + 다음 백로그
1. nmae idle fe 워크트리 picking — 이슈 #688 (Round 6 a11y 백로그)
2. stale verification: `gh pr list --search "fixes #688" --state all` → PR #790 가 이미 close 한 사실 확인
3. nmae: `gh issue close 688 -c "Already implemented by PR #790"`
4. fe 백로그 다음 picking (#508) → stale verification 재진행
5. fresh 확인 후 launch
6. cycle-status.json `fe.state=launched`

### 시나리오 B — rev audit 🔴 발견 → Discord push + fe 우선순위 변경
1. rev sub-agent v0.4.0-rc release audit 완료. 코멘트에 🔴 (P95 regression / 응답시간 SLO 위반) 발견
2. nmae 다음 turn 깨움 → rev 결과 read
3. nmae: 같은 turn 내 `gh issue create` 로 follow-up 이슈 등록 (`label: priority:high,scope:web`)
4. DIGEST_CHANNEL_ID 에 Discord push (#1019 rename — 기존 `NOTIFY_CHANNEL_ID` backward-compat) (`🔴 rev audit: P95 950ms (SLO 800ms) — fe 우선순위 변경 launch`)
5. fe 다음 picking 은 일반 백로그 대신 새 이슈 우선
6. cycle-status.json `fe.current_sub_agent` 갱신

### 시나리오 C — PR 머지 충돌 (worktree branch 점유)
1. nmae be sub-agent 완료 → PR #870 squash merge
2. nmae 가 be 워크트리에서 `git checkout develop && git pull` 시도 → 다른 워크트리 (be) 가 develop 점유 중 → conflict
3. 회피 패턴: 같은 워크트리에서 다음 sub-agent launch 시 `git checkout -b <new-branch> origin/develop` 으로 직접 origin 기준 분기 (local develop branch 의존 X)
4. local develop 정리는 별 cron 또는 idle 시점에만 수행

### 시나리오 D — be lock (helper-launched)
1. helper 가 BE 관련 자체 점검 작업 필요 (예: bot.py 수정 — 단, §5-6 boundary 위반이므로 실제는 nmae 위임)
2. **올바른 흐름**: helper 가 nmae 에 `tmux send-keys` 로 위임. be 워크트리는 nmae 가 launch
3. **위반 시**: helper 가 be 워크트리 직접 점유 → cycle-status `be.state=locked` 갱신 의무. nmae 는 locked 워크트리 launch 안 함
4. 결과: 3 워크트리만 (fe/rev/plan) 가동, be 는 helper 해제까지 idle locked

## 7) 오픈 질문
| # | 질문 | 선택지 | 담당/기한 | 우선순위 |
|---|---|---|---|---|
| Q1 | release cut 자동 트리거 (D12) | (a) develop → main 자동 release PR 생성 + 사용자 ack 만 / (b) 현 수동 유지 | @user / v0.5.0 계획 시 | 중 |
| Q2 | 1 turn 1 launch 룰 정형화 | (a) nmae prompt 에 hard rule / (b) 현 ad-hoc | @nmae / 다음 spec 갱신 | 중 |
| Q3 | helper boundary 자동 enforce | (a) tmux pre-exec hook 로 helper 의 PR / gh issue create 차단 / (b) 현 룰 only | @user / boundary 위반 사고 발생 시 | 하 |
| Q4 | cycle-status.json schema 버저닝 | (a) `schema_version` 필드 추가 / (b) breaking change 없으면 미도입 | @nmae / bot.py reader 변경 시 | 하 |

## 8) 결정 로그
- 2026-05-23: 초안 작성 (status=implementing — 관련 PR 11건 이미 머지된 상태). 4 워크트리 동시 + cycle-status digest + worktree lock + helper boundary 4 축 정형화. release cut 자동화는 D12 사용자 결정 대기로 §7 오픈. 1 turn 1 launch + helper boundary 자동 enforce 도 후속 결정.
