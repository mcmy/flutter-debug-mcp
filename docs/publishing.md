# 发布到 GitHub 自定义插件仓库

本项目使用 **GitHub Release 安装包 + XML 仓库索引** 分发，无需部署服务器、开启 GitHub Pages 或提交到 JetBrains Marketplace。只推送源码到 public 仓库还不能让 IDEA 安装插件，需要发布下面两份 Release 附件。

当前仓库：`mcmy/flutter-debug-mcp`。如果换了 GitHub 仓库，生成索引时传 `--repository owner/repo`，并同步修改 README 中的仓库地址。

## 自动发布（推荐）

项目已配置 [Release workflow](../.github/workflows/release.yml)，推送 `v*` tag 时触发。普通分支提交不触发发布。workflow 文件需要包含在 tag 指向的源码提交中。

首次发布当前版本：

```sh
# 先提交本次源码与 workflow，再推送分支
git push origin main
git tag -a v0.5.0 -m "Release 0.5.0"
git push origin v0.5.0
```

后续发布前，同步更新 `build.gradle.kts` 和 `src/main/resources/META-INF/plugin.xml` 中的版本，提交后推送对应 tag，例如 `v0.5.1`。tag 必须严格等于 `v<plugin.xml 版本>`，两个文件版本不一致或 tag 不匹配时，CI 会在下载依赖前失败。

workflow 在 Ubuntu 上使用 Java 21，自动从 JetBrains 下载固定版本的 IntelliJ IDEA 2026.1 SDK、Dart 511.0.0 和 Flutter 98.0.0，执行 `build.py` 的三组回归测试与打包，再从安装包生成索引。安装包和 `updatePlugins.xml` 先上传至草稿 Release，两份上传完成后发布。正式版设为 Latest；如 `v0.5.1-rc.1` 的预发布 tag（源码版本也需为 `0.5.1-rc.1`）会发布为 Pre-release，不改变正式版仓库入口。

无需手动配置 PAT 或其他 Secret，使用 GitHub 自动提供的 `GITHUB_TOKEN`，workflow 已声明 `contents: write`。仓库需要启用 GitHub Actions；若组织策略禁止 Actions 创建 Release，需调整对应策略。

可在仓库 **Actions → Release plugin** 查看构建及发布结果。下载、编译或回归失败时不会发布 Release；附件上传中断时可能保留草稿，可修复问题后重新运行该 job。已公开的同名 Release 不会被覆盖。每次正式版发布都会切换 Latest，应按版本递增顺序发布，避免用旧版本 tag 回退正式仓库。

本地只需提交 workflow 和源码，`dist/` 继续由 Git 忽略。自动发布成功后，下方的固定仓库 URL 即可使用。首次自动发布之前不要先手动创建并公开同名 Release。

## 手动发布（备用）

1. 将源码提交并推送到 public GitHub 仓库。`dist/` 已被 Git 忽略，安装包和生成的索引通过 Release 附件上传，不需要加入源码提交。
2. 在已准备好 IntelliJ 平台 SDK、Flutter/Dart 编译依赖的机器上构建，详见 README 的开发说明。自动发布 workflow 会自行下载这些依赖，本地构建可传参指定路径。

   ```sh
   python3 build.py
   # 或在配置好 JAVA_HOME、ideaHome、pluginsHome 后：
   ./gradlew --no-daemon clean buildPlugin
   ```

3. 从实际安装包生成索引：

   ```sh
   python3 prepare_repository.py dist/flutter-debug-mcp-0.5.0.zip
   ```

   输出 `dist/updatePlugins.xml`。插件 ID、版本、兼容范围、名称、说明和必要插件依赖均读取安装包内的 `META-INF/plugin.xml`。默认下载地址指向 `v0.5.0` Release 下的对应 ZIP。如果 tag 使用其他名称，传入 `--tag 实际标签名`。

4. 在 GitHub → Releases → Draft a new release，创建 tag **`v0.5.0`**，确认 tag 指向本次构建对应的源码提交。上传两份附件：

   - `dist/flutter-debug-mcp-0.5.0.zip`
   - `dist/updatePlugins.xml`

   发布为正式 Release，并设为 **Latest**。首次发布前固定仓库地址尚不可用；Draft 和 Pre-release 不用于这个正式仓库入口。不要上传 GitHub 自动生成的 Source code ZIP 作为插件安装包。

5. 发布后打开以下地址验证：第一项应返回 XML，XML 的 `url` 应能下载本次插件 ZIP。

   ```text
   https://github.com/mcmy/flutter-debug-mcp/releases/latest/download/updatePlugins.xml
   https://github.com/mcmy/flutter-debug-mcp/releases/download/v0.5.0/flutter-debug-mcp-0.5.0.zip
   ```

索引使用固定的 latest 入口，索引内的 ZIP 地址固定到具体 tag，避免索引和安装包版本错配。

## 在 IDEA 中添加

1. Settings / Preferences → Plugins → 齿轮 → **Manage Plugin Repositories**。
2. 点击 `+`，填入以下完整地址：

   ```text
   https://github.com/mcmy/flutter-debug-mcp/releases/latest/download/updatePlugins.xml
   ```

3. 确认后，在 Marketplace 页搜索 **Flutter Debug MCP** 并安装，按 IDE 提示重启。

这里需要填写 XML 的下载地址。GitHub 项目首页、Release 页面和 ZIP 地址均不能作为仓库地址。IDE 所在机器需要能够访问 GitHub 及其附件下载域名。

IDE 中需安装并启用官方 Flutter 和 Dart 插件。目前兼容声明为 `261.22158.277–263.*`，以安装包及生成索引中的 `idea-version` 为准；不兼容的 IDE 可能不显示插件或报告没有可用插件。

如果之前通过 Install Plugin from Disk 安装，在支持更新来源设置的 IDEA 中，可在 Plugins → Installed → Flutter Debug MCP → Additional Info → **Updates from** 中选择这个自定义仓库，再检查更新。

## 后续版本

推荐同步更新两个版本文件并提交后，推送对应 `v*` tag 交给 workflow 自动发布。以下为手动发布流程：

1. 同步修改 `build.gradle.kts` 的 `version` 和 `src/main/resources/META-INF/plugin.xml` 的 `<version>`，新版本应递增；`build.py` 已从 `plugin.xml` 读取包名版本。
2. 构建新 ZIP，并对新 ZIP 再次运行 `prepare_repository.py`。
3. 提交源码并创建与索引一致的新 tag，将新 ZIP 和新生成的 `updatePlugins.xml` 一起上传到新的正式 Release，设为 Latest。
4. 使用者保留原仓库地址，在 IDEA 中检查插件更新即可。每个新的 Latest Release 都必须带有 `updatePlugins.xml` 附件。

如果未来的新版本不再支持旧 IDE，该单索引只提供最新版本；需要继续为旧 IDE 提供可发现的版本时，应为不同兼容范围维护独立索引与仓库入口。

参考：[JetBrains 自定义插件仓库格式](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html)、[IDEA 插件仓库及更新来源](https://www.jetbrains.com/help/idea/managing-plugins.html)、[GitHub Release 固定下载链接](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)。
