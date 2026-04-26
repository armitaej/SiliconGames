#!/usr/bin/env bash
#
# One-shot bootstrap: downloads gradle-wrapper.jar so that ./gradlew works.
#
# Run this ONCE on your Mac after cloning, then delete it (or keep it for
# teammates). After this finishes you can use ./gradlew normally.
#
# Why this exists: the Gradle wrapper is a tiny Java jar that can't easily be
# committed by an AI agent, but it CAN be downloaded over the public internet.
# This script grabs the version that matches gradle/wrapper/gradle-wrapper.properties.
#

set -euo pipefail

cd "$(dirname "$0")"

WRAPPER_DIR="gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
WRAPPER_PROPS="$WRAPPER_DIR/gradle-wrapper.properties"

if [ ! -f "$WRAPPER_PROPS" ]; then
  echo "ERROR: $WRAPPER_PROPS missing. Are you in the project root?" >&2
  exit 1
fi

# Pull the gradle version out of the distributionUrl line:
#   distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
GRADLE_VERSION=$(grep -E '^distributionUrl=' "$WRAPPER_PROPS" \
  | sed -E 's|.*gradle-([0-9]+\.[0-9]+(\.[0-9]+)?)-.*|\1|')

if [ -z "$GRADLE_VERSION" ]; then
  echo "ERROR: could not parse gradle version from $WRAPPER_PROPS" >&2
  exit 1
fi

echo "==> Bootstrapping Gradle wrapper for Gradle $GRADLE_VERSION"

mkdir -p "$WRAPPER_DIR"

# Strategy 1: if gradle is on PATH (homebrew, sdkman, Android Studio's bundled
# gradle), use it to generate a proper wrapper. This is the most reliable path.
if command -v gradle >/dev/null 2>&1; then
  echo "==> Found 'gradle' on PATH — running 'gradle wrapper' (recommended)"
  gradle wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin
  echo "==> Done. Try: ./gradlew --version"
  exit 0
fi

# Strategy 2: try to find Android Studio's bundled gradle.
for as_path in \
  "/Applications/Android Studio.app/Contents/gradle"/gradle-* \
  "$HOME/Library/Android/Sdk/cmdline-tools/latest/bin/gradle" \
  ; do
  for candidate in $as_path; do
    if [ -x "$candidate/bin/gradle" ]; then
      echo "==> Found bundled gradle at $candidate — using it"
      "$candidate/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin
      echo "==> Done. Try: ./gradlew --version"
      exit 0
    fi
  done
done

# Strategy 3: download the wrapper jar directly from Gradle's public CDN.
WRAPPER_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-wrapper.jar"

# Note: Gradle doesn't actually publish a standalone wrapper jar at that path,
# so we have to fall back to extracting it from the full distribution.
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
TMP_ZIP="$(mktemp -t gradle-dist.XXXXXX).zip"
TMP_DIR="$(mktemp -d -t gradle-dist.XXXXXX)"

cleanup() { rm -rf "$TMP_ZIP" "$TMP_DIR"; }
trap cleanup EXIT

echo "==> Downloading $DIST_URL"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL "$DIST_URL" -o "$TMP_ZIP"
elif command -v wget >/dev/null 2>&1; then
  wget -q "$DIST_URL" -O "$TMP_ZIP"
else
  echo "ERROR: need curl or wget on PATH" >&2
  exit 1
fi

echo "==> Extracting gradle-wrapper.jar"
unzip -q "$TMP_ZIP" -d "$TMP_DIR"

# In a Gradle distribution the *runtime* wrapper jar (the one that gets
# invoked by `java -jar gradle-wrapper.jar`) is NOT at lib/gradle-wrapper.jar
# directly. It lives nested inside the plugin module jar:
#   lib/plugins/gradle-wrapper-X.Y.jar  <- this is the plugin module
#     └─ gradle-wrapper.jar             <- this is the runtime wrapper we want
#
# So we have to unzip the plugin jar and pull the inner jar out.
PLUGIN_JAR="$TMP_DIR/gradle-${GRADLE_VERSION}/lib/plugins/gradle-wrapper-${GRADLE_VERSION}.jar"
if [ ! -f "$PLUGIN_JAR" ]; then
  PLUGIN_JAR=$(find "$TMP_DIR" -name 'gradle-wrapper-*.jar' | head -1)
fi
if [ -z "$PLUGIN_JAR" ] || [ ! -f "$PLUGIN_JAR" ]; then
  echo "ERROR: could not find plugin jar inside Gradle distribution" >&2
  exit 1
fi

INNER_DIR="$(mktemp -d -t gradle-inner.XXXXXX)"
unzip -q "$PLUGIN_JAR" -d "$INNER_DIR"
if [ -f "$INNER_DIR/gradle-wrapper.jar" ]; then
  cp "$INNER_DIR/gradle-wrapper.jar" "$WRAPPER_JAR"
else
  # Older Gradle versions: the plugin jar IS the wrapper jar.
  cp "$PLUGIN_JAR" "$WRAPPER_JAR"
fi
rm -rf "$INNER_DIR"

if [ ! -s "$WRAPPER_JAR" ]; then
  echo "ERROR: failed to extract gradle-wrapper.jar" >&2
  exit 1
fi

# Sanity check: the runtime jar must contain GradleWrapperMain AND the
# IDownload class it depends on. If only the first is present we grabbed
# the plugin jar by mistake.
if ! unzip -l "$WRAPPER_JAR" 2>/dev/null | grep -q 'org/gradle/wrapper/Install\.class'; then
  echo "ERROR: extracted jar is missing runtime classes (got the plugin jar?)" >&2
  exit 1
fi

chmod +x ./gradlew 2>/dev/null || true

echo "==> Wrapper installed at $WRAPPER_JAR"
echo "==> Done. Try: ./gradlew --version"
