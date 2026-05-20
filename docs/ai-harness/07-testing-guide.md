# Testing Guide

## 1) 목적
레이어별로 **어떤 테스트를 얼마나** 작성할지 기준을 고정해서 팀원/AI 에이전트가 일관되게 작성하도록 한다.

## 2) 테스트 피라미드 (이 레포 기준)

```
        E2E (RestAssured 전체 플로우)  — 핵심 유스케이스만
      ─────────────────────────
     Slice 테스트 (@WebMvcTest, @DataJpaTest) — 경계층
   ─────────────────────────────────
  단위 테스트 (도메인 로직, 순수 JUnit)  — 가장 많이
```

## 3) 레이어별 지침 (Backend)

### 3-1) 도메인 엔티티 / 값 객체
- **언제**: 생성자 검증, 도메인 메서드(상태 전이, 계산) 있을 때
- **어노테이션**: 없음. 순수 JUnit + AssertJ
- **DB 접근**: 금지 (엔티티 로직만 검증)

### 3-2) Repository 계층
- **언제**: 커스텀 쿼리(`@Query`)나 복잡한 `Specification`이 있을 때
- **어노테이션**: `@DataJpaTest`
- **단순 CRUD만 있는 Spring Data 메서드는 테스트 생략 가능** (프레임워크가 보증)

### 3-3) Service 계층 (도메인 로직)
- **언제**: 비즈니스 규칙, 검증, 트랜잭션 경계가 있을 때
- **어노테이션**: 없음. Mockito로 Repository/외부 어댑터 mock
- **DB 접근**: mock. 실제 DB 붙이면 그건 통합 테스트

### 3-4) Controller 계층
- **언제**: 입력 검증, 인증/인가, 응답 포맷 검증
- **어노테이션**: `@WebMvcTest(컨트롤러.class)` + `MockMvc`
- **하단 mock**: `@MockBean`으로 Service mock

### 3-5) 통합 (Integration) 테스트
- **언제**: 외부 연동 어댑터, 핵심 E2E 유스케이스
- **어노테이션**: `@SpringBootTest` + `@AutoConfigureMockMvc`
- **DB**: H2 또는 testcontainers (선택은 첫 통합 테스트 PR에서 ADR로 결정)
- **외부 API**: WireMock 또는 인터페이스 mock
- **수량 기준**: "사용자 시나리오당 1개" 정도. 과도하게 쓰면 CI 느려짐

## 4) 네이밍 / 스타일 (BDD)

- **BDD 스타일을 기본 규칙**으로 한다. 본문은 `given / when / then` 블록으로 나눈다.
- 메서드명은 **영어**(`given_when_then` 스타일 또는 간결한 영어)로 작성하고, `@DisplayName`에 **한국어** 설명을 붙인다. 파일 내 일관 사용.

```java
@Test
@DisplayName("recommend 호출하면 사용자 음역대에 맞는 곡 리스트를 반환한다")
void recommend_called_returnsSongsInRange() {
    // given
    final VoiceRange voiceRange = VoiceRange.of(Note.C3, Note.A4);
    final RecommendationRequest request = new RecommendationRequest(voiceRange, Gender.MALE, Mood.UPBEAT);

    // when
    final List<Song> responses = recommendationService.recommend(request);

    // then
    assertThat(responses).isNotEmpty();
    assertThat(responses).allMatch(song -> voiceRange.contains(song.getRange()));
}
```

## 4-1) E2E 필수 규칙
- **모든 신규 엔드포인트는 성공 케이스 E2E 테스트를 반드시 작성**한다.
- 실패/검증 케이스는 리뷰어 요청 시 추가.
- E2E 프레임워크는 **RestAssured**를 사용한다 (`@SpringBootTest(webEnvironment = RANDOM_PORT)` + RestAssured).
- RestAssured가 아직 의존성에 없다면 첫 E2E 작성 PR에서 함께 추가.

## 5) 어떤 테스트를 꼭 써야 하는가 (MVP 기준)

| 대상 | 강제 |
|---|---|
| 신규 **도메인 메서드**(엔티티/VO에 로직 추가) | ✅ 단위 테스트 필수 |
| 신규 **Service 메서드**(비즈니스 규칙) | ✅ 단위 테스트 필수 |
| 신규 **Controller 엔드포인트** | ✅ `@WebMvcTest` 1개 이상 + E2E 성공 케이스 |
| 신규 **외부 어댑터**(음원 API, OAuth 등) | ✅ 응답 매핑 단위 + 통합 1개 |
| 순수 필드 추가/이름 변경만 있는 리팩터 | ⭕ 생략 가능, PR 본문에 사유 명시 |
| DTO/값 객체 | ⭕ 복잡한 로직 있을 때만 |

## 6) Frontend 테스트
- 컴포넌트: React Testing Library + Vitest/Jest
- E2E: Playwright (도입 시점은 추후 ADR)
- 우선순위: 추천 결과 표시 컴포넌트, 입력 폼 검증
- 신규 페이지/주요 인터랙션은 최소 1개 컴포넌트 테스트 동반

## 7) 테스트 생략 조건
- 설정/문서/스타일 변경
- 엔티티 필드 추가만 있는 리팩터 (도메인 로직 없음)
- 외부 서비스 연결이 불가능한 환경 (그 경우 PR 본문에 "테스트 불가 사유" 명시)

## 8) 커버리지 기준
- 현재 정량 기준 없음. jacoco 도입 후 도메인 패키지 70% 목표 검토.
- 대신 **변경 라인에 대해 의미 있는 케이스를 1개 이상** 요구한다.

## 9) 디렉토리 구조 (Backend)
```
backend/src/test/java/com/mobruji/
├── user/
│   ├── UserTest.java            # 도메인 단위
│   ├── UserServiceTest.java     # 서비스 단위
│   └── UserControllerTest.java  # 컨트롤러 슬라이스
├── recommendation/
│   └── ...
├── integration/                  # @SpringBootTest
│   └── ...
└── MobrujiBackendApplicationTests.java  # 컨텍스트 로딩
```

## 10) 실행
```bash
# Backend
cd backend
./gradlew test                          # 전체
./gradlew test --tests "*UserTest"      # 특정 클래스
./gradlew test --tests "*recommend*"    # 특정 메서드 패턴

# Frontend (셋업 후)
cd web
npm test
```

푸시 전 반드시 `cd backend && ./gradlew checkstyleMain spotlessCheck test` (백엔드 변경 시) 통과.
