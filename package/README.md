# ScrWiki — Software Project Wiki

## Quick Start

```bash
# 1. .env bearbeiten
cp .env.example .env
nano .env   # API-Key eintragen

# 2. Starten
./run.sh

# 3. Browser öffnen
http://localhost:8080
```

## Struktur

```
package/
├── scrwiki-1.0.0-SNAPSHOT-runner.jar   # Quarkus Fat JAR
├── run.sh                               # Startskript
├── build.sh                             # Build-Skript (im Projektroot)
├── .env.example                         # Konfigurations-Vorlage
├── .env                                 # Deine Konfiguration (git-ignorieren!)
└── wiki-data/                           # Wird automatisch erstellt
    ├── wiki/
    │   ├── entities/
    │   ├── concepts/
    │   ├── projects/
    │   ├── technologies/
    │   ├── sources/
    │   └── analyses/
    └── raw/
```

## Konfiguration

| Env-Variable | Standard | Beschreibung |
|---|---|---|
| `LLM_CHAT_BASE_URL` | `https://openrouter.ai/api/v1` | LLM Endpoint |
| `LLM_CHAT_API_KEY` | — | Dein API-Key |
| `LLM_CHAT_MODEL` | `openai/gpt-4o-mini` | Modellname |
| `PORT` | `8080` | HTTP Port |
| `LLMWIKI_BASE_DIR` | `./wiki-data` | Wiki Basisverzeichnis |

## Deployment

Einfach das gesamte `package/` Verzeichnis auf den Zielserver kopieren:

```bash
# Auf dem Zielserver
cd /opt/scrwiki
./run.sh
```

Wiki-Daten liegen immer relativ zum Startverzeichnis in `./wiki-data/`.

## Projektanalyse

- **GitHub Repo:** `POST /projects/analyze` mit `{"githubUrl": "https://github.com/user/repo"}`
- **Lokales Projekt:** `{"localPath": "/path/to/project"}`

## Voraussetzungen

- Java 21+
- `git` CLI (für GitHub Repo-Analyse)
