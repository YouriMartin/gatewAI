package io.github.yourimartin.gatewai;

import static com.tngtech.archunit.library.Architectures.onionArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "io.github.yourimartin.gatewai",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    @ArchTest
    static final ArchRule HEXAGONAL_ARCHITECTURE_IS_RESPECTED =
        onionArchitecture()
            .domainModels("io.github.yourimartin.gatewai.domain.model..")
            .domainServices("io.github.yourimartin.gatewai.domain.port..")
            .applicationServices("io.github.yourimartin.gatewai.application..")
            .adapter("web", "io.github.yourimartin.gatewai.adapter.in.web..")
            .adapter("mcp", "io.github.yourimartin.gatewai.adapter.in.mcp..")
            .adapter("persistence", "io.github.yourimartin.gatewai.infrastructure.persistence..")
            .adapter("llm", "io.github.yourimartin.gatewai.infrastructure.llm..")
            .adapter("vectorstore", "io.github.yourimartin.gatewai.infrastructure.vectorstore..")
            .adapter("cache", "io.github.yourimartin.gatewai.infrastructure.cache..")
            .adapter("carbon", "io.github.yourimartin.gatewai.infrastructure.carbon..")
            .adapter("dispatch", "io.github.yourimartin.gatewai.infrastructure.dispatch..")
            .adapter("metrics", "io.github.yourimartin.gatewai.infrastructure.metrics..");
}
