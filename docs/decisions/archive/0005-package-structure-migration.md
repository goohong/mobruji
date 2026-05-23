# ADR 0005 패키지 마이그레이션 가이드 (be 사이클용)

> **Status**: `companion to 0005` — completed migration (archived 2026-05-23, see #459)
>
> 본 문서는 ADR 0005 결정에 따른 **1회성 마이그레이션 절차**다. backend 4계층(`domain/application/infrastructure/api`) 이동은 `docs/milestones/v0.2.md` 기록대로 완료됐고, ADR 0008(ArchUnit) 가드가 본 가이드의 룰을 자동 검증 중이다. 본 문서는 결정 이력 보존을 위해 `docs/decisions/archive/` 로 이동했으며, 신규 결정은 ADR 0005 본 문서(`docs/decisions/0005-package-structure.md`)를 참조한다. 마이그레이션 실행 이슈: #82의 후속 이슈 참조.

## 1) 목표 디렉토리 트리

```
backend/src/main/java/com/mobruji/
├── MobrujiBackendApplication.java          (그대로 — 루트 유지)
├── voice/
│   ├── domain/
│   │   ├── VoiceRange.java
│   │   ├── VoiceRangeSourceMethod.java
│   │   └── VoiceRangeNotFoundException.java
│   ├── application/
│   │   └── VoiceRangeService.java
│   ├── infrastructure/
│   │   └── VoiceRangeRepository.java
│   └── api/
│       ├── VoiceRangeController.java
│       └── dto/
│           ├── VoiceRangeCreateRequest.java
│           ├── VoiceRangeUpdateRequest.java
│           └── VoiceRangeResponse.java
├── song/
│   ├── domain/
│   │   ├── Song.java
│   │   ├── Mood.java
│   │   ├── MusicalKey.java
│   │   ├── MetadataSource.java
│   │   └── SongNotFoundException.java
│   ├── application/
│   │   ├── SongService.java
│   │   └── SongSeedLoader.java
│   ├── infrastructure/
│   │   └── SongRepository.java
│   └── api/
│       ├── SongController.java
│       └── dto/
│           └── SongResponse.java
└── recommendation/
    ├── domain/
    │   ├── Recommendation.java
    │   ├── RecommendationRequestEntity.java
    │   └── RecommendationNotFoundException.java
    ├── application/
    │   ├── RecommendationService.java
    │   ├── RecommendationScorer.java
    │   ├── RecommendationProperties.java
    │   ├── DiversityPostProcessor.java
    │   └── SeedDeriver.java
    ├── infrastructure/
    │   ├── RecommendationRepository.java
    │   ├── RecommendationRequestRepository.java
    │   └── MusicalKeyMidiResolver.java
    └── api/
        ├── RecommendationController.java
        └── dto/
            ├── RecommendationCreateRequest.java
            ├── RecommendationResponse.java
            └── RecommendedSongResponse.java
```

## 2) 파일별 이동 매핑

### voice BC

| 현재 | 목표 | 분류 근거 |
|---|---|---|
| `voice/VoiceRange.java` | `voice/domain/VoiceRange.java` | JPA 엔티티 (v0.x 한정 domain 배치) |
| `voice/VoiceRangeSourceMethod.java` | `voice/domain/VoiceRangeSourceMethod.java` | 값 객체(enum) |
| `voice/VoiceRangeNotFoundException.java` | `voice/domain/VoiceRangeNotFoundException.java` | 도메인 예외 |
| `voice/VoiceRangeService.java` | `voice/application/VoiceRangeService.java` | `@Service` 유스케이스 |
| `voice/VoiceRangeRepository.java` | `voice/infrastructure/VoiceRangeRepository.java` | Spring Data JPA repo |
| `voice/VoiceRangeController.java` | `voice/api/VoiceRangeController.java` | `@RestController` |
| `voice/dto/*` | `voice/api/dto/*` | API DTO |

### song BC

| 현재 | 목표 | 분류 근거 |
|---|---|---|
| `song/Song.java` | `song/domain/Song.java` | JPA 엔티티 |
| `song/Mood.java` | `song/domain/Mood.java` | 값 객체(enum) |
| `song/MusicalKey.java` | `song/domain/MusicalKey.java` | 값 객체(enum) |
| `song/MetadataSource.java` | `song/domain/MetadataSource.java` | 값 객체(enum) |
| `song/SongNotFoundException.java` | `song/domain/SongNotFoundException.java` | 도메인 예외 |
| `song/SongService.java` | `song/application/SongService.java` | `@Service` 유스케이스 |
| `song/SongSeedLoader.java` | `song/application/SongSeedLoader.java` | `ApplicationRunner` — 부팅 시 application-level 시드 적재 |
| `song/SongRepository.java` | `song/infrastructure/SongRepository.java` | Spring Data JPA repo |
| `song/SongController.java` | `song/api/SongController.java` | `@RestController` |
| `song/dto/SongResponse.java` | `song/api/dto/SongResponse.java` | API DTO |

### recommendation BC

| 현재 | 목표 | 분류 근거 |
|---|---|---|
| `recommendation/Recommendation.java` | `recommendation/domain/Recommendation.java` | JPA 엔티티 |
| `recommendation/RecommendationRequestEntity.java` | `recommendation/domain/RecommendationRequestEntity.java` | JPA 엔티티 |
| `recommendation/RecommendationNotFoundException.java` | `recommendation/domain/RecommendationNotFoundException.java` | 도메인 예외 |
| `recommendation/RecommendationService.java` | `recommendation/application/RecommendationService.java` | `@Service` 유스케이스 |
| `recommendation/RecommendationScorer.java` | `recommendation/application/RecommendationScorer.java` | `@Component` 도메인 서비스 — 가중치 의존이라 application |
| `recommendation/RecommendationProperties.java` | `recommendation/application/RecommendationProperties.java` | `@ConfigurationProperties` |
| `recommendation/DiversityPostProcessor.java` | `recommendation/application/DiversityPostProcessor.java` | application 후처리기 |
| `recommendation/SeedDeriver.java` | `recommendation/application/SeedDeriver.java` | application 유틸 (static helper) |
| `recommendation/MusicalKeyMidiResolver.java` | `recommendation/infrastructure/MusicalKeyMidiResolver.java` | 영속/메타 매핑 보조 (enum → MIDI 정수) |
| `recommendation/RecommendationRepository.java` | `recommendation/infrastructure/RecommendationRepository.java` | Spring Data JPA repo |
| `recommendation/RecommendationRequestRepository.java` | `recommendation/infrastructure/RecommendationRequestRepository.java` | Spring Data JPA repo |
| `recommendation/RecommendationController.java` | `recommendation/api/RecommendationController.java` | `@RestController` |
| `recommendation/dto/*` | `recommendation/api/dto/*` | API DTO |

### 루트

| 현재 | 목표 | 분류 근거 |
|---|---|---|
| `MobrujiBackendApplication.java` | (변경 없음) | Spring Boot `@SpringBootApplication`은 component-scan 루트라 유지 |

## 3) 테스트 패키지

테스트는 운영 코드의 패키지를 그대로 따라간다.

| 현재 | 목표 |
|---|---|
| `voice/VoiceRangeTest.java` | `voice/domain/VoiceRangeTest.java` |
| `voice/VoiceRangeServiceTest.java` | `voice/application/VoiceRangeServiceTest.java` |
| `voice/VoiceRangeControllerTest.java` | `voice/api/VoiceRangeControllerTest.java` |
| `song/SongTest.java` | `song/domain/SongTest.java` |
| `song/SongServiceTest.java` | `song/application/SongServiceTest.java` |
| `song/SongControllerTest.java` | `song/api/SongControllerTest.java` |
| `recommendation/RecommendationScorerTest.java` | `recommendation/application/RecommendationScorerTest.java` |
| `recommendation/RecommendationDiversityTest.java` | `recommendation/application/RecommendationDiversityTest.java` |
| `recommendation/SeedDeriverTest.java` | `recommendation/application/SeedDeriverTest.java` |
| `recommendation/RecommendationPropertiesValidationTest.java` | `recommendation/application/RecommendationPropertiesValidationTest.java` |
| `recommendation/RecommendationRequestEntityPersistenceTest.java` | `recommendation/domain/RecommendationRequestEntityPersistenceTest.java` |
| `integration/*` | (변경 없음) | E2E는 BC 횡단이라 별도 패키지 유지 |

## 4) 실행 절차 (be 사이클 권장 순서)

1. **사전 확인**
   - 본 ADR 0005 PR이 머지된 상태에서 시작.
   - 마이그레이션 작업 브랜치: `refactor/package-structure-#<후속이슈>`.
2. **IDE refactor 사용** (권장: IntelliJ "Move Class")
   - 한 BC씩 처리: voice → song → recommendation 순. BC당 1 커밋 권장.
   - Move 시 "Search references in comments and strings" 체크 → `application.yml`, README 등의 풀 경로 문자열도 갱신.
   - 패키지 declaration, import는 IDE가 자동 갱신.
3. **검증**
   - 각 BC 이동 후 즉시 `./gradlew compileJava compileTestJava` 통과 확인.
   - 모두 끝나면 `./gradlew checkstyleMain spotlessCheck test`.
4. **부수 작업**
   - `application.yml`의 `@ConfigurationProperties` 바인딩(`recommendation.*`)은 prefix 기반이라 패키지 이동의 영향 없음. 단 `@EnableConfigurationProperties(RecommendationProperties.class)` 위치가 있으면 import 경로만 바뀐다.
   - `SongSeedLoader`의 `@Profile("!test")`는 동작 영향 없음.
   - `RecommendationService` → `SongRepository` cross-BC 주입은 ADR 0005 §Cross-BC 의존에 따라 그대로 유지 (포트 분리하지 않음).
5. **PR 본문**
   - PR size 룰 예외 명시: "ADR 0005 마이그레이션 — 전체 일관 변경이라 자연 분할 불가".
   - 라벨: `type:refactor, scope:infra, ai-generated, ai:claude` (보호 영역 변경 없음 — `needs-human-review` 불필요).

## 5) 마이그레이션 후 후속 작업

- ArchUnit 도입 이슈 (계층 의존 자동 검증). 본 가이드와 별 이슈.
- `RecommendationService` cross-BC 의존을 Port-Adapter로 분리할지는 BC 수가 늘 때 재논의 (별도 ADR).
