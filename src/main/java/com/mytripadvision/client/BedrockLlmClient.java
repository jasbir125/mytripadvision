package com.mytripadvision.client;

import com.mytripadvision.config.AppProperties;
import com.mytripadvision.config.HttpClients;
import com.mytripadvision.workflow.Usage;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * Amazon Bedrock through the AWS SDK (Converse API). No API key: the SDK finds the credentials itself
 * (ECS task role, environment variables or a profile).
 */
public class BedrockLlmClient implements LlmClient {

    private final AppProperties.Llm config;
    private final BedrockRuntimeClient bedrock;

    public BedrockLlmClient(AppProperties.Llm config, BedrockRuntimeClient bedrock) {
        this.config = config;
        this.bedrock = bedrock;
    }

    @Override
    public String complete(Usage usage, String systemPrompt, String userPrompt) {
        return send(config.resolvedModel(), usage, systemPrompt, userPrompt);
    }

    @Override
    public String completeWithFinalModel(Usage usage, String systemPrompt, String userPrompt) {
        return send(config.resolvedFinalModel(), usage, systemPrompt, userPrompt);
    }

    /** The system prompt is a system block, the user prompt is one user message. */
    private String send(String model, Usage usage, String systemPrompt, String userPrompt) {
        ConverseRequest.Builder request = ConverseRequest.builder()
                .modelId(model)
                .messages(Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText(userPrompt)).build());
        if (systemPrompt != null) {
            request.system(SystemContentBlock.fromText(systemPrompt));
        }
        ConverseRequest converseRequest = request.build();

        ConverseResponse response = HttpClients.callWithRetry("llm", () -> bedrock.converse(converseRequest));
        if (response.usage() != null) {
            usage.add(model, response.usage().inputTokens(), response.usage().outputTokens());
        }
        return textOf(response);
    }

    /** The reply is a list of content blocks; only the text ones are used. */
    private static String textOf(ConverseResponse response) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.output().message().content()) {
            if (block.text() != null) {
                text.append(block.text());
            }
        }
        return text.toString();
    }
}
