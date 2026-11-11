package dev.mcmy.ideafluttermcp;

import java.util.*;

/** 所有入口共享权限分类，未知方法拒绝执行。 */
final class ToolPolicy {
  static final Set<String> INSPECT = Set.of("getVM", "getVersion", "getIsolate", "getIsolatePauseEvent", "getStack", "getObject", "getScripts", "getClassList", "getMemoryUsage", "getAllocationProfile", "getCpuSamples", "getVMTimeline", "getVMTimelineFlags", "getVMTimelineMicros", "getSourceReport", "getInstances", "getInboundReferences", "getRetainingPath", "getProcessMemoryUsage", "getSupportedProtocols", "lookupPackageUris", "lookupResolvedPackageUris");
  static final Set<String> DEBUG = Set.of("pause", "resume", "addBreakpoint", "addBreakpointWithScriptUri", "addBreakpointAtEntry", "removeBreakpoint", "setBreakpointState", "setExceptionPauseMode", "setLibraryDebuggable");
  static final Set<String> EVALUATE = Set.of("evaluate", "evaluateInFrame", "invoke");
  /** 按实际 VM 方法分类，通用入口无法绕过权限。 */
  static String vmPermission(String method) {
    if (INSPECT.contains(method)) return "inspect";
    if (DEBUG.contains(method)) return "debug";
    if (EVALUATE.contains(method)) return "evaluate";
    if (method.startsWith("ext.flutter.") || method.startsWith("ext.dart.io.")) return "extensions";
    throw new IllegalArgumentException("Unsupported VM method: " + method);
  }
  /** 获取每个桥接工具需要的权限。 */
  static String permission(String tool, com.google.gson.JsonObject args) {
    return switch (tool) {
      case "projects", "sessions", "dtd", "configurations", "get_configuration", "devices" -> "discovery";
      case "source" -> "inspect";
      case "logs", "events", "capture_events" -> "logs";
      case "run", "rerun", "stop", "hot_reload", "hot_restart", "select_device", "create_configuration", "update_configuration" -> "run";
      case "devtools" -> "devtools";
      case "breakpoints", "set_breakpoint", "remove_breakpoint", "debug_action" -> "debug";
      case "vm_call" -> vmPermission(Json.required(args, "method"));
      case "audit", "permissions" -> "";
      default -> throw new IllegalArgumentException("Unknown tool: " + tool);
    };
  }
}
