---
feature: rev sub-agent 직접 site API + 브라우저 e2e QA 확장
slug: rev-direct-qa-extension
status: draft
owner: @goohong
scope: infra
related_issues: [1109, 1283]
related_prs: []
last_reviewed: 2026-05-29
---

# rev sub-agent 직접 site API + 브라우저 e2e QA 확장

> **status: draft — evidence 미확정.** rev round 25 last_completed 에 "사용자 요청 1 (rev QA 확장) 진단 후보 A/B/C/D/E" 명시되어 있으나 (`~/.mobruji/cycle-status.json`), 후보 5종의 raw 텍스트가 워크트리 / mobruji 운영 디렉토리 / PR 코멘트 / 이슈 본문 (탐색 evidence: §9 결정 로그 2026-05-29 entry) 어디에도 박혀 있지 않습니다. **본 spec 의 §3 후보 매트릭스는 plan sub-agent 의 추론 기반 (origin = 이슈 #1283 본문 + #1109 본문 + 기존 `rev-qa-protocol.md §5-4-1` dev 서버 활용 가이드 외삽)** 이며, rev round 25 의 실제 후보와 차이가 있을 수 있습니다. 머지 전 rev sub-agent 와 추가 협의 의무.

## 1) 개요 (What / Why)

- 기존 `docs/features/rev-qa-protocol.md` (status: implementing) 는 **local 3-tier** 1차 검증 + **dev 서버 NCP** 외부 의존 검증을 명시. 사용자 요청 1 (rev round 25, 2026-05-29) 은 그보다 한 단계 더 — **rev sub-agent 가 사이트의 실제 API endpoint 를 직접 호출 + 브라우저 (Playwright 등) e2e 흐름을 직접 돌려 검증** 하도록 역할 확장.
- 배경: 이슈 #1283 본문 §"분리된 spec" 가 "rev sub-agent 가 직접 사이트 API + 브라우저 e2e QA 는 rev 역할 확장이라 scope 가 song 과 다름. 별 spec 분리 권고" 명시. 이슈 #1109 §"7 항목 매트릭스" #6 "rev QA hook 결함 — 추천 받기 400 회귀 + RestAssured E2E hook + DTO 검증 hook + production smoke" 가 같은 방향.
- 본 spec 은 그 확장 방향을 **단일 진실 spec** 으로 박는다. 기존 `rev-qa-protocol.md` 는 **수정하지 않고** 본 spec 이 §5-4-1 / §5-9 단계 1/2/3 보고 형식을 reference 로 인용한다 (룰 충돌 회피).

## 2) 사용자 시나리오

- **시나리오 1 (단계 1 PR 머지 전 — 머지 차단 게이트)**: rev sub-agent 가 머지 대기 PR 의 변경 endpoint 를 **dev 서버에 PR branch deploy 후** 직접 curl + DTO schema 정합 / 결정성 / p95 검증 → 🟢/🟡/🔴 코멘트.
- **시나리오 2 (단계 2 develop 머지 후 dev 환경)**: 머지 직후 dev 서버에 자동 deploy 된 commit 에 대해 rev 가 **Playwright headless e2e** 로 home → voice → recommendations → likes/history 사용자 흐름 실행 → `rev-post-merge-pass` 라벨.
- **시나리오 3 (단계 3 release 후 production)**: production 머지 후 rev 가 **production endpoint 에 read-only smoke** (헬스 / 추천 1건 / 결정성) → `rev-prod-pass` 라벨.

## 3) 요구사항

### 기능 요구사항

#### 3-1) rev round 25 진단 후보 매트릭스 (evidence 미확정 — 추론 기반)

| 후보 | 한 줄 설명 | 단계 | 도구 / 명령 후보 | 의존 | 위험 / 한계 |
|---|---|---|---|---|---|
| **A. dev 서버 direct curl + 응답 검증** | rev sub-agent 가 PR branch deploy 후 `http://101.79.20.94/api/...` 에 직접 curl + JSON schema / 결정성 / p95 검증. | 단계 1 / 2 | `curl` + `jq` + `gh pr view <N> --json statusCheckRollup` (deploy 대기) | dev 서버 가용 (Phase 4 ncp-dev-deployment 머지 완료) | dev 환경 1개 — 동시 PR 다수 deploy race. 가드 메커니즘 부재. |
| **B. Playwright headless 브라우저 e2e** | rev sub-agent 가 `/home → /voice → /recommendations → /history` 사용자 흐름을 Playwright headless 로 실행. console error / network fail / DOM assertion. | 단계 2 / 3 | `npx playwright test --reporter=line` + 시나리오 spec 파일 | PR #1200 (Playwright e2e infra impl) 머지 의존 | 1차 deploy 만 검증. PR branch 별 deploy 안 됨 (단계 1 미적용). |
| **C. production smoke (read-only)** | release 후 production endpoint 에 read-only smoke (헬스 / 추천 1건 / 결정성). production 사용자 데이터 변경 X. | 단계 3 | `curl https://<prod>/actuator/health/liveness` + 추천 read-only smoke | production 환경 구축 (deployment-infrastructure.md Hetzner CX22 머지 의존) | production write 금지 — read-only smoke 만. 인증 가드 endpoint 검증 한계. |
| **D. FE-BE contract / DTO schema 정합** | rev 가 PR diff 의 DTO record 변경을 OpenAPI / TypeScript type 과 자동 매칭. unmatch 시 🔴. | 단계 1 | (도구 미확정) springdoc OpenAPI export + `openapi-typescript` codegen 비교 / 또는 단순 jq schema 추출 | springdoc-openapi 또는 ApiDoc 생성 인프라 부재 — 신설 의존 | 도구 신설 = 별도 spec 의존. 단기 수동 grep fallback 가능. |
| **E. visual regression (Playwright screenshot diff)** | rev 가 Playwright screenshot + `pixelmatch` 또는 `playwright --update-snapshots` 로 시각 회귀 감지. ADR-0018 단계 4 design tokens swap 시리즈 회귀 가드. | 단계 2 / 3 | Playwright screenshot + diff threshold | PR #1200 머지 + visual regression CI ADR 신설 | screenshot baseline drift — 의도 변경 vs 회귀 구분 룰 필요 (rev 가 자율 판단 안 됨). |

**evidence 미확정 주의**: 위 A/B/C/D/E 는 rev round 25 의 실제 후보와 다를 수 있습니다. rev sub-agent 와 머지 전 추가 협의 (§8 Q1).

#### 3-2) 후보 평가 기준

각 후보를 다음 5 기준으로 평가 → 우선 채택 후보 결정:

| 기준 | 의미 | 가중 |
|---|---|---|
| **검증 신뢰도** | "사용자가 실제 보는 화면" 까지 도달하는가 (browser e2e > curl > unit) | 0.3 |
| **자동화 가능성** | rev sub-agent 가 prompt 없이 자율 실행 가능한가 | 0.25 |
| **인프라 의존도** | 신규 의존 (예: PR #1200, production 환경) 가 없는가 | 0.2 |
| **확장성** | PR 마다 추가 비용 없이 일반화 가능한가 | 0.15 |
| **회귀 catch rate** | 과거 사고 (예: PR #851 voice-range 404, PR #1255 추천 400) 를 catch 했을 것인가 | 0.1 |

#### 3-3) 선호 옵션 — 사이클별 채택 후보

(plan 자율 결정, 사유 §9 결정 로그):

| 사이클 | 후보 | 이유 |
|---|---|---|
| **즉시 (이번 사이클)** | A (dev 서버 direct curl + 응답 검증) | 의존 0 (Phase 4 머지 완료), rev sub-agent 가 이미 §5-4-1 dev curl 패턴 사용 중. 자동화 가능성 ↑. |
| **다음 사이클 (PR #1200 머지 후)** | B (Playwright headless e2e) | A 와 보완. PR branch deploy 인프라 부재로 단계 2/3 만 적용. |
| **후속 (production 머지 후)** | C (production smoke read-only) | 단계 3 e2e 의 코드 강제 — 현재 spec 부재. |
| **별 cycle (도구 신설 필요)** | D (DTO contract) | OpenAPI export 인프라 별 spec 분리 권고. |
| **별 ADR** | E (visual regression) | ADR-0018 단계 4 후속 — `visual-regression-ci-adr` 후보 (nmae handoff §"신규 백로그" plan: "visual regression CI ADR"). |

### 비기능 요구사항

- **자율성**: rev sub-agent 가 본 spec 외 추가 질문 없이 단계별 QA 수행 가능 — 후보별 도구 / 명령 / Pass 조건 명시.
- **wall-clock**: 단일 PR QA wall-clock 본 spec 확장 후 < 15 분 (rev-qa-protocol §3 비기능 "10 분" + dev deploy 대기 ~3 분).
- **race 가드**: dev 서버 동시 deploy race (후보 A) 는 lock 메커니즘 또는 PR-branch deploy 격리 spec 분리 권고 (별 ADR).
- **graceful 실패**: dev / production 환경 down 시 단계 N skip + `rev-skip-env-down:<단계>` 라벨 + DIGEST push (rev-qa-protocol §5-9 단계별 push 룰 reference).

## 4) 범위 / 비범위

### 포함

- rev round 25 진단 후보 A/B/C/D/E 의 매트릭스 정리 + 평가 기준 + 사이클별 채택 후보.
- 후보 A (dev curl) 의 단계 1/2 적용 절차 — `rev-qa-protocol.md §5-4-1` reference.
- 단계별 PR 분할 (§6).

### 제외 (Out of Scope)

- **rev-qa-protocol.md 본문 수정**: 본 spec 은 확장. 기존 spec 은 read-only reference.
- **PR branch deploy 인프라 신설**: dev 서버 동시 deploy race 해소는 별 spec (`dev-pr-branch-deploy-isolation` 후보).
- **Playwright e2e 시나리오 작성**: PR #1200 머지 후 별 cycle (fe sub-agent 위임).
- **DTO contract 도구 신설** (후보 D): OpenAPI export 인프라 = 별 spec 분리.
- **visual regression CI ADR** (후보 E): 별 ADR (`visual-regression-ci`) 신설 권고.
- **production 환경 구축**: deployment-infrastructure.md Hetzner CX22 spec 의존.

## 5) 설계

### 5-1) 도메인 모델

- rev sub-agent 역할 확장 (역할 자체는 `docs/ai-harness/actors/sub-agent.md §2-rev` SoT — 본 spec 은 도구 / 절차만 spec).
- 새 도메인 용어 없음 — `06-domain-model.md §4` 갱신 의무 없음.

### 5-2) API 엔드포인트

신규 endpoint 없음 — rev 가 호출하는 기존 endpoint 만 사용. dev 서버 endpoint 후보:

| Method | Path | 후보 적용 | Pass 조건 |
|---|---|---|---|
| GET | `/actuator/health/liveness` | A (단계 1/2), C (단계 3) | 200 + `{"status":"UP"}` |
| POST | `/api/v1/voice-ranges` | A (단계 1/2) | 201 + lowestNoteMidi/highestNoteMidi echo |
| POST | `/api/v1/recommendations` | A (단계 1/2), B (단계 2) | 201 + recommendations 배열 non-empty + 결정성 (2회 호출 동일 순서) |
| GET | `/api/v1/sessions/{sid}/likes` | A (단계 1/2) | X-Session-Id 헤더 누락 시 401 |

### 5-3) 외부 연동

- **dev 서버 (NCP, 후보 A/B)**: `http://101.79.20.94/` — Phase 4 머지 완료. `cd-dev.yml` 가 develop tip 자동 deploy.
- **production 서버 (후보 C)**: 미구축 — `deployment-infrastructure.md` Hetzner CX22 머지 의존.
- **Playwright (후보 B/E)**: PR #1200 머지 의존. `web/playwright.config.ts` 신설 PR.

### 5-4) 데이터 흐름 / 시퀀스

후보 A (단계 1 PR 머지 전) 흐름:

```
1. PR 생성 → CI green
2. rev sub-agent: gh pr checkout <N> → dev 서버에 PR branch deploy 신청 (수동 trigger 또는 cd-dev-pr.yml 가정 — PR branch deploy 인프라 = 별 spec)
3. rev sub-agent: curl http://101.79.20.94/api/v1/... → §5-2 Pass 조건 검증
4. rev sub-agent: PR 코멘트 "rev단계1: 🟢/🟡/🔴 ..." + reviewed:claude 라벨
5. nmae 또는 사용자 머지 트리거
```

후보 A 한계: PR branch deploy 인프라 (단계 2) 부재 — 단기 fallback = develop 머지 후 단계 2 만 적용.

### 5-5) DB 마이그레이션

- 없음. rev 는 read-only QA.

### 5-6) 프론트엔드 화면 (해당 시)

- 후보 B/E 만 FE 관여 (Playwright e2e + visual regression). PR #1200 후속 사이클.

## 6) 작업 분할 (예상 PR 리스트)

본 spec 머지 후 후속 PR 분할 (사이클별):

- [ ] **PR 1 (즉시, plan/rev)**: 본 spec 머지 + `docs/ai-harness/actors/sub-agent.md §2-rev` 에 후보 A 절차 reference (`§3-3` 채택 후보 표) — sub-agent SoT 1 줄 추가.
- [ ] **PR 2 (rev round 26+, 후보 A 실증)**: rev sub-agent 가 dev 서버 curl 절차 시범 적용 + `rev-qa-protocol.md §5-9` 보고 템플릿 호환 검증.
- [ ] **PR 3 (PR #1200 머지 후, 후보 B)**: rev Playwright headless e2e 시나리오 spec 신설 + 단계 2 자동화.
- [ ] **PR 4 (production 구축 후, 후보 C)**: production smoke 절차 spec — `rev-qa-protocol.md §5-9-3` 단계 3 보고 호환.
- [ ] **PR 5 (별 spec, 후보 D)**: `dto-contract-openapi-codegen` spec 분리 — DTO schema 정합 도구.
- [ ] **PR 6 (별 ADR, 후보 E)**: `visual-regression-ci-adr` ADR 신설.

### 보호 영역 변경 여부 (필수 명시)

- 보호 영역 변경 여부: ☐ 없음 — 본 spec 은 docs 만. 후속 PR (PR 3 / 4 / 5 / 6) 에서 `.github/workflows/cd-dev-pr.yml` (단계 1 PR branch deploy) 또는 `.github/workflows/visual-regression.yml` 신설 시 보호 영역 발생 가능. 정보성 신호 — rev 사이클이 가중도 정보로 활용.

## 7) 테스트 전략

- 본 spec 자체는 docs 만 — 테스트 X.
- 후속 PR 별 테스트 전략:
  - PR 2 (rev 후보 A 시범): 시범 1 PR 에 대한 dev curl 보고서 1건 (rev-qa-protocol §5-9 단계 1 / 2 보고 형식 호환).
  - PR 3 (Playwright e2e): PR #1200 의 Playwright infra 기반 시나리오 spec 단위 — 시나리오 spec → 실행 검증 별도 PR.
  - PR 4 (production smoke): production 환경 구축 PR 의 의존 — production endpoint smoke 1건.

## 8) 오픈 질문

> 구현 전에 답이 나와야 하는 것들. 해소되면 §9 결정 로그로 이동.

| # | 질문 | 선택지 | 담당 / 기한 |
|---|---|---|---|
| Q1 | rev round 25 진단 후보 A/B/C/D/E 의 실제 raw 텍스트는 무엇인가 (본 spec §3-1 추론 매트릭스가 일치하는가) | (a) rev sub-agent 다음 round 시 raw 텍스트 첨부 / (b) 본 추론 그대로 채택 (rev 가 확인) / (c) 본 spec 재작성 | @goohong / rev round 26 |
| Q2 | 후보 A (dev curl) 의 단계 1 PR branch deploy 인프라는 별 spec 분리하는가 | (a) 별 spec `dev-pr-branch-deploy-isolation` 분리 / (b) 본 spec 에 포함 / (c) 단계 1 적용 포기 (단계 2 부터만 적용) | @goohong / PR 2 머지 전 |
| Q3 | 후보 D (DTO contract) 의 도구는 springdoc-openapi + openapi-typescript codegen 인가 | (a) springdoc + openapi-typescript / (b) 단순 jq schema 추출 / (c) 별 도구 / (d) 후보 D 폐기 | rev sub-agent / 별 spec 분리 시 |
| Q4 | 후보 E (visual regression) 의 baseline drift (의도 변경 vs 회귀) 구분 룰 | (a) `playwright --update-snapshots` 명시 PR 만 baseline 갱신 / (b) rev 자율 판단 (위험) / (c) ADR 분리 | @goohong / ADR 신설 시 |

## 9) 결정 로그

- **2026-05-29**: 초안 작성 (status=draft). evidence 미확정 명시 — rev round 25 진단 후보 A/B/C/D/E 의 raw 텍스트가 워크트리 / mobruji 운영 디렉토리 / PR 코멘트 / 이슈 본문 어디에도 박혀 있지 않음 (탐색 evidence: `grep -rE "후보 A|진단 후보|rev QA 확장"` 결과 cycle-status.json title / nmae-handoff-latest.md 멘션만 hit). §3-1 매트릭스는 이슈 #1283 본문 §"분리된 spec" + 이슈 #1109 §"7 항목 매트릭스" #6 + `rev-qa-protocol.md §5-4-1` dev 서버 활용 가이드 외삽 — 추론 기반. 머지 전 rev sub-agent 와 추가 협의 의무 (§8 Q1).
- **2026-05-29**: 사이클별 채택 후보 선호 옵션 (§3-3) plan 자율 결정. 사유:
  - 즉시 채택 = A (dev curl) — 의존 0, 이미 rev 가 §5-4-1 패턴 사용 중. PR branch deploy 인프라 부재 한계는 §8 Q2 로 분리.
  - B (Playwright) 보류 — PR #1200 머지 의존. 단기 단계 2/3 만 적용 가능.
  - D (DTO contract) 별 spec 분리 — 도구 신설 비용 큰. 단기 수동 grep fallback 가능.
  - E (visual regression) 별 ADR 분리 — baseline drift 룰 미정 (§8 Q4).
