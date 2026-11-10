# MCP 工具清单

工具通过原生 Streamable HTTP 提供，始终全部暴露；实际调用按设置检查权限。完整类型和范围由 `tools/list` 的 JSON Schema 提供。

| 工具 | 功能 | 必填参数 |
| --- | --- | --- |
| `flutter_projects` | 列出 IDEA 开放项目。服务开关位于 Settings > Tools > Flutter Debug MCP。 |  |
| `flutter_sessions` | 列出 IDEA 已运行 Flutter 会话、设备、运行模式与 VM Service URI。 |  |
| `flutter_dtd` | 获取项目现有 DTD URI；不创建 DTD。ready=false 时地址为空。 |  |
| `flutter_configurations` | 列出已有 Flutter 运行配置及 configurationId。 |  |
| `flutter_devices` | 列出 Flutter 插件发现的设备。 |  |
| `flutter_run` | 通过 IDEA 运行配置启动 Flutter，默认 debug；返回 accepted，随后查询 sessions 确认。 | `configurationId` |
| `flutter_hot_reload` | 执行 Flutter hot reload，等待实际操作结果。 | `sessionId` |
| `flutter_hot_restart` | 执行 Flutter hot restart，等待实际结果；isolate/object ID 随后需重新获取。 | `sessionId` |
| `flutter_rerun` | 停止并重新运行相同 IDEA 配置；返回 accepted，随后重新发现 sessionId。 | `sessionId` |
| `flutter_stop` | 停止指定 Flutter 进程；返回 accepted。 | `sessionId` |
| `flutter_logs` | 读取有界实时进程日志，行号从1开始，支持字面量搜索、前后上下文、分页及 channel。fromLine 小于缓存首行时 truncated=true。pending 返回尚未换行分片。 | `sessionId` |
| `flutter_events` | 读取已订阅的 VM 事件，支持与 logs 相同的搜索、按行分页和上下文扩展。 | `sessionId` |
| `flutter_capture_events` | 开启/关闭独立连接的 Debug/Isolate/Logging/Stdout/Stderr/Extension/GC/Timeline 流；不会取消 IDEA 的订阅。开始后用 events 查询。 | `sessionId` |
| `flutter_devtools` | 获取 DevTools URL，必要时由 Flutter 插件启动 DevTools 服务。不会打开浏览器。 | `sessionId` |
| `flutter_source` | 读取项目源码指定行及上下文，包含 IDEA 尚未保存的文档内容；行号从1开始。 | `filePath` |
| `flutter_permissions` | 查询当前总开关和分类权限。全部工具始终暴露；被拒绝时请用户在 IDEA 设置页授权。 |  |
| `flutter_audit` | 查询最近2000条持久化操作记录，支持工具/状态/时间字面量过滤。没有参数正文或响应值。 |  |
| `flutter_breakpoints` | 列出 IDEA 持久化 Dart 行断点，行号从1开始。 |  |
| `flutter_set_breakpoint` | 创建/更新 IDEA Dart 行断点，支持 condition、enabled。可在 IDEA 中查看。 | `filePath`, `line` |
| `flutter_remove_breakpoint` | 移除指定文件和1基行号的 IDEA Dart 行断点。 | `filePath`, `line` |
| `flutter_debug_action` | 通过 IDEA 调试会话暂停、继续、单步；accepted 之后用 get_isolate/get_stack 确认实际状态。 | `sessionId`, `action` |
| `flutter_get_vm` | 获取 VM 与所有 isolate ID；热重启后重新获取。 | `sessionId` |
| `flutter_get_isolate` | 获取 isolate 状态、pauseEvent、已注册扩展和 VM 断点。 | `sessionId`, `isolateId` |
| `flutter_get_stack` | 获取堆栈与 frame.vars 局部变量引用，frameIndex 从0开始。 | `sessionId`, `isolateId` |
| `flutter_get_object` | 按 objectId 获取对象、字段、集合值或脚本源码；offset/count 支持分页。对象已被GC时返回 Sentinel。 | `sessionId`, `isolateId`, `objectId` |
| `flutter_evaluate` | 在暂停帧中执行 Dart 表达式并获取值，可产生副作用。需要 evaluate 权限。 | `sessionId`, `isolateId`, `frameIndex`, `expression` |
| `flutter_evaluate_object` | 在对象/库上下文中执行 Dart 表达式，可产生副作用。 | `sessionId`, `isolateId`, `targetId`, `expression` |
| `flutter_invoke` | 调用对象方法，可产生副作用；argumentIds 是 VM 对象ID数组。 | `sessionId`, `isolateId`, `targetId`, `selector`, `argumentIds` |
| `flutter_pause_isolate` | 暂停指定 isolate。随后通过 get_isolate 确认暂停事件。 | `sessionId`, `isolateId` |
| `flutter_resume_isolate` | 继续或步进指定 isolate：Into/Over/Out/OverAsyncSuspension。 | `sessionId`, `isolateId` |
| `flutter_add_vm_breakpoint` | 添加当前 VM 生命周期内的临时断点，scriptUri 可为 package: 或 file:；line为1基。持久断点使用 set_breakpoint。 | `sessionId`, `isolateId`, `scriptUri`, `line` |
| `flutter_remove_vm_breakpoint` | 删除当前 VM 断点；breakpointId 从 get_isolate 获取。 | `sessionId`, `isolateId`, `breakpointId` |
| `flutter_set_vm_breakpoint_state` | 启用/禁用当前 VM 断点。 | `sessionId`, `isolateId`, `breakpointId`, `enable` |
| `flutter_exception_pause_mode` | 设置异常暂停：None、Unhandled、All。 | `sessionId`, `isolateId`, `mode` |
| `flutter_get_scripts` | 列出 isolate 的脚本；get_object(scriptId) 可获取源码。 | `sessionId`, `isolateId` |
| `flutter_get_memory` | 读取 isolate 堆内存统计。 | `sessionId`, `isolateId` |
| `flutter_get_allocation_profile` | 获取类分配统计；不强制GC或重置统计。 | `sessionId`, `isolateId` |
| `flutter_get_instances` | 获取指定类实例引用，后续用 get_object 读取对象。 | `sessionId`, `isolateId`, `objectId`, `limit` |
| `flutter_get_retaining_path` | 获取对象存活引用路径以排查内存泄漏。 | `sessionId`, `isolateId`, `targetId`, `limit` |
| `flutter_get_cpu_samples` | 获取指定微秒时间范围的CPU采样；运行模式/VM可能限制该能力。 | `sessionId`, `isolateId`, `timeOriginMicros`, `timeExtentMicros` |
| `flutter_get_timeline` | 获取VM时间线，可传微秒时间范围。 | `sessionId` |
| `flutter_service_extension` | 调用已注册的 ext.flutter.* / ext.dart.io.* 扩展。先用 get_isolate 查看 extensionRPCs；可查询 Widget 树、截图、Inspector，或设置调试显示开关。具体参数取决于运行SDK。 | `sessionId`, `method` |
| `flutter_vm_call` | 高级 VM RPC 入口，仅允许插件列明的查询、调试、表达式和Flutter扩展方法；按实际method校验权限，不能绕过分类权限。params遵循Dart VM Service规范。 | `sessionId`, `method` |
