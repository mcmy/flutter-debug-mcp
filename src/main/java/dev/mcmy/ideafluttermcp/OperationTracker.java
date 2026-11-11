package dev.mcmy.ideafluttermcp;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** 有界操作记录。结束等待不会取消 IDE 请求或解开重复调用保护。 */
final class OperationTracker {
  private static final long SLOW_NANOS = TimeUnit.SECONDS.toNanos(120);
  private final LongSupplier clock;
  private final LinkedHashMap<String, Operation> history = new LinkedHashMap<>();
  private final Map<String, Operation> active = new HashMap<>();
  private boolean closed;

  static final class Operation {
    final String id = UUID.randomUUID().toString();
    final String kind;
    final String target;
    final JsonObject context;
    final String startedAt = Instant.now().toString();
    final long started;
    final CompletableFuture<Void> completion = new CompletableFuture<>();
    String status = "pending";
    String message = "Waiting for Flutter";
    String completedAt;
    long finished = -1;
    boolean superseded;
    Operation(String kind, String target, JsonObject context, long started) { this.kind = kind; this.target = target; this.context = context.deepCopy(); this.started = started; }
  }
  record Start(Operation operation, boolean duplicate) {}

  OperationTracker() { this(System::nanoTime); }
  OperationTracker(LongSupplier clock) { this.clock = clock; }

  synchronized Start start(String kind, String target, JsonObject context, Callable<CompletableFuture<JsonObject>> launch) throws Exception {
    if (closed) throw new IllegalStateException("MCP server stopped");
    if (active.containsKey("stop:" + target)) return new Start(active.get("stop:" + target), true);
    if (active.containsKey(target)) return new Start(active.get(target), true);
    if (history.size() >= 128 && history.values().stream().allMatch(op -> active.containsValue(op))) throw new IllegalStateException("Too many unresolved operations");
    Operation operation = new Operation(kind, target, context, clock.getAsLong());
    CompletableFuture<JsonObject> future = Objects.requireNonNull(launch.call());
    history.put(operation.id, operation);
    active.put(target, operation);
    while (history.size() > 128) {
      String oldest = history.entrySet().stream().filter(entry -> !active.containsValue(entry.getValue())).map(Map.Entry::getKey).findFirst().orElseThrow();
      history.remove(oldest);
    }
    future.whenComplete((value, error) -> finish(operation, value, error));
    return new Start(operation, false);
  }

  private synchronized void finish(Operation operation, JsonObject value, Throwable error) {
    if (closed || operation.superseded) return;
    if (error != null || value == null) {
      // Transport errors do not establish whether the device applied the change.
      operation.status = "unknown";
      operation.message = "Flutter result unavailable; do not resend automatically. Check device, app foreground and debugger state.";
    } else {
      operation.status = Json.bool(value, "ok", false) ? "succeeded" : "failed";
      operation.message = Json.string(value, "message", "");
      operation.completedAt = Instant.now().toString();
      operation.finished = clock.getAsLong();
      active.remove(operation.target, operation);
    }
    operation.completion.complete(null);
  }

  synchronized JsonObject read(String id) {
    Operation operation = history.get(id);
    if (operation == null) throw new IllegalArgumentException("Operation unavailable; records last until the MCP server stops; completed history is bounded to 128 entries");
    long elapsed = Math.max(0, (operation.finished < 0 ? clock.getAsLong() : operation.finished) - operation.started);
    String status = operation.status;
    if (status.equals("pending") && elapsed >= SLOW_NANOS) status = "unknown";
    boolean unresolved = status.equals("pending") || status.equals("unknown");
    JsonObject out = Json.obj("operationId", operation.id, "operation", operation.kind, "status", status,
        "startedAt", operation.startedAt, "completedAt", operation.completedAt,
        "elapsedMillis", TimeUnit.NANOSECONDS.toMillis(elapsed), "retryAllowed", !closed && !unresolved,
        "deviceLocked", "unknown", "message", operation.message);
    out.add("target", operation.context.deepCopy());
    if (!unresolved) out.addProperty("ok", status.equals("succeeded"));
    else out.addProperty("hint", "Check whether the device needs unlocking and bring the app to foreground; check debugger pause and connection. Query flutter_operation_status instead of repeating run/rerun/reload/restart/stop. A timeout does not cancel the original request. If it cannot recover, explicitly stop and run again.");
    return out;
  }

  synchronized String activeId(String target) { return active.containsKey(target) ? active.get(target).id : null; }

  synchronized void observe(String target, JsonObject state) {
    Operation operation = active.get(target);
    if (operation == null) return;
    operation.context.add("lastObservedSessionState", state.deepCopy());
    operation.context.addProperty("stateObservedAt", Instant.now().toString());
  }

  /** 已确认进程停止后允许显式重新启动，但不把原请求伪装为成功或可重试。 */
  synchronized void processStopped(String target) {
    Operation operation = active.get(target);
    if (operation == null) return;
    if (operation.kind.equals("run") || operation.kind.equals("rerun")) {
      operation.message = "Old process stopped but IDE launch may still be queued. Check or cancel the IDE launch; do not issue another run automatically.";
      return;
    }
    active.remove(target);
    operation.superseded = true;
    operation.status = "unknown";
    operation.message = "Process stopped before this operation was confirmed. The original result is unknown; an explicit new run is now allowed.";
    operation.completion.complete(null);
  }

  synchronized void close() {
    closed = true;
    for (Operation operation : active.values()) {
      operation.status = "unknown";
      operation.message = "MCP server stopped before the result was confirmed";
      operation.completion.complete(null);
    }

  }

  static void await(Operation operation, int seconds) throws InterruptedException, ExecutionException {
    try { operation.completion.get(seconds, TimeUnit.SECONDS); }
    catch (TimeoutException ignored) { /* The original request remains tracked. */ }
  }
}
