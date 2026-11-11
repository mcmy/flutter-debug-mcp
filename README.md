# Flutter Debug MCP Server

Flutter debugging through MCP for IntelliJ IDEA and Android Studio.

IDEA 插件直接提供原生 **Streamable HTTP MCP**。客户端使用 URL 连接，无需 Python、stdio 转发或额外请求头。

默认全部分类权限开启，服务总开关关闭。启用后始终列出全部47个工具，调用时才检查权限。权限拒绝返回分类名称及 `Settings > Tools > Flutter Debug MCP`，客户端没有修改权限或开启服务器的接口。

## 安装、配置与复制

插件及设置列表显示名为 **Flutter Debug MCP**，MCP 服务名为 `flutter-debug-mcp`，插件 ID 为 `dev.mcmy.idea-flutter-mcp`，Java 包名为 `dev.mcmy.ideafluttermcp`。

插件作者为 **mcmy**。本版统一使用 `dev.mcmy` 标识与 Java 包名。插件 ID 已变更，IDEA 将其识别为新插件；安装前先卸载旧 ID 的 Flutter Debug MCP，避免两个版本同时加载。设置存储标识与私有缓存目录保持不变，以便在同一 IDE 配置目录下继续读取原有端口、权限、URL令牌和审计。

安装包：`dist/flutter-debug-mcp-0.4.1.zip`。

通过 GitHub 自定义插件仓库安装及后续更新，见 [发布与自定义插件仓库](docs/publishing.md)。推送与源码版本一致的 `v*` tag（如 `v0.4.1`）会自动构建、运行回归并发布包含插件 ZIP 与仓库索引的 GitHub Release。首次发布完成后，可在 IDEA 的 **Manage Plugin Repositories** 中添加：

```text
https://github.com/mcmy/flutter-debug-mcp/releases/latest/download/updatePlugins.xml
```

1. IDEA → Settings → Plugins → 齿轮 → Install Plugin from Disk，选择安装包。如果IDE要求重启，由用户选择重启时机。
2. Settings → Tools → **Flutter Debug MCP**，在左侧「配置」中启用服务并 Apply。左侧另外两个菜单是「权限」和「日志」，分别用于授权与查询操作记录。
3. 点击 **复制 URL** 可直接配置支持 Streamable HTTP 的客户端；配置格式可选 **Codex TOML** 或 **MCP JSON**，点击 **复制配置** 获取完整配置。
4. 监听端口默认 **29191**，可修改为1024–65535。输入时更新预览，Apply 后切换监听。端口占用会报错并保留原监听和配置，不自动改用随机端口。
5. 权限和开关会持久化。关闭服务释放监听与专用VM连接，不停止用户Flutter应用。

URL形如 `http://127.0.0.1:29191/mcp/<私有令牌>`，可直接粘贴，无需请求头。令牌只保存在当前用户私有缓存，按IDE配置目录隔离，重启和修改端口不轮换。URL包含访问权限，请仅交给本机MCP客户端。设置页显示当前实际监听状态，并标识未保存的端口修改；Cancel/Reset不应用预览配置。设置页使用左侧菜单与右侧内容布局，菜单和内容之间仅用主题细线分隔，外围滚动框不显示边框；菜单选中态和配置预览底色使用平台小圆角，配置预览带主题细边框；“服务”和“客户端连接”按原生分组标题排列。长URL在输入框内横向滚动，复制链接保持可见，配置预览自动折行。

Codex 项目配置示例（完整URL从设置页复制，不使用占位符）：

```toml
[mcp_servers.flutter-debug-mcp]
url = "http://127.0.0.1:29191/mcp/<从设置页复制的令牌>"
enabled = true
startup_timeout_sec = 20
tool_timeout_sec = 120
```

通用JSON配置示例：

```json
{
  "mcpServers": {
    "flutter-debug-mcp": {
      "url": "http://127.0.0.1:29191/mcp/<从设置页复制的令牌>"
    }
  }
}
```

多个IDE同时运行时，分别在设置页选择不同端口并复制对应URL。修改URL后重新加载客户端MCP连接。首次未启用服务时不会创建网络监听；曾保存为开启时，后续项目启动按保存状态启动。

## 工具能力

| 范围 | 功能 |
| --- | --- |
| 发现 | 开放项目、Flutter会话、设备与选中状态、运行配置及完整应用启动参数、现有DTD和VM地址 |
| 运行 | 选择设备、创建/修改IDEA应用运行配置、启动Run/Debug、停止、重新运行、hot reload、hot restart |
| 日志 | stdout/stderr、稳定行号、分页、字面量搜索、大小写选项、来源筛选、匹配上下文扩展 |
| 事件 | 订阅Debug、Isolate、Logging、Stdout、Stderr、Extension、GC、Timeline并按行查询 |
| DevTools | 获取或由官方Flutter插件启动DevTools，返回连接URL |
| 断点 | IDEA持久化Dart行断点、条件、启用/禁用、删除；VM生命周期内临时断点和异常暂停策略 |
| 调试 | 暂停、继续、step over/into/out、isolate步进、调用栈、局部变量 |
| 值与源码 | 对象字段、集合分页、表达式执行、对象方法调用、源码行及上下文（含未保存文档） |
| 性能 | 内存、类分配、实例、保留路径、CPU样本、VM时间线 |
| Flutter扩展 | 调用当前SDK注册的ext.flutter.* / ext.dart.io.*，用于Widget树、Inspector、截图、调试显示开关等 |
| 权限与审计 | 查询分类权限、按文字搜索最近2000条跨IDE重启保留的操作记录 |

详见 [工具清单](docs/tools.md)。高级 `flutter_vm_call` 仅支持白名单，并按实际方法检查权限。未知方法拒绝执行。

## 调试流程与日志

先调用 `flutter_projects` 和 `flutter_sessions` 获取目标项目及 `sessionId`；多个开放项目时项目级工具必须提供 `projectPath`。`flutter_dtd` 只读取现有DTD，未就绪返回 `ready=false, uri=null`，不会自行创建DTD。

`flutter_configurations` 直接返回 Flutter 应用配置的 `id`、`name`、`filePath`（Dart entrypoint）、`additionalArgs`（Additional run args）、`attachArgs`（Additional attach args）、`buildFlavor`、`envs`（环境变量实际值）、`selected` 和 `selectedDeviceId`。`flutter_get_configuration` 可读取单个配置；非应用配置仍在列表中，以 `editable=false` 标识，不提供这组应用字段。读取配置使用 `discovery` 权限，选择设备、创建与修改配置使用 `run` 权限；审计不保存参数和环境变量值。

AI 可先读取配置，再调用 `flutter_update_configuration` 保存局部修改，例如：

```json
{
  "configurationId": "从 flutter_configurations 返回的 id 原样复制",
  "filePath": "lib/main_dev.dart",
  "additionalArgs": "--dart-define=API_ENV=dev --verbose",
  "attachArgs": "--app-id=com.example.app",
  "buildFlavor": "dev",
  "envs": {"API_ENV": "dev"}
}
```

仅传需要修改的字段，省略的字段保留；`additionalArgs`、`attachArgs`、`buildFlavor` 传空字符串清除。`envs` 整体替换当前配置的环境变量，传 `{}` 清空；需要保留旧变量时先读取并合并。入口支持项目相对路径，保存时转换为绝对路径并检查 Dart 文件存在。`name` 可重命名，之后使用返回的新 `id`。`flutter_create_configuration` 必须提供 `name` 和 `filePath`，可同时传入其余字段；新配置保存在 IDEA 本地工作区。现有配置的存储位置及“执行前”任务保持原样，暂不提供编辑任务链的接口。保存不自动停止或重新启动进程，参数按 IDEA 的规则解析，不执行 shell 展开。

`flutter_devices` 列出当前已连接设备及 `selected` 状态，`flutter_select_device` 接受精确 `deviceId` 并切换项目 Flutter 工具栏设备。也可在 `flutter_run` 传 `deviceId`，先选择再调度启动；省略则沿用当前工具栏设备。设备选择属于项目状态，不绑定到单个配置；启动完成前避免并发切换设备，通过 `flutter_sessions` 确认实际设备。它不会把已有运行会话迁移到新设备，也不负责启动尚未连接的模拟器。

`flutter_logs` 示例：

```json
{
  "sessionId": "从flutter_sessions获取",
  "query": "Exception",
  "before": 5,
  "after": 15,
  "limit": 200
}
```

日志从1基行号开始，响应包含 `lines`、`matches`、`firstAvailableLine`、`nextFromLine`、`hasMore`、`truncated` 和未换行 `pending`。分页使用 `nextFromLine`；通过匹配行号和 `before/after` 读取更多上下文，重叠上下文合并。`limit` 必须容纳一组完整上下文，否则返回错误。日志保留最多20000行，事件5000行，每个缓存同时限制约400万字符；单行超过16384字符分为连续行。开启采集之前的控制台历史不回填。

调用 `flutter_capture_events` 后用 `flutter_events` 查询VM事件；不同流单独返回订阅结果。`flutter_set_breakpoint` 设置可在IDEA中查看的持久断点，命中后 `flutter_get_vm` → `flutter_get_isolate` → `flutter_get_stack` 获取暂停状态、栈帧和变量引用，用 `flutter_get_object` 展开或 `flutter_evaluate` 求值。

运行/重新运行/停止/IDEA步进返回 `accepted` 表示已交给IDE处理，需查询会话/VM确认实际结果；热重载/热重启等待Flutter实际响应。Hot restart后重新获取isolate/object ID，rerun后重新获取sessionId。超时后先查询状态，不盲目重发变更操作。

Flutter扩展依运行SDK而异，先用 `flutter_get_isolate` 的 `extensionRPCs` 查看实际注册的方法，再用 `flutter_service_extension` 传入真实参数。Release模式、VM和设备平台可能限制调试、CPU采样或扩展，具体失败由底层返回。

## 权限、记录与传输

8类权限为 `discovery`、`logs`、`run`、`inspect`、`debug`、`evaluate`、`extensions`、`devtools`。默认全部开启；权限撤销后工具仍在目录中，调用返回缺少的分类与设置入口。已发起的操作不会因权限变化而撤销。总开关关闭会断开HTTP服务，此时客户端无法获取目录；开启服务后目录不因分类权限变化而减少。

审计保存时间、实际方法名、ok/denied/error和耗时，不保存参数、表达式、响应、日志正文或令牌。设置页与 `flutter_audit` 均可查询，关闭服务后设置页仍可查询历史。私有缓存为 macOS `~/Library/Caches/idea-flutter-mcp/` 或 Linux `~/.cache/idea-flutter-mcp/`，目录0700，凭据和记录0600；仅支持POSIX权限平台。

服务只绑定127.0.0.1，拒绝浏览器Origin并验证URL令牌。采用无状态Streamable HTTP：POST JSON-RPC，响应为application/json；通知返回202；不提供独立GET SSE流（返回405），无需MCP-Session-Id。支持2025-11-25、2025-06-18、2025-03-26协议。运行时事件通过工具查询，不通过SSE推送。

## 开发与验证

可直接用IDEA或Android Studio打开本目录作为Gradle Java项目。最低支持正式版平台为 **2026.1 / 261.22158.277**，兼容声明为 `261.22158.277–263.*`，面向IDEA 2026.1/2026.2/2026.3，以及使用这些平台分支的Android Studio 2026.x；不承诺早于2026.1正式版的EAP构建。

产物统一使用 **Java21字节码**，以适配2026.1自带的Java21，也可由262/263自带的Java25加载。默认编译SDK为官方 `ideaIC 2026.1`，下载的编译库放在 `~/Library/Caches/idea-flutter-mcp-sdk/ideaIC-2026.1/`，不是安装或启动旧版IDE；默认JDK为本机 `ms-21.0.10`。SDK来源：[JetBrains官方Maven仓库](https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/ideaIC/2026.1/ideaIC-2026.1.zip)，其他机器可解压到此目录，或使用 `--idea-home` / `-PideaHome` 指向包含 `lib/` 的SDK目录。

Flutter98.0.0、Dart511.0.0作为编译依赖，最低平台要求分别为252和253，其字节码均兼容Java21。当前机器这两个插件来自IDEA插件目录；所有IDE/Flutter/Dart JAR都只做编译依赖，不打入插件。

Android Studio和IDEA都需安装并启用官方Flutter、Dart插件。插件使用通用IntelliJ平台与Flutter/Dart接口，无需Android专用API依赖。两个IDE同时启用时需要不同监听端口；URL令牌和权限按IDE配置目录隔离。

```sh
# 261 SDK + Java21，作为最终发布包的构建基线
python3 build.py --java-home $HOME/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home
# 或
JAVA_HOME=$HOME/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home ./gradlew --no-daemon clean buildPlugin

# Android Studio262 SDK + JBR25，独立验证新版平台API
python3 build.py --idea-home "$HOME/Applications/Android Studio.app/Contents" --java-home "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# IDEA263 SDK + JDK26，独立验证新版平台API
python3 build.py --idea-home "$HOME/Applications/IntelliJ IDEA.app/Contents" --java-home $HOME/Library/Java/JavaVirtualMachines/openjdk-26.0.2.1/Contents/Home
```

构建执行 `CoreTest`、`VmConnectionTest`、`McpHttpServerTest`，分别覆盖日志/权限/审计、VM WebSocket协议、MCP HTTP生命周期/目录/权限拒绝恢复/参数/端口冲突/URL与配置。它们是启用断言的独立Java入口，由Gradle check显式执行，不是JUnit。`build.py`支持覆盖IDEA、Dart、Flutter、Java路径；Gradle支持 `-PideaHome` 和 `-PpluginsHome`。

已完成IDEA261 SDK/Java21、Android Studio262 SDK/JBR25、IDEA263 SDK/JDK26三套编译、协议回归及打包；尚未在Android Studio中安装插件并进行真实设备调试。使用设置页布局源码、示例连接数据和Darcula主题完成540/660/960宽度离线渲染，检查复制控件边界及菜单切换。离线渲染未连接IDE服务，主题细节不等同于已安装IDE；新版真实IDE设置页交互、真实设备调试及端到端验证仍需安装后执行。构建不会安装插件、启动真实IDE服务或重启应用。

实现参考：[Flutter IntelliJ](https://github.com/flutter/flutter-intellij)、[JetBrains Settings示例](https://github.com/JetBrains/intellij-sdk-code-samples/tree/main/settings)、[Dart VM Service](https://github.com/dart-lang/sdk/blob/main/runtime/vm/service/service.md)、[MCP Streamable HTTP规范](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)。未复制或打包这些项目的实现源码。
