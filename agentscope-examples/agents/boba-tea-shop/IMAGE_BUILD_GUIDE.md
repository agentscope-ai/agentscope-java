# Boba Tea Shop Build Guide

This document describes how to use the build scripts to build the various modules of the Boba Tea Shop project.

## Table of Contents

- [Quick Start](#quick-start)
- [Main Build Script](#main-build-script-buildsh)
- [Submodule Build Scripts](#submodule-build-scripts)
- [Other Modules](#other-modules)

---

## Quick Start

```bash
# Navigate to the project directory
cd agentscope-examples/boba-tea-shop

# Build default modules (excluding infrastructure modules)
./build.sh -v 1.0.0 -p linux/amd64 -r registry.cn-hangzhou.aliyuncs.com/myapp --push

# Build all modules (including infrastructure modules)
./build.sh -m all -v 1.0.0 -p linux/amd64 -r registry.cn-hangzhou.aliyuncs.com/myapp --push
```

---

## Main Build Script

Located at `boba-tea-shop/build.sh`, used for batch building multiple submodules.

### Module Categories

| Type | Module Name | Description |
|------|-------------|-------------|
| **Default Modules** | `supervisor-agent` | Main supervisor agent (includes frontend) |
| | `business-mcp-server` | Business MCP server |
| | `business-sub-agent` | Business sub-agent |
| | `consult-sub-agent` | Consultation sub-agent |
| **Additional Modules** | `mysql-image` | MySQL database image |
| | `nacos-image` | Nacos registry image |

> **Note**: The frontend has been merged with `supervisor-agent` deployment. Frontend static files are served directly by Spring Boot, accessible through port 10008.

### Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `-v, --version` | Image version tag | `test` |
| `-r, --registry` | Docker image registry address | None |
| `-p, --platform` | Target platform | Current platform |
| `-t, --run-tests` | Run Maven tests | Skip tests |
| `--skip-build` | Skip source build (Maven/npm) | Don't skip |
| `--skip-docker` | Skip Docker image build | Don't skip |
| `--push` | Push image to registry after build | Don't push |
| `-m, --modules` | Specify modules to build | `default` |
| `-h, --help` | Display help information | - |

### `-m` Parameter Special Values

| Value | Description |
|-------|-------------|
| `default` | Build default 4 modules (default behavior) |
| `all` | Build all 6 modules |
| `module name` | Build specified modules, separate multiple with commas |

### Usage Examples

```bash
# 1. Build and push to image registry
./build.sh -v 1.0.0 -p linux/amd64 -r registry.cn-hangzhou.aliyuncs.com/myapp --push

# 2. Only perform source build, skip Docker image build
./build.sh --skip-docker

# 3. Full example: Build all modules and push to registry
./build.sh -m all \
    -v 1.0.0 \
    -r registry.cn-hangzhou.aliyuncs.com/myapp \
    -p linux/amd64 \
    --push
```

---

## Submodule Build Scripts

Each submodule directory contains an independent `build.sh` for building that specific module.

### Application Modules

Includes: `supervisor-agent` (with frontend), `business-mcp-server`, `business-sub-agent`, `consult-sub-agent`

#### Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `-v, --version` | Image version tag | `test` |
| `-r, --registry` | Docker image registry address | None |
| `-p, --platform` | Target platform | Current platform |
| `-t, --run-tests` | Run tests (Java modules only) | Skip |
| `--skip-build` | Skip source build | Don't skip |
| `--skip-docker` | Skip Docker image build | Don't skip |
| `--push` | Push image to registry | Don't push |
| `-h, --help` | Display help information | - |

#### Usage Examples

```bash
# Build supervisor-agent (with frontend)
cd supervisor-agent

# 1. Build and push
./build.sh -v 1.0.0 -p linux/amd64 -r registry.cn-hangzhou.aliyuncs.com/myapp --push

# 2. Only perform Maven build, skip Docker image build
./build.sh --skip-docker
```

> **Note**: The `supervisor-agent` build script automatically builds the frontend and packages it into the same image.

### Infrastructure Modules

Includes: `mysql-image`, `nacos-image`

These modules only build Docker images; there is no source build step.

#### Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `-v, --version` | Image version tag | Varies by module |
| `-r, --registry` | Docker image registry address | None |
| `-p, --platform` | Target platform | Current platform |
| `--push` | Push image to registry | Don't push |
| `-h, --help` | Display help information | - |

#### Usage Examples

```bash
# Navigate to module directory
cd mysql-image  # or nacos-image

# 1. Default build
./build.sh

# 2. Specify version
./build.sh -v 8.0.30  # mysql-image
./build.sh -v 3.1.1   # nacos-image

# 3. Specify platform
./build.sh -p linux/amd64

# 4. Build and push
./build.sh -v 8.0.30 -p linux/amd64 -r registry.cn-hangzhou.aliyuncs.com/myapp --push
```

## Other Modules

### HiMarket

For HiMarket introduction and build/deployment guide, see [HIMARKET_DEPLOYMENT.md](HIMARKET_DEPLOYMENT.md)

