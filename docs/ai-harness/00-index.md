# AI Harness Docs Index (mobruji)

## 1) 목적
- 이 문서 묶음은 `mobruji`(노래방 추천 서비스)에서 AI 코딩 하네스를 안전하고 일관되게 운영하기 위한 기준이다.
- 1차 목표는 품질 안정성(우선) + 개발 속도(보조)이며, 객체지향 설계와 DDD 규칙 준수를 포함한다.

## 2) 적용 범위
- 적용: 이 레포(`mobruji`)에서 수행하는 AI 기반 코드/문서 작업 (backend + web 모노레포)
- 비적용: 타 레포 공통 정책, 조직 전사 정책

## 3) 문서 목록 (P0)
- `docs/ai-harness/01-harness-spec.md`: 하네스 실행 규격과 결정 규칙
- `docs/ai-harness/02-agent-workflow.md`: 브랜치/PR/커밋/핸드오프 워크플로우
- `docs/ai-harness/03-quality-gates.md`: 빌드/테스트 게이트와 실패 처리
- `docs/ai-harness/04-security-policy.md`: 민감정보/시크릿/금지행위 정책
- `docs/ai-harness/05-prompt-ops.md`: 프롬프트 버전관리/승인/롤백 운영
- `docs/ai-harness/06-domain-model.md`: 도메인 모델/유비쿼터스 랭귀지/ERD (기능 구현의 공통 참조 기준) — **현재 스켈레톤, 도메인 확정 시 채움**
- `docs/ai-harness/07-testing-guide.md`: 레이어별 테스트 전략 및 필수 기준
- `docs/ai-harness/08-code-conventions.md`: 코드 컨벤션 (final/어노테이션/DTO/엔티티/Lombok/null 검증 등)
- `docs/ai-harness/10-observability.md`: 관측성 (Actuator + Micrometer) — 현재 backend endpoint만 노출, 시각화는 추후
- `docs/ai-harness/11-multi-session-runbook.md`: 다중 세션(be/fe/rev) 셋업·운영 런북
- `docs/ai-harness/12-sub-agent-prompt-template.md`: 본진이 sub-agent launch 시 참조하는 공통 룰 + 역할별 추가 룰
- `docs/ai-harness/13-memory-promote-tracking.md`: Claude 메모리 → 코드 promote 트래킹 매트릭스

> `09-notion-api-spec.md`는 추후 Notion API 명세 DB 연동 시 추가.

## 3-1) 관련 자산
- `prompts/`: 재사용 프롬프트 저장소 (운영 규칙은 `05-prompt-ops.md`)
- `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/task.md`: PR/이슈 템플릿
- `docs/features/`: 기능 단위 living 명세서 (Feature Spec). 프로세스는 `02-agent-workflow.md §9`
- `docs/decisions/`: Architecture Decision Records (ADR). 횡단 결정의 영속 이력
  - `0001-tech-stack-and-monorepo.md` — Spring Boot + Next.js 모노레포
  - `0002-license-agpl-3-0.md` — AGPL-3.0 라이선스
  - `0003-test-db-strategy.md` — 테스트 DB 전략
  - `0004-frontend-state-and-fetching.md` — 프론트 상태 관리/페칭
  - `0005-package-structure.md` — 백엔드 패키지 구조 (+ `0005-package-structure-migration.md`)
  - `0006-audio-source-youtube.md` — PoC 단계 audio 출처 = YouTube extract
  - `0007-vocal-difficulty-classification.md` — 곡 난이도 분류 (EASY/NORMAL/HARD)
- `docs/milestones/`: 마일스톤별 roadmap (v0.2~). 가시화 목적이며 실제 사이클은 별도 launch
- `CLAUDE.md`: Claude Code 세션 자동 로드 룰 요약

## 4) 정책 우선순위
1. 법/규제 및 보안 정책 (특히 AGPL-3.0 라이선스 의무)
2. 이 문서 세트
3. 팀 합의(이슈/PR 코멘트)
4. 개인 선호

## 5) 운영 원칙
- 모든 변경은 PR로 수행한다. (`main` 직접 푸시 금지)
- 최소 1명 리뷰 승인 후 머지한다 (1인 개발 초기엔 self-review 허용, PR 본문에 명시).
- 머지는 기본적으로 Squash merge를 사용한다.
- 불명확한 사항은 이 문서를 먼저 업데이트하고 구현한다.
- 위 규칙들은 GitHub Branch Protection으로 기술적으로 강제하지 않으며, 팀 합의(사회적 규약)로 운영한다.

## 6) 문서 유지관리
- 문서 오너: 프로젝트 owner
- 갱신 트리거: 브랜치/PR 규칙 변경, 품질게이트 변경, 보안 사고/정책 변경, 도메인 모델 확정/변경
- 권장 점검 주기: 스프린트 1회 또는 도메인 큰 변경 시점
