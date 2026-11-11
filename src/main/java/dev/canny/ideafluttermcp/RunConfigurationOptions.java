package dev.canny.ideafluttermcp;

import com.google.gson.*;
import java.util.Set;

/** 验证局部配置更新；先完整验证再由 EDT 修改 IDE 模型。 */
final class RunConfigurationOptions {
  static final Set<String> TEXT = Set.of("name", "filePath", "additionalArgs", "attachArgs", "buildFlavor");

  static JsonObject merge(JsonObject current, JsonObject patch) {
    JsonObject result = current.deepCopy();
    for (var entry : patch.entrySet()) {
      String key = entry.getKey();
      if (TEXT.contains(key)) {
        String value = Json.string(patch, key, "");
        if ((key.equals("name") || key.equals("filePath")) && value.isBlank()) throw new IllegalArgumentException(key + " must not be blank");
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException(key + " contains NUL");
        result.addProperty(key, value);
      } else if (key.equals("envs")) {
        if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("envs must be an object of string values");
        for (var env : entry.getValue().getAsJsonObject().entrySet()) {
          if (env.getKey().isBlank() || env.getKey().contains("=") || env.getKey().indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid environment variable name");
          String value = Json.string(entry.getValue().getAsJsonObject(), env.getKey(), "");
          if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("Environment value contains NUL");
        }
        result.add(key, entry.getValue().deepCopy());
      } else throw new IllegalArgumentException("Unknown configuration option: " + key);
    }
    return result;
  }
}
