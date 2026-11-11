package com.mytripadvision.support;

import com.mytripadvision.rag.EmbeddingClient;
import java.util.List;
import java.util.Locale;

/** Deterministic bag-of-words embeddings: the same words give the same vector, no model is downloaded. */
public class FakeEmbeddingClient implements EmbeddingClient {

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(FakeEmbeddingClient::vector).toList();
    }

    static float[] vector(String text) {
        float[] v = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) {
                v[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            v[0] = 1f;
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] /= (float) norm;
        }
        return v;
    }
}
