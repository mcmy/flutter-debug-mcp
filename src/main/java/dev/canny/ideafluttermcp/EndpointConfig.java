package dev.canny.ideafluttermcp;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 稳定的私有URL凭据和可复制客户端配置。 */
final class EndpointConfig {
  static final int DEFAULT_PORT = 29191;
  /** 按IDE配置目录隔离密钥，重启和改端口不轮换URL令牌。 */
  static String secret(Path root, String identity) throws Exception {
    PrivateFiles.directory(root);
    String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
    Path file = root.resolve("endpoint-" + key + ".secret");
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 256
          || !Files.getOwner(file).equals(Files.getOwner(Path.of(System.getProperty("user.home"))))) throw new SecurityException("Invalid endpoint secret file");
      Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      String value = Files.readString(file).trim();
      if (!value.matches("[a-f0-9]{64}")) throw new IllegalStateException("Invalid endpoint secret");
      return value;
    }
    byte[] random = new byte[32];
    new java.security.SecureRandom().nextBytes(random);
    String value = HexFormat.of().formatHex(random);
    Path temporary = PrivateFiles.temp(root, ".endpoint-");
    try {
      Files.writeString(temporary, value);
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
    } finally { Files.deleteIfExists(temporary); }
    return value;
  }
  /** 验证用户输入的固定TCP端口。 */
  static int port(int value) {
    if (value < 1024 || value > 65535) throw new IllegalArgumentException("端口必须在 1024–65535 之间");
    return value;
  }
  /** 返回可单独粘贴使用的完整URL。 */
  static String url(int port, String secret) { return "http://127.0.0.1:" + port(port) + "/mcp/" + secret; }
  /** 生成Codex项目配置，无需外部进程或额外请求头。 */
  static String toml(int port, String secret) {
    return "[mcp_servers.flutter-debug-mcp]\nurl = \"" + url(port, secret) + "\"\nenabled = true\nstartup_timeout_sec = 20\ntool_timeout_sec = 120\n";
  }
  /** 生成通用MCP客户端JSON。 */
  static String json(int port, String secret) {
    return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Json.obj("mcpServers", Json.obj("flutter-debug-mcp", Json.obj("url", url(port, secret)))));
  }
}
