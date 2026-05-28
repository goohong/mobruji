# nmae Runbook — NCP maestro 전용

> **로드 대상: nmae 세션(NCP `tmux mobruji:0.0`)만.** 다른 actor(helper/be/fe/rev/plan)는 읽지 않습니다.
> CLAUDE.md §11(+§13 부록)에서 분리 (2026-05-28 actor-scoped 문서화 — universal 로드 노이즈 제거).
> 충돌 시 본 문서 + 강제 코드(bot.py watchdog / wrapper)가 우선.

---

## 11) nmae 전용 룰 (NCP maestro 오케스트레이션)

> 대상: NCP 호스트 `tmux mobruji:0.0`. 메인 사이클 launch + cycle-status + 머지.
> 메모리 actor: `nmae`.

### 11-1) 항시 가동 — 사이클 idle 금지 (3중 안전망)

**사용자 2026-05-24 명시: "절대로 nmae 사이클이 멈춰서는 안돼".**

1. **bot.py `cycle_idle_watch_loop`** (외부 데몬 watchdog) — 5분 polling `~/.mobruji/cycle-status.json`. idle 발견 시 자동 nmae tmux inject + Discord push. spec: `docs/features/nmae-cycle-watchdog.md`. 메모리 [[feedback-nmae-cycle-watchdog]]
2. **nmae 매 turn 종료 직전 자기 점검** — cycle-status.json 4 워크트리 active 검증. idle 시 즉시 launch. 메모리 [[feedback-keep-4-cycles-active]]
3. **helper 가 사용자 메시지 처리 중 cycle-status.json 우연 발견 시** — idle 발견 시 helper 가 직접 tmux inject 가능 (같은 서버, [[feedback-helper-role-boundary]] nmae 위임 영역)

**idle 정의**: `in_progress: null` AND `last_completed.completed_at` > `now - 10분`.

**nmae 가 까먹는 반복 패턴**:
- sub-agent 완료 통지 처리 → cycle-status.json 갱신 → 다음 launch 까먹음
- 자기 turn 안 priority 에 밀림
- 메모리 룰 학습됐어도 행동 안 함

따라서 1번 (외부 watchdog) 가 핵심. nmae 룰 위반 시 자동 정정.

### 11-2) watchdog inject 대응 의무 절차 (#972, 비협상)

inject 받으면 **다음 turn 시작 즉시** 4단계 순서대로 수행 (누락 시 다음 5분 polling 에서 또 inject = 무한 idle loop):

| 단계 | 명령 | 의미 |
|---|---|---|
| 1 | 백로그 후보 1개 선정 | 위반 시: 그냥 inject 무시 = 무한 loop |
| 2 | `bash tools/cycle-status/update.sh <ws> set-active --title "<후보>"` <br>(권장: `bash tools/agent-launch-wrapper.sh <ws> --title "<후보>"` — set-active + launch 안내 + per-cycle 채널 launch 알림/thread 자동 push 한 번에, #1008 + B2) | cycle-status.json `in_progress` 채워 idle 분류 탈출 + cycle 채널 가시화 |
| 3 | `Agent` tool 로 sub-agent launch (worktree=`/home/mobruji/mobruji-<ws>`) | 실제 작업 위임 |
| 4 | `bash /home/mobruji/.mobruji/discord-reply.sh "<ws> 사이클 재개 — <후보>"` | 사용자 가시성 |

**Escalation**: 같은 워크트리 inject 3회 연속 + in_progress NULL → bot.py 가 MOBRUJI_CHANNEL_ID 에 가시화 push (debounce 1h, 조치 무관). 메모리 [[feedback-sub-agent-no-user-wait]]

### 11-3) cycle-status.json 보호 (#971 회귀 방지)

**사용자 2026-05-24 정정: "KST 시각을 Z suffix 로 hand-edit → future timestamp → detect 차단" 사고 박제.**

- `~/.mobruji/cycle-status.json` **수동 편집 절대 금지** (`vim` / `cat <<EOF` / `jq` 직접 X).
- `tools/cycle-status/update.sh` 헬퍼만 사용 (atomic write + 스키마 안전).
- `validate.sh` 가 매 update 후 sanity 검증 — fail 시 즉시 root cause 조사.
- bot.py `detect_idle_worktrees` 가 future timestamp 발견 시 ERROR 로그 + Discord push.

### 11-4) idle 시 `note` 필드 의무 (#956 STRICT mode)

**사용자 2026-05-24 추가 정정: "타당한 사유 없으면 relaunch 강제".**

- `in_progress: null` 진입 시 `note` 필드 의무 (사유 또는 다음 launch 후보).
- 미명시 시 watchdog `cycle_idle_watch_loop` 가 STRICT relaunch prompt 즉시 inject.
- 갱신: `tools/cycle-status/update.sh <ws> set-idle --note "..."` (수동 JSON 편집 금지).
- 검증: `tools/cycle-status/validate.sh` — idle note 누락 + timestamp sanity 동시 detect.
- env: `CYCLE_REASON_REQUIRED=1` default. 후방호환 off (=0) 가능.

Discord watchdog push 도 reason 표시 — STRICT 라벨 분리 + 워크트리별 `idle_since` / reason 한 줄.

### 11-5) 4 사이클 동시 launch — 도메인 독립 병행 + 본진 오케스트레이션 only

- be/fe/rev/plan **4 워크트리 동시 가동** ([[feedback-keep-4-cycles-active]]). 1 sub-agent 완료 통지 받자마자 같은 워크트리 다음 백로그 launch (idle default 금지).
- placeholder/null fallback 으로 BE/FE 거의 다 동시 launch 가능 ([[feedback-be-fe-parallel]]). "BE 필드 의존" 보수적 미룸 금지.
- 한 워크트리 = 동시 1 sub-agent ([[feedback-worktree-lock]]). 같은 도메인 백로그 2건 동시 launch 시 git stash/checkout 충돌. 같은 도메인 병렬 필요 시 임시 워크트리 (`git worktree add /tmp/<name> <branch>`) 신설.

**동시 사이클 수 = 4 고정** (사용자 2026-05-26 ~16:00 정정): "동시 사이클 4개 유지. 과부하 대응 = 본진 context 가벼이 유지 + 위임."

- 본진(nmae) 과부하 시 사이클 수를 줄이지 않는다. 대신 **본진을 가볍게 유지** 한다 — 구현·머지·긴 추론·테스트 실행을 본진 turn 안에서 처리하지 않고 sub-agent 에 위임.
- 본진 책임 = **오케스트레이션 only** — (1) sub-agent launch / 위임 prompt 작성 / (2) 완료 통지 수신 + cycle-status.json 갱신 + 다음 백로그 launch / (3) PR 머지 결정 (실제 머지 명령 자체도 가능하면 sub-agent 위임 또는 cron auto-merge) / (4) cron digest 가 cover 안 하는 1회성 사용자 보고.
- 본진이 직접 수행하면 안 되는 것 — 코드 변경, 길어진 git 작업, gradle/npm 실행, 대량 로그 분석. 이 모두는 sub-agent 또는 helper-launched sub-agent 영역.
- 본진 context% marker (§14) 가 75% 이상 → 즉시 본진 추가 작업 자제 + sub-agent 위임 + 다음 turn `/clear` 후보. context 폭증을 본진에서 흡수하면 4 사이클 launch 가 끊긴다.

관련 메모리 (nmae 후속 박제 예정): `nmae/feedback_nmae_main_lightweight.md` — "본진은 오케스트레이션만 / 구현·머지·긴 추론은 sub-agent 위임 / context% marker + cycle-status 강제".

### 11-6) Sub-agent launch / 통지 / Discord 가시화

- **launch 까먹기 절대 금지** — nmae 자체 reasoning 으로 코드/테스트 처리 시 워크트리 idle + 컨텍스트 폭증.
- launch 직후 즉시 `discord-reply.sh "X 작업 위임함"` push ([[feedback-subagent-launch-report]]). 완료 통지만 기다리면 사용자 깜깜이.
- sub-agent 완료 통지는 nmae 자기 작업보다 **우선 처리** ([[feedback-notification-preempts-main]]). wall-clock 최소화.
- 사이클 launch/오류/결정 분기는 GitHub events 로 expose → webhook push ([[feedback-discord-status-push]]). URL .env 보유 금지.

### 11-7) Autonomous wake / orchestration

- idle 시 ScheduleWakeup 3600s self-perpetuating ([[feedback-autonomous-wake-pattern]]). 변경 시 Discord push, cron digest 와 책임 분할.
- rev 코멘트는 nmae 가 자동 후속 이슈 등록 ([[feedback-auto-register-rev-findings]]). 🔴 시급 항목은 같은 사이클에 즉시 트리거.
- nmae 가 Agent 도구로 be/fe/rev/plan background 가동 ([[feedback-orchestration-pattern]]) — 사용자가 워크트리마다 새 Claude 띄우지 않음.

### 11-8) nmae status push 채널 라우팅 (#1036, 2026-05-24 채널 분리 leak fix)

- **nmae 의 모든 status push 는 DIGEST_CHANNEL_ID 로** ([[feedback-nmae-status-channel]]). sub-agent launch / 완료 / cycle alert / audit / digest 모두 해당. #모부르지 (MOBRUJI_CHANNEL_ID) leak 금지 — 사용자 응답 전용 채널.
- 호출 방법 (택 1):
  - **wrapper** (권장): `bash /home/mobruji/.mobruji/nmae-discord-push.sh "<본문>"`. `--auto-ack-thread` / `--thread` / `--auto-thread` 모두 forward.
  - **flag 직접**: `bash /home/mobruji/.mobruji/discord-reply.sh --status-channel "<본문>"`. 또는 `--channel <id>` 임의 채널.
- **거울 룰**: helper 측 [[feedback-helper-relay-scope]] (§12-1) 와 짝. helper 는 nmae 사이클 디테일 relay 금지 + nmae 는 자기 status 를 status 채널로 송신 — 두 룰이 합쳐져 채널 분리 보장.
- 검증: push 후 `channel_id` 가 `MOBRUJI_CHANNEL_ID` 와 같으면 leak. 다음 grep 으로 위반 패턴 점검: `grep -RnE 'discord-reply\.sh "🚀|discord-reply\.sh ".*sub-agent' .` (해당 호출은 `nmae-discord-push.sh` 또는 `--status-channel` 으로 갱신).
- 사용자 정정 인용 (2026-05-24): "이런게 모부르지 채널로 오니" — nmae launch 알림이 #모부르지 로 leak 된 사고 박제.

### 11-9) nmae manual status push 자제 (2026-05-24 사용자 정정 인계 #11)

- **manual status push 자제** ([[feedback-nmae-no-manual-status-push]]): nmae 본체의 사이클 launch / 완료 / 요약 push 폐기 — cron digest 가 5분 주기 cover. 같은 정보를 manual 로 또 push 하면 중복 + 노이즈.
- **예외 3종 만 manual OK**:
  1. **긴급 escalation** — 9h stale 같은 incident, 사용자 즉시 결정 필요.
  2. **사용자 인계 직후 1줄 ack** — helper 또는 사용자 inject 직후 "받음 — <한 줄>" 형태 1회.
  3. **cron 미커버 1회성 이벤트** — release tag 생성, 신규 채널 생성 등 cron digest 가 추적 안 하는 메타 이벤트.
- 일반 사이클 launch / 완료 통지 / 요약 = **cron digest 만 신뢰**. nmae 직접 push 금지.
- 사용자 정정 인용 (2026-05-24): "digest 에는 이미 주기적으로 가는 메세지가 있잖아".
- 본 룰은 §11-8 ([[feedback-nmae-status-channel]]) 채널 라우팅 룰을 정밀화 — 채널 (= DIGEST) 에 더해 빈도 (manual 자제) 까지 규정.

### 11-10) per-cycle channel 라우팅 (2026-05-24 사이클 별 채널 분리)

- **사이클 별 채널 라우팅** ([[feedback-nmae-per-cycle-channel]]): sub-agent launch / 완료 / milestone / audit 알림 = 해당 cycle 의 전용 채널. DIGEST_CHANNEL_ID 로 묶지 않는다.
- **채널 매핑** (.env):
  - be → `BE_CHANNEL_ID=1507987421831233648` (#모부르지-be)
  - fe → `FE_CHANNEL_ID=1507987424884691015` (#모부르지-fe)
  - rev → `REV_CHANNEL_ID=1507987428005380106` (#모부르지-rev)
  - plan → `PLAN_CHANNEL_ID=1507987431331201154` (#모부르지-plan)
  - 종합 → `DIGEST_CHANNEL_ID=1507617571384328312` (#모부르지-digest, cron + 4 사이클 aggregate 전용)
- **카테고리** (Discord 사이드바 3 그룹): 💬 대화 / 🤖 사이클 / 📊 종합.
- **nmae sub-agent launch 시점**: cycle channel 에 첫 알림 push + thread 생성 (`LAUNCH_THREAD_ID`). sub-agent 가 그 thread 안 milestone stream. §11-9 manual 자제 룰의 예외 2 (사용자 인계 직후 1줄 ack) 와 일치하지만, 사이클 별 채널로 분기되는 점에서 정밀화.
- **종합 채널 사용 시점**: cron digest 본체 / 4 사이클 aggregate / cross-cycle alert 만.
- **거울 룰**: helper 본체는 사이클 별 채널 알림 자체 push 금지 — §12-1 helper-relay-scope 룰 거울.
- 사용자 정정 인용 (2026-05-24): "나는 plan채널 be채널 fe채널 뭐 이렇게 다 따로파라고 지시했는데 왜 digest채널에" — generic DIGEST 묶음 처리 사고 박제.

### 11-11) directive-board update flow (2026-05-26 #1129 event-driven 재설계)

- **jsonl = source of truth (SoT)** ([[feedback-nmae-directive-board-update-flow]]): `~/.mobruji/directive-board.jsonl` 의 각 entry status 가 단일 진실. Discord 채널 #모부르지-지시 의 메시지 본문 / forum 태그는 그 view.
- **트리거 3 시점 actor atomic 호출** (사용자 16:21-24 directive — polling sync_loop 폐기, 상세: `docs/features/directive-board-event-driven-redesign.md`):

| 시점 | 호출 | actor |
|---|---|---|
| (a) 지시 발생 — directive 분류 시 | `bash ~/.mobruji/directive_append.sh <msg_id> "<title>" [pr_url]` (jsonl append + forum-post atomic) | bot.py `on_message` / helper / nmae |
| (b) 위임 — sub-agent launch | `bash ~/.mobruji/directive_status.sh <id> in_progress [pr_url]` (jsonl + forum-retag + update-status atomic) | nmae / helper / `agent-launch-wrapper.sh` 강제 |
| (c) 완료 — sub-agent 완료 보고 / PR 머지 | `bash ~/.mobruji/directive_status.sh <id> completed [pr_url]` | sub-agent (`actors/sub-agent.md` 강제) / nmae |

- **수동 Discord 본문 edit 금지** — Discord UI 손 수정은 desync. 반드시 헬퍼 호출.
- **폐기 (#1129)**: bot.py `directive_board_sync_loop` / `directive_status_sync_loop` 함수 + `~/.mobruji/directive-board-sync.json` 캐시 + cron digest `directive_board_mismatch=N` 한 줄. polling sync 자체가 desync 원인 (사용자 2026-05-26 정정: 자동 PATCH 불완전 + 5분 latency).
- **누락 검출**: `helper-turn-start.sh` (helper) + `agent-launch-wrapper.sh` (sub-agent launch) wrapper 가 turn / launch 시작 시점에 `directive-board.jsonl` 최근 N=20 entry status ↔ Discord forum 태그 비교 + mismatch 발견 시 stdout visible warning. actor 가 warning 보고 즉시 정정 호출.
- 1회성 sweep (잔존 mismatch 정리) 는 `docs/features/directive-jsonl-mismatch-sweep.md` PR 에서 별도 처리.
- 사용자 정정 인용 (2026-05-24): "진행상황 변동 없네" — directive-board 본문 stale 사고 박제.
- 사용자 정정 인용 (2026-05-26 16:21-24): "polling sync 폐기 — event-driven 재설계, bot.py = dumb conduit, desync 0 목표".

---

## 부록) sub-agent launch quick-ref (구 CLAUDE.md §13)

> nmae 가 be/fe/rev/plan sub-agent 를 launch 할 때 참조. 상세 SoT 는 `docs/ai-harness/actors/sub-agent.md`.

> **상세는 `docs/ai-harness/actors/sub-agent.md` 단일 SoT**. 본 섹션은 nmae/helper 가 sub-agent launch 시점에 의존하는 비협상 룰만 요약.
> 메모리 actor: `subagent` (공통) / `rev` (rev 전용).

### 13-1) 공통 룰 포인터 — `12-template §1`

- 워크트리 격리 (`cd /home/mobruji/mobruji-<role>`. 다른 워크트리/메모리 침범 금지)
- 한 워크트리 = 동시 1 sub-agent ([[feedback-worktree-lock]])
- reasoning 5분 룰 — turn 5분 넘으면 자기 interrupt + 다음 사이클 분할 ([[feedback-reasoning-chunk-limit]])
- 메모리 직접 수정 금지 (nmae 만 갱신)
- 사용자 wait state 금지 ([[feedback-sub-agent-no-user-wait]]) — "사용자 결정 대기" 금지, 자율 결정 default
- hook 우회 금지 (`--no-verify` 등)
- 보호 영역 변경 시 `needs-human-review` 라벨
- `gh pr create --base develop` 강제 ([[feedback-pr-base-develop]]) — default=main 사고 가드
- 라벨 자기 점검 (type/scope/ai-generated/ai:claude/`session:<backend|frontend|review|plan|helper>`) — session 라벨 부착 의무 (이슈 #1002 / `.github/workflows/auto-label.yml` 추론 fallback). sub-agent role → 라벨 매핑: be → `session:backend`, fe → `session:frontend`, rev → `session:review`, plan → `session:plan`, helper → `session:helper` (`docs/ai-harness/actors/sub-agent.md §1 PR session 라벨 부착 의무` 표 참조)
- 완료 보고 시 PR URL + mergeable + 게이트 + 보호 영역 + 발견 사항 (🔴/🟡/🟢) + 다음 사이클 후보

### 13-2) 역할별 룰 포인터 — `12-template §2`

| Role | 워크트리 | 작업 가능 경로 | 품질 게이트 |
|---|---|---|---|
| **be** | `/home/mobruji/mobruji-be` | `backend/**` | `cd backend && ./gradlew checkstyleMain spotlessCheck test` |
| **fe** | `/home/mobruji/mobruji-fe` | `web/**` | `cd web && npm run lint && npm run typecheck && npm test && npm run build` |
| **rev** | `/home/mobruji/mobruji-rev` | **파일 수정 금지** (PR 코멘트만) | read-only 실행 검증 (gradle test / npm test) |
| **plan** | `/home/mobruji/mobruji-plan` | `docs/**`, `.github/**` (보호 영역 라벨) | 해당 없음 |

- be: 새 엔드포인트 성공 케이스 E2E (RestAssured) 필수, DDD 계층 침범 금지
- fe: **의존성 설치 금지** — `npm install` 실행 금지 (외부 디스크 symlink 보존 — [[feedback-npm-install-symlink-swap]]). 누락 시 nmae 보고
- rev: 3단계 e2e (단계 1 머지 전 / 단계 2 develop 후 / 단계 3 release 후), 매 사이클 첫 액션 `tools/rev-queue/rev-queue.sh all` ([[feedback-rev-e2e-always]] [[feedback-rev-develop-grep]] [[feedback-rev-release-gate]])
- plan: docs/ADR/spec, 구현 코드 금지

### 13-3) git 사고 가드

- **stash drop unmerged file** ([[feedback-stash-drop-unmerged-file]]): stash pop conflict 후 working tree 마커 잔존 → service crash. 별도 restore 필수.
