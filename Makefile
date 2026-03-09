.PHONY: help setup build test check plugin run clean verify deps

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
