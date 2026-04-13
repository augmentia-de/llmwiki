# LLM Wiki — Karpathy Pattern

> **⚠️ TEST PROJECT — Work in Progress**
>
> This is an **experimental test project** exploring Andrej Karpathy's LLM wiki idea.
> It is **incomplete**, not production-ready, and serves as a technological feasibility study.
> Some features are only partially implemented or missing entirely (see [Status](#status--missing-features)).

---

A **persistent, knowledge-accumulating wiki server** based on [Andrej Karpathy's LLM Wiki idea](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f).

Instead of starting from scratch on every query (like RAG), this app **reads, synthesizes, and cross-links** sources incrementally — building a persistent wiki that grows smarter over time.

> *"Ask a subtle question that requires synthesizing five documents, and the LLM has to find and piece together the relevant fragments every time. Nothing is built up."*
> — Andrej Karpathy

---

## Status & Missing Features

**This project is a proof of concept.** The following areas are known to be incomplete:

| Area | Status |
|---|---|
| **Contradiction Detection** | Only during ingest, not during lint (as Karpathy originally envisioned) |
| **Human Review Queue** | Not implemented — changes are applied directly |
| **Schema / Agent Doc** | No `CLAUDE.md` / `AGENTS.md` for LLM self-description |
| **Tests** | No meaningful unit or integration tests yet |
| **Auth / Multi-User** | No authentication, no user management |
| **Production Hardening** | No rate limiting, no caching, no monitoring |
| **UI** | Basic Bootstrap SPA — not a full wiki experience yet |

---

## Architecture Advantages

### 1. **No Vector Database — Index-Based Search**

Instead of relying on expensive vector embeddings and specialized databases (Pinecone, Weaviate, Milvus, etc.), this project uses **plain-text index search**:

| Vector-Based (RAG) | This Project (Index-Based) |
|---|---|
| Embedding model locks you in — once chosen, hard to switch | **Model-agnostic** — any LLM can answer any question |
| Embeddings are **computed once** — new model = recalculate all vectors | Text stays **always readable and searchable**, regardless of which model you use |
| Vector DBs cause **ongoing costs** and vendor lock-in | **No extra infrastructure** — just the file system |
| Semantic search is a "black box" — you don't know why something was found | **Transparent** — `index.md` shows exactly which pages exist and how they're linked |

### 2. **Cost Advantages Through Cheap LLMs**

- **Any OpenAI-compatible LLM works** — from free Ollama (local) to GPT-4o
- **Fallback mechanism** built in: when the primary model fails, an alternative model automatically takes over (configurable in `application.properties`)
- **No embedding costs**: With vector-based systems you pay twice — once for embeddings, once for the chat response. Here the embedding part is eliminated entirely
- **Local models possible**: With Ollama the system runs **completely free** and offline

### 3. **Interoperability & Model Freedom**

- **Switch models in seconds**: Just change `LLM_CHAT_MODEL` and `LLM_CHAT_BASE_URL` — done
- **No lock-in**: Your data is Markdown files, not binary vectors. They survive any model switch
- **Multi-provider support**: OpenAI, OpenRouter, Ollama, Azure OpenAI, local models — all possible
- **Future-proof**: When a better, cheaper model appears tomorrow, you change one environment variable

### 4. **Persistent, Growing Knowledge Base**

- **No "reset" per query** like classic RAG — the wiki **learns and grows** with every source
- One source updates **10–30 existing pages**, not just generating a new answer
- **Cross-links** (`[[slug]]`) connect concepts automatically — a growing knowledge-graph-like network
- **Logbook** (`log.md`) chronicles every change

### 5. **Simplicity & Portability**

- **No database** — everything is Markdown files
- **Git-compatible** — the wiki can be versioned, pushed, pulled
- **Single Binary** — Quarkus Native enables a single executable binary
- **MCP Server** integrated — the wiki can be used as a tool by other LLM clients

---

## Inspiration

This project is a direct implementation of the pattern described in **[Andrej Karpathy's Gist: "LLM Wiki"](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f)**.

The core idea: instead of RAG (Retrieval Augmented Generation) where every query re-discovers knowledge from scratch, an LLM **writes and maintains a persistent wiki**. New sources don't just create new pages — they **update existing ones**, refine concepts, note contradictions, and maintain cross-links. The wiki **compounds** over time.

```
Raw Sources  →  Wiki (LLM-generated, persistent)  →  User
(immutable)       (compounding, cross-linked)        (asks, guides)
```

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    REST API (JSON)                           │
│  POST /sources  │  GET/POST/PUT/DELETE /wiki  │  POST /query │
│  GET /wiki/lint │  GET /wiki/files/*          │  GET / (HTML)│
└────────────────────────┬─────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
┌───────────────┐ ┌──────────────┐ ┌──────────────┐
│ IngestService │ │ WikiService  │ │ QueryService │
│ • JSoup       │ │ • CRUD       │ │ • Index-based│
│ • LLM Summary │ │ • Cross-Links│ │   Search     │
│ • Entity Ext. │ │ • index.md   │ │ • LLM Answer │
│ • Contradict. │ │ • log.md     │ │ • Save as Pg │
└───────┬───────┘ └──────┬───────┘ └──────┬───────┘
        │                │                │
        └────────────────┼────────────────┘
                         ▼
              ┌──────────────────────┐
              │   File System (MD)   │
              │ ~/karpathy-wiki/     │
              │ ├── raw/             │
              │ └── wiki/            │
              │     ├── index.md     │
              │     ├── log.md       │
              │     ├── entities/    │
              │     ├── concepts/    │
              │     ├── sources/     │
              │     └── analyses/    │
              └──────────────────────┘
```

**No database.** Everything is plain Markdown files — readable, git-compatible, and portable.

---

## Tech Stack

| Component | Version |
|---|---|
| Java | 21 |
| Quarkus | 3.34.3 |
| LangChain4j | 1.12.2 |
| Quarkus-LangChain4j | 1.8.4 |
| JSoup | 1.18.3 |
| Lombok | 1.18.36 |

---

## Quick Start

### Prerequisites

- **Java 21** (`java -version`)
- **Maven 3.9+**
- **LLM API Key** (OpenAI, OpenRouter, Ollama, etc.)

### 1. Configure

Edit `.env` with your values:

```bash
# LLM Provider
LLM_CHAT_BASE_URL=https://openrouter.ai/api/v1
LLM_CHAT_API_KEY=sk-or-v1-...
LLM_CHAT_MODEL=openai/gpt-4o

# Wiki directories
LLMWIKI_BASE_DIR=${HOME}/karpathy-wiki
LLMWIKI_RAW_DIR=${HOME}/karpathy-wiki/raw
LLMWIKI_WIKI_DIR=${HOME}/karpathy-wiki/wiki

# Server
QUARKUS_HTTP_PORT=8480
```

### 2. Run

```bash
./dev.sh
```

Or manually:

```bash
source .env
mvn quarkus:dev
```

### 3. Open the UI

| | URL |
|---|---|
| Local | `http://localhost:8480` |
| Network | `http://<your-ip>:8480` |

---

## Features

### Ingest — Add a Source

```bash
curl -X POST http://localhost:8480/sources \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/article"}'
```

**What happens:**

1. Fetch URL → JSoup extracts text
2. LLM analyzes content → extracts **summary, entities, concepts, key facts, contradictions, cross-links**
3. Raw source saved to `raw/`
4. **Existing wiki pages are updated** (not recreated) — entities, concepts get new information appended
5. New pages created only for previously unknown entities/concepts
6. Contradictions noted as analysis pages
7. Cross-links (`[[slug]]`) added to related pages
8. `index.md` regenerated
9. `log.md` appended

A single source typically touches **10–30 wiki pages**.

### Query — Ask the Wiki

```bash
curl -X POST http://localhost:8480/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is the wiki pattern?", "saveAsPage": true}'
```

**What happens:**

1. LLM reads `index.md` → identifies relevant pages
2. Full pages loaded → context built
3. LLM synthesizes answer with citations
4. Optionally saved as a new analysis page

### Lint — Health Check

```bash
curl http://localhost:8480/query/lint
```

Checks for:
- **Orphaned pages** (no inbound links)
- **Missing pages** (mentioned slugs without a page)
- **LLM suggestions** for improvements

### File Browser

The web UI includes a file browser sidebar — click any `.md` file (`index.md`, `log.md`, entity pages, etc.) to view its raw content in the browser.

---

## Project Structure

```
llmwiki/
├── pom.xml
├── .env
├── dev.sh
├── README.md
└── src/main/
    ├── java/com/example/llmwiki/
    │   ├── config/
    │   │   └── WikiConfig.java          # @ConfigMapping for wiki dirs
    │   ├── model/
    │   │   ├── WikiPage.java            # Page record + Markdown serialization
    │   │   ├── IndexEntry.java          # Index entry record
    │   │   └── LintReport.java          # Lint result record
    │   ├── service/
    │   │   ├── WikiFileService.java     # File I/O, cross-links, slugify
    │   │   ├── IngestService.java       # Ingest pipeline (Karpathy pattern)
    │   │   ├── QueryService.java        # Query pipeline (index-based)
    │   │   └── LintService.java         # Health check + LLM suggestions
    │   └── resource/
    │       ├── SourceResource.java      # POST /sources
    │       ├── WikiResource.java        # /wiki CRUD + file browser
    │       ├── QueryResource.java       # POST /query, GET /wiki/lint
    │       └── PageResource.java        # GET / → index.html
    └── resources/
        ├── application.properties
        └── META-INF/resources/
            └── index.html               # Web UI (Bootstrap SPA)
```

---

## Wiki Directory Layout

```
~/karpathy-wiki/
├── raw/                          # Immutable raw sources
│   └── assets/
└── wiki/
    ├── index.md                  # Auto-generated index
    ├── log.md                    # Append-only chronicle
    ├── entities/                 # Entity pages (people, orgs, products)
    │   ├── openai.md
    │   └── karpathy.md
    ├── concepts/                 # Concepts, theories, methods
    │   ├── wiki-pattern.md
    │   └── multi-agent-framework.md
    ├── sources/                  # Source summaries
    │   └── source-example-com-article.md
    └── analyses/                 # Query answers, contradictions
        └── contradictions-example.md
```

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Web UI |
| `POST` | `/sources` | Ingest a URL |
| `GET` | `/wiki` | List all pages |
| `GET` | `/wiki/{slug}` | Get single page |
| `GET` | `/wiki/category/{cat}` | Pages by category |
| `POST` | `/wiki` | Create page |
| `PUT` | `/wiki/{slug}` | Update page |
| `DELETE` | `/wiki/{slug}` | Delete page |
| `GET` | `/wiki/index` | Get index.md content |
| `GET` | `/wiki/log` | Get log.md content |
| `GET` | `/wiki/files/tree` | File tree (JSON) |
| `GET` | `/wiki/files/content/{path}` | Get file content |
| `POST` | `/query` | Ask a question |
| `GET` | `/query/lint` | Run health check |

---

## Configuration

| Variable | Description | Default |
|---|---|---|
| `LLM_CHAT_BASE_URL` | LLM API endpoint | `https://api.openai.com/v1` |
| `LLM_CHAT_API_KEY` | LLM API key | *(required)* |
| `LLM_CHAT_MODEL` | Model ID | `gpt-4o` |
| `LLMWIKI_BASE_DIR` | Base directory | `${HOME}/karpathy-wiki` |
| `LLMWIKI_WIKI_DIR` | Wiki directory | `${HOME}/karpathy-wiki/wiki` |
| `QUARKUS_HTTP_PORT` | HTTP port | `8480` |

### Typical Setups

**OpenAI:**
```env
LLM_CHAT_BASE_URL=https://api.openai.com/v1
LLM_CHAT_API_KEY=sk-proj-xxx
LLM_CHAT_MODEL=gpt-4o
```

**OpenRouter:**
```env
LLM_CHAT_BASE_URL=https://openrouter.ai/api/v1
LLM_CHAT_API_KEY=sk-or-v1-xxx
LLM_CHAT_MODEL=openai/gpt-4o
```

**Ollama (local, no API key):**
```env
LLM_CHAT_BASE_URL=http://localhost:11434/v1
LLM_CHAT_API_KEY=ollama
LLM_CHAT_MODEL=llama3.1:8b
```

---

## Dev Mode

```bash
./dev.sh
```

- **Hot Reload** on code changes
- **Debug Port 5005** (JDWP)
- **Log file:** `llmwiki.log` (with rotation)
- **DEBUG logging** for `com.example.llmwiki` and `dev.langchain4j`
- **LLM request/response logging** enabled

---

## How It Differs from Karpathy's Original

| Aspect | Karpathy's Vision | This Implementation |
|---|---|---|
| **Storage** | Markdown files on disk | ✅ Markdown files on disk |
| **Ingest** | One source updates 10–15 existing pages | ✅ Existing pages updated/extended |
| **Cross-links** | `[[slug]]` in Markdown | ✅ Auto-detected and maintained |
| **index.md** | Manually curated + auto-updated | ✅ Auto-regenerated on ingest |
| **log.md** | Append-only, grep-able | ✅ With parseable date prefix |
| **Query** | LLM reads index → finds pages → answers | ✅ Index-based (no embeddings) |
| **Contradictions** | LLM detects during lint | ⚠️ Detected during ingest only |
| **Schema doc** | CLAUDE.md / AGENTS.md | ❌ Not implemented |
| **Human review** | Human reviews each change | ❌ No review queue yet |

---

## License

MIT
