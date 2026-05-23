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

## 2. 스코프
- 대상 PR: `type:fix` 또는 `type:feat` 라벨 + e2e 가능 (web/backend scope)
- 제외: `scope:infra` / `scope:docs` — e2e 불가능, 통과 처리 (`reviewed:claude` 라벨만)
- rev sub-agent 가 3단계 모두 수행

## 3. 기능 요구사항
### 3-1. PR 머지 전 (단계 1)
- rev 사이클 시작 시 `gh pr list --state open --label type:fix --label type:feat --search 'no:label:reviewed:claude'` 후보 발굴
- scope:web → Playwright headless 또는 NCP dev 직접 호출
- scope:backend → RestAssured E2E 또는 curl + 응답 검증
- 통과 → `reviewed:claude` 라벨 + PR 코멘트 `✅ rev e2e PR pass`
- 실패 → 후속 이슈 등록 + PR 코멘트 `❌ rev e2e PR fail: <원인>` + 머지 보류
- 머지 게이트: `reviewed:claude` 라벨 없는 PR 은 nmae 자율 머지 안 함

### 3-2. develop 머지 후 사후 검사 (단계 2)
- develop 머지 직후 NCP dev deploy 사이클 완료까지 대기 (~5분)
- rev 가 단계 1 시나리오 동일 재실행 — 실제 deploy 환경
- 통과 → PR 코멘트 `✅ rev e2e post-merge pass`
- 실패 → 즉시 revert 이슈 등록 + `regression:dev` 라벨 + Discord push

### 3-3. release 후 production 검증 (단계 3)
- release (develop → main) 머지 + production deploy 완료까지 대기
- rev 가 release 에 포함된 모든 type:fix/feat PR 에 대해 단계 1 시나리오 재실행
- 통과 → release 노트에 `✅ rev e2e production verified` 추가
- 실패 → hotfix 이슈 등록 + `regression:prod` 라벨 + 즉시 Discord push

## 4. 비기능 요구사항
- 단계별 timeout: 단계 1 = 10분, 단계 2 = 5분, 단계 3 = 10분
- 실패 시 재시도 1회 (transient 회피)
- 모든 단계 결과는 PR 코멘트 + cycle-status.json `rev.in_progress` 에 기록

## 5. 구현 계획
- rev sub-agent prompt template 갱신 (`docs/ai-harness/12-sub-agent-prompt-template.md`) — 별 PR (PR #875 머지 후)
- CLAUDE.md §4 품질 게이트에 "type:fix/feat PR 머지 전 rev 3단계 e2e" 한 줄 — 별 PR (PR #875 머지 후)
- rev e2e 시나리오 라이브러리 (`tools/rev-e2e/`) — 별 PR

## 6. 마이그레이션 / rollout
- phase 1: 단계 1 (PR 머지 전) 만 — 즉시
- phase 2: 단계 2 (사후) — 1 사이클 후
- phase 3: 단계 3 (release 후) — release v0.4.0 부터

## 7. 관련
- 이슈 #882, #879 (원인 PR #851)
- 메모리 [[feedback-rev-e2e-always]] [[feedback-rev-release-gate]] [[feedback-role-expansion]]
- 다음 plan 사이클에서 §5 구현 PR 시퀀스 작성
