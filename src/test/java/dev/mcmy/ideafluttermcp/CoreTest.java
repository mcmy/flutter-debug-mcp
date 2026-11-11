package dev.mcmy.ideafluttermcp;

import com.google.gson.*;
import java.nio.file.*;

/** 运行不依赖 IDE 实例的关键协议和权限回归。 */
public final class CoreTest {
  /** 覆盖日志分片、淘汰、分页、上下文、权限分类和审计脱敏。 */
  public static void main(String[] args) throws Exception {
    OperationTrackerTest.run();
    assert ToolPolicy.permission("operation_status", Json.obj()).equals("run");
    LineBuffer logs = new LineBuffer(3);
    logs.append("stdout", "first\nhel");
    logs.append("stdout", "lo\nthird\nfourth\n");
    JsonObject read = logs.read(Json.obj("fromLine", 1));
    assert read.get("truncated").getAsBoolean();
    assert read.get("firstAvailableLine").getAsInt() == 2;
    assert read.getAsJsonArray("lines").size() == 3;
    assert read.getAsJsonArray("lines").get(0).getAsJsonObject().get("text").getAsString().equals("hello");
    JsonObject match = logs.read(Json.obj("query", "THIRD", "before", 1, "after", 1));
    assert match.getAsJsonArray("matches").get(0).getAsInt() == 3;
    assert match.getAsJsonArray("lines").size() == 3;
    JsonObject page = logs.read(Json.obj("limit", 1));
    assert page.get("hasMore").getAsBoolean();
    assert page.get("nextFromLine").getAsInt() == 3;
    assert logs.read(Json.obj("query", "absent")).getAsJsonArray("lines").isEmpty();
    logs.append("stderr", "tail");
    assert logs.read(new JsonObject()).getAsJsonArray("pending").size() == 1;
    logs.flush();
    assert logs.read(new JsonObject()).get("nextLine").getAsInt() == 6;
    try { logs.read(Json.obj("limit", 0)); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
    try { Json.integer(Json.obj("value", 1.5), "value", 0, 0, 10); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
    LineBuffer crlf = new LineBuffer(10);
    crlf.append("stdout", "a\r");
    crlf.append("stdout", "\nb\n");
    assert crlf.read(new JsonObject()).getAsJsonArray("lines").size() == 2;
    try { crlf.read(Json.obj("before", 1, "after", 1, "limit", 1)); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
    try { Json.integer(new JsonObject(), "line", 0, 1, 100); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
    assert ToolPolicy.vmPermission("getStack").equals("inspect");
    assert ToolPolicy.permission("get_configuration", new JsonObject()).equals("discovery");
    for (String tool : java.util.List.of("create_configuration", "update_configuration", "select_device")) assert ToolPolicy.permission(tool, new JsonObject()).equals("run");
    JsonObject original = Json.obj("name", "main", "filePath", "lib/main.dart", "additionalArgs", "--verbose", "attachArgs", "--app-id=app", "buildFlavor", "dev", "envs", Json.obj("KEEP", "yes"));
    JsonObject updated = RunConfigurationOptions.merge(original, Json.obj("additionalArgs", "--dart-define=VALUE='hello world'", "buildFlavor", "", "envs", Json.obj("NEW", "value")));
    assert updated.get("attachArgs").equals(original.get("attachArgs"));
    assert updated.get("filePath").equals(original.get("filePath"));
    assert updated.get("buildFlavor").getAsString().isEmpty();
    assert !updated.getAsJsonObject("envs").has("KEEP");
    assert original.getAsJsonObject("envs").has("KEEP");
    assert RunConfigurationOptions.merge(original, Json.obj("envs", new JsonObject())).getAsJsonObject("envs").isEmpty();
    for (JsonObject invalid : java.util.List.of(Json.obj("name", " "), Json.obj("envs", Json.obj("BAD=KEY", "x")), Json.obj("envs", Json.obj("KEY", 12)), Json.obj("filePath", JsonNull.INSTANCE), Json.obj("additionalArgs", "bad\0arg"), Json.obj("unknown", "x"))) {
      try { RunConfigurationOptions.merge(original, invalid); throw new AssertionError("Invalid patch accepted"); } catch (IllegalArgumentException expected) {}
      assert original.get("additionalArgs").getAsString().equals("--verbose");
    }
    assert ToolPolicy.vmPermission("evaluateInFrame").equals("evaluate");
    assert ToolPolicy.vmPermission("ext.flutter.inspector.getRootWidgetSummaryTree").equals("extensions");
    try { ToolPolicy.vmPermission("kill"); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
    Settings settings = new Settings();
    assert !settings.getState().enabled;
    assert settings.getState().port == 29191;
    assert settings.getState().permissions.values().stream().allMatch(Boolean::booleanValue);
    try { settings.check("logs"); throw new AssertionError(); } catch (SecurityException expected) {}
    settings.getState().enabled = true;
    settings.check("logs");
    settings.getState().permissions.put("logs", false);
    try { settings.check("logs"); throw new AssertionError(); } catch (SecurityException expected) {}
    settings.getState().permissions.put("logs", true);
    settings.check("logs");
    Path directory = Files.createTempDirectory("flutter-mcp-test");
    try {
      AuditStore store = new AuditStore(directory.resolve("audit.jsonl"));
      store.add("hot_reload", "ok", 10);
      store.add("vm_call", "denied", 1);
      assert new AuditStore(directory.resolve("audit.jsonl")).query("denied", 20).size() == 1;
      assert store.query("", 1).size() == 1;
      assert Files.getPosixFilePermissions(directory.resolve("audit.jsonl")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    } finally {
      try (var files = Files.list(directory)) { for (Path file : files.toList()) Files.delete(file); }
      Files.delete(directory);
    }
    System.out.println("CoreTest passed");
  }
}
