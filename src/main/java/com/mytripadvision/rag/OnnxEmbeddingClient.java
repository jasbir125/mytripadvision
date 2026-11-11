package com.mytripadvision.rag;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.mytripadvision.config.AppProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * BAAI/bge-small-en-v1.5 (the model the Python app uses through fastembed), run locally with ONNX Runtime.
 * Pooling is the model's own: the [CLS] vector, L2-normalised. The model files are downloaded once into the
 * cache directory on first use; nothing is downloaded when the class is only constructed.
 */
@Component
@Slf4j
public class OnnxEmbeddingClient implements EmbeddingClient {

    private final AppProperties.Rag.Embedding config;
    private OrtEnvironment environment;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    public OnnxEmbeddingClient(AppProperties props) {
        this.config = props.rag().embedding();
    }

    @Override
    public synchronized List<float[]> embed(List<String> texts) {
        ensureLoaded();
        List<float[]> vectors = new ArrayList<>();
        try {
            for (String text : texts) {
                vectors.add(embedText(text));
            }
        } catch (OrtException ex) {
            throw new IllegalStateException("Embedding failed", ex);
        }
        return vectors;
    }

    private float[] embedText(String text) throws OrtException {
        Encoding encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();
        long[][] inputIds = {ids};
        long[][] mask = {encoding.getAttentionMask()};
        long[][] types = {new long[ids.length]};
        try (OnnxTensor idTensor = OnnxTensor.createTensor(environment, inputIds);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask);
             OnnxTensor typeTensor = OnnxTensor.createTensor(environment, types)) {
            Map<String, OnnxTensor> inputs = new java.util.HashMap<>();
            inputs.put("input_ids", idTensor);
            inputs.put("attention_mask", maskTensor);
            if (session.getInputNames().contains("token_type_ids")) {
                inputs.put("token_type_ids", typeTensor);
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                float[] cls = hidden[0][0].clone();
                double norm = 0;
                for (float v : cls) {
                    norm += v * v;
                }
                norm = Math.sqrt(norm);
                for (int i = 0; i < cls.length; i++) {
                    cls[i] = (float) (cls[i] / (norm == 0 ? 1 : norm));
                }
                return cls;
            }
        }
    }

    private void ensureLoaded() {
        if (session != null) {
            return;
        }
        try {
            Path dir = Path.of(config.cacheDir());
            Files.createDirectories(dir);
            Path model = download(config.modelUrl(), dir.resolve("bge-small-en-v1.5.onnx"));
            Path tok = download(config.tokenizerUrl(), dir.resolve("bge-small-en-v1.5-tokenizer.json"));
            tokenizer = HuggingFaceTokenizer.newInstance(tok,
                    Map.of("maxLength", String.valueOf(config.maxTokens()), "truncation", "true"));
            environment = OrtEnvironment.getEnvironment();
            session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
            log.info("[embeddings] model loaded from {}", dir);
        } catch (IOException | OrtException ex) {
            throw new IllegalStateException("Could not load the embedding model", ex);
        }
    }

    private Path download(String url, Path target) throws IOException {
        if (Files.exists(target) && Files.size(target) > 0) {
            return target;
        }
        log.info("[embeddings] downloading {}", target.getFileName());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15)).build();
        try {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("download failed with HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted", ex);
        }
    }

    @PreDestroy
    synchronized void close() throws OrtException {
        if (session != null) {
            session.close();
        }
        if (tokenizer != null) {
            tokenizer.close();
        }
    }
}
