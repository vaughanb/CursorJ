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
	@echo "  make release <major|minor|patch> - Clean tree required; bump version, commit/push, tag/push"

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

release: ## Bump version, commit/push, then create and push semantic git tag
	@case "$(BUMP)" in \
		major|minor|patch) ;; \
		*) echo "Usage: make release <major|minor|patch> (or make release BUMP=<major|minor|patch>)"; exit 1 ;; \
	esac
	@if ! git diff --quiet || ! git diff --cached --quiet; then \
		echo "Working tree must be clean before release."; \
		echo "Commit or stash local changes, then run release again."; \
		exit 1; \
	fi
	@if ! git rev-parse --abbrev-ref --symbolic-full-name @{u} >/dev/null 2>&1; then \
		echo "Current branch has no upstream. Push with: git push -u origin <branch>"; \
		exit 1; \
	fi
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
	new_version="$$major.$$minor.$$patch"; \
	new_tag="v$$new_version"; \
	if git rev-parse "$$new_tag" >/dev/null 2>&1; then \
		echo "Tag $$new_tag already exists."; \
		exit 1; \
	fi; \
	tmp_file="gradle.properties.tmp"; \
	awk -v new_version="$$new_version" 'BEGIN { updated=0 } /^pluginVersion[[:space:]]*=/ { print "pluginVersion = " new_version; updated=1; next } { print } END { if (!updated) exit 2 }' gradle.properties > "$$tmp_file"; \
	awk_exit=$$?; \
	if [ $$awk_exit -ne 0 ]; then \
		rm -f "$$tmp_file"; \
		echo "Failed to update pluginVersion in gradle.properties."; \
		exit 1; \
	fi; \
	mv "$$tmp_file" gradle.properties; \
	git commit --only gradle.properties -m "chore(release): v$$new_version"; \
	git push; \
	git tag "$$new_tag"; \
	git push origin "$$new_tag"; \
	echo "Updated pluginVersion to $$new_version in gradle.properties"; \
	echo "Committed, pushed, and tagged $$new_tag (previous: $$latest_tag)"

# Consume optional second goal when using: make release patch
major minor patch:
	@:
