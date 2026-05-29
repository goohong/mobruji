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
5. **rev 자율 판단** — rev 가 baseline 갱신 PR 의 `## visual baseline update` 섹션 review (의도 명시 여부 검사) — 미명시 시 `🔴 시각 회귀 의심` 코멘트.

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
- 후속 spec: `docs/features/visual-regression-ci.md` (accepted 격상 시 작성)
