package dev.mcmy.ideafluttermcp;

import com.intellij.openapi.project.*;
import com.intellij.openapi.startup.StartupActivity;

/** 按用户保存的总开关启动应用服务。 */
public final class ProjectStartup implements StartupActivity, DumbAware {
  /** 默认关闭时不创建网络监听。 */
  @Override public void runActivity(Project project) { BridgeService.getInstance().start(); }
}
