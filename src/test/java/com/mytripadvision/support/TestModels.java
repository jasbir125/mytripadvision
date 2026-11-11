package com.mytripadvision.support;

import com.mytripadvision.config.AppProperties;
import java.util.List;
import java.util.Map;

/** The model and price settings of application.yml, for tests that build the LLM settings by hand. */
public final class TestModels {

    public static final Map<String, AppProperties.Llm.Models> PROVIDERS = Map.of(
            "openai", new AppProperties.Llm.Models("gpt-4o-mini", "gpt-4o"),
            "groq", new AppProperties.Llm.Models("openai/gpt-oss-120b", ""),   // no final model: the normal one is used
            "bedrock", new AppProperties.Llm.Models("us.amazon.nova-lite-v1:0", "us.amazon.nova-pro-v1:0"));

    public static final List<AppProperties.Llm.Price> PRICES = List.of(
            new AppProperties.Llm.Price("gpt-4o-mini", 0.15, 0.60),
            new AppProperties.Llm.Price("gpt-4o", 2.50, 10.00),
            new AppProperties.Llm.Price("amazon.nova-lite-v1:0", 0.06, 0.24),
            new AppProperties.Llm.Price("amazon.nova-pro-v1:0", 0.80, 3.20));

    private TestModels() {
    }
}
