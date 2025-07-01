#!/usr/bin/env bash

set -eo pipefail

echo "Running test..."
mvn compile
mvn exec:java@RunJdbc
