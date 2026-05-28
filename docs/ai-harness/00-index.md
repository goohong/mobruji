# AI Harness Docs Index (mobruji)

## 1) 목적
- 이 문서 묶음은 `mobruji`(노래방 추천 서비스)에서 AI 코딩 하네스를 안전하고 일관되게 운영하기 위한 기준이다.
- 1차 목표는 품질 안정성(우선) + 개발 속도(보조)이며, 객체지향 설계와 DDD 규칙 준수를 포함한다.

## 2) 적용 범위
- 적용: 이 레포(`mobruji`)에서 수행하는 AI 기반 코드/문서 작업 (backend + web 모노레포)
- 비적용: 타 레포 공통 정책, 조직 전사 정책

## 3) 문서 목록 (P0)
- `docs/ai-harness/00-MANIFEST.md`: **Vol 0 — 라우팅 지도** (16개 문서를 4 볼륨 그룹으로 묶은 view + 에이전트별 주입 맵 + 작업 트랙 + 보고 프로토콜). 문서 대체 아님, 네비게이션/주입 인덱스.
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
- `docs/ai-harness/12-sub-agent-prompt-template.md`: maestro이 sub-agent launch 시 참조하는 공통 룰 + 역할별 추가 룰
- `docs/ai-harness/13-memory-and-enforcement.md`: 메모리↔코드 책임 분리 ("왜"=메모리 / "어떻게"=코드: hook/wrapper/workflow/test) + 결정 트리 + 패턴 카탈로그 + promote 트래킹 (구 13+16 통합)
- `docs/ai-harness/14-discord-ops.md`: Discord 셋업(webhook/bot/토큰) + 메시지 템플릿 + 양방향 명령 syntax + Forum (구 14+15 통합)

> `09-notion-api-spec.md`는 추후 Notion API 명세 DB 연동 시 추가.

## 3-1) 관련 자산
- `prompts/`: 재사용 프롬프트 저장소 (운영 규칙은 `05-prompt-ops.md`)
  - `prompts/feature-implementation.md`: BE/FE/Plan 기능 구현 위임 템플릿
  - `prompts/review-qa.md`: Rev PR 사후 감사/QA 템플릿
- `docs/ai-harness/actors/`: **actor 전용 런북** (해당 actor 만 로드 — universal 노이즈 제거, 2026-05-28). `nmae-runbook.md` (구 CLAUDE.md §11). helper 는 `docs/helper-rules.md`, sub-agent 는 `12-sub-agent-prompt-template.md` 가 각각 자기 SoT. 라우팅 표는 `CLAUDE.md §0` / `00-MANIFEST.md`.
- `.github/PULL_REQUEST_TEMPLATE.md`, `.github/ISSUE_TEMPLATE/task.md`: PR/이슈 템플릿
- `docs/features/`: 기능 단위 living 명세서 (Feature Spec). 프로세스는 `02-agent-workflow.md §9`
- `docs/decisions/`: Architecture Decision Records (ADR). 횡단 결정의 영속 이력
  - `0001-tech-stack-and-monorepo.md` — Spring Boot + Next.js 모노레포
  - `0002-license-agpl-3-0.md` — AGPL-3.0 라이선스
  - `0003-test-db-strategy.md` — 테스트 DB 전략
  - `0004-frontend-state-and-fetching.md` — 프론트 상태 관리/페칭
  - `0005-package-structure.md` — 백엔드 패키지 구조 (+ `archive/0005-package-structure-migration.md` — 완료된 마이그레이션 가이드)
  - `0006-audio-source-youtube.md` — PoC 단계 audio 출처 = YouTube extract
  - `0007-vocal-difficulty-classification.md` — 곡 난이도 분류 (EASY/NORMAL/HARD)
  - `0008-archunit-layer-verification.md` — ArchUnit 계층 검증
  - `0009-schema-migration-tool.md` — 스키마 마이그레이션 도구
  - `0010-self-analysis-pipeline-stack.md` — 자체 분석 파이프라인 스택
  - `0011-session-bound-auth-policy.md` — 세션 기반 인증 정책
  - `0012-observability-stack.md` — 관측성 스택
  - `0013-sessionid-ttl-rotation.md` — sessionId TTL 회전
  - `0014-multi-agent-worktree-orchestration.md` — 다중 agent worktree 오케스트레이션
  - `0015-hosting-stack.md` — 호스팅 스택
  - `0016-maestro-context-percent-estimation.md` — maestro context 사용률 추정
  - `0017-spring-boot-eol-strategy.md` — Spring Boot 3.5 EOL 대응 (3.6 라인 채택, proposed)
  - `0018-design-tokens.md` — 디자인 토큰 (color/typography/spacing/radius/shadow/motion) — UI/UX 부활 4단계 (`docs/features/ui-ux-redesign.md`)
  - `0019-event-driven-architecture-v2.md` — 작업 체계 event-driven 아키텍처 v2 (agent 망각 의존 폐기, 동반 spec: `docs/features/event-action-mapping.md` + `docs/features/work-cycle-refactor.md`, PR #1077)
- `docs/milestones/`: 마일스톤별 roadmap (v0.2~). 가시화 목적이며 실제 사이클은 별도 launch
- `docs/runbooks/`: 운영 런북 (로컬 환경 가동 등)
  - `local-3tier-setup.md` — MySQL + Spring Boot + Next.js 로컬 3-tier 가동 가이드 (rev QA 기본 환경)
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
