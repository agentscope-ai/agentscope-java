---
title: "Workspaces: shared instructions and capabilities"
---

[简体中文](/v2/zh/service/workspaces)

**Resources → Workspaces** stores reusable Agent material: `AGENTS.md`, skills, tools and subagent definitions. A Workspace is a resource, separate from an account Namespace and an execution's temporary directory.

## Create and link

Select **New workspace**, give it a recognizable name and maintain its guidance and capability files. Link it from an Agent's Workspace page. Several Agents can reuse it.

Start with concise `AGENTS.md` guidance before adding capabilities:

```markdown
# Reporting conventions

Read task material in inputs first.
Cite sources for facts and mark hypotheses separately.
Write the report to outputs and return its location.
```

Create the referenced directories and files yourself; instructions do not create them. Use a new task to verify visible paths and content.

## Choose the right content

| Content | Purpose |
| --- | --- |
| AGENTS.md | Project operating guidance and shared constraints |
| Skills | Reusable procedures and supporting files |
| Tools / MCP configuration | External capability connections |
| Subagents | Specialist delegation definitions |

Use [Memory](/v2/en/service/memory) for shared knowledge and [Vault](/v2/en/service/vault) for secrets. Reference credentials explicitly in tool connections instead of storing plaintext.

## Execution directories

Managed Agents access files through their [Environment](/v2/en/service/environments). A Hosted Runtime Host projects portable definitions into supported provider configuration and uses task-specific working directories. Check the selected Runtime's projection capabilities.

Inspect consumers before editing and verify changes with new work. Resolve dependent references before deleting a shared Workspace. Backups need both database references and Workspace storage.
