#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_ENV="$SCRIPT_DIR/../../.env"

if [ ! -f "$ROOT_ENV" ]; then
    if [ -f "$(pwd)/.env" ]; then
        ROOT_ENV="$(pwd)/.env"
    fi
fi

if [ ! -f "$ROOT_ENV" ]; then
    echo "Error: Root .env file not found. Copy .env.example to .env at repository root and configure local credentials." >&2
    exit 1
fi

ALLOWLIST=("DATABASE_URL" "DATABASE_USERNAME" "DATABASE_PASSWORD" "BACKEND_PORT")
REQUIRED=("DATABASE_URL" "DATABASE_USERNAME" "DATABASE_PASSWORD")

LOADED=()
while IFS='=' read -r key val || [ -n "$key" ]; do
    key=$(echo "$key" | tr -d ' \r\n')
    [[ "$key" =~ ^#.*$ ]] && continue
    [ -z "$key" ] && continue

    val=$(echo "$val" | tr -d '\r\n')
    val="${val#\"}"
    val="${val%\"}"
    val="${val#\'}"
    val="${val%\'}"

    for allowed in "${ALLOWLIST[@]}"; do
        if [ "$key" = "$allowed" ]; then
            export "$key=$val"
            LOADED+=("$key")
            break
        fi
    done
done < "$ROOT_ENV"

for req in "${REQUIRED[@]}"; do
    if [ -z "${!req:-}" ]; then
        echo "Error: Required variable '$req' is missing or empty in .env." >&2
        exit 1
    fi
done

echo "Loaded allowlisted environment variables from .env: ${LOADED[*]}"
# Secrets and passwords are intentionally never printed to the terminal

export SPRING_PROFILES_ACTIVE=dev

cd "$SCRIPT_DIR"
exec ./mvnw spring-boot:run
