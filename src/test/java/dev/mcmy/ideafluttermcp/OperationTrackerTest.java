package dev.mcmy.ideafluttermcp;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 用可控时间和 Future 验证锁屏等长等待场景，无需设备或 IDE。 */
final class OperationTrackerTest {
  static void run() throws Exception {
    AtomicLong now = new AtomicLong();
    OperationTracker tracker = new OperationTracker(now::get);
    AtomicInteger dispatched = new AtomicInteger();
    CompletableFuture<com.google.gson.JsonObject> future = new CompletableFuture<>();
    var first = tracker.start("hot_reload", "app", Json.obj("sessionId", "s"), () -> { dispatched.incrementAndGet(); return future; });
    OperationTracker.await(first.operation(), 0);
    assert !future.isCancelled();
    assert tracker.read(first.operation().id).get("status").getAsString().equals("pending");
    try (var pool = Executors.newFixedThreadPool(4)) {
      var calls = new java.util.ArrayList<Future<OperationTracker.Start>>();
      for (int i = 0; i < 20; i++) calls.add(pool.submit(() -> tracker.start("hot_restart", "app", Json.obj(), () -> { dispatched.incrementAndGet(); return new CompletableFuture<>(); })));
      for (var call : calls) assert call.get().duplicate() && call.get().operation() == first.operation();
    }
    assert dispatched.get() == 1;
    now.set(TimeUnit.SECONDS.toNanos(121));
    var unknown = tracker.read(first.operation().id);
    assert unknown.get("status").getAsString().equals("unknown");
    assert !unknown.has("ok") && !unknown.get("retryAllowed").getAsBoolean();
    future.complete(Json.obj("ok", true, "message", "Reload completed after unlock"));
    assert tracker.read(first.operation().id).get("status").getAsString().equals("succeeded");
    long elapsed = tracker.read(first.operation().id).get("elapsedMillis").getAsLong();
    now.addAndGet(TimeUnit.SECONDS.toNanos(10));
    assert tracker.read(first.operation().id).get("elapsedMillis").getAsLong() == elapsed;
    assert tracker.activeId("app") == null;
    for (String kind : java.util.List.of("run", "rerun", "stop", "hot_restart")) {
      var op = tracker.start(kind, "app", Json.obj(), () -> CompletableFuture.completedFuture(Json.obj("ok", false, "message", "Confirmed failure")));
      assert tracker.read(op.operation().id).get("status").getAsString().equals("failed");
      assert tracker.activeId("app") == null;
    }
    var broken = tracker.start("hot_reload", "app", Json.obj(), () -> CompletableFuture.failedFuture(new TimeoutException()));
    assert tracker.read(broken.operation().id).get("status").getAsString().equals("unknown");
    assert tracker.activeId("app").equals(broken.operation().id);
    tracker.processStopped("app");
    assert tracker.activeId("app") == null;
    CompletableFuture<com.google.gson.JsonObject> delayed = new CompletableFuture<>();
    var stoppedReload = tracker.start("hot_reload", "app", Json.obj(), () -> delayed);
    tracker.processStopped("app");
    delayed.complete(Json.obj("ok", true, "message", "Late reply"));
    assert tracker.read(stoppedReload.operation().id).get("status").getAsString().equals("unknown");
    var launching = tracker.start("rerun", "launching", Json.obj(), CompletableFuture::new);
    tracker.processStopped("launching");
    assert tracker.activeId("launching").equals(launching.operation().id);
    var stop = tracker.start("stop", "stop:s", Json.obj(), CompletableFuture::new);
    var duringStop = tracker.start("run", "s", Json.obj(), () -> { throw new AssertionError("Must not launch while stopping"); });
    assert duringStop.duplicate() && duringStop.operation() == stop.operation();
    for (int i = 0; i < 150; i++) tracker.start("run", "history", Json.obj(), () -> CompletableFuture.completedFuture(Json.obj("ok", true, "message", "started")));
    assert tracker.read(stop.operation().id).get("status").getAsString().equals("pending");
    try { tracker.read("missing"); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
    tracker.close();
    assert tracker.read(stop.operation().id).get("status").getAsString().equals("unknown");
    assert !tracker.read(stop.operation().id).get("retryAllowed").getAsBoolean();
    try { tracker.start("run", "new", Json.obj(), CompletableFuture::new); throw new AssertionError(); } catch (IllegalStateException expected) {}
    OperationTracker limit = new OperationTracker();
    for (int i = 0; i < 128; i++) limit.start("run", "app" + i, Json.obj(), CompletableFuture::new);
    try { limit.start("run", "overflow", Json.obj(), CompletableFuture::new); throw new AssertionError(); } catch (IllegalStateException expected) {}
    limit.close();
    System.out.println("OperationTrackerTest passed");
  }
}
