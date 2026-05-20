# Coding Harness Spec

## 1) 목적

- AI가 생성/수정하는 결과를 예측 가능하게 만들고, 품질과 안전 기준을 일관되게 적용한다.

## 2) 작업 단위

- 기본 단위: 이슈 1건 = 브랜치 1개 = PR 1개
- 브랜치 이름 형식: `<type>/<요약>-#<이슈번호>` (예: `feature/voice-range-input-#12`)
- 파생 브랜치 타입: `feature`, `refactor`, `chore`, `fix` (base: `develop`)

## 3) 입력 계약 (AI 작업 요청 시)

- 문제 정의: 무엇을 왜 바꾸는지
- 완료 기준: 테스트/행동/성능 기준
- 제약: 아키텍처(DDD, OOP), 보안, 일정
- 변경 범위: 허용 파일/금지 파일
- **중간 규모 이상 기능(신규 도메인/외부 연동/다중 PR)은 `docs/features/<slug>.md` Feature Spec을 먼저 작성·합의한 뒤 구현 착수한다.** 상세:
  `docs/features/README.md`, `02-agent-workflow.md §9`

## 4) 출력 계약 (AI 결과물)

- 코드 변경 + 근거 + 영향 범위
- 테스트 계획(신규/수정) 또는 테스트 불가 사유
- 리스크와 롤백 포인트

## 5) 결정 규칙

- 불명확하면: 구현 전에 가정값을 명시하고 승인 요청
- 과도한 변경이면: 기능 단위로 PR 분할
- 도메인 규칙 충돌 시: DDD 경계/용어를 우선 정리 후 구현

## 6) AI 작업 보호 영역 (사람 검토 필수)

- 아래 경로를 AI가 변경한 PR은 사람이 1줄 이상 코멘트로 검토 의견을 남긴 뒤에만 머지한다.
- DB 마이그레이션: `**/db/migration/**`, `**/resources/db/**`
- 시크릿/환경 설정: `**/application*.yml`, `**/application*.properties`, `.env*`
- CI/CD 워크플로우: `.github/workflows/**`
- Backend 빌드 스크립트: `backend/build.gradle*`, `backend/settings.gradle*`, `backend/gradle/**`
- Web 빌드/설정: `web/next.config.*`, `web/package.json`, `web/*-lock.*`
- 인프라: `Dockerfile`, `docker-compose*.yml`
- 라이선스: `LICENSE`
- AI 단독으로 위 파일을 신규 생성/삭제하는 PR은 원칙적으로 분할하거나 사람이 직접 작성한다.

## 7) 비목표

- AI가 릴리즈 승인자가 되는 것
- 문서 없이 암묵 규칙으로 운영하는 것
