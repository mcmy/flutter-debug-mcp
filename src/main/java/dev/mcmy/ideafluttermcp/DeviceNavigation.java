package dev.mcmy.ideafluttermcp;

import com.google.gson.JsonObject;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.EnvironmentUtil;
import io.flutter.android.IntelliJAndroidSdk;
import io.flutter.sdk.FlutterSdk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Delivers a link through the OS of the device already attached to the IDE session. */
final class DeviceNavigation {
  private static final Pattern ANDROID_APP_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)++");
  private static final String XCRUN = "/usr/bin/xcrun";

  static boolean android(String platform) {
    return platform != null && (platform.equals("android") || platform.startsWith("android-"));
  }

  static String findAdb(Project project) {
    String adbName = SystemInfo.isWindows ? "adb.exe" : "adb";
    var ideaSdk = ReadAction.compute(() -> IntelliJAndroidSdk.fromProject(project));
    String executable = adbInSdk(ideaSdk == null ? null : ideaSdk.getHome().getPath(), adbName);
    if (executable != null) return executable;

    var flutterSdk = ReadAction.compute(() -> FlutterSdk.getFlutterSdk(project));
    executable = adbInSdk(flutterSdk == null ? null : flutterSdk.queryFlutterConfig("android-sdk", true), adbName);
    if (executable != null) return executable;

    String defaultSdk = defaultAndroidSdk(Path.of(System.getProperty("user.home")), SystemInfo.isWindows, SystemInfo.isMac,
        EnvironmentUtil.getValue("LOCALAPPDATA")).toString();
    for (String home : new String[]{EnvironmentUtil.getValue("ANDROID_HOME"), EnvironmentUtil.getValue("ANDROID_SDK_ROOT"), defaultSdk}) {
      executable = adbInSdk(home, adbName);
      if (executable != null) return executable;
    }
    var adb = PathEnvironmentVariableUtil.findInPath(adbName);
    if (adb == null || !Files.isExecutable(adb.toPath())) {
      throw new IllegalStateException("adb unavailable; install Android SDK Platform-Tools and check the project's Android SDK");
    }
    return adb.getAbsolutePath();
  }

  static Path defaultAndroidSdk(Path home, boolean windows, boolean mac, String localAppData) {
    if (windows) {
      Path local = localAppData == null || localAppData.isBlank() ? home.resolve("AppData/Local") : Path.of(localAppData);
      return local.resolve("Android/Sdk");
    }
    return home.resolve(mac ? "Library/Android/sdk" : "Android/Sdk");
  }

  private static String adbInSdk(String home, String adbName) {
    if (home == null || home.isBlank()) return null;
    Path path = Path.of(home, "platform-tools", adbName);
    return Files.isRegularFile(path) && Files.isExecutable(path) ? path.toAbsolutePath().toString() : null;
  }

  static JsonObject openAndroid(String adb, String deviceId, String uri, String appId, int vmPid, Runnable beforeDispatch) throws IOException {
    if (appId != null && !validAppId(appId)) throw new IllegalArgumentException("appId must be an Android application ID");
    Integer user = null;
    if (vmPid > 0) {
      var process = run("adb", List.of(adb, "-s", deviceId, "shell", "ps", "-p", Integer.toString(vmPid), "-o", "UID"), 5_000);
      checkAdbDevice(process);
      if (!succeeded(process)) throw new IllegalStateException("The Flutter VM process is no longer running on the session device");
      Integer uid = androidProcessUid(process);
      if (uid != null) {
        // Android reserves a range of 100,000 UIDs for each user/profile.
        int processUser = uid / 100_000;
        var packages = run("adb", List.of(adb, "-s", deviceId, "shell", "cmd", "package", "list", "packages",
            "--user", Integer.toString(processUser), "--uid", Integer.toString(uid)), 5_000);
        checkAdbDevice(packages);
        List<String> owners = androidPackages(packages);
        if (!owners.isEmpty()) {
          appId = androidAppId(owners, appId);
          user = processUser;
        }
      }
    }
    if (appId == null) throw new IllegalArgumentException("Cannot identify the running Android app; pass its exact appId");
    if (user == null) checkAndroidRunning(run("adb", List.of(adb, "-s", deviceId, "shell", "pidof", appId), 5_000), appId);
    beforeDispatch.run();
    return androidResult(run("adb", androidCommand(adb, deviceId, uri, appId, user), 10_000));
  }

  private static void checkAndroidRunning(ProcessOutput result, String appId) {
    checkAdbDevice(result);
    if (!succeeded(result) || !result.getStdout().strip().matches("[0-9]+(\\s+[0-9]+)*")) {
      throw new IllegalStateException("Android app " + appId + " is not running on the session device");
    }
  }

  static Integer androidProcessUid(ProcessOutput result) {
    var lines = result.getStdoutLines(true);
    if (!succeeded(result) || lines.size() != 2 || !"UID".equals(lines.getFirst().strip())) return null;
    try {
      int uid = Integer.parseInt(lines.getLast().strip());
      return uid < 0 ? null : uid;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static List<String> androidPackages(ProcessOutput result) {
    if (!succeeded(result)) return List.of();
    return result.getStdoutLines(true).stream().map(String::strip).filter(line -> line.startsWith("package:"))
        .map(line -> line.substring("package:".length())).filter(DeviceNavigation::validAppId).distinct().toList();
  }

  static String androidAppId(List<String> owners, String appId) {
    if (appId == null) {
      if (owners.size() == 1) return owners.getFirst();
      throw new IllegalArgumentException("The running Android process has a shared UID; pass the exact appId");
    }
    if (!owners.contains(appId)) throw new IllegalArgumentException("appId does not match the running Flutter session's Android UID");
    return appId;
  }

  private static boolean validAppId(String appId) {
    return appId.length() <= 255 && ANDROID_APP_ID.matcher(appId).matches();
  }

  static List<String> androidCommand(String adb, String deviceId, String uri, String appId, Integer user) {
    // adb joins these arguments into a command for the device shell.
    String quotedUri = "'" + uri.replace("'", "'\\''") + "'";
    return List.of(adb, "-s", deviceId, "shell", "am", "start", "--user", user == null ? "current" : user.toString(),
        "-W", "-a", "android.intent.action.VIEW", "-d", quotedUri, "-p", appId);
  }

  static JsonObject androidResult(ProcessOutput result) {
    var lines = result.getStdoutLines(true);
    if (unconfirmed(result) || lines.contains("Status: timeout")) return RuntimeNavigation.unknown();
    if (succeeded(result) && lines.contains("Status: ok")) return Json.obj("status", "accepted");
    if (result.getStdout().contains("unable to resolve Intent") || result.getStderr().contains("unable to resolve Intent")) {
      throw new IllegalStateException("No matching Android activity; check the app's intent filter and appId");
    }
    if (hasAdbDeviceError(result)) return RuntimeNavigation.unknown();
    throw new IllegalStateException("Android could not deliver the URI; check the app and its intent filter");
  }

  static JsonObject openIos(String platform, boolean simulator, String deviceId, String uri, String appId, Runnable beforeDispatch) throws IOException {
    if (!"ios".equals(platform)) throw new IllegalArgumentException("System navigation supports Android and iOS Simulator; use debug mode for this platform");
    if (!simulator) throw new IllegalArgumentException("Use debug mode on physical iOS devices; system URL delivery during an existing session is unverified");
    if (appId != null) throw new IllegalArgumentException("Omit appId for iOS Simulator; the simulator resolves the URI handler");
    if (!SystemInfo.isMac) throw new IllegalStateException("iOS Simulator requires macOS and Xcode");
    beforeDispatch.run();
    var result = run("simctl", List.of(XCRUN, "simctl", "openurl", deviceId, uri), 10_000);
    if (unconfirmed(result)) return RuntimeNavigation.unknown();
    if (succeeded(result)) return Json.obj("status", "accepted");
    throw new IllegalStateException("simctl could not open the URI; check the selected Xcode, the booted simulator and the registered URL scheme");
  }

  private static void checkAdbDevice(ProcessOutput result) {
    if (unconfirmed(result) || hasAdbDeviceError(result)) {
      throw new IllegalStateException("ADB could not inspect or contact the session device; check its connection and authorization");
    }
  }

  private static boolean hasAdbDeviceError(ProcessOutput result) {
    return result.getStderrLines(true).stream().anyMatch(line -> line.startsWith("adb:"));
  }

  private static boolean unconfirmed(ProcessOutput result) { return result.isTimeout() || result.isCancelled(); }

  private static boolean succeeded(ProcessOutput result) {
    return !unconfirmed(result) && result.isExitCodeSet() && result.getExitCode() == 0;
  }

  private static ProcessOutput run(String tool, List<String> command, int timeoutMs) throws IOException {
    Process process;
    try {
      var builder = new ProcessBuilder(command);
      if (tool.equals("simctl")) {
        String developerDir = EnvironmentUtil.getValue("DEVELOPER_DIR");
        if (developerDir != null && !developerDir.isBlank()) builder.environment().put("DEVELOPER_DIR", developerDir);
      }
      process = builder.start();
    } catch (IOException error) {
      throw new IOException("Could not start " + tool + "; check the Android SDK or selected Xcode");
    }
    try {
      return new CapturingProcessHandler(process, StandardCharsets.UTF_8, tool + " navigation").runProcess(timeoutMs);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}
