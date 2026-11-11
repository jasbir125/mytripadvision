# Stage 1: the embedding model. The URLs and file names must match mytripadvision.rag.embedding.* in application.yml
# and OnnxEmbeddingClient: it skips the download when these files already exist.
FROM eclipse-temurin:21-jdk AS models
ARG MODEL_URL=https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/onnx/model.onnx
ARG TOKENIZER_URL=https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/tokenizer.json
RUN mkdir -p /models/djl \
 && curl -fsSL --retry 3 -o /models/bge-small-en-v1.5.onnx "$MODEL_URL" \
 && curl -fsSL --retry 3 -o /models/bge-small-en-v1.5-tokenizer.json "$TOKENIZER_URL" \
 && test "$(stat -c %s /models/bge-small-en-v1.5.onnx)" -gt 100000000 \
 && test "$(stat -c %s /models/bge-small-en-v1.5-tokenizer.json)" -gt 100000

# Stage 2: build the jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY .mvn .mvn
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests -Djacoco.skip=true package \
 && java -Djarmode=tools -jar target/mytripadvision-*.jar extract --destination /workspace/extracted

# Stage 3: load the tokenizer once, so DJL downloads its native library now and not when the first task starts
FROM eclipse-temurin:21-jdk AS warm
COPY --from=models /models /models
COPY --from=build /workspace/extracted/lib /workspace/lib
COPY docker/PrewarmTokenizer.java /workspace/PrewarmTokenizer.java
RUN DJL_CACHE_DIR=/models/djl java -cp "/workspace/lib/*" /workspace/PrewarmTokenizer.java /models/bge-small-en-v1.5-tokenizer.json

# Run (a glibc image: ONNX Runtime ships native libraries)
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home mytripadvision
COPY --from=warm --chown=mytripadvision:mytripadvision /models /models
USER mytripadvision
WORKDIR /app
COPY --from=build /workspace/target/mytripadvision-*.jar app.jar
ENV EMBEDDING_CACHE_DIR=/models \
    DJL_CACHE_DIR=/models/djl \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]
