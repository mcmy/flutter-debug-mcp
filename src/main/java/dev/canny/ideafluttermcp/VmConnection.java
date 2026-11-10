package dev.canny.ideafluttermcp;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 独立 VM 协议连接，不接管 IDEA 自有连接或流订阅。 */
final class VmConnection implements WebSocket.Listener, AutoCloseable {
  private final ConcurrentMap<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
  private final AtomicLong ids = new AtomicLong();
  private final StringBuilder fragments = new StringBuilder();
  private final Consumer<JsonObject> events;
  private final HttpClient client;
  private volatile WebSocket socket;
  private volatile boolean closed;
  final String uri;

  /** 建立本机连接；握手失败时释放 HTTP 客户端。 */
  VmConnection(String uri, Consumer<JsonObject> events) throws Exception {
    URI address = URI.create(uri);
    String host = address.getHost();
    if (!("ws".equals(address.getScheme()) || "wss".equals(address.getScheme()))
        || !("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host))
        || address.getUserInfo() != null || address.getPort() < 1) throw new IllegalArgumentException("VM URI must be loopback WebSocket");
    this.uri = uri;
    this.events = events;
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    try { socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(address, this).get(6, TimeUnit.SECONDS); }
    catch (Exception error) { client.shutdownNow(); throw error; }
  }
  /** 发出带超时清理的 JSON-RPC 请求。 */
  JsonObject call(String method, JsonObject params, int timeoutSeconds) throws Exception {
    if (closed) throw new IllegalStateException("VM connection closed; rediscover the running session");
    String id = Long.toString(ids.incrementAndGet());
    CompletableFuture<JsonObject> result = new CompletableFuture<>();
    pending.put(id, result);
    try {
      socket.sendText(Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params).toString(), true)
          .whenComplete((ignored, error) -> { if (error != null) result.completeExceptionally(error); });
      return result.get(timeoutSeconds, TimeUnit.SECONDS);
    } finally { pending.remove(id); }
  }
  /** 请求首个 WebSocket 数据帧。 */
  @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
  /** 拼接有界协议帧并分别分发响应与运行时事件。 */
  @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    try {
      if (fragments.length() + data.length() > 16 * 1024 * 1024) throw new IllegalStateException("VM message exceeds 16 MiB");
      fragments.append(data);
      if (last) {
        JsonObject message = JsonParser.parseString(fragments.toString()).getAsJsonObject();
        fragments.setLength(0);
        if (message.has("id")) {
          CompletableFuture<JsonObject> response = pending.get(message.get("id").getAsString());
          if (response != null) {
            if (message.has("error")) response.completeExceptionally(new IllegalStateException("VM RPC: " + message.get("error")));
            else response.complete(message.getAsJsonObject("result"));
          }
        } else if ("streamNotify".equals(Json.string(message, "method", ""))) events.accept(message.getAsJsonObject("params"));
      }
      webSocket.request(1);
    } catch (Exception error) { fail(error); webSocket.abort(); }
    return null;
  }
  /** 标记连接已结束并结束等待中的请求。 */
  private void fail(Throwable error) {
    closed = true;
    pending.values().forEach(future -> future.completeExceptionally(error));
    pending.clear();
  }
  /** 将远端断开传播给调用者。 */
  @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
    fail(new IllegalStateException("VM disconnected (" + status + ")"));
    return null;
  }
  /** 将传输错误传播给调用者。 */
  @Override public void onError(WebSocket socket, Throwable error) { fail(error); }
  /** 供会话管理器识别失效连接。 */
  boolean isClosed() { return closed; }
  /** 关闭插件拥有的连接并释放客户端资源。 */
  @Override public void close() {
    fail(new IllegalStateException("VM connection disposed"));
    if (socket != null) socket.abort();
    client.shutdownNow();
  }
}
