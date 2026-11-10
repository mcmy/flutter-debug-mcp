package dev.canny.ideafluttermcp;

import com.google.gson.*;

/** 统一协议对象和参数校验。 */
final class Json {
  static final Gson GSON = new GsonBuilder().serializeNulls().create();
  /** 将键值对转换为协议对象。 */
  static JsonObject obj(Object... pairs) {
    JsonObject out = new JsonObject();
    for (int i = 0; i < pairs.length; i += 2) out.add((String)pairs[i], GSON.toJsonTree(pairs[i + 1]));
    return out;
  }
  /** 读取必填非空字符串。 */
  static String required(JsonObject args, String key) {
    String value = string(args, key, "");
    if (value.isBlank()) throw new IllegalArgumentException("Missing " + key);
    return value;
  }
  /** 读取可选字符串，拒绝隐式类型转换。 */
  static String string(JsonObject args, String key, String fallback) {
    if (!args.has(key)) return fallback;
    JsonElement value = args.get(key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be a string");
    return value.getAsString();
  }
  /** 验证整数参数及上下界。 */
  static int integer(JsonObject args, String key, int fallback, int min, int max) {
    if (!args.has(key)) {
      if (fallback < min || fallback > max) throw new IllegalArgumentException("Missing " + key);
      return fallback;
    }
    try {
      JsonElement value = args.get(key);
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
      int result = value.getAsBigDecimal().intValueExact();
      if (result < min || result > max) throw new ArithmeticException();
      return result;
    } catch (ArithmeticException | NumberFormatException error) {
      throw new IllegalArgumentException(key + " must be an integer in " + min + ".." + max);
    }
  }
  /** 读取可选布尔参数。 */
  static boolean bool(JsonObject args, String key, boolean fallback) {
    if (!args.has(key)) return fallback;
    JsonElement value = args.get(key);
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be boolean");
    return value.getAsBoolean();
  }
}
