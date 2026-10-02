# AgentScope Service 发布手册（项目管理员）

[English](README.md) · [用户安装文档](../../docs/v2/zh/service/quickstart.md)

这份手册说明项目管理员如何把一个确定的源码版本发布为用户可安装的 AgentScope Service。命令默认在仓库根目录执行。发布脚本不会自动修改版本、提交代码、创建 Git tag 或发布官网。

建议先完成一次 RC 发布和安装验收，再发布稳定版。RC 与稳定版使用不同版本号，稳定版重新构建、打包和验收。文中的版本与仓库地址是示例，执行前需要确认命名空间权限和版本可用性。

## 一、先确定这次发布的范围

| 组件 | 用户获取形式 | 发布方式 |
| --- | --- | --- |
| Control Plane + Dashboard | `agentscope-service-control` 镜像 | Actions 或 `release.py images --push` |
| Gateway | `agentscope-service-gateway` 镜像 | 同上 |
| Dataplane | `agentscope-service-dataplane` 镜像 | 同上 |
| Scheduler | `agentscope-service-scheduler` 镜像 | 同上 |
| 完整部署配置 | Compose 压缩包、Helm Chart | GitHub Release 附件；Chart 同时发布到 OCI registry |
| `agentscope`、兼容名称 `aistioctl`、Runtime Host | Linux/macOS × amd64/arm64 压缩包 | GitHub Release 附件 |
| Java Application SDK | `io.agentscope:agentscope-extensions-aistio` 及所需依赖 | Maven Central，单独发布 |
| Python SDK | `aistio-sdk` wheel、sdist | PyPI，单独发布 |
| DSH 插件 | `@agentscope/dsh-aistio` npm 包 | npm，单独发布 |
| 用户文档 | 官网 Service 专区 | 合入 `main` 后由网站工作流部署 |

前端已经包含在 control 镜像中，不单独发布 npm 包。PostgreSQL 使用上游镜像或外部数据库。`service-common` 和 Service 可执行模块默认不发布到 Maven Central。完整 Service Chart 使用 standalone HTTP 模式；旧 Aistio Chart 与 ASDP gRPC 属于另一种部署形态，发布说明应区分它们。

SDK 可以独立发版。SDK 内容未变且已有兼容公开版本时，发布说明引用现有版本即可；不能为了凑齐发布清单重复上传同版本包。

## 二、管理员首次发布前的一次性配置

### 2.1 代码仓库与发布入口

当前项目的 `origin` 是 `agentscope-ai/agentscope-java`。你需要相应的代码合并、tag、Actions 和 Release 操作权限，并遵循组织的分支保护规则。

先让 `.github/workflows/service-release.yml` 进入仓库默认分支，再使用 Actions 的手动发布入口。GitHub 要求 `workflow_dispatch` 工作流存在于默认分支，才能手动触发；实际构建仍可选定其他分支或 tag。[GitHub 官方说明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)

这一步可以先只合入发布基础设施。完整文档何时对外上线单独安排，因为当前网站工作流会在 `main` push 时部署官网。

### 2.2 镜像及 Helm 仓库

选择支持容器镜像与 Helm OCI 的 registry。以下使用 `ghcr.io/agentscope-ai` 举例，未代表该命名空间权限已经配置完成。

在代码仓库 **Settings → Secrets and variables → Actions → Repository secrets** 中配置：

| Secret 名称 | 示例或内容 |
| --- | --- |
| `SERVICE_REGISTRY_HOST` | `ghcr.io`，不包含协议或组织路径 |
| `SERVICE_REGISTRY_USER` | 有该组织 package 写权限的账号 |
| `SERVICE_REGISTRY_TOKEN` | 该账号用于 registry 登录的凭据 |

工作流的 `repository` 输入则填写 `ghcr.io/agentscope-ai`，包含组织路径。四个镜像最终位于该路径下，Chart 位于 `oci://ghcr.io/agentscope-ai/charts/agentscope-service`。

当前工作流使用以上三个 Secret，不会自动改用 `GITHUB_TOKEN`。GHCR 使用 PAT 登录时按官方要求配置 classic token 的 `write:packages` 权限，并满足组织 SSO 策略。发布后逐个检查四个镜像和 Chart package 的可见性；代码仓库公开不等于 package 已公开。[GHCR 登录说明](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)、[Package 可见性说明](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)

不要把 registry token 填入产品部署的 `.env`、Chart values 或源码。

### 2.3 SDK 仓库与官网

| 发布目标 | 管理员需要准备 |
| --- | --- |
| Maven Central | `io.agentscope` 命名空间发布权限、Central Portal user token、可用的 GPG 签名配置 |
| PyPI | `aistio-sdk` 的发布权限；首次发布先确认包名归属；用于 Twine 的 API token |
| npm | `@agentscope` scope 和目标包发布权限；交互登录及账号要求的 2FA |
| 官网 | 网站工作流的写权限、GitHub Pages 发布源、`java.agentscope.io` 域名配置 |

本仓库现有 Service 工作流没有接入 PyPI/npm 的 Trusted Publishing，也没有 SDK 发布 Secret；本手册采用管理员单独发布 SDK 的流程。PyPI 凭据可通过 Twine 的交互提示或 keyring 提供，避免写进命令和 Git。[PyPI 打包发布说明](https://packaging.python.org/en/latest/tutorials/packaging-projects/)、[Twine 凭据配置](https://packaging.python.org/en/latest/specifications/pypirc/)

npm 的发布权限和 2FA 要求由包设置决定；此处使用交互式 `npm login` / `npm publish`，按提示完成验证。[npm 官方发布认证说明](https://docs.npmjs.com/requiring-2fa-for-package-publishing-and-settings-modification/)

## 三、每次发布先填写版本清单

| 项目 | 本次需要确定的值 | 影响范围 |
| --- | --- | --- |
| Service 版本 | 例如 `2.0.3-rc.1` | 四个镜像、Chart version/appVersion、CLI、Compose 文件名 |
| Git tag | 建议 `agentscope-service-v2.0.3-rc.1` | 唯一指向本次审核过的源码 |
| Registry namespace | 例如 `ghcr.io/agentscope-ai` | 镜像和 Chart 的公开安装地址 |
| Java 版本 | 根 `pom.xml` 的 `revision` | Java SDK、父 POM、相关 reactor 依赖 |
| Python 版本 | 两处 Python 版本声明 | PyPI 包版本 |
| DSH 版本 | `package.json` 与 lockfile | npm 包版本 |
| 支持范围 | 实际验收过的平台、数据库、存储和模型环境 | Release 中的兼容性声明 |

Service 参数不带 `v` 前缀，当前脚本不接受 `+build` 元数据。Python 的 RC 采用 Python 版本格式，例如 `0.1.1rc1`；npm 可用 `0.1.1-rc.1`。版本号不必全部一致，但必须在清单中建立对应关系。

需要更新的源码文件：

- Java：根 `pom.xml` 的 `<revision>`。初始发布准备提交中为 `2.0.3-SNAPSHOT`；发 Maven 正式制品前应确定可公开发布的非 SNAPSHOT 版本，并检查所需依赖。
- Python：`agentscope-service/aistio/sdk/python/pyproject.toml` 的 `version` 和 `aistio/__init__.py` 的 `__version__`，两处同步。
- DSH：在 `agentscope-service/aistio/sdk/dsh` 执行 `npm version 新版本 --no-git-tag-version`，核对 `package.json`、`package-lock.json`。
- Helm：`release.py package` 会把本次 Service 版本写入打包后的 Chart version/appVersion，无需为了打包手工修改模板中的默认版本。

先完成上述调整、Release Notes 草稿和相关测试，再提交经过审核的改动。当前开发分支上的其他功能修改也必须明确是否纳入本次版本，不能用一份旧候选包代表后来改动过的源码。

后续命令使用这些变量；请替换成你的实际选择：

```bash
export SERVICE_VERSION=2.0.3-rc.1
export RELEASE_TAG="agentscope-service-v${SERVICE_VERSION}"
export IMAGE_REPOSITORY=ghcr.io/agentscope-ai
export RELEASE_REPO=agentscope-ai/agentscope-java
```

## 四、冻结源码并完成候选验证

### 4.1 源码和构建工具

在用户使用的主目录检查分支和工作区。不要对未完成的功能改动直接打发布 tag。

```bash
pwd
git branch --show-current
git status --short
git log -1 --oneline
```

准备 Java 21、Maven、Go（按 `agentscope-service/aistio/go.mod`）、Node.js 22、Python 3.10+、Docker Buildx、Helm 3.17+ 或兼容版本；使用后文 GitHub CLI 命令时还需要 `gh` 并完成 `gh auth login`。

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r agentscope-service/release/requirements.txt \
  -e 'agentscope-service/aistio/sdk/python[dev]'
```

### 4.2 测试和网站构建

先设置 `AISTIO_TEST_POSTGRES_DSN`，指向专门建立的临时 PostgreSQL 测试库。测试会修改数据库，不能使用开发或生产数据库。发布工作流已经提供临时 PostgreSQL。

```bash
python agentscope-service/release/release.py verify
mvn -B -ntp clean verify
python -m pip install -e 'docs[dev]'
jupyter-book build docs
python docs/scripts/check_service_docs.py
```

`verify` 覆盖 Service Java reactor、Go、前端、Python、DSH 和 Chart 检查；不是完整仓库 `mvn clean verify` 的替代。Go 共享测试库的包按 `-p 1` 串行执行。部分 Kubernetes controller 集成测试还需要 envtest 资源，按 Aistio Makefile 的 `test-integration` 入口运行。

### 4.3 打包和安装演练

```bash
python agentscope-service/release/release.py package \
  --version "$SERVICE_VERSION" --repository "$IMAGE_REPOSITORY"

# Apple Silicon 本机演练；amd64 主机改为 linux/amd64。
python agentscope-service/release/release.py images \
  --version "$SERVICE_VERSION" --repository "$IMAGE_REPOSITORY" \
  --platforms linux/arm64
```

默认制品目录是 `agentscope-service/release/dist/$SERVICE_VERSION/`。`package` 拒绝覆盖已有目录；重新演练用新的 `--output`，后续 `images` / `publish-chart` 也应指向同一个目录。对外交付使用冻结源码后生成的包，不能直接上传早先 `sourceDirty: true` 的候选包。

校验包文件；镜像元数据是之后生成的独立证据，不包含在这份校验和中：

```bash
(cd "agentscope-service/release/dist/$SERVICE_VERSION" && shasum -a 256 -c SHA256SUMS)
```

解压 Compose 包到独立安装目录，运行 `init-env.sh`，用单独的 Compose project 名和未占用端口演练。不要覆盖正在使用的开发栈。按照[快速上手](../../docs/v2/zh/service/quickstart.md)配置测试环境，按[Helm 文档](../../docs/v2/zh/service/kubernetes.md)完成临时集群安装。

在用于演练的安装上启用 Local 后运行：

```bash
python agentscope-service/release/smoke.py \
  --base http://127.0.0.1:18081 \
  --env-file /private/path/service.env \
  --state /tmp/agentscope-release-smoke.json
```

`--env-file` 指向该测试安装生成的私有 `.env` 或等价配置文件。脚本检查健康、管理员登录、演示密码拒绝，以及 Agent / Environment / Session / Team / Issue 创建。配置有效模型凭据后加 `--model-turn` 验证实际推理；重启或恢复后用原参数加 `--resume` 验证资源仍可访问。

管理员还应验收共享 Workspace、备份恢复、目标集群 RWX、Ingress/SSE，以及宣称支持的各个架构。四个平台的 CLI 交叉编译通过，并不表示四个平台的运行验收都通过。

## 五、创建发布 tag

完成所有必要提交并确保 `git status --short` 没有输出，再记录当前提交和创建 tag。以下命令会把 tag 推送到 `origin`，仅在确定正式候选源码后执行：

```bash
git rev-parse HEAD
git tag -a "$RELEASE_TAG" -m "AgentScope Service $SERVICE_VERSION"
git push origin "$RELEASE_TAG"
```

遵循仓库保护规则完成必要的 PR 合并和分支推送。Tag 应指向最终要发布的提交。当前工作流只检查选择的是 tag，不检查 tag 名与 `version` 是否相符，这个对应关系由管理员核对。

对外发布后不移动 tag、不覆盖同名镜像或包。需要修正内容时发布下一个 RC 或补丁版本。

## 六、发布镜像和 Helm：优先使用 Actions

### 6.1 先理解工作流会做什么

| 操作 | 当前工作流行为 |
| --- | --- |
| `publish=false` | 测试、打包、在 runner 本地构建 amd64 镜像；不推送 registry |
| `publish=true` | 测试、打包、推送 amd64/arm64 镜像和 OCI Chart；要求选定 Git tag |
| 构建产物 | 上传 `agentscope-service-release` Actions artifact |
| 镜像元数据 | 上传 `agentscope-service-image-metadata` Actions artifact |
| Docker/Helm 业务安装演练 | 不自动执行，需管理员完成第四节验收 |
| Maven/PyPI/npm、GitHub Release、官网 | 不由此工作流发布 |

`publish=false` 的镜像只存在于临时 runner，不是可下载的 Docker 镜像归档。需要本地安装演练时使用第四节的本地镜像构建命令。

### 6.2 运行与查看结果

在 GitHub Actions 中选择 **AgentScope Service release**，选定发布 tag，填写 `version` 和 `repository`；正式推送时设置 `publish=true`。也可明确用 CLI 指定 tag，避免选错分支：

```bash
gh workflow run service-release.yml --repo "$RELEASE_REPO" \
  --ref "$RELEASE_TAG" \
  -f version="$SERVICE_VERSION" \
  -f repository="$IMAGE_REPOSITORY" \
  -F publish=true

gh run list --repo "$RELEASE_REPO" --workflow service-release.yml --limit 5
```

从列表找到本次 run ID，查看运行页确认 tag、commit、版本和镜像路径。把下方 `RUN_ID` 替换为实际值：

```bash
gh run watch RUN_ID --repo "$RELEASE_REPO" --exit-status
gh run download RUN_ID --repo "$RELEASE_REPO" \
  --name agentscope-service-release --dir /private/path/release-artifacts
gh run download RUN_ID --repo "$RELEASE_REPO" \
  --name agentscope-service-image-metadata --dir /private/path/image-metadata
```

下载目录可能包含版本子目录。找到同一目录中的 `release-manifest.json`、`SHA256SUMS` 和各制品；核对 `sourceCommit` 等于 tag 对应提交、`sourceDirty` 为 `false`、镜像路径与 SDK 版本正确，并重新校验 SHA256。

### 6.3 Actions 暂不可用时的本地替代

管理员可以从同一冻结源码执行以下命令，无需再次执行 Actions 的推送步骤。先确认已经完成 `package`、工作区干净、Docker Buildx 支持要构建的两个平台：

```bash
docker login ghcr.io
helm registry login ghcr.io
python agentscope-service/release/release.py images \
  --version "$SERVICE_VERSION" --repository "$IMAGE_REPOSITORY" \
  --platforms linux/amd64,linux/arm64 --push
python agentscope-service/release/release.py publish-chart \
  --version "$SERVICE_VERSION" --repository "$IMAGE_REPOSITORY"
```

使用其他 registry 时替换两个登录域名。发布脚本会拒绝脏工作区；镜像推送请求附带 SBOM 和 provenance，digest 等信息写入 `image-*.json`。Actions 与本地方式择一完成同一版本的公开推送。

## 七、按本次范围单独发布 SDK

使用第六节下载并校验过的 SDK 包。设置 `RELEASE_ARTIFACT_DIR` 为包含 wheel、sdist 和 npm tarball 的实际目录；每个 SDK 使用第三节确定的独立版本号。

### 7.1 Python → PyPI

```bash
export RELEASE_ARTIFACT_DIR=/private/path/release-artifacts/VERSION
python -m twine check \
  "$RELEASE_ARTIFACT_DIR"/aistio_sdk-*.whl \
  "$RELEASE_ARTIFACT_DIR"/aistio_sdk-*.tar.gz
python -m twine upload \
  "$RELEASE_ARTIFACT_DIR"/aistio_sdk-*.whl \
  "$RELEASE_ARTIFACT_DIR"/aistio_sdk-*.tar.gz
```

根据 Twine 提示输入 API token，或使用已配置的 keyring。不要上传整个发布目录，Compose 和 CLI 的 `.tar.gz` 不是 Python 包。发布后在新的虚拟环境中执行 `pip install aistio-sdk==实际版本` 并验证导入。

### 7.2 DSH → npm

```bash
npm login
npm publish "$RELEASE_ARTIFACT_DIR"/agentscope-dsh-aistio-*.tgz \
  --access public --tag next
```

RC 使用 `next`；稳定版审核通过后发布时改为 `--tag latest`。发布前核对 tarball 内的实际版本，按 npm 提示完成 2FA。发布后在独立目录安装 `@agentscope/dsh-aistio@实际版本` 并验证导入。

### 7.3 Java → Maven Central

根 POM 的 `release` profile 已配置 GPG 签名和 `central-publishing-maven-plugin`。在个人 Maven `settings.xml` 的已有 `<servers>` 中加入 ID 为 `central` 的凭据，保留文件里的其他配置。下面是结构示例，不是可直接使用的账号：

```xml
<server>
  <id>central</id>
  <username>CENTRAL_PORTAL_TOKEN_USERNAME</username>
  <password>CENTRAL_PORTAL_TOKEN_PASSWORD</password>
</server>
```

提前确认签名私钥可用，并按组织惯例完成公钥分发和 passphrase 配置。然后从发布 tag 对应的干净源码执行：

```bash
export JAVA_RELEASE_VERSION=2.0.3-rc.1
mvn -B -ntp -pl agentscope-extensions/agentscope-extensions-aistio -am \
  -Drevision="$JAVA_RELEASE_VERSION" -Prelease deploy
```

此处 Java 版本仍是示例，应与版本清单一致。`-am` 会包含所需 reactor 模块和父 POM；发布前审查整个 reactor 的坐标，确保没有重复发布或未解析的 SNAPSHOT 依赖。不要为了发 SDK 而打开 Service 可执行模块的 Maven 发布开关。

**Maven 命令完成后仍需检查 Central Portal。** 当前 POM 没有开启 `autoPublish`；插件默认上传供校验及人工发布。进入 Portal 查看此次 deployment，确认校验通过并完成 Publish，直到状态显示发布完成，再从独立消费者项目验证依赖解析。[Sonatype Maven 插件说明](https://central.sonatype.org/publish/publish-portal-maven/)

## 八、发布 GitHub Release 与官网

### 8.1 整理 Release 附件与说明

从同一 tag 的工作流取出以下文件：

- Compose `.tar.gz`、Helm `.tgz`。
- 四个平台的 `agentscope-cli-*.tar.gz`，每份包含 CLI 与 Runtime Host。
- Python wheel/sdist 和 DSH npm tarball，说明哪些版本本次新发布、哪些沿用已有版本。
- `release-manifest.json`、`SHA256SUMS`、四个 `image-*.json`。

只上传这些公开制品；不要把工作目录、测试 `.env`、数据库备份或密钥一起打包。GitHub 自动生成的源码压缩包不能替代 Compose、CLI 和 SDK 附件。

先在 GitHub Releases 建立草稿，选用已存在的 tag，上传附件并校验下载。若使用 CLI，先在仓库之外准备完整的 Markdown Release Notes：

```bash
gh release create "$RELEASE_TAG" --repo "$RELEASE_REPO" \
  --verify-tag --draft \
  --title "AgentScope Service $SERVICE_VERSION" \
  --notes-file /private/path/release-notes.md
```

通过草稿页面添加上述附件。RC 勾选 **Set as a pre-release**；稳定版完成验收后再设置为正式发布及合适的 latest 状态。发布说明至少包含：

```markdown
# AgentScope Service VERSION

## 新增与修复
- 本次用户可感知的变化。

## 安装入口
- Compose 附件名称与校验方式。
- 四个镜像的确切版本及 digest。
- OCI Chart 地址与版本。
- Java / Python / DSH 安装坐标及各自版本。
- 对应官网文档链接。

## 升级与兼容性
- 已验证的架构、数据库及存储条件。
- 数据迁移、维护窗口、备份和恢复要求。
- 已知限制、尚未验证的集成。
```

### 8.2 发布官网

官网源码位于 `docs/v2/{zh,en}/service/`。把对应版本文档按审核流程合入 `main`，查看 **Deploy Docs to GitHub Pages** 工作流。当前配置只有 `main` push 会部署；手动触发及 PR 只做构建检查，不会上线。

上线后使用浏览器打开：

- 中文：`https://java.agentscope.io/v2/zh/service/index.html`
- 英文：`https://java.agentscope.io/v2/en/service/index.html`

确认 Service 导航、语言切换、搜索、图片、直接页面链接均可用，并核对安装文档使用的是已经公开可获取的版本。随后完成 GitHub Release 的公开发布。

## 九、以普通用户身份验收，才算完成

使用没有管理员 registry 登录状态的临时客户端或全新 CI job 验证公开获取，避免复用本机缓存把私有镜像误判为公开可用：

```bash
docker pull "$IMAGE_REPOSITORY/agentscope-service-control:$SERVICE_VERSION"
docker pull "$IMAGE_REPOSITORY/agentscope-service-gateway:$SERVICE_VERSION"
docker pull "$IMAGE_REPOSITORY/agentscope-service-dataplane:$SERVICE_VERSION"
docker pull "$IMAGE_REPOSITORY/agentscope-service-scheduler:$SERVICE_VERSION"
helm pull "oci://$IMAGE_REPOSITORY/charts/agentscope-service" \
  --version "$SERVICE_VERSION"
```

管理员最终核对：

- [ ] Release tag、manifest 的 commit、镜像和 Chart 版本一致。
- [ ] 公开下载的附件通过 SHA256 校验，镜像和 Chart 可以匿名拉取。
- [ ] 按公开文档完成全新 Docker 与 Helm 安装，管理员登录、模型任务和历史查询通过。
- [ ] Java/Python/npm 的已公布坐标可从公开仓库安装。
- [ ] 官网中英文文档可访问，升级说明与当前数据库行为一致。
- [ ] 发布说明准确列出未覆盖的平台或功能，没有把编译通过写成运行验证通过。

当前完整 Chart 每组件单副本、Recreate 更新，不提供 PostgreSQL 或 RWX provisioner。Go 启动时执行迁移，Java 使用 Hibernate 更新表结构；回退镜像不能代替恢复数据库、Workspace、Artifact 和原 Vault 密钥。正式升级按[备份恢复文档](../../docs/v2/zh/service/operations.md)安排维护窗口。

## 十、常见发布阻塞

| 现象 | 管理员处理方式 |
| --- | --- |
| Actions 找不到手动工作流 | 确认工作流已进入默认分支，账号有 Actions 权限 |
| 提示必须选择 tag | 用 `gh workflow run ... --ref "$RELEASE_TAG"`，确认 tag 已推送 |
| 本地发布提示工作区不干净 | 审核并提交发布范围内的改动，再重新生成正式包；不要用旧 dirty 候选包代替 |
| 打包提示输出目录已存在 | 换新的 `--output`，后续步骤使用同一目录；不要覆盖已发布版本 |
| 镜像成功、Chart 失败 | 检查同次产物位置和 Helm 登录；源码与版本未变时可只补发缺失 Chart |
| 上传成功但用户拉取失败 | 检查镜像与 Chart 各自的可见性、组织权限，使用匿名客户端复验 |
| PyPI/npm 报版本已存在 | 检查是否已经发布成功；内容需要变化时提升版本并重新打包 |
| Maven 返回成功但依赖搜不到 | 查看 Central Portal validation / Publish 状态，以及公开仓库同步情况 |
| 网站手动运行成功但没更新 | 当前手动执行不部署，检查对应文档是否合入 `main` |

如果某个组件已经公开、另一个组件发布失败，记录已成功的 digest 和包版本；相同源码可以补齐缺失步骤。需要修改源码时创建新的 RC 或补丁版本，不把部分成功的旧版本重新指向另一份代码。
