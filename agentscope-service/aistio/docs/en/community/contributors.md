# Contributors

AgentScope Go welcomes contributions through code, documentation, examples, issue reports, and field experience.

## Contribution Areas

Common contribution areas include:

- Fixing documentation errors, missing explanations, or outdated examples.
- Adding examples for model providers, tools, Sandbox environments, and middleware.
- Reporting reproducible issues with version, environment, input, and actual behavior.
- Fixing bugs, adding tests, and improving error messages or runtime logs.
- Discussing API design, module boundaries, and compatibility trade-offs.

## How To Contribute

1. Check existing GitHub issues and pull requests before starting.
2. For documentation changes or small fixes, open a pull request directly.
3. For changes that affect APIs, runtime behavior, or compatibility, open an issue first and describe the background, proposal, and impact.
4. Configure local pre-commit and run the relevant local checks before submitting code.

## Local Pre-commit Setup

The repository provides `.pre-commit-config.yaml` and `.githook/pre-commit`. After cloning the repository for the first time, run:

```bash
make setup
make install-tools
```

`make setup` installs the project-pinned `pre-commit` version and runs:

```bash
git config core.hooksPath .githook
```

After setup, regular `git commit` commands run local pre-commit automatically. You can also run all hooks manually:

```bash
.venv/bin/pre-commit run --all-files
```

The current pre-commit configuration covers general file checks, Go linting, spell checking, secret scanning, `go mod tidy`, and Markdown linting. If commit output says `pre-commit is not installed in .venv`, run `make setup` again.

## Local Checks

For documentation changes, usually run:

```bash
make docs-lint
make docs-build
```

For code changes, usually run:

```bash
make fmt
make test-unit
```

When changing examples, Sandbox behavior, permission flows, or model integrations, include reproducible verification steps.

## Contributor List

The complete contributor record is maintained by GitHub:

- [GitHub Contributors](https://github.com/yuluo-yx/agentscope-go/graphs/contributors)

| GitHub ID / Avatar | Role | Term |
| --- | --- | --- |
| <img src="https://github.com/yuluo-yx.png?size=64" alt="yuluo-yx" width="32" height="32" style="border-radius: 50%; vertical-align: middle; margin-right: 8px;" /> <a href="https://github.com/yuluo-yx">yuluo-yx</a> | maintainer | May 2025 to present |
