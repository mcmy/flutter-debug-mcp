package dev.canny.ideafluttermcp;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** 本机凭据和审计目录仅允许当前用户读取。 */
final class PrivateFiles {
  /** 创建私有目录并拒绝符号链接和其他用户的目录。 */
  static void directory(Path path) throws Exception {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        || !Files.getOwner(path).equals(Files.getOwner(Path.of(System.getProperty("user.home"))))) throw new SecurityException("Invalid private directory");
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
  }
  /** 创建权限为0600的临时文件。 */
  static Path temp(Path directory, String prefix) throws Exception {
    return Files.createTempFile(directory, prefix, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
  }
  /** 返回当前平台用户缓存目录。 */
  static Path root() {
    Path home = Path.of(System.getProperty("user.home"));
    return System.getProperty("os.name").toLowerCase().contains("mac") ? home.resolve("Library/Caches/idea-flutter-mcp") : home.resolve(".cache/idea-flutter-mcp");
  }
}
