package com.mobruji.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ADR 0005 §A-7 의존 방향(`api → application → domain ← infrastructure`)을 자동 검증한다.
 *
 * <p>위반이 발견되면 PR 단계에서 차단되어, application이 api.dto를 참조하는 등의 회귀를 막는다.
 * 본 룰은 BC를 분리하지 않고 패키지 패턴(`..api..`, `..application..` 등)으로만 검증한다. v0.x 한정 단축형
 * (cross-BC application → infrastructure 허용)이라 BC 단위 분리 검증은 도입하지 않는다.
 *
 * <p>{@link #layered}는 4계층 의존 방향 전반을 강제하고, 아래의 명시적 anti-rule들은 메시지/의도를
 * 더 분명히 드러내기 위해 좁은 범위에서 중복 가드한다(특히 {@code api.dto} 회귀 방지).
 *
 * <p>레이어 가드를 확장할 때(예: cyclic dependency 금지, controller 어노테이션 검증)는 본 파일에 룰을
 * 추가하면 된다. 별도 ADR 없이도 ADR 0008 References에 사유와 PR 번호만 남긴다.
 */
@AnalyzeClasses(packages = "com.mobruji", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyTest {

    @ArchTest
    static final ArchRule layered = layeredArchitecture()
            .consideringAllDependencies()
            .layer("api").definedBy("..api..")
            .layer("application").definedBy("..application..")
            .layer("domain").definedBy("..domain..")
            .layer("infrastructure").definedBy("..infrastructure..")
            .whereLayer("api").mayNotBeAccessedByAnyLayer()
            .whereLayer("application").mayOnlyBeAccessedByLayers("api")
            .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "infrastructure", "api")
            .whereLayer("infrastructure").mayOnlyBeAccessedByLayers("application");

    /**
     * application 계층은 api.dto(컨트롤러 요청/응답 DTO)에 의존해서는 안 된다.
     *
     * <p>{@link #layered} 룰이 이미 application → api 전체를 차단하지만, PR #89에서 발견된 8건
     * 회귀의 직접 원인이 api.dto 참조였기 때문에, 룰 단위로 분리해 위반 메시지가 즉시 "api.dto 회귀"
     * 임을 드러내도록 한다. ADR 0005 §A-7, 0008 References "DTO 위치 검증".
     */
    @ArchTest
    static final ArchRule applicationMustNotDependOnApiDto = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..api.dto..")
            .because("application 계층은 api.dto에 의존할 수 없다 (ADR 0005 §A-7, PR #89 회귀 가드)");

    /**
     * domain 계층은 api.dto에 의존해서는 안 된다. application과 같은 사유로, layered 룰을 좁혀
     * 위반 시 메시지 가독성을 높인다.
     */
    @ArchTest
    static final ArchRule domainMustNotDependOnApiDto = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..api.dto..")
            .because("domain 계층은 api.dto에 의존할 수 없다 (ADR 0005 §A-7)");

    /**
     * infrastructure 계층은 api.dto에 의존해서는 안 된다.
     */
    @ArchTest
    static final ArchRule infrastructureMustNotDependOnApiDto = noClasses()
            .that().resideInAPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("..api.dto..")
            .because("infrastructure 계층은 api.dto에 의존할 수 없다 (ADR 0005 §A-7)");

    /**
     * DTO 클래스는 오직 {@code ..api.dto..} 패키지 아래에만 존재해야 한다.
     *
     * <p>이는 누군가 {@code application.dto}, {@code domain.dto}, {@code infrastructure.dto}와
     * 같은 우회 패키지를 새로 만들어 위 anti-rule을 무력화하는 것을 방지한다. application 계층 입력은
     * {@code *Command} record(예: {@link com.mobruji.recommendation.application.CreateRecommendationCommand}),
     * 출력은 domain 객체(예: {@link com.mobruji.recommendation.domain.RecommendationResult})를 사용한다.
     */
    @ArchTest
    static final ArchRule dtoClassesMustLiveUnderApiDto = classes()
            .that().resideInAPackage("..dto..")
            .should().resideInAPackage("..api.dto..")
            .because("DTO는 컨트롤러 표면 전용이며 ..api.dto..에만 위치해야 한다 (ADR 0005 §A-7, ADR 0008)");
}
