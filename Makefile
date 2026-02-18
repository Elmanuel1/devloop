# Makefile for tosspaper-email-engine

.PHONY: help build build-fast docker-up docker-up-fast docker-down docker-infra clean test jooq all

# Default target
help:
	@echo "Available targets:"
	@echo "  build           - Build everything service with all dependencies"
	@echo "  build-fast      - Quick build (no tests, no JOOQ generation)"
	@echo "  jooq            - Generate JOOQ classes for all modules"
	@echo "  docker-up       - Build and start all services with docker-compose"
	@echo "  docker-up-fast  - Quick build and start services (no tests)"
	@echo "  docker-infra    - Start only PostgreSQL and Redis (no app)"
	@echo "  docker-down     - Stop all services"
	@echo "  clean           - Clean build artifacts and docker volumes"
	@echo "  test            - Run all tests"
	@echo "  all             - Build and start all services"

# Generate JOOQ for all modules
jooq:
	@echo "Generating JOOQ classes for all modules..."
	./gradlew generateJooq --no-configuration-cache --stacktrace
	@echo "JOOQ generation completed!"

# Build everything service (includes all dependencies)
build: jooq
	@echo "Building everything service with all dependencies..."
	./gradlew openApiGenerate
	./gradlew :services:everything:build -x test
	@echo "Build completed successfully!"

# Quick build without tests or JOOQ generation
build-fast:
	@echo "Quick building everything service (no tests, no JOOQ generation)..."
	./gradlew openApiGenerate generateJsonSchema2Pojo 
	./gradlew :services:everything:build -x test -x generateJooq --stacktrace
	@echo "Quick build completed!"

# Start all services with docker-compose
docker-up: build
	@echo "Starting services with docker-compose..."
	docker compose up -d --build
	@echo "All services started!"
	@echo "Services available at:"
	@echo "  - Everything service: http://localhost:8080"
	@echo "  - PostgreSQL: localhost:5434"
	@echo "  - Redis: localhost:16379"

# Helper function to increment version (patch version)
# Usage: $(call increment_version,0.1.9) -> 0.1.10
increment_version = $(shell echo "$1" | awk -F. '{if (NF==3) printf "%d.%d.%d", $$1, $$2, $$3+1; else if (NF==2) printf "%d.%d", $$1, $$2+1; else printf "%d", $$1+1}')

# Get current version from springboot.env
get_version = $(shell grep "^APP_VERSION:" springboot.env | cut -d' ' -f2)

# Quick build and start (no tests, no JOOQ generation)
# Automatically increments version before building
docker-up-fast: build-fast
	@echo "Incrementing version..."
	@CURRENT_VERSION=$$(grep "^APP_VERSION:" springboot.env | cut -d' ' -f2); \
	NEW_VERSION=$$(echo $$CURRENT_VERSION | awk -F. '{if (NF==3) printf "%d.%d.%d", $$1, $$2, $$3+1; else if (NF==2) printf "%d.%d", $$1, $$2+1; else printf "%d", $$1+1}'); \
	echo "Current version: $$CURRENT_VERSION -> New version: $$NEW_VERSION"; \
	if [ "$$(uname)" = "Darwin" ]; then \
		sed -i '' "s/^APP_VERSION:.*/APP_VERSION: $$NEW_VERSION/" springboot.env; \
	else \
		sed -i.bak "s/^APP_VERSION:.*/APP_VERSION: $$NEW_VERSION/" springboot.env && rm -f springboot.env.bak; \
	fi; \
	echo "Updated APP_VERSION in springboot.env to $$NEW_VERSION"
	@echo "Starting services with docker-compose..."
	@CURRENT_VERSION=$$(grep "^APP_VERSION:" springboot.env | cut -d' ' -f2); \
	APP_VERSION=$$CURRENT_VERSION docker compose up -d --build
	@echo "All services started!"

# Start only infrastructure services (PostgreSQL and Redis)
docker-infra:
	@echo "Starting infrastructure services (PostgreSQL and Redis)..."
	docker compose up -d postgres redis flyway
	@echo "Infrastructure services started!"
	@echo "Services available at:"
	@echo "  - PostgreSQL: localhost:5434"
	@echo "  - Redis: localhost:16379"

# Stop all services
docker-down:
	@echo "Stopping services..."
	docker compose down
	@echo "All services stopped!"

# Clean build artifacts and docker volumes
clean:
	@echo "Cleaning build artifacts..."
	./gradlew clean
	@echo "Stopping and removing docker containers and volumes..."
	docker compose down --volumes --remove-orphans
	@echo "Clean completed!"

# Run all tests
test:
	@echo "Running all tests..."
	./gradlew test --stacktrace
	@echo "Tests completed!"

# Alias for docker-up
all: docker-up
