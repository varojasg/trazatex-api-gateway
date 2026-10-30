package com.nodotextil.trazatex.apigateway;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.nodotextil.trazatex.apigateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.nodotextil.trazatex.apigateway";

    @ArchTest
    static final ArchRule domainOnlyDependsOnJava = classes()
            .that().resideInAPackage(ROOT + ".domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", ROOT + ".domain..")
            .because("el dominio no importa Spring, Kafka, Nimbus ni capas externas");

    @ArchTest
    static final ArchRule applicationOnlyDependsOnDomainReactorAndItself = classes()
            .that().resideInAPackage(ROOT + ".application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "reactor..", ROOT + ".domain..", ROOT + ".application..")
            .because("los casos de uso hablan con el exterior solo por puertos");

    @ArchTest
    static final ArchRule noOneDependsOnInfrastructureFromInside = noClasses()
            .that().resideInAnyPackage(ROOT + ".domain..", ROOT + ".application..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure..");

    @ArchTest
    static final ArchRule gatewayDoesNotDependOnSharedKernel = noClasses()
            .that().resideInAPackage(ROOT + "..")
            .should().dependOnClassesThat().resideInAPackage("com.nodotextil.trazatex.sharedkernel..")
            .because("el Gateway es reactivo y no depende de shared-kernel (progress.md)");
}
