package com.mytripadvision.workflow;

import com.mytripadvision.config.AppProperties;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token counter for ONE request. The workflow creates it and every LLM call adds to it.
 * The prices come from mytripadvision.llm.prices in application.yml; a model that is not listed counts as free.
 */
public class Usage {

    /** USD per 1 million tokens {input, output}, by model id. */
    private final Map<String, double[]> prices = new HashMap<>();

    private long promptTokens;
    private long completionTokens;
    private double cost;

    /** Without a price list every model counts as free. */
    public Usage() {
    }

    public Usage(List<AppProperties.Llm.Price> priceList) {
        if (priceList == null) {
            return;
        }
        for (AppProperties.Llm.Price price : priceList) {
            prices.put(price.model(), new double[] {price.input(), price.output()});
        }
    }

    /** Bedrock inference profile ids start with "us." (us.amazon.nova-lite-v1:0); the price is the same without it. */
    private double[] priceFor(String model) {
        String id = model != null && model.startsWith("us.") ? model.substring(3) : model;
        return prices.get(id);
    }

    /** Agents run in parallel, so this is synchronized. */
    public synchronized void add(String model, int prompt, int completion) {
        promptTokens += prompt;
        completionTokens += completion;
        double[] price = priceFor(model);
        if (price != null) {
            cost += (prompt * price[0] + completion * price[1]) / 1_000_000;
        }
    }

    public synchronized long total() {
        return promptTokens + completionTokens;
    }

    public synchronized double cost() {
        return Math.round(cost * 1_000_000d) / 1_000_000d;
    }

    /** The JSON the web page reads as token_usage. */
    public synchronized Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("prompt", promptTokens);
        map.put("completion", completionTokens);
        map.put("total", promptTokens + completionTokens);
        map.put("cost_usd", cost());
        return map;
    }
}
