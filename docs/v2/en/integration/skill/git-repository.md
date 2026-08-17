# Git Skill Repository

`agentscope-extensions-skill-git-repository` treats a remote Git repo as a skill repository. It runs a lightweight remote-ref check on each read and only pulls when the remote HEAD changes — near-zero overhead at idle.

## When to use

- You want Git to manage versioning and review of skill content.
- You want to share a single skill set across multiple projects.
- You don't want to embed a database or config center in production.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-git-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

It uses JGit under the hood and supports both HTTPS and SSH.

## Quickstart

```java
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.AgentSkill;

// Public repo + default branch, temporary local directory
GitSkillRepository repo = new GitSkillRepository(
    "https://github.com/agentscope/skills.git"
);

// Register all skills into a Toolkit
Toolkit toolkit = new Toolkit();
repo.getAllSkills().forEach(toolkit::registerSkill);

// Clean up the temp directory on shutdown
Runtime.getRuntime().addShutdownHook(new Thread(repo::close));
```

## Pin branch / use a fixed local path

```java
GitSkillRepository repo = new GitSkillRepository(
    "https://github.com/agentscope/skills.git",
    "develop",                   // branch
    Path.of("/var/skills/repo"), // local path (null = temp dir)
    "agentscope-public",         // source label (visible in Toolkit)
    true                         // autoSync
);
```

## Auth for private repos

`GitSkillRepository` reuses system-level Git config; it doesn't manage credentials in Java:

- **HTTPS**: uses the credential helper from `~/.gitconfig` (osxkeychain, libsecret, ...).
- **SSH**: uses keys under `~/.ssh/` and `ssh-agent`.

```java
// Private SSH repository
GitSkillRepository repo = new GitSkillRepository(
    "git@github.com:my-org/private-skills.git"
);
```

In CI, make sure the runner user has the credentials or has the SSH agent configured.

## Auto-sync vs. manual sync

- `autoSync=true` (default): every read first runs `ls-remote`; pull happens only if the remote moved.
- `autoSync=false`: never pulls automatically; call `repo.sync()` to refresh.

```java
GitSkillRepository repo = new GitSkillRepository(remoteUrl, false);
repo.sync();              // sync once at startup
schedule(() -> repo.sync(), 5, TimeUnit.MINUTES);
```

## Operational notes

- Hold the repo as a singleton Spring Bean; close it once during shutdown.
- The temp directory has a JVM shutdown hook, but force-killed processes may leave residue — clean up externally if needed.
- Multi-instance deployments each maintain their own clone; no lock contention.
