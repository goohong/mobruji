# branch-cleanup

remote/local branch + `.claude/worktrees/agent-*` + closed issue cleanup tooling. issue #1127.

## 배경

2026-05-26 사용자 16:04 directive — **remote 451 / closed issue 416 / open issue 142** 누적. PR squash merge 후 head branch 삭제 안 되어 누적 + closed issue archive 안 함 → 검색 / GitHub UI 가독성 ↓.

본 도구는 PR #1128 의 sweep.sh 1차 구현을 확장합니다 (#1127 IMPL):
- closed-not-merged PR head branch 카테고리 추가
- markdown report `reports/<YYYY-MM-DD>.md` 자동 생성
- `--issues` flag — closed issue 분류 + stale 라벨 / archive milestone

## 정책 — 5 카테고리

| category | 조건 | 처리 |
|---|---|---|
| **merged-branch** | PR `merged` + merge 시점 30일+ 경과 | remote + local 삭제 |
| **closed-no-merge-branch** | PR `closed` + `mergedAt=null` + closedAt 30일+ | remote + local 삭제 |
| **stale-branch** | open PR 없음 + last commit 60일+ | remote + local 삭제 |
| **agent-worktree** | `.claude/worktrees/agent-*` path 존재 | `git worktree remove --force` |
| **closed-issue** (`--issues`) | 30일+ closed + stale 라벨 미부착 | `stale` 라벨 부착 (+ 옵션 milestone) |

### 보호 (whitelist, 모든 카테고리 공통)

- `main` / `develop` / `HEAD` / 현재 checkout
- `release/*` / `hotfix/*` 영구
- open PR head (`gh pr list --state open`)
- 최근 N일 (default 7일) active commit branch (`--active-days`)

## 사용

```bash
# dry-run + 통계 + markdown report (실 변경 없음)
bash tools/branch-cleanup/sweep.sh

# 카테고리 좁히기
bash tools/branch-cleanup/sweep.sh --merged-only            # 머지된 branch 만
bash tools/branch-cleanup/sweep.sh --closed-no-merge-only   # closed-not-merged 만
bash tools/branch-cleanup/sweep.sh --stale-only             # stale 만
bash tools/branch-cleanup/sweep.sh --remote-only            # remote 만 (local 건드리지 않음)
bash tools/branch-cleanup/sweep.sh --local-only             # local 만

# cutoff 조정
bash tools/branch-cleanup/sweep.sh --merged-days 60         # 30 → 60일
bash tools/branch-cleanup/sweep.sh --closed-no-merge-days 60
bash tools/branch-cleanup/sweep.sh --stale-days 90          # 60 → 90일
bash tools/branch-cleanup/sweep.sh --active-days 14         # 보호 윈도우 확장
bash tools/branch-cleanup/sweep.sh --limit-merged 2000      # gh API limit

# 실 삭제 (branch 5 카테고리)
bash tools/branch-cleanup/sweep.sh --apply                  # confirm prompt
bash tools/branch-cleanup/sweep.sh --apply --yes            # 자동화 용 (CI)
bash tools/branch-cleanup/sweep.sh --merged-only --apply --yes

# closed issue 정리
bash tools/branch-cleanup/sweep.sh --issues                 # 분류만 (dry-run)
bash tools/branch-cleanup/sweep.sh --issues --apply --yes   # stale 라벨 부착
bash tools/branch-cleanup/sweep.sh --issues --apply --yes \
    --archive-milestone "archived-2026Q2"                   # milestone 으로 묶기
bash tools/branch-cleanup/sweep.sh --issues --issues-days 60       # 30 → 60일
bash tools/branch-cleanup/sweep.sh --issues --issues-stale-label aged
```

## report

### markdown (default)

`tools/branch-cleanup/reports/<YYYY-MM-DD>.md` — 사람 가독 + PR 본문에 첨부 가능.

표 형식:
```
| 카테고리                  | 후보 수 |
|---|---:|
| merged-30d+              | 12     |
| closed-nomerge-30d+      | 3      |
| stale-60d+               | 8      |
| agent-worktree           | 53     |
| closed-issue-30d+        | 400    |
```

`--no-md-report` 로 비활성.

### plain text

`${REPO_ROOT}/sweep-report-<ISO_TS>.txt` — pipe / grep 친화.

형식:
```
# sweep-report 20260526T071716Z
# REPO=/home/mobruji/mobruji-be
# APPLY=0 MERGED_DAYS=30 ...

## branches
merged-30d+|feat/foo-#42|#100|2026-04-15T...
closed-nomerge-30d+|fix/bar-#88|#90|2026-04-20T...
stale-60d+|feat/abandoned-#77|-|2026-02-15T...
agent-worktree|/path/.claude/worktrees/agent-abc|-|locked=1

## issues
closed-issue-30d+|#123|2026-04-10T...|이슈 제목
```

`--no-report` 로 둘 다 비활성.

## 테스트

```bash
bash tools/branch-cleanup/test_sweep.sh
```

20개 inline shell 테스트 — 문법 / dry-run / 보호 (main/develop/release/hotfix/active-days) / 모드 flag / 보고서 형식 / `--issues` 활성.

## 자동화 (cron / GitHub Actions)

운영 schedule 예 (cron 0 6 * * 1 — 매주 월요일 06:00 KST):
```bash
0 6 * * 1 cd /home/mobruji/mobruji && bash tools/branch-cleanup/sweep.sh --merged-only --apply --yes
```

GitHub Actions 도입 시 `.github/workflows/branch-cleanup.yml` 추가 — `needs-human-review` 라벨 필요 (보호 영역).

## 안티패턴

- `git push --force-with-lease` 또는 `git branch -D` 직접 호출하지 말고 본 스크립트 통해. protected list + dry-run 안전 장치.
- `.claude/worktrees/agent-*` 도 절대 `rm -rf` 직접 X — git worktree state 가 garbage 됩니다. 반드시 `git worktree remove`.
- closed issue 를 일괄 `gh issue close --reason "not planned"` 로 다시 닫지 마십시오 — 이미 닫혀있음. 정리는 라벨 / milestone 만.
