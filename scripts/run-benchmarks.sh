#!/bin/bash

# Script to run AgentScope performance benchmarks

# Exit on any error
set -e

echo "AgentScope Performance Benchmark Runner"
echo "======================================"

# Check if Maven is installed
if ! command -v mvn &> /dev/null
then
    echo "Maven is not installed. Please install Maven to run benchmarks."
    exit 1
fi

# Default values
PROFILE="benchmark"
QUICK_TEST=false
THOROUGH_TEST=false

# Parse command line arguments
while [[ $# -gt 0 ]]
do
    key="$1"
    case $key in
        -q|--quick)
        QUICK_TEST=true
        shift
        ;;
        -t|--thorough)
        THOROUGH_TEST=true
        shift
        ;;
        -p|--profile)
        PROFILE="$2"
        shift
        shift
        ;;
        -h|--help)
        echo "Usage: $0 [OPTIONS]"
        echo "Run AgentScope performance benchmarks"
        echo ""
        echo "Options:"
        echo "  -q, --quick     Run quick benchmarks (reduced iterations)"
        echo "  -t, --thorough  Run thorough benchmarks (increased iterations)"
        echo "  -p, --profile   Maven profile to use (default: benchmark)"
        echo "  -h, --help      Show this help message"
        exit 0
        ;;
        *)
        echo "Unknown option: $1"
        echo "Use -h or --help for usage information"
        exit 1
        ;;
    esac
done

# Build the project first
echo "Building project..."
mvn clean compile

# Run benchmarks based on selected options
if [ "$QUICK_TEST" = true ]
then
    echo "Running quick benchmarks..."
    mvn test -P$PROFILE -Dbenchmark.config=quick
elif [ "$THOROUGH_TEST" = true ]
then
    echo "Running thorough benchmarks..."
    mvn test -P$PROFILE -Dbenchmark.config=thorough
else
    echo "Running standard benchmarks..."
    mvn test -P$PROFILE
fi

echo ""
echo "Benchmark execution completed!"
echo "Reports can be found in target/benchmark-reports/"