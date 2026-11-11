# CLAUDE.md

Travel-planning assistant ("MyTripAdvision Agent" in the UI). This repo is the Java app. A Python original with the same job lives in a separate repo and is not part of this one.

| App | Where | Stack | Port |
| --- | --- | --- | --- |
| Original | separate Python repo (not in this folder; do not edit it) | Python, FastAPI, LangGraph, MCP | 8000 |
| Java port (the one being developed) | this repo root (`pom.xml`, `src/`, `compose.yaml`) | Spring Boot 4.1, Java 21, Maven, PostgreSQL + pgvector | 8000 default, 8001 locally (set in `config/local-secrets.yml`) |

Unless the user says otherwise, "the app", "the server" and "the UI" mean the **Java app**. Do not change the Python app. The Java app is named MyTripAdvision everywhere, with no other brand name left: repo folder `mytripadvision/`, Maven artifact `mytripadvision`, Java package `com.mytripadvision`, main class `MyTripAdvisionApplication`, settings class `AppProperties` with the prefix `mytripadvision.*`, database/user `mytripadvision`, AWS resources named `mytripadvision-*`. The UI says "MyTripAdvision Agent". Do not reintroduce the old name.

Read first: `docs/how-it-works.md` (plain-English design), `docs/architecture.md`, `infra/README.md` (AWS).

## Commands (run in the repo root)

- Database: `docker compose up -d postgres` (file `compose.yaml`; port 15432, database and user `mytripadvision`, password `mytripadvision-local`). `docker compose down -v` wipes the data.
- Run: IntelliJ `MyTripAdvisionApplication` (debug works), or `./mvnw spring-boot:run`. First start downloads a ~130 MB embedding model.
- Tests: `./mvnw -B verify` (needs Docker: Testcontainers with `pgvector/pgvector:pg17`). About 130 unit and 30 integration tests, all must pass; coverage gate 70%.
- All settings live in `src/main/resources/application.yml`. API keys go in `config/local-secrets.yml` (git-ignored, excluded from the Docker image); template: `config/local-secrets.example.yml`.
- AI provider: `mytripadvision.llm.provider` = `openai`, `groq` or `bedrock`. **All model ids and prices live in `application.yml`** (`mytripadvision.llm.providers` and `.prices`), none in Java. Leave `model` and `final-model` empty to get the provider's models from there. The final answer uses a stronger model (`LLM_FINAL_MODEL`).

## How a request flows (agents are in `agent/`, the chain is `workflow/TravelWorkflow`)

Guardrails (no AI) -> Supervisor (1 AI call, returns agents + trip details) -> flight / hotel / weather in parallel -> budget -> itinerary -> pause for human review -> final answer -> memory summary. Only the agents a question needs run; a plan without an itinerary skips the review. After "request changes", `ReviewRouting` re-runs only the agents the words point to. Max 2 revisions; the 2nd change request is final.

RAG: hotel search results (per request, Tavily -> pgvector, top 6) and the Markdown guides in `src/main/resources/knowledge_base/` (top 4, score >= 0.66, used by the itinerary agent). Embeddings run locally (bge-small, 384 dims).

## Rules for changing behaviour

- **Put important rules in Java, not in prompts.** `gpt-4o-mini` ignores conditional prompt text. Measured: the router failed about 4 in 10 simple questions with prompt-only rules. A sentence like "if days are missing, plan 3 days" was followed even when days were given. So code works out the facts (what is missing, whether an origin exists, how many days) and puts only the matching instruction in the prompt: `Texts.missingDetails`, `ItineraryAgent.daysRule`, `BudgetAgent.rules`, `FlightAgent` tasks, `FinalAgent` focused vs full layout.
- Existing code rules: a destination is required (`SupervisorAgent.hasDestination`), never invent days, budget, travelers, dates or a departure city, blank "changes" feedback is a 400, the supervisor never asks about trip details (only non-travel or harmful requests get a `clarification`).
- Before changing the supervisor prompt, measure it against the real model on many cases (fresh requests, follow-ups, off-topic). Wording changes moved mistakes around until the design changed.
- The integration tests stub the AI by matching text in the prompt (`support/ExternalApiStubs`, e.g. "Write the final travel plan", "Answer the user's question using ONLY"). If you change how a prompt starts, update the stub.
- Keep the code simple and readable (the user asked for this): short methods, plain `if`/loops, no thread-locals or framework layers. Never read or print `.env` or `config/local-secrets.yml`; never put keys in tracked files.

## Pitfalls we hit

- **Do not run `./mvnw clean verify` in the repo root while IntelliJ is open.** Both write `target/`, and a jar was built without `BudgetAgent.class`. Build in a scratch copy instead (copy `pom.xml`, `mvnw`, `.mvn`, `src` to a temp folder). Use `--server.port=8002` for a throwaway instance so the user's server on 8001 is untouched.
- IntelliJ serves the page from `target/classes`: after UI edits the user must rebuild and hard-refresh. Java changes need a restart.
- **PDF export:** html2pdf prints a copy of the element, so it must not be hidden or moved off-screen (that gave a blank PDF). `downloadPDF` builds a fresh `.pdf-sheet` in the light theme. Check a PDF by looking at it (`qlmanage -t`), not just that a download happened.
- AviationStack free plan allows only `/flights`, and has a monthly quota (`usage_limit_reached`, HTTP 429). When every airline lookup throws, the flight agent falls back to estimates with a banner and a one-line notice (`WorkflowState.notices`, wording in `agent/Outage`); no banner when the model simply named no airline. The final model drops or rewrites the banner at random, so `FinalAgent.keepFlightBanner` puts it back in code. Hotel and weather outages give a notice the same way. Only an AI-model failure fails the request (503 with a one-line reason). Do not retry a quota error.
- AI and web text is shown through DOMPurify; keep it that way.

## How to verify work

- Tests passing is not enough for UI or prompt changes: drive the real page with headless Chrome (install `playwright-core` in a scratch folder and use `channel: 'chrome'`), look at screenshots (desktop, phone, dark mode), and run the real model several times for prompt changes (it varies).
- Use throwaway user ids (for example `qa-...`) and delete the test chats from the local database afterwards. Do not touch the user's own browser ids.
- Cost awareness: a full trip is about 6 AI calls plus 1 after approval (a few cents). Prefer synthetic page state (`messages = [...]; render()`) for pure UI checks.

## AWS (`infra/`)

Terraform for VPC, RDS PostgreSQL, ECR, Secrets Manager, ECS Fargate behind an ALB, and a task role that may call only the configured Bedrock models (default Amazon Nova; `us.amazon.nova-lite-v1:0` and `-pro-`). Validated offline only: **never applied**, and the AWS CLI credentials were invalid when last checked. `allowed_cidrs` has no default on purpose (the app has no login). `deploy.sh` stops for confirmation. Do not run `terraform apply` or deploy without the user's explicit go-ahead; creating AWS resources costs money.

## Git

Remote: `origin` on GitHub, branch `main`. Do not commit or push unless asked.
