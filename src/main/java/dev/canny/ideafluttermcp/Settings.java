package dev.canny.ideafluttermcp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import java.util.*;

/** 应用级权限持久化；凭据不进入可同步设置。 */
@State(name = "IdeaFlutterMcp", storages = @Storage(value = "idea-flutter-mcp.xml", roamingType = RoamingType.DISABLED))
public final class Settings implements PersistentStateComponent<Settings.Data> {
  public static final Map<String, String> PERMISSIONS = new LinkedHashMap<>();
  static {
    PERMISSIONS.put("discovery", "发现项目、设备、DTD 和 VM 地址");
    PERMISSIONS.put("logs", "读取运行日志和运行时事件");
    PERMISSIONS.put("run", "启动、停止、重新运行、热重载、热重启");
    PERMISSIONS.put("inspect", "查询堆栈、变量、对象、内存和性能");
    PERMISSIONS.put("debug", "断点、暂停、继续、步进和异常暂停策略");
    PERMISSIONS.put("evaluate", "执行表达式、调用对象方法（可改变应用状态）");
    PERMISSIONS.put("extensions", "调用 Flutter 扩展（Widget 树、Inspector、性能开关等）");
    PERMISSIONS.put("devtools", "获取或启动 DevTools 服务");
  }
  public static final class Data {
    public boolean enabled = false;
    public int port = EndpointConfig.DEFAULT_PORT;
    public Map<String, Boolean> permissions = new LinkedHashMap<>();
    /** 分类权限默认全开；服务总开关默认关闭。 */
    public Data() { PERMISSIONS.keySet().forEach(key -> permissions.put(key, true)); }
  }
  private volatile Data data = new Data();
  /** 获取平台管理的设置实例。 */
  public static Settings getInstance() { return ApplicationManager.getApplication().getService(Settings.class); }
  /** 返回持久化状态。 */
  @Override public Data getState() { return data; }
  /** 替换完整权限快照。 */
  @Override public void loadState(Data state) { data = state; }
  /** 验证当前操作的分类权限。 */
  void check(String permission) {
    Data current = data;
    if (!current.enabled) throw new SecurityException("Flutter Debug MCP is disabled in Settings > Tools");
    if (!Boolean.TRUE.equals(current.permissions.get(permission))) throw new SecurityException("Permission denied: " + permission);
  }
}
