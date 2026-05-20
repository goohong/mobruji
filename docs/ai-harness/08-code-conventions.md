# Code Conventions

본 문서는 코드 작성 시의 **협의된 규칙**을 모아둔다. 컨벤션이 변경되면 본 문서를 먼저 PR로 수정한 뒤 코드에 반영한다. 일부 항목은 checkstyle/spotless(backend) 또는 ESLint/Prettier(web)로 자동 강제되지만 대부분은 PR 리뷰와 팀 합의로 운영한다.

## A. Backend (Java / Spring Boot)

### A-1) 언어 / 포매팅

#### `final` 키워드
- **메서드 매개변수**: 반드시 `final`
- **지역 변수**: 반드시 `final` (재할당이 의도된 루프 변수 등 예외는 PR 리뷰에서 판단)
- **필드**: 불변이 가능하면 `final` 권장

```java
public RecommendationResponse recommend(final RecommendationRequest request) {
    final Recommendation recommendation = recommendationService.create(request.voiceRange(), request.mood());
    return RecommendationResponse.from(recommendation);
}
```

#### `record`
- 필드가 2개 이상이면 **멀티라인**으로 작성.

```java
public record RecommendationRequest(
        VoiceRange voiceRange,
        Gender gender,
        Mood mood) {}
```

#### 어노테이션 순서
- **글자 길이 피라미드**(짧은 것부터 긴 것으로 내려가도록) 정렬.
- 타입/메서드/필드 각각 독립 적용.

```java
@Getter
@Entity
@Table(name = "songs")
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Song { ... }
```

#### 변수명
- **풀네임**으로 작성. 약어 금지.
- DTO/엔티티 인스턴스는 **타입명을 그대로 camelCase**로 사용한다.

```java
// 좋음
final RecommendationResponse recommendationResponse = recommendationService.recommend(request);

// 나쁨
final RecommendationResponse resp = recommendationService.recommend(request);
```

### A-2) DTO

#### 네이밍
- 요청: `XxxRequest`
- 응답: `XxxResponse`
- 리스트 응답 변수명: **`responses`** (복수형 s)

#### 재활용 금지 (이번 스프린트 한정)
- **API별로 DTO를 분리**한다. 한 DTO를 여러 엔드포인트에서 공유하지 않는다.

#### record vs class
- 요청/응답 DTO는 **record 우선**.
- 검증 어노테이션(`@NotNull`, `@Size` 등)은 record 필드에 바로 적용.

### A-3) 메서드 네이밍 (Controller / Service)

| 동작 | 선호 prefix |
|---|---|
| 조회 | `read` / `get` / `find` |
| 등록 | `create` / `add` |
| 수정 | `update` / `modify` |
| 삭제 | `delete` / `remove` |

- `find` 계열은 "없을 수 있음"을 반환 타입(`Optional<T>`)으로 표현할 때 우선.
- `get` 계열은 **반드시 존재**하는 단건 조회에 사용(없으면 예외).
- `read` 계열은 목록/단건 일반 조회에 사용.

### A-4) 엔티티

#### 생성자 접근 제한
- 다음 두 어노테이션을 **함께** 사용한다.
  ```java
  @AllArgsConstructor(access = AccessLevel.PACKAGE)
  @NoArgsConstructor(access = AccessLevel.PROTECTED)
  ```
- `@NoArgsConstructor`는 **`PROTECTED`** 여야 한다. Hibernate가 lazy loading을 위해 엔티티를 상속한 프록시 클래스를 런타임에 생성하는데, 프록시는 일반적으로 다른 패키지/클래스로더에서 만들어지므로 package-private 기본 생성자에는 접근할 수 없다. `PROTECTED`여야 프록시 서브클래스가 `super()`를 호출할 수 있다.
- `@AllArgsConstructor`는 **`PACKAGE`** 로 좁혀, 외부 패키지에서 임의 생성을 막고 같은 패키지의 정적 팩토리 메서드(`Song.create(...)`)만 전체 필드 생성자를 호출하도록 한다.

#### 도메인 메서드
- 상태 전이는 반드시 **엔티티 내부의 도메인 메서드**로 캡슐화한다 (`softDelete()`, `markPlayed()` 등).
- Service에서 엔티티의 setter를 직접 호출하지 않는다. Setter는 원칙적으로 두지 않는다.

### A-5) Lombok 사용 지침

#### 적극 사용 가능
- `@Getter` — 엔티티/DTO/도메인 객체
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)` — JPA 엔티티 (프록시 호환). `@AllArgsConstructor(access = AccessLevel.PACKAGE)`와 함께 사용.
- `@RequiredArgsConstructor` — Service/Repository 주입
- `@Slf4j` — 로거

#### 주의해서 사용
- `@Data` — **금지**.
- `@Setter` — 엔티티에는 **금지**. DTO에도 비권장(record 사용).
- `@AllArgsConstructor` — public이면 외부 결합 증가. 내부용으로만.
- `@Builder` — 생성자 파라미터가 많을 때만.

### A-6) null 검증

#### 두 레이어에서 모두 검증
- **DTO 레이어**: `@NotNull`, `@NotBlank`, `@Valid` 등 Bean Validation 어노테이션으로 1차 차단.
- **Domain 레이어**: 엔티티/값 객체의 팩토리 메서드 또는 생성자에서 `Objects.requireNonNull(...)` 혹은 명시적 예외 throw로 2차 차단.

#### Domain 검증 원칙
> Domain에서는 **매개변수로 받은 모든 값에 대해 null 검증**을 수행한다.

```java
public static Song create(final String title, final String artist, final VoiceRange range) {
    Objects.requireNonNull(title, "title must not be null");
    Objects.requireNonNull(artist, "artist must not be null");
    Objects.requireNonNull(range, "range must not be null");
    return new Song(title, artist, range);
}
```

- "DTO에서 검증했으니 Domain은 생략"은 허용하지 않는다.

#### Service/Controller/Config 중간 가드 지양
- **Service/Controller 메서드 파라미터**에 대한 `Objects.requireNonNull` 가드는 **두지 않는다**.
- **Service/Controller 생성자의 DI 주입 의존성**에도 `Objects.requireNonNull` 가드를 두지 않는다.
- **`@ConfigurationProperties` 바인딩 레코드의 필드**는 `@Validated` + `@NotBlank`/`@NotNull` 등 Bean Validation 어노테이션으로 선언적으로 검증.

---

## B. Frontend (Next.js / TypeScript)

### B-1) 포맷
- Prettier로 자동 포맷 (ESLint와 통합)
- import 정렬: `eslint-plugin-import` 자동 정렬

### B-2) 컴포넌트
- 함수형 컴포넌트만. 클래스 컴포넌트 금지.
- props는 명시적 타입 (interface 또는 type)
- 파일명: 컴포넌트는 `PascalCase.tsx`, 훅은 `useXxx.ts`, 유틸은 `camelCase.ts`

### B-3) 상태 관리
- 서버 상태: React Query (TanStack Query)
- 클라이언트 상태: 우선 React 기본 (useState/Reducer). 복잡해지면 Zustand 도입 (ADR 추가)
- URL 상태: Next.js 라우터 + searchParams

### B-4) API 호출
- 모든 API 호출은 `web/src/lib/api/` 한 곳에서 집중 관리
- fetch wrapper 또는 axios 인스턴스 1개. 컴포넌트가 직접 `fetch` 호출 금지.
- 응답 타입은 backend DTO와 1:1 매핑 (수동 동기화 또는 추후 OpenAPI 생성기)

### B-5) 환경변수
- 클라이언트 노출: `NEXT_PUBLIC_*` 접두사
- 서버 전용: 접두사 없이
- 검증: 부트 시점 zod 등으로 스키마 검증 (선택)

### B-6) 스타일
- Tailwind 우선. 인라인 클래스 합성.
- 디자인 토큰/테마는 `tailwind.config.ts`에서 관리
- 복잡한 컴포넌트는 shadcn/ui 패턴 (도입 시 ADR)

---

## C. 오픈 항목 (컨벤션 미결)

| # | 주제 | 상태 |
|---|---|---|
| C1 | Frontend 상태관리 라이브러리 도입 시점 (Zustand vs Jotai) | 논의 필요 |
| C2 | API 타입 자동 생성 도구 (OpenAPI generator, tRPC 등) | 논의 필요 |
| C3 | E2E 프레임워크 도입 (Playwright vs Cypress) | 논의 필요 |
| C4 | `@ToString`, `@EqualsAndHashCode` 허용 범위 | 논의 필요 |

## D. 참고
- 테스트 컨벤션: `07-testing-guide.md`
- 도메인 모델: `06-domain-model.md`
- PR 워크플로우: `02-agent-workflow.md`
