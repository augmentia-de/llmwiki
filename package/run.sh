#!/usr/bin/env bash
#
# run.sh — Startet ScrWiki (Quarkus JAR)
#
# Usage:
#   ./run.sh                    # Startet mit Standard-Port 8080
#   PORT=9090 ./run.sh          # Startet mit Custom-Port
#   LLM_CHAT_API_KEY=xxx ./run.sh  # Mit LLM API Key
#
# Wiki-Daten werden im ./wiki-data Verzeichnis gespeichert.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ─── JAR finden ───
JAR_FILE=""
for f in srcwiki-*runner.jar scrwiki-*runner.jar srcwiki-*.jar scrwiki-*.jar; do
    if [ -f "$f" ]; then
        JAR_FILE="$f"
        break
    fi
done

if [ -z "$JAR_FILE" ]; then
    echo "❌ Kein scrwiki-*.jar gefunden."
    echo "   Bitte zuerst: ./build.sh"
    exit 1
fi

echo "📦 JAR: $JAR_FILE"

# ─── Wiki-Data Verzeichnis ───
if [ ! -d "wiki-data" ]; then
    mkdir -p wiki-data/wiki/{entities,concepts,projects,technologies,sources,analyses}
    mkdir -p wiki-data/raw/assets
    echo "📁 Wiki-Data Verzeichnis erstellt: ./wiki-data"
fi

# ─── .env laden (falls vorhanden) ───
if [ -f ".env" ]; then
    echo "📄 .env geladen"
    set -a
    source .env
    set +a
fi

# ─── Defaults ───
export QUARKUS_HTTP_PORT="${PORT:-8080}"
# Prod-Mode: wiki-data relativ zum Startverzeichnis
export LLMWIKI_BASE_DIR="./wiki-data"
export LLMWIKI_RAW_DIR="./wiki-data/raw"
export LLMWIKI_WIKI_DIR="./wiki-data/wiki"
export LLMWIKI_PROJECTS_DIR="./wiki-data/projects"

# ─── Start ───
echo "🚀 Starte ScrWiki auf Port $QUARKUS_HTTP_PORT ..."
echo "   Wiki: $LLMWIKI_WIKI_DIR"
echo "   http://localhost:$QUARKUS_HTTP_PORT"
echo ""

exec java -jar "$JAR_FILE"
