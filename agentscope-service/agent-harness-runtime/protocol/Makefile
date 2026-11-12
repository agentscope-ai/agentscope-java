PYTHON ?= python3
NODE ?= node

.PHONY: check
check:
	$(PYTHON) scripts/validate.py
	$(NODE) scripts/verify-vectors.mjs
