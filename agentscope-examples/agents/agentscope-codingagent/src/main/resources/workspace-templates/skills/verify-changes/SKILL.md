---
name: verify-changes
description: Verify code changes by running the project's typecheck, build, lint, and targeted tests, then fix and re-run until clean. Use after editing any source file.
---

# Verify Changes

Editing a file is only half a step. Always close the loop by verifying before you mark
work done or open a PR. This is the manual equivalent of an IDE/LSP diagnostics loop.

## Procedure

1. Detect the project's check commands from its config files:
   - Node/TS: `package.json` scripts (`build`, `lint`, `typecheck`), `tsconfig.json` -> `npx tsc --noEmit`, `npx eslint <changed files>`.
   - Python: `ruff check <path>`, `mypy <path>`, `pytest <specific test file>`.
   - Java/Maven: `mvn -q -pl <module> compile`, then the single relevant test class via `-Dtest=<ClassName>`.
   - Gradle: `./gradlew :<module>:compileJava` / `:<module>:test --tests <ClassName>`.
2. Run ONLY the checks and tests directly related to the files you changed. Never run the
   full test suite.
3. Disable color output so logs are readable: `NO_COLOR=1`, `--no-color`, or `--no-colors`.
4. Read the output as ground truth. If it reports errors, they are real — do not claim success.
5. Fix the root cause, then re-run the SAME command. Repeat until it passes.

## Rules

- Only mark a todo `completed` once its verification command passes.
- If you cannot find a check command, at minimum compile/parse the changed files.
- Do not disable or delete failing tests to make checks pass.
