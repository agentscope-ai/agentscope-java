# Documentation (Jupyter Book)

## Prerequisites

- **Python 3.10+** (matches CI)

## Local setup

From this directory (`docs/`):

```bash
python -m pip install --upgrade pip
pip install -e ".[dev]"
```

## Build

```bash
jupyter-book clean .
jupyter-book build .
```

Static site output: **`_build/html/`**. Open `_build/html/index.html` in a browser, or serve it, for example:

```bash
python -m http.server 8000 --directory _build/html
```

Then visit `http://localhost:8000/`.

## Layout

- **`_config.yml`** — Sphinx / Furo / Jupyter Book config  
- **`_toc.yml`** — site table of contents  
- **`_templates/`**, **`_static/`** — theme overrides and assets  
- **`_sphinx_extensions/`** — local Sphinx extensions (keep in version control)
