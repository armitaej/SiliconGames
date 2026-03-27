#!/bin/bash
# Sync game files from the web repo into Android assets
# Run this before building to ensure the latest game files are bundled

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$SCRIPT_DIR/app/src/main/assets"

echo "=== SiliconGames Asset Sync ==="
echo "Source:  $REPO_ROOT"
echo "Target:  $ASSETS_DIR"
echo ""

# Copy index.html
cp "$REPO_ROOT/index.html" "$ASSETS_DIR/index.html"
echo "✓ Copied index.html"

# Copy all game HTML files
mkdir -p "$ASSETS_DIR/games"
cp "$REPO_ROOT/games/"*.html "$ASSETS_DIR/games/"
GAME_COUNT=$(ls -1 "$ASSETS_DIR/games/"*.html 2>/dev/null | wc -l)
echo "✓ Copied $GAME_COUNT game files"

echo ""
echo "=== Sync complete! ==="
echo "You can now build the app with: ./gradlew assembleRelease"
