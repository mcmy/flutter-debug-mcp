package dev.canny.ideafluttermcp;

import com.google.gson.*;
import java.time.Instant;
import java.util.*;

/** 有界日志缓存；行号在淘汰和分块写入后保持稳定。 */
final class LineBuffer {
  private record Line(long number, String time, String channel, String text) {}
  private final ArrayDeque<Line> lines = new ArrayDeque<>();
  private final int capacity;
  private long next = 1;
  private int retainedCharacters;
  private static final int MAX_CHARACTERS = 4 * 1024 * 1024;
  private final Map<String, StringBuilder> partial = new HashMap<>();
  private final Set<String> carriageReturn = new HashSet<>();
  private static final int MAX_LINE = 16384;

  /** 指定保留行数上限。 */
  LineBuffer(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
  }
  /** 拼接进程输出分片并限制单行内存。 */
  synchronized void append(String channel, String text) {
    StringBuilder pending = partial.computeIfAbsent(channel, ignored -> new StringBuilder());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') {
        if (!(c == '\n' && carriageReturn.contains(channel))) add(channel, pending.toString());
        if (c == '\r') carriageReturn.add(channel); else carriageReturn.remove(channel);
        pending.setLength(0);
      } else {
        carriageReturn.remove(channel);
        pending.append(c);
        if (pending.length() >= MAX_LINE) { add(channel, pending.toString()); pending.setLength(0); }
      }
    }
  }
  /** 发布尚未换行的尾部，供进程结束时读取。 */
  synchronized void flush() {
    partial.forEach((channel, pending) -> { if (!pending.isEmpty()) { add(channel, pending.toString()); pending.setLength(0); } });
  }
  /** 写入带时间和来源的完整行。 */
  private void add(String channel, String text) {
    lines.addLast(new Line(next++, Instant.now().toString(), channel, text));
    retainedCharacters += text.length();
    while (lines.size() > capacity || retainedCharacters > MAX_CHARACTERS) retainedCharacters -= lines.removeFirst().text().length();
  }
  /** 按稳定行号分页或搜索，并为匹配行返回上下文。 */
  synchronized JsonObject read(JsonObject args) {
    long first = lines.isEmpty() ? next : lines.getFirst().number();
    int limit = Json.integer(args, "limit", 200, 1, 1000);
    long from = Json.integer(args, "fromLine", (int)Math.min(first, Integer.MAX_VALUE), 1, Integer.MAX_VALUE);
    int before = Json.integer(args, "before", 0, 0, 100);
    int after = Json.integer(args, "after", 0, 0, 100);
    String query = Json.string(args, "query", "");
    String channel = Json.string(args, "channel", "");
    boolean sensitive = Json.bool(args, "caseSensitive", false);
    String needle = sensitive ? query : query.toLowerCase(Locale.ROOT);
    List<Line> snapshot = new ArrayList<>(lines);
    JsonArray matches = new JsonArray();
    JsonArray rows = new JsonArray();
    TreeSet<Integer> indices = new TreeSet<>();
    long cursor = Math.max(from, first);
    boolean more = false;
    for (int i = 0; i < snapshot.size(); i++) {
      Line line = snapshot.get(i);
      if (line.number() < from) continue;
      boolean hit = (channel.isEmpty() || channel.equals(line.channel()))
          && (sensitive ? line.text() : line.text().toLowerCase(Locale.ROOT)).contains(needle);
      if (!hit) { cursor = line.number() + 1; continue; }
      int start = Math.max(0, i - before), end = Math.min(snapshot.size() - 1, i + after);
      if (end - start + 1 > limit) throw new IllegalArgumentException("limit must fit before + match + after context");
      int extra = 0;
      for (int j = start; j <= end; j++) if (!indices.contains(j)) extra++;
      if (indices.size() + extra > limit) { more = true; break; }
      matches.add(line.number());
      for (int j = start; j <= end; j++) indices.add(j);
      cursor = line.number() + 1;
    }
    for (int i : indices) {
      Line line = snapshot.get(i);
      rows.add(Json.obj("line", line.number(), "time", line.time(), "channel", line.channel(), "text", line.text()));
    }
    JsonArray pending = new JsonArray();
    partial.forEach((name, value) -> { if (!value.isEmpty()) pending.add(Json.obj("channel", name, "text", value.toString())); });
    return Json.obj("firstAvailableLine", first, "nextLine", next, "nextFromLine", cursor,
        "truncated", from < first, "hasMore", more, "matches", matches, "lines", rows, "pending", pending);
  }
}
