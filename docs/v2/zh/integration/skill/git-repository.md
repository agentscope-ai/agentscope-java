# Git 技能仓库

`agentscope-extensions-skill-git-repository` 把一个远程 Git 仓库当作技能仓库：每次读取时做轻量的 remote ref 检查，只在远端 HEAD 变化时才真正 pull，平时几乎零开销。

## 何时使用

- 想用 Git 来管控技能内容的版本与审阅。
- 想跨多个项目共享同一份技能集。
- 不希望在生产服务里嵌入数据库或配置中心。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-skill-git-repository</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

底层使用 JGit，HTTPS / SSH 都支持。

## 快速上手

```java
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.AgentSkill;

// 公开仓库 + 默认分支，使用临时目录
GitSkillRepository repo = new GitSkillRepository(
    "https://github.com/agentscope/skills.git"
);

// 取出全部技能注册到 Toolkit
Toolkit toolkit = new Toolkit();
repo.getAllSkills().forEach(toolkit::registerSkill);

// 应用退出时清理临时目录
Runtime.getRuntime().addShutdownHook(new Thread(repo::close));
```

## 选定分支 / 自定义本地路径

```java
GitSkillRepository repo = new GitSkillRepository(
    "https://github.com/agentscope/skills.git",
    "develop",                   // 分支
    Path.of("/var/skills/repo"), // 本地路径（null = 临时目录）
    "agentscope-public",         // source 标识（在 Toolkit 里能看到）
    true                         // autoSync = true，每次读自动检查并 pull
);
```

## 私有仓库的鉴权

`GitSkillRepository` 复用系统级 Git 配置，不在 Java 侧管理凭证：

- **HTTPS**：使用 `~/.gitconfig` 里的 credential helper（osxkeychain、libsecret 等）。
- **SSH**：使用 `~/.ssh/` 下的密钥与 ssh-agent。

```java
// SSH 私有仓库
GitSkillRepository repo = new GitSkillRepository(
    "git@github.com:my-org/private-skills.git"
);
```

CI 环境下请确保 runner 用户具备相应凭证或挂载好 SSH agent。

## 自动同步与手动同步

- `autoSync=true`（默认）：`getSkill / getAllSkills / skillExists` 等读操作前会先 `ls-remote`，如远端有更新才执行 pull。
- `autoSync=false`：完全不自动 pull，要刷新时调用 `repo.sync()`。

```java
GitSkillRepository repo = new GitSkillRepository(remoteUrl, false);
repo.sync();              // 启动时同步一次
schedule(() -> repo.sync(), 5, TimeUnit.MINUTES);  // 定时同步
```

## 工程实践建议

- 建议在 Spring Bean 上以单例形式持有仓库，重启时统一 `close()`。
- 临时目录会注册 JVM Shutdown Hook 自动删除；如果你强制 kill 进程，可能残留，需要外部清理。
- 多实例部署时各自维护一份本地 clone，没有锁竞争。
