#!/usr/bin/env bash
# Loads variables from the root .env file into the process environment,
# then starts the Spring Boot backend. Spring Boot does not read .env files
# natively, so this script bridges that gap for local development.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$ROOT_DIR/.env"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  echo "Loaded environment variables from $ENV_FILE"
else
  echo "No .env file found at $ENV_FILE — copy .env.example to .env first."
  echo "Continuing with default values baked into application.yml."
fi

cd "$ROOT_DIR/backend"
mvn spring-boot:run
