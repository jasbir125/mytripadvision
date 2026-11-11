package com.mytripadvision.config;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/** The AWS client, created only when LLM_PROVIDER=bedrock. */
@Configuration(proxyBeanMethods = false)
public class BedrockConfig {

    @Bean
    @ConditionalOnProperty(prefix = "mytripadvision.llm", name = "provider", havingValue = "bedrock")
    BedrockRuntimeClient bedrockRuntimeClient(AppProperties props) {
        AppProperties.Llm llm = props.llm();
        Duration readTimeout = llm.readTimeout();
        return BedrockRuntimeClient.builder()
                .region(Region.of(llm.resolvedRegion()))
                // ECS task role in production; env variables or your profile on a laptop. We never read keys ourselves.
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(llm.connectTimeout())
                        .socketTimeout(readTimeout)
                        .build())
                // HttpClients.callWithRetry does the (single) retry, so the SDK must not retry as well
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .apiCallTimeout(readTimeout)
                        .build())
                .build();
    }
}
