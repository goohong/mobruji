---
purpose: 기능 구현 및 리팩토링 요청 템플릿
scope: user|song|recommendation|voice|infra|web
owner: @goohong
last_reviewed: 2026-05-27
---

# Feature Implementation Template

## 1. 개요
- **목표**: {이슈 요약}
- **참조 Spec**: {docs/features/slug.md}
- **브랜치**: {type/summary-#issue}

## 2. 작업 지침
- **핵심 로직**: {구현해야 할 핵심 기능 1~3가지}
- **테스트 요구사항**: {단위 테스트/E2E 필수 시나리오}
- **기술적 제약**: {BC 격리, 결정성 보장 등 특이사항}

## 3. 완료 기준 (Definition of Done)
- [ ] 관련 모든 테스트 통과 (BE: gradlew test / FE: npm test)
- [ ] 정적 분석 및 포맷 준수 (spotless / lint)
- [ ] PR 생성 및 올바른 라벨 부착
- [ ] 하네스 표준 양식에 따른 결과 보고 (🔴/🟡/🟢)

## 4. 주입 컨텍스트
- 공통: `docs/ai-harness/01-GOVERNANCE.md`, `03-ENGINEERING_STANDARDS.md`
- 도메인: `docs/ai-harness/02-ARCHITECT_NOTE.md`
