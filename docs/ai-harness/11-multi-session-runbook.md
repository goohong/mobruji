# Multi-Session Runbook

> Claude/Codex를 여러 세션 동시에 돌릴 때의 셋업·운영 런북.
> `docs/ai-harness/02-agent-workflow.md §10`(다중 AI 운영 룰)을 실행 가능한 형태로 풀어 적은 문서.

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
| **본진** | `mobruji` | develop 점유 + 공유 영역 관리 | `CLAUDE.md`, `AGENTS.md`, `docs/ai-harness/**`, root 설정, 일회성 인프라 보수 PR | 다른 세션 브랜치 체크아웃(=develop 점유 해제) |
| **be** | `mobruji-be` | 백엔드 구현 | `backend/**`, `docs/features/*.md`(backend 부분), `docs/ai-harness/06-domain-model.md` §5/§6 (Spring entity 변경 시) | `web/**`, 다른 세션의 브랜치 |
| **fe** | `mobruji-fe` | 프론트엔드 구현 | `web/**`, `docs/features/*.md`(UI 부분) | `backend/**`, 다른 세션의 브랜치 |
| **rev** | `mobruji-rev` | 리뷰 전용 (read + PR 코멘트만) | (없음 — 코드/문서 직접 수정 금지, `pre-push` hook으로 push 차단됨) | 모든 직접 수정 |

**공통 룰**:
- be/fe 세션은 `origin/develop`에서 분기 (워크트리는 detached HEAD라 develop을 체크아웃하지 않는다).
- 한 세션의 브랜치에 다른 세션이 직접 push 금지.
- 공유 영역(`CLAUDE.md`, `AGENTS.md`, `docs/ai-harness/`, root 설정) 변경은 본진에서 처리.
- 본진은 항상 `develop` 브랜치에 머물러야 한다. 본진에서 다른 세션 브랜치를 체크아웃하면 develop 점유가 해제돼 다른 세션이 stale 참조하는 사고가 생긴다. 본진에서 일회성 PR을 만들어야 할 때는 임시 브랜치 분기 후 머지 즉시 `develop`으로 복귀.

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
