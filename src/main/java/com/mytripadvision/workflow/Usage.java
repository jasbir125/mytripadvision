package com.mytripadvision.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/** Token counter for ONE request. The workflow creates it and every LLM call adds to it. */
public class Usage {

    // USD per 1 million tokens {input, output}. These are estimates: check your provider's price list and
    // update them when prices change. A model that is not listed here is counted as free.
    private static final Map<String, double[]> PRICES = Map.of(
            "gpt-4o-mini", new double[] {0.15, 0.60},
            "gpt-4o", new double[] {2.50, 10.00},
            "gpt-4.1", new double[] {2.00, 8.00},
            "gpt-4.1-mini", new double[] {0.40, 1.60},
            "amazon.nova-lite-v1:0", new double[] {0.06, 0.24},
            "amazon.nova-pro-v1:0", new double[] {0.80, 3.20});

    private long promptTokens;
    private long completionTokens;
    private double cost;

    /** Bedrock inference profile ids start with "us." (us.amazon.nova-lite-v1:0); the price is the same without it. */
    private static double[] priceFor(String model) {
        String id = model != null && model.startsWith("us.") ? model.substring(3) : model;
        return PRICES.get(id);
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
