# AI Harness Volume 3: ENGINEERING STANDARDS

> BE, FE, Rev 에이전트가 고품질의 코드를 작성하고 검증하기 위해 준수해야 할 기술 표준입니다.

---

## 1. 코드 컨벤션 (Conventions)
### Backend (Java/Spring)
- **불변성**: 메서드 매개변수와 지역 변수에 `final` 키워드 사용 필수. DTO는 `record` 우선.
- **Lombok**: `@Getter`, `@RequiredArgsConstructor` 등은 적극 사용하되, `@Data`, `@Setter`(엔티티)는 금지.
- **Null 검증**: DTO 레이어(Bean Validation)와 Domain 레이어(requireNonNull) 두 곳에서 모두 검증한다.
- **메서드 네이밍**: 조회(`read/get/find`), 등록(`create`), 수정(`update`), 삭제(`delete`) 접두어 사용.

### Frontend (Next.js/TS)
- **컴포넌트**: 함수형 컴포넌트만 사용. Props는 interface/type으로 명시.
- **상태 관리**: 서버 상태는 React Query, 클라이언트 상태는 useState/Zustand.
- **로깅**: `console.*` 직접 사용 금지. `@/lib/logging`의 `safeLog` 래퍼를 통해 PII 마스킹 처리 필수.

## 2. 테스트 가이드 (Testing)
- **테스트 피라미드**: 단위 테스트(가장 많이) > 슬라이스 테스트 > E2E(RestAssured) 순서로 구성.
- **필수 작성 대상**: 모든 신규 도메인/서비스 메서드, 컨트롤러 엔드포인트(성공 케이스 E2E 포함).
- **회귀 가드**: 엔티티 필드 추가나 Seed 데이터 변경 시, 기존 데이터와의 호환성을 검증하는 통합 테스트를 반드시 포함한다.
- **스타일**: BDD(Given/When/Then) 스타일을 따르며, `@DisplayName`에 한국어 설명을 적는다.

## 3. 품질 게이트 (Quality Gates)
- **CI 통과 필수**: 빌드, 테스트, Lint, Typecheck가 모두 통과해야 머지 가능하다.
- **PR 사이즈**: 변경 라인 +400 LOC 이내 권장 (최대 1000 LOC 미만으로 분할).
- **rev QA**: 머지 전후로 Rev 에이전트가 실 환경(local 3-tier)에서 런타임 에러, API 통합, 비기능 요구사항을 검증한다.

## 4. 관측성 및 로깅 (Observability)
- **메트릭 네이밍**: `mobruji.<domain>.<action>` 형식을 따르며 소문자+dot으로 구분한다.
- **PII 마스킹**: 메트릭 라벨에 `sessionId`, `email` 등 개인 식별 정보를 포함하지 않는다.
- **알림 규칙**: 외부 API 에러율, p95 지연 시간 등이 임계치를 넘으면 Discord로 알림을 보낸다.

---
**참조 문서**: `docs/ai-harness/03, 07, 08, 10`
