package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.graphics.Color;
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

/**
 * 设置页：分类卡片 + 统一规格开关行（52dp 行高、右侧 Switch、行间细分隔线）。
 * 配置写入 LSPosed RemotePreferences（App 侧经 libxposed/service，
 * Hook 侧经 XposedModule#getRemotePreferences，同名 group 双侧共享）。
 */
public final class MainActivity extends Activity {

    private LinearLayout currentCard;
    private TextView statusView;

    private static final int BG_PAGE = 0xFFF2F3F7;
    private static final int BG_CARD = 0xFFFFFFFF;
    private static final int TX_PRIMARY = 0xFF1A1C1E;
    private static final int TX_SECONDARY = 0xFF7A7E85;
    private static final int TX_ACCENT = 0xFF0A6CF5;
    private static final int DIVIDER = 0xFFECEEF1;
    private static final int CARD_RADIUS = 14;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        TextView title = text("高德增强", 22, Typeface.BOLD, TX_PRIMARY);
        title.setPadding(dp(4), dp(14), 0, dp(2));
        root.addView(title);

        statusView = text(statusText(), 13, Typeface.NORMAL, TX_SECONDARY);
        statusView.setPadding(dp(4), 0, 0, dp(10));
        root.addView(statusView);

        // ---- 去广告 ----
        root.addView(sectionHeader("去广告"));
        beginCard();
        addNoteRow("开屏 / 横幅 / 推送 / 信息流广告拦截（始终开启，无需配置）");
        endCard(root);

        // ---- 主页标签栏 ----
        root.addView(sectionHeader("主页标签栏"));
        beginCard();
        for (String tab : Config.TABS) addSwitch("显示标签「" + tab + "」", Config.K_TAB_PREFIX + tab);
        endCard(root);

        // ---- 首页工具 ----
        root.addView(sectionHeader("首页工具宫格"));
        beginCard();
        for (String tool : Config.TOOLS) addSwitch("显示「" + tool + "」", Config.K_TOOL_PREFIX + tool);
        addSwitch("显示扩展工具页（景点游玩 / 离线地图 / 通行费助手 / 收藏夹 / 旅游度假）",
                Config.K_TOOL_EXTRA);
        endCard(root);

        // ---- 首页推荐内容 ----
        root.addView(sectionHeader("首页推荐内容"));
        beginCard();
        addSwitch("天气卡片", Config.K_FEED_WEATHER);
        addSwitch("周边景区 / 景点推荐", Config.K_FEED_SCENIC);
        addSwitch("榜单帖子卡（景区榜 / 美食榜 / 打卡地…）", Config.K_FEED_POSTS);
        addSwitch("带距离的内容卡（旅行帖 / 探店帖…）", Config.K_FEED_DISTANCE);
        addSwitch("精选榜单 / 热门榜", Config.K_FEED_RANK);
        addSwitch("攻略 / 内容流卡片", Config.K_FEED_CONTENT);
        addSwitch("问问 AI 入口", Config.K_FEED_AI);
        addSwitch("推荐频道栏（关注 / 附近 / 美食…）", Config.K_FEED_FILTER);
        addSwitch("设置家 / 设置单位 / 常去地点", Config.K_HOME_CHIPS);
        endCard(root);

        // ---- 「我的」页 ----
        root.addView(sectionHeader("「我的」页"));
        beginCard();
        addSwitch("订单 / 收藏 / 待评价 一栏", Config.K_MY_ORDER_ROW);
        addSwitch("车辆服务 / 高德运动 一栏", Config.K_MY_SERVICE_ROW);
        addSwitch("达人任务卡片", Config.K_MY_TASK);
        addSwitch("扫街新发现 / 小德果园 一栏", Config.K_MY_PROMO_ROW);
        addSwitch("猜你喜欢", Config.K_MY_GUESS);
        addSwitch("资质信息 / 协议中心", Config.K_MY_QUALITY);
        endCard(root);

        // ---- 其他 ----
        root.addView(sectionHeader("其他"));
        beginCard();
        addSwitch("调试日志（logcat 输出首页文本锚点）", Config.K_DEBUG_LOG);
        addActionButton("恢复默认（全部显示）", new Runnable() {
            @Override public void run() {
                if (App.clearAll()) {
                    Toast.makeText(MainActivity.this, "已恢复默认，请强停高德地图生效", Toast.LENGTH_LONG).show();
                    recreate();
                } else {
                    Toast.makeText(MainActivity.this, "LSPosed 服务未连接，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            }
        });
        endCard(root);

        TextView tip = text("改动后请强停高德地图并重新打开以生效", 12, Typeface.NORMAL, TX_SECONDARY);
        tip.setPadding(dp(4), dp(14), 0, 0);
        root.addView(tip);
    }

    @Override
    protected void onResume() {
        super.onResume();
        synced = false;          // 回到设置页时按存储重刷一遍开关
        statusRefresher.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusRefresher);
    }

    private String statusText() {
        io.github.libxposed.service.XposedService s = App.svc();
        if (s == null) {
            return "○ 未激活 / 配置通道连接中…\n（在 LSPosed 中启用本模块后此页会自动变为已连接）";
        }
        return "● 已激活 · 高德地图\n● 配置通道已连接 · " + s.getFrameworkName() + " " + s.getFrameworkVersion();
    }

    private final android.os.Handler statusHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable statusRefresher = new Runnable() {
        @Override public void run() {
            if (statusView != null) statusView.setText(statusText());
            // 服务是异步绑定的：绑定前 readState() 只能回退默认 true，
            // 若此时就把开关画成"开"，用户重开设置页会以为配置全丢了。
            // 所以绑定成功后立刻按真实存储重刷一遍开关。
            if (!synced && App.svc() != null) {
                synced = true;
                syncSwitches();
            }
            statusHandler.postDelayed(this, 800);
        }
    };

    private final java.util.LinkedHashMap<String, Switch> switches = new java.util.LinkedHashMap<>();
    private volatile boolean synced;

    private void syncSwitches() {
        try {
            io.github.libxposed.service.XposedService s = App.svc();
            if (s == null) return;
            android.content.SharedPreferences p =
                    s.getRemotePreferences(Config.PREF_GROUP);
            for (java.util.Map.Entry<String, Switch> e : switches.entrySet()) {
                Switch sw = e.getValue();
                sw.setChecked(p.getBoolean(e.getKey(), Config.defaultVisible(e.getKey())));
                sw.setEnabled(true);
                View row = (View) sw.getParent();
                if (row != null) row.setEnabled(true);
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ UI 工厂

    private TextView text(String s, int sp, int style, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT_BOLD, style == Typeface.BOLD ? Typeface.BOLD : Typeface.NORMAL);
        t.setTextColor(color);
        return t;
    }

    private TextView sectionHeader(String s) {
        TextView t = text(s, 14, Typeface.BOLD, TX_ACCENT);
        t.setPadding(dp(6), dp(18), 0, dp(8));
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

    /** 统一规格开关行：52dp 高、左标题右 Switch、行间分隔线 */
    private void addSwitch(String title, final String key) {
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

        final Switch sw = new Switch(this);
        sw.setChecked(readState(key));
        // 服务未绑定时先禁用，等 syncSwitches() 用真实存储值刷新后再放开
        sw.setEnabled(App.svc() != null);
        sw.setClickable(false);
        switches.put(key, sw);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean next = !sw.isChecked();
                if (App.writeBoolean(key, next)) {
                    sw.setChecked(next);
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

    /** 当前状态：优先读 RemotePreferences（Hook 侧同一数据源），读不到按各键默认值 */
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

    private void addActionButton(String title, final Runnable action) {
        addDivider();
        TextView label = text(title, 15, Typeface.NORMAL, TX_ACCENT);
        label.setPadding(0, dp(12), dp(8), dp(12));
        label.setGravity(Gravity.CENTER);
        label.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        currentCard.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
