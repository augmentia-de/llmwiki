#!/usr/bin/env bash
#
# build.sh — Baut das Quarkus JAR und kopiert es ins package/ Verzeichnis
#
# Usage:
#   ./package/build.sh
#
# Nach dem Build:
#   cd package && ./run.sh

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE_DIR="$PROJECT_ROOT/package"

echo "🔨 Baue ScrWiki ..."
cd "$PROJECT_ROOT"

mvn clean package -DskipTests -q

# JAR finden (uber-jar mit -runner Suffix)
JAR_FILE=""
for f in target/srcwiki-*runner.jar target/scrwiki-*runner.jar; do
    if [ -f "$f" ]; then
        JAR_FILE="$f"
        break
    fi
done

if [ -z "$JAR_FILE" ]; then
    # Fallback: normales JAR
    for f in target/srcwiki-*.jar target/scrwiki-*.jar; do
        if [ -f "$f" ] && [[ ! "$f" =~ sources ]] && [[ ! "$f" =~ javadoc ]]; then
            JAR_FILE="$f"
            break
        fi
    done
fi

if [ -z "$JAR_FILE" ]; then
    echo "❌ Kein runner JAR gefunden in target/"
    exit 1
fi

JAR_NAME=$(basename "$JAR_FILE")
cp "$JAR_FILE" "$PACKAGE_DIR/"

echo "✅ JAR kopiert: $PACKAGE_DIR/$JAR_NAME"

# .env erstellen falls nicht vorhanden
if [ ! -f "$PACKAGE_DIR/.env" ]; then
    cp "$PACKAGE_DIR/.env.example" "$PACKAGE_DIR/.env"
    echo "📝 .env erstellt aus .env.example (bitte API-Key eintragen!)"
fi

echo ""
echo "📦 Package bereit in: $PACKAGE_DIR"
echo ""
echo "Starten mit:"
echo "  cd package"
echo "  # .env bearbeiten (API-Key eintragen)"
echo "  ./run.sh"
