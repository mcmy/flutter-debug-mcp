package dev.mcmy.ideafluttermcp;

import com.google.gson.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.*;

/** 使用本机虚拟 VM 验证 WebSocket 协议；不启动 MCP 或真实 Flutter。 */
public final class VmConnectionTest {
  /** 验证分片响应、事件、RPC错误和断线传播。 */
  public static void main(String[] args) throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(5000);
          InputStream in = socket.getInputStream();
          OutputStream out = socket.getOutputStream();
          StringBuilder headers = new StringBuilder();
          while (!headers.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            headers.append((char)b);
          }
          String key = headers.toString().lines().filter(line -> line.toLowerCase().startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":", 2)[1].trim();
          String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
          out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          out.flush();
          JsonObject request = read(in);
          String reply = Json.obj("jsonrpc", "2.0", "id", request.get("id"), "result", Json.obj("type", "VM")).toString();
          send(out, reply.substring(0, 10), 1, false);
          send(out, reply.substring(10), 0, true);
          send(out, Json.obj("jsonrpc", "2.0", "method", "streamNotify", "params", Json.obj("streamId", "Debug")).toString(), 1, true);
          request = read(in);
          send(out, Json.obj("jsonrpc", "2.0", "id", request.get("id"), "error", Json.obj("code", 100, "message", "Isolate must be paused")).toString(), 1, true);
          read(in);
        } catch (Exception error) { throw new CompletionException(error); }
      });
      CountDownLatch event = new CountDownLatch(1);
      try (VmConnection vm = new VmConnection("ws://127.0.0.1:" + server.getLocalPort() + "/ws", data -> event.countDown())) {
        assert vm.call("getVM", new JsonObject(), 3).get("type").getAsString().equals("VM");
        assert event.await(3, TimeUnit.SECONDS);
        try { vm.call("getStack", new JsonObject(), 3); throw new AssertionError(); }
        catch (ExecutionException error) { assert error.getCause().getMessage().contains("paused"); }
        try { vm.call("getVM", new JsonObject(), 3); throw new AssertionError(); }
        catch (ExecutionException error) { assert error.getCause() != null; }
      }
      served.get(5, TimeUnit.SECONDS);
    }
    try { new VmConnection("ws://example.com:1234/ws", data -> {}); throw new AssertionError(); }
    catch (IllegalArgumentException expected) {}
    System.out.println("VmConnectionTest passed");
  }
  /** 解码客户端屏蔽的短 WebSocket 文本帧。 */
  private static JsonObject read(InputStream in) throws Exception {
    if (in.read() < 0) throw new EOFException();
    int flags = in.read();
    int length = flags & 127;
    if (length == 126) length = (in.read() << 8) | in.read();
    if (length > 65535 || (flags & 128) == 0) throw new IOException("Unexpected test frame");
    byte[] mask = in.readNBytes(4), payload = in.readNBytes(length);
    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
    return JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
  }
  /** 写出测试响应及分片帧。 */
  private static void send(OutputStream out, String text, int opcode, boolean last) throws Exception {
    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
    out.write((last ? 128 : 0) | opcode);
    if (payload.length < 126) out.write(payload.length);
    else { out.write(126); out.write(payload.length >> 8); out.write(payload.length & 255); }
    out.write(payload);
    out.flush();
  }
}
