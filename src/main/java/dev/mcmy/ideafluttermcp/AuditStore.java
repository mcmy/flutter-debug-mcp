package dev.mcmy.ideafluttermcp;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 私有有界审计记录，不存参数、表达式、连接地址或响应正文。 */
final class AuditStore {
  private final Path file;
  private final ArrayDeque<JsonObject> records = new ArrayDeque<>();
  /** 读取当前 IDE 的历史操作。 */
  AuditStore(Path file) throws Exception {
    this.file = file;
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || !Files.getOwner(file).equals(Files.getOwner(Path.of(System.getProperty("user.home"))))) throw new SecurityException("Invalid audit file");
      Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      if (Files.size(file) > 4 * 1024 * 1024) throw new IllegalStateException("Audit file too large");
      for (String line : Files.readAllLines(file)) if (!line.isBlank()) records.add(JsonParser.parseString(line).getAsJsonObject());
      while (records.size() > 2000) records.removeFirst();
    }
  }
  /** 原子保存最近两千条执行结果。 */
  synchronized void add(String tool, String status, long millis) throws Exception {
    records.add(Json.obj("time", Instant.now().toString(), "tool", tool, "status", status, "durationMs", millis));
    while (records.size() > 2000) records.removeFirst();
    Path temporary = PrivateFiles.temp(file.getParent(), ".audit-");
    try {
      StringBuilder text = new StringBuilder();
      records.forEach(row -> text.append(row).append('\n'));
      Files.writeString(temporary, text, StandardCharsets.UTF_8);
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally { Files.deleteIfExists(temporary); }
  }
  /** 按工具或状态文字过滤并返回最近记录。 */
  synchronized JsonArray query(String query, int limit) {
    JsonArray out = new JsonArray();
    Iterator<JsonObject> iterator = records.descendingIterator();
    while (iterator.hasNext() && out.size() < limit) {
      JsonObject row = iterator.next();
      if (row.toString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) out.add(row.deepCopy());
    }
    return out;
  }
}
