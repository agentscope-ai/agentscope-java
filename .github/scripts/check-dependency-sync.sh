#!/bin/bash
# Check that agentscope-core's compile dependencies are declared in agentscope-all
# This prevents missing transitive dependencies when users use agentscope-all

set -e

CORE_POM="agentscope-core/pom.xml"
ALL_POM="agentscope-distribution/agentscope-all/pom.xml"

echo "Checking dependency sync between agentscope-core and agentscope-all..."

# Step 1: Extract optional dependencies from agentscope-core/pom.xml
# These should be skipped as they are not required at runtime
# Pattern: find <artifactId>xxx</artifactId> followed by <optional>true</optional>
OPTIONAL_DEPS=$(awk '
    /<dependency>/ { in_dep=1; artifact="" }
    in_dep && /<artifactId>/ { 
        gsub(/.*<artifactId>|<\/artifactId>.*/, ""); 
        artifact=$0 
    }
    in_dep && /<optional>true<\/optional>/ { 
        if (artifact != "") print artifact 
    }
    /<\/dependency>/ { in_dep=0 }
' "$CORE_POM")

echo "Optional dependencies (skipped): $(echo $OPTIONAL_DEPS | tr '\n' ' ')"

# Step 2: Get direct compile dependencies of agentscope-core
# Output format: "groupId:artifactId:type:version:scope"
# Filter: only :compile scope, exclude io.agentscope (shaded into jar)
CORE_DEPS=$(mvn dependency:list -pl agentscope-core \
    -DexcludeTransitive=true \
    -DoutputAbsoluteArtifactFilename=false \
    2>/dev/null \
    | grep ":compile" \
    | grep -v "io.agentscope" \
    | sed 's/^\[INFO\][[:space:]]*//' \
    | sed 's/^[[:space:]]*//' \
    || true)

if [ -z "$CORE_DEPS" ]; then
    echo "Warning: No compile dependencies found in agentscope-core"
    exit 0
fi

# Step 3: For each dependency, extract artifactId and check if in agentscope-all
# Skip optional dependencies
MISSING=()

while IFS= read -r dep; do
    # Extract artifactId (2nd field, split by :)
    # Example: com.fasterxml.jackson.core:jackson-databind:jar:2.20.1:compile
    artifactId=$(echo "$dep" | cut -d: -f2)

    if [ -n "$artifactId" ]; then
        # Skip if this is an optional dependency
        if echo "$OPTIONAL_DEPS" | grep -q "^${artifactId}$"; then
            continue
        fi

        # Check if artifactId exists in agentscope-all pom.xml
        if ! grep -q "<artifactId>${artifactId}</artifactId>" "$ALL_POM"; then
            MISSING+=("$dep")
        fi
    fi
done <<< "$CORE_DEPS"

# Step 3: Report results
if [ ${#MISSING[@]} -gt 0 ]; then
    echo ""
    echo "Failed: Missing dependencies in agentscope-all/pom.xml:"
    echo ""
    for m in "${MISSING[@]}"; do
        artifactId=$(echo "$m" | cut -d: -f2)
        groupId=$(echo "$m" | cut -d: -f1)
        echo "   <dependency>"
        echo "       <groupId>${groupId}</groupId>"
        echo "       <artifactId>${artifactId}</artifactId>"
        echo "   </dependency>"
        echo ""
    done
    echo "Please add the above dependencies to: $ALL_POM"
    exit 1
fi

echo "Success: All agentscope-core dependencies are synced to agentscope-all"