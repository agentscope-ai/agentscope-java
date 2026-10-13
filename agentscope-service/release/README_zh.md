# AgentScope Service 发布手册（项目管理员）

[English](README.md) · [用户安装文档](../../docs/v2/zh/service/quickstart.md)

这份手册说明项目管理员如何把一个确定的源码版本发布为用户可安装的 AgentScope Service。命令默认在仓库根目录执行。发布脚本不会自动修改版本、提交代码、创建 Git tag 或发布官网。

建议先完成一次 RC 发布和安装验收，再发布稳定版。RC 与稳定版使用不同版本号，稳定版重新构建、打包和验收。文中的版本与仓库地址是示例，执行前需要确认命名空间权限和版本可用性。

## 一、先确定这次发布的范围

| 组件 | 用户获取形式 | 发布方式 |
| --- | --- | --- |
| Control Plane + Dashboard | `as-controlplane` 镜像 | Actions 或 `release.py images --push` |
| Gateway | `as-gateway` 镜像 | 同上 |
| Dataplane | `as-dataplane` 镜像 | 同上 |
| Scheduler | `as-scheduler` 镜像 | 同上 |
| 完整部署配置 | Compose 压缩包、Helm Chart | GitHub Release 附件；Chart 发布到公开 HTTP Helm 仓库 |
| `as`、Runtime Host | Linux/macOS × amd64/arm64 压缩包 | GitHub Release 附件 |
| Java Application SDK | `io.agentscope:agentscope-extensions-controlplane` 及所需依赖 | Maven Central，单独发布 |
| Python SDK | `agentscope-service-sdk` wheel、sdist | PyPI，单独发布 |
| DSH 插件 | `@agentscope-service/dsh-controlplane` npm 包 | npm，单独发布 |
| 用户文档 | 官网 Service 专区 | 合入 `main` 后由网站工作流部署 |

前端已经包含在 control 镜像中，不单独发布 npm 包。PostgreSQL 使用上游镜像或外部数据库。`service-common` 和 Service 可执行模块默认不发布到 Maven Central。完整 Service Chart 使用 standalone HTTP 模式；旧 Control Plane Chart 与 ASDP gRPC 属于另一种部署形态，发布说明应区分它们。

SDK 可以独立发版。SDK 内容未变且已有兼容公开版本时，发布说明引用现有版本即可；不能为了凑齐发布清单重复上传同版本包。

## 二、管理员首次发布前的一次性配置

### 2.1 代码仓库与发布入口

当前项目的 `origin` 是 `agentscope-ai/agentscope-java`。你需要相应的代码合并、tag、Actions 和 Release 操作权限，并遵循组织的分支保护规则。

先让 `.github/workflows/service-release.yml` 和 `service-dist-release.yml` 进入仓库默认分支，再使用 Actions 的手动发布入口或主 tag 自动发布入口。GitHub 要求 `workflow_dispatch` 工作流存在于默认分支，才能手动触发；实际构建仍可选定其他分支或 tag。[GitHub 官方说明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)

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
| PyPI | 首次配置 `agentscope-service-sdk` 的 GitHub Pending Publisher；已有项目则配置 Trusted Publisher |
| npm | `@agentscope-service` scope 和目标包发布权限；交互登录及账号要求的 2FA |
| 官网 | 网站工作流的写权限、GitHub Pages 发布源、`java.agentscope.io` 域名配置 |

PyPI 和 npm 使用第七节的独立 OIDC 工作流发布，无需 SDK 发布 Secret。本地 Twine 是替代方式，凭据可通过交互提示或 keyring 提供，避免写进命令和 Git。[PyPI 打包发布说明](https://packaging.python.org/en/latest/tutorials/packaging-projects/)、[Twine 凭据配置](https://packaging.python.org/en/latest/specifications/pypirc/)

npm 的发布权限和 2FA 要求由包设置决定；此处使用交互式 `npm login` / `npm publish`，按提示完成验证。[npm 官方发布认证说明](https://docs.npmjs.com/requiring-2fa-for-package-publishing-and-settings-modification/)

## 三、每次发布先填写版本清单

| 项目 | 本次需要确定的值 | 影响范围 |
| --- | --- | --- |
| Service 版本 | 例如 `2.0.3-rc.1` | 四个镜像、Chart version/appVersion、CLI、Compose 文件名 |
| Git tag | `v2.1.0-BETA1` | 唯一指向本次审核过的源码 |
| Registry namespace | 例如 `ghcr.io/agentscope-ai` | 镜像和 Chart 的公开安装地址 |
| Java 版本 | 根 `pom.xml` 的 `revision` | Java SDK、父 POM、相关 reactor 依赖 |
| Python 版本 | 两处 Python 版本声明 | PyPI 包版本 |
| DSH 版本 | `package.json` 与 lockfile | npm 包版本 |
| 支持范围 | 实际验收过的平台、数据库、存储和模型环境 | Release 中的兼容性声明 |

Service 参数不带 `v` 前缀，当前脚本不接受 `+build` 元数据。Python 的 RC 采用 Python 版本格式，例如 `0.1.1rc1`；npm 可用 `0.1.1-rc.1`。版本号不必全部一致，但必须在清单中建立对应关系。

需要更新的源码文件：

- Java：根 `pom.xml` 的 `<revision>`。初始发布准备提交中为 `2.0.3-SNAPSHOT`；发 Maven 正式制品前应确定可公开发布的非 SNAPSHOT 版本，并检查所需依赖。
- Python：`agentscope-service/service-controlplane/sdk/python/pyproject.toml` 的 `version` 和 `service-controlplane/sdk/python/agentscope_service/__init__.py` 的 `__version__`，两处同步。
- DSH：在 `agentscope-service/service-controlplane/sdk/dsh` 执行 `npm version 新版本 --no-git-tag-version`，核对 `package.json`、`package-lock.json`。
- Helm：`release.py package` 会把本次 Service 版本写入打包后的 Chart version/appVersion，无需为了打包手工修改模板中的默认版本。

先完成上述调整、Release Notes 草稿和相关测试，再提交经过审核的改动。当前开发分支上的其他功能修改也必须明确是否纳入本次版本，不能用一份旧候选包代表后来改动过的源码。

后续命令使用这些变量；请替换成你的实际选择：

```bash
export SERVICE_VERSION=2.0.3-rc.1
export RELEASE_TAG="v${SERVICE_VERSION}"
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

准备 Java 21、Maven、Go（按 `agentscope-service/service-controlplane/go.mod`）、Node.js 22、Python 3.10+、Docker Buildx、Helm 3.17+ 或兼容版本；使用后文 GitHub CLI 命令时还需要 `gh` 并完成 `gh auth login`。

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r agentscope-service/release/requirements.txt \
  -e 'agentscope-service/service-controlplane/sdk/python[dev]'
```

### 4.2 测试和网站构建

先设置 `CONTROL_PLANE_TEST_POSTGRES_DSN`，指向专门建立的临时 PostgreSQL 测试库。测试会修改数据库，不能使用开发或生产数据库。发布工作流已经提供临时 PostgreSQL。

```bash
python agentscope-service/release/release.py verify
mvn -B -ntp -T1 clean verify
cd docs
npm ci
npm test
npm run validate
npm run broken-links
cd ..
```

`verify` 覆盖 Service Java reactor、Go、前端、Python、DSH 和 Chart 检查；不是完整仓库 `mvn clean verify` 的替代。Go 共享测试库的包按 `-p 1` 串行执行。部分 Kubernetes controller 集成测试还需要 envtest 资源，按 Control Plane Makefile 的 `test-integration` 入口运行。

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

CLI 压缩包包含两个可执行文件和中英文安装说明。`agentscope-service-VERSION-kubernetes.tar.gz` 包含 Chart、Secret 配置模板、数据库 schema 初始化 SQL 及安装说明，同时保留独立的 Chart `.tgz`。SDK 已单独发布时，给 `package` 加 `--distributions-only`，只构建 CLI、Compose、Kubernetes 和 Chart 制品。

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

遵循仓库保护规则，先将最终发布源码和工作流合入 `main`，再在该提交创建主 tag。`service-dist-release.yml` 会核对主 tag、Java/Go 版本及 `main` 来源，并自动创建同一提交的 Go 模块 tag；镜像手动工作流的 `version` 仍由管理员填写并核对。

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
| Maven/PyPI/npm、GitHub Release、官网 | 不由镜像工作流发布；GitHub Release 和 Go 模块 tag 由第八节的独立工作流自动完成 |

`publish=false` 的镜像只存在于临时 runner，不是可下载的 Docker 镜像归档。需要本地安装演练时使用第四节的本地镜像构建命令。

### 主 tag 自动发布镜像

未来发版使用 `service-dist-release.yml` 自动串联安装包、镜像与 GitHub Release。先在仓库 **Settings → Secrets and variables → Actions** 配置两个 Repository secrets：

| Secret | 内容 |
| --- | --- |
| `SERVICE_REGISTRY_USER` | 能访问四个镜像仓库的 ACR 登录用户名 |
| `SERVICE_REGISTRY_TOKEN` | 该用户名对应的 ACR Registry 专用密码，不是阿里云控制台登录密码 |

登录域名从 Repository variable `SERVICE_IMAGE_REPOSITORY` 的镜像命名空间提取，默认 `sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope`；自动入口不需要 `SERVICE_REGISTRY_HOST`。本机 `docker login` 不会授权 GitHub 云端 runner。凭据含义见 [ACR 访问凭证文档](https://help.aliyun.com/zh/acr/user-guide/configure-access-credentials/)。上面的三个 Secret 仍适用于原手动镜像与 OCI Chart 工作流。

先准备 Java POM 与 Go 版本常量中的正式发布版本，将源码合入 `main`，再推送主 `vVERSION` tag。当前 Maven `2.1.0-SNAPSHOT` 用于开发，打下一版本的发布 tag 前需同步实际发布版本。自动工作流先构建安装包，然后推送 `as-controlplane`、`as-gateway`、`as-dataplane`、`as-scheduler`，镜像 tag 为不含 `v` 的 `VERSION`，每个同时包含 `linux/amd64` 与 `linux/arm64`。Buildx 请求 SBOM 与 provenance。

重跑时，已有镜像必须同时满足架构、版本和源码提交标签一致才能复用；冲突或认证、网络错误时停止，不覆盖。缺失镜像才会构建，推送后再次检查。部分推送失败可重跑 images job，复用已经成功的镜像。四个镜像全部成功后，才公开 GitHub Release，并自动上传含引用、digest、源码提交与架构的 `images.json`，把它纳入 `SHA256SUMS`。原始 Buildx 元数据保留在 `service-image-metadata` Actions artifact。公开 HTTP Helm 仓库与 Homebrew tap 保留独立发布流程，自动镜像 job 不依赖 Helm OCI 登录。

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

首次发布前，在 [PyPI 账号 Publishing 设置](https://pypi.org/manage/account/publishing/) 中新增 GitHub Pending Publisher：

| 字段 | 填写值 |
| --- | --- |
| PyPI project name | `agentscope-service-sdk` |
| Owner | `agentscope-ai` |
| Repository | `agentscope-java` |
| Workflow filename | `service-pypi-release.yml`，不要带 `.github/workflows/` 路径 |
| Environment | `pypi` |

工作流显示名称为 **AgentScope Service PyPI release**。它测试 Python 3.9–3.14，核对 tag/输入版本与 SDK 的两处版本声明，构建并校验 wheel/sdist，最后通过 OIDC 上传。仅上传 job 拥有 `id-token: write`，不需要 PyPI API token 或 GitHub SDK Secret；首次成功上传会创建 PyPI 项目。见 [PyPI Trusted Publishing](https://docs.pypi.org/trusted-publishers/creating-a-project-through-oidc/)。

推送包含该工作流的 `v*` 或 `agentscope-service-v*` tag 会触发发布，但源码必须已合入 `main`；手动入口要求工作流先进入默认分支。本次输入 `2.1.0-BETA1` 或等价的 Python 版本 `2.1.0b1`。配置 Publisher 并将工作流合入默认分支后，指定待发布的源码分支运行：

```bash
gh workflow run service-pypi-release.yml --repo agentscope-ai/agentscope-java \
  --ref main -f version=2.1.0-BETA1
```

发布后在新的虚拟环境中执行 `pip install agentscope-service-sdk==2.1.0b1` 并验证导入。已发布版本不能重复上传。需要本地 Twine 替代方式时，先确认包名归属、版本未发布及 API token，再执行：

```bash
export RELEASE_ARTIFACT_DIR=/private/path/release-artifacts/VERSION
python -m twine check \
  "$RELEASE_ARTIFACT_DIR"/agentscope_service_sdk-*.whl \
  "$RELEASE_ARTIFACT_DIR"/agentscope_service_sdk-*.tar.gz
python -m twine upload \
  "$RELEASE_ARTIFACT_DIR"/agentscope_service_sdk-*.whl \
  "$RELEASE_ARTIFACT_DIR"/agentscope_service_sdk-*.tar.gz
```

根据 Twine 提示输入 API token，或使用已配置的 keyring。不要上传整个发布目录，Compose 和 CLI 的 `.tar.gz` 不是 Python 包。发布后在新的虚拟环境中执行 `pip install agentscope-service-sdk==实际版本` 并验证导入。

### 7.2 DSH → npm

```bash
npm login
python agentscope-service/release/release.py publish-npm --version "$SERVICE_VERSION"
```

发布命令会测试、构建并将 `@agentscope-service/dsh-controlplane` 公开发布到 `https://registry.npmjs.org`，预发布自动选择 `next`，稳定版选择 `latest`。加 `--dry-run` 可检查候选包而不上传；实际发布要求已提交的干净源码。

`service-npm-release.yml` 会在推送 `v*` 或 `agentscope-service-v*` tag 时自动发布，也支持手动填写版本；tag/输入版本必须与 SDK 版本一致。首次本地发布成功后，在 npm 包设置中配置 Trusted Publisher：GitHub owner 为 `agentscope-ai`，repository 为 `agentscope-java`，workflow 为 `service-npm-release.yml`，environment 为 `npm`。工作流使用 npm 11 和 OIDC，无需 `NPM_TOKEN`。tag 对应源码须包含该工作流且已合入 `main`；手动入口还要求它进入默认分支。也可用 npm 11.15+ 完成首次绑定：

```bash
npm trust github @agentscope-service/dsh-controlplane \
  --repo agentscope-ai/agentscope-java --file service-npm-release.yml \
  --env npm --allow-publish
```

首次绑定可能需要浏览器/2FA 验证。 新 Trusted Publisher 须在两天内完成首次成功的 CI 发布；否则下次发布前需要重建过期绑定。已经从本机上传的首版不能重复上传来验证 CI。见 [npm Trusted Publishing](https://docs.npmjs.com/trusted-publishers/#trusted-publisher-configuration-expiry)。用 `npm trust list @agentscope-service/dsh-controlplane` 确认配置。发布前核对 tarball 内的实际版本，按 npm 提示完成 2FA。发布后在独立目录安装 `@agentscope-service/dsh-controlplane@实际版本` 并验证导入。

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
mvn -B -ntp -pl agentscope-extensions/agentscope-extensions-controlplane -am \
  -Drevision="$JAVA_RELEASE_VERSION" -Prelease deploy
```

此处 Java 版本仍是示例，应与版本清单一致。`-am` 会包含所需 reactor 模块和父 POM；发布前审查整个 reactor 的坐标，确保没有重复发布或未解析的 SNAPSHOT 依赖。不要为了发 SDK 而打开 Service 可执行模块的 Maven 发布开关。

**Maven 命令完成后仍需检查 Central Portal。** 当前 POM 没有开启 `autoPublish`；插件默认上传供校验及人工发布。进入 Portal 查看此次 deployment，确认校验通过并完成 Publish，直到状态显示发布完成，再从独立消费者项目验证依赖解析。[Sonatype Maven 插件说明](https://central.sonatype.org/publish/publish-portal-maven/)

## 八、发布 GitHub Release 与官网

### 8.1 整理 Release 附件与说明

从同一 tag 的工作流取出以下文件：

- Compose `.tar.gz`、Helm `.tgz`。
- Kubernetes `.tar.gz`，包含 Chart、配置模板和初始化 SQL。
- 四个平台的 `agentscope-cli-*.tar.gz`，每份包含 CLI 与 Runtime Host。
- `release-manifest.json`、`SHA256SUMS`。

以上九个安装包附件与镜像 digest 清单 `images.json` 由主 tag 工作流自动上传，共十个附件。Python wheel/sdist 和 DSH npm tarball 由各自发布流程提供，按实际发布情况另行补充。

只上传这些公开制品；不要把工作目录、测试 `.env`、数据库备份或密钥一起打包。GitHub 自动生成的源码压缩包不能替代 Compose、CLI 和 SDK 附件。

工作流 **AgentScope Service distribution release**（`service-dist-release.yml`）在推送主 `v*` tag 时自动执行。先更新根 POM 的 Java revision 与 Go 版本常量，完成发布验证并把源码、工作流合入 `main`，然后只创建主 tag。例如准备好下一版本后：

```bash
git fetch origin main
RELEASE_TAG=v2.1.0-BETA2
git tag -a "$RELEASE_TAG" origin/main -m "$RELEASE_TAG"
git push origin "$RELEASE_TAG"
```

工作流校验提交已进入 `main`、Java/Go 版本与 tag 一致、Go 模块主版本一致，执行打包和 CLI 测试并构建上述附件。四个双架构镜像推送完成后，附件和镜像清单先上传至草稿，随后创建指向同一提交的 `agentscope-service/service-controlplane/vVERSION` tag，最后公开 GitHub Release；带预发布后缀的版本自动标记为 prerelease。Release Notes 保持一句话，链接到中英文官方文档。

仅 GitHub Release 发布 job 使用 `contents: write` 和仓库自带的 `GITHUB_TOKEN`；镜像 job 使用第六节的两个 ACR Secret。可选的 Repository variable `SERVICE_IMAGE_REPOSITORY` 控制安装包内引用的镜像命名空间，默认是 `sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope`。此工作流构建并推送四个镜像，不发布 Maven/npm/PyPI，也不更新独立的 HTTP Helm 仓库或 Homebrew tap；各渠道保留已有发布流程。SDK 工作流仍独立响应主 tag。

对于已经包含此工作流的现有 tag，可手动重跑，无需新建 tag：

```bash
gh workflow run service-dist-release.yml --repo agentscope-ai/agentscope-java \
  --ref main -f tag=vVERSION
```

已经公开的 Release 保持不变；同提交的 Go tag 和同校验和的草稿附件会复用，tag 或校验和冲突时停止，不覆盖。失败时优先重跑 **publish job**，复用该次 Actions 原始制品；重新打包可能生成不同字节，不能覆盖已有草稿附件。已发布的 `v2.1.0-BETA1` 与 Go tag 早于此工作流，保持原样，自动化用于之后包含该工作流的新版本。通过 `GITHUB_TOKEN` 创建的 Go tag 不会再次触发 push 工作流，避免递归发布。见 [GitHub 工作流触发说明](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)。

公开 HTTP Helm 仓库和可选的 Homebrew tap 是独立发布渠道。

用户按 [Runtime Host 安装指南](https://java.agentscope.io/v2/zh/service/runtime-host)，通过 `go install` 安装同一版本的 CLI 和 Runtime Host。可选的个人公开 tap 为 [chickenlj/homebrew-tap](https://github.com/chickenlj/homebrew-tap)。配方源码保留在 `release/homebrew/agentscope-cli.rb`。每次 CLI 发版后，更新四个平台的 URL、已验证的 SHA-256、配方版本和测试中的版本断言，再将配方发布到 tap。既有二进制附件保持不变。组织仓库的 CI 若要自动更新这个个人仓库，需要另行配置对 tap 拥有 Contents 写权限的凭据；默认 `GITHUB_TOKEN` 不包含这个跨仓库权限。

Go 模块要求 Go 1.26，使用 `/v2` 模块路径。除主 `vVERSION` tag 外，安装包工作流会在同一提交创建嵌套模块 tag `agentscope-service/service-controlplane/vVERSION`，用户即可通过 `@v2.1.0-BETA1` 安装两个命令，见中英文 Runtime Host 安装页。后续 Go 版本同样需要两个 tag 指向同一提交，管理员只需推送主 tag，不移动已发布 tag。

需要手动发布作为替代方案时，先在 GitHub Releases 建立草稿，选用已存在的 tag，上传附件并校验下载。若使用 CLI，先在仓库之外准备 Markdown Release Notes：

```bash
gh release create "$RELEASE_TAG" --repo "$RELEASE_REPO" \
  --verify-tag --draft \
  --title "AgentScope Service $SERVICE_VERSION" \
  --notes-file /private/path/release-notes.md
```

通过草稿页面添加上述附件。RC 勾选 **Set as a pre-release**；稳定版完成验收后再设置为正式发布及合适的 latest 状态。公开 Release 说明保持一两句话，并链接到官方 Service 文档。Compose 快速启动、
Go CLI 安装、公开 HTTP Helm 仓库与运行要求集中维护在相应安装文档中。
制品清单、校验和及详细验证记录继续保留在 Release 附件中。

### 8.2 发布官网

公开 HTTP Helm 仓库位于 [chickenlj/helm-charts](https://github.com/chickenlj/helm-charts)。
先公开发布上游 `vVERSION` Release，并上传独立 Chart `.tgz`，然后运行独立仓库的工作流。
该分发渠道不要求执行前面的 OCI Chart 推送：

```bash
gh workflow run publish.yml --repo chickenlj/helm-charts --ref main -f version=2.1.0-BETA1
helm repo add agentscope https://chickenlj.github.io/helm-charts
helm repo update agentscope
helm pull agentscope/agentscope-service --version 2.1.0-BETA1
```

后续发版替换为实际版本。工作流从上游 Release 下载原包，核对 SHA256 与 Chart 元数据，
保留旧版本索引，拒绝以不同内容覆盖已发布版本，并部署 GitHub Pages。
不需要额外 registry 凭据或跨仓库写入 token。官网跳转配置虽已合并，但线上 `/helm/index.yaml` 仍返回 404；
安装文档默认使用已验证的 Pages 地址，待官网入口通过实际 Helm 检查后再启用别名。

官网源码位于 `docs/v2/{zh,en}/service/`。把对应版本文档按审核流程合入 `main`，检查 **Validate Mintlify Docs** 工作流，并确认 Mintlify 从 `main` 部署成功；文档校验通过本身不等于官网已上线。

上线后使用浏览器打开：

- 中文：`https://java.agentscope.io/v2/zh/service/index`
- 英文：`https://java.agentscope.io/v2/en/service/index`

确认 Service 导航、语言切换、搜索、图片、直接页面链接均可用，并核对安装文档使用的是已经公开可获取的版本。随后完成 GitHub Release 的公开发布。

## 九、以普通用户身份验收，才算完成

使用没有管理员 registry 登录状态的临时客户端或全新 CI job 验证公开获取，避免复用本机缓存把私有镜像误判为公开可用：

```bash
docker pull "$IMAGE_REPOSITORY/as-controlplane:$SERVICE_VERSION"
docker pull "$IMAGE_REPOSITORY/as-gateway:$SERVICE_VERSION"
docker pull "$IMAGE_REPOSITORY/as-dataplane:$SERVICE_VERSION"
docker pull "$IMAGE_REPOSITORY/as-scheduler:$SERVICE_VERSION"
helm repo add agentscope https://chickenlj.github.io/helm-charts
helm repo update agentscope
helm pull agentscope/agentscope-service \
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
