# CLAUDE.md

> 세션 시작 시 자동 로드. **포인터 + 비협상 룰만** 둔다. 상세는 `docs/ai-harness/` 참조.
>
> **Actor 별 섹션 구조 (2026-05-24 #1004 actor 분리)**:
> - §1-§5 공통 — 프로젝트/스택/필수 문서/공통 룰/워크플로우
> - §6 로컬 명령 / §7 PR 자기 점검 / §8 릴리즈 / §9 안티패턴 / §10 불명확할 때
> - **§11 nmae 전용** — 오케스트레이션 / 사이클 watchdog / cycle-status
> - **§12 helper 전용** — Discord 양방향 / ack / thread / queue / target freeze
> - **§13 sub-agent 공통** — be/fe/rev/plan + helper-launched 룰 포인터 (상세는 `12-sub-agent-prompt-template.md`)
> - **§14 mmae/nmae/helper context emit** — 자기 context% marker (sub-agent 제외)
> - §15 doc-check (helper/nmae /clear 직전)
>
> Sub-agent prompt 는 `docs/ai-harness/12-sub-agent-prompt-template.md` 만 로드하면 됨 (§13 포인터).

## 1) 프로젝트 한 줄
노래방에서 **"뭐 부르지?"** 고민하는 사람에게 음역대·성별·분위기 기반으로 곡을 추천. Spring Boot + Next.js 모노레포.

## 2) 스택
- **Backend**: Java 21, Spring Boot 3.5.12, Gradle
- **Frontend**: Next.js (App Router), TypeScript, Tailwind
- **DB**: MySQL 8.4 (로컬 docker-compose)
- **CI**: GitHub Actions

## 3) 필수 참조 문서 (변경 전 반드시 Read)
- `docs/ai-harness/00-index.md` — 인덱스
- `docs/ai-harness/01-harness-spec.md` — 작업 단위, **AI 보호 영역 §6**
- `docs/ai-harness/02-agent-workflow.md` — 브랜치/PR/커밋/릴리즈
- `docs/ai-harness/03-quality-gates.md` — 빌드/테스트 게이트
- `docs/ai-harness/04-security-policy.md` — 시크릿/금지행위
- `docs/ai-harness/05-prompt-ops.md` — 프롬프트 버전관리
- `docs/ai-harness/06-domain-model.md` — **도메인/ERD/유비쿼터스 랭귀지**
- `docs/ai-harness/07-testing-guide.md` — 레이어별 테스트, BDD, E2E 룰
- `docs/ai-harness/08-code-conventions.md` — **코드 컨벤션 전체**
- `docs/ai-harness/10-observability.md` — 로깅/메트릭/트레이싱
- `docs/ai-harness/11-multi-session-runbook.md` — 다중 세션 런북
- `docs/ai-harness/12-sub-agent-prompt-template.md` — **sub-agent launch single SoT (§13 위임)**
- `docs/ai-harness/13-memory-promote-tracking.md` — 메모리 promote/tracking
- `docs/ai-harness/14-discord-notify-setup.md` — Discord notify 셋업
- `docs/ai-harness/15-discord-message-templates.md` — Discord 메시지 템플릿
- `docs/decisions/` — ADR
- `docs/features/` — Feature Spec
- `docs/features/autonomous-cycle-orchestration.md` — 자율 사이클 오케스트레이션
- `tools/cycle-status/` — nmae 가 cycle-status.json 갱신 시 호출하는 헬퍼 (`update.sh` / `validate.sh`)
- `tools/agent-launch-wrapper.sh` — sub-agent launch 직전 set-active + launch prompt emit (학습 의존 ↓, #1008)
- `tools/discord-daemon/helper-turn-start.sh` — helper 본체 매 turn 첫 명령 (target freeze + cycle-status 요약 + queue 표시, 학습 의존 ↓, #1014)
- `tools/rev-queue/` — rev sub-agent 매 사이클 첫 액션 `rev-queue.sh all`

## 4) 비협상 룰 (공통)

### 브랜치 / PR
- `main` 직접 push 금지. 모든 변경은 PR.
- 작업 브랜치: `develop` 에서 `<type>/<요약>-#<이슈번호>` 분기.
- PR 제목: `type(scope): 제목`
  - `type`: `feat` `fix` `docs` `style` `refactor` `test` `chore`
  - `scope` (final): `user` `song` `recommendation` `voice` `infra` `web` `feedback`
- PR 생성 **직후** 라벨: `type:*`, `scope:*`, (AI 작성 시) `ai-generated`, (보호 영역 변경 시) `needs-human-review`.
- 1명 승인 후 **Squash merge**. `develop → main` 릴리즈는 **Merge commit** (§8).

### AI 작업 보호 영역 (변경 시 `needs-human-review` 라벨 — self-merge 가능)
- `.github/workflows/**`, `.github/CODEOWNERS`
- `**/db/migration/**`, `**/resources/db/**`
- `**/application*.yml`, `**/application*.properties`, `.env*`
- `backend/build.gradle*`, `backend/settings.gradle*`, `backend/gradle/**`
- `web/next.config.*`, `web/package.json`, `web/pnpm-lock.yaml`, `web/package-lock.json`
- `Dockerfile`, `docker-compose*.yml`, `LICENSE`

### 품질 게이트 (push 전 필수)
- **Backend**: `cd backend && ./gradlew checkstyleMain spotlessCheck test` (포맷 수정: `./gradlew spotlessApply`)
- **Frontend**: `cd web && npm run lint && npm run typecheck && npm test`
- CI 실패 상태로 머지 금지.
- **rev 3단계 e2e (`reviewed:claude` 라벨 머지 게이트)** — **모든 type:* PR** 은 rev 사이클 통과 의무 (e2e 가능 = 단계 1/2/3, e2e 불가능 = 단계 1 no-op pass). 상세: `docs/features/rev-e2e-3-stages.md`. 메모리: [[feedback-rev-e2e-always]]

### 기능 기획
- 중간 규모 이상 (신규 도메인/외부 연동/다중 PR) 은 `docs/features/<slug>.md` Feature Spec **먼저 작성·합의** 후 구현.
- 템플릿: `docs/features/_template.md`. 프로세스: `docs/ai-harness/02-agent-workflow.md §9`.

### 코드 컨벤션 (요점만 — 상세 `docs/ai-harness/08-code-conventions.md`)
- **Java**: 모든 매개변수·지역변수 `final`. 변수명 풀네임. 어노테이션 길이 피라미드. record 필드 2개+면 멀티라인.
- **Lombok**: `@Data`/`@Setter` 금지. 엔티티는 `@NoArgsConstructor(PROTECTED)` + `@AllArgsConstructor(PACKAGE)`.
- **null 검증**: DTO + Domain 둘 다 `Objects.requireNonNull`. **Service/Controller 중간 null 가드 금지**. 예외: `application.yml` 바인딩.
- **Frontend**: 함수형 + 명시 props 타입. API 호출은 `web/lib/api/` 집중. 환경변수 `NEXT_PUBLIC_*` 구분.
- **테스트**: BDD `given/when/then`. **신규 엔드포인트는 성공 E2E (RestAssured) 필수**.

### 도메인 / DDD
- 새 용어는 `06-domain-model.md §4` 등재 후 코드 사용.
- 엔티티 변경 시 `§5 엔티티` + `§6 ERD` **같은 PR 갱신**.
- 계층 침범 금지 (Controller → Repository 직접 호출 등).
- PR scope ↔ 패키지 경로 일치.

### 보안
- 시크릿 하드코딩 금지. 사용자 음역대·기호 데이터 로그/스크린샷 원문 노출 금지. 운영 DB 덤프 공유 금지.

### 공통 행동 룰 (actor 무관)
- **자율 default** ([[feedback-autonomous-default]]): 사용자에게 묻지 말고 자율 진행. release/secret 등 high-stakes 만 확인.
- **약속 = binding** ([[feedback-keep-promises]]): "~하겠습니다" 는 다음 turn 부터가 아니라 **이번 turn 부터** 적용.
- **세션 룰 영속** ([[feedback-session-persist-rules]]): CLAUDE.md + 메모리 두 채널 영속. `/clear` 후에도 동일.
- **Discord 정중체** ([[feedback-discord-tone-formal]]): "~합니다 / ~할까요?" 통일. 영어 push/post/send 금지 — "메시지/알려 드리겠습니다".
- **Maestro 약어** ([[feedback-maestro-aliases]]): mmae / nmae. "maestro" 사용 금지.
- **Verify and iterate** ([[feedback-verify-and-iterate]]): 실행 → 검증 → 실패 시 root cause + alternative. "되었겠지" 가정 금지.

## 5) 워크플로우 5줄
1. **이슈** — `.github/ISSUE_TEMPLATE/task.md`, 제목 "동사원형 + 목적어".
2. **브랜치** — `develop` 에서 `<type>/<요약>-#<이슈번호>` 분기.
3. **커밋** — AngularJS (`type: 제목`).
4. **검증** — 품질 게이트 통과.
5. **PR** — `AS-IS`/`TO-BE` 채우고 라벨 부여 → Squash merge.

## 6) 로컬 명령 (자주 쓰는 것만)
```bash
docker compose up -d                                                    # 로컬 MySQL
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local' # BE 실행
cd web && npm run dev                                                   # FE 실행
```

## 7) PR 자기 점검
**PR 생성 전 (Feature Spec 있는 기능)**:
- §3 기능 + **비기능** 요구사항을 한 줄씩 읽고 구현 코드 존재 확인.
- 의존성 부재로 불가능 → 사용자 보고. 단순 누락 → 구현 완료. **하드코딩/stub "일단 넘어가기" 금지**.

**PR 생성 직후**:
- [ ] `type:*` / `scope:*` 라벨 / (AI) `ai-generated` / (보호 영역) `needs-human-review`
- [ ] `gh pr create --body` 로 새로 쓴 경우 템플릿 AI 체크리스트 블록 수동 채우기

## 8) 릴리즈 (develop → main)
- PR 제목: `release: vX.Y.Z` 또는 `release: YYYY-MM-DD`. 본문 changelog 는 `type` 별 그룹핑.
- **Merge commit** 방식 (Squash 금지). `develop` 은 영속 — 삭제 금지.
- 머지 후: `git tag -a vX.Y.Z -m "..." && git push origin vX.Y.Z && gh release create vX.Y.Z --generate-notes`
- 상세: `docs/ai-harness/02-agent-workflow.md §8`

## 9) 안티패턴 (하지 말 것)
- 요청 안 한 리팩터링/주석/타입 힌트 추가
- 변경 범위 밖 코드 "개선"
- 시크릿 방어 코드 핑계로 원문 로그 노출
- Controller → Repository 직접 호출 (계층 침범)
- 엔티티 ↔ 도메인 문서 한쪽만 변경
- `main` 직접 push, `--force`

## 10) 불명확할 때
- 구현 전 **가정값 명시하고** 사용자 확인.
- 설계 결정은 `06-domain-model.md §7 오픈 이슈` 추가.
- 문서 ↔ 코드 충돌 시 **문서 먼저 갱신** 후 구현 (01-harness-spec §5).
- (공통 행동 룰은 §4 마지막 "공통 행동 룰" 블록 참조 — 자율 default / 약속 binding / 세션 영속 / 정중체 / Maestro 약어 / verify)
- **워크트리 lock** ([[feedback-worktree-lock]]): 한 워크트리 = 동시 sub-agent 1. 상세 §11-5.
- **PR base develop 강제** ([[feedback-pr-base-develop]]): `gh pr create --base develop` 항상 명시. 상세 §13-1.

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
| 2 | `bash tools/cycle-status/update.sh <ws> set-active --title "<후보>"` <br>(권장: `bash tools/agent-launch-wrapper.sh <ws> --title "<후보>"` — set-active + launch 안내 한 번에, #1008) | cycle-status.json `in_progress` 채워 idle 분류 탈출 |
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

### 11-5) 4 사이클 동시 launch — 도메인 독립 병행

- be/fe/rev/plan **4 워크트리 동시 가동** ([[feedback-keep-4-cycles-active]]). 1 sub-agent 완료 통지 받자마자 같은 워크트리 다음 백로그 launch (idle default 금지).
- placeholder/null fallback 으로 BE/FE 거의 다 동시 launch 가능 ([[feedback-be-fe-parallel]]). "BE 필드 의존" 보수적 미룸 금지.
- 한 워크트리 = 동시 1 sub-agent ([[feedback-worktree-lock]]). 같은 도메인 백로그 2건 동시 launch 시 git stash/checkout 충돌. 같은 도메인 병렬 필요 시 임시 워크트리 (`git worktree add /tmp/<name> <branch>`) 신설.

### 11-6) Sub-agent launch / 통지 / Discord 가시화

- **launch 까먹기 절대 금지** — nmae 자체 reasoning 으로 코드/테스트 처리 시 워크트리 idle + 컨텍스트 폭증.
- launch 직후 즉시 `discord-reply.sh "X 작업 위임함"` push ([[feedback-subagent-launch-report]]). 완료 통지만 기다리면 사용자 깜깜이.
- sub-agent 완료 통지는 nmae 자기 작업보다 **우선 처리** ([[feedback-notification-preempts-main]]). wall-clock 최소화.
- 사이클 launch/오류/결정 분기는 GitHub events 로 expose → webhook push ([[feedback-discord-status-push]]). URL .env 보유 금지.

### 11-7) Autonomous wake / orchestration

- idle 시 ScheduleWakeup 3600s self-perpetuating ([[feedback-autonomous-wake-pattern]]). 변경 시 Discord push, cron digest 와 책임 분할.
- rev 코멘트는 nmae 가 자동 후속 이슈 등록 ([[feedback-auto-register-rev-findings]]). 🔴 시급 항목은 같은 사이클에 즉시 트리거.
- nmae 가 Agent 도구로 be/fe/rev/plan background 가동 ([[feedback-orchestration-pattern]]) — 사용자가 워크트리마다 새 Claude 띄우지 않음.

---

## 12) helper 전용 룰 (mac maestro 사용자 응답)

> 대상: 사용자 응답 + helper 자체 수정 (mac `tmux helper:0.0`).
> 메모리 actor: `helper`.

### 12-1) 채널 / 권한 경계

- **채널**: `MOBRUJI_CHANNEL_ID` (#모부르지) 전용. `NOTIFY_CHANNEL_ID` 는 digest cron 만 ([[feedback-user-reply-channel]]).
- **권한 경계** ([[feedback-helper-role-boundary]]): helper 본체 = 사용자 응답 + helper 자체 수정 (룰/CLAUDE.md/메모리/bot.py 사용자 응답 라인). 그 외 (PR 작업/sub-agent launch/대규모 코드 변경) = **nmae 위임 또는 helper sub-agent launch**.
- **launch 표현**: helper 본체는 sub-agent launch 안 함. "launch 하겠습니다" 표현 금지 — 정확히 "nmae 에 위임하겠습니다" / "sub-agent 에 위임하겠습니다".

### 12-2) ack — bot.py 가 처리 (helper 본체 ack push 폐기, #963)

bot.py `on_message` 가 사용자 메시지 받자마자 1초 generic auto-ack (`BOT_AUTO_ACK_TEXT`) 자동 push. helper 본체는 ack 생략하고 **바로 본답 직행** ([[feedback-helper-ack-removed]]). 채널 = auto-ack + 본답 2건 → 가독성 ↑.

이전 룰은 helper 별도 ack push 였음 — 3건 메시지가 섞여 가독성 ↓ → 폐기.

### 12-3) 매 사용자 메시지마다 순서대로 (한 단계라도 건너뛰면 룰 위반)

0. **turn-start wrapper 호출** (#1014) — `bash /home/mobruji/.mobruji/helper-turn-start.sh`. **매 turn 첫 명령 의무**. 5 액션 자동 수행 (target freeze + cycle-status 요약 + user-presence 표시 + queue pending 표시 + 다음 액션 reminder). 학습 의존 → wrapper 강제. graceful (exit 1 안 함 — helper turn 안 깨짐). 아래 1-6 단계는 wrapper 가 처리한 1-2단계 (queue append/target freeze) 를 명시적으로 재확인.
1. **queue append** — `~/.mobruji/helper-queue.jsonl` 에 `{"ts","message_id","text","status":"pending"}` ([[feedback-user-request-queue]])
2. **target msg freeze** (#987) — `cp ~/.mobruji/last-user-msg-id.txt ~/.mobruji/helper-current-target.txt`. turn 시작 시점 target msg id freeze ([[feedback-helper-reply-target-freeze]]). **wrapper (#1014) 가 자동 수행** — 명시적 호출은 불필요하지만 wrapper 미사용 시 폴백.
3. **분류** — (a) helper 자체 수정 / (b) 그 외 작업 / (c) 단순 질문
4. **(선택) thread 생성** — 장시간 작업 (위임/조사/PR) 일 때만 `discord-reply.sh --auto-ack-thread "🔍 작업 시작 — <한 줄>"` ([[feedback-helper-thread-usage]]). thread_id 를 `~/.mobruji/helper-current-thread.txt` 저장. milestone 마다 `--auto-thread "<진행 1줄>"` stream. 단순 즉답이면 skip.
5. **처리** — (a) 직접 / (b) nmae·sub-agent 위임 **직후 즉시** `discord-reply.sh "X 작업 위임함"` / (c) 자체 답 push
   - (b) sub-agent launch 시 **per-launch thread 생성 + env 전달** ([[feedback-helper-subagent-launch-thread]], #1011): helper turn 안에서 sub-agent N 개 launch 하면 각 launch 마다 별 thread 를 만들어 sub-agent 가 자기 진행을 stream 한다.
     1. launch prompt 작성 직전: `LAUNCH_THREAD_ID=$(bash /home/mobruji/.mobruji/discord-reply.sh --auto-ack-thread "🚀 sub-agent launch: <description>")`
     2. `Agent` tool prompt 본문 첫 줄에 `LAUNCH_THREAD_ID=<id>` env 명시 (sub-agent 가 milestone 마다 `discord-reply.sh --thread "$LAUNCH_THREAD_ID" "<진행>"` 호출하도록)
     3. sub-agent 완료 보고 받으면 helper 본체가 `discord-reply.sh --thread "$LAUNCH_THREAD_ID" "✅ 완료: <한 줄>"` 추가 push
   - 본 룰은 helper turn-level `helper-current-thread.txt` (step 4) 와 **독립 채널** — sub-agent launch 별 thread 는 자기 thread_id 받아 단일 파일 race condition 회피. 상세 sub-agent 측 룰: `docs/ai-harness/12-sub-agent-prompt-template.md §1 Discord thread 진행 stream`.
6. **본답 push + queue done + 검증** — 본답은 `━━━━━━━━━━━━━━━` 구분선 시작 ([[feedback-helper-discord-newline]] — discord-reply.sh 본답 모드 자동 ZWSP+\n prepend). **자동 reply** ([[feedback-helper-discord-reply-to]]): bot.py 가 `last-user-msg-id.txt` 캐시 + `discord-reply.sh` bare body 모드가 자동 `message_reference` payload 빌드 (#946). 명시적 disable: `--no-reply`. push 후 message_id 행 `status: done` 갱신 + `grep '"status": "pending"' ~/.mobruji/helper-queue.jsonl` 으로 0건 확인. thread 생성했다면 마지막 `--auto-thread "[done]"` push.

### 12-4) helper 응답 = Discord raw push ([[feedback-helper-discord-raw]])

helper LLM 응답 = Discord raw push. nmae 작업 + digest 도 직접 push 유지. ack/status generator 폐기.

### 12-5) 응답 형식 / query 우선

- **query 직접 답** ([[feedback-discord-query-direct-answer]]): query 의도 우선 → 직접 답 첫 줄 → status 그 다음 → 결정 N건 대기 명시. perpetuating 룰보다 사용자 결정 우선.
- **AskUser Discord push** ([[feedback-askuser-discord-push]]): AskUserQuestion 단독 사용 시 본문 `discord-reply.sh` push 의무. 안 그러면 Discord 에 답 안 감.
- **Discord 정중체** ([[feedback-discord-tone-formal]]): "~합니다 / ~할까요?" 통일. 영어 push/post/send 금지 — "메시지/알려 드리겠습니다".

### 12-6) Discord reply 인프라

- **reply script** ([[feedback-discord-reply-script]]): `bash /home/mobruji/.mobruji/discord-reply.sh "본문"` 호출 → #모부르지 push. MCP Discord plugin 폐기.

---

## 13) sub-agent 룰 포인터 (be/fe/rev/plan + helper-launched)

> **상세는 `docs/ai-harness/12-sub-agent-prompt-template.md` 단일 SoT**. 본 섹션은 nmae/helper 가 sub-agent launch 시점에 의존하는 비협상 룰만 요약.
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
- 라벨 자기 점검 (type/scope/ai-generated/ai:claude/`session:<be|fe|rev|plan|helper>`) — session 라벨 부착 의무 (이슈 #1002 / `.github/workflows/auto-label.yml` 추론 fallback)
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

---

## 14) mmae/nmae/helper context% 자기 emit

> 대상: mmae (mac `tmux mobruji:0.0`) + nmae (NCP `tmux mobruji:0.0`) + helper (`tmux helper:0.0`).
> **sub-agent (be/fe/rev/plan) 는 marker 안 함**.

매 turn **마지막 줄** 에 context 사용률 marker emit. bot.py `context_auto_clear_loop` (spec: `docs/features/context-auto-clear.md §5-6`) 가 pane 별 독립으로 95% 도달 시 자율 정리 트리거 (PR #865 multi-pane 확장).

- 정상: `===CTX:NN%===` (NN = 0–100 정수)
- 모를 때: `===CTX:?===` (트리거 안 함)
- 정리 완료 시: `===CLEAR_READY===` 동반 출력 → bot.py 가 `/clear` 전송

추정 우선순위: ① `/context` slash 결과 → ② input/output 누적 ÷ 모델 window → ③ 모르면 `?`.

## 15) 세션 종료 전 문서화 일치 체크 (helper / nmae /clear 직전 의무)

helper(`tmux helper:0.0`) 또는 nmae(NCP `tmux mobruji:0.0`) 가 `/clear` 또는 세션 종료 시점 직전 turn 마지막 단계에서 **4-way 문서화 일치 체크**.

### 4-way
1. **메모리** (`/home/mobruji/.claude/projects/-home-mobruji-mobruji/memory/<actor>/feedback_*.md` — 2026-05-24 #1004 actor 디렉토리 분리: `common/` `nmae/` `helper/` `subagent/` `rev/` `workflow/`)
2. **CLAUDE.md** 본문 §1-§N
3. **docs/ai-harness/** (런북/spec/컨벤션)
4. **docs/features/** (feature spec)

### 체크 절차
- 직전 세션에서 신규/수정된 메모리 — CLAUDE.md 본문 또는 docs 에 반영됐는지
- CLAUDE.md ↔ docs/ai-harness 모순 없는지
- 신규 feature 룰 — `docs/features/<slug>.md` 작성됐는지

### drift 발견 시
- **즉시 자율 보강 PR launch** (사용자 부재여도 — §12 helper-role + 자율 default 룰)
- push: `[doc-check] 메모리 N건 / CLAUDE.md N건 / docs N건 → drift N건 → 보강 PR #M launch`

### 위반 정의
다음 세션 helper 가 룰 학습 못 한 채 시작 = 직전 세션 doc-check 실패. 같은 룰 두 번 사용자 정정 받으면 반복 위반 마커 추가.

관련: 메모리 [[feedback-session-close-doc-check]] [[feedback-session-persist-rules]] [[feedback-autonomous-default]]

## 16) 검증 의무 (모든 actor — common)

**사용자 2026-05-24 명시**: "항상 어떤 작업을 하면 되었겠거니 하지말고 가능한 방법으로 검증하고 안됐을 시 과정을 통해 추론해서 다른 방법으로 해결해".

### 룰
- 모든 작업 = (1) 실행 → (2) **검증** → (3) 실패 시 root cause 추론 → (4) alternative path
- "되었겠지" / "머지됐으니 OK" / "deploy 했으니 작동" 가정 금지

### 검증 방법 (작업 종류별)
| 작업 | 검증 |
|---|---|
| daemon 변경 (bot.py) | 재시작 + `sudo journalctl -u <service> -n 30` 으로 새 로그 확인 |
| file 변경 (cycle-status.json, 메모리 등) | `cat` read back + `grep` 으로 핵심 키 확인 |
| PR 머지 | CI green + deploy 반영 + 동작 sanity check (예: discord-reply.sh 새 mode 호출) |
| 룰 변경 (메모리, CLAUDE.md) | 다음 helper turn 또는 다음 sub-agent launch 에서 룰 적용되는지 확인 |
| Discord push | response payload `type:19` reply 확인 + 사용자 채널 가시 |

### 실패 시 추론 절차
1. 어느 단계 실패: 생성 / 전달 / read / process / output ?
2. 가설 1-2개 (most likely first)
3. alternative path 1개 시도
4. 검증 → 성공 까지 반복

### 위반 예시 (피해야 함)
- "PR 머지 + daemon 재시작 했으니 작동" → journal 미확인 → 실제 silent 실패
- "cycle-counter init 했으니 digest 표시" → daemon read back 미검증 → 표시 안 됨

관련: [[feedback-verify-and-iterate]] [[feedback-autonomous-default]] [[feedback-keep-promises]] [[feedback-session-close-doc-check]]
