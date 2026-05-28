---
feature: Branch cleanup policy (remote / local / agent worktrees)
slug: branch-cleanup-policy
status: approved
owner: @mobruji-maestro
scope: infra
related_issues: [1109]
related_prs: []
last_reviewed: 2026-05-26
---

# Branch cleanup policy (remote / local / agent worktrees)

## 1) 개요 (What / Why)

2026-05-26 기준 mobruji repo 의 brach / worktree 상태가 관리 임계점을 초과했다:

- **remote**: `origin/*` 444 개 (대부분 머지된 PR 의 head branch 가 잔존).
- **local (NCP 호스트)**: 862 개 (오래된 사이클 branch, debug branch, throwaway branch 누적).
- **.claude/worktrees/agent-***: 60+ 개 (locked + stale, sub-agent launch 시 자동 생성 후 cleanup 안 됨).

문제:
- `gh pr list` / `gh branch list` 결과 가독성 ↓ — 사용자 / nmae 가 active 작업 추적 어려움.
- 디스크 비용 (`.claude/worktrees/` 각각 수십 MB). 60+ × 수십 MB = 수 GB.
- nmae 가 새 사이클 launch 시 stale worktree 와 충돌 가능 ([[feedback-worktree-lock]] 추가 risk).
- 머지된 PR 의 head branch 가 남아 있으면 fix/feat 검색 시 노이즈.

본 spec 은 **삭제 정책 + 보호 정책 + 자동화 가능성** 을 명문화한다.

## 2) 사용자 시나리오

- **시나리오 1**: nmae 가 매주 (또는 dry-run 기준 매일) `tools/branch-cleanup/sweep.sh --dry-run` 실행 → 삭제 후보 목록 출력 → 위험 항목 없음 confirm 후 `--apply` 로 일괄 삭제.
- **시나리오 2**: 사용자가 `tools/branch-cleanup/sweep.sh --dry-run --verbose` 로 sweep 후보 직접 확인. 보호 brach (main / develop / 활성 사이클) 가 결과에 포함되지 않음을 검증.
- **시나리오 3**: sub-agent 가 사이클 끝난 후 자기 워크트리를 정리. nmae 가 자동으로 `git worktree remove` 호출.

## 3) 요구사항

### 기능 요구사항

- [ ] **삭제 대상 정의** (§5-1):
  - 머지된 PR head branch + 30일+ 경과 (closed/merged 기준).
  - open PR 없는 branch + last commit 60일+ 경과.
  - `.claude/worktrees/agent-*` 60+ 일 지난 항목 → `git worktree remove --force` + 디렉토리 prune.
- [ ] **보호 대상 정의** (§5-2): main / develop / 활성 사이클 worktree branches / release tag branches.
- [ ] **sweep 스크립트 신설** — `tools/branch-cleanup/sweep.sh`, dry-run default + `--apply` 명시 시에만 실제 삭제.
- [ ] **cron 정책** (§5-4): 주 1회 자동 dry-run + Discord push, manual `--apply` 만 허용 (자동 apply 금지).
- [ ] **검증 / 회귀 방지** (§7): 보호 branch 가 sweep 결과에 포함되지 않음을 unit test 로 보장.

### 비기능 요구사항

- **안전성**: dry-run 이 default. `--apply` 명시 안 하면 실제 변경 X. 사용자 / nmae 가 결과를 미리 검토 가능.
- **idempotency**: 같은 sweep 을 여러 번 실행해도 동일 결과 (이미 삭제된 항목은 skip).
- **롤백 가능성**: 삭제된 branch 는 머지된 PR head 라면 GitHub 에서 1회 클릭 (Restore branch) 으로 복구 가능. local branch 는 reflog 로 복구 가능 (~30일 이내).
- **운영 부담**: 주 1회 nmae 가 dry-run 결과 review + `--apply` 결정. 자동 apply 는 운영 안전 시점 도달 후 별도 PR 로 활성화 (현 spec 에서 금지).

## 4) 범위 / 비범위 (중요)

### 포함

- remote branch (`origin/*`) 삭제 정책.
- local branch (NCP 호스트, helper / nmae 의 mobruji 워크트리 + sub-agent 워크트리) 삭제 정책.
- `.claude/worktrees/agent-*` worktree 정리 정책.
- sweep 스크립트 (`tools/branch-cleanup/sweep.sh`) 의 dry-run / --apply 인터페이스.
- 보호 branch 정의 (main / develop / 활성 사이클 / release tag).

### 제외 (Out of Scope)

- 자동 `--apply` cron — 본 spec 은 dry-run cron + manual `--apply` 만. 자동 apply 는 운영 안전 시점 도달 후 별도 spec.
- release tag (`v*`) 삭제 — tag 는 영구 보존 (`docs/ai-harness/02-agent-workflow.md §8` 릴리즈 절차 일관).
- 보호 branch (main / develop) 의 force-delete — sweep 절대 손대지 않음.
- GitHub 의 branch protection rule 변경 — 본 spec 은 cleanup 만, protection rule 은 별도 보호 영역 (`.github/CODEOWNERS` 등).
- sub-agent 워크트리 (`/home/mobruji/mobruji-{be,fe,rev,plan}`) 자체 삭제 — 4 worktree 는 항시 보존. 본 spec 은 그 워크트리 내부의 branch 만 다룸.

## 5) 설계

### 5-1) 삭제 대상 정의

#### 5-1-1) Remote branch

후보 grep:

```bash
# 머지된 PR head branch + 30일+ 경과
gh pr list --state merged --search 'merged:<2026-04-26' --json number,headRefName,mergedAt --limit 1000 \
  | jq -r '.[] | "\(.headRefName) (PR #\(.number), merged \(.mergedAt))"'

# closed (unmerged) PR head branch + 30일+ 경과
gh pr list --state closed --search 'closed:<2026-04-26 -is:merged' --json number,headRefName,closedAt --limit 1000 \
  | jq -r '.[] | "\(.headRefName) (PR #\(.number), closed \(.closedAt))"'

# remote branch 중 open PR 가 없고 last commit 60일+
git for-each-ref --format='%(refname:short) %(committerdate:iso8601)' refs/remotes/origin/ \
  | awk -v cutoff="$(date -d '60 days ago' -Iseconds)" '$2 < cutoff {print $1}'
```

삭제 명령:

```bash
git push origin --delete <branch-name>
# 또는 sweep 스크립트 내부에서:
gh api -X DELETE repos/{owner}/{repo}/git/refs/heads/<branch-name>
```

#### 5-1-2) Local branch

후보 grep:

```bash
# local branch 중 remote 에 대응 없거나 (gone) last commit 60일+
git for-each-ref --format='%(refname:short) %(committerdate:iso8601) %(upstream:track)' refs/heads/ \
  | awk -v cutoff="$(date -d '60 days ago' -Iseconds)" '
    $2 < cutoff && ($3 == "[gone]" || $3 == "") {print $1}'
```

삭제 명령:

```bash
git branch -D <branch-name>
```

> `-D` (force delete) 사용 — 머지 안 된 branch 도 cutoff 통과 시 삭제. local 은 reflog 로 ~30일 복구 가능.

#### 5-1-3) `.claude/worktrees/agent-*`

후보 grep:

```bash
# 60일+ 안 만진 worktree
find /home/mobruji/mobruji/.claude/worktrees/agent-* -maxdepth 0 -type d -mtime +60
```

삭제 명령:

```bash
git -C /home/mobruji/mobruji worktree remove --force /home/mobruji/mobruji/.claude/worktrees/agent-<id>
rm -rf /home/mobruji/mobruji/.claude/worktrees/agent-<id>   # 잔존 디렉토리 정리
git -C /home/mobruji/mobruji worktree prune
```

### 5-2) 보호 대상 정의 (exempt)

다음 항목은 sweep 결과에 포함되지 않음 (스크립트 내부 hard-coded allowlist):

| 보호 종류 | 항목 |
|---|---|
| **branch** | `main`, `develop` |
| **워크트리 branch** | `/home/mobruji/mobruji` 의 현재 branch, `/home/mobruji/mobruji-{be,fe,rev,plan}` 의 현재 branch |
| **release tag branches** | `release/*` (있다면) |
| **활성 PR head branch** | `gh pr list --state open --json headRefName` 결과의 모든 branch |
| **최근 release tag** | `git tag -l 'v*' | sort -V | tail -3` 가리키는 commit 의 branch (있다면) |

`tools/branch-cleanup/sweep.sh` 는 위 allowlist 를 **삭제 후보 list 에서 제외 후** 출력. 단 1건이라도 보호 branch 가 후보에 들어가면 스크립트는 ERROR 로 abort.

### 5-3) Sweep 스크립트 인터페이스

`tools/branch-cleanup/sweep.sh` — be sub-agent 가 후속 PR 로 구현.

```bash
# 기본 (dry-run, 모든 대상)
bash tools/branch-cleanup/sweep.sh

# 명시 dry-run + verbose
bash tools/branch-cleanup/sweep.sh --dry-run --verbose

# 실제 적용 (manual 만)
bash tools/branch-cleanup/sweep.sh --apply

# 대상 분리
bash tools/branch-cleanup/sweep.sh --remote-only
bash tools/branch-cleanup/sweep.sh --local-only
bash tools/branch-cleanup/sweep.sh --worktrees-only

# 보호 branch 검증만
bash tools/branch-cleanup/sweep.sh --verify-protect
```

출력 형식 (dry-run 예시):

```text
=== Branch Cleanup Sweep (dry-run) ===
Cutoff: merged>30d, last-commit>60d, worktree-mtime>60d

[REMOTE] 12 후보 (머지된 PR head, 30d+):
  fix/foo-#123 (PR #123, merged 2026-04-01)
  feat/bar-#234 (PR #234, merged 2026-03-20)
  ...

[LOCAL] 27 후보 (gone tracking, 60d+):
  fix/baz-#345 (last 2026-02-15, gone)
  ...

[WORKTREES] 8 후보 (mtime 60d+):
  /home/mobruji/mobruji/.claude/worktrees/agent-abc123 (last 2026-02-10)
  ...

[PROTECTED] 다음 항목은 자동 제외:
  main, develop, docs/spec-adr-bundle-plan-1109 (current plan worktree), ...

Total to delete (with --apply): 47 branches + 8 worktrees
Estimated disk reclaim: ~340MB

Hint: re-run with --apply to actually delete.
```

### 5-4) Cron 정책

- **주 1회 dry-run + Discord push** (자동, scheduled GHA 또는 nmae cron):
  - 매주 월요일 09:00 KST `sweep.sh --dry-run --verbose` 실행 후 결과를 #모부르지-digest 채널 push.
  - 사용자 / nmae 가 결과 검토 후 manual `--apply` 결정.
- **자동 --apply 금지** (현 spec): 운영 안전 시점 도달 후 별도 spec 으로 활성화 검토. 현재는 manual 만.
- **수동 호출 가능 시점**: nmae 가 사이클 idle 시점에 1회 호출 권고 — nmae 자율 default + verify-and-iterate 룰 일관.

### 5-5) 데이터 흐름 / 시퀀스

```text
[주 1회 cron]
        │
        ▼
sweep.sh --dry-run --verbose
        │
        ├─ grep 후보 (remote/local/worktrees)
        ├─ allowlist filter
        └─ 결과 출력 + Discord push (#모부르지-digest)
        │
        ▼
[사용자 / nmae 검토]
        │
        ▼
sweep.sh --apply   ← manual
        │
        ├─ remote: `git push origin --delete` × N
        ├─ local: `git branch -D` × N
        └─ worktrees: `git worktree remove --force` × N
        │
        ▼
journalctl / discord-reply.sh "sweep --apply done, deleted N+M+W"
```

## 6) 작업 분할 (예상 PR 리스트)

- [x] PR 1 (본 plan PR): docs/features/branch-cleanup-policy.md 작성.
- [ ] PR 2 (be sub-agent): `tools/branch-cleanup/sweep.sh` 구현 + unit test.
- [ ] PR 3 (infra, nmae 직접): scheduled GHA workflow `.github/workflows/branch-cleanup-weekly.yml` 신설 + Discord push integration (보호 영역 라벨).
- [ ] PR 4 (docs, plan follow-up): 운영 1 개월 검증 후 자동 --apply 허용 검토 spec 갱신 (Out of Scope 해제 여부).

## 7) 테스트 전략

### unit test (sweep.sh 자체)

- **보호 branch 미포함 검증**: dry-run 후보에 main / develop / 현재 4 sub-agent worktree branch 가 없음을 assert.
- **cutoff 적용 검증**: 임의로 30d / 60d 경계 case 4개 (29d / 30d / 59d / 60d) 생성 후 cutoff 너머만 후보에 포함.
- **idempotency**: 같은 sweep 2회 실행 → 같은 후보 list.
- **--apply 안전**: dry-run 후 --apply 명시 안 한 호출은 실제 변경 0건.

### 통합 test (be sub-agent 가 PR 2 에서 작성)

- throwaway branch 5개 생성 후 sweep `--apply` 호출 → 5개 삭제 confirm.
- 보호 branch (`main`, `develop`) 가 의도적으로 cutoff 너머 (불가능하지만 mock) 있어도 삭제 안 됨을 assert.

### 회귀 방지

- 매 PR 머지 후 head branch 가 30일 후 sweep 후보에 들어가는지 1회 검증 (cron dry-run 결과 grep).

## 8) 오픈 질문

> 구현 전 답이 나와야 하는 항목.

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | cutoff 30d/60d 가 적절한가, 더 짧게 (예: 14d/30d) 또는 길게 (90d/180d) ? | 30d/60d / 14d/30d / 90d/180d | @nmae / PR 2 머지 전 |
| Q2 | `release/*` branch 가 실제로 사용 중인가, 보호 allowlist 에 포함할 필요가 있는가 ? | yes / no | @nmae / PR 2 머지 전 |
| Q3 | scheduled GHA workflow 의 시각 (월요일 09:00 KST) 이 적절한가 ? | 그대로 / 다른 요일·시각 | @nmae / PR 3 머지 전 |

§10 자율 결정 (사유) 에 각 question 의 plan sub-agent 자율 채택값 명시.

## 9) 결정 로그

- 2026-05-26 — 초안 작성 (plan sub-agent, 이슈 #1109). status=approved. 사용자 정정 (2026-05-26): "remote 444 + local 862 + worktrees 60+ 관리 불가능" — 본 spec 으로 cleanup 정책 명문화. 자동 --apply 는 현 spec 에서 금지 (운영 안전 시점 도달 후 별도 spec).

## 10) 자율 결정 (사유)

- 결정 1 (Q1): cutoff 30d/60d 채택.
  - 사유: 머지된 PR head 의 GitHub Restore branch 윈도우 (90d 까지 가능, 단 default 는 PR 머지 시 자동 삭제 옵션이 표준 관행) 와 PR 후속 follow-up 사이클 (보통 2-4 주) 의 충돌 회피. 60d local cutoff 는 reflog 보존 윈도우 (default 90d) 보다 짧아 복구 가능성 보장.
- 결정 2 (Q2): `release/*` 보호 allowlist 에 포함.
  - 사유: 현재 mobruji repo 가 release branch 패턴을 명시 사용 안 하지만, 추후 ADR-0023 (release cadence) 채택 시 도입 가능. 사전 보호로 미래 사고 차단.
- 결정 3 (Q3): scheduled GHA workflow 매주 월요일 09:00 KST 채택.
  - 사유: 주 시작 시점에 dry-run 결과 본 후 nmae 가 한 주 동안 manual --apply 결정 가능. 일요일 등 사용자 부재 시점 회피.

각 결정 follow-up 이슈로 사용자 재검토 가능 (현재 결정으로 PR 2-3 진행 가능).

## 11) 사용자 확인 필요

- 없음. plan sub-agent 자율 채택값으로 PR 2-3 진행 가능.

## 12) References

- 이슈 #1109 — 5 spec + 1 ADR + 1 sweep spec bundle (직전 PR f139504, plan 사이클).
- ADR-0023 — workflow / unit file main 미동기화 사고 박제 + sync 전략. 본 spec 의 `release/*` 보호 결정은 ADR-0023 의 release cadence 채택 시점에 효력 발생.
- `docs/ai-harness/02-agent-workflow.md §8` — release 절차 (tag 보존).
- `CLAUDE.md §4 비협상 룰` — main 직접 push 금지 + 보호 영역 라벨.
- `tools/cycle-status/` — nmae 사이클 status 헬퍼 (sweep 결과 push 와 통합 검토 가능).
- 메모리: [[feedback-worktree-lock]] / [[feedback-verify-and-iterate]] / [[feedback-autonomous-default]].

## 13) 변경 이력

- 2026-05-26 — 초안 작성 (plan sub-agent, 이슈 #1109). status=approved. cutoff 30d/60d + worktree mtime 60d + manual --apply only + 보호 allowlist 명문화. PR 2 (be 구현) / PR 3 (infra GHA) / PR 4 (docs follow-up) 분할.
