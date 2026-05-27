---
feature: ADR-0023 sub-옵션 A1/A2/A3 비교 분석 (release PR #1122 unblock)
slug: adr-0023-suboption-analysis
status: draft
owner: @mobruji-maestro
scope: infra
related_issues: [1109, 1121, 1122]
related_prs: []
last_reviewed: 2026-05-27
---

# ADR-0023 sub-옵션 A1/A2/A3 비교 분석 (release PR #1122 unblock)

## 1) 개요 (What / Why)

- ADR-0023 (`docs/decisions/0023-workflow-main-sync.md`) status=accepted, **옵션 A (정식 release PR cadence) 채택**. 그러나 채택 직후 release PR #1121 / #1122 머지 시도 중 **main↔develop 누적 fork** 사고가 발견됨.
- 본 spec 은 그 누적 fork 를 *retroactive* 해소하는 3 sub-옵션 (A1 force reset / A2 merge strategy / A3 cherry-pick) 의 trade-off 비교 + 권고 default + 자동화 script 제안 + PR 분담을 박제.
- 사용자 결정 입력을 받기 전 plan sub-agent 가 비교 spec 을 먼저 작성 (자율 default 룰) — 사용자 결정 후 status=approved + be sub-agent 가 자동화 script impl 또는 직접 머지로 진행.

### 사고 컨텍스트 (2026-05-26 14:51 KST)

- `git merge-base origin/develop origin/main` 빈 응답 / mergeable=UNKNOWN.
- 단순 squash merge / cherry-pick 으로 풀 수 없는 규모.
- ADR-0023 본문 §해결 sub-옵션 절에 sub-옵션 A1/A2/A3 가 이미 박제됐으나 그 명명/내용이 본 spec 의 A1/A2/A3 와 **차이가 있음** — §4 명명 정합성 절 참조.

### 실제 fork 규모 (2026-05-27 검증)

`/home/mobruji/mobruji-plan` 워크트리에서 origin/develop ^origin/main / origin/main ^origin/develop 으로 측정:

| 방향 | commit 수 | 의미 |
|---|---|---|
| develop-only (`git log origin/develop ^origin/main`) | **240** | develop 에 머지된 commit 중 main 미반영 |
| main-only (`git log origin/main ^origin/develop`) | **162** | main 에 있으나 develop 미반영 (대부분 historical squash merge commit 추정) |

**ADR-0023 의 "16+" 추정은 작성 시점 추정치** — 실제 fork 는 240 vs 162 의 양방향 분기. 본 spec 의 sub-옵션 평가는 실제 규모 기준.

## 2) 사용자 시나리오

- 시나리오 1 (사용자 결정자): mmae/사용자가 release PR #1122 머지 unblock 을 위해 본 spec 을 읽고 A1/A2/A3 중 1개 채택.
- 시나리오 2 (nmae 오케스트레이터): 사용자 결정 받자마자 본 spec status=approved 갱신 + be sub-agent 에 채택 옵션 impl 위임.
- 시나리오 3 (be sub-agent 구현자): 사용자가 A3 (cherry-pick) 채택 시 `tools/release-sync/cherry-pick-release-commits.sh` 자동화 script impl PR 작성.

## 3) 요구사항

### 기능 요구사항

- [ ] A1/A2/A3 각각의 절차/장점/단점/위험도 박제.
- [ ] 권고 default 1개 명시 + 사용자 결정 fallback 옵션 명시.
- [ ] 자동화 script 권고 (A3 채택 시 사용할 script 의 입력/출력/conflict resolve 정책).
- [ ] PR 분담 (plan / nmae / be) 명시.
- [ ] ADR-0023 본문 §해결 sub-옵션 절과의 **명명 정합성** 박제 (혼선 방지).

### 비기능 요구사항

- 본 spec 은 plan sub-agent 가 작성하는 비교/권고 문서이며 **구현 코드 포함 금지** (`CLAUDE.md §13-2` plan 역할 룰).
- ADR-0023 본문은 직접 수정하지 않음 (이미 status=accepted, 사고 박제는 별도 follow-up PR 의무).
- 사용자 wait state 금지 — 권고만 작성, 결정은 사용자가 별도 채널에서.

## 4) 범위 / 비범위 (중요)

### 포함

- A1/A2/A3 비교 표 + 절차 + 위험도.
- A3 채택 시 자동화 script 의 **요구사항/입력/출력 정의** (impl 은 be sub-agent 가 별도 PR).
- ADR-0023 §해결 sub-옵션 절의 기존 A1/A2/A3 명명과의 차이 박제.

### 제외 (Out of Scope)

- 본 spec 은 **결정** 자체를 내리지 않음 — 사용자 채택 결정은 nmae 가 별도 turn 에서 수신.
- 자동화 script 의 실제 코드/테스트 (be sub-agent 후속 PR).
- ADR-0023 본문 수정 (별도 follow-up PR — 사용자 채택 결정 후 §변경 이력에 결정 결과 추가).
- 옵션 1/2/3/4 (ADR-0023 본문의 forward cadence 정책) 재논의 — 이미 옵션 1 accepted.

## 5) 설계

### 5-1) 명명 정합성 (혼선 방지)

본 spec 의 sub-옵션 A1/A2/A3 와 ADR-0023 §Decision §해결 sub-옵션 절의 sub-옵션 A1/A2/A3 는 **명명 구조는 같으나 정의가 다름**:

| 라벨 | ADR-0023 본문 정의 | 본 spec 정의 (이번 task) |
|---|---|---|
| A1 | main HEAD 의 develop 미반영 commit 을 develop 으로 sync PR (forward sync) | main 을 develop 으로 force reset (**main 기준 develop 채택, 반대 방향**) |
| A2 | release PR 머지 시 `-X theirs` / `-X ours` merge strategy | develop → main merge with `-X ours` (또는 `-X theirs`) |
| A3 | 16+ commit 개별 cherry-pick (main → develop 방향) | develop → main 방향 cherry-pick (또는 양방향 일관) |

**차이 원인**: ADR-0023 본문은 "main 에 있는 develop 미반영 commit 을 develop 에 합치자" 방향 (= mergeable=yes 만들기), 본 spec 은 작업 지침에 따라 "develop 의 240 commit 을 main 으로 어떻게 옮기는가" 방향. 두 방향 모두 fork 해소의 sub-options 이지만 방향이 반대.

**해소 권고**: 사용자 결정 시점에 본 spec 본문의 명명 (= 작업 지침의 A1/A2/A3) 을 채택. ADR-0023 본문 follow-up PR 시 §해결 sub-옵션 절을 본 spec 으로 link (`See: docs/features/adr-0023-suboption-analysis.md`) 또는 본 spec 명명으로 통일.

### 5-2) sub-옵션 A1 — sync 먼저 (main 강제 reset develop)

#### 절차

```bash
# 모든 워크트리에서 작업 중지 + cycle-status idle 보장 (nmae 사전 작업)
git checkout main
git fetch origin
git reset --hard origin/develop
git push --force-with-lease origin main
# tag 별도 발행: git tag -a v0.4.0 -m "..." && git push origin v0.4.0
```

#### 장점

- **가장 단순** — 1회 작업으로 fork 완전 해소.
- 향후 release cadence 일관성 — main = develop superset.
- wall-clock 비용 가장 낮음 (수 분 내 종료).
- 자동화 script 불요 — git native command 만으로 가능.

#### 단점

- **main commit history 잃음** (162 main-only commit 사라짐 — 대부분 historical squash merge 인데, 그 commit 들의 author/timestamp/SHA 가 develop history 와 다를 수 있음).
- `--force-with-lease` 라도 force push 위험 (PR review history / commit link / CI artifact 의 SHA 기반 reference 가 깨질 수 있음).
- AGPL-3.0 라이선스 + audit trail 일관성 risk (다른 사용자/contributor 가 main SHA 를 fork/star/clone 한 경우 깨짐).
- **irreversible** — 사후 복구는 `reflog` 로 가능하나 push 후 다른 사용자가 fetch 했으면 복구 어려움.

#### 위험도

**높음** (irreversible + audit trail risk).

### 5-3) sub-옵션 A2 — -X ours merge (develop → main)

#### 절차

```bash
git checkout main
git fetch origin
git pull origin main
git merge origin/develop -X ours   # main 쪽 채택 (또는 -X theirs 로 develop 쪽 채택)
# conflict 자동 해소 후 검증
git diff origin/develop main -- <중요 파일들>  # diff 검증
git push origin main
# tag 별도 발행
```

#### 장점

- main commit 보존 — 162 main-only commit 의 SHA / history 유지.
- force push 없음 — 외부 fork/clone 에 영향 없음.
- audit trail 일관 — merge commit 으로 명확한 fork 해소 시점 박제.

#### 단점

- **conflict 자동 해소 부작용** — `-X ours` 면 develop 의 변경이 main 쪽 코드로 덮어쓰일 위험. `-X theirs` 면 main 의 162 commit 의 변경이 develop 으로 덮어쓰임. 어느 쪽이든 한쪽 history 의 의도가 silent 하게 사라짐.
- A1 보다 복잡 — 머지 후 detailed diff 검증 필요 (어떤 파일이 어느 쪽으로 결정됐는지).
- merge commit 1개에 240 + 162 = 402 commit 의 변경이 압축됨 — review 어려움.
- **bisect/blame 신뢰도 ↓** — 단일 merge commit 이 다수 변경의 source 가 되어 향후 회귀 디버깅 부담.

#### 위험도

**중간** (force push 없음 + 그러나 silent override risk).

### 5-4) sub-옵션 A3 — cherry-pick 240 commits (develop → main)

#### 절차

```bash
git checkout main
git fetch origin
git pull origin main
# develop-only commits 식별
git log --oneline origin/develop ^origin/main > /tmp/develop-only-commits.txt
# 역순 (oldest first) 으로 cherry-pick
tac /tmp/develop-only-commits.txt | awk '{print $1}' | while read commit; do
  git cherry-pick "$commit" || {
    # conflict 시 자동 또는 manual resolve
    git cherry-pick --skip   # 또는 git mergetool / manual fix + git cherry-pick --continue
  }
done
git push origin main
```

#### 장점

- **정확한 history 통제** — 각 commit 의 author / message / SHA-tree 가 main 에 보존 (단 cherry-pick SHA 는 새로 생성).
- 작업 가시성 — 각 commit 별 진행률 / 실패 commit 식별 가능.
- 위험도 낮음 — 실패 시 cherry-pick 중단하고 이전 상태 복원 가능.
- conflict 검증이 commit 단위로 분리 — review 부담 분산.
- main-only 162 commit 도 보존 (별도 작업 없음).

#### 단점

- **240 commit 작업 부담** — wall-clock 수 시간 ~ 1일.
- conflict 다발 가능 — 같은 파일을 다수 commit 이 수정한 경우 conflict resolve 가 반복.
- merge commit (squash merge 의 결과) 을 cherry-pick 하면 sub-commit 정보가 사라짐 (squash 이미 됐으므로 영향 적음).
- 자동화 script 필수 — manual 240회 cherry-pick 은 비현실적.
- conflict resolve 정책 (`-X ours` / `-X theirs` / manual) 결정 필요 — 정책별 risk 가 A2 와 유사하게 발생.

#### 위험도

**낮음** (each commit 독립 검증 가능 + 중단 가능 + force push 불요).

### 5-5) 옵션별 비교 표

| sub-옵션 | wall-clock | history 보존 | force push | 자동화 필수 | bisect/blame | 위험도 |
|---|---|---|---|---|---|---|
| A1 force reset | < 30분 | main 162 commit 잃음 | yes | no | develop 일관 | **높음** (irreversible) |
| A2 -X ours/theirs merge | ~1h | merge commit 압축 | no | no | 단일 merge commit → 신뢰도 ↓ | 중간 (silent override) |
| A3 cherry-pick 240건 | 수 시간 ~ 1일 | 양쪽 commit 보존 (cherry-pick SHA 신규) | no | **yes** (script 필요) | 각 commit 독립 → 신뢰도 ↑ | **낮음** |

### 5-6) 권고 default

**A3 cherry-pick — 안전 + 가시성 + 양쪽 history 보존**.

- 다만 240 commit 자동화 script 필요. impl 은 be sub-agent 후속 PR 에 위임.
- conflict resolve 정책은 script 의 옵션으로 `--strategy=ours` / `--strategy=theirs` / `--strategy=manual` 3종 지원 권고 (사용자 또는 nmae 가 script 실행 시점에 결정).
- 실패 commit (resolve 불가) 은 별도 list 로 출력 + manual 후속 처리.

#### 대안

- **A1 force reset** — 사용자가 release 일관성 + wall-clock 최소화를 audit trail 보다 우선시할 경우. 단 **사용자 명시 결정 필수** (irreversible, nmae 자율 결정 금지).
- **A2 merge strategy** — A1 의 force push risk 회피 + A3 의 wall-clock 부담 회피의 절충안. 단 silent override 검증 비용은 발생.

### 5-7) 자동화 script 권고 (A3 채택 시)

#### 파일 위치 / 진입점

- `tools/release-sync/cherry-pick-release-commits.sh` 신설 (be sub-agent 후속 impl PR).

#### 입력 (CLI args)

| arg | 의미 | default |
|---|---|---|
| `--source` | cherry-pick 시작 branch (대상은 currently checked out branch) | `origin/develop` |
| `--target` | cherry-pick 대상 branch | `main` |
| `--strategy` | conflict resolve 정책 (`ours` / `theirs` / `manual` / `auto-skip`) | `manual` |
| `--dry-run` | 실제 cherry-pick 안 함, list 만 출력 | off |
| `--limit` | 최대 cherry-pick commit 수 (테스트용) | unlimited |
| `--continue` | 중단 후 재개 (이미 진행한 commit 은 skip) | off |

#### 출력

- stdout: 각 commit cherry-pick 결과 (success / conflict / skip).
- `/tmp/cherry-pick-failed-commits.txt` — resolve 실패 commit list (manual 후속 처리용).
- `/tmp/cherry-pick-progress.json` — `{processed: N, total: M, failed: [...]}` 형태 진행률 (재개 시 reference).

#### conflict resolve 정책

- `manual`: conflict 시 script 중단, 사용자가 resolve 후 `--continue` 로 재개.
- `ours`: 항상 target branch (main) 쪽 채택 — silent override risk.
- `theirs`: 항상 source branch (develop) 쪽 채택 — silent override risk.
- `auto-skip`: conflict commit 은 skip + failed list 에 기록 + 다음 commit 진행.

#### 추가 검증

- script 실행 전 `git merge-base origin/main origin/develop` 검증 — base commit 가 비어있지 않은지 확인 (비어있으면 ERROR 종료).
- 실행 후 `git log --oneline target ^source` 으로 cherry-pick 누락 검증 — 0건이어야 정상.

#### 테스트 전략

- be sub-agent 가 작은 fork (수동 fixture branch 2개, 3-5 commit) 로 unit-level 검증.
- 실제 240 commit 적용 전 `--dry-run` 으로 list 만 확인.
- `--limit=5` 로 일부만 cherry-pick → mergeable 검증 → push → 다시 `--limit=N+5` 반복 (점진 적용).

## 6) 작업 분할 (예상 PR 리스트)

| PR | 담당 | 내용 | 상태 |
|---|---|---|---|
| 1 | plan (이번 PR) | 본 spec 작성 (A1/A2/A3 비교 + 권고 default + script 요구사항) | 진행 |
| 2 | nmae | 사용자 결정 수신 (A1/A2/A3 중 1개) + 본 spec status=approved 갱신 + ADR-0023 §변경 이력 결과 추가 | 대기 (사용자 결정 후) |
| 3a | be (A3 선택 시) | `tools/release-sync/cherry-pick-release-commits.sh` impl + 단위 테스트 + dry-run 검증 PR | 대기 |
| 3b | be (A3 선택 시) | 실제 240 commit cherry-pick 실행 + main push + release PR #1122 mergeable 재계산 | 대기 |
| 3c | be (A1 또는 A2 선택 시) | 직접 머지 (사전 backup tag + force-with-lease 또는 -X ours merge) + 검증 | 대기 |
| 4 | plan (후속) | ADR-0023 §변경 이력에 채택 sub-옵션 + 사유 + 본 spec link 추가 follow-up PR | 대기 |

## 7) 테스트 전략

### plan PR (본 PR)

- 단위 테스트 해당 없음 (spec 문서).
- `docs/features/_template.md` frontmatter 룰 준수 검증 — `tools/spec-frontmatter/validate.sh` (있다면) 또는 manual review.
- `docs/features/README.md` 등재 갱신 (필요 시).

### be impl PR (A3 채택 시)

- `tools/release-sync/cherry-pick-release-commits.sh` 단위 테스트:
  - fixture branch 2개로 정상 case 검증 (--dry-run / --limit / --continue / 각 strategy).
  - conflict case 검증 (`auto-skip` / `manual` 두 strategy).
  - 진행률 JSON 출력 schema 검증.
- 실제 240 commit 적용 전 `--dry-run` + `--limit=5` 점진 적용.

### 사용자 검증 (모든 sub-옵션 공통)

- `git log --oneline origin/main ^origin/develop` = 0건 (또는 의도된 main-only commit 만 남음).
- `git log --oneline origin/develop ^origin/main` = 0건 (develop 의 240 commit 이 모두 main 도달).
- release PR #1122 mergeable=true 재계산.
- CI green (main 의 모든 workflow 가 develop 의 240 commit 변경 적용 후 통과).

## 8) 오픈 질문

| # | 질문 | 선택지 | 담당/기한 |
|---|---|---|---|
| Q1 | A1/A2/A3 중 어느 sub-옵션 채택 | A1 force reset / A2 -X ours merge / A3 cherry-pick (권고) | @mobruji-maestro / ASAP |
| Q2 | A3 채택 시 conflict resolve 정책 default | manual (사용자 개입) / auto-skip (script 자동 skip) / ours/theirs (silent override) | @mobruji-maestro / A3 채택 후 |
| Q3 | main-only 162 commit 중 develop 에 의도적으로 안 들어간 commit 이 있는가 | yes (선별 보존 필요) / no (모두 historical squash, develop 미반영 OK) | @mobruji-maestro / 검증 시점 |
| Q4 | release PR #1122 에 본 sub-옵션 작업 결과를 어떻게 반영 | (a) 본 sub-옵션 작업 후 #1122 의 mergeable 재계산 → 그대로 머지 / (b) #1122 close 후 새 release PR 생성 | nmae / sub-옵션 실행 후 |
| Q5 | 본 spec 의 A1/A2/A3 명명과 ADR-0023 본문의 A1/A2/A3 명명 차이를 어떻게 해소 | (a) ADR-0023 본문을 본 spec 명명으로 update / (b) 본 spec 을 ADR-0023 명명으로 align / (c) 두 명명 모두 유지 + 본 spec 에 정합성 표만 박제 (현 상태) | plan / 사용자 결정 후 |

## 9) 결정 로그

- 2026-05-27: 초안 작성 (status=draft, plan sub-agent). A1/A2/A3 비교 + 권고 default (A3 cherry-pick) + 자동화 script 요구사항 + PR 분담 박제. 실제 fork 규모 240 vs 162 검증. ADR-0023 본문과의 명명 차이 박제. 사용자 결정 대기.
