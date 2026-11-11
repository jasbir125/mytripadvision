# Architecture

What is this? MyTripAdvision Agent is a travel planner. You describe a trip in plain words; a team of small "agents" (flights, hotels, weather, budget, itinerary) works on it and you get a written plan. Before the final answer you can approve the draft or ask for changes. This service is the Java port of the original Python app and keeps the same web page and the same API.

Names in the diagrams are the names in the code (`src/main/java/com/mytripadvision/...`).

## 1. System context

```mermaid
flowchart LR
    Browser["Web UI (static/index.html)"] -->|"/api/travel, /api/threads"| App["mytripadvision"]
    App -->|JDBC| PG[("PostgreSQL + pgvector")]
    App -->|"chat completions"| LLM["LLM: Groq or OpenAI (external)"]
    App -->|"GET /v1/flights"| AVS["AviationStack (external)"]
    App -->|"POST /search"| TAV["Tavily (external)"]
    App -->|"GET /weather, /forecast"| OWM["OpenWeather (external)"]
    App -->|"model file, first start only"| HF["Hugging Face (external)"]
```

- The browser only talks to this service. Every call to a provider is made from Java over HTTP (no Python, no MCP).
- The database holds chats, paused plans and the two vector tables.
- The embedding model (`BAAI/bge-small-en-v1.5`) runs inside the service; it is downloaded once into `mytripadvision.rag.embedding.cache-dir`.

## 2. Components

```mermaid
flowchart TB
    F["CorrelationIdFilter"] --> TC["TravelController"]
    F --> THC["ThreadController / HomeController"]
    TC --> TS["TravelService"]
    THC --> HS["HistoryService"]
    TS --> WF["TravelWorkflow"]
    TS --> HS
    WF --> AG["Agents: Guardrails, Supervisor, Flight, Hotel, Weather, Budget, Itinerary, Final, MemoryWriter"]
    WF --> PR["CompletableFuture on virtual threads"]
    AG --> CL["Clients: LlmClient, FlightClient, TavilyClient, WeatherClient"]
    CL --> RC["HttpClients (timeouts + one retry)"]
    AG --> RAG["SearchResultsRag, KnowledgeBaseService"]
    RAG --> EMB["EmbeddingClient (OnnxEmbeddingClient)"]
    RAG --> VR["RagChunkRepository, KnowledgeChunkRepository (JdbcClient)"]
    HS --> JR["ChatThreadRepository, ChatMessageRepository (JPA)"]
    ERR["ApiExceptionHandler"] -.-> TC
```

- `TravelService` is deliberately not transactional: the workflow makes slow remote calls, and no database transaction stays open during them. Each save in `HistoryService` is its own short transaction.
- Observability is request-id log lines plus `token_usage` in the response. `CorrelationIdFilter` puts a short request id in the log context; the workflow copies the context into the threads of the parallel agents. A small `Usage` object counts the tokens of one request.

## 3. Workflow of one question

```mermaid
flowchart TD
    G["guardrails"] -->|blocked| END1(["done: reason as answer"])
    G --> S["supervisor"]
    S -->|clarification| END1
    S --> P["flight / hotel / weather in parallel (only the planned ones)"]
    P --> GA["gather"]
    GA -->|"budget planned"| B["budget"]
    GA -->|"no budget, itinerary planned"| IT
    GA -->|"neither planned"| FI
    B --> IT["itinerary (+ knowledge base)"]
    B -->|"no itinerary planned"| FI
    IT --> R{"review"}
    R -->|"human review on and revisions left"| PAUSE(["needs_review: state saved"])
    R -->|"off, or limit reached"| FI["final answer"]
    PAUSE -->|approve| FI
    PAUSE -->|"changes: only the matching agents re-run"| P
    FI --> M["memory writer (every 2nd question)"] --> END2(["done"])
```

What happens, step by step:

1. Guardrails refuse empty, too long (over 1000 characters) or prompt-injection input without any LLM call.
2. The supervisor makes one LLM call: which agents are needed, the trip details, or one clarifying question.
3. Flight, hotel and weather run in parallel on virtual threads; budget and itinerary follow.
4. The draft itinerary is returned with `status: needs_review` and the whole workflow state is stored in `chat_threads.workflow_state`.
5. `POST /api/travel/review` loads that state (any replica, also after a restart) and continues. After two change requests the plan goes out as it is.

## 4. End-to-end sequence

```mermaid
sequenceDiagram
    participant UI as Web UI
    participant API as TravelController
    participant TS as TravelService
    participant WF as TravelWorkflow
    participant DB as PostgreSQL
    participant EXT as LLM and data APIs
    UI->>API: POST /api/travel {message, thread_id, user_id}
    alt blank message
        API-->>UI: 400 Problem Details (error, success=false)
    end
    API->>TS: plan(request)
    TS->>DB: openThread (summary, known trip details)
    TS->>WF: start(state)
    WF->>EXT: supervisor, flight, hotel, weather, budget, itinerary calls
    alt a provider still fails after one retry
        WF-->>TS: ExternalServiceException
        API-->>UI: 503 Problem Details (no provider text)
    end
    WF-->>TS: NEEDS_REVIEW (draft itinerary)
    TS->>DB: saveQuestionTurn (messages + workflow_state)
    API-->>UI: 200 status needs_review
    UI->>API: POST /api/travel/review {action: approve}
    API->>TS: review(request)
    TS->>DB: loadPaused (version N)
    alt nothing paused, or already approved
        API-->>UI: 409 Problem Details
    end
    TS->>WF: resume(state, approve)
    WF->>EXT: final answer (+ summary when due)
    TS->>DB: saveReviewTurn (expects version N)
    alt another request saved first
        API-->>UI: 409 Version conflict
    end
    API-->>UI: 200 status done
```

## 5. Flight agent

```mermaid
sequenceDiagram
    participant FA as FlightAgent
    participant LLM as LlmClient
    participant AV as FlightDataClient (AviationStack)
    FA->>LLM: suggest up to 3 airlines (JSON array)
    par one call per airline
        FA->>AV: GET /flights?airline_name=A&limit=30
    and
        FA->>AV: GET /flights?airline_name=B&limit=30
    end
    alt at least one airline answered
        FA->>LLM: guidance from live flights (2000 chars per airline)
    else no live data
        FA->>LLM: guidance, "numbers are estimates"
        Note over FA: the answer starts with the Note banner, a WARN line is logged
    end
```

## 6. Data model

```mermaid
erDiagram
    chat_threads ||--o{ chat_messages : has
    chat_threads {
        bigint id PK
        varchar thread_id UK "uk_chat_threads_thread_id"
        varchar user_id
        varchar title
        text summary
        jsonb trip_constraints
        varchar status "IDLE or AWAITING_REVIEW"
        jsonb workflow_state "set only while paused"
        bigint version "optimistic lock"
    }
    chat_messages {
        bigint id PK
        bigint thread_pk FK "fk_chat_messages_thread"
        varchar role "user or assistant"
        text content
        jsonb meta
    }
    rag_chunks {
        bigint id PK
        varchar request_id "ix_rag_chunks_request"
        text content
        vector_384 embedding
    }
    knowledge_chunks {
        bigint id PK
        varchar source
        text heading
        text content
        vector_384 embedding "HNSW cosine index"
    }
```

Deleting a thread deletes its messages (cascade).

## 7. Thread lifecycle

```mermaid
stateDiagram-v2
    [*] --> IDLE: first question answered or refused
    [*] --> AWAITING_REVIEW: first question, draft plan saved
    IDLE --> AWAITING_REVIEW: new question ends in a draft plan
    AWAITING_REVIEW --> AWAITING_REVIEW: changes requested (revision 1)
    AWAITING_REVIEW --> IDLE: approve, or the revision limit is reached
    AWAITING_REVIEW --> IDLE: a new question replaces the paused plan
```
