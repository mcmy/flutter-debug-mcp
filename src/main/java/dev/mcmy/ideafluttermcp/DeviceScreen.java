package dev.mcmy.ideafluttermcp;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class DeviceScreen {
  private static final Pattern DISPLAY_STATE = Pattern.compile(", state ([A-Z_]+)(?=[,}])");

  enum State {
    ON, OFF, DOZE, DOZE_SUSPEND, ON_SUSPEND, VR, UNKNOWN;

    boolean needsWake() { return this == OFF || this == DOZE || this == DOZE_SUSPEND; }
  }

  record Reading(State state, String reason, Instant observedAt) {
    JsonObject json() {
      return Json.obj("screenState", state.name().toLowerCase(Locale.ROOT),
          "screenOff", state == State.UNKNOWN ? null : state == State.OFF,
          "needsWake", state == State.UNKNOWN ? null : state.needsWake(),
          "reason", reason, "observedAt", observedAt.toString());
    }
  }

  static Reading read(Project project, String deviceId) {
    if (project.isDisposed()) return unknown("device_unavailable");
    try {
      return readAndroid(DeviceNavigation.findAdb(project), deviceId);
    } catch (IOException | RuntimeException ignored) {
      return unknown("adb_unavailable");
    }
  }

  static Reading readAndroid(String adb, String deviceId) throws IOException {
    var output = DeviceNavigation.run("adb", List.of(adb, "-s", deviceId, "shell", "dumpsys", "-t", "2", "display"), 3_000);
    if (!DeviceNavigation.succeeded(output)) return unknown("adb_query_failed");
    State state = parse(output.getStdout());
    return state == State.UNKNOWN ? unknown("display_state_unavailable") : new Reading(state, null, Instant.now());
  }

  private static Reading unknown(String reason) { return new Reading(State.UNKNOWN, reason, Instant.now()); }

  static State parse(String dump) {
    boolean defaultDisplay = false;
    for (String raw : dump.lines().toList()) {
      String line = raw.strip();
      if (line.startsWith("mDisplayId=")) defaultDisplay = line.equals("mDisplayId=0");
      if (defaultDisplay && line.startsWith("mBaseDisplayInfo=DisplayInfo{")) {
        var state = DISPLAY_STATE.matcher(line);
        if (state.find()) {
          try { return State.valueOf(state.group(1)); }
          catch (IllegalArgumentException ignored) { return State.UNKNOWN; }
        }
        return State.UNKNOWN;
      }
    }
    return State.UNKNOWN;
  }
}
