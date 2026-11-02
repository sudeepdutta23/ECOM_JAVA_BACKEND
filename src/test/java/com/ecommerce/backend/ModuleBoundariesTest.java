package com.ecommerce.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The dependency rules of docs/ARCHITECTURE.md as tests, so a forbidden import fails the build
 * instead of waiting for a reviewer. Runs on the compiled production classes in a fraction of a
 * second, without Spring.
 *
 * <p>Module dependencies point one way: {@code auth -> org -> rbac -> common}, {@code auth -> rbac}.
 * A module talks to another only through its root package (public types such as
 * {@code rbac.Permission}, {@code org.OrgPermission}) and its {@code service} package. Its entities
 * and repositories are private: that is what keeps a seller's data behind {@code OrganizationGuard},
 * and what would let a module move into its own service one day.
 */
@AnalyzeClasses(packages = "com.ecommerce.backend", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

    private static final String BASE = "com.ecommerce.backend.";

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices().matching(BASE + "(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule commonDependsOnNoModule = noClasses().that().resideInAPackage(BASE + "common..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "auth..", BASE + "rbac..", BASE + "org..",
                    BASE + "system..");

    @ArchTest
    static final ArchRule rbacKnowsNoOtherModule = noClasses().that().resideInAPackage(BASE + "rbac..")
            .should().dependOnClassesThat().resideInAnyPackage(BASE + "auth..", BASE + "org..");

    @ArchTest
    static final ArchRule orgDoesNotKnowAuth = noClasses().that().resideInAPackage(BASE + "org..")
            .should().dependOnClassesThat().resideInAPackage(BASE + "auth..");

    @ArchTest
    static final ArchRule authInternalsArePrivate = internalsOf("auth");

    @ArchTest
    static final ArchRule rbacInternalsArePrivate = internalsOf("rbac");

    @ArchTest
    static final ArchRule orgInternalsArePrivate = internalsOf("org");

    @ArchTest
    static final ArchRule controllersNeverTouchRepositories = noClasses().that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule repositoriesNeverCallUpwards = noClasses().that().resideInAPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..");

    /** Nothing outside the module may use its entities, repositories, DTOs or controllers. */
    private static ArchRule internalsOf(String module) {
        String root = BASE + module;
        return noClasses().that().resideOutsideOfPackage(root + "..")
                .should().dependOnClassesThat().resideInAnyPackage(root + ".entity..", root + ".repository..",
                        root + ".dto..", root + ".controller..")
                .because(module + " exposes only its root package and its services to other modules");
    }
}
