---
name: AI 작업 요청
about: AI 에이전트(Claude Code 등)에게 구조화된 입력 계약으로 작업을 맡길 때
title: ""
labels: ["task", "ai-generated"]
assignees: []
---

<!--
제목: 동사 원형 + 목적어, 마침표 없음
예) "음역대 진단 1차 알고리즘 구현"
-->

## 0) Feature Spec
<!-- 중간 규모 이상 기능은 spec 먼저. 관련 spec 경로 또는 "해당 없음". -->
- `docs/features/<slug>.md`

## 1) 목적 / Why
<!-- 왜 이 작업이 필요한가. 1~3줄. -->

## 2) 범위 (이번 세션에서 할 것)
- [ ] 

## 3) 비범위 (이번 세션에서 하지 않을 것)
<!-- 스코프 크립 방지. 명시적으로 제외할 것. -->
- 

## 4) 제약
### 허용 scope (PR 라벨)
<!-- user / song / recommendation / voice / infra / web / feedback 중 하나 -->
- `scope:*`

### 건드려도 되는 영역
- 

### 건드리면 안 되는 영역 (보호 영역 주의)
<!-- 01-harness-spec §6 기본 보호 영역 외에 추가로 제한할 파일 -->
- 

## 5) 완료 기준 (Definition of Done)
- [ ] 기능 요구사항 A
- [ ] 기능 요구사항 B
- [ ] (backend) `cd backend && ./gradlew checkstyleMain spotlessCheck test` 통과
- [ ] (web) `cd web && npm run lint && npm run typecheck` 통과
- [ ] PR 본문에 `AS-IS` / `TO-BE` 작성
- [ ] 라벨 부여 (`type:*`, `scope:*`, `ai-generated`)
- [ ] (해당 시) Feature Spec 상태/결정 로그 갱신

## 6) 참고
- 관련 이슈: #
- 관련 PR: #
- 도메인 문서: `docs/ai-harness/06-domain-model.md`
- 외부 링크:

## 7) 오픈 질문 (AI가 구현 전에 확인할 것)
<!-- 구현 진입 전 사용자에게 확인받아야 하는 항목. 없으면 "없음" -->
- 
