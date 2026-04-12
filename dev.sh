#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# .env laden falls vorhanden
if [ -f .env ]; then
    set -a
    source .env
    set +a
fi

# IP-Adresse ermitteln
IP=$(hostname -I | awk '{print $1}')
PORT="${QUARKUS_HTTP_PORT:-8480}"
LOG_FILE="${LLMWIKI_LOG_FILE:-llmwiki.log}"

echo "============================================"
echo "  LLM Wiki — Dev Mode"
echo "============================================"
echo ""
echo "  Lokal:    http://localhost:${PORT}"
echo "  Netzwerk: http://${IP}:${PORT}"
echo ""
echo "  Debug:    Port 5005 (JDWP)"
echo "  Wiki-Dir: ${LLMWIKI_WIKI_DIR:-${HOME}/karpathy-wiki/wiki}"
echo "  Log-File: ${SCRIPT_DIR}/${LOG_FILE}"
echo ""
echo "============================================"
echo ""

# Log-Datei zurücksetzen
> "$LOG_FILE"

# Server starten — Log geht in Datei UND Konsole
mvn quarkus:dev \
    -Dquarkus.http.host=0.0.0.0 \
    -Ddebug=5005 \
    2>&1 | tee -a "$LOG_FILE"
