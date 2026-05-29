---
id: 0026
title: 다크모드·디자인 토큰 swap 회귀 가드 — Playwright visual regression CI 도입 (proposed)
status: proposed
date: 2026-05-29
deciders: [@goohong]
---

# 0026. 다크모드·디자인 토큰 swap 회귀 가드 — Playwright visual regression CI 도입 (proposed)

## Context

ADR-0018 (design tokens) 단계 4 swap 시리즈 (PR #1236 / fe in_progress directive 2026-05-29) 에서 color / typography / spacing 토큰을 일괄 치환하는 중 다크모드 회귀 (대비 부족 / 일부 컴포넌트 토큰 미적용) 감지 자동화가 부재한 상태가 지속 중. rev sub-agent 가 단계 1 (PR 머지 전) 에서 일반 e2e (Playwright) 는 돌리지만 **시각 회귀 (screenshot diff)** 는 매뉴얼 — `rev-direct-qa-extension.md §3 후보 E` + §8 Q4 에서 baseline drift 룰 (의도 변경 vs 회귀 구분) 가 미정으로 별 ADR 후보로 분리 백로그. nmae handoff (round 2 마지막) 의 신규 백로그 `visual regression CI ADR` 후보 일치.

## Decision

Playwright visual regression CI 를 검토 단계 (proposed) 로 도입. 구체 범위 / 도입 시점 / baseline 관리 룰은 본 ADR Decision 만으로 확정하지 않고 다음 사항을 후속 spec (`docs/features/visual-regression-ci.md`) + 단계 1 / 단계 2 / 단계 3 의 rev 3단계 e2e 와 동일 라이프사이클로 정한다:

1. **대상 페이지** — 다크모드 toggle 영향 받는 핵심 5-10 페이지 (`/` 홈 / 추천 결과 / 음역 입력 / 좋아요 / 북마크 등 ADR-0018 단계 4 swap 범위).
2. **baseline 관리** — 의도 변경 vs 회귀 구분 룰 (§8 Q4 후보 a/b/c). **proposed: 후보 (a)** — `playwright --update-snapshots` 명시 PR 만 baseline 갱신 + PR body `## visual baseline update` 섹션 의무.
3. **CI 실행 위치** — 단계 1 (`rev-gate.yml` 또는 별 workflow `visual-regression.yml`) + 단계 2 (dev 환경 nightly).
4. **drift threshold** — pixelmatch 기준 0.1% (proposed, fe sub-agent 사이클이 첫 baseline 확보 후 조정).
5. **rev 자율 판단** — rev 가 baseline 갱신 PR 의 `## visual baseline update` 섹션 review (의도 명시 여부 검사) — 미명시 시 `🔴 시각 회귀 의심` 코멘트. 상세 판단 표 (no change 🟢 / 의도 명시 🟢 / 섹션 부재 + diff > 0.1% 🔴) 는 `docs/features/visual-regression-ci.md §3-4` SoT — 본 ADR 머지 후 spec PR (#1311 후보) 머지 시점에 reference 활성화.

본 ADR 은 status `proposed` 로 박제. accepted 격상은 ADR-0018 단계 4 완료 후 fe sub-agent 가 visual-regression-ci.md spec draft + 첫 baseline PR 머지 시점.

## Consequences

### 긍정적
- 다크모드 swap 회귀 자동 감지 (rev 매뉴얼 부담 ↓).
- ADR-0018 후속 단계 (단계 5 typography swap 등) 도 동일 가드.
- baseline drift 룰 명시 = rev 가 자율 판단 안 되는 분기점 해소 (§8 Q4 closure).
- fe sub-agent 가 PR body 의 `## visual baseline update` 섹션으로 의도 명시 — rev review 표준화.

### 부정적
- Playwright screenshot CI runtime 증가 (단계 1 e2e 위에 추가 — 추정 +1~2분 per PR).
- baseline 파일 (`.png`) 가 git LFS 미사용 시 repo 비대화. LFS 전환 시 별 마이그레이션 ADR 필요할 수 있음.
- false-positive (font rendering OS 차이) — Playwright 의 `--ignore-default-args` / Docker 통일 필요.

## Alternatives (considered)

- (A) **percy / Chromatic SaaS** — 자체 호스팅 부담 ↓, 그러나 외부 SaaS 비용 (월 \$X) + 사용자 데이터 (UI screenshot) 외부 유출 risk. 보안 정책 `04-security-policy.md` 위반 가능. **선택 X.**
- (B) **rev 자율 매뉴얼 screenshot** — `rev-direct-qa-extension.md §3 후보 E` 단순화. 그러나 회귀 prevention 가 rev 의 학습 의존 (망각 가드 부재). ADR-0019 (event-driven 아키텍처 — agent 망각 의존 폐기) 정신과 충돌. **선택 X.**
- (C) **별 ADR 없이 spec 만** — `visual-regression-ci.md` spec 만 작성. 그러나 baseline drift 룰 = 횡단 결정 (모든 fe PR 영향) → ADR 단위 (`docs/decisions/README.md` "팀 전체 영향 컨벤션 결정" 룰). **선택 X.**

## References

- ADR-0018 (design tokens) — 단계 4 swap 시리즈 후속 회귀 가드 트리거
- ADR-0019 (event-driven 아키텍처) — agent 망각 의존 폐기 원칙
- `docs/features/rev-direct-qa-extension.md` §3 후보 E + §8 Q4 (baseline drift 룰 후보)
- PR #1236 (fe 단계 4 swap in_progress directive 2026-05-29)
- 후속 spec: `docs/features/visual-regression-ci.md` (PR #1310 머지 후 §3-4 rev 자율 판단 표 활성화 + §8 Q1-Q4 매트릭스)

## 8. 오픈 질문

> 본 ADR 의 status `proposed` → `accepted` 격상 전에 답이 나와야 하는 것들. 본 ADR 의 §8 Q1~Q4 는 후속 spec `docs/features/visual-regression-ci.md` §8 Q1~Q4 와 1:1 매핑 — spec 의 §8 행이 SoT (구체 후보 + 담당 + 기한). 본 ADR §8 은 격상 게이트 요약만 박는다.

### 매핑 표 (ADR §8 ↔ spec §8)

| ADR-0026 §8 | spec `visual-regression-ci.md` §8 | 횡단 결정 (ADR 격상 게이트) | 담당 / 기한 |
|---|---|---|---|
| **Q1 — baseline `.png` 저장 매체** (git 직접 commit vs LFS vs GH artifacts) | spec §8 Q1 | (a) git 직접 commit — 1차 도입 proposed (spec §3-2). 누적 100 MB 도달 시 별 마이그레이션 ADR 트리거. | @goohong / PR 3 (workflow + 첫 baseline) 머지 전 |
| **Q2 — drift threshold 적정값** (pixelmatch 0.1% vs SSIM 0.99 vs 운영 후 조정) | spec §8 Q2 | (a) pixelmatch 0.1% — proposed (spec §3-3). 첫 baseline 1 주 운영 결과 후 조정. SSIM 전환은 false-positive 5% 초과 시 별 ADR. | rev sub-agent / 첫 baseline 확보 1 주 후 |
| **Q3 — OS font rendering false-positive 가드** (Playwright Docker 통일 vs 시스템 font install vs `fontFamily` CSS 강제) | spec §8 Q3 | (a) Playwright Docker (`mcr.microsoft.com/playwright:v1.4x`) 통일 — proposed (spec §3-3). 로컬 / CI 환경 OS 차이 제거. | fe sub-agent / PR 3 |
| **Q4 — nightly (단계 2) diff 발견 시 사용자 알림 방식** (rev DIGEST vs Discord 사용자 reply vs GitHub issue 자동 생성) | spec §8 Q4 | (a) rev DIGEST push (`rev-qa-protocol §5-9-3` 단계 2 보고 형식) — proposed (spec §2 시나리오 3). 별 보강 PR 가능. | @goohong / PR 5 (nightly workflow) 머지 전 |

### 격상 트리거 (proposed → accepted)

본 ADR 의 status `proposed` → `accepted` 격상은 다음 두 조건이 모두 만족된 시점:

1. **spec 머지 + 첫 baseline 확보** — `docs/features/visual-regression-ci.md` 머지 (PR #1310) + 후속 PR 3 (`.github/workflows/visual-regression.yml` + 24 baseline `.png` commit) 머지.
2. **§8 Q1~Q4 결정 로그 박제** — 본 §8 Q1~Q4 의 proposed 답이 실제 운영 후 결정 (accepted) 으로 격상되어 본 ADR `## 9. 결정 로그` (격상 시 추가) 및 spec §9 결정 로그에 박힘.

### spec 우선순위 (충돌 시)

본 §8 매트릭스와 spec §8 의 동일 Q 행이 충돌 시 — **spec §8 우선** (spec 의 SoT 원칙, ADR 은 횡단 결정만 박제). ADR 갱신은 격상 시점에 spec → ADR 동기화 한 번에 처리. spec §8 Q5 (ADR §8 vs spec §8 우선순위) 가 본 §8 신설로 closure.

### 비고

- 본 §8 신설 PR (#1317 후속) = spec §8 Q5 closure 트리거. 본 PR 머지 후 spec §8 Q5 박스를 ✅ 처리 + 결정 로그 1 줄 추가 (별 PR plan).
- §8 Q1~Q4 의 proposed 답은 본 ADR §Decision 의 1~5 항목과 일관 — Decision 1 (대상 페이지) 은 spec §3-1 에서 확정됨 (본 §8 Q 에 없음, Decision 5 항목으로 closure).
