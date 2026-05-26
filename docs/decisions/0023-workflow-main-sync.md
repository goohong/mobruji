---
id: 0023
title: workflow / unit file main 미동기화 사고 박제 + sync 전략
status: accepted
date: 2026-05-26
deciders: [@mobruji-maestro]
related_issues: [1109, 1121, 1122]
related_prs: []
supersedes: []
superseded_by: []
---

# ADR-0023. workflow / unit file main 미동기화 사고 박제 + sync 전략

## Context

GitHub Actions 가 push / pull_request / schedule trigger 시 트리거되는 워크플로우 파일은 **해당 trigger 가 발생한 ref 가 자체 보유한 파일** 만 실행한다. develop 에 추가된 workflow 가 main branch 에 머지되지 않은 동안에는, **main branch 가 base 인 trigger (예: release PR, scheduled cron, default branch event)** 가 신규 workflow 를 인식하지 못한다.

2026-05-26 현재 main 미반영 워크플로우 4종 박제:
1. `auto-merge-on-rev-pass.yml` — rev 가 `reviewed:claude` 라벨 부착 시 자동 squash merge.
2. `stale-pr-watch.yml` — 24h 이상 머지 안 된 PR 감시 + Discord push.
3. `rev-stage2-post-merge.yml` — develop 머지 후 1h 대기 후 dev 환경 e2e 자동 트리거.
4. `#1095 등 helper writing marker / reaction hooks 의 일부 후속 workflow` — develop 에만 존재, main 도달 시점 미확정.

`mobruji-helper.service` (`docs/features/systemd-restart-always.md` 의 unit file) 도 동일한 main-sync 문제를 갖는다 — develop 에 unit file 변경만 가하면 NCP 호스트에 배포되는 정식 release 가 main 도달 후만 가능 (deploy pipeline 이 `main` ref 기준 운영).

사용자 2026-05-26 정정: "develop 에만 있는 workflow 가 main trigger 에서 동작 안 함 — release / cherry-pick 전략 결정 필요". `release: vX.Y.Z` 패턴 cadence 가 명시 안 됨 + workflow 만 main 으로 우선 동기화하는 우회 절차도 부재.

본 ADR 은 4개 옵션 trade-off 박제 + 결정은 nmae 가 사용자 확인 후 채택 (status=proposed). 결정 채택 시 본 ADR status=accepted + `docs/ai-harness/02-agent-workflow.md §8` 보강.

## Decision

### 사용자 결정 (2026-05-26 14:51 KST)

**옵션 A (정식 release PR `develop → main` cadence) 채택.** 본 ADR §Decision §후보 옵션 4종 의 옵션 1 (정기 release PR cadence) 과 동치.

사용자 정정: "단순 cherry-pick 으로 안 풀리는 규모. 정식 release PR 로 가자". 14:51 KST 결정 직후 nmae 가 #1121 release PR launch — 단 직후 PR #1122 머지 검증에서 **merge-base 빈 응답 + main 에 develop 미반영 commit 16+ 건 (Phase 4 인프라)** 사고가 발견됨. 본 ADR §Consequences §사고 박제 절에 상세 기록.

옵션 2 (cherry-pick) 는 단순 hotfix 비상 시만 fallback. 옵션 3 (workflow-only base=main) 은 옵션 1 cadence 의 운영 부담 검증 후 보강 도입 검토. 옵션 4 (GHA toggle) 은 GitHub 지원 시점 도달 시 재평가.

### 사고 박제 — release PR mergeable 실패 (2026-05-26)

#1121 release PR 머지 시도 중 사고 sequence:

1. **merge-base 빈 응답**: `git merge-base origin/develop origin/main` 반환 commit 이 release PR 생성 시점의 develop HEAD 와 동기화 불일치. CI mergeable=UNKNOWN 또는 conflict marker 다수.
2. **main 에 develop 미반영 commit 16+ 건 발견**: Phase 4 인프라 PR 들이 develop 머지 후 main 에 도달하지 않은 상태로 누적. 단순 squash merge 로 풀 수 없는 규모 (history fork 진행).
3. **단순 cherry-pick 으로 안 풀리는 규모**: 16+ commit 의 individual cherry-pick 은 (a) wall-clock 비용 (b) conflict 매 commit 검증 (c) 중간 ref state 비일관 risk 동반.

본 사고는 옵션 1 cadence 가 **장기 미실행** 시 누적되는 구조적 risk 의 instance. 본 ADR 채택 직후 그동안 누적된 main-develop fork 를 한 번 해소해야 한다 (be sub-agent 가 자율 판단 진행 중, §sub-옵션 A1-A3 참조).

### 해결 sub-옵션 (be sub-agent 자율 판단 — #1122 진행 중)

본 ADR §Decision 옵션 1 채택은 cadence 의 *forward* 정책. 누적된 fork 를 *retroactive* 해소하는 방법은 3 sub-옵션:

#### sub-옵션 A1: 모든 미반영 main commit 을 develop 으로 sync PR 먼저

- main HEAD 의 commit 중 develop 에 없는 16+ 건을 develop 으로 sync PR (`sync: main → develop pre-release`) 생성.
- 머지 후 develop HEAD 가 main HEAD 의 superset 이 되어 release PR mergeable=yes 재계산 가능.
- **장점**: history 일관. release PR 본문이 명확 (develop 의 changelog 만 포함). 다음 cadence 부터 fork 발생 시 즉시 같은 패턴.
- **단점**: 추가 PR 사이클 1회 (sync PR 자체). main 에만 있는 commit 의 author 정보가 develop history 에 두 번 표시 — 약간의 noise.

#### sub-옵션 A2: `-X theirs` 또는 `-X ours` strategy 로 release 직접 머지

- release PR 머지 시 git merge strategy option (`-X theirs` / `-X ours`) 으로 main 쪽 또는 develop 쪽을 일괄 채택.
- **장점**: 1 머지로 fork 해소. 빠름.
- **단점**: blunt — 어떤 commit 이 누락 / 덮어쓰기됐는지 추적 어려움. main 의 16+ commit 이 살아남은 건지 사라진 건지 검증 비용 ↑. AGPL-3.0 라이선스 + audit trail 일관성 risk.

#### sub-옵션 A3: 16+ 건 개별 cherry-pick

- main 에 있는 develop 누락 commit 각각을 develop 으로 cherry-pick (16+ PR).
- **장점**: 가장 precise — 각 commit 의 author / message / diff 보존. audit trail 명확.
- **단점**: wall-clock 비용 가장 큼. conflict 매 commit 마다 manual resolve 가능성. nmae 사이클 자율성 손상 우려.

**결정**: be sub-agent (#1122 진행 중) 자율 판단 — 16+ commit 의 nature (workflow only / code / docs) 분석 후 가장 안전한 sub-옵션 1개 채택. 본 ADR 의 plan PR follow-up 으로 결정 결과 §변경 이력에 추가 기록한다 (PR 본문의 `## 자율 결정 (사유)` 섹션).

### 후보 옵션 4종

#### 옵션 1: 정기 release PR (develop → main, 주 1회 cadence)

- 매주 월요일 (또는 합의된 요일) `release: YYYY-MM-DD` PR 생성. **Merge commit** 방식 (Squash 금지, `docs/ai-harness/02-agent-workflow.md §8` 일치).
- PR 본문 = 해당 주의 type 별 changelog 자동 생성 (`gh pr list --merged --base develop --search 'merged:>=<prev-release-date>'`).
- 머지 후 `git tag -a vX.Y.Z` + `gh release create vX.Y.Z --generate-notes` (CalVer or SemVer 결정 필요).

**Trade-off**:
- **장점**: cadence 명확. 사용자 / nmae / sub-agent 모두 같은 주기로 예측 가능. release note 자동화 가능. 다른 코드 변경도 main 으로 일관 sync.
- **단점**: workflow 만 빠르게 main 도달 필요한 경우 1주 wait. 긴급 fix workflow 도 cadence 에 묶임. cadence 자체가 학습 의존 (nmae 가 까먹으면 release loop 정체).
- **운영 부담**: ADR 별 cron / scheduled GHA workflow 1개 — `release-watchdog.yml` 같은 trigger 추가.

#### 옵션 2: workflow 만 cherry-pick to main (수동)

- 워크플로우 PR 머지 직후 nmae 가 `git checkout main && git cherry-pick <commit>` 으로 main 직접 commit + push.
- 다른 변경 (코드 / docs) 는 cherry-pick 안 함. workflow / unit file 같은 main-trigger-dependent 항목만.

**Trade-off**:
- **장점**: 즉시 main 도달. cadence 의존 X. 긴급 hotfix 가능.
- **단점**: `main` 직접 push 금지 룰 (`CLAUDE.md §4 비협상 룰`) 위반. main / develop history 분기. main 에 워크플로우만 있고 코드는 develop 이전 stale — confusing.
- **운영 부담**: cherry-pick 룰 학습 의존. 누락 시 다시 같은 사고 반복. 자동화 어려움 (선택적 cherry-pick).

#### 옵션 3: workflow PR 만 main 직접 base (workflow-only branch 정책)

- `.github/workflows/**` 변경 만 포함된 PR 은 `gh pr create --base main` 으로 main 직접 base. squash merge 후 develop 으로 back-merge.
- 다른 변경 (코드 / docs) 와 같은 PR 에 섞이면 거부 (workflow-only diff 룰).

**Trade-off**:
- **장점**: workflow 가 main 도달까지 평균 시간 ~30분 (PR + review + merge). 명확한 분기 룰.
- **단점**: workflow 와 code 가 같이 변경되어야 하는 경우 (예: 새 endpoint + 새 ci-check) PR 2개 분할 필요 + 머지 순서 디펜던시. main 에서 develop back-merge 자동화 필요 (`gh workflow run ...`). nmae 자율 머지에 새 룰 추가 부담.
- **운영 부담**: `.github/workflows/**` 만 변경 detect → `--base main` 강제하는 `pr-target-enforce.yml` workflow 신설. workflow 룰 학습 의존 ↓.

#### 옵션 4: GitHub Actions "Allow non-default branch workflows" toggle

- GitHub repo settings 에 "Allow workflows from non-default branches to run on default branch events" 기능 활성화. (현재 GitHub Actions 는 이 기능을 직접 지원 안 함 — 본 옵션은 가설.)
- workflow trigger 가 main event 여도 develop branch 의 workflow 파일을 실행하도록 정책 변경.

**Trade-off**:
- **장점**: sync 불요. develop 에 추가하면 즉시 모든 trigger 에서 효력.
- **단점**: **GitHub Actions native 지원 부재** — 현재 옵션 (`pull_request_target` 같은 우회) 는 보안 risk (secret 노출). non-default branch 의 trusted workflow 만 허용하는 정식 toggle 은 2026-05 기준 미공개.
- **운영 부담**: 지원 시점 미정. v1 채택 불가.

### 옵션별 비교 표

| 옵션 | sync latency | 룰 학습 의존 | main 직접 push | 자동화 가능 | 위험 |
|---|---|---|---|---|---|
| 1 release PR cadence | ≤ 1주 | 중 (cadence 까먹기) | X | yes (scheduled GHA) | release loop 정체 |
| 2 cherry-pick | < 1h | 고 (cherry-pick 까먹기) | yes (룰 위반) | 어려움 | history 분기 |
| 3 workflow-only base=main | ~30분 | 저 (workflow 자동 룰) | yes (workflow-only) | yes (pr-target-enforce.yml) | PR 분할 부담 |
| 4 GHA toggle | 즉시 | 저 | X | yes (native) | 미지원 |

### 권장 (accepted, 2026-05-26 14:51 사용자 결정)

**옵션 1 (release cadence) 채택**. 옵션 3 (workflow-only base=main) 은 cadence 운영 검증 후 추가 도입 검토 (보강 항목).

- **정상 cadence**: 옵션 1 release PR 주 1회 (구체 요일 / 시각은 PR 본문 cadence 절에 명시 — `docs/ai-harness/02-agent-workflow.md §8` 보강 follow-up).
- **긴급 workflow 변경**: 본 cadence 의 1주 wait 가 부담일 경우 옵션 3 도입 — 별도 PR 로 `pr-target-enforce.yml` 신설 + 정책 spec.
- **옵션 2 cherry-pick**: 비상 hotfix 만 허용 (cadence + 옵션 3 둘 다 실패한 비상시). main 직접 push 룰 위반이므로 nmae 가 사용자 사전 통보 의무.
- **옵션 4 (GHA toggle)**: GitHub 지원 시점 도달 시 재평가. 현 시점 v1 채택 불가.

본 ADR status=accepted 효력 발생. `docs/ai-harness/02-agent-workflow.md §8` 의 release 절차 보강 + cadence 정책 follow-up PR 의무.

## Consequences

### 긍정적 (proposed 권장 채택 시)
- workflow 가 main 도달 latency: 평균 ≤ 1주 (cadence) / 긴급 시 ≤ 30분 (옵션 3).
- main 직접 push 사고 차단 (옵션 3 의 workflow-only 정책 + `pr-target-enforce.yml`).
- release cadence 가 다른 main-sync 항목 (코드 / docs / unit file) 도 cover.
- nmae 자율 모드 운영 시 release PR 자동 생성 가능 (scheduled GHA).

### 부정적
- release cadence 1주 — 긴급 아닌 workflow 도 즉시 main 도달 불가.
- 옵션 3 workflow-only 정책 학습 의존 (nmae 가 mixed PR 만들면 enforce workflow 가 차단).
- `pr-target-enforce.yml` 자체가 main 도달해야 enforce — bootstrap 문제 (1회 cherry-pick 또는 첫 release 에 포함).
- back-merge (main → develop) 자동화 필요 — workflow-only PR 머지 후 develop 으로 즉시 동기화 안 하면 develop history diverge.

## Alternatives Considered

위 §Decision 의 옵션 1~4 외 추가 고려:

- **(D) workflow 를 repo 외부 reusable workflow 로 이전** — `@org/.github-workflows/...` 같은 별 repo 의 reusable workflow 호출. mobruji repo 의 main / develop 둘 다 reusable 호출만, 본문은 외부 repo 에서 관리. → 새 repo 운영 부담 + reusable workflow caller-callee 디버깅 복잡도. v1 기각.
- **(E) main branch 운영 폐기 + develop 만 사용** — main 자체를 deprecation 하고 release tag 는 develop 에서 발행. → AGPL-3.0 라이선스 + tag 추적 + GitHub default branch 관행 위배. v1 기각.

## References

- 이슈 #1109 — workflow 미반영 사고 박제 + sync 전략 결정.
- `docs/ai-harness/02-agent-workflow.md §8` — release 절차 (develop → main merge commit, tag, release note).
- `CLAUDE.md §4 비협상 룰` — main 직접 push 금지.
- `CLAUDE.md §8 릴리즈` — release PR + tag + GitHub release.
- `docs/features/systemd-restart-always.md` — 본 ADR 의 main-sync 정책에 동반 의존하는 unit file 변경 예.
- `docs/features/answer-first-verification-hook.md` / `channel-history-grep-wrapper.md` / `helper-target-freeze-enforced.md` / `discord-reply-length-split.md` — 본 ADR 결정 후 main 도달 절차 의존하는 다른 spec.
- 메모리: [[feedback-keep-promises]] / [[feedback-verify-and-iterate]] / [[feedback-session-persist-rules]].

## 변경 이력

- 2026-05-26 — proposed 작성 (plan sub-agent, 이슈 #1109). 4 옵션 trade-off 박제. 결정은 nmae 가 사용자 확인 후 채택. 채택 시 status=accepted + `docs/ai-harness/02-agent-workflow.md §8` 보강 + `pr-target-enforce.yml` workflow 신설 PR 별도.
- 2026-05-26 14:51 KST — 사용자 결정: 옵션 A (= 옵션 1, release PR cadence) 채택. status=proposed → accepted. 본 ADR §Decision 상단에 사용자 결정 절 + 사고 박제 절 + 해결 sub-옵션 (A1 / A2 / A3) 절 추가 (plan sub-agent 후속 갱신, 이슈 #1109 / #1121 / #1122).
  - 사고: merge-base 빈 응답 + main 에 develop 미반영 commit 16+ 건 (Phase 4 인프라). 단순 cherry-pick 으로 안 풀리는 규모 박제.
  - sub-옵션 A1-A3 trade-off 박제. 채택 결정은 be sub-agent #1122 자율 판단 — 결정 시 본 ADR follow-up PR 로 §변경 이력에 채택 sub-옵션 + 사유 추가 의무.
  - related_issues 갱신: [1109] → [1109, 1121, 1122].
  - follow-up PR 의무: (a) `docs/ai-harness/02-agent-workflow.md §8` 보강 (cadence 요일 / 시각 / 자동화 명시). (b) #1122 결정 결과 본 ADR 에 사후 기록.
