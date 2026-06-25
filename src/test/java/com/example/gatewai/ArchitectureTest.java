package com.example.gatewai;

import static com.tngtech.archunit.library.Architectures.onionArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.example.gatewai",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    @ArchTest
    static final ArchRule HEXAGONAL_ARCHITECTURE_IS_RESPECTED =
        onionArchitecture()
            .domainModels("com.example.gatewai.domain.model..")
            .domainServices("com.example.gatewai.domain.port..")
            .applicationServices("com.example.gatewai.application..")
            .adapter("web", "com.example.gatewai.adapter.in.web..")
            .adapter("persistence", "com.example.gatewai.infrastructure.persistence..")
            .adapter("llm", "com.example.gatewai.infrastructure.llm..")
            .adapter("vectorstore", "com.example.gatewai.infrastructure.vectorstore..");
}
