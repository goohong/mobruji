# Multi-Session Runbook

> Claude/Codex를 여러 세션 동시에 돌릴 때의 셋업·운영 런북.
> `docs/ai-harness/02-agent-workflow.md §10`(다중 AI 운영 룰)을 실행 가능한 형태로 풀어 적은 문서.

## 0) 운영 모드

3개 세션(be/fe/rev)을 가동하는 방식은 두 가지. **기본은 본진 오케스트레이션.**

### 0-1) 본진 오케스트레이션 (기본 / 권장)

**본진(`mobruji` 워크트리)의 Claude 세션 하나**가 오케스트레이터 역할을 한다. be/fe/rev 작업은 본진이 `Agent` 도구로 `claude` subagent를 `run_in_background: true`로 띄워 각 워크트리에서 실행하게 한다. 사용자는 본진 한 곳에서 진행 상황을 따라간다.

사이클:
1. 백로그 정해지면 본진이 각 워크트리에 `cd`해 `new-session-branch.sh`를 실행 → 이슈/브랜치/Draft PR 사전 스캐폴드.
2. 본진이 `Agent` 도구로 be/fe/rev 서브에이전트를 동시 background 가동 (3개 병렬).
3. 각 서브에이전트는 자기 워크트리에서 코드 작성 → 품질 게이트 → push → `gh pr ready`.
4. 본진은 완료 통지를 받고 사용자에게 머지 결정 요청.

서브에이전트 프롬프트에 반드시 포함:
- `cd <워크트리 절대경로>`로 시작 강제
- 다른 워크트리/본진 건드리지 마
- 메모리(`~/.claude/projects/*/memory/`) 쓰기 금지
- `--no-verify`로 hook 우회 금지
- 보호 영역 변경 시 `needs-human-review` 라벨 부여

> 위 5줄 공통 룰은 매번 반복하지 말고 prompt에 다음 한 줄만 박는다:
> `공통 룰은 docs/ai-harness/12-sub-agent-prompt-template.md 따른다. 역할은 <be|fe|rev|plan>.`
> 역할별 추가 룰(워크트리 경로, 작업 가능 경로, 품질 게이트)도 그 문서에 정리되어 있다.

**언제 쓰나**: 사용자가 백로그를 본진에 풀어놓고 한 자리에서 운영하고 싶을 때. 대부분의 경우.

### 0-2) 수동 터미널 (대체)

사용자가 워크트리마다 별도 터미널과 Claude 인스턴스를 띄워 직접 지시한다. 본진은 develop 점유와 공유 영역 관리만 담당.

사이클: §3 (새 작업 시작)과 §4 (rev 운영) 단계를 사용자가 직접 트리거.

**언제 쓰나**:
- 서브에이전트가 의도와 다르게 동작해 본진에서 개입이 잦아질 때
- 본진이 다른 큰 작업을 동시에 진행 중이라 오케스트레이션 부담이 클 때
- 사람-루프(human-in-the-loop) 빈도를 늘리고 싶을 때

### 0-3) 모드 전환

같은 사이클 중간에 모드 전환은 피한다. 한 사이클(이슈→PR→머지)은 한 모드로 끝내고, 다음 사이클부터 바꾼다. 강제 전환이 필요하면 진행 중 서브에이전트를 정리(`TaskStop` 등) 후 수동 터미널로.

### 0-4) 사이클 명명

본진 task list에서 사이클을 부를 때 **도메인별 카운트**를 유지한다. 단순함 우선.

- `be 사이클 N`, `fe 사이클 N`, `rev 사이클 N`, `plan 사이클 N` (각자 1부터 카운트)
- 도메인 간 비교가 필요하면 PR 번호(`#76`, `#81`)로 지칭. 사이클 번호는 본진 내부 task tracking 용도.
- 통합 카운트(예: "전체 사이클 12")는 쓰지 않는다. 도메인이 달라 의미 약함.
- 표기 패턴: `<도메인> 사이클 <N> (#<PR>) <작업 한 줄>` — 예: `be 사이클 5 (#74) excludeSongIds 영속화`. task list/사용자 보고/PR 본문 일관 적용.

### 0-5) idle 사이클 룰

세션이 idle 상태(의존 PR 머지 대기, 머지된 PR 없음 등)일 때 본진은 다음 백로그를 자체 진행하도록 지시한다:

| 세션 | idle 조건 | 자체 백로그 |
|---|---|---|
| **be** | 의존 ADR/spec 머지 대기 | 작은 nit/refactor (Lombok 정리, final 누락 보완, 메서드 네이밍), 백엔드 테스트 회귀 보강(BDD 스타일 누락 케이스) |
| **fe** | API 의존 또는 디자인 결정 대기 | 컴포넌트 테스트 보강, UX 다듬기(loading/error state), a11y 점검 |
| **rev** | 머지된 PR 없음 / 리뷰 큐 빔 | `develop` 전체 QA — BE 회귀(`./gradlew test`), FE 게이트(`npm run lint/typecheck/test/build`), 통합 시나리오(`docker compose up` + bootRun + dev), 발견 시 본진에 보고 |
| **plan** | 사이클 작업 완료 후 idle | 다음 ADR/spec 후보 발굴, 메모리 → 코드 promote 검토(반복 패턴/preference 코드화), 문서 stale 점검 |

idle 룰 적용 기준:
- be/fe가 의존성 대기로 30분+ idle이면 본진이 위 백로그 중 하나를 launch
- rev는 머지 즉시 트리거가 기본이지만, 머지된 PR이 1시간+ 없으면 자체 QA 사이클 launch
- plan은 사용자가 운영 사이클 종료를 명시할 때까지 백로그 발굴 진행

### 0-6) 사용자 결정 묶음 질문 패턴

본진이 사용자에게 결정을 묻는 빈도를 조정해 컨텍스트 스위칭 비용을 줄인다.

**즉시 묻기 (interrupt-driven)**:
- PR 머지 / `develop → main` 릴리즈 머지
- 보호 영역 변경의 사후 리뷰
- 사이클 step change (다음 백로그 우선순위 재정렬)
- 코드/기획 충돌로 사람만 풀 수 있는 결정

**묶어 묻기 (batched)**:
- 작은 의사결정(naming, 사소한 UX 선택, 비기능 옵션 default) 5건 이상 누적 시 한 번에 모아 질문
- 묶음 형식 예시:
  ```
  결정 묶음 4건:
  1. ADR 0006 제목 — "추천 결정성 정책" vs "추천 결정성 + 다양성 정책"?
  2. fe 사이클 9 — 에러 boundary fallback 카피 "다시 시도" vs "재시도"?
  3. ...
  답을 한 번에 주면 본진이 일괄 적용.
  ```
- 사용자 부재 시: 사용자가 돌아오기 전까지 본진이 합리적 가정으로 진행 + 가정 명시 + 사후 정정 허용.

### 0-7) 사이클 완료 후 워크트리 정리

PR 한 묶음(예: be+fe+rev 3건)을 머지한 후 본진은 다음을 호출해 모든 워크트리를 develop 최신으로 detach 시키고 머지된 로컬 branch를 정리한다:

```bash
./scripts/post-merge-cleanup.sh
```

동작:
- 본진 + be/fe/rev/plan 워크트리에서 `git fetch origin develop` + `git checkout --detach origin/develop`
- 본진에서 `origin/develop`에 머지된 로컬 branch 일괄 삭제 (develop/main 제외, 다른 워크트리 사용 중인 branch는 skip)

수동으로 본진에서 `git -C ../mobruji-be reset --hard origin/develop` 호출하던 패턴을 대체한다. 사이클 종료 직후 1번만 호출하면 다음 사이클을 clean 상태에서 시작할 수 있다.

## 1) 셋업 (최초 1회)

### 1-1) 워크트리 3개 생성

```bash
# 본진은 ~/workspace/github/mobruji 그대로
git worktree add --detach ../mobruji-be
git worktree add --detach ../mobruji-fe
git worktree add --detach ../mobruji-rev
```

`--detach`인 이유: git은 같은 브랜치(develop)를 여러 워크트리에서 동시에 체크아웃 못 함. detached로 만들면 각 세션에서 `new-session-branch.sh`가 `origin/develop`을 기준으로 새 브랜치를 만들어 작업한다.

확인:
```bash
git worktree list
# /Users/goohong/workspace/github/mobruji      <sha> [develop]
# /Users/goohong/workspace/github/mobruji-be   <sha> (detached HEAD)
# /Users/goohong/workspace/github/mobruji-fe   <sha> (detached HEAD)
# /Users/goohong/workspace/github/mobruji-rev  <sha> (detached HEAD)
```

### 1-2) 메모리 디렉토리 공유 (선택)

Claude는 워크트리 경로별로 별 메모리를 갖는다. 본진 메모리(사용자 선호·feedback)를 모든 세션에서 공유하고 싶으면 symlink:

```bash
BASE=~/.claude/projects/-Users-goohong-workspace-github-mobruji/memory
for w in be fe rev; do
    target=~/.claude/projects/-Users-goohong-workspace-github-mobruji-$w/memory
    mkdir -p "$(dirname "$target")"
    ln -snf "$BASE" "$target"
done
```

> ⚠️ **메모리 race 주의**
>
> 본 symlink는 3개 세션이 **같은** 메모리 디렉토리(특히 `MEMORY.md`)를 공유하게 만든다. 두 세션이 동시에 같은 파일을 쓰면 마지막 write가 이전 write를 덮어쓴다.
>
> - **안전한 패턴**: 1인이 한 번에 1세션과만 대화 (사용자 입력 단위로 자연 직렬화).
> - **위험한 패턴**: `/loop` 같은 자동 스케줄러로 여러 세션을 동시에 작업하게 둘 때, 또는 세 세션을 동시에 같은 토픽으로 직접 입력할 때.
> - **회피책**: 메모리 갱신이 잦은 세션은 1개로 제한하거나, 세션별 memory 디렉토리를 분리(symlink 대신 별 디렉토리)해서 사용. 두 번째 패턴은 본진 메모리 공유 이점을 잃으므로 첫 번째를 권장.

### 1-3) 라벨 적용

```bash
bash scripts/setup-labels.sh
```

`session:backend`, `session:frontend`, `session:review`, `reviewed:claude` 추가됨.

### 1-4) Projects v2 보드 (사람용 대시보드)

**현재 셋업된 보드**: https://github.com/users/goohong/projects/5 (mobruji)

#### 최초 셋업 (이미 완료, 재현용 참고)
```bash
# 토큰 scope 갱신 (1회)
gh auth refresh -s project,read:project

# 보드 생성
gh project create --owner goohong --title "mobruji"

# Session 필드 추가
gh project field-create <number> --owner goohong \
    --name "Session" --data-type SINGLE_SELECT \
    --single-select-options "backend,frontend,review,infra,release"

# repo variable 등록 (auto-add workflow가 참조)
gh variable set PROJECT_URL --body "https://github.com/users/goohong/projects/5" \
    --repo goohong/mobruji
```

#### auto-add workflow 동작 조건

`.github/workflows/auto-add-to-project.yml`이 PR/이슈를 자동 등록한다. 단:

- **User-owned project**(현재 상태)는 `github.token`으로 add 불가 (GitHub 제약).
- → `repo` + `project` scope의 **Personal Access Token (Classic)** 을 발급해 repo secret `PROJECT_TOKEN`으로 등록해야 작동.

PAT 발급:
1. https://github.com/settings/tokens (classic) → Generate new token
2. Scopes: `repo`, `project`
3. 토큰을 repo secret으로 등록:
   ```bash
   gh secret set PROJECT_TOKEN --repo goohong/mobruji
   # (붙여넣기 프롬프트에 토큰 입력)
   ```
4. 이후 PR/이슈 열릴 때 자동으로 보드에 추가됨.

PAT 없이도 워크플로우는 살아있고 `skipping`으로 graceful fail. 사람이 보드에 수동 추가하면 동일 효과.

#### 수동 추가 (PAT 미사용 시)
```bash
gh project item-add 5 --owner goohong --url https://github.com/goohong/mobruji/issues/<N>
gh project item-add 5 --owner goohong --url https://github.com/goohong/mobruji/pull/<N>
```

#### Status / Session 필드 운영
- 기본 `Status`: Todo / In Progress / Done. UI에서 칸반 보드로 자동 표시.
- `Session` 필드: 새 PR을 보드에 add 후 backend/frontend/review/infra/release 중 하나로 설정.
- 자동 전이: 별 워크플로우 없음. 사람이 UI에서 드래그하거나 `gh project item-edit`로 갱신.

### 1-5) rev 세션 push 차단 hook 설치

`mobruji-rev` 워크트리는 리뷰 전용이라 코드를 push할 일이 없다. 실수로 변경 후 push하는 사고를 git hook 단에서 차단한다.

```bash
# 어느 워크트리에서 실행해도 됨. core.hooksPath는 모든 워크트리에 공유 적용된다.
git config core.hooksPath scripts/git-hooks
```

확인:
```bash
cd ../mobruji-rev
git commit --allow-empty -m "test" && git push 2>&1 | head -5
# → [BLOCK] rev 세션 워크트리에서는 push 금지. ... 가 출력되고 push 차단됨
```

hook 스크립트는 `scripts/git-hooks/pre-push`. 워크트리 basename이 `mobruji-rev`일 때만 차단하고, 본진/be/fe는 통과한다. 진짜 필요할 때만 `git push --no-verify`로 우회 가능(사후 보고 필요).

> ⚠️ `core.hooksPath`를 바꾸면 기존 `.git/hooks/` 안의 hook은 더 이상 실행되지 않는다. 다른 hook을 쓰고 있었다면 `scripts/git-hooks/`로 옮긴다.

## 2) 세션별 역할

| 세션 | 워크트리 | 역할 | 만질 수 있는 파일 | 금지 |
|---|---|---|---|---|
| **본진** | `mobruji` | 오케스트레이션·**기획·이슈 등록·백로그 우선순위**·공유 영역 관리·develop 점유 | `CLAUDE.md`, `AGENTS.md`, `docs/ai-harness/**`, root 설정, 일회성 인프라 보수 PR | 다른 세션 브랜치 체크아웃(=develop 점유 해제) |
| **be** | `mobruji-be` | 백엔드 **구현 전용** | `backend/**`, `docs/features/*.md`(backend 부분), `docs/ai-harness/06-domain-model.md` §5/§6 (Spring entity 변경 시) | `web/**`, 다른 세션의 브랜치, **기획/이슈 등록(본진에 보고만)** |
| **fe** | `mobruji-fe` | 프론트엔드 **구현 전용** | `web/**`, `docs/features/*.md`(UI 부분) | `backend/**`, 다른 세션의 브랜치, **기획/이슈 등록(본진에 보고만)** |
| **rev** | `mobruji-rev` | 사후 감사 + **QA 실행 검증** (read + PR 코멘트만, 파일 수정 금지) | (없음 — `pre-push` hook으로 push 차단됨) | 모든 직접 수정. 이슈 등록은 본진에 보고 |

### rev 세션의 QA 범위 (확장)
rev는 read-only 감사 외에 **실행 검증**도 수행한다 (워크트리 안에서 read와 실행만, 파일 수정 금지):
- 백엔드: `./gradlew test`로 회귀 확인, RestAssured E2E 결과 분석, curl로 머지된 엔드포인트 sample 검증
- 프론트: `npm run lint/typecheck/test/build`, `npm run dev` 띄우고 curl로 SSR 응답 확인
- BE+FE 통합: `docker compose up -d` + `./gradlew bootRun` + `npm run dev` 동시 기동 후 흐름 + 결정성 + p95 + 다양성 검증
- 발견 사항은 PR 코멘트로 남기고, 후속이 필요하면 본진에 보고(이슈 등록은 본진).

**공통 룰**:
- be/fe 세션은 `origin/develop`에서 분기 (워크트리는 detached HEAD라 develop을 체크아웃하지 않는다).
- 한 세션의 브랜치에 다른 세션이 직접 push 금지.
- 공유 영역(`CLAUDE.md`, `AGENTS.md`, `docs/ai-harness/`, root 설정) 변경은 본진에서 처리.
- 본진은 항상 `develop` 브랜치에 머물러야 한다. 본진에서 다른 세션 브랜치를 체크아웃하면 develop 점유가 해제돼 다른 세션이 stale 참조하는 사고가 생긴다. 본진에서 일회성 PR을 만들어야 할 때는 임시 브랜치 분기 후 머지 즉시 `develop`으로 복귀.
- **자율 운영**: 사용자 부재 시에도 본진은 sub-agent 완료 통지 → 백로그 정리 → 다음 사이클 launch 루프를 자체 진행. release(`develop → main`) 머지만 사용자 확인.

## 3) 새 작업 시작 (be/fe 세션)

해당 워크트리 디렉토리에서:

```bash
./scripts/new-session-branch.sh <session> <type> <scope> <slug> [issue_title]

# 예시:
./scripts/new-session-branch.sh be feat voice voice-range-import "외부 API에서 음역대 import"
./scripts/new-session-branch.sh fe feat web voice-range-input-page "음역대 입력 페이지"
```

스크립트가 한 번에:
1. `origin/develop` 동기화 (`git fetch origin develop` — 워크트리는 detached HEAD라 `git pull`을 쓰지 않는다)
2. 이슈 생성 (라벨: task, type:*, scope:*, ai-generated, ai:claude, session:*)
3. 브랜치 분기 (`<branch_type>/<slug>-#<issue_num>`, `origin/develop` 기준)
4. 빈 스캐폴드 커밋 + push
5. Draft PR 생성 + 라벨 부여

작업 끝나면:
```bash
git push
gh pr ready <PR번호>   # draft → ready for review
```

## 4) 리뷰 세션 (rev) 운영

워크트리 `mobruji-rev`에서 Claude 세션 실행. 사용자가 한 번 트리거하거나 `/loop`로 주기 실행:

```
/loop 15m PR 리뷰 한 번 돌려
```

또는 수동 트리거 시 다음 패턴:

```
다음 작업을 진행해줘:
1. gh pr list --search "is:open draft:false -label:reviewed:claude" --json number,title
2. 각 PR마다:
   - gh pr view <N> --json title,body,labels
   - gh pr diff <N> 으로 변경 확인
   - 관련 spec(docs/features/*.md) Read해서 정합성 확인
   - 코드 컨벤션(docs/ai-harness/08-code-conventions.md) 위반 체크
   - 0~N개 review 코멘트 작성: gh pr review <N> --comment --body "..."
   - 끝나면 라벨 부여: gh pr edit <N> --add-label reviewed:claude
3. 결과 요약
```

리뷰 세션은 **절대 코드/파일을 수정하지 않는다**. PR 코멘트만.

## 5) 동기화 / 충돌 처리

### 자연스러운 동기화
- 모든 세션은 GitHub state(PR/이슈/라벨)를 같은 source로 봄.
- be/fe 워크트리는 detached HEAD 상태이므로 **`git checkout develop`을 쓰지 않는다.** develop은 본진이 점유 중이라 다른 워크트리에서 체크아웃하면 충돌한다.
- 작업 시작 직전: 해당 워크트리에서 `git fetch origin develop` → `new-session-branch.sh`가 `origin/develop` 기준으로 새 브랜치를 만든다.
- 머지 후 동기화:
  - **본진** 워크트리: `git pull --ff-only`로 develop 최신화.
  - **be/fe** 워크트리: `git fetch origin develop`만. 이미 작업 브랜치에서 작업 중이라면 필요 시 `git rebase origin/develop`.

### 자동 충돌 감지
`.github/workflows/session-collision-check.yml`이 PR 열릴 때:
- 다른 open non-draft PR과 변경 파일이 겹치는지 검사
- 겹치면 PR에 경고 코멘트

코멘트가 뜨면:
- 두 세션 작업 중 어느 쪽이 후순위가 될지 사용자가 결정
- 후순위는 선순위 머지 후 rebase

### 사람 중재가 필요한 케이스
- 공유 영역(`CLAUDE.md` 등) 변경
- spec(`docs/features/*.md`) 동시 수정
- 도메인 모델 §5/§6 동시 수정
- 다른 세션의 브랜치 hotfix가 필요한 경우

## 6) Troubleshooting

### worktree에서 `gh` 명령이 안 됨
같은 repo이므로 작동해야 함. 토큰 scope 확인: `gh auth status`.

### Claude 세션이 메모리 못 읽음
worktree 경로가 정확한지 확인. `.claude/projects/<encoded-path>/memory/` 가 존재해야 함. symlink 셋업했으면 link target 확인.

### 동시 세션 두 개가 같은 브랜치를 만지려 함
규칙 위반. 한 세션이 stop. 사용자가 누가 진행할지 결정.

### Projects v2에 자동 등록 안 됨
- `PROJECT_URL` repo variable 확인: `gh variable list`
- 워크플로우 로그 확인: `gh run list --workflow=auto-add-to-project.yml`
- 토큰 권한 부족이면 PAT를 `PROJECT_TOKEN` secret으로 추가

## 7) 정리 / 폐기

세션 끝내고 워크트리 제거:
```bash
git worktree remove ../mobruji-be
# 메모리 디렉토리는 직접 정리
rm -rf ~/.claude/projects/-Users-goohong-workspace-github-mobruji-be
```

영구 셋업이라면 그대로 두고 사용.
