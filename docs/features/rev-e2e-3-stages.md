---
name: rev-e2e-3-stages
status: draft
owners: rev
related-issues: ["#882", "#851"]
---

# rev 3단계 e2e 자율 QA

## 1. 배경 (Why)
PR #851 (voice-range 404 fix) 가 CI green 인 채 30분+ 머지 안 되고 방치 → 사용자 production 에서 여전히 404 (issue #879).
기존 rev 룰은 ADR audit / 도메인 audit 중심. 머지 전 PR 에 대한 자율 e2e 부재. release 전 `reviewed:claude` 라벨 게이트만 있고 (메모리 [[feedback-rev-release-gate]]), 머지 후 회귀 / production 검증 단계 부재.
사용자 (2026-05-23) 결정: "P0 관계없이 모든 type:fix/feat 작업에 대해 e2e 가능하면 rev 가 항상 검사. 사후 검사건 PR 검사건 모두, release 후에도 한 번 더."

## 2. 스코프 (2026-05-24 재확장)
- 대상 PR: **모든 type:* PR** (`type:feat` / `type:fix` / `type:docs` / `type:refactor` / `type:chore` / `type:style` / `type:test`)
- e2e 가능 판정:
  - **e2e 가능** (web/backend 코드 변경) → 단계 1/2/3 모두 실제 수행
  - **e2e 불가능** (docs/spec/refactor/chore 류 — 런타임 행동 변경 없음) → 각 단계에서 **no-op pass 판정**만 + `reviewed:claude` 라벨 부여
- rev sub-agent 가 3단계 모두 수행 (no-op pass PR 은 단계 1 만 — 단계 2/3 skip)
- **모든 PR 은 rev 라벨 없이는 nmae 자율 머지 금지**. rev = mandatory checkpoint.

**Why 확장:** 2026-05-24 사용자: "rev를 거쳐야 (뭐 rev가 간단변경이라 할게 없다 판정을 내린다하더라도 거쳐서) 사이클이 진행되는거야 알겠지?" — docs/refactor PR 도 rev 사이클을 거쳐 mandatory checkpoint 통과 후 머지.

## 3. 기능 요구사항
### 3-1. PR 머지 전 (단계 1)
- rev 사이클 시작 시 `gh pr list --state open --search 'no:label:reviewed:claude'` 후보 발굴 — **모든 type:* 포괄** (라벨 필터 제거)
- e2e 가능 판정 (코드 diff 검사):
  - `web/**` 또는 `backend/**` 코드 변경 포함 → **e2e 가능**
  - `docs/**` / `*.md` / `.github/**` / spec / ADR / `tools/` 만 → **e2e 불가능** (no-op pass 후보)
- **e2e 가능** PR:
  - scope:web → Playwright headless 또는 NCP dev 직접 호출
  - scope:backend → RestAssured E2E 또는 curl + 응답 검증
  - 통과 → `reviewed:claude` 라벨 + PR 코멘트 `✅ rev e2e PR pass`
  - 실패 → 후속 이슈 등록 + PR 코멘트 `❌ rev e2e PR fail: <원인>` + 머지 보류
- **e2e 불가능** PR (no-op pass 절차):
  - rev 가 diff 빠르게 audit (오타/형식/링크 깨짐 등 명백한 결함 없는지)
  - 통과 → `reviewed:claude` 라벨 + PR 코멘트 `📝 rev no-op pass — 변경 사소함, e2e 불요`
  - 결함 발견 → 후속 이슈 등록 + PR 코멘트 `❌ rev no-op audit fail: <원인>`
- 머지 게이트: **모든 PR** `reviewed:claude` 라벨 없으면 nmae 자율 머지 안 함

### 3-2. develop 머지 후 사후 검사 (단계 2)
- **e2e 가능 PR 만 해당** — no-op pass PR 은 skip
- 후보 발굴: `gh pr list --state merged --base develop --search 'merged:>1h ago -label:rev-post-merge-pass -label:type:release'`
- develop 머지 직후 NCP dev deploy 사이클 완료까지 대기 (~5분)
- rev 가 단계 1 시나리오 동일 재실행 — 실제 deploy 환경
- 통과 → PR 코멘트 `✅ rev e2e post-merge pass` + 라벨 `rev-post-merge-pass` (멱등성 표식)
- 실패 → 즉시 revert 이슈 등록 + `regression:dev` 라벨 + Discord push

### 3-3. release 후 production 검증 (단계 3)
- **e2e 가능 PR 만 해당** — no-op pass PR 은 skip
- release (develop → main) 머지 + production deploy 완료까지 대기
- rev 가 release 에 포함된 모든 e2e 가능 PR 에 대해 단계 1 시나리오 재실행
- 통과 → release 노트에 `✅ rev e2e production verified` 추가 + PR 라벨 `rev-prod-pass`
- 실패 → hotfix 이슈 등록 + `regression:prod` 라벨 + 즉시 Discord push (사용자 부재여도 자율 hotfix)

## 4. 비기능 요구사항
- 단계별 timeout: 단계 1 = 10분, 단계 2 = 5분, 단계 3 = 10분
- 실패 시 재시도 1회 (transient 회피)
- 모든 단계 결과는 PR 코멘트 + cycle-status.json `rev.in_progress` 에 기록

## 5. 구현 계획
- [x] rev sub-agent prompt template §E-2 (3단계 절차) 갱신 (`docs/ai-harness/12-sub-agent-prompt-template.md`) — **PR #945 (2026-05-24 완료)**
- [x] GitHub Actions `rev-gate.yml` check 신설 (라벨/코멘트 부재 시 머지 차단) — **PR #945 (2026-05-24 완료)**
- [x] rev 큐 스크립트 (`tools/rev-queue/`) + sub-agent prompt §E-3 (큐 discovery) — **PR #952 (2026-05-24 완료)**. 매 사이클 첫 액션으로 호출. 라벨 + 스크립트가 single source of truth
- [ ] CLAUDE.md §4 품질 게이트에 "모든 type:* PR 머지 전 rev 3단계 e2e" 한 줄 — 별 PR
- [ ] rev e2e 시나리오 라이브러리 (`tools/rev-e2e/`) — 별 PR
- [ ] `rev-gate.yml` 을 `required_status_checks` 로 GitHub 브랜치 보호 설정 등록 — 별 PR (사용자 admin 작업)

## 6. 마이그레이션 / rollout
- phase 1: 단계 1 (PR 머지 전) 만 — 즉시
- phase 2: 단계 2 (사후) — 1 사이클 후
- phase 3: 단계 3 (release 후) — release v0.4.0 부터

## 7. 관련
- 이슈 #882, #879 (원인 PR #851)
- 메모리 [[feedback-rev-e2e-always]] [[feedback-rev-release-gate]] [[feedback-role-expansion]]
- 다음 plan 사이클에서 §5 구현 PR 시퀀스 작성
