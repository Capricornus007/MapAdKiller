package io.github.ldxm666.mapadkiller;

import android.content.SharedPreferences;

/**
 * 配置定义（键名模块 App 与 Hook 侧共用）+ Hook 侧读取。
 * 存储走 LSPosed RemotePreferences（存于 LSPosed 数据库，App 侧经
 * libxposed/service 写入，Hook 侧经 XposedModule#getRemotePreferences 读取，
 * 同名 group 双侧共享，写入即生效）。
 * 读取失败一律回退默认值（全部显示 / 去广告始终开启），保证失效安全。
 * 注意：不要引用 de.robv.*（API 102 模块进程内不存在 legacy API）。
 */
public final class Config {

    public static final String PKG = "io.github.ldxm666.mapadkiller";
    public static final String PREF_GROUP = "amap_enhancer_config";

    // ---- 键名 ----
    public static final String K_TAB_PREFIX = "tab_";
    public static final String K_TOOLS_VISIBLE = "ui_tools_visible";
    public static final String K_TOOL_PREFIX = "tool_";
    public static final String K_FEED_WEATHER = "feed_weather";
    public static final String K_FEED_SCENIC = "feed_scenic";
    public static final String K_FEED_RANK = "feed_rank";
    public static final String K_FEED_POSTS = "feed_posts";
    public static final String K_FEED_DISTANCE = "feed_distance";
    public static final String K_FEED_CONTENT = "feed_content";
    public static final String K_FEED_AI = "feed_ai";
    public static final String K_FEED_FILTER = "feed_filter";
    public static final String K_HOME_CHIPS = "home_chips";
    public static final String K_DEBUG_LOG = "debug_log";

    /** 桌面图标开关：true = 隐藏启动图标（改名本 App 的 launcher-alias 组件） */
    public static final String K_HIDE_ICON = "hide_launcher_icon";

    /**
     * 工具宫格里的「收藏夹」。它以前被并进「扩展工具页」一起隐藏，用户没法单独留它，
     * 所以拆成独立键，默认显示，由设置页单独控制。
     */
    public static final String K_TOOL_FAVORITE = K_TOOL_PREFIX + "收藏夹";

    /**
     * 搜索页顶部那个分页金刚区：美食 / 酒店 / 加油站 / 休闲玩乐 / 扫街榜 …
     * 它不是搜索功能，是运营位（用户原话：这是广告很影响），单独一个开关。
     * 默认显示（与其它键一致的失效安全）。
     */
    public static final String K_SEARCH_CATS = "search_cat_row";

    /**
     * 首页底部那张「去XX」快捷打车卡（智能目的地：标题 + 座位提示 + 打车按钮）。
     * 用户原话："有点没什么用" —— 单独一个开关，默认显示。
     */
    public static final String K_QUICK_CARD = "home_quick_card";

    /**
     * 工具宫格「扩展页」：首页工具宫格往下还有一排没被列进常用工具里的格子
     * （景点游玩 / 离线地图 / 通行费助手 / 收藏夹 / 旅游度假）。
     * 它们不是用户选的工具，默认隐藏；想留就在设置页打开。
     */
    public static final String K_TOOL_EXTRA = "tool_extra_page";

    /** 各键的默认可见性：只有扩展工具页默认隐藏，其余一律默认显示（失效安全） */
    public static boolean defaultVisible(String key) {
        return !K_TOOL_EXTRA.equals(key);
    }

    // ---- 「我的」页 ----
    public static final String K_MY_ORDER_ROW = "my_order_row";
    public static final String K_MY_SERVICE_ROW = "my_service_row";
    public static final String K_MY_TASK = "my_task";
    public static final String K_MY_PROMO_ROW = "my_promo_row";
    public static final String K_MY_GUESS = "my_guess";
    public static final String K_MY_QUALITY = "my_quality";

    /** 底部标签栏 tab 清单（键名 = "tab_" + 标签文本） */
    public static final String[] TABS = {
            "首页", "探索", "长按说话", "打车", "我的",
    };

    /** 首页工具清单（键名 = "tool_" + 标签文本，与首页文本锚点一致） */
    public static final String[] TOOLS = {
            "驾车", "公交地铁", "租车", "打车", "订酒店",
            "火车票", "顺风车", "高德扫街", "代驾", "更多工具",
    };

    private static volatile SharedPreferences cached;

    /** Hook 侧读取入口：框架提供的 RemotePreferences（同名 group 与 App 侧共享） */
    public static SharedPreferences prefs() {
        SharedPreferences p = cached;
        if (p == null) {
            synchronized (Config.class) {
                p = cached;
                if (p == null) {
                    p = H.module.getRemotePreferences(PREF_GROUP);
                    cached = p;
                }
            }
        }
        return p;
    }

    /** 默认 true = 显示；读取失败回退默认，保证失效安全 */
    public static boolean visible(String key) {
        boolean def = defaultVisible(key);
        try { return prefs().getBoolean(key, def); } catch (Throwable t) { return def; }
    }

    public static boolean toolVisible(String label) {
        return visible(K_TOOL_PREFIX + label);
    }

    public static boolean tabVisible(String label) {
        return visible(K_TAB_PREFIX + label);
    }

    /** 临时取证开关：真机测树期间强制开日志（交付前必须改回 false） */
    public static final boolean FORCE_DEBUG = false;

    private static volatile long dbgAt;
    private static volatile boolean dbgVal;

    /** 热点路径（每个 AJX 文本都会问一次）：2 秒 TTL 缓存，避免每次 setText 都走一次 IPC。 */
    public static boolean debugLog() {
        if (FORCE_DEBUG) return true;
        long now = System.currentTimeMillis();
        if (now - dbgAt > 2000) {
            try { dbgVal = prefs().getBoolean(K_DEBUG_LOG, false); }
            catch (Throwable t) { dbgVal = false; }
            dbgAt = now;
        }
        return dbgVal;
    }

    private Config() {}
}
