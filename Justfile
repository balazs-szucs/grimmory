set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

mod api 'booklore-api/Justfile'
mod ui 'frontend/Justfile'
mod release 'tools/release/Justfile'

compose_file := env_var_or_default('GRIMMORY_COMPOSE_FILE', 'dev.docker-compose.yml')
compose_cmd := 'docker compose -f ' + compose_file
db_service := 'backend_db'
local_image_tag := env_var_or_default('GRIMMORY_IMAGE_TAG', 'grimmory:local')
local_container_name := 'grimmory-local'
local_db_url := 'jdbc:mariadb://localhost:3366/grimmory?createDatabaseIfNotExist=true'
local_db_user := 'grimmory'
local_db_password := 'grimmory'
host_platform := if arch() == "aarch64" { "linux/arm64" } else { "linux/amd64" }

# Show the primary developer and agent command surface, including submodule recipes.
help:
    @just --list --list-submodules

# List recipes in a specific module, for example `just list api` or `just list ui`.
list module='':
    @if [[ -n "{{ module }}" ]]; then \
      just --list "{{ module }}"; \
    else \
      just --list --list-submodules; \
    fi

# Install the common local prerequisites used by the UI and API workflows.
bootstrap: ui::install api::version

# Run the full local verification pass used before opening a PR.
check: api::check ui::check

# Run the frontend and backend test suites.
test: api::test ui::test

# Build both application components without publishing a container image.
build: api::build ui::build

# Start the Docker-based development stack in the foreground.
dev-up:
    {{ compose_cmd }} up

# Start the Docker-based development stack in the background.
dev-up-detached:
    {{ compose_cmd }} up -d

# Start only the development database service from the compose stack.
db-up:
    {{ compose_cmd }} up -d {{ db_service }}

# Stop only the development database service from the compose stack.
db-down:
    {{ compose_cmd }} stop {{ db_service }}

# Stop the Docker-based development stack.
dev-down:
    {{ compose_cmd }} down

# Tail logs from the full dev stack or a single service with `just dev-logs backend`.
dev-logs service='':
    @if [[ -n "{{ service }}" ]]; then \
      {{ compose_cmd }} logs -f "{{ service }}"; \
    else \
      {{ compose_cmd }} logs -f; \
    fi

# Build the production image locally with buildx. Usage: `just image-build [platform] [tag]`.
image-build platform=host_platform tag=local_image_tag:
    docker buildx build --platform "{{ platform }}" -t "{{ tag }}" --load .

# Run the locally built production image against the expected development defaults.
image-run tag=local_image_tag db_url=local_db_url db_user=local_db_user db_password=local_db_password:
    docker run --rm -it \
      --name "{{ local_container_name }}" \
      --network host \
      -e "SPRING_DATASOURCE_URL={{ db_url }}" \
      -e "SPRING_DATASOURCE_USERNAME={{ db_user }}" \
      -e "SPRING_DATASOURCE_PASSWORD={{ db_password }}" \
      -v ./shared/data:/app/data \
      -v ./shared/books:/books \
      -v ./shared/bookdrop:/bookdrop \
      -p 6060:6060 \
      "{{ tag }}"

# Show the resolved tool versions that the local commands expect to find.
doctor:
    @echo "just: $$(just --version)"
    @echo "java: $$(java -version 2>&1 | head -n 1)"
    @echo "node: $$(node --version)"
    @echo "yarn: $$(corepack yarn --version)"
    @echo "docker: $$(docker --version)"

# ── Native image (GraalVM) ──────────────────────────────────────
# The native image build is resource-intensive (~10 GB RAM, 10–20 min).
# It runs automatically in CI via .github/workflows/native-image-ci.yml.
# These recipes exist for optional local validation on x86_64 machines.

native_compose := 'docker compose -f native.docker-compose.yml'
native_image_tag := env_var_or_default('GRIMMORY_NATIVE_TAG', 'grimmory:native')
native_min_mem_gb := '10'

# Verify Docker has enough memory for native-image compilation.
[no-exit-message]
_native-preflight:
    #!/usr/bin/env bash
    set -euo pipefail
    mem_bytes=$(docker info --format '{{{{.MemTotal}}')
    mem_gb=$(( mem_bytes / 1073741824 ))
    if (( mem_gb < {{ native_min_mem_gb }} )); then
      echo "ERROR: Docker has ${mem_gb} GB memory — native-image compilation needs ≥{{ native_min_mem_gb }} GB."
      echo "Increase memory in Docker Desktop → Settings → Resources, then restart Docker."
      exit 1
    fi
    echo "Docker memory: ${mem_gb} GB (minimum {{ native_min_mem_gb }} GB) ✓"

# Build the native-image Docker image locally.
native-build platform=host_platform tag=native_image_tag: _native-preflight
    docker buildx build --platform "{{ platform }}" -f Dockerfile.native -t "{{ tag }}" --load .

# Start the native-image dev stack (builds first, then runs).
native-up: _native-preflight
    {{ native_compose }} up --build

# Start the native-image dev stack in the background.
native-up-detached: _native-preflight
    {{ native_compose }} up --build -d

# Stop the native-image dev stack.
native-down:
    {{ native_compose }} down

# Tail logs from the native-image dev stack.
native-logs service='':
    @if [[ -n "{{ service }}" ]]; then \
      {{ native_compose }} logs -f "{{ service }}"; \
    else \
      {{ native_compose }} logs -f; \
    fi
