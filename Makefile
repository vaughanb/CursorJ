.PHONY: help setup build test check plugin run clean verify deps release major minor patch

# Allow `make release patch` in addition to `make release BUMP=patch`.
BUMP ?= $(word 2,$(MAKECMDGOALS))

# Use the correct Gradle wrapper script per OS.
ifeq ($(OS),Windows_NT)
GRADLEW := .\gradlew.bat
else
GRADLEW := ./gradlew
endif

DEFAULT_GOAL := help

help: ## Show available targets
	@echo "Common targets:"
	@echo "  make setup   - Prepare project dependencies and wrapper"
	@echo "  make build   - Compile and run tests"
	@echo "  make test    - Run tests"
	@echo "  make check   - Run checks"
	@echo "  make plugin  - Build installable plugin zip"
	@echo "  make run     - Launch sandbox IDE with plugin"
	@echo "  make verify  - Run IntelliJ plugin verification"
	@echo "  make clean   - Remove build outputs"
	@echo "  make deps    - Print dependency tree"
	@echo "  make release <major|minor|patch> - Create the next semantic version tag"

setup: ## Prepare local Gradle state
	$(GRADLEW) --version

build: ## Compile and run tests
	$(GRADLEW) build

test: ## Run tests
	$(GRADLEW) test

check: ## Run project checks
	$(GRADLEW) check

plugin: ## Package plugin zip
	$(GRADLEW) buildPlugin

run: ## Launch sandbox IDE
	$(GRADLEW) runIde

verify: ## Run JetBrains plugin verifier
	$(GRADLEW) verifyPlugin

clean: ## Remove build outputs
	$(GRADLEW) clean

deps: ## Show dependency graph
	$(GRADLEW) dependencies

release: ## Create next semantic git tag
	@case "$(BUMP)" in \
		major|minor|patch) ;; \
		*) echo "Usage: make release <major|minor|patch> (or make release BUMP=<major|minor|patch>)"; exit 1 ;; \
	esac
	@latest_tag=$$(git describe --tags --abbrev=0 --match "v[0-9]*.[0-9]*.[0-9]*" 2>/dev/null || echo "v0.0.0"); \
	version=$${latest_tag#v}; \
	major=$${version%%.*}; \
	rest=$${version#*.}; \
	minor=$${rest%%.*}; \
	patch=$${rest##*.}; \
	case "$(BUMP)" in \
		major) major=$$((major + 1)); minor=0; patch=0 ;; \
		minor) minor=$$((minor + 1)); patch=0 ;; \
		patch) patch=$$((patch + 1)) ;; \
	esac; \
	new_tag="v$$major.$$minor.$$patch"; \
	if git rev-parse "$$new_tag" >/dev/null 2>&1; then \
		echo "Tag $$new_tag already exists."; \
		exit 1; \
	fi; \
	git tag "$$new_tag"; \
	echo "Created tag $$new_tag (previous: $$latest_tag)"

# Consume optional second goal when using: make release patch
major minor patch:
	@:
