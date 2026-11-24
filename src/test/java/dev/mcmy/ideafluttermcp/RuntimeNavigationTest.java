package dev.mcmy.ideafluttermcp;

import com.google.gson.JsonObject;
import com.intellij.execution.process.ProcessOutput;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class RuntimeNavigationTest {
  private static final String URI = "myapp://orders/123?item=a'b&label=%E4%B8%AD#details";

  static void run() throws Exception {
    assert RuntimeNavigation.uri(URI).equals(URI);
    fails(() -> RuntimeNavigation.uri("/orders/123"), "absolute URI");
    fails(() -> RuntimeNavigation.uri("myapp://secret?token=private value"), "absolute URI");
    assert ToolPolicy.permission("navigate", Json.obj("mode", "system")).equals("run");
    assert ToolPolicy.permission("navigate", Json.obj("mode", "debug")).equals("extensions");
    fails(() -> ToolPolicy.vmPermission(RuntimeNavigation.EXTENSION), "Unsupported VM method");
    assert DeviceNavigation.android("android-arm64");
    assert !DeviceNavigation.android("ios");
    Path home = Path.of(System.getProperty("user.home"));
    Path localAppData = home.resolve("custom-local-app-data");
    assert DeviceNavigation.defaultAndroidSdk(home, true, false, localAppData.toString()).equals(localAppData.resolve("Android/Sdk"));
    assert DeviceNavigation.defaultAndroidSdk(home, true, false, null).equals(home.resolve("AppData/Local/Android/Sdk"));
    assert DeviceNavigation.defaultAndroidSdk(home, false, false, null).equals(home.resolve("Android/Sdk"));
    assert DeviceNavigation.defaultAndroidSdk(home, false, true, null).equals(home.resolve("Library/Android/sdk"));
    assert DeviceNavigation.androidCommand("/path with spaces/adb", "emulator-5560", URI, "com.example.app.debug", 10).equals(List.of(
        "/path with spaces/adb", "-s", "emulator-5560", "shell", "am", "start", "--user", "10", "-W", "-a", "android.intent.action.VIEW",
        "-d", "'myapp://orders/123?item=a'\\''b&label=%E4%B8%AD#details'", "-p", "com.example.app.debug"));
    assert DeviceNavigation.androidProcessUid(new ProcessOutput("  UID\n1012345\n", "", 0, false, false)) == 1012345;
    assert DeviceNavigation.androidProcessUid(new ProcessOutput("UID\n", "", 1, false, false)) == null;
    assert DeviceNavigation.androidAppId(List.of("com.example.app"), null).equals("com.example.app");
    assert DeviceNavigation.androidAppId(List.of("com.example.app", "com.example.other"), "com.example.other").equals("com.example.other");
    fails(() -> DeviceNavigation.androidAppId(List.of("com.example.app", "com.example.other"), null), "shared UID");
    fails(() -> DeviceNavigation.androidAppId(List.of("com.example.app"), "com.example.wrong"), "does not match");
    assert status(DeviceNavigation.androidResult(new ProcessOutput("Warning: Activity not started, intent has been delivered\nStatus: ok\n", "", 0, false, false))).equals("accepted");
    assert status(DeviceNavigation.androidResult(new ProcessOutput("Status: timeout\n", "", 0, false, false))).equals("unknown");
    fails(() -> DeviceNavigation.androidResult(new ProcessOutput("Starting: Intent\n", "Error: unable to resolve Intent " + URI, 0, false, false)), "No matching Android activity");
    fails(() -> DeviceNavigation.openIos("ios", false, "device", URI, null, () -> { throw new AssertionError(); }), "Use debug mode");

    AtomicBoolean checked = new AtomicBoolean();
    var result = RuntimeNavigation.debug((method, params, timeout) -> {
      if (method.equals("getVM")) return vm();
      if (method.equals("getIsolate")) {
        return params.get("isolateId").getAsString().equals("main") ? isolate("Resume") : Json.obj();
      }
      assert method.equals(RuntimeNavigation.EXTENSION) && checked.get();
      assert params.equals(Json.obj("isolateId", "main", "uri", URI));
      return Json.obj("status", "handled");
    }, URI, () -> checked.set(true));
    assert status(result).equals("handled");
    fails(() -> RuntimeNavigation.debug((method, params, timeout) -> method.equals("getVM") ? vm() : Json.obj(), URI,
        () -> { throw new AssertionError(); }), "extension missing");
    fails(() -> RuntimeNavigation.debug((method, params, timeout) -> method.equals("getVM") ? vm() : isolate("PauseBreakpoint"), URI,
        () -> { throw new AssertionError(); }), "is paused");
    fails(() -> RuntimeNavigation.debug((method, params, timeout) -> method.equals("getVM") ? vm() : isolate("Resume"), URI,
        () -> { throw new AssertionError(); }), "only in the app's main isolate");
    assert status(RuntimeNavigation.debug((method, params, timeout) -> {
      if (method.equals("getVM")) return Json.obj("isolates", List.of(Json.obj("id", "main")));
      if (method.equals("getIsolate")) return isolate("Resume");
      throw new TimeoutException(URI);
    }, URI, () -> {})).equals("unknown");
    fails(() -> RuntimeNavigation.debug((method, params, timeout) -> {
      if (method.equals("getVM")) return Json.obj("isolates", List.of(Json.obj("id", "main")));
      if (method.equals("getIsolate")) return isolate("Resume");
      throw new ExecutionException(new VmConnection.RpcException(Json.obj("message", URI)));
    }, URI, () -> {}), "returned an RPC error");
  }

  private static JsonObject vm() { return Json.obj("isolates", List.of(Json.obj("id", "worker"), Json.obj("id", "main"))); }

  private static JsonObject isolate(String pauseKind) {
    return Json.obj("extensionRPCs", List.of(RuntimeNavigation.EXTENSION), "pauseEvent", Json.obj("kind", pauseKind));
  }

  private static String status(JsonObject result) { return result.get("status").getAsString(); }

  @FunctionalInterface
  private interface Call { void run() throws Exception; }

  private static void fails(Call action, String message) throws Exception {
    try { action.run(); throw new AssertionError("Unexpected successful navigation"); }
    catch (IllegalArgumentException | IllegalStateException error) {
      assert error.getMessage().contains(message) : error.getMessage();
      assert !error.getMessage().contains("myapp://");
    }
  }
}
