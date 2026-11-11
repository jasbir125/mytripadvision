package com.mytripadvision.rag;

import java.util.List;

/** Turns text into 384-dimension vectors. Production uses a local ONNX model; tests use a deterministic fake. */
public interface EmbeddingClient {

    int DIMENSIONS = 384;

    List<float[]> embed(List<String> texts);

    default float[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }
}
