# CLAUDE.md (Harness Summary)

> 이 파일은 세션 시작 시 자동으로 로드되는 **핵심 포인터**입니다. 
> 상세 규정은 `docs/ai-harness/01~03` 볼륨을 참조하십시오.

## 1. 프로젝트 요약
노래방 곡 추천 서비스 (Spring Boot 3.5.3 + Next.js App Router).

## 2. 핵심 하네스 볼륨 (필독)
- **Vol 0: [MANIFEST](./docs/ai-harness/00-MANIFEST.md)** (에이전트 역할 및 라우팅 지도)
- **Vol 1: [GOVERNANCE](./docs/ai-harness/01-GOVERNANCE.md)** (보안, 브랜치, PR 프로토콜)
- **Vol 2: [ARCHITECT](./docs/ai-harness/02-ARCHITECT_NOTE.md)** (도메인, BC, 설계 원칙)
- **Vol 3: [STANDARDS](./docs/ai-harness/03-ENGINEERING_STANDARDS.md)** (컨벤션, 테스트, 품질 게이트)
- **Vol 12: [HELPER](./docs/ai-harness/12-helper-agent-runbook.md)** (NCP Helper 운영 런북)

## 3. 비협상 룰 (어기지 말 것)
- **브랜칭**: `develop`에서 `<type>/<summary>-#<issue-number>` 분기. `main` 직접 푸시 금지.
- **리스크 관리 (Rev 위임)**: CI/CD, DB, 설정 등 민감 영역 변경 시 **Rev 에이전트가 위험도를 판단**한다. Rev의 판단 하에 실질적 리스크가 낮을 경우 인간 개입 없이 자율적으로 진행할 수 있다.
- **품질 게이트**: 푸시 전 아래 명령 통과 필수.
    - BE: `./gradlew checkstyleMain spotlessCheck test`
    - FE: `npm run lint && npm run typecheck && npm test`
- **보안**: 시크릿 하드코딩 및 사용자 원문 데이터 로그 노출 절대 금지.

## 4. 에이전트 체계 (NCP 전용)
- **nmae** (ncp maestro): `tmux attach -t mobruji`. sub-agent 오케스트레이션 및 작업 전담.
- **helper**: `tmux attach -t helper`. 사용자 응답 및 nmae 위임 전담. **4단계 패턴** 준수.
- **보고 마커**: 매 turn 응답 마지막 줄에 `===CTX:NN%===` 마커 emit 필수.
- **라우팅**: 신규/복잡 작업은 `Standard Track`, 단순 수정은 `Fast Track` (참조: `00-MANIFEST.md`).

---
> 모든 작업 전 관련 **Feature Spec**(`docs/features/*.md`)을 반드시 먼저 읽고, 충돌 시 스펙을 우선 갱신한 후 구현한다.
