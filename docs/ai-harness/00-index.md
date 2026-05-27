# AI Harness Docs Index (mobruji)

## 1) 목적
- 이 문서 묶음은 `mobruji`에서 다중 에이전트(Maestro, Plan, BE, FE, Rev)가 자율적으로 협업하기 위한 **모듈형 프로토콜**이다.
- 에이전트의 역할에 따라 필요한 지식(Volume)만 주입하여 토큰 효율성과 정확도를 극대화한다.

## 2) 핵심 하네스 볼륨 (P0 - 모듈형 통합본)
- [**`00-MANIFEST.md`**](./00-MANIFEST.md): 에이전트 라우팅 및 작업 분류(Fast/Standard) 지도.
- [**`01-GOVERNANCE.md`**](./01-GOVERNANCE.md): 공통 운영 헌법, 보안 정책, PR/핸드오프 프로토콜.
- [**`02-ARCHITECT_NOTE.md`**](./02-ARCHITECT_NOTE.md): 도메인 모델, 패키지 구조, 설계 원칙 및 ADR 가이드.
- [**`03-ENGINEERING_STANDARDS.md`**](./03-ENGINEERING_STANDARDS.md): 코드 컨벤션, 테스트 전략, 품질 게이트, 관측성 표준.

## 3) 운영 및 참조 문서
- `docs/ai-harness/11-multi-session-runbook.md`: 다중 세션(be/fe/rev/plan) 셋업·운영 런북.
- `docs/ai-harness/13-memory-promote-tracking.md`: Claude 메모리 → 코드 promote 트래킹 매트릭스.
- `CLAUDE.md`: Claude Code 세션 자동 로드 룰 요약 (비협상 핵심 룰).

---
> ⚠️ **주의**: 아래의 `01~15` 개별 문서들은 위 통합 볼륨(00~03)의 원천 데이터이며, 향후 관리 효율을 위해 통합본으로 완전 대체될 예정이다. 작업 시에는 상단의 **통합 볼륨을 최우선**으로 참조한다.

## 3-1) 원천 문서 목록 (Archive 예정)
- `docs/ai-harness/01-harness-spec.md` / `02-agent-workflow.md`
- `docs/ai-harness/03-quality-gates.md` / `04-security-policy.md`
- `docs/ai-harness/06-domain-model.md` / `07-testing-guide.md`
- `docs/ai-harness/08-code-conventions.md` / `10-observability.md`
- `docs/ai-harness/12-sub-agent-prompt-template.md`
- `docs/ai-harness/14-discord-notify-setup.md` / `15-discord-message-templates.md`


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
