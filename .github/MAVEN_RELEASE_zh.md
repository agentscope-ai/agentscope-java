# Maven Central 发布

[English](MAVEN_RELEASE.md)

推送版本 tag（例如 `v2.1.0-BETA2`、`v2.1.0-RC1` 或 `v2.1.0`）时，
触发 `Maven Central staging` 工作流（`maven-central-release.yml`）。
SNAPSHOT 版本、Service tag 和 Go 模块独立 tag 不属于本流程的发布输入。
推送分支、提交 PR 不会触发上传。

在仓库的 Actions secrets 中配置：

| Secret | 内容 |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal 用户 token 的 username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal 用户 token 的 password |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII armored 格式的 GPG 签名私钥 |
| `MAVEN_GPG_PASSPHRASE` | 签名私钥密码 |

打 tag 前，将根 `pom.xml`、`agentscope-dependencies-bom/pom.xml` 和
`agentscope-distribution/agentscope-bom/pom.xml` 的 `revision` 改为发布版本，
把版本准备与工作流改动合入准备发布的分支。
工作流要求三个版本都与 tag 一致，不会通过参数把 SNAPSHOT 源码临时改成发布版本。

例如，在 `main` 准备好 `2.1.0-BETA2` 后执行：

```bash
git switch main
git pull --ff-only origin main
git tag -a v2.1.0-BETA2 -m "Release 2.1.0-BETA2"
git push origin v2.1.0-BETA2
```

工作流校验模块与 BOM 声明，执行 Maven 测试和格式检查，生成 sources、Javadoc，
签名并上传 Maven 库模块（Core、Harness、extensions、BOM 和 distribution）。
构建范围排除 Service 应用、examples 和需要外部模型服务的 E2E 测试模块。
其他组件由各自的工作流发布。

部署命令明确设置 `-DautoPublish=false -DwaitUntil=validated`。
成功后进入 **VALIDATED** 状态，等待手动发布，包此时还不能从 Maven Central 下载。
进入 [Central Portal deployments](https://central.sonatype.com/publishing/deployments)，
检查 `agentscope-java-<版本号>`，确认后手动点击 **Publish**。
行为说明见 [Central 插件文档](https://central.sonatype.org/publish/publish-portal-maven/)。

运行失败时，先检查 Actions 日志及 Portal 中的状态，再决定是否重跑。
校验请求超时并不代表上传失败。再次上传前，在 Portal 删除不需要的未发布 deployment；
已正式发布的版本不能覆盖。通过 GitHub Actions 重跑原 tag 的工作流，不移动 tag。

发布 tag 创建后，后续开发提交可以把 Maven 版本切回 `SNAPSHOT`；工作流始终构建 tag 对应的源码。
