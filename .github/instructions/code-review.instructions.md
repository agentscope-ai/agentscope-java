---
applyTo: "**"
---

When reviewing code, focus on:

## Java Code Standards
- No non-English content allowed (except i18n resources)
- No `@author` tags - git history tracks authorship
- All public methods must have Javadoc
- Check for simpler implementations with same functionality
- Apply design principles (SOLID, DRY, etc.) for improvement opportunities
- Use imports instead of fully qualified class names
- No wildcard imports allowed

## Security Check
- Hardcoded secrets, API keys, or credentials
- Sensitive information leakage in logs
- Input validation and sanitization
- OWASP Top 10 vulnerabilities (SQL injection, XSS, etc.)

## Performance Check
- Blocking calls in Reactor chains (improper `.block()` usage)
- Unclosed resources (streams, connections)
- Unnecessary object creation
- N+1 query problems

## Documentation Sync Check
- If changes affect functionality described in `docs/` directory, remind user to update corresponding documentation
- Check if new features need documentation additions

## PR Title Check
- Must follow conventional commit format: `type(scope): description`
- Valid types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `chore`, `ci`, `build`
- Title should accurately reflect the main changes in the PR
- Keep it concise but descriptive

## Review Style
- Be specific and actionable in feedback
- Explain the "why" behind recommendations
- Acknowledge good patterns when you see them
