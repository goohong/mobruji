---
purpose: PR 사후 감사 및 런타임 QA 템플릿 (Rev sub-agent)
scope: user|song|recommendation|voice|infra|web
owner: @goohong
last_reviewed: 2026-05-28
---

# Review & QA Template

> nmae 가 Rev sub-agent(`mobruji-rev`, **파일 수정 금지 — PR 코멘트만**)를 launch 할 때 쓰는 템플릿. 매 사이클 첫 액션은 `tools/rev-queue/rev-queue.sh all`. 3단계 e2e SoT: `docs/features/rev-e2e-3-stages.md`.

## 1. 감사 대상
- **PR**: {PR URL 또는 번호}
- **변경 영역**: {diff 주요 도메인/scope}
- **단계**: {1 머지 전 / 2 develop 후 / 3 release 후 — e2e 불가 시 단계 1 no-op pass}

## 2. 체크리스트 (비기능 매트릭스)
- **보안**: PII/시크릿 하드코딩 grep (`04-security-policy.md`)
- **로그/관측성**: 구조화 로그 + PII 마스킹 (`10-observability.md`)
- **DB**: 마이그레이션 안전성 + N+1 쿼리
- **테스트**: 변경 라인 커버리지 + 회귀 가드 (엔티티 필드/Seed 변경 시 호환성 테스트)
- **계층/컨벤션**: DDD 계층 침범, `final`/null 검증 (`08-code-conventions.md`)
- **런타임 QA**: local 3-tier(`docs/runbooks/local-3tier-setup.md`)에서 실행 검증 — 런타임 에러/API 통합/비기능

## 3. 결과 보고 (🔴/🟡/🟢)
- **결론**: 발견 사항 → 분석 → 종합 판정 (단정형 헤더 지양 — `verify and iterate`)
- **머지 게이트**: 통과 시 `reviewed:claude` 라벨. 보호 영역 + 리스크 종합 판단 (Rev 자율 — `01-harness-spec`)
- **후속 액션**: 🔴 시급 = 같은 사이클 즉시 트리거 제안 / 🟡🟢 = 후속 이슈 등록 제안
