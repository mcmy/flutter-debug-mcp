package dev.mcmy.ideafluttermcp;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** 本机凭据和审计目录仅允许当前用户读取。 */
final class PrivateFiles {
  private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
  private static final String CACHE_NAME = "idea-flutter-mcp";

  /** 创建私有目录并拒绝符号链接和其他用户的目录。 */
  static void directory(Path path) throws IOException {
    if (!Files.exists(path, NOFOLLOW)) {
      if (posix(path) != null) Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      else Files.createDirectories(path);
    }
    restrict(path, true);
  }

  /** 在写入凭据或审计数据之前限制临时文件访问。 */
  static Path temp(Path directory, String prefix) throws IOException {
    if (posix(directory) != null) {
      return Files.createTempFile(directory, prefix, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
    Path path = Files.createTempFile(directory, prefix, ".tmp");
    try {
      file(path);
      return path;
    } catch (IOException | RuntimeException error) {
      Files.deleteIfExists(path);
      throw error;
    }
  }

  static void file(Path path) throws IOException { restrict(path, false); }

  private static void restrict(Path path, boolean directory) throws IOException {
    var attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
    // On Windows a junction is both a directory and a reparse point (isOther).
    if (attributes.isOther() || !(directory ? attributes.isDirectory() : attributes.isRegularFile())) {
      throw new SecurityException("Expected a private " + (directory ? "directory" : "regular file"));
    }
    var posix = posix(path);
    if (posix != null) {
      if (!posix.getOwner().equals(Files.getOwner(home()))) throw new SecurityException("Unexpected owner of private MCP data");
      posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
      return;
    }

    var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, NOFOLLOW);
    if (acl == null) throw new IOException("MCP data needs a filesystem with POSIX permissions or Windows ACLs");
    var owner = windowsAccount(path);
    if (!acl.getOwner().equals(owner)) throw new SecurityException("Unexpected owner of private MCP data");
    var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
        .setPermissions(EnumSet.allOf(AclEntryPermission.class));
    if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
    var privateAcl = List.of(entry.build());
    if (!acl.getAcl().equals(privateAcl)) acl.setAcl(privateAcl);
    if (!acl.getAcl().equals(privateAcl)) throw new SecurityException("Could not restrict MCP data to the current Windows account");
  }

  private static PosixFileAttributeView posix(Path path) {
    return Files.getFileAttributeView(path, PosixFileAttributeView.class, NOFOLLOW);
  }

  private static UserPrincipal windowsAccount(Path path) throws IOException {
    String domain = System.getenv("USERDOMAIN");
    String user = System.getProperty("user.name");
    String account = domain == null || domain.isBlank() ? user : domain + "\\" + user;
    return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(account);
  }

  /** 返回当前平台用户缓存目录。 */
  static Path root() {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    if (os.startsWith("windows")) {
      String local = System.getenv("LOCALAPPDATA");
      return (local == null || local.isBlank() ? home().resolve("AppData/Local") : Path.of(local)).resolve(CACHE_NAME);
    }
    return home().resolve(os.contains("mac") ? "Library/Caches" : ".cache").resolve(CACHE_NAME);
  }

  private static Path home() { return Path.of(System.getProperty("user.home")); }
}
