# Subagents

Each `*.md` file in this directory defines a subagent the parent agent may
delegate to. The file name (without extension) is the subagent id; the front
matter declares its system prompt and tool allowlist.

Example skeleton:

```markdown
---
name: researcher
description: Investigates a question and returns a written summary.
tools: [read_file, grep_files, glob_files]
---

You are a research specialist. Stay focused on the task you receive; do not
edit files directly.
```

Delete this README once you have added real subagents.
