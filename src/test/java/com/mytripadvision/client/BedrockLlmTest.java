package com.mytripadvision.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mytripadvision.config.AppProperties;
import com.mytripadvision.config.LlmConfig;
import com.mytripadvision.exception.ExternalServiceException;
import com.mytripadvision.workflow.Usage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;

/** BedrockLlmClient with a fake AWS client: nothing here calls AWS. */
class BedrockLlmTest {

    private final BedrockRuntimeClient aws = mock(BedrockRuntimeClient.class);

    private static AppProperties.Llm config(String model, String finalModel, String region) {
        return new AppProperties.Llm("bedrock", model, finalModel, region, "", "", "http://x", "http://x",
                Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private LlmClient llm(String model, String finalModel, String region) {
        return new BedrockLlmClient(config(model, finalModel, region), aws);
    }

    private LlmClient llm() {
        return llm(null, null, null);
    }

    private static ConverseResponse reply(String text, int in, int out) {
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(Message.builder().role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText(text)).build()).build())
                .usage(TokenUsage.builder().inputTokens(in).outputTokens(out).totalTokens(in + out).build())
                .build();
    }

    private static <T extends software.amazon.awssdk.awscore.exception.AwsServiceException.Builder> T details(T builder,
            int status, String code, String message) {
        builder.statusCode(status).message(message).awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build());
        return builder;
    }

    private static ThrottlingException throttling() {
        return (ThrottlingException) details(ThrottlingException.builder(), 429, "ThrottlingException", "slow down").build();
    }

    // ---- reply, tokens, request shape

    @Test
    void reply_isReadFromTheTextBlocks_andTokensAreAddedToUsage() {
        when(aws.converse(any(ConverseRequest.class))).thenReturn(reply("Hello from Nova", 1_000_000, 1_000_000));
        Usage usage = new Usage();

        String text = llm().complete(usage, "be brief", "say hello");

        assertThat(text).isEqualTo("Hello from Nova");
        assertThat(usage.asMap()).containsEntry("prompt", 1_000_000L).containsEntry("completion", 1_000_000L);
        assertThat(usage.cost()).isEqualTo(0.30);   // nova-lite: 0.06 in + 0.24 out per million
    }

    @Test
    void request_hasTheSystemBlock_oneUserMessage_andTheModelId() {
        when(aws.converse(any(ConverseRequest.class))).thenReturn(reply("ok", 1, 1));

        llm().complete(new Usage(), "be brief", "say hello");

        ArgumentCaptor<ConverseRequest> sent = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(aws).converse(sent.capture());
        assertThat(sent.getValue().modelId()).isEqualTo("us.amazon.nova-lite-v1:0");
        assertThat(sent.getValue().system()).hasSize(1);
        assertThat(sent.getValue().system().get(0).text()).isEqualTo("be brief");
        assertThat(sent.getValue().messages()).hasSize(1);
        assertThat(sent.getValue().messages().get(0).role()).isEqualTo(ConversationRole.USER);
        assertThat(sent.getValue().messages().get(0).content().get(0).text()).isEqualTo("say hello");
    }

    @Test
    void withoutASystemPrompt_noSystemBlockIsSent() {
        when(aws.converse(any(ConverseRequest.class))).thenReturn(reply("ok", 1, 1));

        llm().complete(new Usage(), null, "hi");

        ArgumentCaptor<ConverseRequest> sent = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(aws).converse(sent.capture());
        assertThat(sent.getValue().hasSystem()).isFalse();
    }

    // ---- model selection

    @Test
    void normalCalls_useTheModel_andTheFinalAnswerUsesTheStrongerDefault() {
        when(aws.converse(any(ConverseRequest.class))).thenReturn(reply("ok", 1, 1));
        LlmClient llm = llm();

        llm.complete(new Usage(), null, "normal");
        llm.completeWithFinalModel(new Usage(), null, "final");

        ArgumentCaptor<ConverseRequest> sent = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(aws, times(2)).converse(sent.capture());
        assertThat(sent.getAllValues().get(0).modelId()).isEqualTo("us.amazon.nova-lite-v1:0");
        assertThat(sent.getAllValues().get(1).modelId()).isEqualTo("us.amazon.nova-pro-v1:0");
    }

    @Test
    void configuredModelIds_winOverTheDefaults() {
        when(aws.converse(any(ConverseRequest.class))).thenReturn(reply("ok", 1, 1));
        LlmClient llm = llm("my.model-a", "my.model-b", null);

        llm.complete(new Usage(), null, "normal");
        llm.completeWithFinalModel(new Usage(), null, "final");

        ArgumentCaptor<ConverseRequest> sent = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(aws, times(2)).converse(sent.capture());
        assertThat(sent.getAllValues()).extracting(ConverseRequest::modelId).containsExactly("my.model-a", "my.model-b");
    }

    @Test
    void finalModelCost_usesTheNovaProPrice() {
        when(aws.converse(any(ConverseRequest.class))).thenReturn(reply("ok", 1_000_000, 1_000_000));
        Usage usage = new Usage();

        llm().completeWithFinalModel(usage, null, "final");

        assertThat(usage.cost()).isEqualTo(4.00);   // 0.80 + 3.20
    }

    // ---- errors and retries

    @Test
    void throttling_isRetriedOnce_thenSucceeds() {
        when(aws.converse(any(ConverseRequest.class))).thenThrow(throttling()).thenReturn(reply("second try", 1, 1));

        assertThat(llm().complete(new Usage(), null, "hi")).isEqualTo("second try");
        verify(aws, times(2)).converse(any(ConverseRequest.class));
    }

    @Test
    void throttlingTwice_failsWithTheShortCode() {
        when(aws.converse(any(ConverseRequest.class))).thenThrow(throttling());

        assertThatThrownBy(() -> llm().complete(new Usage(), null, "hi"))
                .isInstanceOf(ExternalServiceException.class).hasMessage("llm: ThrottlingException").hasNoCause();
        verify(aws, times(2)).converse(any(ConverseRequest.class));
    }

    @Test
    void serviceUnavailable_isRetried() {
        var unavailable = (ServiceUnavailableException) details(ServiceUnavailableException.builder(), 503,
                "ServiceUnavailableException", "try later").build();
        when(aws.converse(any(ConverseRequest.class))).thenThrow(unavailable).thenReturn(reply("back", 1, 1));

        assertThat(llm().complete(new Usage(), null, "hi")).isEqualTo("back");
    }

    @Test
    void timeoutsAndConnectionErrors_areRetried_andReportedWithoutDetails() {
        when(aws.converse(any(ConverseRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: bedrock-runtime.us-east-1.amazonaws.com"));

        assertThatThrownBy(() -> llm().complete(new Usage(), null, "hi"))
                .hasMessage("llm: timeout or connection error");
        verify(aws, times(2)).converse(any(ConverseRequest.class));
    }

    @Test
    void accessDenied_isNotRetried_andTheMessageHoldsOnlyTheShortCode() {
        var denied = (AccessDeniedException) details(AccessDeniedException.builder(), 403, "AccessDeniedException",
                "User: arn:aws:sts::123456789012:assumed-role/task is not authorized to perform bedrock:InvokeModel").build();
        when(aws.converse(any(ConverseRequest.class))).thenThrow(denied);

        assertThatThrownBy(() -> llm().complete(new Usage(), null, "hi"))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessage("llm: AccessDeniedException")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("arn:").doesNotContain("123456789012"))
                .hasNoCause();
        verify(aws, times(1)).converse(any(ConverseRequest.class));
    }

    @Test
    void anOddErrorCode_isReplacedByTheHttpStatus() {
        var odd = (AccessDeniedException) details(AccessDeniedException.builder(), 403,
                "arn:aws:iam::123456789012:role/x", "denied").build();
        when(aws.converse(any(ConverseRequest.class))).thenThrow(odd);

        assertThatThrownBy(() -> llm().complete(new Usage(), null, "hi")).hasMessage("llm: HTTP 403");
    }

    @Test
    void aReplyWithoutText_givesAnEmptyAnswer() {
        when(aws.converse(any(ConverseRequest.class))).thenReturn(ConverseResponse.builder()
                .output(ConverseOutput.builder().message(Message.builder().role(ConversationRole.ASSISTANT)
                        .content(java.util.List.<ContentBlock>of()).build()).build()).build());

        assertThat(llm().complete(new Usage(), null, "hi")).isEmpty();
    }

    // ---- configuration

    @Test
    void bedrockDefaults_areNovaLite_forTheModel_andNovaPro_forTheFinalAnswer_inUsEast1() {
        var config = new AppProperties.Llm("Bedrock", null, "", " ", "", "", "http://x", "http://x",
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThat(config.isBedrock()).isTrue();
        assertThat(config.resolvedModel()).isEqualTo("us.amazon.nova-lite-v1:0");
        assertThat(config.resolvedFinalModel()).isEqualTo("us.amazon.nova-pro-v1:0");
        assertThat(config.resolvedRegion()).isEqualTo("us-east-1");
    }

    @Test
    void bedrockSettings_canBeOverridden() {
        var config = new AppProperties.Llm("bedrock", "a", "b", "eu-west-1", "", "", "http://x", "http://x",
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThat(config.resolvedModel()).isEqualTo("a");
        assertThat(config.resolvedFinalModel()).isEqualTo("b");
        assertThat(config.resolvedRegion()).isEqualTo("eu-west-1");
    }

    @Test
    void otherProviders_keepTheirDefaults_groqUsesOneModel_openaiUsesGpt4oForTheFinalAnswer() {
        var groq = new AppProperties.Llm("groq", null, null, null, "", "k", "http://x", "http://x",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        var openai = new AppProperties.Llm("openai", null, null, null, "k", "", "http://x", "http://x",
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThat(groq.resolvedModel()).isEqualTo("openai/gpt-oss-120b");
        assertThat(groq.resolvedFinalModel()).isEqualTo("openai/gpt-oss-120b");
        assertThat(openai.resolvedModel()).isEqualTo("gpt-4o-mini");
        assertThat(openai.resolvedFinalModel()).isEqualTo("gpt-4o");
    }

    @Test
    void bedrockStartup_needsNoApiKey() {
        var props = new AppProperties(config(null, null, null), null, null, null, null, null);

        assertThatCode(() -> new LlmConfig().llmClient(props, aws)).doesNotThrowAnyException();
        assertThat(new LlmConfig().llmClient(props, aws)).isInstanceOf(BedrockLlmClient.class);
    }

    @Test
    void novaPrices_areAlsoFoundWithoutTheUsPrefix() {
        Usage usage = new Usage();

        usage.add("amazon.nova-lite-v1:0", 1_000_000, 0);
        usage.add("us.amazon.nova-pro-v1:0", 0, 1_000_000);
        usage.add("some.unknown-model", 5_000_000, 5_000_000);

        assertThat(usage.cost()).isEqualTo(3.26);   // 0.06 + 3.20, unknown model is free
    }
}
