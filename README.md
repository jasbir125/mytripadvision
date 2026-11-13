# MyTripAdvision Agent (mytripadvision)

**What is this?** MyTripAdvision Agent is a travel planner. You describe a trip in plain words ("7 days in Tokyo from Delhi under 2 lakhs"), and a team of small helpers (called agents) looks up flights, hotels and weather, estimates the cost and writes a day-by-day plan. You can approve the draft or ask for changes before you get the final answer. This service is the complete Java (Spring Boot) port of the original Python app (FastAPI, LangGraph, MCP). It serves the same web page and the same API, and it contains no Python and no MCP: it calls the LLM, AviationStack, Tavily and OpenWeather directly over HTTP.

- Version: `1.0.0` (semantic versioning), Spring Boot 4.1.2, Java 21 or newer, Maven.
- Owns: chat threads and messages, the paused plan of a thread, embedded hotel search results, the embedded knowledge base. Details and diagrams: [docs/architecture.md](docs/architecture.md).
- No events are published or consumed (no Kafka, so no `docs/events.md`).

```mermaid
flowchart LR
    Browser["Web UI"] --> App["mytripadvision"]
    App --> PG[("PostgreSQL + pgvector")]
    App --> LLM["LLM (Groq or OpenAI)"]
    App --> AVS["AviationStack"]
    App --> TAV["Tavily"]
    App --> OWM["OpenWeather"]
```

## Run it locally

Prerequisites: JDK 21 or newer, Docker (for the database and for the tests). Maven is not needed; use the wrapper.

1. Start the database (PostgreSQL with the pgvector extension, on port 15432):
   ```bash
   docker compose up -d postgres
   ```
2. Create your settings file and fill in the keys (see [.env.example](.env.example) for the names; the file is never committed):
   ```bash
   cp .env.example .env
   ```
3. Run the service. Spring Boot does not read `.env` itself, so load it into the shell first:
   ```bash
   set -a; source .env; set +a
   ./mvnw spring-boot:run
   ```
   Without `DATABASE_URL` the service uses the compose database (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; the local password is `mytripadvision-local`, set `DB_PASSWORD` to it).
4. Open <http://localhost:8000>.

When you run the jar yourself, the first start downloads the embedding model (about 130 MB) from Hugging Face into `EMBEDDING_CACHE_DIR` and indexes the knowledge base. The Docker image already contains the model (it is downloaded while the image is built), so a new container only indexes the knowledge base, which takes a few seconds; until that finishes the readiness probe reports `OUT_OF_SERVICE`. Later starts take a few seconds.

Everything in containers (database and service): `docker compose --profile app up --build`.

Example calls:

```bash
curl -s localhost:8000/api/travel -H 'Content-Type: application/json' \
  -d '{"message": "Plan 5 days in Lisbon under 1500 EUR", "user_id": "demo"}'
# status "needs_review" -> approve it (use the thread_id from the answer)
curl -s localhost:8000/api/travel/review -H 'Content-Type: application/json' \
  -d '{"thread_id": "<thread_id>", "user_id": "demo", "action": "approve"}'
```

## Configuration

Every value is read from the environment; nothing secret is stored in the repository. Typed in `config/AppProperties.java`, defaults in `src/main/resources/application.yml`.

| Property | Env variable | Default | Description |
|----------|--------------|---------|-------------|
| `mytripadvision.database.url` | `DATABASE_URL` | empty | `postgresql://user:pass@host:port/db`; converted to JDBC, `sslmode=require` is added when missing (not for localhost). Takes precedence over `DB_*` |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:15432/mytripadvision` | JDBC URL when `DATABASE_URL` is not set |
| `spring.datasource.username` | `DB_USERNAME` | `mytripadvision` | database user |
| `spring.datasource.password` | `DB_PASSWORD` | empty | database password (**required** in production; compose uses a local-only value) |
| `mytripadvision.llm.provider` | `LLM_PROVIDER` | `groq` | `openai`, `groq` or `bedrock` (AWS, see below) |
| `mytripadvision.llm.model` | `LLM_MODEL` | empty = the provider's `model` under `mytripadvision.llm.providers` | override for one run; for bedrock a model id or an inference profile id |
| `mytripadvision.llm.final-model` | `LLM_FINAL_MODEL` | empty = the provider's `final-model` under `mytripadvision.llm.providers` | override for the stronger model used only for the final answer |
| `mytripadvision.llm.providers` | - | openai `gpt-4o-mini` / `gpt-4o`, groq `openai/gpt-oss-120b`, bedrock `us.amazon.nova-lite-v1:0` / `us.amazon.nova-pro-v1:0` | **all AI models in one place**: each provider's `model` (agents) and `final-model` (final answer) |
| `mytripadvision.llm.prices` | - | see `application.yml` | USD per 1M tokens per model, for the cost shown under answers; unlisted models count as free |
| `mytripadvision.llm.region` | `AWS_REGION` | `us-east-1` | AWS region, only for `bedrock` |
| `mytripadvision.llm.groq-api-key` | `GROQ_API_KEY` | empty | **required** when the provider is `groq` |
| `mytripadvision.llm.openai-api-key` | `OPENAI_API_KEY` | empty | **required** when the provider is `openai` |
| (none) | AWS credentials | AWS default chain | `bedrock` has no key setting: the AWS SDK finds credentials itself (ECS task role, `AWS_*` variables or your profile). They are never read or logged by the app |
| `mytripadvision.aviationstack.api-key` | `AVIATIONSTACK_API_KEY` | empty | live flights (free plan, `/flights` only). Without it the flight agent answers with estimates |
| `mytripadvision.tavily.api-key` | `TAVILY_API_KEY` | empty | hotel web search; **required** for hotel requests |
| `mytripadvision.openweather.api-key` | `OPENWEATHER_API_KEY` | empty | weather; **required** for weather requests |
| `mytripadvision.workflow.human-review` | `HUMAN_REVIEW` | `on` | `off` skips the approval step |
| `mytripadvision.workflow.default-origin-iata` | `DEFAULT_ORIGIN_IATA` | empty | read for parity with the Python `.env`; not used by the workflow (the Python code does not use it either) |
| `mytripadvision.rag.knowledge-location` | `KNOWLEDGE_LOCATION` | `classpath:knowledge_base` | folder of `.md` guides (`file:/path` also works) |
| `mytripadvision.rag.embedding.cache-dir` | `EMBEDDING_CACHE_DIR` | `${java.io.tmpdir}/mytripadvision-models` | where the embedding model is stored |
| `server.port` | `PORT` | `8000` | HTTP port |

Fixed in `application.yml` (change there if needed): provider base URLs, connect and read timeouts per provider, RAG score threshold `0.66`, chunk size `700`/overlap `100`, hotel top-k `6`, knowledge top-k `4`, retention of search chunks `24h`, revision limit `2`, summary every `2` questions.

### Running from IntelliJ (no environment variables)

All settings are in `src/main/resources/application.yml` with working local defaults (local database, `openai`
with `gpt-4o-mini` for the agents and `gpt-4o` for the final answer, chosen by the provider). To run it:

1. `docker compose up -d postgres`
2. Copy `config/local-secrets.example.yml` to `config/local-secrets.yml` and add your API keys.
   That file is git-ignored and is never copied into the Docker image. `application.yml` loads it automatically.
3. Run `com.mytripadvision.MyTripAdvisionApplication` (debug works as usual).

To switch provider or model, edit the `mytripadvision.llm` block in `application.yml` (the comments list the values for
`groq` and `bedrock`). An environment variable of the same name still overrides the file, which is how Docker and
AWS configure it.

## Using AWS Bedrock as the LLM

Set `LLM_PROVIDER=bedrock` (and `AWS_REGION` if it is not `us-east-1`). The app calls the Bedrock Converse API with the AWS SDK for Java v2.

- Local emulator: set `BEDROCK_ENDPOINT=http://localhost:4566` (LocalStack or Floci) with `AWS_ACCESS_KEY_ID=test` and `AWS_SECRET_ACCESS_KEY=test` to run the Bedrock path without AWS. Floci's always-free Bedrock returns a fixed stub sentence for every prompt, so it proves the wiring, not the plans.
- Models: `LLM_MODEL` defaults to `us.amazon.nova-lite-v1:0` and `LLM_FINAL_MODEL` to `us.amazon.nova-pro-v1:0`, so the final answer uses the stronger model. Any Bedrock model id or cross-region inference profile id works (the `us.` prefix is an inference profile).
- IAM: the task role (or your local identity) needs `bedrock:InvokeModel` on the model, and also on the inference profile when the id starts with `us.`. For a cross-region profile the permission is also needed on the foundation model in each region the profile can route to.
- Model access: Amazon Nova models work out of the box. Anthropic models need the one-time use-case form in the Bedrock console first.
- Errors: a failed call becomes `503` and the log shows the short AWS error code, for example `llm: AccessDeniedException` or `llm: ThrottlingException`. Throttling, 5xx and connection errors are retried once. The message never contains request text, account ids or ARNs.
- Cost: `token_usage.cost_usd` is an estimate for Nova Lite (0.06 / 0.24 USD per 1M tokens in / out) and Nova Pro (0.80 / 3.20); check the AWS price list.

## API

The paths stay under `/api` (not `/api/v1`) because the bundled web page calls them. JSON uses snake_case.

| Method and path | What it does | Success | Errors |
|-----------------|--------------|---------|--------|
| `POST /api/travel` | New question on a new or existing thread | `200` `{success, status, agent, thread_id, answer, flight_results, hotel_results, itinerary, budget_analysis, trip_constraints, llm_calls, request_id, plan, notices, token_usage}`; `status` is `done` or `needs_review`; `agent` is who wrote the answer (`guardrails`, `supervisor`, `itinerary` or `final`); `notices` are one-line warnings about a service that was down or out of quota | `400` validation, `404` thread of another user, `503` a provider is unavailable, `500` |
| `POST /api/travel/review` | `{thread_id, user_id, action: approve\|changes, feedback}` for a plan that waits | same body as above | `400`, `409` nothing waits for review / another request changed the chat, `503` |
| `GET /api/threads?user_id=` | The user's chats (50 newest) as `{threads: [{thread_id, updated_at, title}]}` | `200` | `400` |
| `GET /api/threads/{id}/messages?user_id=` | `{messages: [{role, content, meta, created_at}]}` | `200` | `400` |
| `DELETE /api/threads/{id}?user_id=` | Deletes the chat and its messages | `200` `{success: true}` | `400` |
| `GET /health` | `{status: "ok", ...}` | `200` | |
| `GET /` and `/static/**` | The web page | `200` | |
| `GET /actuator/health/{liveness,readiness}` | Probes (database included in readiness) | `200` | |

All errors are RFC 9457 Problem Details (`application/problem+json`). Two extra fields keep the web page working: `success: false` and `error` (the message it shows). `503` and `500` never contain provider or exception text.

There is no login, as in the Python app: `user_id` is an anonymous id created by the browser, and a thread can only be used by the user that created it. Do not expose the service publicly without adding authentication.

## Tests and quality gates

```bash
./mvnw -B clean verify
```

Runs unit and slice tests (Surefire, `*Test`), integration tests (Failsafe, `*IT`) against a real PostgreSQL with pgvector through Testcontainers (Docker must be running; they are never skipped), the Enforcer rules and the JaCoCo coverage gate (lines at least 70 percent). No test calls a real provider: the four HTTP APIs are stubbed with WireMock and the embedding model is replaced by a deterministic fake, so nothing is downloaded.

## Operations

- Probes: `/actuator/health/liveness` and `/actuator/health/readiness`. Graceful shutdown (30 s) and virtual threads are on.
- Logs: every line carries `[mytripadvision,<correlationId>,req=<requestId>]`. The `X-Correlation-Id` header is accepted (letters, digits, `._:-`, up to 64 characters) or generated, and echoed in the response. `requestId` is a short id per HTTP request; it is also returned as `request_id`, so a log search by it shows everything one request did. Messages use `[component]` prefixes such as `[supervisor]` and `[knowledge]`. Keys, payloads and user questions are never logged.
- Remote calls: each provider has timeouts, and one retry (only for I/O errors, 429 and 5xx). A provider that still fails gives `503`; its error text, URL and key are never passed on. There is no circuit breaker.
- Observability is deliberately simple: request-id log lines, a `took ... ms` line per question, and `token_usage` (prompt, completion, total, `cost_usd`) in every response. The cost is computed for the models listed under `mytripadvision.llm.prices` in `application.yml`; other models count as free.
- Cleanup: search chunks older than 24 hours are deleted hourly (`RagCleanupJob`).

## Differences from the Python app

See the report of the port for the full list. In short: the embedding model runs through ONNX Runtime (same model, `BAAI/bge-small-en-v1.5`); the paused workflow is stored in a table column instead of a LangGraph checkpoint; `llm_calls` counts the calls that were actually made (hotel agent 0, weather agent 1); error responses no longer contain exception text.
