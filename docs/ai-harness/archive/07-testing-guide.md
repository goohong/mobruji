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
- 엔티티 필드 추가만 있는 리팩터 (도메인 로직 없음) — 단, **§7-1 회귀 가드**의 조건을 충족하지 않으면 생략 불가
- 외부 서비스 연결이 불가능한 환경 (그 경우 PR 본문에 "테스트 불가 사유" 명시)

## 7-1) 엔티티 / seed 변경 회귀 가드 (비협상)

> 근거: rev 사이클 9 F-2(이슈 #125). PR #96(`Song.lowMidi/highMidi/difficulty` 추가)에서 `SongSeedLoader`의 `if (count > 0) skip` 패턴이 **새 컬럼이 NULL인 기존 row**를 그대로 두어 운영 결함을 유발했다. 새 컬럼 추가가 "리팩터" 카테고리로 분류되어 통합 테스트 없이 머지된 게 원인.

### 7-1.1 엔티티 필드 추가 시 — 두 케이스 모두 통합 테스트

엔티티에 nullable 필드를 추가하더라도 **이전 데이터셋과의 호환성**을 통합 테스트로 가드한다. 둘 중 하나라도 빠지면 PR 머지 금지.

| 케이스 | 시나리오 | 검증 항목 |
|---|---|---|
| **A. 빈 DB → seed 적재** | 신규 환경 부팅. seed loader가 새 컬럼까지 채움 | 새 컬럼이 not-null인 row 수 = 시드 수 |
| **B. 기존 데이터(이전 컬럼만) → 재기동** | 운영 DB에 새 컬럼이 NULL인 row가 이미 있음 | (1) 부팅 성공 (2) 새 컬럼 NULL인 row에 대한 응답 직렬화/추천 로직이 안전(null safety 또는 backfill 검증) |

### 7-1.2 Idempotent loader (`count > 0 skip` 류) 안전성 명시

`SongSeedLoader`처럼 "이미 있으면 skip"하는 loader는 **새 컬럼 추가 시점에 반드시 다음 중 하나를 명시**한다 (PR 본문 + 코드 주석):

1. **Row별 upsert로 전환** — `findByNaturalKey` 후 새 컬럼만 update.
2. **Backfill 마이그레이션 동반** — Flyway/Liquibase 도입 전에는 1회성 `@Bean ApplicationRunner`로 NULL row 채움. 컬럼 추가 PR과 **같은 PR**에서 처리.
3. **NULL 허용 + 응답 안전 처리** — 새 컬럼이 끝까지 nullable. DTO/직렬화/도메인 로직이 NULL을 모두 수용. 통합 테스트로 NULL row 시나리오 1건 강제.

PR 본문 체크리스트(엔티티/seed 변경 시 필수):
- [ ] Case A(빈 DB) 통합 테스트 존재
- [ ] Case B(기존 데이터) 통합 테스트 존재 또는 선택지 1~3 중 하나를 명시적으로 채택
- [ ] Idempotent loader가 있다면 위 1~3 중 선택지 + 근거를 PR 본문 또는 코드 주석에 기록

### 7-1.3 마이그레이션(Flyway/Liquibase 도입 시)

도입 시점에 본 절을 갱신할 ADR을 함께 작성한다. 도입 이전 임시 규칙:
- DDL 변경(컬럼 추가/제거/타입 변경)은 **rollback 가능**해야 한다. PR 본문에 rollback 절차를 적는다.
- **Zero-downtime 검증**: (1) 기존 코드가 새 컬럼 없이도 동작 (2) 새 코드가 기존 컬럼만 있는 row를 처리 — 두 케이스 모두 통합 테스트 1건씩.

### 7-1.4 예시

#### 단위 — Idempotent loader의 새 컬럼 안전성 가드 (Case B 일부)

```java
@Test
@DisplayName("기존 row가 있을 때 SongSeedLoader는 새 컬럼을 backfill하거나 skip 사유를 명시한다")
void run_existingRowsPresent_safeForNewColumns() throws Exception {
    // given — 새 컬럼(lowMidi/highMidi)이 NULL인 기존 row 1건
    final Song legacy = Song.builder()
            .title("기존곡")
            .artist("아티스트")
            .metadataSource(MetadataSource.MANUAL_SEED)
            .build();
    songRepository.save(legacy);

    // when — loader 재기동
    songSeedLoader.run(new DefaultApplicationArguments());

    // then — 부팅 성공 + 응답 직렬화 시 NULL 노트명이 안전 처리
    final Song reloaded = songRepository.findById(legacy.getId()).orElseThrow();
    assertThat(SongResponse.from(reloaded).lowestNoteName()).isNull(); // null-safe 직렬화 가드
}
```

#### 통합 — 빈 DB seed 적재 (Case A)

```java
@Test
@DisplayName("빈 DB 부팅 시 SongSeedLoader는 모든 시드를 lowMidi/highMidi 포함해 적재한다")
void emptyDb_seedLoader_populatesNewColumns() {
    // given: songRepository.count() == 0 (테스트 컨테이너 초기 상태)
    // when: ApplicationRunner 실행 (Spring 컨텍스트 로딩 시점)
    // then
    final List<Song> all = songRepository.findAll();
    assertThat(all).isNotEmpty();
    assertThat(all).allMatch(s -> s.getLowMidi() != null && s.getHighMidi() != null);
}
```

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
