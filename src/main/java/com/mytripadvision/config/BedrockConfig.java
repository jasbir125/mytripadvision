package com.mytripadvision.config;

import java.net.URI;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * The AWS client, created only when LLM_PROVIDER=bedrock. With BEDROCK_ENDPOINT set (a local emulator such as
 * LocalStack or Floci on http://localhost:4566) the client talks to that URL instead of AWS; the emulator
 * still wants credentials, any value in AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY will do.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class BedrockConfig {

    @Bean
    @ConditionalOnProperty(prefix = "mytripadvision.llm", name = "provider", havingValue = "bedrock")
    public BedrockRuntimeClient bedrockRuntimeClient(AppProperties props) {
        AppProperties.Llm llm = props.llm();
        Duration readTimeout = llm.readTimeout();
        var builder = BedrockRuntimeClient.builder()
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
                        .build());
        if (llm.hasBedrockEndpoint()) {
            log.info("[llm] bedrock endpoint override: {} (local emulator, not AWS)", llm.bedrockEndpoint());
            builder.endpointOverride(URI.create(llm.bedrockEndpoint()));
        }
        return builder.build();
    }
}
