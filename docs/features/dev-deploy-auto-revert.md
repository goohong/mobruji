---
feature: dev 배포 E2E 회귀 자동 revert 배선
slug: dev-deploy-auto-revert
status: draft
owner: @goohong
scope: infra
related_issues: [1463]
related_prs: []
last_reviewed: 2026-06-02
---

# dev 배포 E2E 회귀 자동 revert 배선

> **위치**: `docs/features/stage2-dev-deploy-e2e.md` (단계 2 = dev 배포 E2E 검증) 의 **Q4 = 자동 revert** 결정(§9)의 실제 배선. 그 spec §4 가 "자동 revert 의 실제 구현은 별도 follow-up impl PR" 로 미룬 부분 = 본 spec(impl PR 4, 이슈 #1463).

## 1) 개요 (What / Why)

단계 2(dev 배포 E2E 검증)에서 rev sub-agent 가 dev 배포본 회귀를 발견하면 머지된 원본 PR 에 `regression:dev` 라벨을 부착한다. 이때 dev(사용자 dogfooding 환경)는 깨진 상태로 남는다. **자동 revert** = 그 회귀 커밋을 develop 에서 되돌리는 revert PR 을 자동 생성해, 기존 rev+nmae 머지 흐름을 거쳐 cd-dev 가 dev 를 정상 코드로 재배포하게 하는 것이다.

**핵심 결정 (사용자 2026-06-02)**:

1. **실행 주체 = GitHub Actions** (라벨 trigger workflow, CI 러너에서 `git revert`). bot.py 가 NCP develop-고정 작업장에서 브랜치 전환 시 배포 오염 위험([[project_bridge_deploy_decoupling]])이 있어, 격리된 CI 러너에서 수행한다.
2. **자동 생성까지만** — revert PR 을 자동 **생성**하고 원본 PR 에 cross-link 만 남긴다. **공유 develop 으로의 머지는 자동화하지 않는다** (기존 rev 단계 1 + nmae 머지 흐름 통과). 공유 브랜치 무인 머지 = high-stakes 라 회피.

이로써 rev 는 read-only 불변(라벨만 부착)을 유지하고, 롤백 실행은 자동화 계층(workflow)이 맡으며, 최종 머지 판단은 기존 게이트가 책임진다.

## 2) 사용자 시나리오

1. rev 가 단계 2 에서 회귀 발견 → 원본 PR(머지됨)에 `regression:dev` 라벨 부착.
2. `dev-regression-auto-revert.yml` 이 `pull_request_target: labeled` 로 trigger → 회귀 커밋(squash merge SHA) 을 `git revert` → revert 브랜치 push → revert PR 생성(base develop).
3. workflow 가 원본 PR 에 `🔴 dev 배포 E2E 회귀 → revert PR #M 생성` cross-link 코멘트 + `dev-revert-opened` 멱등 마커 라벨 부착.
4. revert PR 은 기존 rev 단계 1(빠른 통과) + nmae 머지 흐름을 거친다 → develop push → cd-dev 자동 재배포 → dev green 복구.

end-user 가시화 없음 (개발 사이클 내부).

## 3) 요구사항

### 기능 요구사항

- [ ] `regression:dev` 라벨이 **머지된** PR 에 부착될 때만 trigger (`if: label.name == 'regression:dev' && pull_request.merged == true`). 미머지/다른 라벨 = no-op.
- [ ] revert 대상 = `github.event.pull_request.merge_commit_sha` (squash merge = 단일 부모 커밋 → `git revert --no-edit <sha>`, `-m` 불요).
- [ ] revert 브랜치 `auto-revert/pr-<n>-dev-regression` push + revert PR `revert: <원본 제목> (dev 배포 E2E 회귀 #<n>)` 생성 (base develop).
- [ ] revert PR 라벨 = 원본 PR 의 `type:*`/`scope:*` 복사 + `ai-generated` + `ai:claude` + `dev-revert` 마커. (rev 단계 1 이 정상 분류하도록)
- [ ] 원본 PR 에 cross-link 코멘트 + `dev-revert-opened` 멱등 마커 라벨.
- [ ] **멱등성**: 원본 PR 에 `dev-revert-opened` 가 이미 있으면 전체 skip (라벨 재부착/workflow 재실행 시 중복 revert PR 방지).
- [ ] `git revert` 충돌 시: revert PR 생성하지 않고 원본 PR 에 `⚠️ 자동 revert 충돌 — 수동 revert 필요` 코멘트 + nmae 알림(라벨 `dev-revert-conflict`). 무인 강제 해결 금지.

### 비기능 요구사항

- **격리/보안**: `pull_request_target` 는 secret 접근 가능하므로, **PR head 코드를 절대 checkout·실행하지 않는다**. `develop` 만 checkout 하고 이미 머지된 커밋의 `git revert` + `gh` CLI 만 실행 (임의 PR 코드 실행 경로 없음).
- **권한**: `contents: write`(revert 브랜치 push) + `pull-requests: write`(PR 생성/코멘트/라벨).
- **동시성**: `concurrency: group: auto-revert-pr-<n>` 로 같은 PR 중복 실행 직렬화.
- **graceful**: secret/권한 부재, merge_commit_sha null(드묾) 등은 fail 대신 원본 PR 코멘트로 사유 남기고 종료.

## 4) 알려진 한계 / 결정

- **GITHUB_TOKEN 으로 만든 PR 은 다른 workflow 를 trigger 하지 않는다** (GitHub 의 의도된 재귀 방지). 즉 revert PR 에 `rev-gate.yml` / `auto-label.yml` 이 자동으로 안 돌 수 있다.
  - 대응: workflow 가 revert PR 생성 시 **라벨을 명시 부착**(auto-label 의존 제거). rev 단계 1 은 nmae/rev 가 `gh pr list` 로 발굴하므로 workflow trigger 와 무관하게 검토된다.
  - rev-gate 가 required check 라 revert PR 에서 안 돌아 머지가 막히면: (a) `secrets.REVERT_PAT`(PAT) 이 설정돼 있으면 그 토큰으로 PR 을 만들어 정상 trigger / (b) PAT 미설정 시 nmae 가 revert PR 에 빈 커밋 push 로 check 재유발 또는 admin merge. **권장 = REVERT_PAT 설정** (§6 오픈 질문 Q1).
- **단순 SHA 재배포(cd-dev workflow_dispatch) 미채택 사유**: dev 만 옛 SHA 로 돌리면 develop HEAD 엔 깨진 커밋이 남아 다음 머지 때 도로 깨진다. revert 커밋이 develop HEAD 에 반영돼야 일관 (stage2 spec §9 Q4 근거).

## 5) 배선 / 구현

- **신설**: `.github/workflows/dev-regression-auto-revert.yml`
  - trigger: `on: pull_request_target: types: [labeled]`
  - job guard → 멱등 체크(github-script) → checkout develop → git revert → push → gh pr create → 원본 PR 코멘트/라벨
- **읽기만**: `cd-dev.yml`(revert 머지 후 자동 재배포), `rev-gate.yml`(revert PR 도 동일 게이트)
- **신규 라벨**: `dev-revert`(revert PR 표식) / `dev-revert-opened`(원본 PR 멱등 마커) / `dev-revert-conflict`(충돌 시) — repo 라벨 사전 생성 필요(§6 Q2).

## 6) 오픈 질문

| # | 질문 | 선택지 | 담당 |
|---|---|---|---|
| Q1 | revert PR 의 rev-gate 재트리거 방식 | (a) `REVERT_PAT` secret 설정해 PAT 로 PR 생성(정상 trigger, 권장) / (b) GITHUB_TOKEN + nmae 수동 nudge | @goohong / 배포 전 |
| Q2 | 신규 라벨 3종 사전 생성 | (a) 본 PR 의 setup step 에서 `gh label create` 멱등 보장 / (b) 수동 사전 생성 | @goohong |
| Q3 | revert PR 자동 머지 단계로 확장 여부 | (a) 현행 "자동 생성까지만" 유지 / (b) 추후 dev-revert 라벨 whitelist + auto-merge (별도 spec, high-stakes) | 추후 |

## 7) 테스트 / 검증

- workflow 는 단위 테스트 곤란 → **수동 sanity (배포 후)**: dev 에 의도적으로 깨지는 변경을 머지 → 단계 2 rev 가 `regression:dev` 부착 → revert PR 자동 생성 + 원본 PR cross-link 확인 → revert 머지 후 cd-dev green 복구 1회 확인 ([[feedback-verify-and-iterate]]).
- 멱등성 sanity: 같은 PR 에 `regression:dev` 재부착 시 두 번째 revert PR 안 생기는지(=`dev-revert-opened` 가드) 확인.
- 정적 검증: `actionlint` (가능 시) + YAML 파싱.

## 8) 관련

- **상위 spec**: `docs/features/stage2-dev-deploy-e2e.md` (§9 Q4=b 결정 / §6 impl PR 4)
- **단계 구조 SoT**: `docs/features/rev-e2e-2-stages.md` §3-2
- **배포**: `.github/workflows/cd-dev.yml` (revert 머지 후 재배포)
- **게이트**: `.github/workflows/rev-gate.yml` (revert PR 도 통과 의무)
- 메모리 [[project_bridge_deploy_decoupling]] [[feedback-verify-and-iterate]] [[feedback-evidence-based-root-cause]]
