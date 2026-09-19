package io.github.capricornus007.mapadkiller;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;

/**
 * 设置页：Material 3 手风琴分组（参考 XposedSmsCode 的「設定」页）。
 *  顶部：主開關（整体启停 UI 自定义）+ 手風琴模式（分组可折叠 / 全部直接展开）。
 *  下面每个分区是一个可折叠卡片：点标题展开/收起；关掉手风琴模式则全部平铺。
 * 配置写入 LSPosed RemotePreferences（App 侧经 libxposed/service，Hook 侧经
 *  XposedModule#getRemotePreferences，同名 group 双侧共享）。折叠状态是本机 UI 偏好，
 *  存普通 SharedPreferences 即可，不进 RemotePreferences。
 */
public final class MainActivity extends Activity {

    private LinearLayout currentCard;
    private TextView statusView;
    /** 「已捕获广告 SDK」那一行；服务绑定后要重刷文案，否则一直显示 onCreate 时的空快照 */
    private TextView sdkRow;

    private boolean accordion;                                  // 当前是否手风琴模式
    private final LinkedHashMap<String, View[]> sections = new LinkedHashMap<>();  // id -> {header, chevron, body}

    // 配色：跟随系统深浅色，onCreate 里按 night 选一套（原来写死浅色，深色系统下刺眼）。
    private boolean night;
    private int BG_PAGE, BG_CARD, TX_PRIMARY, TX_SECONDARY, TX_ACCENT, DIVIDER, SW_ON;
    private static final int CARD_RADIUS = 14;

    private void applyPalette() {
        if (night) {
            BG_PAGE     = 0xFF10131A;
            BG_CARD     = 0xFF1B1F28;
            TX_PRIMARY  = 0xFFE6E8EC;
            TX_SECONDARY= 0xFF9AA0AA;
            TX_ACCENT   = 0xFF7FB0FF;
            DIVIDER     = 0xFF2A2F3A;
        } else {
            BG_PAGE     = 0xFFF2F3F7;
            BG_CARD     = 0xFFFFFFFF;
            TX_PRIMARY  = 0xFF1A1C1E;
            TX_SECONDARY= 0xFF7A7E85;
            TX_ACCENT   = 0xFF0A6CF5;
            DIVIDER     = 0xFFECEEF1;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        applyPalette();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG_PAGE);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(12), p, dp(28));
        scroll.addView(root);
        setContentView(scroll);

        // ---- 标题区 ----
        TextView title = text("MapAdKiller", 22, Typeface.BOLD, TX_PRIMARY);
        title.setPadding(dp(4), dp(14), 0, dp(2));
        root.addView(title);

        statusView = text("", 13, Typeface.NORMAL, TX_SECONDARY);
        statusView.setPadding(dp(4), 0, 0, dp(10));
        root.addView(statusView);
        renderStatus();

        // ---- 顶部：主開關 + 手風琴模式 ----
        accordion = readUi("accordion_mode", true);
        beginCard();
        addSwitch("主開關（模块 UI 自定义总开关）", Config.K_MASTER, new OnToggle() {
            @Override public void changed(boolean value) {
                Toast.makeText(MainActivity.this,
                        value ? "已开启：强停地图后重新打开生效"
                              : "已关闭：强停地图后重新打开，恢复地图原生界面",
                        Toast.LENGTH_LONG).show();
            }
        });
        addSwitch("手風琴模式（分组可折叠）", null, new OnToggle() {
            @Override public void changed(boolean value) {
                accordion = value;
                writeUi("accordion_mode", value);
                applyAllSections();
            }
        }, accordion);   // 初始态用手风琴模式当前值
        endCard(root);

        // ---- 去广告 ----
        beginSection(root, "去广告 · 高德 / 百度 / 腾讯", "ads");
        addNoteRow("开屏 / 横幅 / 推送 / 信息流广告卡拦截，覆盖高德、百度、腾讯三家地图（始终开启，不受主开关影响）");
        addNoteRow("广告 SDK 自动检索：打开地图时模块会在其进程内扫描 dex，命中广告特征的厂商包"
                + "会自动记下来并拦截，记录长期保存，下次启动直接生效。");
        sdkRow = addActionButton(sdkSummary(), new Runnable() {
            @Override public void run() { showLearnedSdks(); }
        });
        endSection(root);

        // ---- 高德 · 主页标签栏 ----
        beginSection(root, "高德 · 主页标签栏", "amap_tab");
        for (String tab : Config.TABS) addSwitch("显示标签「" + tab + "」", Config.K_TAB_PREFIX + tab);
        endSection(root);

        // ---- 高德 · 首页工具 ----
        beginSection(root, "高德 · 首页工具宫格", "amap_tool");
        for (String tool : Config.TOOLS) addSwitch("显示「" + tool + "」", Config.K_TOOL_PREFIX + tool);
        addSwitch("显示扩展工具页（景点游玩 / 离线地图 / 通行费助手 / 旅游度假）", Config.K_TOOL_EXTRA);
        addSwitch("显示「收藏夹」", Config.K_TOOL_FAVORITE);
        endSection(root);

        // ---- 高德 · 首页推荐内容 ----
        beginSection(root, "高德 · 首页推荐内容", "amap_feed");
        addSwitch("天气卡片", Config.K_FEED_WEATHER);
        addSwitch("周边景区 / 景点推荐", Config.K_FEED_SCENIC);
        addSwitch("榜单帖子卡（景区榜 / 美食榜 / 打卡地…）", Config.K_FEED_POSTS);
        addSwitch("带距离的内容卡（旅行帖 / 探店帖…）", Config.K_FEED_DISTANCE);
        addSwitch("精选榜单 / 热门榜", Config.K_FEED_RANK);
        addSwitch("攻略 / 内容流卡片", Config.K_FEED_CONTENT);
        addSwitch("问问 AI 入口", Config.K_FEED_AI);
        addSwitch("推荐频道栏（关注 / 附近 / 美食…）", Config.K_FEED_FILTER);
        addSwitch("设置家 / 设置单位 / 常去地点", Config.K_HOME_CHIPS);
        addSwitch("智能出行推广卡（去XX / 帮我预约车辆 / AI叫车）", Config.K_QUICK_CARD);
        endSection(root);

        // ---- 高德 · 搜索页 ----
        beginSection(root, "高德 · 搜索页", "amap_search");
        addSwitch("显示搜索页「美食 / 酒店 / 加油站 / 休闲玩乐 / 扫街榜」那一排", Config.K_SEARCH_CATS);
        endSection(root);

        // ---- 高德 · 「我的」页 ----
        beginSection(root, "高德 · 「我的」页", "amap_my");
        addSwitch("订单 / 收藏 / 待评价 一栏", Config.K_MY_ORDER_ROW);
        addSwitch("车辆服务 / 高德运动 一栏", Config.K_MY_SERVICE_ROW);
        addSwitch("达人任务卡片", Config.K_MY_TASK);
        addSwitch("扫街新发现 / 小德果园 一栏", Config.K_MY_PROMO_ROW);
        addSwitch("猜你喜欢", Config.K_MY_GUESS);
        addSwitch("资质信息 / 协议中心", Config.K_MY_QUALITY);
        endSection(root);

        // ---- 腾讯 · 首页（键名带 tmap_ 前缀，与高德/百度各自独立）----
        beginSection(root, "腾讯 · 首页", "tmap");
        for (String tab : Config.TMAP_TABS)
            addSwitch("显示底部标签「" + tab + "」", Config.K_TMAP_TAB_PREFIX + tab);
        addSwitch("「大家都在看」推荐流", Config.K_TMAP_FEED_HOT);
        endSection(root);

        // ---- 其他 ----
        beginSection(root, "其他", "misc");
        addSwitch("调试日志（logcat 输出首页文本锚点）", Config.K_DEBUG_LOG);
        addSwitch("隐藏桌面图标（靠常驻通知回到本页）", Config.K_HIDE_ICON, new OnToggle() {
            @Override public void changed(boolean value) {
                if (value) {
                    App.ensureNotificationPermission(MainActivity.this);
                    Toast.makeText(MainActivity.this,
                            "图标已隐藏 · 从通知栏「MapAdKiller 正在运行」可以回到这里",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "桌面图标已恢复", Toast.LENGTH_SHORT).show();
                }
                App.applyIconVisibility(MainActivity.this, value, true);
            }
        });
        addActionButton("恢复默认（全部显示）", new Runnable() {
            @Override public void run() {
                if (App.clearAll()) {
                    App.applyIconVisibility(MainActivity.this, false, true);
                    Toast.makeText(MainActivity.this, "已恢复默认，请强停地图 App 生效", Toast.LENGTH_LONG).show();
                    recreate();
                } else {
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            }
        });
        endSection(root);

        TextView tip = text("改动后请强停对应地图 App 并重新打开以生效", 12, Typeface.NORMAL, TX_SECONDARY);
        tip.setPadding(dp(4), dp(14), 0, 0);
        root.addView(tip);

        applyAllSections();   // 按当前模式 + 折叠状态刷新所有分区
    }

    // ══════════════════════════════════════════════════════════ 手风琴分区

    /** 开始一个可折叠分区：加一个可点标题 + 一个卡片正文（正文由后续 addSwitch 填、endSection 收尾）。 */
    private void beginSection(LinearLayout root, String title, String id) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), dp(16), dp(6), dp(8));
        TextView h = text(title, 15, Typeface.BOLD, TX_ACCENT);
        header.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = text("▾", 16, Typeface.NORMAL, TX_SECONDARY);
        header.addView(chevron, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        beginCard();
        final LinearLayout body = currentCard;
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!accordion) return;
                boolean now = !expanded(id);
                writeUi("exp_" + id, now);
                applySection(id);
            }
        });
        sections.put(id, new View[]{header, chevron, body});
    }

    private void endSection(LinearLayout root) { endCard(root); }

    private boolean expanded(String id) { return readUi("exp_" + id, true); }

    private void applySection(String id) {
        View[] s = sections.get(id);
        if (s == null) return;
        TextView chevron = (TextView) s[1];
        View body = s[2];
        if (!accordion) {
            body.setVisibility(View.VISIBLE);
            chevron.setText("");
            return;
        }
        boolean exp = expanded(id);
        body.setVisibility(exp ? View.VISIBLE : View.GONE);
        chevron.setText(exp ? "▾" : "▸");
    }

    private void applyAllSections() {
        for (String id : sections.keySet()) applySection(id);
    }

    // 折叠/模式这类纯 UI 偏好放本机 SharedPreferences，不占用与 Hook 侧共享的 RemotePreferences。
    private android.content.SharedPreferences uiPrefs() {
        return getSharedPreferences("ui_state", MODE_PRIVATE);
    }
    private boolean readUi(String key, boolean def) {
        try { return uiPrefs().getBoolean(key, def); } catch (Throwable t) { return def; }
    }
    private void writeUi(String key, boolean value) {
        try { uiPrefs().edit().putBoolean(key, value).apply(); } catch (Throwable ignored) {}
    }

    // ══════════════════════════════════════════════════════════ 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        synced = false;
        try { LearnedProvider.flushToRemote(this); } catch (Throwable ignored) {}
        try { App.syncIconFromConfig(this); } catch (Throwable ignored) {}
        refreshSdkRow();
        statusRefresher.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == App.REQ_NOTIFY && readState(Config.K_HIDE_ICON)) {
            App.applyIconVisibility(this, true, true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusRefresher);
    }

    private static final int DOT_OK = 0xFF12B76A;
    private static final int DOT_BAD = 0xFFE5484D;

    private void renderStatus() {
        if (statusView == null) return;
        io.github.libxposed.service.XposedService s = App.svc();
        boolean active = s != null;
        String[] targets = {MainHook.PKG_AMAP, MainHook.PKG_BMAP, MainHook.PKG_TMAP};
        String[] labels = {"高德", "百度", "腾讯"};
        int scoped = 0;
        StringBuilder picked = new StringBuilder();
        try {
            java.util.List<String> scope = active ? s.getScope() : null;
            if (scope != null) {
                for (int i = 0; i < targets.length; i++) {
                    if (scope.contains(targets[i])) {
                        scoped++;
                        if (picked.length() > 0) picked.append(" / ");
                        picked.append(labels[i]);
                    }
                }
            }
        } catch (Throwable ignored) {}
        boolean scopeOk = scoped > 0;
        String l1 = active
                ? "已激活 · " + s.getFrameworkName() + " " + s.getFrameworkVersion()
                : "未激活 · 请在 LSPosed 中启用本模块";
        String l2;
        if (!active) l2 = "作用域未知 · 正在等待 LSPosed 服务…";
        else if (scopeOk) l2 = "作用域已勾选 · " + picked + "（" + scoped + "/3）";
        else l2 = "作用域未勾选 · 请在 LSPosed 里勾选地图应用";
        String plain = "● " + l1 + "\n● " + l2;
        android.text.SpannableString ss = new android.text.SpannableString(plain);
        int second = plain.indexOf('\n') + 1;
        ss.setSpan(new android.text.style.ForegroundColorSpan(active ? DOT_OK : DOT_BAD),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.ForegroundColorSpan(scopeOk ? DOT_OK : DOT_BAD),
                second, second + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        statusView.setText(ss);
    }

    private final android.os.Handler statusHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable statusRefresher = new Runnable() {
        @Override public void run() {
            renderStatus();
            if (!synced && App.svc() != null) {
                synced = true;
                syncSwitches();
            }
            statusHandler.postDelayed(this, 800);
        }
    };

    private final LinkedHashMap<String, Switch> switches = new LinkedHashMap<>();
    private volatile boolean synced;

    private void syncSwitches() {
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            if (s == null) return;
            android.content.SharedPreferences p = s.getRemotePreferences(Config.PREF_GROUP);
            for (java.util.Map.Entry<String, Switch> e : switches.entrySet()) {
                Switch sw = e.getValue();
                sw.setChecked(p.getBoolean(e.getKey(), Config.defaultVisible(e.getKey())));
                sw.setEnabled(true);
                View row = (View) sw.getParent();
                if (row != null) row.setEnabled(true);
            }
            refreshSdkRow();
        } catch (Throwable ignored) {}
    }

    // ══════════════════════════════════════════════════════════ UI 工厂

    private TextView text(String s, int sp, int style, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT_BOLD, style == Typeface.BOLD ? Typeface.BOLD : Typeface.NORMAL);
        t.setTextColor(color);
        return t;
    }

    private void beginCard() {
        currentCard = new LinearLayout(this);
        currentCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_CARD);
        bg.setCornerRadius(dp(CARD_RADIUS));
        currentCard.setBackground(bg);
        currentCard.setPadding(dp(14), 0, dp(14), 0);
    }

    private void endCard(LinearLayout root) {
        root.addView(currentCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        currentCard = null;
    }

    private void refreshSdkRow() {
        try { if (sdkRow != null) sdkRow.setText(sdkSummary()); } catch (Throwable ignored) {}
    }

    private String sdkSummary() {
        int n = 0;
        try { n = SdkAutoBlock.learnedListForApp().size(); } catch (Throwable ignored) {}
        return "已捕获广告 SDK：" + n + " 个 · 点按查看清单";
    }

    private void showLearnedSdks() {
        java.util.List<String> list;
        try { list = SdkAutoBlock.learnedListForApp(); } catch (Throwable t) { list = null; }
        if (list == null || list.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("已捕获广告 SDK")
                    .setMessage("还没有捕获记录。\n\n模块会扫描三家地图 App 自身 dex 里带广告特征的类，"
                            + "把厂商包名记下来并拦截；记录跨进程保存，下次启动直接生效。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append('\n');
        new android.app.AlertDialog.Builder(this)
                .setTitle("已捕获广告 SDK（" + list.size() + "）")
                .setMessage(sb.toString().trim())
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空重新学习", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try { SdkAutoBlock.clearLearned(); } catch (Throwable ignored) {}
                        Toast.makeText(MainActivity.this, "已清空，强停对应地图后重新打开即重新学习",
                                Toast.LENGTH_LONG).show();
                        recreate();
                    }
                })
                .show();
    }

    private interface OnToggle { void changed(boolean value); }

    private void addSwitch(String title, String key) { addSwitch(title, key, null); }
    private void addSwitch(String title, String key, OnToggle onToggle) { addSwitch(title, key, onToggle, null); }

    /**
     * 统一规格开关行。key 为 null 时是「纯 UI 开关」（如手风琴模式），初值由 initOverride 给、
     * 不经 RemotePreferences；否则读写远端配置、默认值走 Config.defaultVisible。
     */
    private void addSwitch(String title, final String key, final OnToggle onToggle, final Boolean initOverride) {
        if (currentCard.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(DIVIDER);
            currentCard.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));

        TextView label = text(title, 15, Typeface.NORMAL, TX_PRIMARY);
        label.setPadding(0, dp(12), dp(8), dp(12));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final boolean uiOnly = (key == null);
        final boolean initChecked = uiOnly
                ? (initOverride != null && initOverride)
                : readState(key);
        final Switch sw = new Switch(this);
        sw.setChecked(initChecked);
        sw.setEnabled(uiOnly || App.svc() != null);
        sw.setClickable(false);
        if (!uiOnly) switches.put(key, sw);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean next = !sw.isChecked();
                boolean ok = uiOnly || App.writeBoolean(key, next);
                if (ok) {
                    sw.setChecked(next);
                    if (onToggle != null) { try { onToggle.changed(next); } catch (Throwable ignored) {} }
                } else {
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            }
        });
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) { /* 由行点击驱动 */ }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        currentCard.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private boolean readState(String key) {
        boolean def = Config.defaultVisible(key);
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            if (s != null) return s.getRemotePreferences(Config.PREF_GROUP).getBoolean(key, def);
        } catch (Throwable ignored) {}
        return def;
    }

    private void addDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER);
        currentCard.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
    }

    private TextView addActionButton(String title, final Runnable action) {
        addDivider();
        TextView label = text(title, 15, Typeface.NORMAL, TX_ACCENT);
        label.setPadding(0, dp(12), dp(8), dp(12));
        label.setGravity(Gravity.CENTER);
        label.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        currentCard.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return label;
    }

    private void addNoteRow(String s) {
        TextView t = text(s, 13, Typeface.NORMAL, TX_SECONDARY);
        t.setPadding(0, dp(12), 0, dp(12));
        t.setLineSpacing(0, 1.15f);
        currentCard.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
