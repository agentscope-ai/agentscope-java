# Pinned external inputs / 固定外部输入

This directory maintains locks, selections and adaptations, not upstream snapshots or generated tasks.
capability tasks are owned and are not managed here.
本目录维护版本锁定、选题和适配，不保存上游快照或生成题目。能力评测题属于自有题。

## Terminal Bench

`terminal-bench/source.lock.json` records an immutable commit, required file SHA256 values and expected
adapted task hashes. Preparation fetches selected tasks, preserves the original verifier entrypoint as
`tests/upstream-test.sh`, and applies checked-in overlays. Original test assertions and environment
resources remain unchanged. Exported environment resources are the image build inputs.

`terminal-bench/source.lock.json` 记录不可变 commit、必需文件 SHA256 和适配后任务哈希。
准备时下载选中题目，将原验证入口保留为 `tests/upstream-test.sh`，再应用覆盖文件。
原始测试断言和环境资源不变，镜像使用导出题目中的环境资源。

From the harness directory / 在 harness 目录中：

```sh
python eval.py prepare-tasks configs/suites/full.json
python eval.py export-tasks configs/suites/full.json --output artifacts/export-full
# Replace the placeholder with a digest-pinned image / 替换为固定 digest 的镜像
docker build -f environments/Dockerfile.tb-fix-git \
  --build-arg NATIVE_IMAGE=YOUR_NATIVE_IMAGE_AT_DIGEST artifacts/export-full
```

Record the resulting image digest. Recipes install system packages, so source locks alone do not
ensure bit-identical images. / 记录结果镜像 digest；配方仍安装系统包，来源锁定不代表镜像逐位可复现。

## Business

`business/inventory.json` selects official task IDs. One `business/template/` replaces repeated wrappers.
`business/sources.lock.json` pins tau-bench/AppWorld commits, tau test declarations, AppWorld version
metadata and LFS pointers. Preparation downloads and verifies these files, then expands selected IDs.
Official answers are not copied into agent fixtures.

`business/inventory.json` 选择官方任务 ID，`business/template/` 替代重复包装。
`business/sources.lock.json` 固定 tau-bench/AppWorld commit、tau 测试声明、AppWorld 版本元数据和
LFS 指针。准备时下载并校验这些文件，再展开选中 ID。官方答案不复制进 Agent fixture。

**Preparation does not install official worlds.** The backend must supply isolated official packages
at these commits, AppWorld data version `0.2.0`, and the corresponding databases, tools and evaluator.
Keep large data outside Git. `prepare_appworld.py` fetches installed AppWorld LFS objects against an
explicit commit and verifies SHA256/size. Before trials, the runner must verify and record actual
package, data and image identities against the exported manifest. A prepared wrapper does not prove
that verification. Missing business backends remain errors. No cloud/cluster deployment is included.

**准备题目不等于安装官方环境。** backend 必须提供对应 commit 的隔离官方包、AppWorld `0.2.0`
数据以及相应数据库、工具和评分器，大文件留在 Git 外。`prepare_appworld.py` 根据明确 commit 获取
已安装 AppWorld 的 LFS 对象并核对 SHA256/大小。runner 必须在试验前记录并核对实际包、数据和
镜像身份与导出清单一致；准备包装本身不能证明已完成核对。缺少 backend 仍明确报错，不包含云或集群部署。

## Offline use and updates / 离线与更新

`prepare-tasks CONFIG --offline` uses verified cached downloads. Removing generated tasks and
preparing again reproduces their hashes. Corrupt caches fail instead of being silently replaced;
remove affected entries explicitly before downloading again. Export and release validation never download.

`prepare-tasks CONFIG --offline` 使用已校验下载缓存。删除生成任务后重新准备，应重建相同哈希。
损坏缓存明确失败，不静默替换；需要显式删除损坏条目后再下载。导出和发布校验不下载。

Update upstream deliberately: change the commit, input hashes, affected selections/overlays and
expected output hashes, review assertions and environment compatibility, then establish a new baseline.
Ordinary preparation never follows a floating branch or modifies suite profiles.

上游更新需显式修改 commit、输入哈希、受影响的选题/覆盖文件和预期输出哈希，检查断言及环境
兼容性，再建立新基线。普通准备不跟随浮动分支，也不改写套件配置。
