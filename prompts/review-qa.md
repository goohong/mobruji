---
purpose: PR 사후 감사 및 품질 검증 템플릿
scope: user|song|recommendation|voice|infra|web
owner: @goohong
last_reviewed: 2026-05-27
---

# Review & QA Template

## 1. 감사 대상
- **PR**: {PR URL 또는 번호}
- **변경 영역**: {diff의 주요 도메인}

## 2. 체크리스트 (비기능 매트릭스)
- **보안**: PII 노출, 시크릿 하드코딩 여부 grep (`01-GOVERNANCE.md §3`)
- **로그/관측성**: 구조화 로그 준수 및 PII 마스킹 확인 (`03-ENGINEERING_STANDARDS.md §4`)
- **DB**: 마이그레이션 안전성 및 쿼리 성능(N+1) 검토
- **테스트**: 변경 라인에 대한 테스트 커버리지 및 회귀 가드 확인

## 3. 결과 보고 (🔴/🟡/🟢)
- **결론**: 발견 사항 → 분석 → 종합 판정 (결론 단정 헤더 지양)
- **후속 액션**: 발견된 결함에 대한 이슈 등록 제안
