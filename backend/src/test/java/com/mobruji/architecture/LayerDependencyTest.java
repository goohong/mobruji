package com.mobruji.architecture;

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
}
