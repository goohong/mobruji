# CLAUDE.md

> 이 파일은 Claude Code가 세션 시작 시 자동으로 읽는 레포 규칙 요약이다.
> 상세 규정은 `docs/ai-harness/`를 참조한다. 이 파일은 "포인터 + 비협상 룰"만 둔다.

## 1) 프로젝트 한 줄 요약
노래방에서 **"뭐 부르지?"** 고민하는 사람에게 음역대·성별·분위기 입력 기반으로 부르기 좋은 곡을 추천하는 서비스. Spring Boot 백엔드 + Next.js 프론트 모노레포.

## 2) 기술 스택 (운영 기준)
- **Backend**: Java 21 (LTS), Spring Boot 3.5.3, Gradle
- **Frontend**: Next.js (App Router), TypeScript, Tailwind
- **DB**: MySQL 8.4 (로컬: docker-compose, 운영: TBD)
- **CI**: GitHub Actions

## 3) 필수 참조 문서
변경하거나 구현하기 전에 해당 문서를 먼저 읽는다.

- `docs/ai-harness/00-index.md` — 문서 인덱스
- `docs/ai-harness/01-harness-spec.md` — 작업 단위, 결정 규칙, **AI 작업 보호 영역 §6**
- `docs/ai-harness/02-agent-workflow.md` — 브랜치/PR/커밋/릴리즈 워크플로우
- `docs/ai-harness/03-quality-gates.md` — 빌드/테스트 게이트, 테스트 정책, PR 사이즈
- `docs/ai-harness/04-security-policy.md` — 민감정보/시크릿/금지행위
- `docs/ai-harness/05-prompt-ops.md` — 프롬프트 버전관리
- `docs/ai-harness/06-domain-model.md` — **도메인 모델 / ERD / 유비쿼터스 랭귀지 (구현 전 반드시 확인)**
- `docs/ai-harness/07-testing-guide.md` — 레이어별 테스트 전략, BDD 스타일, E2E 필수 룰
- `docs/ai-harness/08-code-conventions.md` — 코드 컨벤션 (final/DTO/엔티티/Lombok/null 검증)
- `docs/ai-harness/10-observability.md` — 로깅/메트릭/트레이싱 룰
- `docs/ai-harness/11-multi-session-runbook.md` — 다중 세션(be/fe/rev) 셋업·운영 런북
- `docs/decisions/` — ADR (횡단 결정의 영속 이력)
- `docs/features/` — Feature Spec (기능 단위 living 명세서)

> `09-notion-api-spec.md`는 추후 Notion API 명세 DB 연동 시 추가.

## 4) 비협상 룰 (어기지 말 것)

### 브랜치 / PR
- `main` 직접 푸시 금지. 모든 변경은 PR로.
- 작업 브랜치는 `develop`에서 파생: `<type>/<요약>-#<이슈번호>` (예: `feature/voice-range-input-#12`)
- PR 제목 형식: `type(scope): 제목`
  - `type`: `feat` `fix` `docs` `style` `refactor` `test` `chore`
  - `scope` 화이트리스트(final): `user` `song` `recommendation` `voice` `infra` `web` `feedback`
- PR 생성 **직후** 라벨을 부여한다(한 세트로): `type:*`, `scope:*`, (AI 작성 시) `ai-generated`, (보호 영역 변경 시) `needs-human-review`
- 리뷰 1명 승인 후 **Squash merge**. 단 `develop → main` 릴리즈는 **Merge commit** (§8 참조).

### AI 작업 보호 영역
아래 경로 변경 시 **사람 사후 리뷰 권장**(필수 아님). AI도 self-merge 가능하되, `needs-human-review` 라벨로 가시화한다.
- `.github/workflows/**`, `.github/CODEOWNERS`
- `**/db/migration/**`, `**/resources/db/**`
- `**/application*.yml`, `**/application*.properties`, `.env*`
- `backend/build.gradle*`, `backend/settings.gradle*`, `backend/gradle/**`
- `web/next.config.*`, `web/package.json`, `web/pnpm-lock.yaml`, `web/package-lock.json`
- `Dockerfile`, `docker-compose*.yml`
- `LICENSE`

> **운영 원칙**: 보호 영역은 "잘못되면 영향이 큰 영역"이라 표시만 강제. 사람 머지를 기다리느라 흐름이 끊기는 비용이 더 크다고 판단해 권장 수준으로 둔다. 사후 리뷰는 PR 코멘트/사후 PR로 한다. 정말 사람만 만져야 하는 영역(예: 라이선스, CI/CD 시크릿)은 별도 룰로 분리한다.

### 품질 게이트

**Backend** (푸시 전 필수):
```bash
cd backend && ./gradlew checkstyleMain spotlessCheck test
```
포맷 위반 시: `./gradlew spotlessApply`

**Frontend** (푸시 전 필수):
```bash
cd web && npm run lint && npm run typecheck && npm test
```

- CI 실패 상태로 머지 금지(기술적으로는 가능하나 팀 합의로 차단).

### 기능 기획 (Feature Spec)
- 중간 규모 이상 기능(신규 도메인/외부 연동/다중 PR)은 **`docs/features/<slug>.md` Feature Spec을 먼저 작성·합의**한 뒤 구현에 착수한다.
- 템플릿: `docs/features/_template.md`, 상세 규칙: `docs/features/README.md`, 프로세스: `docs/ai-harness/02-agent-workflow.md §9`.
- 관련 PR을 만들 때 해당 spec을 **반드시 Read**하고, 충돌 시 spec을 먼저 갱신한 뒤 구현한다.

### 코드 컨벤션 (요점)
상세: `docs/ai-harness/08-code-conventions.md`

**Java/Backend**
- 메서드 매개변수, 지역변수 모두 `final`
- record는 필드 2개 이상이면 멀티라인
- 어노테이션은 **글자 길이 피라미드**(짧은 것부터) 순
- 변수명은 **풀네임** (`SongRecommendationResponse songRecommendationResponse`)
- 메서드 네이밍: 조회 `read`/`get`/`find`, 등록 `create`/`add`, 수정 `update`/`modify`, 삭제 `delete`/`remove`
- DTO는 API별 분리(이번 스프린트 한정), 리스트 응답 변수명은 `responses`
- 엔티티는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)` + `@AllArgsConstructor(access = AccessLevel.PACKAGE)` 조합 (PROTECTED no-args는 Hibernate 프록시 호환을 위해 필수)
- Lombok: `@Data`/`@Setter` 금지. `@Getter`/`@NoArgsConstructor`/`@RequiredArgsConstructor`는 적극 사용
- **null 검증은 DTO와 Domain 둘 다 수행**. Domain은 매개변수의 모든 값에 대해 `Objects.requireNonNull`
- **Service/Controller 중간 null 가드 금지**. 메서드 파라미터·DI 생성자 모두 `Objects.requireNonNull` 두지 않음. 예외: `application.yml` 바인딩 설정 값은 부트 fail-fast 목적으로 유지

**Frontend**
- 컴포넌트는 함수형 + 명시적 props 타입
- 서버 상태는 React Query, 클라이언트 상태는 Zustand/Jotai 등 (스택 확정 시 ADR 추가)
- API 호출은 `web/lib/api/` 한 곳에서 집중 관리
- 환경변수는 `NEXT_PUBLIC_*` 또는 서버 전용 명확히 구분

**테스트**
- BDD 스타일(`given/when/then`), **신규 엔드포인트는 성공 케이스 E2E(RestAssured) 필수**

### 도메인 / DDD
- 새 용어는 `docs/ai-harness/06-domain-model.md §4 유비쿼터스 랭귀지`에 먼저 등재한 뒤 코드에서 사용.
- 엔티티 변경 시 `§5 엔티티`, `§6 Mermaid ERD`를 **같은 PR**에서 갱신.
- 계층 침범 금지 (Controller → Repository 직접 호출 등).
- PR scope와 패키지 경로가 일치해야 함 (예: `scope:recommendation` ↔ `com.mobruji.recommendation.*`).

### 보안 / 민감정보
- 시크릿(API 키/토큰/비밀번호) 하드코딩 금지.
- 사용자 음역대·기호 데이터는 로그/코멘트/스크린샷에 원문 노출 금지.
- 운영 DB 덤프를 로컬/공유 채널에 업로드 금지.

## 5) 워크플로우 5줄 요약
1. **이슈부터** — `.github/ISSUE_TEMPLATE/task.md`로 생성, 제목은 "동사 원형 + 목적어".
2. **브랜치** — `develop`에서 `<type>/<요약>-#<이슈번호>` 분기.
3. **커밋** — AngularJS 컨벤션(`type: 제목`).
4. **푸시 전 검증** — 위 품질 게이트 통과 (backend + web 변경 영역).
5. **PR** — 템플릿의 `AS-IS`/`TO-BE` 채우고 라벨 부여 후 리뷰 요청 → Squash merge.

## 6) 로컬 개발 명령 치트시트
```bash
# 로컬 MySQL 기동 (docker-compose.yml 기준 mysql:8.4)
docker compose up -d

# 백엔드 실행 (local 프로필 = docker MySQL 사용)
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local'

# 백엔드 품질 게이트 (푸시 전 필수)
cd backend && ./gradlew checkstyleMain spotlessCheck test

# 백엔드 포맷 자동 수정
cd backend && ./gradlew spotlessApply

# 프론트 개발 서버
cd web && npm run dev

# 프론트 품질 게이트
cd web && npm run lint && npm run typecheck && npm test

# GitHub CLI 흐름
gh issue create --title "..." --label "task,type:*,scope:*"
gh pr create --base develop --title "type(scope): 제목" --body "..."
gh pr edit <num> --add-label "type:*,scope:*,ai-generated"
gh pr merge <num> --squash --delete-branch
```

## 7) AI 에이전트 자기 점검

### 7-1) 구현 완료 후, PR 생성 전 (필수)
Feature Spec(`docs/features/*.md`)이 있는 기능이면, PR을 만들기 **전에** 다음을 수행한다:
1. Feature Spec의 `§3 기능 요구사항` 체크박스를 **한 줄씩** 읽는다.
2. **§3 비기능 요구사항(결정성·응답시간·설정 외부화·관측성 등)도 같은 방식으로 한 줄씩 읽는다.** 비기능은 기능보다 누락 빈도가 높다.
3. 각 항목에 대해 **구현 코드가 존재하는지** 확인한다 (파일명/메서드명 수준).
4. 구현이 누락된 항목이 있으면:
   - 의존성 부재(엔티티/테이블 미존재 등)로 불가능한 경우 → **사용자에게 보고**하고 스펙 수정 또는 구현 방향을 확인받는다.
   - 단순 누락이면 → 구현을 완료한다.
5. **하드코딩/stub으로 대체하여 "일단 넘어가기" 금지.** 스펙과 코드가 1:1 대응되지 않으면 PR을 만들지 않는다.

### 7-2) PR 생성 직후
PR을 만든 직후 다음을 떠올려라. 떠올리지 않았다면 PR 생성이 끝난 것이 아니다.
- [ ] `type:*` 라벨 1개 부여
- [ ] `scope:*` 라벨 1개 부여
- [ ] AI 보조/생성이면 `ai-generated` 라벨 부여
- [ ] 보호 영역 변경 시 `needs-human-review` 라벨 부여
- [ ] PR body를 `gh pr create --body`로 새로 쓴 경우, 템플릿의 AI 체크리스트 블록을 수동으로 채운다 (`--body`는 템플릿을 덮어씀).

## 8) 릴리즈 (develop → main)
- 릴리즈 PR 제목: `release: vX.Y.Z` 또는 `release: YYYY-MM-DD`
- 본문 changelog는 `type`별 그룹핑
- **Merge commit** 방식 머지 (Squash 금지). develop 히스토리를 main에 보존.
- `develop` 브랜치는 영속 브랜치이므로 **삭제하지 않음**.
- 머지 후: `git tag -a vX.Y.Z -m "..." && git push origin vX.Y.Z && gh release create vX.Y.Z --generate-notes`
- 상세: `docs/ai-harness/02-agent-workflow.md §8`

## 9) 안티패턴 (하지 말 것)
- 요청하지 않은 리팩터링/주석/타입 힌트를 추가하는 것
- 변경 범위 밖의 코드 "개선"
- 시크릿/민감정보를 로그에 남기는 방어 코드를 핑계로 원문 노출
- Controller에서 Repository 직접 호출 (계층 침범)
- 엔티티 변경 없이 도메인 문서만 바꾸거나, 문서 갱신 없이 엔티티만 바꾸기
- `main` 기본 브랜치 PR 없이 직접 push, `--force` 사용

## 10) 불명확할 때
- 구현 전에 **가정값을 명시하고** 사용자에게 확인 요청.
- 설계 결정이 필요하면 `docs/ai-harness/06-domain-model.md §7 오픈 이슈`에 추가.
- 문서와 코드가 충돌하면 **문서를 먼저 갱신**하고 구현한다 (01-harness-spec §5).

## 11-pre-0) helper 매 turn 고정 체크리스트 (절대 룰 — 누락 재발 방지)

매 사용자 메시지를 받았을 때, 아래 6단계를 **순서대로 빠짐없이** 실행한다. 한 단계라도 건너뛰면 룰 위반.

1. **ack push** — `discord-reply.sh "답변 가능합니다. 잠시만 기다려주세요."` (또는 상황별 ack 문구). 다른 어떤 action 보다 먼저.
2. **queue append** — `~/.mobruji/helper-queue.jsonl` 에 `{"ts","message_id","text","status":"pending"}` append.
3. **분류** — 요청이 (a) helper 자체 수정인가, (b) 그 외 작업인가, (c) 단순 질문인가.
4. **처리** — (a) helper 가 직접 / (b) sub-agent 또는 nmae 위임 → 위임 직후 **즉시** `discord-reply.sh "X 작업 위임함"` push / (c) helper 자체 답 push.
5. **응답 push** — 본 답변은 `━━━━━━━━━━━━━━━` 구분선으로 시작. 본 응답이 ack 와 시각적으로 분리되도록.
6. **queue done + pending 검증** — 처리한 message_id 행 status `done` 갱신 + `grep '"status": "pending"' ~/.mobruji/helper-queue.jsonl` 으로 미처리 0건 확인. 1건이라도 남았으면 turn 안 끝났다.

**누락 원인 (회고 — 2026-05-23):**
- 단일 thread 처리: 한 번에 하나만 보고 이전 obligation 망각
- 반응형 동작: system reminder 기다리고 능동적 self-check 부재
- 다단계 룰이 인지 부하 아래서 끊김 (ack → 분류 → 위임 → 보고 chain)

**재발 방지:** 위 6단계를 turn 시작 시 mental check, turn 종료 전 queue verify. 두 지점 모두 binary 검증 (pending 0건 / push 했나 안 했나) 이라 빠질 자리가 없다.

## 11-pre) helper 응답 절대 룰 (Discord 양방향)
helper 또는 maestro(mmae/nmae)가 Discord에서 **사용자 메시지를 받은 매 turn 의 첫 액션** 은 무조건 다음 호출이다:
```bash
bash /home/mobruji/.mobruji/discord-reply.sh "답변 가능합니다. 잠시만 기다려주세요."
```
또는 상황별 ack 문구 (예: "nmae가 X 작업 중이라 ~분 걸립니다"). bot.py auto-ack 는 이슈 #807 단순화에서 제거됐기 때문에, 사용자 입장에서 "받았다" 신호 없이 깜깜이가 된다.

**금지:** 분석/조사/tool call 을 먼저 하고 ack 를 뒤로 미루는 행위. 사용자가 "답변 안왔어" 라고 한 번이라도 적었다면 룰을 어긴 것이다.

**문구 3종 (확정):**
- 즉답 가능 → "답변 가능합니다. 잠시만 기다려주세요."
- 대기 필요 → "nmae가 X 작업 중이라 ~분 걸립니다"
- nmae busy + 재배치 → "nmae가 X 작업 중입니다. 중단시키고 작업 전달할까요?"

**ack ↔ 본 답변 분리 룰:**
- ack 메시지는 위 3종 중 하나 **한 줄만**. 부가 설명/조사 결과/사과/계획 일체 금지.
- 본 답변 push 시 첫 줄에 `━━━━━━━━━━━━━━━` 구분선 + 빈 줄. Discord 가 같은 author 연속 메시지를 시각적으로 묶더라도 분리되게.

채널은 **MOBRUJI_CHANNEL_ID** (#모부르지) 전용. NOTIFY_CHANNEL_ID 는 digest cron 만 사용.

## 11) context 사용량 자기 emit (maestro 자율 회복용)
maestro 본진(tmux `mobruji:0.0`)은 매 turn 응답 **마지막 줄**에 자기 context 사용률을 marker 로 emit 한다. bot.py 의 `context_auto_clear_loop` (spec: `docs/features/context-auto-clear.md §5-6`) 가 이 marker 를 polling 해서 95% 도달 시 자율 정리 사이클을 트리거한다.

**형식**
- 정상: `===CTX:NN%===` (NN = 0–100 정수). 예: `===CTX:73%===`
- 모를 때: `===CTX:?===` (bot.py 는 unknown 처리 — 트리거 안 함)
- 정리 완료 시: `===CLEAR_READY===` 도 함께 출력 (별도 줄 가능). bot.py 가 감지하면 `/clear` 전송.

**추정 방법** (가능한 것 우선)
1. `/context` slash 결과를 직전에 본 경우 → 그 수치 사용
2. 본인이 알고 있는 input/output token 누적 ÷ 모델 window 총량 (1M context 모델 기준) × 100
3. 둘 다 모르면 `===CTX:?===`

**적용 대상**
- maestro 본진만. sub-agent (be/fe/rev/plan 워크트리 sub-agent) 는 marker emit 하지 않는다 — 본진 pane 만 polling 대상.

**예시 (turn 마지막)**
```
result: PR #777 머지 완료.
===CTX:42%===
```
