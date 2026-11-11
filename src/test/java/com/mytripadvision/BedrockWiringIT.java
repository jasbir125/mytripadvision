package com.mytripadvision;

import static org.assertj.core.api.Assertions.assertThat;

import com.mytripadvision.client.LlmClient;
import com.mytripadvision.support.AbstractIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/** The app starts with LLM_PROVIDER=bedrock and no API key. The AWS client is built but never called. */
@TestPropertySource(properties = {"mytripadvision.llm.provider=bedrock", "mytripadvision.llm.region=eu-west-1"})
class BedrockWiringIT extends AbstractIT {

    @Autowired ApplicationContext context;
    @Autowired LlmClient llm;

    @Test
    void bedrockClientIsCreated_onlyWhenTheProviderIsBedrock() {
        assertThat(context.getBeansOfType(BedrockRuntimeClient.class)).hasSize(1);
        assertThat(llm).isNotNull();
    }
}
