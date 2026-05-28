---
purpose: 기능 구현 및 리팩터 위임 템플릿 (BE/FE/Plan sub-agent)
scope: user|song|recommendation|voice|infra|web
owner: @goohong
last_reviewed: 2026-05-28
---

# Feature Implementation Template

> nmae/helper 가 BE/FE/Plan sub-agent 를 `Agent` 도구로 launch 할 때 prompt 본문에 채워 쓰는 재사용 템플릿. 운영 규칙: `docs/ai-harness/05-prompt-ops.md`.

## 1. 개요
- **목표**: {이슈/백로그 한 줄 요약}
- **참조 Spec**: {docs/features/<slug>.md — 없으면 "Spec 불요(Fast Track)" 명시}
- **이슈/브랜치**: {#issue} / `<type>/<요약>-#<issue>` (`develop` 분기)
- **워크트리**: {`/home/mobruji/mobruji-be` | `-fe` | `-plan`}

## 2. 작업 지침
- **핵심 로직**: {구현할 기능 1~3가지}
- **테스트 요구사항**: {필수 단위/슬라이스/E2E 시나리오 — BE 신규 엔드포인트는 성공 E2E(RestAssured) 필수}
- **기술 제약**: {DDD 계층 격리, 결정성(Clock/SeedDeriver DI), BC ID-only 참조 등 — `06-domain-model` / `08-code-conventions`}

## 3. 완료 기준 (Definition of Done)
- [ ] 품질 게이트 통과 — BE: `cd backend && ./gradlew checkstyleMain spotlessCheck test` / FE: `cd web && npm run lint && npm run typecheck && npm test && npm run build`
- [ ] `gh pr create --base develop` (base=main 사고 가드) + 라벨 `type:*`/`scope:*`/`ai-generated`/`session:<backend|frontend|plan>`
- [ ] 보호 영역 변경 시 `needs-human-review` 라벨
- [ ] Spec 있는 기능: §3 기능 + 비기능 요구사항 한 줄씩 구현 확인 (하드코딩/stub "일단 넘어가기" 금지)
- [ ] 완료 보고: PR URL · mergeable · 게이트 결과 · 보호 영역 · 발견 사항(🔴/🟡/🟢) · 다음 사이클 후보

## 4. 주입 컨텍스트
- 공통: `docs/ai-harness/02-agent-workflow.md`(브랜치/PR), `03-quality-gates.md`, `04-security-policy.md`
- 컨벤션/테스트: `docs/ai-harness/08-code-conventions.md`, `07-testing-guide.md`
- 도메인: `docs/ai-harness/06-domain-model.md`
- sub-agent 공통/역할 룰: `docs/ai-harness/12-sub-agent-prompt-template.md`
