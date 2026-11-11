package dev.canny.ideafluttermcp;

import com.google.gson.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.*;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.*;
import com.intellij.execution.*;
import com.intellij.execution.executors.*;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.*;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.breakpoints.*;
import com.jetbrains.lang.dart.ide.runner.DartLineBreakpointType;
import com.jetbrains.lang.dart.ide.toolingDaemon.DartToolingDaemonService;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.flutter.run.daemon.*;
import io.flutter.run.SdkRunConfig;
import io.flutter.run.SdkFields;
import io.flutter.run.FlutterRunConfigurationType;
import io.flutter.run.FlutterDevice;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** IDEA 运行会话桥接；所有 UI API 统一调度到 EDT。 */
public final class BridgeService implements Disposable {
  private static final Logger LOG = Logger.getInstance(BridgeService.class);
  private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
  private final java.util.concurrent.atomic.AtomicBoolean discoveryQueued = new java.util.concurrent.atomic.AtomicBoolean();
  private volatile McpHttpServer server;
  private ScheduledFuture<?> poll;
  private AuditStore auditStore;
  private volatile boolean disposed;
  private String secret;
  private String lastStartupError;

  private static final class Session {
    final String id = UUID.randomUUID().toString();
    final FlutterApp app;
    final LineBuffer logs = new LineBuffer(20000);
    final LineBuffer events = new LineBuffer(5000);
    ProcessListener listener;
    VmConnection vm;
    /** 绑定 Flutter 插件已经拥有的运行进程。 */
    Session(FlutterApp app) { this.app = app; }
    /** 释放采集资源，不停止用户的 Flutter 应用。 */
    synchronized void close() {
      app.getProcessHandler().removeProcessListener(listener);
      if (vm != null) vm.close();
      vm = null;
    }
  }
  /** 返回应用服务。 */
  public static BridgeService getInstance() { return ApplicationManager.getApplication().getService(BridgeService.class); }
  /** 在配置已启用时启动私有桥接服务。 */
  public synchronized void start() {
    if (disposed || server != null || !Settings.getInstance().getState().enabled) return;
    try {
      ensureAudit();
      Settings.Data settings = Settings.getInstance().getState();
      server = new McpHttpServer(EndpointConfig.port(settings.port), endpointSecret(), this::invokeTool);
      poll = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
        if (discoveryQueued.compareAndSet(false, true)) ApplicationManager.getApplication().invokeLater(() -> {
          try { discover(); }
          finally { discoveryQueued.set(false); }
        });
      }, 0, 1, TimeUnit.SECONDS);
      lastStartupError = null;
    } catch (Exception error) {
      lastStartupError = error instanceof java.net.BindException ? "端口已被占用，请修改监听端口" : error.getClass().getSimpleName() + ": " + error.getMessage();
      LOG.warn("Flutter Debug MCP startup failed: " + lastStartupError);
      stopServer();
    }
  }
  /** 在执行端逐次校验权限并记录成功、拒绝或错误。 */
  private JsonElement invokeTool(String tool, JsonObject args) throws Exception {
    long started = System.nanoTime();
    String status = "ok";
    try {
      String permission = ToolPolicy.permission(tool, args);
      if (!Settings.getInstance().getState().enabled) throw new SecurityException("MCP server disabled");
      if (!permission.isEmpty()) Settings.getInstance().check(permission);
      return execute(tool, args);
    } catch (Exception error) {
      Throwable cause = error;
      while ((cause instanceof java.util.concurrent.ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) cause = cause.getCause();
      status = cause instanceof SecurityException ? "denied" : "error";
      throw error;
    } finally {
      try { if (auditStore != null) auditStore.add(auditOperation(tool, args), status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)); }
      catch (Exception error) { LOG.warn("Flutter MCP audit failed: " + error.getClass().getSimpleName()); }
    }
  }
  /** 懒加载稳定URL凭据，显示配置不会启动监听。 */
  synchronized String endpointSecret() throws Exception {
    if (secret == null) secret = EndpointConfig.secret(PrivateFiles.root(), com.intellij.openapi.application.PathManager.getConfigPath());
    return secret;
  }
  /** 返回当前监听状态，不将URL令牌写入日志。 */
  synchronized String serverStatus() {
    if (lastStartupError != null) return "启动失败：" + lastStartupError;
    return server == null ? "服务已关闭" : "正在监听 127.0.0.1:" + server.port();
  }
  /** 记录实际 VM 方法名，不记录其表达式和参数。 */
  private static String auditOperation(String tool, JsonObject args) {
    if (!tool.equals("vm_call")) return tool;
    String method = Json.string(args, "method", "");
    return tool + ":" + method.substring(0, Math.min(128, method.length()));
  }
  /** 在 EDT 同步读取或修改 IDEA 模型，超时取消尚未执行的任务。 */
  private static <T> T edt(Callable<T> action) throws Exception {
    if (ApplicationManager.getApplication().isDispatchThread()) return action.call();
    FutureTask<T> task = new FutureTask<>(action);
    ApplicationManager.getApplication().invokeLater(task);
    try { return task.get(10, TimeUnit.SECONDS); }
    catch (TimeoutException error) { task.cancel(false); throw error; }
  }
  /** 发现新进程并在开始采集时标注日志边界。 */
  private synchronized void discover() {
    if (disposed || server == null || !Settings.getInstance().getState().enabled) return;
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      if (project.isDisposed()) continue;
      for (FlutterApp app : FlutterApp.allFromProjectProcess(project)) {
        if (sessions.values().stream().anyMatch(session -> session.app == app)) continue;
        Session session = new Session(app);
        session.listener = new ProcessAdapter() {
          /** 仅在日志权限开启时缓存新输出。 */
          @Override public void onTextAvailable(ProcessEvent event, Key outputType) {
            Settings.Data settings = Settings.getInstance().getState();
            if (settings.enabled && Boolean.TRUE.equals(settings.permissions.get("logs"))) session.logs.append(outputType.toString(), event.getText());
          }
          /** 完成最后一个未换行分片。 */
          @Override public void processTerminated(ProcessEvent event) { session.logs.flush(); }
        };
        app.getProcessHandler().addProcessListener(session.listener);
        session.logs.append("bridge", "Log capture started; earlier console output is unavailable.\n");
        sessions.put(session.id, session);
      }
    }
    Iterator<Session> iterator = sessions.values().iterator();
    while (iterator.hasNext()) {
      Session session = iterator.next();
      if (session.app.getProcessHandler().isProcessTerminated()) session.close();
      if (session.app.getProject().isDisposed() || (sessions.size() > 32 && session.app.getProcessHandler().isProcessTerminated())) { session.close(); iterator.remove(); }
    }
  }
  /** 获取调用指定的开放项目，多个项目时要求明确路径。 */
  private Project project(JsonObject args) {
    String path = Json.string(args, "projectPath", "");
    List<Project> projects = Arrays.stream(ProjectManager.getInstance().getOpenProjects()).filter(p -> !p.isDisposed() && p.getBasePath() != null && (path.isEmpty() || path.equals(p.getBasePath()))).toList();
    if (projects.size() != 1) throw new IllegalArgumentException("Specify projectPath; matching open projects: " + projects.size());
    return projects.getFirst();
  }
  /** 从稳定会话 ID 获取进程，拒绝自动选择其他会话。 */
  private synchronized Session session(JsonObject args) {
    Session session = sessions.get(Json.required(args, "sessionId"));
    if (session == null || session.app.getProject().isDisposed()) throw new IllegalArgumentException("Session unavailable; call flutter_sessions");
    return session;
  }
  /** 为当前 VM 地址建立专用连接，热重启地址变化时清理旧连接。 */
  private VmConnection vm(Session session) throws Exception {
    String uri = edt(() -> {
      if (session.app.getProject().isDisposed() || session.app.getProcessHandler().isProcessTerminated()) throw new IllegalStateException("Flutter process ended");
      return session.app.getConnector().getWebSocketUrl();
    });
    if (uri == null) throw new IllegalStateException("VM service unavailable; use a debug/profile session");
    synchronized (session) {
      if (session.vm == null || session.vm.isClosed() || !session.vm.uri.equals(uri)) {
        if (session.vm != null) session.vm.close();
        session.vm = new VmConnection(uri, event -> {
          Settings.Data settings = Settings.getInstance().getState();
          if (settings.enabled && Boolean.TRUE.equals(settings.permissions.get("logs"))) session.events.append("vm", event.toString() + "\n");
        });
      }
      return session.vm;
    }
  }
  /** 执行已通过权限校验的桥接命令。 */
  private JsonElement execute(String tool, JsonObject args) throws Exception {
    if (tool.equals("audit")) return audit(Json.string(args, "query", ""), Json.integer(args, "limit", 200, 1, 2000));
    if (tool.equals("permissions")) return Json.obj("enabled", Settings.getInstance().getState().enabled, "permissions", Settings.getInstance().getState().permissions);
    if (tool.equals("vm_call")) {
      Session session = session(args);
      JsonObject params = args.has("params") ? args.getAsJsonObject("params").deepCopy() : new JsonObject();
      return vm(session).call(Json.required(args, "method"), params, Json.integer(args, "timeoutSeconds", 20, 1, 60));
    }
    if (tool.equals("logs") || tool.equals("events")) return (tool.equals("logs") ? session(args).logs : session(args).events).read(args);
    if (tool.equals("capture_events")) {
      VmConnection vm = vm(session(args));
      JsonArray results = new JsonArray();
      for (String stream : List.of("Debug", "Isolate", "Logging", "Stdout", "Stderr", "Extension", "GC", "Timeline")) {
        boolean enabled = Json.bool(args, "enabled", true);
        try { results.add(Json.obj("stream", stream, "result", vm.call(enabled ? "streamListen" : "streamCancel", Json.obj("streamId", stream), 5))); }
        catch (Exception error) { results.add(Json.obj("stream", stream, "error", error.getMessage())); }
      }
      return results;
    }
    if (tool.equals("hot_reload") || tool.equals("hot_restart")) {
      Session session = session(args);
      CompletableFuture<DaemonApi.RestartResult> result = edt(() -> {
        Settings.getInstance().check("run");
        if (session.app.getProject().isDisposed()) throw new IllegalStateException("Project closed");
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        if (!session.app.isStarted() || session.app.isReloading()) throw new IllegalStateException("App not ready or already reloading");
        return tool.equals("hot_reload") ? session.app.performHotReload(false, "Flutter Debug MCP") : session.app.performRestartApp("Flutter Debug MCP");
      });
      DaemonApi.RestartResult value = result.get(90, TimeUnit.SECONDS);
      if (session.app.getProject().isDisposed()) throw new IllegalStateException("Project closed");
      if (!value.ok()) throw new IllegalStateException("Flutter restart failed: " + value.getCode() + " " + value.getMessage());
      return Json.obj("ok", true, "message", value.getMessage());
    }
    if (tool.equals("devtools")) {
      Session session = session(args);
      CompletableFuture<DevToolsInstance> future = edt(() -> {
        Settings.getInstance().check("devtools");
        if (session.app.getProject().isDisposed()) throw new IllegalStateException("Project closed");
        return DevToolsService.getInstance(session.app.getProject()).getDevToolsInstance();
      });
      DevToolsInstance instance = future.get(30, TimeUnit.SECONDS);
      String vmUri = edt(() -> session.app.getProject().isDisposed() ? null : session.app.getConnector().getBrowserUrl());
      if (vmUri == null) throw new IllegalStateException("VM URL unavailable");
      return Json.obj("url", "http://" + instance.host() + ":" + instance.port() + "/?uri=" + URLEncoder.encode(vmUri, StandardCharsets.UTF_8), "vmServiceUri", vmUri);
    }
    return edt(() -> executeIde(tool, args));
  }
  /** 处理需要 IDEA 项目模型的命令。 */
  private JsonElement executeIde(String tool, JsonObject args) throws Exception {
    String permission = ToolPolicy.permission(tool, args);
    if (!permission.isEmpty()) Settings.getInstance().check(permission);
    if (disposed || server == null) throw new IllegalStateException("MCP service stopped");
    if (tool.equals("projects")) {
      JsonArray out = new JsonArray();
      for (Project project : ProjectManager.getInstance().getOpenProjects()) if (!project.isDisposed()) out.add(Json.obj("name", project.getName(), "path", project.getBasePath()));
      return out;
    }
    if (tool.equals("sessions")) {
      discover();
      JsonArray out = new JsonArray();
      synchronized (this) {
        for (Session session : sessions.values()) {
          FlutterApp app = session.app;
          out.add(Json.obj("sessionId", session.id, "projectPath", app.getProject().getBasePath(), "state", app.getState().toString(), "mode", app.getMode().toString(), "terminated", app.getProcessHandler().isProcessTerminated(), "deviceId", app.deviceId(), "deviceName", app.device().deviceName(), "vmServiceUri", app.getConnector().getWebSocketUrl(), "supportsHotReload", app.appSupportsHotReload()));
        }
      }
      return out;
    }
    if (Set.of("dtd", "configurations", "get_configuration", "create_configuration", "update_configuration", "select_device", "devices", "run", "breakpoints", "set_breakpoint", "remove_breakpoint", "source").contains(tool)) {
      Project project = project(args);
      if (tool.equals("source")) {
        String path = Json.required(args, "filePath");
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
        if (file == null || project.getBasePath() == null || !Path.of(file.getPath()).normalize().startsWith(Path.of(project.getBasePath()).normalize())) throw new IllegalArgumentException("Expected source file inside project");
        var document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
        if (document == null) throw new IllegalArgumentException("Text document unavailable");
        int line = Json.integer(args, "line", 1, 1, document.getLineCount());
        int before = Json.integer(args, "before", 10, 0, 250), after = Json.integer(args, "after", 10, 0, 250);
        JsonArray rows = new JsonArray();
        for (int index = Math.max(0, line - 1 - before); index <= Math.min(document.getLineCount() - 1, line - 1 + after); index++) rows.add(Json.obj("line", index + 1, "text", document.getText(new com.intellij.openapi.util.TextRange(document.getLineStartOffset(index), document.getLineEndOffset(index)))));
        return Json.obj("filePath", file.getPath(), "totalLines", document.getLineCount(), "lines", rows);
      }
      if (tool.equals("dtd")) {
        DartToolingDaemonService dtd = project.getServiceIfCreated(DartToolingDaemonService.class);
        return Json.obj("projectPath", project.getBasePath(), "ready", dtd != null && dtd.getWebSocketReady(), "uri", dtd != null && dtd.getWebSocketReady() ? dtd.getUri() : null);
      }
      if (tool.equals("devices")) {
        JsonArray out = new JsonArray();
        var service = DeviceService.getInstance(project);
        for (var device : service.getConnectedDevices()) out.add(Json.obj("id", device.deviceId(), "name", device.deviceName(), "platform", device.platform(), "emulator", device.emulator(), "selected", device.equals(service.getSelectedDevice())));
        return out;
      }
      if (tool.equals("select_device")) {
        FlutterDevice device = selectDevice(project, Json.required(args, "deviceId"));
        return Json.obj("selected", true, "deviceId", device.deviceId(), "deviceName", device.deviceName());
      }
      if (tool.equals("configurations")) {
        JsonArray out = new JsonArray();
        for (var configuration : RunManager.getInstance(project).getAllSettings()) if (configuration.getType().getId().toLowerCase(Locale.ROOT).contains("flutter")) out.add(configurationDetails(project, configuration));
        return out;
      }
      if (tool.equals("get_configuration")) return configurationDetails(project, configuration(project, args));
      if (tool.equals("create_configuration") || tool.equals("update_configuration")) {
        var manager = RunManager.getInstance(project);
        boolean create = tool.equals("create_configuration");
        var settings = create ? manager.createConfiguration(Json.required(args, "name"), FlutterRunConfigurationType.getInstance().getFactory()) : configuration(project, args);
        if (!(settings.getConfiguration() instanceof SdkRunConfig config)) throw new IllegalArgumentException("Only Flutter application configurations are editable");
        JsonObject patch = new JsonObject();
        for (String key : RunConfigurationOptions.TEXT) if (args.has(key)) patch.add(key, args.get(key));
        if (args.has("envs")) patch.add("envs", args.get("envs"));
        if (patch.isEmpty()) throw new IllegalArgumentException("Provide at least one configuration field to update");
        JsonObject updated = RunConfigurationOptions.merge(configurationOptions(settings), patch);
        String name = Json.required(updated, "name");
        if (manager.getAllSettings().stream().anyMatch(other -> other != settings && other.getType().equals(settings.getType()) && other.getName().equals(name))) throw new IllegalArgumentException("Flutter configuration name already exists: " + name);
        String filePath = Json.required(updated, "filePath");
        Path path = Path.of(filePath);
        if (!path.isAbsolute()) path = Path.of(project.getBasePath()).resolve(path);
        path = path.normalize();
        if (!Files.isRegularFile(path) || !path.toString().endsWith(".dart")) throw new IllegalArgumentException("filePath must refer to an existing Dart file (absolute or relative to project)");
        // Construct all replacement values before touching the stored configuration.
        SdkFields fields = new SdkFields();
        fields.setFilePath(path.toString());
        fields.setAdditionalArgs(emptyToNull(Json.string(updated, "additionalArgs", "")));
        fields.setAttachArgs(emptyToNull(Json.string(updated, "attachArgs", "")));
        fields.setBuildFlavor(emptyToNull(Json.string(updated, "buildFlavor", "")));
        Map<String, String> envs = new LinkedHashMap<>();
        updated.getAsJsonObject("envs").entrySet().forEach(entry -> envs.put(entry.getKey(), entry.getValue().getAsString()));
        fields.setEnvs(envs);
        config.setFields(fields);
        settings.setName(name);
        if (create) settings.storeInLocalWorkspace();
        manager.addConfiguration(settings);
        manager.makeStable(settings);
        return configurationDetails(project, settings);
      }
      if (tool.equals("run")) {
        String id = Json.required(args, "configurationId");
        var config = configuration(project, args);
        var executor = Json.bool(args, "debug", true) ? DefaultDebugExecutor.getDebugExecutorInstance() : DefaultRunExecutor.getRunExecutorInstance();
        if (args.has("deviceId")) {
          if (!(config.getConfiguration() instanceof SdkRunConfig)) throw new IllegalArgumentException("deviceId is supported only for Flutter application configurations");
          selectDevice(project, Json.required(args, "deviceId"));
        }
        ExecutionEnvironmentBuilder.create(executor, config).buildAndExecute();
        return Json.obj("accepted", true, "configurationId", id, "note", "Launch scheduled; call flutter_sessions for process state");
      }
      return breakpoint(project, tool, args);
    }
    Session session = session(args);
    FlutterApp app = session.app;
    if (app.getProcessHandler().isProcessTerminated()) throw new IllegalStateException("Flutter process ended");
    if (tool.equals("stop")) { app.getProcessHandler().destroyProcess(); return Json.obj("accepted", true); }
    var process = app.getFlutterDebugProcess();
    if (tool.equals("rerun")) {
      ExecutionEnvironment environment = process == null ? null : process.getExecutionEnvironment();
      var manager = ExecutionManager.getInstance(app.getProject());
      if (environment == null && manager instanceof com.intellij.execution.impl.ExecutionManagerImpl implementation) {
        for (var content : manager.getContentManager().getAllDescriptors()) {
          if (content.getProcessHandler() != app.getProcessHandler()) continue;
          var matches = implementation.getExecutionEnvironments(content).stream().filter(item -> FlutterApp.fromEnv(item) == app).toList();
          if (matches.size() == 1) environment = matches.getFirst();
        }
      }
      if (environment == null) throw new IllegalStateException("Original execution environment unavailable; use flutter_run with configurationId");
      manager.restartRunProfile(environment);
      return Json.obj("accepted", true, "note", "Rerun scheduled; discover the new session ID");
    }
    if (process == null) throw new IllegalStateException("IDE debug process unavailable");
    if (tool.equals("debug_action")) {
      XDebugSession debug = process.getSession();
      String action = Json.required(args, "action");
      if (!action.equals("pause") && !debug.isSuspended()) throw new IllegalStateException("Debugger must be paused");
      switch (action) {
        case "pause" -> debug.pause();
        case "resume" -> debug.resume();
        case "step_over" -> debug.stepOver(false);
        case "step_into" -> debug.stepInto();
        case "step_out" -> debug.stepOut();
        default -> throw new IllegalArgumentException("Unknown debug action");
      }
      return Json.obj("accepted", true, "action", action);
    }
    throw new IllegalArgumentException("Unknown IDE tool");
  }
  private static String emptyToNull(String value) { return value.isEmpty() ? null : value; }

  private static RunnerAndConfigurationSettings configuration(Project project, JsonObject args) {
    String id = Json.required(args, "configurationId");
    return RunManager.getInstance(project).getAllSettings().stream().filter(item -> item.getUniqueID().equals(id) && item.getType().getId().toLowerCase(Locale.ROOT).contains("flutter")).findFirst().orElseThrow(() -> new IllegalArgumentException("Flutter configuration not found; call flutter_configurations"));
  }

  private static FlutterDevice selectDevice(Project project, String id) {
    var service = DeviceService.getInstance(project);
    var device = service.getConnectedDevices().stream().filter(item -> item.deviceId().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Device unavailable; call flutter_devices"));
    service.setSelectedDevice(device);
    return device;
  }

  private static JsonObject configurationOptions(RunnerAndConfigurationSettings settings) {
    var fields = ((SdkRunConfig)settings.getConfiguration()).getFields();
    return Json.obj("name", settings.getName(), "filePath", Objects.toString(fields.getFilePath(), ""), "additionalArgs", Objects.toString(fields.getAdditionalArgs(), ""), "attachArgs", Objects.toString(fields.getAttachArgs(), ""), "buildFlavor", Objects.toString(fields.getBuildFlavor(), ""), "envs", fields.getEnvs());
  }

  private static JsonObject configurationDetails(Project project, RunnerAndConfigurationSettings settings) {
    boolean editable = settings.getConfiguration() instanceof SdkRunConfig;
    JsonObject out = editable ? configurationOptions(settings) : Json.obj("name", settings.getName());
    out.addProperty("id", settings.getUniqueID());
    out.addProperty("type", settings.getType().getId());
    out.addProperty("editable", editable);
    out.addProperty("selected", settings == RunManager.getInstance(project).getSelectedConfiguration());
    var device = DeviceService.getInstance(project).getSelectedDevice();
    out.add("selectedDeviceId", Json.GSON.toJsonTree(device == null ? null : device.deviceId()));
    return out;
  }
  /** 创建可在 IDEA 中查看并随项目保存的 Dart 行断点。 */
  private JsonElement breakpoint(Project project, String tool, JsonObject args) {
    XBreakpointManager manager = XDebuggerManager.getInstance(project).getBreakpointManager();
    var type = XBreakpointType.EXTENSION_POINT_NAME.getExtensionList().stream().filter(item -> item instanceof DartLineBreakpointType).map(item -> (DartLineBreakpointType)item).findFirst().orElseThrow(() -> new IllegalStateException("Dart breakpoint type unavailable"));
    if (tool.equals("breakpoints")) {
      JsonArray out = new JsonArray();
      for (var item : manager.getBreakpoints(type)) out.add(Json.obj("fileUrl", item.getFileUrl(), "line", item.getLine() + 1, "enabled", item.isEnabled(), "condition", item.getConditionExpression() == null ? null : item.getConditionExpression().getExpression()));
      return out;
    }
    String path = Json.required(args, "filePath");
    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
    if (file == null || !file.getName().endsWith(".dart") || project.getBasePath() == null || !Path.of(path).normalize().startsWith(Path.of(project.getBasePath()).normalize())) throw new IllegalArgumentException("Expected existing Dart file inside project");
    int line = Json.integer(args, "line", 0, 1, Integer.MAX_VALUE) - 1;
    ApplicationManager.getApplication().runWriteAction(() -> {
      var existing = manager.findBreakpointsAtLine(type, file, line).stream().findFirst().orElse(null);
      if (tool.equals("remove_breakpoint")) {
        if (existing == null) throw new IllegalArgumentException("Breakpoint not found");
        manager.removeBreakpoint(existing);
      } else {
        if (!type.canPutAt(file, line, project)) throw new IllegalArgumentException("Dart breakpoint cannot be placed here");
        var bp = existing == null ? manager.addLineBreakpoint(type, file.getUrl(), line, type.createBreakpointProperties(file, line)) : existing;
        bp.setEnabled(Json.bool(args, "enabled", true));
        String condition = Json.string(args, "condition", "");
        bp.setConditionExpression(condition.isEmpty() ? null : XDebuggerUtil.getInstance().createExpression(condition, null, null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION));
      }
    });
    return Json.obj("ok", true, "filePath", path, "line", line + 1);
  }
  /** 供设置页和 MCP 查询审计记录。 */
  synchronized JsonArray audit(String query, int limit) {
    try { ensureAudit(); return auditStore.query(query, limit); }
    catch (Exception error) { throw new IllegalStateException("Audit unavailable: " + error.getClass().getSimpleName()); }
  }
  /** 按 IDE 配置目录隔离持久审计，跨 IDE 重启保留记录。 */
  private void ensureAudit() throws Exception {
    if (auditStore != null) return;
    Path root = PrivateFiles.root();
    PrivateFiles.directory(root);
    String key = Integer.toHexString(com.intellij.openapi.application.PathManager.getConfigPath().hashCode());
    auditStore = new AuditStore(root.resolve("audit-" + key + ".jsonl"));
  }
  /** 应用权限撤销，停止后台采集或重建服务。 */
  public synchronized void permissionsChanged() {
    if (!Settings.getInstance().getState().enabled) { lastStartupError = null; stopServer(); }
    else {
      if (!Boolean.TRUE.equals(Settings.getInstance().getState().permissions.get("logs"))) for (Session session : sessions.values()) synchronized (session) { if (session.vm != null) session.vm.close(); session.vm = null; }
      if (server != null && server.port() != Settings.getInstance().getState().port) {
        try {
          McpHttpServer replacement = new McpHttpServer(EndpointConfig.port(Settings.getInstance().getState().port), endpointSecret(), this::invokeTool);
          McpHttpServer previous = server;
          server = replacement;
          previous.close();
          lastStartupError = null;
        } catch (Exception error) {
          throw new IllegalStateException("监听端口切换失败，原端口仍在运行：" + (error instanceof java.net.BindException ? "端口已被占用" : error.getClass().getSimpleName()));
        }
      }
      start();
    }
    try { if (auditStore != null) auditStore.add("settings_changed", "ok", 0); }
    catch (Exception error) { LOG.warn("MCP settings audit failed: " + error.getClass().getSimpleName()); }
    if (lastStartupError != null) throw new IllegalStateException("MCP startup failed: " + lastStartupError);
  }
  /** 停止监听与采集；保留稳定URL凭据和审计。 */
  private synchronized void stopServer() {
    if (poll != null) poll.cancel(false);
    poll = null;
    if (server != null) server.close();
    server = null;
    sessions.values().forEach(Session::close);
    sessions.clear();
  }
  /** 卸载时释放全部监听器、任务和插件连接。 */
  @Override public synchronized void dispose() { disposed = true; stopServer(); }
}
