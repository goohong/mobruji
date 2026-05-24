# CLAUDE.md

> 세션 시작 시 자동 로드. **포인터 + 비협상 룰만** 둔다. 상세는 `docs/ai-harness/` 참조.

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
- `docs/ai-harness/12-sub-agent-prompt-template.md` — **sub-agent launch single SoT (우선순위 최상위)**
- `docs/ai-harness/13-memory-promote-tracking.md` — 메모리 promote/tracking
- `docs/ai-harness/14-discord-notify-setup.md` — Discord notify 셋업
- `docs/ai-harness/15-discord-message-templates.md` — Discord 메시지 템플릿
- `docs/decisions/` — ADR
- `docs/features/` — Feature Spec
- `docs/features/autonomous-cycle-orchestration.md` — 자율 사이클 오케스트레이션 (4 워크트리 동시 + cycle-status digest + worktree lock + helper boundary)
- `tools/cycle-status/` — nmae 가 cycle-status.json 갱신 시 호출하는 헬퍼 (`update.sh` / `validate.sh`). 수동 JSON 편집 금지 (§14 + 11-runbook §0-11)
- `tools/rev-queue/` — rev sub-agent 매 사이클 첫 액션 `rev-queue.sh all` (§4 rev 3단계 e2e 게이트 + 11-runbook §0-12)

## 4) 비협상 룰

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
- **rev 3단계 e2e (`reviewed:claude` 라벨 머지 게이트)** — **모든 type:* PR** 은 rev 사이클을 거쳐야 머지 가능 (2026-05-24 확장). e2e 가능 (web/backend) 은 단계 1/2/3 실제 수행, e2e 불가능 (docs/refactor/chore 등) 은 단계 1 에서 **no-op pass 판정** + `reviewed:claude` 라벨 부여. 라벨 없는 PR 자율 머지 금지. 상세: `docs/features/rev-e2e-3-stages.md`. 메모리: [[feedback-rev-e2e-always]]

### 기능 기획
- 중간 규모 이상 (신규 도메인/외부 연동/다중 PR) 은 `docs/features/<slug>.md` Feature Spec **먼저 작성·합의** 후 구현.
- 템플릿: `docs/features/_template.md`. 프로세스: `docs/ai-harness/02-agent-workflow.md §9`.
- 관련 PR 작업 시 spec 반드시 Read. 충돌 시 spec 먼저 갱신.

### 코드 컨벤션 (요점만 — 상세 `docs/ai-harness/08-code-conventions.md`)
- **Java**: 모든 매개변수·지역변수 `final`. 변수명 풀네임. 어노테이션 길이 피라미드. record 필드 2개+면 멀티라인.
- **Lombok**: `@Data`/`@Setter` 금지. 엔티티는 `@NoArgsConstructor(PROTECTED)` + `@AllArgsConstructor(PACKAGE)`.
- **null 검증**: DTO + Domain 둘 다 `Objects.requireNonNull`. **Service/Controller 중간 null 가드 금지** (생성자·메서드 파라미터 모두). 예외: `application.yml` 바인딩.
- **Frontend**: 함수형 + 명시 props 타입. API 호출은 `web/lib/api/` 집중. 환경변수 `NEXT_PUBLIC_*` 구분.
- **테스트**: BDD `given/when/then`. **신규 엔드포인트는 성공 E2E (RestAssured) 필수**.

### 도메인 / DDD
- 새 용어는 `06-domain-model.md §4` 등재 후 코드 사용.
- 엔티티 변경 시 `§5 엔티티` + `§6 ERD` **같은 PR 갱신**.
- 계층 침범 금지 (Controller → Repository 직접 호출 등).
- PR scope ↔ 패키지 경로 일치 (예: `scope:recommendation` ↔ `com.mobruji.recommendation.*`).

### 보안
- 시크릿 하드코딩 금지. 사용자 음역대·기호 데이터 로그/스크린샷 원문 노출 금지. 운영 DB 덤프 공유 금지.

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
# 품질 게이트는 §4 참조
```

## 7) PR 자기 점검
**PR 생성 전 (Feature Spec 있는 기능)**:
- §3 기능 + **비기능** 요구사항을 한 줄씩 읽고 구현 코드 존재 확인 (파일/메서드 수준).
- 의존성 부재로 불가능 → 사용자 보고. 단순 누락 → 구현 완료. **하드코딩/stub "일단 넘어가기" 금지**. 1:1 대응 안 되면 PR 만들지 않음.

**PR 생성 직후 (떠올리지 않았다면 PR 끝난 것 아님)**:
- [ ] `type:*` / `scope:*` 라벨 / (AI) `ai-generated` / (보호 영역) `needs-human-review`
- [ ] `gh pr create --body` 로 새로 쓴 경우 템플릿 AI 체크리스트 블록 수동 채우기 (`--body` 는 템플릿 덮어씀)

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
- **항상 자율** (메모리 `feedback-autonomous-default`): user-presence active/absent 무관. release/destructive 포함 모든 결정 즉시 자율 (사용자 인지 메시지 X). user-presence.json 은 단순 정보 표시 — 행동 분기 X.
- **약속 = binding** (메모리 `feedback-keep-promises`): "~하겠습니다" 발언은 다음 turn 부터가 아니라 **이번 turn 부터** 적용. 미적용 시 약속 위반.
- **세션 룰 영속** (메모리 `feedback-session-persist-rules`): CLAUDE.md 본문 + 메모리 두 채널로 영속. `/clear` 후에도 동일 적용. 같은 룰 두 번 사용자 정정 받으면 반복 위반 마커 추가. 세션 종료 전 §13 doc-check 의무.
- **워크트리 lock** (메모리 `feedback-worktree-lock`): 한 워크트리 = 동시 sub-agent 1. 같은 도메인 백로그 2건 동시 launch 금지 (브랜치/working tree 공유 불가). 상세: `docs/ai-harness/12-sub-agent-prompt-template.md §1 워크트리 lock`, `11-runbook §0-10`.
- **PR base develop 강제** (메모리 `feedback-pr-base-develop`): `gh pr create` 호출 시 항상 `--base develop` 명시. release PR (`develop → main`) 만 예외. 누락 시 GitHub default(`main`) base 로 생성되어 `main` 직접 변경 사고 + rev-gate.yml skip + 라벨 자동 부착 오작동. 상세: `docs/ai-harness/12-sub-agent-prompt-template.md §1 PR 생성 표준 명령`.

## 11) helper / maestro Discord 양방향 절대 룰

**채널**: `MOBRUJI_CHANNEL_ID` (#모부르지) 전용. `NOTIFY_CHANNEL_ID` 는 digest cron 만.

**ack 는 bot.py 가 처리 (helper 본체는 ack push 안 함, #963, 2026-05-24)**: bot.py `on_message` 가 사용자 메시지 받자마자 1초 generic auto-ack (`BOT_AUTO_ACK_TEXT`) 를 자동 push. helper 본체는 ack 단계 생략하고 바로 본답으로 직행 — 채널에 messages 2건 (auto-ack 1 + 본답 1) 만 남아 가독성 ↑. 사용자 깜깜이 우려는 bot auto-ack 1초 + (선택) thread stream 으로 해소. 이전엔 helper 가 별도 ack 를 push 해 3건 메시지가 섞여 가독성이 떨어졌음.

**매 사용자 메시지마다 순서대로 (한 단계라도 건너뛰면 룰 위반)**:
1. **queue append** — `~/.mobruji/helper-queue.jsonl` 에 `{"ts","message_id","text","status":"pending"}`.
1b. **target msg freeze** (#987) — queue append 직후 `cp ~/.mobruji/last-user-msg-id.txt ~/.mobruji/helper-current-target.txt` 호출. turn 시작 시점의 target msg id 를 freeze. turn 진행 중 새 user msg 도착해도 helper-current-target.txt 는 안 바뀜 → 본답 push 시 자동으로 freeze 된 id 에 reply (race condition 차단).
2. **분류** — (a) helper 자체 수정 / (b) 그 외 작업 / (c) 단순 질문.
3. **(선택) thread 생성** — 장시간 작업 (위임/조사/PR) 일 때만 `bash /home/mobruji/.mobruji/discord-reply.sh --auto-ack-thread "🔍 작업 시작 — <한 줄 요약>"` 호출해 진행 thread 생성 + thread_id 를 `~/.mobruji/helper-current-thread.txt` 에 저장 (#947). 이후 milestone (sub-task 끝 / 위임 결정 / 발견 사항) 마다 `--auto-thread "<진행 1줄>"` 으로 stream. 단순 즉답이면 skip — bot auto-ack 만으로 충분.
4. **처리** — (a) 직접 / (b) sub-agent·nmae 위임 **직후 즉시** `discord-reply.sh "X 작업 위임함"` / (c) 자체 답 push.
5. **본답 push + queue done + 검증** — 본답은 `━━━━━━━━━━━━━━━` 구분선으로 시작 (auto-ack 와 시각적 분리). **본답은 자동 Discord reply (사용자 메시지에 답장 형태)**: bot.py 가 `~/.mobruji/last-user-msg-id.txt` 에 message_id 캐시 + `discord-reply.sh` bare body 모드가 자동으로 `message_reference` payload 빌드 (#946). 명시적 disable 필요 시 `--no-reply`. push 후 message_id 행 `status: done` 갱신 + `grep '"status": "pending"' ~/.mobruji/helper-queue.jsonl` 으로 0건 확인. 1건이라도 남으면 turn 안 끝남. thread 생성했다면 마지막 줄로 `--auto-thread "[done]"` push 후 종료.

**금지**: helper 본체가 ack push 를 추가로 호출 (auto-ack 와 중복 → 가독성 ↓). `discord-reply.sh --ack` mode 는 `--auto-ack-thread` 의 thread 생성 용도로만 잔존. ack 문구 단독 push 는 deprecated (#963).

**helper launch 표현 룰**: helper 본체는 sub-agent launch 안 함 (Agent 도구는 helper sub-agent 가 nmae 동일 권한으로 launch). helper 가 사용자 응답에서 "launch 하겠습니다" 표현 사용 시 주체 혼동 — 정확히 "nmae 에 위임하겠습니다" / "sub-agent 에 위임하겠습니다" / "nmae 에 알리겠습니다" 로 표현. 메모리 [[feedback-helper-role-boundary]] 참조.

**Discord 발송 약속 표현 룰** (2026-05-24 사용자 정정): helper / nmae 가 미래 Discord 발송 약속 시 영어 동사 `push` / `post` / `send` 금지. 한국어 정중체 사용 — "메시지 드리겠습니다" / "알려드리겠습니다" / "보고 드리겠습니다". 예: ❌ "1회 push 합니다" / ❌ "결과 push 합니다" → ✅ "1회 알려드리겠습니다" / ✅ "결과 알려드리겠습니다". 메모리 [[feedback-discord-tone-formal]] 참조.

## 12) maestro/helper context% 자기 emit
mmae(tmux `mobruji:0.0`) + nmae(NCP 호스트 tmux `mobruji:0.0`) + helper(tmux `helper:0.0`) 매 turn **마지막 줄**에 context 사용률 marker 를 emit. bot.py `context_auto_clear_loop` (spec: `docs/features/context-auto-clear.md §5-6`) 가 pane 별 독립으로 95% 도달 시 자율 정리 트리거 (PR #865 multi-pane 확장).

- 정상: `===CTX:NN%===` (NN = 0–100 정수)
- 모를 때: `===CTX:?===` (트리거 안 함)
- 정리 완료 시: `===CLEAR_READY===` 동반 출력 → bot.py 가 `/clear` 전송
- **mmae / nmae / helper 만 emit**. sub-agent (be/fe/rev/plan) 는 marker 안 함.

추정 우선순위: ① `/context` slash 결과 → ② input/output 누적 ÷ 모델 window → ③ 모르면 `?`.

## 13) 세션 종료 전 문서화 일치 체크 (helper / nmae /clear 직전 의무)

helper(`tmux helper:0.0`) 또는 nmae(NCP `tmux mobruji:0.0`) 가 `/clear` 또는 세션 종료 시점 직전 turn 마지막 단계에서 **4-way 문서화 일치 체크**.

### 4-way
1. **메모리** (`/home/mobruji/.claude/projects/-home-mobruji-mobruji/memory/feedback_*.md`)
2. **CLAUDE.md** 본문 §1-§N
3. **docs/ai-harness/** (런북/spec/컨벤션)
4. **docs/features/** (feature spec)

### 체크 절차
- 직전 세션에서 신규/수정된 메모리 — CLAUDE.md 본문 또는 docs 에 반영됐는지
- CLAUDE.md ↔ docs/ai-harness 모순 없는지
- 신규 feature 룰 — `docs/features/<slug>.md` 작성됐는지

### drift 발견 시
- **즉시 자율 보강 PR launch** (사용자 부재여도 — §11 helper-role + 자율 default 룰)
- push: `[doc-check] 메모리 N건 / CLAUDE.md N건 / docs N건 → drift N건 → 보강 PR #M launch`

### 위반 정의
다음 세션 helper 가 룰 학습 못 한 채 시작 = 직전 세션 doc-check 실패. 같은 룰 두 번 사용자 정정 받으면 반복 위반 마커 추가.

관련: 메모리 `feedback-session-close-doc-check` / `feedback-session-persist-rules` / `feedback-autonomous-default`

## 14) nmae 사이클 watchdog (절대 idle 금지 — 3중 안전망)

**사용자 2026-05-24 명시: "절대로 nmae 사이클이 멈춰서는 안돼".**

### 3중 안전망
1. **bot.py `cycle_idle_watch_loop`** (외부 데몬 watchdog) — 5분 polling `~/.mobruji/cycle-status.json` 4 워크트리 검사. idle 워크트리 발견 시 자동 nmae 에 tmux inject + Discord push. **메모리/룰에 의존 X — 최후 보루**. spec: `docs/features/nmae-cycle-watchdog.md`
2. **nmae 매 turn 종료 직전 자기 점검** — cycle-status.json 4 워크트리 active 검증. idle 시 즉시 launch. 메모리 [[feedback-keep-4-cycles-active]]
3. **helper 가 사용자 메시지 처리 중 cycle-status.json 우연 발견 시** — idle 발견 시 helper 가 직접 tmux inject 가능 (같은 서버, [[feedback-helper-role-boundary]] nmae 위임 영역)

### idle 정의
`in_progress: null` AND `last_completed.completed_at` > `now - 10분`.

### nmae 가 까먹는 경우 (반복 패턴)
- sub-agent 완료 통지 처리 → cycle-status.json 갱신 → 다음 launch 까먹음
- 자기 turn 안 priority 에 밀림
- 메모리 룰 학습됐어도 행동 안 함

따라서 1번 (외부 watchdog) 가 핵심. nmae 룰 위반 시 자동 정정.

### idle 시 `note` 필드 의무 (#956 STRICT mode)
**사용자 2026-05-24 추가 정정: "타당한 사유 없으면 relaunch 강제".**

- `in_progress: null` 진입 시 cycle-status.json `note` 필드 의무 (사유 또는 다음 launch 후보).
- 미명시 시 watchdog `cycle_idle_watch_loop` 가 **STRICT relaunch prompt** 즉시 inject.
- 갱신: `tools/cycle-status/update.sh <ws> set-idle --note "..."` (수동 JSON 편집 금지).
- 검증: `tools/cycle-status/validate.sh` — idle note 누락 + timestamp sanity 동시 detect.
- env: `CYCLE_REASON_REQUIRED=1` default. 후방호환 off (=0) 가능.

Discord watchdog push 도 reason 표시 — STRICT 라벨 분리 + 워크트리별 `idle_since` / reason 한 줄.

### 수동 cycle-status.json 편집 절대 금지 (#971 회귀 방지)
**사용자 2026-05-24 정정: PR #970 회귀 사고 ("KST 시각을 Z suffix 로 hand-edit → future timestamp → detect 차단").**

- nmae / helper / mmae 모두 `tools/cycle-status/update.sh` 만 사용. `vim` / `cat <<EOF >` / `jq` 직접 편집 금지.
- 위반 시 timestamp 가 잘못된 timezone (예: KST 시각을 Z suffix 로) 들어가면 watchdog detect 차단 — 핵심 회귀 사례.
- `validate.sh` 가 매 update 후 sanity 검증 — fail 시 (a) 절차 위반 또는 (b) 시스템 시각 문제. 둘 중 어떤 경우든 즉시 root cause 조사.
- bot.py `detect_idle_worktrees` 도 future timestamp 발견 시 ERROR 로그 + Discord push (#971) — 사용자 즉시 가시화.

## 15) 검증 의무 (모든 helper/sub-agent)

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

관련: `feedback-verify-and-iterate` / `feedback-autonomous-default` / `feedback-keep-promises` / `feedback-session-close-doc-check`
