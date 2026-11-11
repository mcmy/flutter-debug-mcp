package dev.mcmy.ideafluttermcp;

import com.intellij.openapi.options.*;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.google.gson.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.RoundedLineBorder;
import com.intellij.ui.popup.list.SelectablePanel;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.util.*;

/** 原生权限、连接配置、端口编辑与审计查询页。 */
public final class SettingsPage implements Configurable {
  private JPanel panel;
  private JBCheckBox enabled;
  private final Map<String, JBCheckBox> permissions = new LinkedHashMap<>();
  private JBTextField port;
  private JBTextField url;
  private JBTextArea config;
  private JComboBox<String> format;
  private JBLabel status;
  private JBTextField filter;
  private DefaultTableModel model;
  private String secret;
  private JButton copyUrl;
  private JButton copyConfig;
  /** 返回设置树标题。 */
  @Override public String getDisplayName() { return "Flutter Debug MCP"; }
  /** 使用平台表单和滚动组件适配IDE主题与窗口缩放。 */
  @Override public JComponent createComponent() {
    enabled = new JBCheckBox("启用 Flutter Debug MCP");
    port = new JBTextField(9);
    url = new JBTextField(24);
    url.setMinimumSize(new Dimension(0, url.getPreferredSize().height));
    url.setEditable(false);
    status = new JBLabel();
    status.setFontColor(UIUtil.FontColor.BRIGHTER);
    config = new JBTextArea(8, 32);
    config.setLineWrap(true);
    config.setWrapStyleWord(false);
    config.setEditable(false);
    config.setBorder(JBUI.Borders.empty());
    config.setOpaque(false);
    format = new JComboBox<>(new String[]{"Codex TOML", "MCP JSON"});
    copyUrl = new ActionLink("复制 URL");
    ((ActionLink)copyUrl).setAutoHideOnDisable(false);
    copyConfig = new ActionLink("复制配置");
    ((ActionLink)copyConfig).setAutoHideOnDisable(false);
    copyUrl.addActionListener(event -> CopyPasteManager.getInstance().setContents(new StringSelection(url.getText())));
    copyConfig.addActionListener(event -> CopyPasteManager.getInstance().setContents(new StringSelection(config.getText())));
    JPanel address = new JPanel(new BorderLayout(JBUI.scale(12), 0));
    address.add(url, BorderLayout.CENTER);
    address.add(copyUrl, BorderLayout.EAST);
    JPanel configToolbar = new JPanel(new BorderLayout());
    JPanel formatSelector = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    formatSelector.add(format);
    configToolbar.add(formatSelector, BorderLayout.CENTER);
    configToolbar.add(copyConfig, BorderLayout.EAST);
    JPanel connection = new JPanel(new BorderLayout(0, JBUI.scale(12)));
    connection.add(configToolbar, BorderLayout.NORTH);
    JBScrollPane preview = borderlessScroll(config);
    preview.setMinimumSize(JBUI.size(0, 120));
    preview.setOpaque(false);
    preview.getViewport().setOpaque(false);
    SelectablePanel previewBackground = SelectablePanel.wrap(preview, UIUtil.getPanelBackground());
    previewBackground.setSelectionColor(UIUtil.getTextFieldBackground());
    previewBackground.setSelectionArc(JBUI.scale(12));
    previewBackground.setBorder(JBUI.Borders.compound(
        new RoundedLineBorder(null, JBUI.scale(12), JBUI.scale(1)),
        JBUI.Borders.empty(11)));
    connection.add(previewBackground, BorderLayout.CENTER);
    JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    portRow.add(port);
    JBLabel portHint = new JBLabel("1024–65535", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
    portHint.setBorder(JBUI.Borders.emptyLeft(12));
    portRow.add(portHint);
    FormBuilder builder = FormBuilder.createFormBuilder().setVerticalGap(8)
        .addComponent(new TitledSeparator("服务"))
        .addComponent(enabled)
        .addLabeledComponent("监听端口", portRow, 12, true)
        .addComponent(status)
        .addComponent(note("修改端口后点击“应用”生效。"))
        .addVerticalGap(20)
        .addComponent(new TitledSeparator("客户端连接"))
        .addComponent(new JBLabel("Streamable HTTP URL"), 12)
        .addComponent(address)
        .addComponent(note("URL 包含访问令牌，仅供本机 MCP 客户端使用。"))
        .addComponent(connection, 16);
    FormBuilder permissionBuilder = FormBuilder.createFormBuilder().setVerticalGap(12)
        .addComponent(new TitledSeparator("工具权限"))
        .addComponent(note("所有工具始终可见。关闭权限后，调用时会提示所需授权。"), 8)
        .addVerticalGap(8);
    Settings.PERMISSIONS.forEach((key, label) -> {
      JBCheckBox box = new JBCheckBox(label);
      permissions.put(key, box);
      permissionBuilder.addComponent(box);
    });
    permissionBuilder.addComponent(note("权限在点击“应用”后生效，已执行的操作不会撤销。"), 20);
    JPanel audit = new JPanel(new BorderLayout(0, JBUI.scale(16)));
    audit.setBorder(JBUI.Borders.empty(16, 20, 20, 24));
    filter = new JBTextField();
    filter.getEmptyText().setText("按工具名、状态或时间搜索操作记录");
    JButton refresh = new ActionLink("刷新");
    refresh.addActionListener(event -> { refreshAudit(); refreshConnection(); });
    filter.addActionListener(event -> refreshAudit());
    JPanel toolbar = new JPanel(new BorderLayout(JBUI.scale(12), 0));
    toolbar.add(filter, BorderLayout.CENTER);
    toolbar.add(refresh, BorderLayout.EAST);
    JPanel auditHeader = new JPanel(new BorderLayout(0, JBUI.scale(12)));
    auditHeader.add(new TitledSeparator("操作日志"), BorderLayout.NORTH);
    auditHeader.add(toolbar, BorderLayout.CENTER);
    audit.add(auditHeader, BorderLayout.NORTH);
    model = new DefaultTableModel(new String[]{"时间", "操作", "结果", "耗时(ms)"}, 0) {
      /** 审计记录仅供查询。 */
      @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    JBTable table = new JBTable(model);
    table.setShowGrid(false);
    table.setRowHeight(JBUI.scale(28));
    table.setFillsViewportHeight(true);
    table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(220));
    table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(180));
    audit.add(borderlessScroll(table), BorderLayout.CENTER);
    CardLayout cards = new CardLayout();
    JPanel pages = new JPanel(cards);
    pages.add(borderlessScroll(new FormPanel(builder.getPanel())), "配置");
    pages.add(borderlessScroll(new FormPanel(permissionBuilder.getPanel())), "权限");
    pages.add(audit, "日志");
    JBList<String> menu = new JBList<>("配置", "权限", "日志");
    menu.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    menu.setFixedCellWidth(JBUI.scale(140));
    menu.setFixedCellHeight(JBUI.scale(28));
    menu.setCellRenderer(new DefaultListCellRenderer() {
      /** 使用平台圆角选中背景并保留菜单文字的主题颜色和留白。 */
      @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
        JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, selected, focused);
        label.setBorder(JBUI.Borders.empty(4, 12));
        label.setOpaque(false);
        SelectablePanel row = SelectablePanel.wrap(label, list.getBackground());
        row.setSelectionArc(JBUI.scale(12));
        row.setSelectionInsets(JBUI.insets(2, 0));
        row.setSelectionColor(selected ? list.getSelectionBackground() : null);
        row.setSize(list.getWidth(), list.getFixedCellHeight());
        row.doLayout();
        return row;
      }
    });
    menu.addListSelectionListener(event -> {
      if (event.getValueIsAdjusting() || menu.getSelectedValue() == null) return;
      String selected = menu.getSelectedValue();
      cards.show(pages, selected);
      if (selected.equals("日志")) refreshAudit();
      else if (selected.equals("配置")) refreshConnection();
    });
    menu.setSelectedIndex(0);
    panel = new JPanel(new BorderLayout());
    JPanel navigation = new JPanel(new BorderLayout());
    navigation.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineRight(JBColor.border()), JBUI.Borders.empty(16, 8, 0, 8)));
    navigation.add(borderlessScroll(menu), BorderLayout.CENTER);
    panel.add(navigation, BorderLayout.WEST);
    panel.add(pages, BorderLayout.CENTER);
    panel.setPreferredSize(JBUI.size(680, 520));
    try { secret = BridgeService.getInstance().endpointSecret(); }
    catch (Exception error) { status.setText("无法读取连接凭据：" + error.getClass().getSimpleName()); }
    port.getDocument().addDocumentListener(new DocumentListener() {
      /** 输入变化时更新配置预览。 */
      @Override public void insertUpdate(DocumentEvent event) { refreshConnection(); }
      /** 删除输入时更新配置预览。 */
      @Override public void removeUpdate(DocumentEvent event) { refreshConnection(); }
      /** 文档属性变化时更新配置预览。 */
      @Override public void changedUpdate(DocumentEvent event) { refreshConnection(); }
    });
    format.addActionListener(event -> refreshConnection());
    reset();
    refreshAudit();
    return panel;
  }
  /** 滚动容器不绘制外围框线，仅在内容超出时显示滚动条。 */
  private static JBScrollPane borderlessScroll(java.awt.Component view) {
    JBScrollPane scroll = new JBScrollPane(view);
    scroll.setBorder(JBUI.Borders.empty());
    scroll.setViewportBorder(JBUI.Borders.empty());
    return scroll;
  }
  /** 提示文字随页面宽度换行并使用当前主题字体和背景。 */
  private static JComponent note(String text) {
    JBTextArea area = new JBTextArea(text);
    area.setEditable(false);
    area.setFocusable(false);
    area.setOpaque(false);
    area.setFont(UIUtil.getLabelFont(UIUtil.FontSize.SMALL));
    area.setForeground(UIUtil.getContextHelpForeground());
    area.setBorder(JBUI.Borders.empty());
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    return area;
  }
  /** 表单始终跟随视口宽度，长URL只在输入框内滚动。 */
  private static final class FormPanel extends JPanel implements Scrollable {
    /** 在顶部排列设置表单，保留页面剩余空间。 */
    FormPanel(JPanel form) {
      super(new BorderLayout());
      setBorder(JBUI.Borders.empty(16, 20, 20, 24));
      add(form, BorderLayout.NORTH);
    }
    /** 提供适中的初始视口尺寸。 */
    @Override public Dimension getPreferredScrollableViewportSize() { return JBUI.size(480, 400); }
    /** 使用与IDE缩放一致的滚动步长。 */
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return JBUI.scale(16); }
    /** 每次翻页保留少量上下文。 */
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(JBUI.scale(16), visible.height - JBUI.scale(16)); }
    /** 限制表单在视口内布局，避免复制按钮移出窗口。 */
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    /** 内容超过页面高度时允许纵向滚动。 */
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
  }
  /** 同步URL和客户端配置预览，不启动服务器。 */
  private void refreshConnection() {
    if (secret == null) { copyUrl.setEnabled(false); copyConfig.setEnabled(false); return; }
    try {
      int value = EndpointConfig.port(Integer.parseInt(port.getText().trim()));
      url.setText(EndpointConfig.url(value, secret));
      url.setCaretPosition(0);
      config.setText(format.getSelectedIndex() == 0 ? EndpointConfig.toml(value, secret) : EndpointConfig.json(value, secret));
      config.setCaretPosition(0);
      copyUrl.setEnabled(true);
      copyConfig.setEnabled(true);
      status.setText(BridgeService.getInstance().serverStatus() + (value == Settings.getInstance().getState().port ? "" : "；端口更改尚未保存"));
    } catch (IllegalArgumentException error) {
      url.setText(""); config.setText(""); copyUrl.setEnabled(false); copyConfig.setEnabled(false);
      status.setText("请输入 1024–65535 的端口，Apply 后生效");
    }
  }
  /** 将审计快照填充到只读表格。 */
  private void refreshAudit() {
    model.setRowCount(0);
    JsonArray records;
    try { records = BridgeService.getInstance().audit(filter.getText(), 2000); }
    catch (IllegalStateException error) { model.addRow(new Object[]{"", "audit", error.getMessage(), ""}); return; }
    for (JsonElement item : records) {
      JsonObject row = item.getAsJsonObject();
      model.addRow(new Object[]{row.get("time").getAsString(), row.get("tool").getAsString(), row.get("status").getAsString(), row.get("durationMs").getAsLong()});
    }
  }
  /** 比较当前表单、端口和持久化权限。 */
  @Override public boolean isModified() {
    Settings.Data data = Settings.getInstance().getState();
    return enabled.isSelected() != data.enabled || !port.getText().trim().equals(Integer.toString(data.port))
        || permissions.entrySet().stream().anyMatch(entry -> entry.getValue().isSelected() != Boolean.TRUE.equals(data.permissions.get(entry.getKey())));
  }
  /** 保存权限和端口，启动失败时恢复先前配置与监听。 */
  @Override public void apply() throws ConfigurationException {
    Settings.Data previous = Settings.getInstance().getState();
    Settings.Data data = new Settings.Data();
    try { data.port = EndpointConfig.port(Integer.parseInt(port.getText().trim())); }
    catch (IllegalArgumentException error) { throw new ConfigurationException("端口必须在 1024–65535 之间"); }
    data.enabled = enabled.isSelected();
    permissions.forEach((key, box) -> data.permissions.put(key, box.isSelected()));
    Settings.getInstance().loadState(data);
    try { BridgeService.getInstance().permissionsChanged(); }
    catch (IllegalStateException error) {
      Settings.getInstance().loadState(previous);
      try { BridgeService.getInstance().permissionsChanged(); }
      catch (IllegalStateException restoreError) { error.addSuppressed(restoreError); }
      refreshConnection();
      throw new ConfigurationException(error.getMessage());
    }
    refreshConnection();
  }
  /** 还原未保存的权限与端口修改。 */
  @Override public void reset() {
    Settings.Data data = Settings.getInstance().getState();
    enabled.setSelected(data.enabled);
    permissions.forEach((key, box) -> box.setSelected(Boolean.TRUE.equals(data.permissions.get(key))));
    port.setText(Integer.toString(data.port));
    port.setCaretPosition(0);
    refreshConnection();
  }
  /** 释放设置页组件及URL凭据引用。 */
  @Override public void disposeUIResources() {
    panel = null; enabled = null; port = null; url = null; config = null; format = null;
    status = null; filter = null; model = null; secret = null; copyUrl = null; copyConfig = null; permissions.clear();
  }
}
