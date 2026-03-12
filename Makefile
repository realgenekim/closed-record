# Closed Record — Strict Map Wrapper for Clojure

# Add ~/bin to PATH for Clojure CLI tools
export PATH := $(HOME)/bin:$(PATH)

# ========================================
# Testing
# ========================================

# Run tests once with fail-fast
.PHONY: runtests-once
runtests-once:
	clj -X:test

# ========================================
# Code Quality
# ========================================

# Format Clojure source code
.PHONY: format
format:
	npx @chrisoakman/standard-clojure-style fix src test deps.edn

# ========================================
# Cleanup
# ========================================

.PHONY: clean
clean:
	rm -rf target .cpcache

# ========================================
# Help
# ========================================

.PHONY: help
help:
	@echo "Closed Record — Strict Map Wrapper"
	@echo ""
	@echo "Testing:"
	@echo "  make runtests-once        - Run tests once with fail-fast"
	@echo ""
	@echo "Code Quality:"
	@echo "  make format               - Format Clojure code (standard-clj)"
	@echo ""
	@echo "Other:"
	@echo "  make clean                - Remove build artifacts"
