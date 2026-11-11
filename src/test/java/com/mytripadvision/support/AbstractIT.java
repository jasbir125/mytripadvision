package com.mytripadvision.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Whole application against real PostgreSQL (pgvector) with the four external HTTP APIs replaced by one WireMock
 * server and the embedding model replaced by a deterministic fake. No real network call is made.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mytripadvision.database.url=",
        "mytripadvision.workflow.human-review=on",
        "mytripadvision.llm.provider=groq",
        "mytripadvision.llm.groq-api-key=test-key",
        "mytripadvision.aviationstack.api-key=test-key",
        "mytripadvision.tavily.api-key=test-key",
        "mytripadvision.openweather.api-key=test-key"
})
@AutoConfigureRestTestClient
@Import({TestcontainersConfiguration.class, FakeEmbeddingConfiguration.class})
public abstract class AbstractIT {

    protected static final WireMockServer WIRE = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIRE.start();
    }

    @DynamicPropertySource
    static void externalApis(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + WIRE.port();
        registry.add("mytripadvision.llm.groq-base-url", () -> base + "/llm");
        registry.add("mytripadvision.aviationstack.base-url", () -> base + "/aviation");
        registry.add("mytripadvision.tavily.base-url", () -> base + "/tavily");
        registry.add("mytripadvision.openweather.base-url", () -> base + "/weather");
    }
}
