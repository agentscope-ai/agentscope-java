# agentscope-claw — local layout

agentscope-claw is a single-user local assistant. Everything that used to be
scoped per-user / per-tenant is now scoped to the machine that runs the JVM.
All persistent state lives under `${claw.home}` (default `~/.agentscope`):

```
~/.agentscope/
├── agentscope.json                       # built-in agent definitions
├── agents.json                           # custom agent catalog (JSON file)
└── agents/
    └── <agentId>/
        ├── workspace/                    # AGENTS.md, skills/, subagents/, tools.json, memory/, …
        └── sessions.json                 # session-store index for that agent's main session
```

## Two kinds of agents, one runtime

- **Built-in agents** are listed in `~/.agentscope/agentscope.json`. They are
  loaded at bootstrap and are read-only from the UI; edit the JSON file if you
  want to tweak them.
- **Custom agents** are created through the UI and stored in
  `~/.agentscope/agents.json` (managed by `UserAgentDefinitionStore`). They can
  be edited and deleted from the UI.

Both kinds share the same per-agent directory layout under
`~/.agentscope/agents/<agentId>/` and the same controller surface
(`/api/agents/*`, `/api/agents/<id>/workspace/*`, etc.).

## Sessions

Each main agent has exactly one `sessions.json` file at
`~/.agentscope/agents/<agentId>/sessions.json`. It is the index used by
`SessionStore` / `SessionAgentManager` to track active and historical
sessions for that agent. There is no per-user fan-out and no shared index
across agents.

Inside the agent's own workspace (`~/.agentscope/agents/<agentId>/workspace/`)
the harness still maintains its usual per-sub-agent directories under
`agents/<subId>/sessions/` and `agents/<subId>/context/` — those are runtime
artifacts produced by `WorkspaceManager` and `WorkspaceSession`, not by the
top-level session store.

## Channels

The default deployment registers a single `chatui` channel with
`DmScope.MAIN`, so chat in the UI talks to one shared session per agent. You
can still add external channel bindings (Slack, Discord, etc.) per agent via
the Channels tab — those bindings are useful for personal hooks even on a
local install.

## What was removed

The repository used to ship a multi-tenant / distributed deployment surface.
For the local refactor we removed:

- JWT authentication, the user store, the admin user controller, and all
  `Authentication` parameters from the REST controllers.
- The JPA layer (`spring-boot-starter-data-jpa`, H2/MySQL/PostgreSQL drivers,
  `application-jdbc.yml`, `data-h2.sql`).
- Per-user workspace namespacing (`WorkspaceManagerFactory`,
  `BuilderWorkspaceConfig`, `NamespacedFilesystemView`).
- The Docker-sandbox wiring (`BuilderSandboxConfig`, `SandboxFilesystemSpec`,
  `SandboxDistributedOptions`, `RemoteFilesystem`, `CompositeFilesystem`,
  `OverlayFilesystem` references).
- Agent sharing (`AgentAclService`, `AgentAccessGuard`, share grants),
  cloning (`AgentCloneController`, `WorkspaceCopier`), the per-user activity
  feed (`AgentActivityStore`, `ActivityEvent`), per-user usage tracking
  (`UsageStore`), and identity linking (`IdentityLinkStore`) along with the
  matching `/dock_<channel>` / `/identity` slash commands.
- The corresponding frontend pages and APIs (`LoginPage`, `ProfilePage`,
  `AdminUsersPage`, `AgentActivityPage`, `ShareAgentDialog`, `api/auth.ts`,
  `api/admin.ts`, `api/shares.ts`, `api/clone.ts`, `api/activity.ts`).

If you need any of that back, look in the git history before this refactor.
