package dev.mcmy.ideafluttermcp;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 原生 MCP 生命周期、固定工具目录与参数分发。 */
final class McpProtocol {
  static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
  private final LinkedHashMap<String, JsonObject> definitions = new LinkedHashMap<>();
  private final Invoker invoker;
  interface Invoker {
    /** 执行经过结构验证的工具，权限由执行端检查。 */
    JsonElement call(String tool, JsonObject args) throws Exception;
  }
  /** 从打包资源加载工具定义，与权限状态无关。 */
  McpProtocol(Invoker invoker) throws IOException {
    this.invoker = invoker;
    try (InputStream input = McpProtocol.class.getResourceAsStream("/tools.json")) {
      if (input == null) throw new IOException("Missing tools.json");
      for (JsonElement element : JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonArray()) {
        JsonObject entry = element.getAsJsonObject();
        definitions.put(entry.getAsJsonObject("tool").get("name").getAsString(), entry);
      }
    }
  }
  /** 处理单条 JSON-RPC；合法通知无需响应。 */
  JsonObject handle(JsonElement input) {
    if (!input.isJsonObject()) return error(JsonNull.INSTANCE, -32600, "Invalid Request");
    JsonObject message = input.getAsJsonObject();
    JsonElement id = message.has("id") ? message.get("id") : JsonNull.INSTANCE;
    try {
      if (!"2.0".equals(Json.string(message, "jsonrpc", "")) || !message.has("method")) return error(id, -32600, "Invalid Request");
      String method = Json.required(message, "method");
      if (!message.has("id")) return null;
      if (!id.isJsonPrimitive() || !(id.getAsJsonPrimitive().isString() || id.getAsJsonPrimitive().isNumber())) return error(JsonNull.INSTANCE, -32600, "Invalid id");
      JsonObject params = message.has("params") ? message.getAsJsonObject("params") : new JsonObject();
      JsonElement result;
      switch (method) {
        case "initialize" -> {
          String requested = Json.required(params, "protocolVersion");
          result = Json.obj("protocolVersion", VERSIONS.contains(requested) ? requested : VERSIONS.getFirst(),
              "capabilities", Json.obj("tools", Json.obj("listChanged", false)), "serverInfo", Json.obj("name", "flutter-debug-mcp", "version", "0.5.0"),
              "instructions", "All tools always visible. Permission denial names the required permission in Settings > Tools > Flutter Debug MCP. Rerun changes sessionId; hot restart changes isolate/object IDs.");
        }
        case "ping" -> result = new JsonObject();
        case "tools/list" -> {
          JsonArray tools = new JsonArray();
          definitions.values().forEach(entry -> tools.add(entry.get("tool").deepCopy()));
          result = Json.obj("tools", tools);
        }
        case "tools/call" -> result = invoke(params);
        default -> { return error(id, -32601, "Method not found"); }
      }
      return Json.obj("jsonrpc", "2.0", "id", id, "result", result);
    } catch (IllegalArgumentException | IllegalStateException | ClassCastException error) {
      return error(id, -32602, "Invalid params: " + error.getMessage());
    }
  }
  /** 验证参数并按定义转换 VM 方法，不在工具目录阶段过滤权限。 */
  private JsonObject invoke(JsonObject params) {
    String name = Json.required(params, "name");
    JsonObject definition = definitions.get(name);
    if (definition == null) throw new IllegalArgumentException("Unknown tool: " + name);
    JsonObject args = params.has("arguments") ? params.getAsJsonObject("arguments").deepCopy() : new JsonObject();
    validate(definition.getAsJsonObject("tool").getAsJsonObject("inputSchema"), args, "arguments");
    String bridge = definition.get("bridge").getAsString();
    if (!definition.get("method").isJsonNull()) {
      JsonElement session = args.remove("sessionId");
      args = Json.obj("sessionId", session, "method", definition.get("method"), "params", args);
    }
    try {
      if (name.equals("flutter_service_extension")) {
        String method = Json.required(args, "method");
        if (!method.startsWith("ext.flutter.") && !method.startsWith("ext.dart.io.")) throw new IllegalArgumentException("Expected Flutter service extension");
      }
      JsonElement value = invoker.call(bridge, args);
      return Json.obj("content", List.of(Json.obj("type", "text", "text", Json.GSON.toJson(value))), "isError", false);
    } catch (Exception error) {
      Throwable cause = error;
      while ((cause instanceof java.util.concurrent.ExecutionException || cause instanceof java.util.concurrent.CompletionException) && cause.getCause() != null) cause = cause.getCause();
      return Json.obj("content", List.of(Json.obj("type", "text", "text", (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()) + " | Settings > Tools > Flutter Debug MCP")), "isError", true);
    }
  }
  /** 递归检查 JSON Schema 中工具使用的类型、范围和必填字段。 */
  private static void validate(JsonObject schema, JsonElement value, String path) {
    String type = Json.string(schema, "type", "");
    boolean valid = switch (type) {
      case "object" -> value.isJsonObject();
      case "array" -> value.isJsonArray();
      case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
      case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
      case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
      default -> false;
    };
    if (!valid) throw new IllegalArgumentException(path + ": expected " + type);
    if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value)) throw new IllegalArgumentException(path + ": unsupported value");
    if (type.equals("integer") && ((schema.has("minimum") && value.getAsBigDecimal().compareTo(schema.get("minimum").getAsBigDecimal()) < 0)
        || (schema.has("maximum") && value.getAsBigDecimal().compareTo(schema.get("maximum").getAsBigDecimal()) > 0))) throw new IllegalArgumentException(path + ": outside allowed range");
    if (type.equals("array")) for (JsonElement item : value.getAsJsonArray()) validate(schema.getAsJsonObject("items"), item, path + "[]");
    if (type.equals("object")) {
      JsonObject object = value.getAsJsonObject();
      JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
      if (schema.has("required")) for (JsonElement key : schema.getAsJsonArray("required")) if (!object.has(key.getAsString())) throw new IllegalArgumentException(path + ": missing " + key.getAsString());
      for (var entry : object.entrySet()) {
        if (properties.has(entry.getKey())) validate(properties.getAsJsonObject(entry.getKey()), entry.getValue(), path + "." + entry.getKey());
        else if (schema.has("additionalProperties")) {
          JsonElement additional = schema.get("additionalProperties");
          if (additional.isJsonObject()) validate(additional.getAsJsonObject(), entry.getValue(), path + "." + entry.getKey());
          else if (!additional.getAsBoolean()) throw new IllegalArgumentException(path + ": unknown field " + entry.getKey());
        }
      }
    }
  }
  /** 返回协议错误，保留请求ID。 */
  static JsonObject error(JsonElement id, int code, String message) { return Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", code, "message", message)); }
}
