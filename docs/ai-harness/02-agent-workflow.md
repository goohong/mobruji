# Agent Workflow

## 1) 브랜치 전략
- 유지 브랜치: `main`, `develop`
- 작업 브랜치: `develop`에서 파생. type 화이트리스트(8종): `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `release` (`release`는 release-prompt-template 자동 생성 PR 전용)
- 브랜치 이름 예시: `feature/voice-range-input-#12`, `docs/voice-range-input-spec-#1`
- 이슈는 `.github/ISSUE_TEMPLATE/task.md` 템플릿으로 생성하고, 제목은 브랜치 목적이 드러나게 간결하게 작성한다.

## 2) PR 라이프사이클
1. 작업 브랜치에서 변경
2. PR 생성 (base: `develop`)
3. **PR 생성 직후 즉시(같은 작업 단계에서) 아래를 모두 수행한다. 이 단계를 건너뛴 PR은 리뷰 대상이 아니다.**
   - [ ] `type:*` 라벨 1개 부여 (`type:feat` `type:fix` `type:refactor` `type:chore` `type:docs` `type:test` `type:style` `type:release`)
   - [ ] `scope:*` 라벨 1개 부여 (화이트리스트: `user` `song` `recommendation` `voice` `infra` `web` `feedback`)
   - [ ] AI가 작성/보조한 PR이면 `ai-generated` 라벨 부여
   - [ ] PR 본문이 `.github/PULL_REQUEST_TEMPLATE.md`를 덮어쓴 경우 AI 체크리스트 블록을 수동으로 다시 채워 넣는다 (`gh pr create --body`는 템플릿을 무시함).
4. 리뷰 1명 승인 (1인 개발 초기엔 self-approval 허용, PR 본문에 명시)
5. Squash merge

> **AI 에이전트 자기 점검:** PR을 만든 직후 위 4개 체크박스를 머릿속에 떠올렸는가? 떠올리지 않았다면 PR 생성이 끝난 것이 아니다. 라벨 부여까지가 "PR 생성" 단계다.

## 3) PR 작성 규칙
- 제목 형식(고정): `type(scope): 제목`
  - 예시: `feat(recommendation): 음역대 기반 1차 추천 알고리즘 구현`
  - `scope`는 아래 화이트리스트에서 선택 (final): `user`, `song`, `recommendation`, `voice`, `infra`, `web`, `feedback`
  - 신규 scope가 필요하면 이 문서를 먼저 PR로 갱신한 뒤 사용한다.
- PR 본문은 `.github/PULL_REQUEST_TEMPLATE.md` 템플릿을 사용한다.
- PR 본문은 `AS-IS`, `TO-BE` 중심으로 간결하게 작성한다.
- PR은 과도하게 커지기 전에 분할한다.
- PR 없이 직접 commit/push 금지

## 4) 예외 정책
- 리뷰 없이 개인 머지 가능 케이스:
  - 보안 긴급 이슈(예: Secret key 유출 대응)
  - 파일 내용 변경 없이 대규모 코드 스타일 변경
  - 대규모 패키지 구조 변경
- 단, 예외 머지도 PR은 생성해야 하며 사유를 본문에 기록한다.
- 단, 예외 머지는 사후 리뷰를 필수로 수행한다. (머지 후 24시간 이내)

### 4-1) rev-gate 합법 우회 — `type:emergency-hotfix` 라벨 (cross-ref)
- production-down / security / data-integrity / CI-down 즉시 패치 한정.
- 라벨 부착 시 `rev-gate.yml` whitelist skip → 즉시 머지 가능, 단 사후 audit 의무.
- 사후 의무: `rev-gate-audit.yml` 자동 issue (`audit:emergency-hotfix-followup`) + DIGEST push + 다음 사이클 rev 단계 2 launch.
- 부적합 사유 (rev 대기 길어서 / docs only / admin 자체 검토) 라벨 사용 금지.
- 상세 정책 / PR body 필수 섹션 / 사용 이력: **`docs/features/emergency-hotfix-flow.md` SoT**.
- 관련 spec: `docs/features/rev-gate-required-check-enforcement.md` §3-2 (whitelist) + `docs/features/rev-gate-audit-workflow.md` §3-3-2 (사후 분기).

## 5) 사후 리뷰(Post Review) 프로세스
1. 머지 당일 담당자가 `Post-Review` 라벨을 PR에 추가한다.
2. 리뷰어 1명이 24시간 내 아래 항목을 검토한다.
   - 보안 정책 위반 여부 (시크릿/민감정보 노출)
   - 품질 게이트 우회 사유의 타당성
   - 회귀 가능성 및 롤백 가능성
3. 결과를 PR 코멘트에 `결론/후속액션/기한`으로 기록한다.
4. 후속 수정이 필요하면 48시간 내 보완 PR을 생성한다.

## 6) 커밋 컨벤션
- AngularJS commit convention 사용
- 타입: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `release` (`release`는 release-prompt-template 자동 생성 PR 전용)
- 형식:

```text
feat: 제목 (필수)

- 본문 (생략 가능)
```

## 7) 핸드오프 체크리스트
- AI 작업 PR의 핸드오프 체크리스트는 `.github/PULL_REQUEST_TEMPLATE.md`의 `AI Agent 전용 체크리스트`를 single source of truth로 사용한다.
- 사람 작성 PR은 `AS-IS`/`TO-BE`만 채우면 되며, AI 체크리스트 블록은 비워둔다.

## 8) 릴리즈 (develop → main)
릴리즈는 `develop`에 누적된 squash 커밋들을 `main`에 한 번에 반영하는 절차다.

> **Cross-ref**: 본 절의 cadence / sync 정책 근거는 [ADR-0023 (workflow / unit file main 미동기화 사고 박제 + sync 전략)](../decisions/0023-workflow-main-sync.md) 와 sub-옵션 비교 spec `docs/features/adr-0023-suboption-analysis.md` (PR #1159 / 머지 후 정식 경로 보존). 옵션 A (정식 release PR cadence) accepted 결정 + #1122 release fork 사고 박제 + retroactive 해소 sub-옵션 A1/A2/A3 가 거기에 박제. 본 §8 은 그 결정의 *operational* 절차.

### 8-1) 변경 점검
```bash
git fetch origin
git log origin/main..origin/develop --oneline
```
릴리즈에 포함될 커밋 목록을 확인한다.

### 8-1-1) Release trigger 임계치 (옵션 C)
> 출처: `docs/features/release-cadence-v0.4.0.md` §5-1 (PR #774). v0.5.0 회고 때 재평가.

maestro는 매 사이클 끝에 다음 조건을 확인하고, 도달 시 release 후보 진입을 자동 트리거한다.

- **트리거 조건 (OR)**:
  - `type:fix` + `type:feat` PR 합산 **5 건 이상** 머지 (마지막 release tag 이후)
  - `develop` ahead `main` **20 commits 이상**
- **트리거 안 함**: `type:docs` / `type:chore` / `type:test` / `type:refactor` 만 누적된 경우 (사용자 체감 변경 없음).
- **즉시 트리거**: `release:hotfix` 라벨이 부여된 PR이 머지된 경우 임계치 무시하고 즉시 release.

카운트 방법:
```bash
# fix + feat 누적 카운트 (마지막 release tag 이후)
LAST_TAG=$(git describe --tags --abbrev=0 origin/main)
gh pr list --base develop --state merged \
  --search "merged:>=$(git log -1 --format=%cI $LAST_TAG) label:type:fix,type:feat" \
  --json number | jq length

# ahead commit 카운트
git rev-list --count origin/main..origin/develop
```

트리거 도달 시 maestro 흐름:
1. release PR draft 생성 (다음 §8-2 절차).
2. rev 워크트리에 release-readiness 점검 위임 → 모든 포함 PR에 `reviewed:claude` 라벨 부여 확인.
3. `#모부르지` 채널에 draft 링크 + 카운트 요약 push.
4. 사용자 결정 대기 (`merge` / `wait`). 사용자 승인 전에는 머지 금지.

### 8-2) 릴리즈 PR 생성 (base: `main`, head: `develop`)
- 제목 형식: `release: vX.Y.Z` 또는 `release: YYYY-MM-DD`
- 본문에 changelog를 type별(`feat` / `fix` / `chore` / `docs` 등)로 그룹핑해 기록
- 라벨: `type:chore`, `scope:infra`, (AI 작성 시) `ai-generated`
- 라벨 정책: `release:skip` 라벨이 부여된 PR은 changelog에서 제외. 기본은 "전부 포함".

### 8-2-1) 버전 결정 룰 (patch / minor / major)
현 0.x.y 체계 유지. 트리거 시 maestro가 자동 판정.

- **patch (vX.Y.Z+1)**: 포함된 PR 중 **사용자 facing `feat` 0 건** (fix / chore / docs / test / refactor 만).
- **minor (vX.Y+1.0)**: 포함된 PR 중 **사용자 facing `feat` 1 건 이상**.
- **major (vX+1.0.0)**: **breaking change** 존재 시 (API 시그니처 변경 / DB 스키마 비호환 / 환경변수 필수화 등). PR 본문/커밋 메시지에 `BREAKING CHANGE:` 트레일러 또는 `type!:` 표기로 감지.

판정이 모호하면 사용자에게 확인.

### 8-3) CI 재검증
- `.github/workflows/*-ci.yml`이 `main` 대상 PR도 트리거하도록 둔다.
- 통과 후 다음 단계로 진행한다.

### 8-4) 리뷰 및 머지
- 리뷰 1명 승인 후 머지한다.
- **머지 방식: Merge commit (Squash 금지)**
  ```bash
  gh pr merge <num> --merge
  ```
- 이유: `develop`의 개별 squash 커밋 히스토리를 `main`에 보존해 추적과 롤백이 용이하다. 머지 커밋이 릴리즈 경계가 되어 전체 롤백이 1커밋 revert로 가능하다.
- ⚠️ `develop` 브랜치는 영속 브랜치이므로 **삭제하지 않는다**.

### 8-5) 태그 + GitHub Release
```bash
git checkout main && git pull
git tag -a vX.Y.Z -m "release vX.Y.Z"
git push origin vX.Y.Z
gh release create vX.Y.Z --generate-notes
```

### 8-6) 핫픽스 정책
- 핫픽스 정책은 첫 사례 발생 시 본 문서에 추가한다 (현재는 미정의).

### 8-7) Release cadence + main↔develop sync 의무 (ADR-0023)

#### 8-7-1) cadence
- **주 1회 정식 release PR** 이 default cadence. 요일은 합의된 시점 (현재 운영 default: 매주 월요일 09:00 KST — `release-cadence-v0.4.0.md` §5-1 옵션 C 와 정합). 사용자 / nmae / sub-agent 모두 같은 주기 가정.
- 사이클 트리거 임계치는 §8-1-1 (fix+feat 5건 또는 ahead 20 commits) 와 동치. 임계치 미달 + cadence 시점 도달 시에도 minimal release PR (docs/chore only 라도) 생성 권장 — fork 누적 차단이 cadence 의 본 목적.
- **긴급 workflow 변경** (예: CI 차단 해제) 는 cadence 와 무관하게 워크플로우-only PR + 옵션 3 (base=main 직접) 으로 처리 가능 — 단 현재는 `pr-target-enforce.yml` 미도입이므로 nmae 가 사용자 사전 통보 후 진행. 별도 PR 신설 시까지 한시적 절차.

#### 8-7-2) release PR 머지 후 24h 안 sync 검증 의무
release PR 이 `Merge commit` 으로 main 에 머지된 직후, **24시간 안** 에 다음 검증을 수행한다 — 누락 시 다음 cadence 의 release PR 이 fork 사고 (§8-8) 를 재발시킨다.

```bash
git fetch origin

# (1) develop ahead main — 정상 cadence 중에는 0 또는 새 작업 누적분
git rev-list --count origin/main..origin/develop

# (2) main ahead develop — release PR 의 머지 commit 외에 0 이어야 정상
git rev-list --count origin/develop..origin/main

# (3) merge-base sanity — non-empty + 최근 release tag 와 정합
git merge-base origin/main origin/develop
```

- (2) 가 **1 보다 크면** main-only commit 발견 — back-merge PR (`sync: main → develop post-release`) 즉시 launch. main 머지 commit 1 개는 정상 (release PR 자체), 그 외는 즉시 sync.
- (3) 이 빈 응답 / unrelated histories → ADR-0023 §사고 박제 절차 (sub-옵션 A1/A2/A3) 진입. nmae 가 be sub-agent 에 retroactive 해소 위임.

#### 8-7-3) develop-only / main-only commit 누적 monitor (P0 임계 50건)
fork 누적 시점을 사후 발견하지 않도록 **매 cadence 사이 (= 매주)** 다음 카운트를 watchdog 한다.

```bash
DEVELOP_ONLY=$(git rev-list --count origin/main..origin/develop)
MAIN_ONLY=$(git rev-list --count origin/develop..origin/main)

echo "develop-only: $DEVELOP_ONLY (정상 ~ release 주기 동안 누적분)"
echo "main-only: $MAIN_ONLY (정상 = 0)"
```

- **임계치 (warning)**: `MAIN_ONLY >= 1` (= 1 commit 이라도 main-only 존재 시 즉시 sync PR).
- **임계치 (P0 alarm)**: `MAIN_ONLY >= 10` 또는 `DEVELOP_ONLY >= 50` — release loop 정체 + ADR-0023 §사고 박제 재현 risk. nmae 가 즉시 사용자 통보 + sync 전략 결정.
- **watchdog 자동화 (후속 spec)**: scheduled GHA workflow (`release-fork-watchdog.yml` 가칭) 가 매일 1회 위 카운트 측정 + 임계치 초과 시 Discord push. 본 cadence 보강 후 별도 PR 로 spec + impl 도입.

#### 8-7-4) release PR conflict 시 ADR-0023 sub-옵션 절차
release PR 생성 시 mergeable=UNKNOWN / conflict marker / merge-base 빈 응답이 관측되면 **ADR-0023 sub-옵션 A1/A2/A3 결정 절차** 로 즉시 진입. 단순 force resolve 금지.

- sub-옵션 정의 + trade-off + 실측 fork 규모 (240/162 commit 박제) 은 `docs/features/adr-0023-suboption-analysis.md` (PR #1159 머지 후 정식 경로) 에서 single source of truth.
- **권고 default**: **A3 cherry-pick** (자동화 script: `tools/release-sync/cherry-pick-release-commits.sh` — be sub-agent 후속 impl PR). audit trail 가장 명확.
- **대안**: A1 force reset (release 일관성 + wall-clock 우선 시, irreversible risk 사용자 사전 동의 필수).
- **A2 -X ours/theirs merge**: silent override risk 로 v1 비권장 — sub-옵션 spec §6 결론 참조.
- 결정 후 nmae 가 ADR-0023 §변경 이력에 채택 sub-옵션 + 사유 follow-up 기록 의무.

### 8-8) 사고 박제 — release #1122 fork (2026-05-26)
**박제 사유**: ADR-0023 옵션 A 채택 직후 첫 release PR (#1121 / #1122) 머지 시점에 main↔develop 누적 fork 발견. 본 cadence 룰 (§8-7) 의 *forward* 적용은 본 사고가 trigger.

| 항목 | 측정값 |
|---|---|
| 사고 발견 시점 | 2026-05-26 14:51 KST 직후 (사용자 옵션 A 결정 ~30분 내) |
| develop-only commits | **240 건** (`git rev-list --count origin/main..origin/develop`) |
| main-only commits | **162 건** (`git rev-list --count origin/develop..origin/main`) |
| merge-base 응답 | 빈 응답 / mergeable=UNKNOWN (release PR CI) |
| 누적 기간 | Phase 4 인프라 시점부터 cadence 미설정 상태로 누적 |
| retroactive 해소 결정 | be sub-agent 자율 판단 — sub-옵션 spec PR #1159 권고 default = A3 cherry-pick |

**Lessons** (본 cadence 룰의 근거):
1. ADR 결정만으로는 부족 — *operational* 보강 (본 §8-7) 이 동반돼야 학습 의존 ↓.
2. fork 의 발견 시점이 release PR 머지 직전이면 이미 늦음 — §8-7-3 watchdog 가 fork 1 건부터 catch.
3. `release: vX.Y.Z` cadence 가 명시되지 않으면 nmae / sub-agent / 사용자 모두 trigger 책임 회피 → 무한 fork 누적.

### 8-9) main 영구 보존 + force push 금지 (비협상)
- **main 은 영속 브랜치** — 삭제 / rename / squash-only 재작성 금지. release tag 의 ref 기준이므로 history 일관성이 audit trail 의 기반.
- **`git push --force` 또는 `--force-with-lease` 금지** — main 대상 모든 force push 금지. 사고 발생 시에도 (예: 잘못 머지된 release PR) revert commit 으로 풀고, history rewrite 금지.
- **예외 절차 없음** — main 에 force push 가 필요한 가설적 상황은 ADR 신설 + 사용자 명시 승인 + 사전 백업 절차로 분리. 본 §8 의 cadence 룰 위반이 발견돼도 force push 로 해소 시도 금지 (ADR-0023 sub-옵션 A1 force reset 도 main 대상이 아님 — develop 대상).
- 사용자 정정 인용 (2026-05-26): "단순 cherry-pick 으로 안 풀리는 규모. 정식 release PR 로 가자" — main 직접 rewrite 가 아닌 정식 release PR + sub-옵션 절차 채택 박제.

#### 8-9-1) branch protection 권고
GitHub repo settings 에 다음 protection 도입을 권고 (별도 인프라 PR — 현재 미적용).

- **`main` branch protection rule**:
  - `Require a pull request before merging` (PR 필수).
  - `Require approvals` (최소 1 — self-approval 허용 phase 에서는 0 으로 운영 가능하지만 release PR 만큼은 사용자 / rev sub-agent approval 의무).
  - `Require status checks to pass before merging` — `.github/workflows/*-ci.yml` 의 핵심 job 지정.
  - `Require linear history` (선택) — Squash merge 의무화 효과. 다만 §8-4 의 Merge commit 룰과 충돌하므로 release PR 만큼은 풀어야 함. → 현실적으로는 main 에 `Restrict who can push to matching branches` + `Do not allow bypassing the above settings` 조합으로 보호하고 linear history 강제는 develop 에만.
  - `Restrict deletions` — main 삭제 차단.
  - `Block force pushes` (필수, §8-9 짝).
- **`develop` branch protection rule**:
  - `Require a pull request before merging` (PR 필수).
  - `Block force pushes`.
  - `Restrict deletions`.
- **CODEOWNERS 활용**: `.github/CODEOWNERS` 의 `docs/ai-harness/**` / `.github/workflows/**` / `**/db/migration/**` 등 보호 영역에 `@mobruji-maestro` 지정. PR 머지 시 CODEOWNER approval 필요.
- 본 권고는 인프라 PR 분리 — 보호 영역 (`.github/CODEOWNERS`, GitHub settings) 변경이지만 rev sub-agent 가 review 대행 (2026-05-28 `needs-human-review` 라벨 폐지).

## 9) Feature Spec 프로세스
중간 규모 이상 기능은 단발 PR 계획 대신 **living document**로서의 Feature Spec을 먼저 작성·합의한 뒤 구현에 착수한다.

### 9-1) 저장소
- 위치: `docs/features/<slug>.md`
- 템플릿: `docs/features/_template.md` 복사
- 상세 규칙: `docs/features/README.md`

### 9-2) 필수 기준
다음 중 하나라도 해당하면 Feature Spec 필수:
- 신규 도메인 기능 (엔티티 신설 또는 신규 API)
- 외부 연동 도입 (음원/메타데이터 API, OAuth, ML 등)
- 여러 PR에 걸쳐 구현될 중간 규모 이상 기능

단순 버그 수정/리팩터링/스타일/문서-only는 spec 불필요. 애매하면 작성을 권장.

### 9-3) 라이프사이클
`draft` → `approved` → `implementing` → `shipped` → (`deprecated`)

- frontmatter의 `status` 필드로 추적
- 상태 전이는 해당 PR에서 같이 수정
- `blocked` (보조 상태) — 외부 의존성/데이터 부재 등으로 진행 불가. `draft` 와 구분 (의도 자체는 확정, 진행만 막힘). 차단 해제 시 원래 상태로 복귀.

### 9-4) 프로세스
1. **Spec 초안 PR** — `docs/features/<slug>.md` 신설. `type:docs` 라벨.
2. **리뷰/합의** — PR 코멘트로 논의, 결정은 spec의 "결정 로그"에, 대기 항목은 "오픈 질문"에 기록.
3. **머지** — status = `approved`. 구현 착수 가능.
4. **구현 PR들** — PR 본문에 `참고: docs/features/<slug>.md` 백링크. 첫 구현 PR 머지 시 status = `implementing`.
5. **요구사항 변경** — spec을 `type:docs` 갱신 PR로 먼저 업데이트 후 구현.
6. **도메인 모델 promote (`shipped` 직전)** — spec `§5-1 잠정 필드`에 남아있는 것들이 코드에 실제 들어갔으면, `docs/ai-harness/06-domain-model.md §5 엔티티 표`로 옮기고(promote) ERD §6도 갱신. 잠정 → 확정 전이는 같은 PR에서 처리. spec과 도메인 모델이 drift된 채 `shipped`로 가지 않도록.
7. **완료** — 마지막 구현 머지 시 status = `shipped`. 6번 promote가 끝났음을 확인하고 전이.

### 9-5) AI 에이전트 의무
- 관련 기능의 PR을 만들 때 해당 spec을 **반드시 Read**해 컨텍스트 로드.
- spec과 코드가 충돌하면 **spec을 먼저 갱신**한 뒤 구현(01-harness-spec §5 결정 규칙).
- 오픈 질문 중 구현에 영향을 주는 것이 남아있으면 구현 착수 금지, 사용자에게 확인.

## 10) 다중 AI 에이전트 운영

복수의 AI 에이전트가 같은 레포에서 동시에 동작할 수 있다. 충돌과 추적성 손실을 막기 위한 룰.

> **현재 운영 형태 (2026-05-23 기준)**: 총 **5 워크트리 (maestro 1 + sub-agent 4: be/fe/rev/plan)** 동시 가동. maestro가 단일 Claude 세션으로 오케스트레이션하고 `Agent` 도구로 각 워크트리에 sub-agent를 background 가동한다. 항시 가동 룰과 셋업은 [§11 multi-session-runbook §0-10](./11-multi-session-runbook.md#0-10-항시-4-워크트리-가동-룰)과 [ADR-0014](../decisions/0014-multi-agent-worktree-orchestration.md) 참조. (초기엔 Claude+Codex 2 에이전트 가정이었으나 Codex 미사용 + 워크트리 분리 패턴으로 진화)

### 10-1) 1 브랜치 = 1 에이전트
- 한 브랜치/PR에는 **한 에이전트만** 커밋한다. 다른 에이전트가 같은 브랜치에 직접 push 금지.
- 다른 에이전트의 변경을 보고 싶다면: **PR 코멘트**로 제안만 한다. 직접 push 하지 않는다.
- 사람만이 에이전트 브랜치를 교차로 수정/머지/리베이스할 수 있다.
- 워크트리 lock: 같은 워크트리(`mobruji-be` 등)에 동시 2 sub-agent launch 금지 (§11 §0-10 워크트리 lock 참조).
- **5분 reasoning chunk limit**: 단일 sub-agent turn의 reasoning/도구 호출 묶음이 5분을 넘기지 않도록 작업을 쪼갠다. LOC 상한·patch 작업 우선 분할이 1차 수단 (`feedback-reasoning-chunk-limit`, [ADR-0014 §Decision 6](../decisions/0014-multi-agent-worktree-orchestration.md#decision)).

### 10-2) 에이전트 식별
- **커밋 trailer**(필수): 모든 AI 작성 커밋에 `Co-Authored-By: <에이전트명> <noreply@...>`를 포함한다.
  - Claude: `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` (또는 사용 모델에 따라 표기)
  - Codex: `Co-Authored-By: OpenAI Codex <noreply@openai.com>` (현재 미사용, 도입 시 적용)
- **PR 라벨**(필수): `ai-generated` 우산 라벨 + 구체 라벨 `ai:claude` (Codex 도입 시 `ai:codex` 추가).
- **PR 본문 "AI 작업 기록"**: 사용 에이전트와 프롬프트 요약을 명시.

### 10-3) 작업 분담 (현재 형태)
maestro 오케스트레이션 + 워크트리 영역 분담이 default. 상세 역할/만질 수 있는 경로는 §11 §2 세션별 역할 표 + [`docs/ai-harness/actors/sub-agent.md`](./actors/sub-agent.md)(maestro 가 sub-agent launch 시 박는 공통 룰 + 역할별 추가 룰의 single source of truth).

- **maestro** (`mobruji`): 기획·이슈 등록·백로그 우선순위·공유 영역(`CLAUDE.md`/`docs/ai-harness/**`) 보수·develop 점유. 코드/테스트 작성은 sub-agent 위임 default.
- **be** (`mobruji-be`): `backend/**` 구현 전용.
- **fe** (`mobruji-fe`): `web/**` 구현 전용.
- **rev** (`mobruji-rev`): 사후 감사 + QA 실행 검증. 파일 수정 금지 (`pre-push` hook 차단).
- **plan** (`mobruji-plan`): ADR/spec/`docs/ai-harness/**` 갱신 전담. v0.2 메타 전환 후 maestro 부담 분산용 (`feedback-plan-session-option`, `project-plan-session-active`).

> 풀스택 기능은 be/fe 두 PR로 분리. 같은 PR에서 두 에이전트가 평행 작업 후 사람이 픽하는 패턴은 비용 크므로 학습/비교 목적에만.

### 10-4) 컨텍스트 파일
- `CLAUDE.md`: Claude 자동 로딩 룰. 비협상 룰의 single source of truth.
- `AGENTS.md`(root): Codex 자동 로딩 룰. `CLAUDE.md`를 가리키되 Codex 한정 메모를 추가한다. (현재 Codex 미사용이지만 파일은 유지 — 도입 시 즉시 활성)
- `web/AGENTS.md`: 프론트엔드 작업 시 Codex/Claude 모두 참조.
- 새 룰 추가는 `CLAUDE.md`/`AGENTS.md` 한 번에 갱신한다 (drift 방지).

### 10-5) 충돌 발생 시
- 같은 파일/심볼을 동시에 만지는 경우 → 후순위 PR이 PR 코멘트로 충돌 사실 명시 + nmae 가 사이클 우선순위 재조정 (2026-05-28 `needs-human-review` 라벨 폐지).
- spec(`docs/features/*.md`)의 결정 로그 충돌 → 사람이 합의 결정 후 다시 spec 갱신 PR.
- `.github/workflows/session-collision-check.yml`이 PR 열릴 때 자동으로 다른 open PR과의 파일 겹침을 검출해 코멘트로 경고.

### 10-6) 항시 가동 + 자율 사이클 룰
maestro는 be/fe/rev/plan 4 워크트리에 sub-agent 1개씩 가동을 **항상 유지**한다. 1개 완료 통지가 들어오면 같은 워크트리에 즉시 다음 백로그를 launch (idle 워크트리 default 금지).

- maestro 자체 작업 default는 메타 (spec/ADR/메모리/orchestration). 코드/테스트/문서 본문 작성은 sub-agent 위임.
- 통지 우선 처리: sub-agent 완료 통지는 maestro 자기 작업보다 우선 (§11 §0-8).
- 자율 운영: 사용자 부재 시에도 maestro는 완료 통지 → 백로그 정리 → 다음 사이클 launch 루프를 자체 진행. release(`develop → main`) 머지만 사용자 확인.
- **백로그 발굴 메타 단계**: 백로그 고갈 시 idle로 두지 않고 다음 후보를 순차 탐색한다 — (1) 직전 사이클 follow-up, (2) rev 코멘트 미해결 항목, (3) spec drift(문서 vs 코드 불일치), (4) 테스트 누락. 후보가 잡히면 가치 점검(비용 대비 우선순위 비교) 후 새 이슈 등록까지 한 호흡으로 진행한다 (`feedback-keep-4-cycles-active`, [ADR-0014 §Decision 3](../decisions/0014-multi-agent-worktree-orchestration.md#decision)).

구체 사이클 명명(§11 §0-4) / idle 룰(§0-5) / 사용자 결정 묶음(§0-6) / Discord 가시성(§0-6-1, §0-6-2) / 워크트리 정리(§0-7) / rev 코멘트 자동 등록(§0-9) / 항시 가동 점검 의무(§0-10)는 모두 §11에 정형화. 본 절은 §10 일관성 유지를 위한 한 줄 요약.

### 10-7) 다중 세션 실행 런북
구체 셋업·운영 명령은 [`docs/ai-harness/11-multi-session-runbook.md`](./11-multi-session-runbook.md). 워크트리 5개 생성(maestro + be/fe/rev/plan), 라벨, 새 브랜치 시작 스크립트, 리뷰 세션 트리거, Projects v2 보드 연동, 항시 가동 룰까지 포함.

### 10-8) 동기화 채널
- **세션 간 시그널**: PR 라벨(`session:*`, `reviewed:*`, `ai:*`) + draft state + `gh pr list` 조회. 새 메커니즘 없이 GitHub state가 자연스러운 싱크 채널.
- **사람 대시보드**: GitHub Projects v2(`mobruji` 보드). PR/이슈 자동 등록은 `.github/workflows/auto-add-to-project.yml`. Status/Session 필드로 칸반 + 필터.
- **모바일/외부 모니터링**: Discord webhook (`docs/ai-harness/14-discord-ops.md` / `docs/features/discord-status-push.md`). maestro 자율 사이클 trail이 GitHub events 경유로 #모부르지 채널에 push.
- `docs/backlog.md`는 폐기되었다(2026-05-21). 대체: Projects v2 보드 + 영속 결정은 `docs/decisions/` ADR로.
