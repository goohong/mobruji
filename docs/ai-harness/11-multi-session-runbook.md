# Multi-Session Runbook

> Claude/Codex를 여러 세션 동시에 돌릴 때의 셋업·운영 룬북.
> `docs/ai-harness/02-agent-workflow.md §10`(다중 AI 운영 룰)을 실행 가능한 형태로 풀어 적은 문서.

## 1) 셋업 (최초 1회)

### 1-1) 워크트리 3개 생성

```bash
# 본진은 ~/workspace/github/mobruji 그대로
git worktree add ../mobruji-be develop
git worktree add ../mobruji-fe develop
git worktree add ../mobruji-rev develop
```

확인:
```bash
git worktree list
# /Users/goohong/workspace/github/mobruji        <sha> [develop]
# /Users/goohong/workspace/github/mobruji-be     <sha> [develop]
# /Users/goohong/workspace/github/mobruji-fe     <sha> [develop]
# /Users/goohong/workspace/github/mobruji-rev    <sha> [develop]
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

⚠️ 두 세션이 동시에 같은 메모리 파일을 쓰면 race. 1인 1세션 직접 호출 패턴에서는 무해. 자동화 스케줄러로 동시 호출 시 충돌 위험 있음.

### 1-3) 라벨 적용

```bash
bash scripts/setup-labels.sh
```

`session:backend`, `session:frontend`, `session:review`, `reviewed:claude` 추가됨.

### 1-4) Projects v2 보드 (사람용 대시보드)

#### 토큰 scope 갱신
```bash
gh auth refresh -s project,read:project
```

#### 보드 생성
```bash
gh project create --owner goohong --title "mobruji"
# → 출력에 number 있음. 예: Project #1
```

#### 커스텀 필드 추가
GitHub UI에서 (gh project field-create CLI도 가능):
- `Session` (single select): `backend`, `frontend`, `review`, `infra`, `release`
- `Status`는 기본 제공 (`Todo`, `In Progress`, `Done`) — 그대로 사용. 필요 시 `Review` 추가.

#### 자동 등록 워크플로우 활성화
1. Project URL을 repo variable로 등록:
   ```bash
   gh variable set PROJECT_URL --body "https://github.com/users/goohong/projects/1"
   ```
2. (선택) repo write 권한 있는 PAT를 `PROJECT_TOKEN` secret으로 등록하면 cross-repo도 가능. 단일 레포면 기본 `github.token`도 작동.
3. `.github/workflows/auto-add-to-project.yml`이 이후 모든 PR/이슈를 자동 등록.

## 2) 세션별 역할

| 세션 | 워크트리 | 역할 | 만질 수 있는 파일 | 금지 |
|---|---|---|---|---|
| **be** | `mobruji-be` | 백엔드 구현 | `backend/**`, `docs/features/*.md`(backend 부분), `docs/ai-harness/06-domain-model.md` §5/§6 (Spring entity 변경 시) | `web/**`, 다른 세션의 브랜치 |
| **fe** | `mobruji-fe` | 프론트엔드 구현 | `web/**`, `docs/features/*.md`(UI 부분) | `backend/**`, 다른 세션의 브랜치 |
| **rev** | `mobruji-rev` | 리뷰 전용 (read + PR 코멘트만) | (없음 — 코드/문서 직접 수정 금지) | 모든 직접 수정 |

**공통 룰**:
- 모든 세션은 `develop`에서 분기.
- 한 세션의 브랜치에 다른 세션이 직접 push 금지.
- 공유 영역(`CLAUDE.md`, `AGENTS.md`, `docs/ai-harness/`, root 설정) 변경은 사용자가 본진에서 직접 또는 rev 세션이 사용자와 합의 후.

## 3) 새 작업 시작 (be/fe 세션)

해당 워크트리 디렉토리에서:

```bash
./scripts/new-session-branch.sh <session> <type> <scope> <slug> [issue_title]

# 예시:
./scripts/new-session-branch.sh be feat voice voice-range-import "외부 API에서 음역대 import"
./scripts/new-session-branch.sh fe feat web voice-range-input-page "음역대 입력 페이지"
```

스크립트가 한 번에:
1. `develop` 동기화 (`git pull --ff-only`)
2. 이슈 생성 (라벨: task, type:*, scope:*, ai-generated, ai:claude, session:*)
3. 브랜치 분기 (`<branch_type>/<slug>-#<issue_num>`)
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
- 작업 시작 직전 항상 `git pull --ff-only`.
- 머지 후 다른 세션도 `git checkout develop && git pull --ff-only`로 동기화.

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
