package com.ecommerce.backend;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Keeps the reasons in .trivyignore.yaml true: each accepted vulnerability is in a feature this
 * application does not use. Using one of them fails here, and the exception must be revisited first.
 */
@AnalyzeClasses(packages = "com.ecommerce.backend", importOptions = ImportOption.DoNotIncludeTests.class)
class NoUnusedVulnerableFeaturesTest {

    /** CVE-2026-47884, fixed only in Spring Framework 7. */
    @ArchTest
    static final ArchRule noXsltViews = noClasses().should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.servlet.view.xslt.XsltView")
            .because("XsltView is affected by CVE-2026-47884, accepted only because it is unused (.trivyignore.yaml)");

    /** CVE-2026-47890, fixed only in Spring Framework 7. */
    @ArchTest
    static final ArchRule noServerSentEvents = noClasses().should().dependOnClassesThat(
                    resideInAPackage("org.springframework.web.servlet.mvc.method.annotation")
                            .and(simpleNameStartingWith("Sse")))
            .because("SSE is affected by CVE-2026-47890, accepted only because it is unused (.trivyignore.yaml)");
}
