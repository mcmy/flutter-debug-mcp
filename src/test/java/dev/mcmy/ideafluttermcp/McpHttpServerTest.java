package dev.mcmy.ideafluttermcp;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 原生MCP传输、权限与配置回归，无需启动IDE或Flutter。 */
public final class McpHttpServerTest {
  /** 验证真实HTTP连接、完整目录、权限拒绝、端口与配置。 */
  public static void main(String[] args) throws Exception {
    AtomicBoolean allowed = new AtomicBoolean(true);
    AtomicReference<JsonObject> received = new AtomicReference<>();
    try (McpHttpServer server = new McpHttpServer(0, "test-secret", (tool, arguments) -> {
      received.set(arguments);
      if (!allowed.get()) throw new SecurityException("Permission denied: inspect");
      return Json.obj("tool", tool, "value", 42);
    }); HttpClient client = HttpClient.newHttpClient()) {
      URI url = URI.create("http://127.0.0.1:" + server.port() + "/mcp/test-secret");
      JsonObject init = rpc(client, url, Json.obj("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Json.obj("protocolVersion", "2025-11-25")));
      assert init.getAsJsonObject("result").get("protocolVersion").getAsString().equals("2025-11-25");
      assert init.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").getAsString().equals("flutter-debug-mcp");
      HttpResponse<String> notification = post(client, url, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
      assert notification.statusCode() == 202 && notification.body().isEmpty();
      JsonObject list = rpc(client, url, request(2, "tools/list", new JsonObject()));
      assert list.getAsJsonObject("result").getAsJsonArray("tools").size() == 47;
      JsonObject call = rpc(client, url, request(3, "tools/call", Json.obj("name", "flutter_get_stack", "arguments", Json.obj("sessionId", "s", "isolateId", "i", "limit", 10))));
      assert !call.getAsJsonObject("result").get("isError").getAsBoolean();
      assert received.get().get("method").getAsString().equals("getStack");
      assert received.get().getAsJsonObject("params").get("isolateId").getAsString().equals("i");
      allowed.set(false);
      call = rpc(client, url, request(4, "tools/call", Json.obj("name", "flutter_get_vm", "arguments", Json.obj("sessionId", "s"))));
      assert call.getAsJsonObject("result").get("isError").getAsBoolean();
      assert call.toString().contains("Permission denied: inspect") && call.toString().contains("Settings > Tools");
      assert rpc(client, url, request(5, "tools/list", new JsonObject())).getAsJsonObject("result").getAsJsonArray("tools").size() == 47;
      allowed.set(true);
      assert !rpc(client, url, request(20, "tools/call", Json.obj("name", "flutter_update_configuration", "arguments", Json.obj("configurationId", "c", "attachArgs", "--app-id=x", "envs", Json.obj("TOKEN", "value"))))).getAsJsonObject("result").get("isError").getAsBoolean();
      assert received.get().getAsJsonObject("envs").get("TOKEN").getAsString().equals("value");
      assert rpc(client, url, request(21, "tools/call", Json.obj("name", "flutter_update_configuration", "arguments", Json.obj("configurationId", "c", "envs", Json.obj("TOKEN", false))))).has("error");
      assert rpc(client, url, request(22, "tools/call", Json.obj("name", "flutter_create_configuration", "arguments", Json.obj("name", "main")))).has("error");
      assert !rpc(client, url, request(23, "tools/call", Json.obj("name", "flutter_select_device", "arguments", Json.obj("deviceId", "chrome")))).getAsJsonObject("result").get("isError").getAsBoolean();
      assert received.get().get("deviceId").getAsString().equals("chrome");
      assert !rpc(client, url, request(24, "tools/call", Json.obj("name", "flutter_run", "arguments", Json.obj("configurationId", "c", "deviceId", "chrome", "debug", true)))).getAsJsonObject("result").get("isError").getAsBoolean();
      assert !rpc(client, url, request(6, "tools/call", Json.obj("name", "flutter_get_vm", "arguments", Json.obj("sessionId", "s")))).getAsJsonObject("result").get("isError").getAsBoolean();
      assert rpc(client, url, request(7, "tools/call", Json.obj("name", "flutter_logs", "arguments", Json.obj("sessionId", "s", "limit", -1)))).getAsJsonObject("error").get("code").getAsInt() == -32602;
      assert rpc(client, url, request(8, "missing", new JsonObject())).getAsJsonObject("error").get("code").getAsInt() == -32601;
      assert post(client, url, "[").statusCode() == 400;
      assert rpc(client, url, new JsonArray()).getAsJsonObject("error").get("code").getAsInt() == -32600;
      assert client.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 405;
      assert client.send(HttpRequest.newBuilder(url).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 405;
      assert client.send(HttpRequest.newBuilder(url).header("Origin", "https://example.com").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 403;
      assert post(client, URI.create("http://127.0.0.1:" + server.port() + "/mcp/wrong"), "{}").statusCode() == 404;
      assert client.send(base(url).header("MCP-Protocol-Version", "invalid").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 400;
      try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
        try { new McpHttpServer(occupied.getLocalPort(), "x", (t, a) -> a); throw new AssertionError(); }
        catch (BindException expected) {}
      }
    }
    Path root = Files.createTempDirectory("flutter-mcp-config-test");
    try {
      String secret = EndpointConfig.secret(root, "test-ide");
      assert EndpointConfig.secret(root, "test-ide").equals(secret);
      assert !EndpointConfig.secret(root, "other-ide").equals(secret);
      assert EndpointConfig.toml(29191, secret).contains("url = \"" + EndpointConfig.url(29191, secret));
      assert !EndpointConfig.toml(29191, secret).contains("command");
      assert EndpointConfig.toml(29191, secret).startsWith("[mcp_servers.flutter-debug-mcp]");
      assert JsonParser.parseString(EndpointConfig.json(29192, secret)).getAsJsonObject().getAsJsonObject("mcpServers").getAsJsonObject("flutter-debug-mcp").get("url").getAsString().contains(":29192/mcp/");
      try { EndpointConfig.port(0); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
      try { EndpointConfig.port(65536); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
    } finally {
      try (var files = Files.list(root)) { for (Path file : files.toList()) Files.delete(file); }
      Files.delete(root);
    }
    System.out.println("McpHttpServerTest passed");
  }
  /** 构造带请求ID的协议消息。 */
  private static JsonObject request(int id, String method, JsonObject params) { return Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params); }
  /** 构造标准Streamable HTTP请求头。 */
  private static HttpRequest.Builder base(URI url) { return HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream"); }
  /** 使用HTTP发送原始协议文本。 */
  private static HttpResponse<String> post(HttpClient client, URI url, String text) throws Exception { return client.send(base(url).POST(HttpRequest.BodyPublishers.ofString(text)).build(), HttpResponse.BodyHandlers.ofString()); }
  /** 发送JSON-RPC并解析服务端响应。 */
  private static JsonObject rpc(HttpClient client, URI url, JsonElement message) throws Exception {
    HttpResponse<String> response = post(client, url, message.toString());
    assert response.statusCode() == 200 : response.statusCode() + " " + response.body();
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }
}
