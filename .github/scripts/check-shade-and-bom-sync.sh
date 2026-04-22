#!/bin/bash
# Check that all extension jar modules are declared in agentscope-all and agentscope-bom
# This prevents missing modules in the shaded uber-jar and BOM
#
# Rules:
# - agentscope-all: Contains non-framework-specific extensions (shaded into uber-jar)
# - agentscope-bom: Contains ALL modules (for version management)

set -e

ALL_POM="agentscope-distribution/agentscope-all/pom.xml"
BOM_POM="agentscope-distribution/agentscope-bom/pom.xml"

echo "Checking module sync between extensions, agentscope-all and agentscope-bom..."
echo ""

# Framework-specific modules that should NOT be included in agentscope-all
# These are integration modules that users should depend on separately
# But they SHOULD be in agentscope-bom for version management
#
# Detection rules (by keyword in module name):
#   - *starter*   : Spring Boot starters
#   - *quarkus*   : Quarkus extensions
#   - *micronaut* : Micronaut extensions
EXCLUDED_KEYWORDS=("starter" "quarkus" "micronaut")

# Two separate lists
MODULES_FOR_ALL=()   # Non-framework extensions -> shade into agentscope-all
MODULES_FOR_BOM=()   # All modules -> version management in BOM
EXCLUDED_MODULES=()  # Track which modules were excluded (for reporting)

# Function to check if module should be excluded from agentscope-all
# Returns 0 (true) if module contains any of the excluded keywords
is_excluded() {
    local module=$1
    for keyword in "${EXCLUDED_KEYWORDS[@]}"; do
        if [[ "$module" == *"$keyword"* ]]; then
            return 0  # true, is excluded
        fi
    done
    return 1  # false, not excluded
}

# Function to extract artifactId from pom.xml (excluding parent's artifactId)
extract_artifact_id() {
    local pom=$1
    awk '
        /<parent>/ { in_parent=1 }
        /<\/parent>/ { in_parent=0; next }
        !in_parent && /<artifactId>/ {
            gsub(/.*<artifactId>/, "")
            gsub(/<\/artifactId>.*/, "")
            print
            exit
        }
    ' "$pom"
}

# Function to check packaging type (default is jar)
get_packaging() {
    local pom=$1
    local packaging=$(grep -o '<packaging>[^<]*</packaging>' "$pom" 2>/dev/null | sed 's/<[^>]*>//g' || echo "jar")
    echo "$packaging"
}

# Scan agentscope-extensions directory for jar modules
echo "Scanning agentscope-extensions/ for jar modules..."
for pom in $(find "agentscope-extensions" -name "pom.xml" -type f); do
    packaging=$(get_packaging "$pom")

    # Skip pom packaging (aggregator modules)
    if [ "$packaging" = "pom" ]; then
        continue
    fi

    artifact_id=$(extract_artifact_id "$pom")

    # Skip if no artifactId found
    if [ -z "$artifact_id" ]; then
        continue
    fi

    # All jar modules go to BOM list
    MODULES_FOR_BOM+=("$artifact_id")

    # Only non-excluded modules with agentscope-extensions-* prefix go to ALL list
    if [[ "$artifact_id" == agentscope-extensions-* ]]; then
        if is_excluded "$artifact_id"; then
            EXCLUDED_MODULES+=("$artifact_id")
        else
            MODULES_FOR_ALL+=("$artifact_id")
        fi
    elif is_excluded "$artifact_id"; then
        # Track non-extension excluded modules (e.g., starters)
        EXCLUDED_MODULES+=("$artifact_id")
    fi
done

# Remove duplicates and sort
MODULES_FOR_ALL=($(printf '%s\n' "${MODULES_FOR_ALL[@]}" | sort -u))
MODULES_FOR_BOM=($(printf '%s\n' "${MODULES_FOR_BOM[@]}" | sort -u))
EXCLUDED_MODULES=($(printf '%s\n' "${EXCLUDED_MODULES[@]}" | sort -u))

# Add agentscope-core to BOM list (it's in a separate directory)
MODULES_FOR_BOM+=("agentscope-core")

echo ""
echo "Modules to check for agentscope-all (${#MODULES_FOR_ALL[@]} extensions):"
for m in "${MODULES_FOR_ALL[@]}"; do
    echo "  - $m"
done

echo ""
echo "Modules to check for agentscope-bom (${#MODULES_FOR_BOM[@]} total):"
for m in "${MODULES_FOR_BOM[@]}"; do
    echo "  - $m"
done

echo ""
echo "Excluded from agentscope-all (detected by keywords: ${EXCLUDED_KEYWORDS[*]}):"
for m in "${EXCLUDED_MODULES[@]}"; do
    echo "  - $m"
done
echo ""

# Check modules against agentscope-all and agentscope-bom
MISSING_IN_ALL=()
MISSING_IN_BOM=()

# Check agentscope-all
for module in "${MODULES_FOR_ALL[@]}"; do
    if ! grep -q "<artifactId>${module}</artifactId>" "$ALL_POM"; then
        MISSING_IN_ALL+=("$module")
    fi
done

# Check agentscope-bom
for module in "${MODULES_FOR_BOM[@]}"; do
    if ! grep -q "<artifactId>${module}</artifactId>" "$BOM_POM"; then
        MISSING_IN_BOM+=("$module")
    fi
done

# Report results
EXIT_CODE=0

if [ ${#MISSING_IN_ALL[@]} -gt 0 ]; then
    echo "=========================================="
    echo "ERROR: Missing in $ALL_POM:"
    echo "=========================================="
    echo ""
    echo "Add the following dependencies to shade into the uber-jar:"
    echo ""
    for m in "${MISSING_IN_ALL[@]}"; do
        echo "    <dependency>"
        echo "        <groupId>io.agentscope</groupId>"
        echo "        <artifactId>${m}</artifactId>"
        echo "        <scope>compile</scope>"
        echo "        <optional>true</optional>"
        echo "    </dependency>"
        echo ""
    done
    EXIT_CODE=1
fi

if [ ${#MISSING_IN_BOM[@]} -gt 0 ]; then
    echo "=========================================="
    echo "ERROR: Missing in $BOM_POM:"
    echo "=========================================="
    echo ""
    echo "Add the following dependencies for version management:"
    echo ""
    for m in "${MISSING_IN_BOM[@]}"; do
        echo "    <dependency>"
        echo "        <groupId>io.agentscope</groupId>"
        echo "        <artifactId>${m}</artifactId>"
        echo "        <version>\${project.version}</version>"
        echo "    </dependency>"
        echo ""
    done
    EXIT_CODE=1
fi

if [ $EXIT_CODE -eq 0 ]; then
    echo "=========================================="
    echo "SUCCESS: All modules are properly synced!"
    echo "=========================================="
    echo ""
    echo "- agentscope-all: ${#MODULES_FOR_ALL[@]} extension modules"
    echo "- agentscope-bom: ${#MODULES_FOR_BOM[@]} total modules"
fi

exit $EXIT_CODE

