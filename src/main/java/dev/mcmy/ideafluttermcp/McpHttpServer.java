package dev.mcmy.ideafluttermcp;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** 无状态 Streamable HTTP，POST 返回 JSON，GET 明确不提供独立 SSE 流。 */
final class McpHttpServer implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService executor;
  private final McpProtocol protocol;
  private final String path;
  /** 绑定固定本机端口；URL中的私有令牌使客户端无需额外认证配置。 */
  McpHttpServer(int port, String secret, McpProtocol.Invoker invoker) throws Exception {
    protocol = new McpProtocol(invoker);
    path = "/mcp/" + secret;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
    executor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
      Thread thread = new Thread(runnable, "IdeaFlutterMcp"); thread.setDaemon(true); return thread;
    });
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }
  /** 返回实际绑定端口，测试可使用系统分配端口。 */
  int port() { return server.getAddress().getPort(); }
  /** 验证来源、路径、协议版本和请求类型后分发 JSON-RPC。 */
  private void handle(HttpExchange exchange) throws java.io.IOException {
    try {
      if (exchange.getRequestHeaders().containsKey("Origin")) { send(exchange, 403, McpProtocol.error(JsonNull.INSTANCE, -32000, "Browser origin rejected")); return; }
      if (!MessageDigest.isEqual(path.getBytes(StandardCharsets.UTF_8), exchange.getRequestURI().getRawPath().getBytes(StandardCharsets.UTF_8))) { send(exchange, 404, McpProtocol.error(JsonNull.INSTANCE, -32000, "Endpoint not found")); return; }
      String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
      if (version != null && !McpProtocol.VERSIONS.contains(version)) { send(exchange, 400, McpProtocol.error(JsonNull.INSTANCE, -32600, "Unsupported protocol version")); return; }
      if (!exchange.getRequestMethod().equals("POST")) { exchange.getResponseHeaders().set("Allow", "POST"); send(exchange, 405, null); return; }
      String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
      if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) { send(exchange, 415, null); return; }
      String accept = exchange.getRequestHeaders().getFirst("Accept");
      if (accept == null || !accept.contains("application/json") || !accept.contains("text/event-stream")) { send(exchange, 406, null); return; }
      byte[] body = exchange.getRequestBody().readNBytes(1024 * 1024 + 1);
      if (body.length > 1024 * 1024) { send(exchange, 413, null); return; }
      JsonElement message;
      try { message = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)); }
      catch (JsonParseException error) { send(exchange, 400, McpProtocol.error(JsonNull.INSTANCE, -32700, "Parse error")); return; }
      // 客户端对服务端请求的响应无需再生成响应；当前服务不发起请求。
      if (message.isJsonObject() && !message.getAsJsonObject().has("method") && (message.getAsJsonObject().has("result") || message.getAsJsonObject().has("error"))) { send(exchange, 202, null); return; }
      JsonObject response = protocol.handle(message);
      send(exchange, response == null ? 202 : 200, response);
    } finally { exchange.close(); }
  }
  /** 输出协议响应，通知和不支持的方法使用空响应体。 */
  private static void send(HttpExchange exchange, int status, JsonObject body) throws java.io.IOException {
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    if (body == null) { exchange.sendResponseHeaders(status, -1); return; }
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
  /** 停止监听并释放工作线程。 */
  @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
