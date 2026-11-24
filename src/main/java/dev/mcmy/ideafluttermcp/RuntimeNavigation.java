package dev.mcmy.ideafluttermcp;

import com.google.gson.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class RuntimeNavigation {
  static final String EXTENSION = "ext.flutter_debug_mcp.navigate";

  @FunctionalInterface
  interface VmRpc {
    JsonObject call(String method, JsonObject params, int timeoutSeconds) throws Exception;
  }

  static String uri(String value) {
    try {
      if (new URI(value).isAbsolute()) return value;
    } catch (URISyntaxException ignored) {
      // Do not include the input or its query parameters in diagnostics.
    }
    throw new IllegalArgumentException("uri must be a valid absolute URI with a scheme; percent-encode spaces");
  }

  static JsonObject debug(VmRpc vm, String uri, Runnable beforeDispatch) throws Exception {
    String isolateId = navigationIsolate(vm);
    beforeDispatch.run();
    JsonObject reply;
    try {
      reply = vm.call(EXTENSION, Json.obj("isolateId", isolateId, "uri", uri), 10);
    } catch (TimeoutException error) {
      return unknown();
    } catch (ExecutionException error) {
      if (error.getCause() instanceof VmConnection.RpcException) {
        throw new IllegalStateException("The app's navigation extension returned an RPC error; check its handler and Flutter logs");
      }
      return unknown();
    }
    String status = reply == null ? "" : Json.string(reply, "status", "");
    return switch (status) {
      case "handled", "accepted" -> Json.obj("status", status);
      case "rejected" -> throw new IllegalStateException("The app's navigation handler rejected the URI; check the app's routes and Flutter logs");
      default -> throw new IllegalStateException("The app's navigation extension must return handled, accepted, or rejected");
    };
  }

  private static String navigationIsolate(VmRpc vm) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    try {
      JsonArray isolates = vm.call("getVM", new JsonObject(), 8).getAsJsonArray("isolates");
      String match = null;
      if (isolates != null) for (JsonElement ref : isolates) {
        String id = Json.required(ref.getAsJsonObject(), "id");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException();
        JsonObject isolate = vm.call("getIsolate", Json.obj("isolateId", id), (int)Math.ceil(remaining / 1_000_000_000.0));
        JsonArray extensions = isolate.getAsJsonArray("extensionRPCs");
        if (extensions == null || !extensions.contains(new JsonPrimitive(EXTENSION))) continue;
        if (match != null) throw new IllegalStateException("Register the navigation extension only in the app's main isolate");
        JsonObject pause = isolate.getAsJsonObject("pauseEvent");
        if (pause != null && Json.string(pause, "kind", "").startsWith("Pause")) {
          throw new IllegalStateException("Navigation isolate is paused; resume it before calling flutter_navigate");
        }
        match = id;
      }
      if (match != null) return match;
    } catch (TimeoutException | ExecutionException error) {
      throw new IllegalStateException("Could not inspect the app's isolates; check the Flutter debug connection");
    }
    throw new IllegalStateException("Navigation extension missing; register " + EXTENSION + " in the target app's debug build");
  }

  static JsonObject unknown() {
    return Json.obj("status", "unknown", "message", "Delivery was not confirmed; check app state before retrying");
  }
}
