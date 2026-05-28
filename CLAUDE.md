# CLAUDE.md

> 세션 시작 시 자동 로드. **모든 actor 공통 비협상 룰 + 포인터만** 둔다. 상세·actor 전용 룰은 아래 라우팅대로 각자 자기 문서만 읽는다.

## 0) Actor 라우팅 — "자기 것만 읽는다"

> 모든 actor 는 **§1~§10 + §14 + §16 + §17 (공통)** 만 필수. 그 외 자기 역할 문서 1개만 추가로 읽으면 됩니다 (노이즈 제거 — 2026-05-28 actor-scoped 분리).

| actor | 추가로 읽을 문서 (이것만) |
|---|---|
| **nmae** (NCP `mobruji:0.0`) | `docs/ai-harness/actors/nmae-runbook.md` |
| **helper** (mac `helper:0.0`) | `docs/helper-rules.md` (+ §15 doc-check) |
| **be / fe / rev / plan** (sub-agent) | `docs/ai-harness/12-sub-agent-prompt-template.md` (§1 공통 + §2 자기 역할) |
| 라우팅/주입 맵 전반 | `docs/ai-harness/00-MANIFEST.md` |

- 강제는 **prose 가 아니라 코드** — bot.py watchdog loop + `tools/agent-launch-wrapper.sh` / `cycle-status/update.sh` / `discord-daemon/helper-turn-start.sh` wrapper (`docs/ai-harness/16-memory-vs-code-enforcement.md` 철학).
- 인시던트 "왜"(사고 박제·정정 인용)는 **메모리** (`memory/<actor>/feedback_*.md`) 가 보관. 본 파일은 "무엇을" 만.

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
- `docs/helper-rules.md` — **helper 본체 비협상 룰 single SoT (§12 위임, 2026-05-26 #1085)**
- `docs/ai-harness/13-memory-promote-tracking.md` — 메모리 promote/tracking
- `docs/ai-harness/14-discord-notify-setup.md` — Discord notify 셋업
- `docs/ai-harness/15-discord-message-templates.md` — Discord 메시지 템플릿
- `docs/decisions/` — ADR
- `docs/features/` — Feature Spec
- `docs/features/autonomous-cycle-orchestration.md` — 자율 사이클 오케스트레이션
- `tools/cycle-status/` — nmae 가 cycle-status.json 갱신 시 호출하는 헬퍼 (`update.sh` / `validate.sh`)
- `tools/agent-launch-wrapper.sh` — sub-agent launch 직전 set-active + launch prompt emit + per-cycle 채널 launch 알림/thread 자동 push (학습 의존 ↓, #1008 + B2 2026-05-24)
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
- **Discord 정중체** ([[feedback-discord-tone-formal]]): "~합니다 / ~할까요?" 통일. 영어 push/post/send 금지 — "메시지/알려 드리겠습니다". **줄임 표현 금지** ("별 sub" → "별도 sub-agent", "별 PR" → "별도 PR"). **비문/미완성 문장 금지** — 주어/술어 완전성 유지 (예: "근본 fix 필요" → "근본 원인 fix 가 필요합니다").
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

## 11) nmae 전용 룰 → `docs/ai-harness/actors/nmae-runbook.md`

> **NCP nmae 세션만 로드.** 3중 watchdog / inject 대응 절차 / cycle-status 보호 / idle note / 4-사이클 / status 채널 라우팅 / directive-board event-driven flow (구 §11-1~11-11). 강제는 bot.py watchdog loop + `tools/agent-launch-wrapper.sh` + `tools/cycle-status/update.sh`.

## 12) helper 전용 룰 → `docs/helper-rules.md` (단일 SoT)

> **mac helper 세션만 로드.** 채널 경계 / ack(bot.py 처리) / 매 turn 4단계 / thread / 정중체 / 빈 메시지 분류 등. CLAUDE.md 거울 폐지 (#1085) — `docs/helper-rules.md` + `tools/discord-daemon/helper-turn-start.sh` 두 채널로 강제.

## 13) sub-agent 룰 → `docs/ai-harness/12-sub-agent-prompt-template.md` (단일 SoT)

> **be/fe/rev/plan sub-agent launch 시 이 한 문서만 로드** (§1 공통 + §2 역할별). 역할↔워크트리↔품질게이트 quick-ref + git 사고 가드 포함. 역할 매핑/주입은 `docs/ai-harness/00-MANIFEST.md`.
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

## 17) Evidence-based root cause (모든 actor — common)

**사용자 2026-05-26 명시**: 사용자가 trigger 한 작업을 시작할 때 **결과 추정 X**. 기록 / 히스토리(git / 메모리 / Discord / journal / PR / queue / tmux pane)를 먼저 분석해 root cause 를 확정한 뒤 작업한다. "메모리 또 박제" 같은 학습 의존 핑계 금지.

### 룰
- 사용자 trigger → (1) **evidence 수집** → (2) root cause 확정 → (3) 작업.
- "아마 …일 것" / "보통 …이니" / "지난 번에 …였으니" 같은 추정 첫 답 금지.
- 같은 사고가 두 번째면 박제 만으로 끝내지 않고 **강제 메커니즘** (hook / wrapper / system prompt) 까지 같이 제안.

### Evidence 수집 채널 (작업 trigger 별)

| trigger | 우선 채널 |
|---|---|
| "왜 안 됨" / "방금 …이 망가짐" | journal (`sudo journalctl -u <svc> --since "10 min ago"`) → PR head commit → git log |
| "사이클이 멈췄음" / "사이클 idle" | `~/.mobruji/cycle-status.json` → `tools/cycle-status/validate.sh` → tmux capture-pane → cron digest |
| "메시지 못 받음" / "Discord 안 옴" | `~/.mobruji/helper-queue.jsonl` → bot.py journal → discord-reply.sh stdout → `last-user-msg-id.txt` |
| 룰 변경 요청 | 관련 메모리 file → CLAUDE.md 섹션 → `docs/ai-harness/` / `docs/features/` |
| 머지 사고 / 회귀 | `git log --oneline -20` + `git show <sha>` + PR rev 코멘트 |

### 위반 예시 (피해야 함)
- 사용자 "사이클 멈췄음" → "메모리 박제 했으니 다음부터 안 멈출 거예요" (추정 + 학습 의존)
- 사용자 "PR 못 봤음" → "Discord push 했으니 봤겠죠" (`discord-reply.sh` stdout 미확인)
- 사용자 "같은 사고 또 발생" → "한 번 더 박제" (강제 메커니즘 제안 누락)

### 메커니즘 단 우선순위 (반복 사고일 때)
1. system prompt append (sub-agent / helper)
2. hook (PreToolUse / PostToolUse / Stop)
3. wrapper script (호출 강제 우회 시 graceful warning)
4. cron digest 가시화
5. 메모리 / CLAUDE.md (보조 학습)

관련: [[feedback-evidence-based-root-cause]] [[feedback-verify-and-iterate]] [[feedback-session-persist-rules]]
