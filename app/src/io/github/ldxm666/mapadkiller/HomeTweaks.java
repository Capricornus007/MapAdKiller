package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * HomeTweaks — 首页 / 「我的」页 UI 自定义引擎。
 *
 * ══ 实测结构（高德 16.25.1.2029 / NewMapActivity / 1080x2400，真机 TreeDump 实证）══
 *
 * 首页主体整棵是 AJX3 虚拟 DOM：
 *   AmapAjxView > FullView > BodyView > PullToRefreshList > AjxList2
 *   AjxList2 的直接子节点 = AjxAbsoluteLayout = 「列表 item」，一个区块一个 item。
 *
 * 工具宫格（**整个宫格只是同一个 item 内部的一块**，不是每格一个 item）：
 *   Label(184x47) > Container(158xH)[格子] > Container(158xH) > Container(1006xH)[行]
 *                > 宫格 Container > 区块 Container > AjxAbsoluteLayout[item]
 *   3 行 × 5 列共 15 个槽位：第 1~2 行是 10 个工具，第 3 行是「更多工具」那页的轮播格
 *   （景点游玩 / 离线地图 / 通行费助手 / 收藏夹 / 更多工具 轮着显示）。
 *   列距 212px，格子宽 158px，行高 164/147/147px。
 *
 * 「我的」页同为 AJX 列表；订单 / 车辆服务是 HorizontalScroller 行：
 *   Label(209x42) > Container(209x205)[格子] > AjxAbsoluteLayout(1043x205) > HorizontalScroller
 *
 * 底部标签栏是原生 View：LinearLayout 行 > TabItemLayoutV2 > TextView(tab_name_v2)。
 *
 * ══ 旧版为什么全线崩坏（本次修复的根因，全部真机实证）══════════════════════
 *
 * 1) **整个工具宫格消失** —— 锚点表用子串匹配，宫格第 3 行轮播出的 `景点游玩`
 *    命中 ANCHOR_SCENIC 的「景点」，被当成 feed_scenic；然后沿父链爬到 AjxList2
 *    的 item —— 那个 item 就是**整个工具宫格** —— GONE + height=0。
 *    一条推荐区规则就这样把整个宫格抹掉。这就是"工具栏整个框被隐藏"。
 *
 * 2) **永远匹配不上的工具** —— 配置里写 `火车票` / `高德扫街`，界面上实际是
 *    `火车票机票` / `高德扫街榜`，精确匹配永远失败。
 *
 * 3) **永远匹配不上的我的页条目** —— 实际文案是 `-资质信息 ` 与 `协议中心-`，
 *    带装饰字符，`equals("资质信息")` 永远为假。
 *
 * 4) **"我的页被全部关闭"** —— 旧的 ascendWideRow 取的是**最外层**匹配祖先
 *    （best 一路被覆盖），一旦中间某层恰好也满足 宽≥760/高<300，就会一路爬到
 *    页面级容器并 GONE 掉；再加上 collapseItem 盲目爬到 AJX 列表 item，
 *    整页自然就没了。
 *
 * 5) **很卡、低效率** —— 每个锚点各自 postDelayed(500ms) + 150ms×20 重试，
 *    外加 600ms 全树遍历轮询，而且每次 visible(key) 都要走一次
 *    RemotePreferences IPC。
 *
 * 6) **先显示后消失的闪烁** —— 收缩发生在渲染之后 350~500ms。
 *
 * ══ 本实现的铁律 ═══════════════════════════════════════════════════
 *  A. 先分类，后动手。文本先判定归属面（工具格 / 标签栏 / 我的行 / 推荐卡），
 *     再做该面的动作，绝不跨面误伤。
 *  B. 工具格受保护。判定过的格子/行/宫格登记为 protected；任何"收缩 item"
 *     的爬升路径一旦穿过受保护节点立即中止 —— 推荐区规则再也不可能抹掉宫格。
 *  C. 一格一图。格子用 WeakHashMap 记住自己的工具键，轮播格的其它文案
 *     （景点游玩 / 旅游度假 …）复用同一个键，永不外溢成推荐区锚点。
 *  D. 一帧一次。Label.setText 只登记并立刻 GONE 掉该显示的标题（消闪烁），
 *     隐藏与重排合并到下一帧执行一次。
 *  E. 自愈。登记的锚点常驻索引，每一轮把"当时还没挂载"的锚点重试到挂载为止
 *     —— 这就是 车辆服务 那次 HorizontalScroller 尚未 attach 时丢事件的解药。
 *  F. 收行不收区。一行全空才收行，宫格全空才收宫格；绝不越级抹掉整个 item。
 */
public final class HomeTweaks {

    // ══════════════════════════════════════════════════════════ 静态表

    private static final Set<String> TAB_LABELS = new HashSet<>(Arrays.asList(Config.TABS));

    /** 工具格文案 → Config.TOOLS 内的键。轮播格的所有文案必须归一到同一个键。 */
    private static final Map<String, String> TOOL_ALIAS = new HashMap<>();
    static {
        for (String t : Config.TOOLS) TOOL_ALIAS.put(t, t);
        TOOL_ALIAS.put("火车票机票", "火车票");
        TOOL_ALIAS.put("高德扫街榜", "高德扫街");
        TOOL_ALIAS.put("旅游度假", "更多工具");
        TOOL_ALIAS.put("景点游玩", "更多工具");
        TOOL_ALIAS.put("离线地图", "更多工具");
        TOOL_ALIAS.put("通行费助手", "更多工具");
        // 「收藏夹」不再并进「更多工具」：它在宫格第 3 行有自己的槽位，
        // 混进别名后永远被 tool_更多工具 与 扩展工具页 两个开关夹击，
        // 用户怎么点都出不来。这里给它自己一个键（Config.K_TOOL_FAVORITE）。
        TOOL_ALIAS.put("收藏夹", "收藏夹");
    }

    /**
     * 工具宫格"扩展页"的文案：首页宫格往下还藏着一排推荐工具
     * （实测 景点游玩 / 离线地图 / 通行费助手 / 收藏夹 / 旅游度假）。
     * 它们不是用户勾选的那 10 个工具，单独由 Config.K_TOOL_EXTRA 控制，
     * **默认隐藏** —— 否则首页往下拉就会冒出一整排没被关掉的格子。
     */
    private static final Set<String> TOOL_EXTRA_LABELS = new HashSet<>(Arrays.asList(
            "景点游玩", "离线地图", "通行费助手", "旅游度假"));

    /** 推荐频道栏 —— 只在"宽格子"里算数，避免误伤达人卡里的粉丝/关注计数（85px 窄格）。 */
    private static final Set<String> FEED_FILTER_LABELS =
            new HashSet<>(Arrays.asList("关注", "附近", "美食", "周末出游", "休闲玩乐"));

    private static final Set<String> HOME_CHIPS_LABELS =
            new HashSet<>(Arrays.asList("设置家", "设置单位", "常去地点"));

    /**
     * 「去XX」快捷打车卡的旁证文案。
     * 标题本身是"去 + 目的地名"（随用户而变），没法写死，所以用旁边这些小字当判据：
     * 只有当标题以「去」开头、**并且**同一小块里还挂着下面任意一条时，才认这张卡。
     * 这样「去扫描」那种信息流文案不会被误伤。
     */
    private static final Set<String> QUICK_CARD_HINTS = new HashSet<>(Arrays.asList(
            "有座不拥挤", "行程有保障", "打车不排队", "特惠打车", "预估", "打车", "AI叫车"));

    /**
     * 这些文案本身就够独特，命中即可认卡，不需要旁证。
     * 「帮我预约车辆 / 通勤高峰担心拥堵、叫不到车 / AI叫车」和「去XX」是**同一个轮播槽位**
     * 里的两张卡（高德按账号下发），所以共用同一个开关。
     */
    private static final String[] QUICK_CARD_SELF = {
            "帮我预约车辆", "帮我叫车", "预约车辆", "AI叫车",
            "叫不到车", "通勤高峰担心拥堵", "有座不拥挤", "行程有保障",
    };

    /**
     * 搜索页金刚区的分类文案。判定方式是"结构投票"：
     * 只有当一个容器里同时挂着 >=4 个这些文案时，才认它是那一排运营位。
     * 单看一个「美食」绝不能动手 —— 首页信息流、我的页、工具宫格里都有它。
     */
    private static final Set<String> SEARCH_CAT_LABELS = new HashSet<>(Arrays.asList(
            "美食", "酒店", "加油站", "休闲玩乐", "扫街榜", "充电站", "洗车", "修车",
            "特价酒店", "民宿", "景点门票", "电影", "丽人", "亲子", "购物", "加油"));

    // ----「我的」页：精确匹配（先做装饰字符归一）----
    private static final Set<String> MY_ORDER = new HashSet<>(Arrays.asList(
            "订单", "收藏", "待评价", "钱包卡券", "借钱"));
    private static final Set<String> MY_SERVICE = new HashSet<>(Arrays.asList(
            "车辆服务", "高德运动", "家人地图", "店铺入驻", "地图共建",
            "地图小程序", "高德代驾", "高德油耗", "工具箱"));
    private static final Set<String> MY_TASK = new HashSet<>(Arrays.asList("达人任务", "达人权益"));
    private static final Set<String> MY_PROMO = new HashSet<>(Arrays.asList(
            "扫街新发现", "长征星火", "小德果园", "重走长征路", "免费领水果", "赢奖牌勋章",
            "地图大富翁", "攒金条兑好礼", "达人卡中心", "写真评兑好礼"));
    private static final Set<String> MY_QUALITY_WORDS = new HashSet<>(Arrays.asList(
            "资质信息", "协议中心", "证照与协议", "猜你喜欢"));

    // ---- 推荐区锚点（按实测文案校准）----
    private static final String[] ANCHOR_WEATHER = {"天气", "降雨", "气温实况", "雷阵雨"};
    private static final String[] ANCHOR_SCENIC = {"周边游玩", "周边景区", "景点推荐", "附近游玩", "景区", "景点"};
    private static final String[] ANCHOR_RANK = {"热门榜", "精选榜", "榜单", "排行榜", "热榜", "上榜餐厅", "上榜景区"};
    private static final String[] ANCHOR_POSTS = {"个地点", "打卡地", "夜市", "周边热玩", "上榜"};
    private static final String[] ANCHOR_CONTENT = {
            "优质内容", "内容精选", "攻略", "Citywalk", "citywalk", "玩法", "秘境", "笔记",
            "避雷指南", "去扫描", "全新上线", "提前推演", "高德快报", "身边的新鲜事",
            "收藏起来", "值得一去", "遛娃", "城市漫游", "不可以", "不知道"};
    private static final String[] ANCHOR_AI = {"问问AI", "问问 AI", "小德助手"};

    // ══════════════════════════════════════════════════════════ 运行态

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static volatile Activity activity;
    private static boolean scheduled;

    /**
     * 常驻锚点索引：AJX 文本 view → 文案。
     * 不随 applyPass 清空 —— 这样"setText 时还没挂载"的锚点可以在后续每一轮重试到挂载为止。
     */
    private static final WeakHashMap<View, String> anchors = new WeakHashMap<>();
    /** 工具格 → 工具键 */
    private static final WeakHashMap<View, String> cellKey = new WeakHashMap<>();
    /** 工具格 → 该格当前显示的 Label（用来判断"这格到底有没有画出来"） */
    private static final WeakHashMap<View, java.lang.ref.WeakReference<View>> cellLabel = new WeakHashMap<>();
    /** 受保护节点：工具格 / 工具行 / 宫格 */
    private static final WeakHashMap<View, Boolean> protectedNodes = new WeakHashMap<>();
    /** 推荐频道栏是否已经处理过（结构识别，每轮 resume 重置） */
    private static volatile boolean channelDone;
    private static volatile long channelSince;

    /** 已收缩的宿主 item */
    private static final List<java.lang.ref.WeakReference<View>> hiddenItems = new ArrayList<>();
    /** 已 GONE 的格子 */
    private static final List<java.lang.ref.WeakReference<View>> hiddenCells = new ArrayList<>();
    /** 已收空的工具行（需要连同高度一起压成 0，否则宫格仍留白） */
    private static final List<java.lang.ref.WeakReference<View>> squashedRows = new ArrayList<>();
    /** 收行之前那一行的原始 lp.height，用来反悔（配置改回来 / 误判纠正） */
    private static final WeakHashMap<View, Integer> squashOrigHeight = new WeakHashMap<>();

    /**
     * 每个工具格最终"留还是不留"的结论（配置驱动，不看几何）。
     *
     * 为什么必须要这张表：判"这一行是不是空行"如果看当时的 width/height，
     * AJX 还没量过的时候每个格子都是 0x0，整行会被误判成空行收掉；
     * 而 squashedRows 每轮都会重申 GONE + height=0 —— 那一行就**永久**死了。
     * 收藏夹开开关也出不来就是这个：它所在的那一行在首帧就被收成了空行。
     */
    private static final WeakHashMap<View, Boolean> cellKeep = new WeakHashMap<>();
    /** 已收缩的宿主 → 规则（去重 + 幂等） */
    private static final WeakHashMap<View, String> hiddenWhy = new WeakHashMap<>();
    /** 已挂过 onBindViewHolder 的列表适配器类（按类去重，一个类只挂一次） */
    private static final Set<String> bindHooked = new HashSet<>();
    /** 可见性代次：每次隐藏/恢复格子就 +1，宫格排版据此只做一次 */
    private static volatile int hideGen = 1;
    private static final WeakHashMap<View, Integer> packedGen = new WeakHashMap<>();
    private static final Set<String> loggedOnce = new HashSet<>();

    private static final Set<String> dumped = new HashSet<>();
    private static int dumpBudget = 120;

    private static volatile Map<String, Boolean> cfg = Collections.emptyMap();
    private static volatile long cfgAt;

    private static ViewGroup tabRow;

    /**
     * 本模块的铁律 G：只在高德首页动手。
     *
     * 以前每一轮 applyPass 都是「整棵 decor 树照单全收」，于是任意页面上的自由文案
     * 都会被当成首页锚点。踩得最狠的一次是路线规划页：备选路线的里程文案
     * 2379公里 / 34米 命中 isDistanceLabel（首页信息流卡片的距离标签规则），
     * collapseHost 顺着父链爬到列表宿主，把「24小时22分 / 封路」那一整排路线信息
     * 模块 GONE 掉 —— 这就是「信息模块也被隐藏」的根因。
     *
     * 首页与「我的」页都带底部悬浮标签栏（LiteTabBar > TabItemLayoutV2），
     * 路线页 / 搜索页 / 导航页没有。所以用「这棵树里有没有一条正在显示的标签栏」
     * 当作「是不是首页」的判据：不是首页就整轮放弃，一个节点都不碰。
     */
    private static volatile boolean homeCtx;

    private HomeTweaks() {}

    // ══════════════════════════════════════════════════════════ 安装

    public static void install(final ClassLoader cl) {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            Method onPause = Activity.class.getDeclaredMethod("onPause");
            H.module.hook(onResume).setId("amapenhancer_resume")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            activity = (Activity) chain.getThisObject();
                            tabRow = null;
                            homeCtx = false;   // 新页面先当"不是首页"，由 applyPass 认领
                            channelDone = false;
                            channelSince = System.currentTimeMillis();
                            scheduled = false;
                            // 收敛尾巴：布局稳定前后各压几轮，然后停手（不再 600ms 常驻轮询）
                            for (long d : new long[]{100, 350, 800, 1500, 2600, 4200}) {
                                MAIN.postDelayed(APPLY, d);
                            }
                            MAIN.removeCallbacks(REASSERT);
                            MAIN.postDelayed(REASSERT, 600);
                            return r;
                        }
                    });
            H.module.hook(onPause).setId("amapenhancer_pause")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            activity = null;
                            tabRow = null;
                            MAIN.removeCallbacks(APPLY);
                            MAIN.removeCallbacks(REASSERT);
                            return chain.proceed();
                        }
                    });
            installLabelHook(cl);
            H.log(Log.INFO, MainHook.TAG, "hometweaks installed");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "hometweaks fail " + t);
        }
    }

    /**
     * AJX 文本钩子。这里只做两件事：
     *  - 登记（常驻索引，供后续每一轮重试到挂载为止）；
     *  - 若该文案对应"已关闭"的配置，立刻 GONE 掉标题本身 → 消除"卡片先显示后消失"的闪烁。
     * 真正的隐藏与重排由 applyPass 在下一帧合并执行一次。
     */
    private static void installLabelHook(ClassLoader cl) {
        try {
            Class<?> label = cl.loadClass("com.autonavi.minimap.ajx3.widget.view.Label");
            // 不能只挂 setText(String)：实测首页"推荐频道栏"那一排
            // （关注 / 成都 / 附近 / 周末出游 / 美食 / 休闲玩乐）根本不走这个重载，
            // 所以它们既看不见也藏不掉。这里把 Label 上所有以文本为入参的
            // 设值方法全部挂上，顺便把方法表打进日志便于以后校准。
            int hooked = 0;
            for (Method m : label.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.startsWith("set")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 0) continue;
                String p0 = ps[0].getName();
                boolean textual = p0.equals("java.lang.String")
                        || p0.equals("java.lang.CharSequence")
                        || p0.equals("java.lang.Object");
                if (!textual) continue;
                if (Config.debugLog() && loggedOnce.add("labelsig_" + n + ps.length)) {
                    H.log(Log.INFO, MainHook.TAG, "LABEL-METHOD " + m);
                }
                if (n.startsWith("setText") && hookLabelText(m)) hooked++;
            }
            // 关键补充：AJX 有一部分文本根本不走 setText，而是走属性系统
            // Label.setAttribute("text", value, ...)。
            // 首页「推荐频道栏」那一排（关注/成都/附近/周末出游/美食/休闲玩乐）
            // 实测就是走这条路 —— 只挂 setText 永远看不见它们。
            if (hookAttribute(label)) hooked++;
            H.log(Log.INFO, MainHook.TAG, "label hook installed x" + hooked);
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "label hook fail " + t);
        }
    }

    private static boolean hookLabelText(Method m) {
        try {
            H.module.hook(m).setId("amapenhancer_label")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try { onLabelText(chain); } catch (Throwable ignored) {}
                            return r;
                        }
                    });
            return true;
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "label hook fail " + m + " " + t);
            return false;
        }
    }

    /** Label.setAttribute(name, value, ...) —— AJX 的属性写入口，文本属性名为 "text" */
    private static boolean hookAttribute(Class<?> label) {
        for (Method m : label.getDeclaredMethods()) {
            if (!m.getName().equals("setAttribute")) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length < 2 || !ps[0].getName().equals("java.lang.String")) continue;
            try {
                H.module.hook(m).setId("amapenhancer_labelattr")
                        .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                            @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                                Object r = chain.proceed();
                                try {
                                    Object n0 = chain.getArg(0);
                                    Object v1 = chain.getArg(1);
                                    if (n0 instanceof String && v1 instanceof CharSequence
                                            && "text".equals(n0)) {
                                        onTextSet((View) chain.getThisObject(),
                                                ((CharSequence) v1).toString());
                                    }
                                } catch (Throwable ignored) {}
                                return r;
                            }
                        });
                return true;
            } catch (Throwable t) {
                H.log(Log.WARN, MainHook.TAG, "attr hook fail " + t);
            }
        }
        return false;
    }

    private static void onLabelText(io.github.libxposed.api.XposedInterface.Chain chain) {
        Object a0 = chain.getArg(0);
        if (!(a0 instanceof CharSequence)) return;
        onTextSet((View) chain.getThisObject(), a0.toString());
    }

    /** 所有 AJX 文本的统一落点（setText 与 setAttribute("text"…) 都汇到这里） */
    private static void onTextSet(View v, String raw) {
        if (v == null || raw == null) return;
        if (raw.length() == 0 || raw.length() > 28) return;
        String t = norm(raw);
        if (t.length() == 0) return;

        String toolKey = TOOL_ALIAS.get(t);
        String rule = toolKey != null ? null : ruleFor(t, v);
        // 搜索金刚区的文案也要登记（它们多数不属于任何一种首页规则，但要做结构投票）
        // 「去XX」快捷卡的标题同理：它出现时旁边的「打车 / 有座不拥挤」可能还没 setText，
        // 当场判定必然落空 —— 所以先按文案形状乐观登记，旁证留给 applyPass 每轮再判。
        if (toolKey == null && rule == null
                && !SEARCH_CAT_LABELS.contains(t) && !looksLikeQuickCardTitle(t)
                && !isSelfEvidentQuickCard(t)) return;

        synchronized (LOCK) { anchors.put(v, t); }
        trace(v, t);

        String key = toolKey;
        if (key == null) {
            View cell = ascendSmallCell(v);
            String ck = cell == null ? null : cellKey.get(cell);
            if (ck != null) key = ck;   // 轮播格的其它文案复用本格已登记的键
        }
        // 即时盖标题（消闪烁）——但**必须先确认这是首页的东西**。
        // 「更多工具」那一整页里也有「收藏夹 / 代驾 / 旅游度假…」这些文案，
        // 原来不加区分地盖，那一页的入口就变成"有图标没字"，
        // 用户看到的正是"偶尔跳出其他工具出来"。
        if (key != null) {
            View cell = ascendSmallCell(v);
            if (cell != null && looksLikeToolCell(cell) && !cfgOn(Config.K_TOOL_PREFIX + key)) {
                if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
            }
        } else if (rule != null && !cfgOn(rule) && homeCtx) {
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
        }
        schedule();
    }

    private static void schedule() {
        if (scheduled) return;
        scheduled = true;
        MAIN.post(APPLY);
    }

    // ══════════════════════════════════════════════════════════ 一帧一次

    private static final Runnable APPLY = new Runnable() {
        @Override public void run() {
            scheduled = false;
            try { applyPass(); }
            catch (Throwable t) { H.log(Log.WARN, MainHook.TAG, "applyPass err " + t); }
        }
    };

    private static void applyPass() {
        Activity act = activity;
        if (act == null || act.isFinishing()) return;
        refreshCfg();

        View decor;
        try { decor = act.getWindow().getDecorView(); } catch (Throwable t) { return; }
        if (decor == null) return;

        // 锚点快照先拍下来：下面几条规则都用它
        List<View> views = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<View, String> e : anchors.entrySet()) {
                views.add(e.getKey());
                texts.add(e.getValue());
            }
        }

        // 铁律 G：先认页面。认得出首页（有正在显示的底部标签栏）才继续往下做，
        // 认不出就整轮什么都不动植物 —— 路线页/搜索页/导航页从此免疫。
        homeCtx = applyTabs(decor);

        // 搜索页金刚区（美食 / 酒店 / 加油站 / 休闲玩乐 / 扫街榜）不属于首页，
        // 所以在首页闸门**之前**单独跑，而且只认"一个容器里同时挂着 >=4 个分类文案"的结构。
        applySearchCats(views, texts);

        if (!homeCtx) return;

        // 推荐频道栏（关注 / 成都 / 附近 / 周末出游 / 美食 / 休闲玩乐）：
        // 实测它的文案既不走 Label.setText 也不走 Label.setAttribute("text")，
        // 所以文本锚点根本看不见它。这一栏用**结构**识别并整体摘掉。
        if (!cfgOn(Config.K_FEED_FILTER) && !channelDone) {
            // 频道栏是懒加载的，可能几秒后才出现，因此在一个时间窗内持续找；
            // 超窗后停手，避免无休止全树遍历。
            View bar = findChannelBar(decor, 0);
            if (bar != null) {
                collapseHost(bar, Config.K_FEED_FILTER);
                channelDone = true;
            } else if (System.currentTimeMillis() - channelSince < 40000) {
                MAIN.postDelayed(APPLY, 400);
            }
        }

        applyTools(views, texts);

        // 运营推广卡槽位：绑定那一刻 item 还没量过（0x0），所以这里按布局后的几何再找一遍
        applyPromoSlot(decor);

        for (int i = 0; i < views.size(); i++) {
            String t = texts.get(i);
            if (TOOL_ALIAS.containsKey(t)) continue;
            String rule = ruleFor(t, views.get(i));
            if (rule == null || cfgOn(rule)) continue;
            // 「设置家 / 设置单位 / 常去地点」只摘这一排 —— 它和「去幸福路步行街」
            // 那种快捷卡常常在**同一个 AJX item** 里，collapseHost 会把整张卡一起收掉，
            // 用户看到的就是"那张卡变成半张、文字被切"。
            if (hideChipsRowOnly(views.get(i), rule)) continue;
            collapseHost(views.get(i), rule);
        }

        reassert();
    }

    /**
     * 常驻重申心跳（只跑 reassert，不做全树扫描，代价极小）。
     *
     * 为什么必须有：resume 时只排了 6 轮 APPLY（最晚 4.2s），之后 applyPass 就停手了。
     * 而 AJX 在滚动 / 重渲染时会把被压掉的 item **复活成 VISIBLE 但保留我们写的
     * height=0** —— 于是那张卡被画成"半张、文字被切"的样子（用户截图里那张
     * 「去幸福路步行街」就是），很难看。有了心跳，复活后最多 300ms 就被按回 GONE。
     */
    private static final Runnable REASSERT = new Runnable() {
        @Override public void run() {
            if (activity == null) return;
            try { reassert(); } catch (Throwable ignored) {}
            MAIN.postDelayed(this, 300);
        }
    };

    /** 每轮重申（AJX 重渲染会复活可见性 / 复位高度）。 */
    private static void reassert() {
        for (Iterator<java.lang.ref.WeakReference<View>> it = hiddenItems.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null) { it.remove(); continue; }
            // AJX 一复活就立刻按回去（GONE + height 0 一起，不能只做一半）
            reapplyHidden(v);
        }
        for (Iterator<java.lang.ref.WeakReference<View>> it = hiddenCells.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null) { it.remove(); continue; }
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
        }
        for (Iterator<java.lang.ref.WeakReference<View>> it = squashedRows.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null) { it.remove(); continue; }
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != 0) {
                lp.height = 0;
                v.setLayoutParams(lp);
            }
        }
    }

    // ══════════════════════════════════════════════════════════ 工具宫格

    private static void applyTools(List<View> views, List<String> texts) {
        Set<ViewGroup> dirtyRows = new LinkedHashSet<>();
        for (int i = 0; i < views.size(); i++) {
            String t = texts.get(i);
            String toolKey = TOOL_ALIAS.get(t);
            if (toolKey == null) continue;
            View anchor = views.get(i);
            View cell = ascendSmallCell(anchor);
            if (cell == null) continue;          // 还没挂载：留在常驻索引里，下一轮再说
            if (!looksLikeToolCell(cell)) continue;   // 形状不像工具格 → 不是宫格里的东西

            String key = cellKey.get(cell);
            if (key == null) { key = toolKey; cellKey.put(cell, key); }
            // 记下该格当前显示的 Label：优先保留"真的画出来了"（width>0）的那个实例
            java.lang.ref.WeakReference<View> prev = cellLabel.get(cell);
            View prevV = prev == null ? null : prev.get();
            if (prevV == null || (prevV.getWidth() <= 0 && anchor.getWidth() > 0)) {
                cellLabel.put(cell, new java.lang.ref.WeakReference<>(anchor));
            }
            protectTool(cell, key);

            // ── 留还是不留，只看「这一格此刻显示的那个文案」自己的开关 ──
            // 这里绝不能用 cellKey：它是首见文案钉住的键，而宫格第 3 行是**轮播格**
            // （景点游玩 / 离线地图 / 通行费助手 / 收藏夹 / 更多工具 在这些槽位里换），
            // 第一个撞上来的如果是「通行费助手」，这一格就被钉成 tool_更多工具，
            // 之后轮到「收藏夹」也照样按那个键判 —— 用户开了收藏夹开关也永远出不来。
            boolean extra = TOOL_EXTRA_LABELS.contains(t);
            boolean keep = extra ? cfgExtraPage() : cfgOn(Config.K_TOOL_PREFIX + toolKey);
            markKeep(cell, keep);
            if (Boolean.TRUE.equals(cellKeep.get(cell))) {
                // 保过一次就永久保（轮播格轮到收藏夹时把它放出来）
                unHideCell(cell);
                continue;
            }
            hideCell(cell, extra ? ("extra:" + t) : key, dirtyRows);
        }
        // 宫格级"补空"：只在**同一行内**把可见格左对齐压实，绝不换父。
        // 换父（removeView/addView 把第 3 行的格搬进第 1 行）实测会被 AJX 的
        // 自有模型下次布局时覆盖，两套几何叠在一起 → 标签重叠错乱。
        // 该功能需要改 AJX 的数据层才能真正实现，属于后续工作，这里不做。
        Set<ViewGroup> grids = new LinkedHashSet<>();
        for (ViewGroup row : dirtyRows) {
            packRow(row);
            ViewParent p = row.getParent();
            if (p instanceof ViewGroup) grids.add((ViewGroup) p);
        }
        for (ViewGroup grid : grids) {
            for (int i = 0; i < grid.getChildCount(); i++) {
                View c = grid.getChildAt(i);
                if (c instanceof ViewGroup) {
                    ViewGroup row = (ViewGroup) c;
                    if (row.getChildCount() >= 3) {
                        packRow(row);
                        squashRowIfEmpty(row);
                    }
                }
            }
        }
    }

    /**
     * 这一坨到底是不是工具宫格里的格子。
     *
     * 为什么必须问这一句：首页底下那张「去幸福路步行街」快捷卡里也有一个写着
     * 「打车」的按钮，`ascendSmallCell` 照样能爬到它的容器（161x95，父容器 1006x210）——
     * 于是那张卡被当成宫格的一格，`packRow` 按工具格尺寸把整张卡重排，
     * 卡片文字被压成 84px 宽（"去幸"后面全被切掉），就是用户报的「半遮住、很难看」。
     *
     * 真机实测工具格：158x147 / 158x164；那张卡里的按钮是 161x95，高度直接出界。
     * 还没量过（0x0）的一律放行，交给下一轮。
     */
    private static boolean looksLikeToolCell(View cell) {
        int w = cell.getWidth(), h = cell.getHeight();
        if (w <= 0 || h <= 0) return true;
        return w >= 120 && w <= 220 && h >= 120 && h <= 200;
    }

    /** 隐藏一个工具格并登记该行待重排 */
    private static void hideCell(View cell, String id, Set<ViewGroup> dirtyRows) {
        if (cell.getVisibility() != View.GONE) {
            cell.setVisibility(View.GONE);
            hiddenCells.add(new java.lang.ref.WeakReference<>(cell));
            if (loggedOnce.add("tool_" + id + "_" + System.identityHashCode(cell))) {
                H.log(Log.INFO, MainHook.TAG, "TOOL-HIDE " + id + " " + geom(cell));
            }
        }
        ViewParent p = cell.getParent();
        if (p instanceof ViewGroup) dirtyRows.add((ViewGroup) p);
    }

    /** 撤回一次 GONE：隐藏登记表里摘掉、可见性摆回来，reassert 才不会下一轮又按下去 */
    private static void unHideCell(View cell) {
        boolean had = false;
        for (Iterator<java.lang.ref.WeakReference<View>> it = hiddenCells.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null || v == cell) { it.remove(); had = true; }
        }
        if (cell.getVisibility() != View.VISIBLE) cell.setVisibility(View.VISIBLE);
        if (had && loggedOnce.add("cellrestore_" + System.identityHashCode(cell))) {
            H.log(Log.INFO, MainHook.TAG, "TOOL-RESTORE " + geom(cell));
        }
    }

    /** 格子 + 其行 + 宫格登记为受保护，杜绝被推荐区锚点顺手抹掉。 */
    private static void protectTool(View cell, String key) {
        protectedNodes.put(cell, Boolean.TRUE);
        ViewParent p = cell.getParent();
        for (int i = 0; i < 2 && p instanceof View; i++) {
            View v = (View) p;
            protectedNodes.put(v, Boolean.TRUE);
            p = v.getParent();
        }
        if (loggedOnce.add("key_" + key)) {
            H.log(Log.INFO, MainHook.TAG, "TOOL-KEY " + key + " " + geom(cell));
        }
    }

    /**
     * 行内补空：把这一行的可见格子按原始槽位几何左对齐压实。
     *
     * 只做行内平移，**绝不换父**。曾经尝试跨行把 `更多工具`（宫格第 3 行）
     * 搬进第 1 行空出来的槽位，实测会被 AJX 自有模型的下一次布局覆盖，
     * 两套几何叠在一起 → 标签重叠错乱。跨行重排必须改 AJX 数据层，
     * 不属于 View 层能稳定做到的事，因此这里不做。
     *
     * 行内平移是安全的：只改 left，槽位间距 pitch 与格子尺寸都不变。
     */
    private static void packRow(ViewGroup row) {
        if (row == null || row.getChildCount() <= 1) return;

        List<View> cells = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c.getWidth() > 0 && c.getHeight() > 0) cells.add(c);
        }
        if (cells.size() <= 1) return;
        Collections.sort(cells, new Comparator<View>() {
            @Override public int compare(View x, View y) { return Integer.compare(x.getLeft(), y.getLeft()); }
        });

        int originX = cells.get(0).getLeft();
        int cellW = cells.get(0).getWidth();
        int cellH = cells.get(0).getHeight();
        // 第二道保险：只重排"看起来就是工具行"的行。
        // 否则任何被误判成工具格的容器都会被按 158 宽重排，卡片会当场被压烂。
        if (cellW < 120 || cellW > 220 || cellH < 120 || cellH > 200) return;
        int top = cells.get(0).getTop();
        int pitch = cellW;
        int d = cells.get(1).getLeft() - originX;
        if (d > 0) pitch = d;
        if (pitch <= 0) return;

        int slot = 0, moved = 0;
        for (View c : cells) {
            if (c.getVisibility() == View.GONE) continue;
            int nl = originX + slot * pitch;
            if (c.getLeft() != nl || c.getTop() != top
                    || c.getWidth() != cellW || c.getHeight() != cellH) {
                c.layout(nl, top, nl + cellW, top + cellH);
                moved++;
            }
            slot++;
        }
        if (moved > 0 && loggedOnce.add("rowpack_" + System.identityHashCode(row))) {
            H.log(Log.INFO, MainHook.TAG, "TOOL-REPACK " + geom(row)
                    + " cells=" + cells.size() + " moved=" + moved + " pitch=" + pitch);
        }
    }

    /**
     * 这个格子是否真的在显示：以该格自己 Label 的实测宽度为准。
     * 轮播格里没轮到显示的那些 Label 是 0x0 —— 这是唯一能区分
     * "View 层 VISIBLE 但其实没画出来"的信号。
     */
    private static boolean isDisplayed(View cell) {
        java.lang.ref.WeakReference<View> r = cellLabel.get(cell);
        if (r == null) return true;
        View lb = r.get();
        if (lb == null) return true;
        return lb.getWidth() > 0;
    }

    /**
     * 一行全空才收行：GONE **并且**把高度压成 0。
     * 只 GONE 不收高度的话，AJX 的宫格容器仍按模型留出那一行的空白
     * —— 这就是"剩下两个工具下面一大片空"的原因。宫格只有全空才收。
     */
    /**
     * 一行全空才收行。判据是 markKeep 登记下来的"配置结论"，不是当时的几何 ——
     * 否则首帧还没量过就会被误判成空行，而 squashedRows 每轮重申，那一行永久消失。
     * 反过来，如果这一行里还有该留的格子（比如刚被单独放出来的「收藏夹」），
     * 这里负责把它**放回去**。
     */
    private static void squashRowIfEmpty(ViewGroup row) {
        if (rowKeepsAnything(row)) { unSquashRow(row); return; }

        if (row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp != null && lp.height != 0) {
            squashOrigHeight.put(row, lp.height);   // 记住原值，方便反悔
            lp.height = 0;
            row.setLayoutParams(lp);
        }
        if (!isSquashListed(row)) squashedRows.add(new java.lang.ref.WeakReference<>(row));
        if (loggedOnce.add("emptyrow_" + System.identityHashCode(row))) {
            H.log(Log.INFO, MainHook.TAG, "TOOL-ROW-EMPTY " + geom(row));
        }

        ViewParent sp = row.getParent();
        if (!(sp instanceof ViewGroup)) return;
        ViewGroup grid = (ViewGroup) sp;
        int gv = 0;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (c.getVisibility() != View.GONE) gv++;
        }
        if (gv == 0 && grid.getVisibility() != View.GONE) {
            grid.setVisibility(View.GONE);
            if (loggedOnce.add("emptygrid_" + System.identityHashCode(grid))) {
                H.log(Log.INFO, MainHook.TAG, "TOOL-GRID-EMPTY " + geom(grid));
            }
        }
    }

    /** 这一行里还有没有"该显示"的格子。没分类过的格子一律当"要显示"（保守）。 */
    private static boolean rowKeepsAnything(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c.getVisibility() == View.GONE) continue;
            Boolean keep = cellKeep.get(c);
            if (keep == null || keep) return true;
        }
        return false;
    }

    /** 把收掉的行放回来（配置改回来 / 之前误判） */
    private static void unSquashRow(ViewGroup row) {
        for (Iterator<java.lang.ref.WeakReference<View>> it = squashedRows.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null || v == row) it.remove();
        }
        boolean changed = false;
        if (row.getVisibility() != View.VISIBLE) { row.setVisibility(View.VISIBLE); changed = true; }
        // 宫格本身可能因为"所有行都空"被收掉，行放回来了，宫格也得跟着回来
        ViewParent gp = row.getParent();
        if (gp instanceof View && ((View) gp).getVisibility() != View.VISIBLE) {
            ((View) gp).setVisibility(View.VISIBLE);
            changed = true;
        }
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        Integer orig = squashOrigHeight.get(row);
        if (lp != null && lp.height == 0) {
            lp.height = orig != null ? orig : ViewGroup.LayoutParams.WRAP_CONTENT;
            row.setLayoutParams(lp);
            changed = true;
        }
        if (changed && loggedOnce.add("unsquash_" + System.identityHashCode(row))) {
            H.log(Log.INFO, MainHook.TAG, "TOOL-ROW-RESTORE " + geom(row));
        }
    }

    private static boolean isSquashListed(View row) {
        for (java.lang.ref.WeakReference<View> r : squashedRows) if (r.get() == row) return true;
        return false;
    }

    /** 登记一个工具格"留还是不留"。留的结论优先 —— 同一格轮播多个文案时，任一开着就留着。 */
    private static void markKeep(View cell, boolean keep) {
        if (keep) cellKeep.put(cell, Boolean.TRUE);
        else if (!cellKeep.containsKey(cell)) cellKeep.put(cell, Boolean.FALSE);
    }

    // ══════════════════════════════════════════════════════════ 搜索页金刚区

    /**
     * 摘掉搜索页顶部那一排运营分类（美食 / 酒店 / 加油站 / 休闲玩乐 / 扫街榜…）。
     *
     * 判定原则是"结构投票"，不是单文案命中：只有某个容器里同时挂着 >=4 个分类文案，
     * 才认它是那一排；命中的是**从上往下第一个**满足的祖先，也就是最小那个 ——
     * 再往上就是整页容器了。
     * 这一条不属于首页，所以跑在 homeCtx 闸门之前（搜索页没有底部标签栏）。
     */
    private static void applySearchCats(List<View> views, List<String> texts) {
        if (cfgOn(Config.K_SEARCH_CATS)) return;
        for (int i = 0; i < views.size(); i++) {
            if (!SEARCH_CAT_LABELS.contains(texts.get(i))) continue;
            View anchor = views.get(i);
            if (hiddenWhy.containsKey(anchor)) continue;
            View row = findCategoryRow(anchor);
            if (row == null) continue;
            row = widenToDots(row);                 // 连同下面那排分页小圆点一起
            if (hiddenWhy.containsKey(row)) continue;
            if (row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp != null && lp.height != 0 && row.getHeight() > 0) {
                lp.height = 0;
                row.setLayoutParams(lp);
            }
            hiddenItems.add(new java.lang.ref.WeakReference<>(row));
            hiddenWhy.put(row, Config.K_SEARCH_CATS);
            H.log(Log.INFO, MainHook.TAG, "SEARCHCAT-HIDE " + shortName(row) + " " + geom(row)
                    + " | " + chainOf(anchor));
            return;                       // 一处命中就够，避免连带误伤
        }
    }

    /** 祖先链上有没有横向分页滚动容器（搜索页金刚区独有的结构特征） */
    private static boolean insideHorizontalScroller(View v) {
        View cur = v;
        for (int i = 0; i < 8 && cur.getParent() instanceof View; i++) {
            cur = (View) cur.getParent();
            if (cur.getClass().getName().endsWith("HorizontalScroller")) return true;
        }
        return false;
    }

    /** 从锚点往上找**最小**的、子树里挂着 >=4 个分类文案的祖先 */
    private static View findCategoryRow(View anchor) {
        View cur = anchor;
        for (int i = 0; i < 10 && cur.getParent() instanceof View; i++) {
            View p = (View) cur.getParent();
            Set<String> found = new HashSet<>();
            countCats(p, found, 0);
            if (found.size() >= 4) {
                // 宽度护栏：这一排一定是整屏宽（实测 1080），半屏宽的卡片一律不碰
                int w = p.getWidth();
                if (w > 0 && w < 600) return null;
                // **关键护栏**：搜索页金刚区是「分页横向滚动条」里的一排，
                // 祖先链上一定有 HorizontalScroller。
                // 没有这道闸门，「更多工具」页那一整页分类宫格（美食 / 洗车养车 / 洗牙…
                // 全是同一批词）会被整页收掉 —— 用户看到的就是"点进去白屏"。
                if (!insideHorizontalScroller(p)) return null;
                return p;
            }
            cur = p;
        }
        return null;
    }

    /**
     * 找到的那一层只是「一页」，分页小圆点是它的叔伯节点。
     * 往上走几层，把高度只多出一点点（那点差值就是圆点的高度）的包装层一起收掉，
     * 否则金刚区没了、圆点还在屏幕上杵着一条空带。
     */
    private static View widenToDots(View row) {
        View target = row;
        if (row.getHeight() <= 0) return row;
        for (int i = 0; i < 5; i++) {
            ViewParent p = target.getParent();
            if (!(p instanceof View)) break;
            View pv = (View) p;
            int ph = pv.getHeight(), pw = pv.getWidth();
            if (ph <= 0 || ph > target.getHeight() * 150 / 100) break;
            if (pw > 0 && target.getWidth() > 0 && pw > target.getWidth() + 60) break;
            target = pv;
        }
        return target;
    }

    private static void countCats(View v, Set<String> found, int depth) {
        if (v == null || depth > 14 || found.size() >= 8) return;
        synchronized (LOCK) {
            String t = anchors.get(v);
            if (t != null && SEARCH_CAT_LABELS.contains(t)) found.add(t);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) countCats(g.getChildAt(i), found, depth + 1);
        }
    }

    /** 上溯到工具格子：连续满足 宽≤300 且 高≤300 的最外层祖先，且其父为宽≥600 的容器。 */
    private static View ascendSmallCell(View v) {
        View cur = v, best = null;
        for (int i = 0; i < 8 && cur.getParent() instanceof View; i++) {
            View p = (View) cur.getParent();
            int w = p.getWidth(), h = p.getHeight();
            if (w <= 0 || h <= 0 || w > 300 || h > 300) break;
            best = p;
            cur = p;
        }
        if (best == null) return null;
        ViewParent pp = best.getParent();
        if (!(pp instanceof View) || ((View) pp).getWidth() < 600) return null;
        return best;
    }

    // ══════════════════════════════════════════════════════════ 标签栏（原生）

    /**
     * 标签栏处理，同时兼任「这页是不是高德首页」的判据（返回值）。
     *
     * 三件事：
     *  1) 按配置藏掉不要的标签；
     *  2) 剩下的标签平分整条栏（weight=1，width=0）；
     *  3) 把每个可见槽位的胶囊背景层 tab_bg_layer 撑成槽位本身 —— 见下方注释。
     *
     * 返回 true = 认得出这是一棵首页树（有标签栏且真的在显示）。
     */
    private static boolean applyTabs(View root) {
        try {
            ViewGroup row = tabRow;
            if (row == null || row.getParent() == null) {
                row = findTabRow(root, 0);
                tabRow = row;
            }
            if (row == null) return false;
            // 认页面：标签栏必须真的挂在窗口上且处在显示状态。
            // 路线页 / 搜索页 / 导航页要么没有这条栏，要么它不在树上 —— 一律判 false。
            if (!row.isShown()) return false;
            if (row.getChildCount() <= 1) return false;

            int visible = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                String name = tabName(c);
                if (name == null) { visible++; continue; }
                if (!cfgOn(Config.K_TAB_PREFIX + name)) {
                    if (c.getVisibility() != View.GONE) {
                        c.setVisibility(View.GONE);
                        hiddenCells.add(new java.lang.ref.WeakReference<>(c));
                        if (loggedOnce.add("tab_" + name)) {
                            H.log(Log.INFO, MainHook.TAG, "TAB-HIDE " + name);
                        }
                    }
                } else {
                    if (c.getVisibility() == View.GONE) c.setVisibility(View.VISIBLE);
                    visible++;
                }
            }
            if (visible == 0) return true;   // 全关也不收条，避免只剩 0 宽的残条

            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (c.getVisibility() == View.GONE) continue;
                if (!(c.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams)) continue;
                android.widget.LinearLayout.LayoutParams lp =
                        (android.widget.LinearLayout.LayoutParams) c.getLayoutParams();
                if (lp.width != 0 || lp.weight != 1f) {
                    lp.width = 0;
                    lp.weight = 1f;
                    c.setLayoutParams(lp);
                }
            }

            // ── 标签栏补满 ──────────────────────────────────────────────
            // 藏标签只改了槽位宽（weight 平分），但「选中胶囊」那一层
            // (id=tab_bg_layer) 的宽度是高德自己按 totalWidth / 标签总数 算死的，
            // 它不知道我们藏了几个。于是 5 个标签变 2 个之后，胶囊还是 201px，
            // 两个标签悬在 1/4、3/4 的位置，中间和两头全是空的 —— 用户截图里
            // 「首页/我的不占满整个悬浮栏」就是这个。
            // 把胶囊层撑成它所在槽位的宽度，等价于高德自己按 2 个标签排出来的样子。
            int rowW = row.getWidth();
            if (rowW <= 0) {
                ViewParent rp = row.getParent();
                rowW = rp instanceof View ? ((View) rp).getWidth() : 0;
            }
            if (rowW <= 0) rowW = 1080;
            final int slot = Math.max(1, rowW / visible);
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (c.getVisibility() == View.GONE) continue;
                if (!(c instanceof ViewGroup)) continue;
                View bg = childByIdName((ViewGroup) c, "tab_bg_layer");
                if (bg == null) continue;
                ViewGroup.LayoutParams blp = bg.getLayoutParams();
                if (blp != null && blp.width != slot) {
                    blp.width = slot;
                    bg.setLayoutParams(blp);
                    if (loggedOnce.add("tabfill_" + System.identityHashCode(c))) {
                        H.log(Log.INFO, MainHook.TAG, "TAB-FILL slot=" + slot
                                + " visible=" + visible + " rowW=" + rowW);
                    }
                }
            }
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /** 按 android:id 名字找直接子 view（id 在对方包里，只能用 getResourceName 比对） */
    private static View childByIdName(ViewGroup g, String idName) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            try {
                int rid = c.getId();
                if (rid == View.NO_ID || rid == 0) continue;
                String n = c.getResources().getResourceName(rid);
                if (n != null && n.endsWith(":id/" + idName)) return c;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static ViewGroup findTabRow(View v, int depth) {
        if (v == null || depth > 26) return null;
        if (v.getClass().getName().endsWith(".TabItemLayoutV2")) {
            ViewParent p = v.getParent();
            if (p instanceof ViewGroup) return (ViewGroup) p;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ViewGroup r = findTabRow(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String tabName(View node) {
        if (node instanceof TextView) {
            CharSequence cs = ((TextView) node).getText();
            if (cs != null && TAB_LABELS.contains(cs.toString())) return cs.toString();
        }
        if (node instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) node;
            for (int i = 0; i < g.getChildCount(); i++) {
                String n = tabName(g.getChildAt(i));
                if (n != null) return n;
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════ 宿主 item 收缩

    /**
     * 从锚点上溯到 AJX 列表 item（父级是 AjxList2/RecyclerView 的那个节点）并收缩。
     * 三重保险：
     *  - 爬升路径穿过受保护节点（工具格/行/宫格）→ 立即放弃（这是"宫格整体消失"的解药）；
     *  - item 高 > 55% 屏高 → 放弃（页面级容器，误伤即整页消失）；
     *  - 找不到列表宿主 → 放弃，留在常驻索引里等下一轮挂载后再试。
     * 绝不盲目 ascendWideRow —— 那是"我的页整体被关闭"的元凶。
     */
    private static void collapseHost(View anchor, String rule) {
        View cur = anchor;
        View item = null;
        boolean touchedProtected = protectedNodes.containsKey(cur);
        for (int i = 0; i < 40 && cur.getParent() instanceof View; i++) {
            View parent = (View) cur.getParent();
            if (protectedNodes.containsKey(parent)) touchedProtected = true;
            if (isList(parent)) { item = cur; break; }
            cur = parent;
        }
        if (item != null) {
            View list = (View) item.getParent();
            dumpListOnce(list);
            hookListAdapter(list);
        }
        if (item == null) {                             // 还没挂载 → 下一轮重试
            if (loggedOnce.add("nolist_" + rule)) {
                H.log(Log.INFO, MainHook.TAG, "NO-LIST " + rule
                        + " attached=" + (anchor.getParent() != null)
                        + " deepest=" + shortName(cur) + " " + geom(cur));
            }
            return;
        }
        if (touchedProtected) {
            if (loggedOnce.add("skip_" + rule)) {
                H.log(Log.INFO, MainHook.TAG, "SKIP(protected) " + rule);
            }
            return;
        }
        if (hiddenWhy.containsKey(item)) return;        // 幂等

        int screenH = screenOf(item);
        int h = item.getHeight();
        if (h > screenH * 55 / 100) {
            if (loggedOnce.add("toolarge_" + rule)) {
                H.log(Log.INFO, MainHook.TAG, "SKIP(toolarge " + h + ") " + rule);
            }
            return;
        }
        if (item.getVisibility() != View.GONE) item.setVisibility(View.GONE);
        hiddenItems.add(new java.lang.ref.WeakReference<>(item));
        hiddenWhy.put(item, rule);
        H.log(Log.INFO, MainHook.TAG, "COLLAPSE " + rule + " " + shortName(item) + " " + geom(item));
    }

    /**
     * 取证：把一个 AJX 列表的适配器结构打出来，用来找"信息流条目"的原始数据，
     * 目标是以后能在数据层直接过滤，而不是等 AJX 把卡片渲染出来再删。
     */
    private static final Set<Integer> dumpedLists = new HashSet<>();

    private static void dumpListOnce(View list) {
        if (!Config.debugLog() || list == null) return;
        if (!dumpedLists.add(System.identityHashCode(list))) return;
        try {
            H.log(Log.INFO, MainHook.TAG, "LIST " + list.getClass().getName() + " " + geom(list));
            Method getAdapter = null;
            for (Class<?> k = list.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                try { getAdapter = k.getDeclaredMethod("getAdapter"); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (getAdapter == null) { H.log(Log.INFO, MainHook.TAG, "LIST no getAdapter"); return; }
            getAdapter.setAccessible(true);
            Object ad = getAdapter.invoke(list);
            if (ad == null) { H.log(Log.INFO, MainHook.TAG, "LIST adapter=null"); return; }
            H.log(Log.INFO, MainHook.TAG, "LIST adapter=" + ad.getClass().getName());
            for (Class<?> k = ad.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (f.getType().isPrimitive() || f.getType().isArray()) continue;
                    H.log(Log.INFO, MainHook.TAG, "LIST  field " + k.getSimpleName()
                            + "." + f.getName() + " : " + f.getType().getName());
                }
            }
            // 数据入口侦察：AJX 列表的数据由 JS 侧灌入，native 侧只可能是
            // BaseList2Adapter / 其子类上的某个 setData 类方法。把方法表打出来定位它。
            for (Class<?> k = ad.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                String sn = k.getSimpleName();
                if (sn.equals("Adapter") || sn.startsWith("RecyclerView")) break;
                for (Method m : k.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 0 || ps.length > 4) continue;
                    StringBuilder sb = new StringBuilder();
                    for (Class<?> x : ps) sb.append(x.getSimpleName()).append(',');
                    H.log(Log.INFO, MainHook.TAG, "LIST  method " + sn + "."
                            + m.getName() + "(" + sb + ") -> "
                            + m.getReturnType().getSimpleName());
                }
            }
            try {
                Object n = ad.getClass().getMethod("getItemCount").invoke(ad);
                H.log(Log.INFO, MainHook.TAG, "LIST itemCount=" + n);
            } catch (Throwable ignored) {}
            // 数据层侦察：把适配器实例上每个非原始字段的"值"也打出来，
            // 找出信息流条目的原始数据容器（目标是从源头过滤，而不是渲染后再删）。
            for (Class<?> k = ad.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(ad);
                        String s = describe(val);
                        if (s == null) continue;
                        H.log(Log.INFO, MainHook.TAG, "LIST  value " + k.getSimpleName()
                                + "." + f.getName() + " = " + s);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "LIST dump err " + t);
        }
    }

    /** 把字段值描述成短字符串；对集合额外报告元素个数与首元素类型/内容 */
    private static String describe(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) v;
                StringBuilder sb = new StringBuilder("Map(size=" + m.size() + ")");
                int i = 0;
                for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    if (i++ >= 3) break;
                    sb.append(" [").append(trunc(String.valueOf(e.getKey())))
                      .append(" -> ").append(describeBrief(e.getValue())).append(']');
                }
                return sb.toString();
            }
            if (v instanceof java.util.List) {
                java.util.List<?> l = (java.util.List<?>) v;
                StringBuilder sb = new StringBuilder("List(size=" + l.size() + ")");
                for (int i = 0; i < Math.min(3, l.size()); i++) {
                    sb.append(" <").append(describeBrief(l.get(i))).append('>');
                }
                return sb.toString();
            }
            String cn = v.getClass().getName();
            if (cn.startsWith("java.lang") || cn.startsWith("android.")) return null;
            return trunc(cn + " :: " + String.valueOf(v));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describeBrief(Object o) {
        if (o == null) return "null";
        return trunc(o.getClass().getSimpleName() + "=" + String.valueOf(o));
    }

    private static String trunc(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 90 ? s.substring(0, 90) + "~" : s;
    }

    /**
     * 结构识别「推荐频道栏」：一个 HorizontalScroller，其内容容器里全是矮而窄的
     * chip（实测 84~168 宽 × 110 高，至少 3 个）。
     * 依据来自实测：同页面"运营位横向滚动条"的内容格是 247x210，
     * 高度 210 直接落在 chip 高度带之外，所以不会误伤。
     */
    private static View findChannelBar(View v, int depth) {
        if (v == null || depth > 26) return null;
        if (v.getClass().getName().endsWith("HorizontalScroller") && v instanceof ViewGroup) {
            ViewGroup sc = (ViewGroup) v;
            if (sc.getChildCount() >= 1 && sc.getChildAt(0) instanceof ViewGroup) {
                ViewGroup content = (ViewGroup) sc.getChildAt(0);
                int chips = 0;
                boolean ok = true;
                for (int i = 0; i < content.getChildCount(); i++) {
                    View c = content.getChildAt(i);
                    int h = c.getHeight(), w = c.getWidth();
                    if (h <= 0 || w <= 0) continue;
                    if (h >= 60 && h <= 170 && w <= 250) { chips++; continue; }
                    // 下划线指示条之类的装饰（实测 53x11）直接忽略，
                    // 只有"明显不是 chip"的大块内容才否决整条
                    if (h > 200 || w > 300) { ok = false; break; }
                }
                if (ok && chips >= 3) {
                    H.log(Log.INFO, MainHook.TAG, "CHANNEL-BAR found chips=" + chips
                            + " " + geom(sc));
                    return v;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findChannelBar(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * 根治向：把 AJX 列表适配器的 onBindViewHolder 挂上，在**同一个布局帧内**判定并隐藏。
     *
     * 为什么这是能做到的最接近"从源头阻断"的做法：
     *  - 实测 AJX 列表适配器是 `...ajx3.widget.view.list.a`（混淆），字段只有
     *    IAjxContext / zr / nv0 —— **native 侧根本不存在条目数据列表**，
     *    数据在 JS 引擎里，Java 层没有可过滤的数据结构；
     *  - 但它的方法表是 section 化的（getSectionByPosition → ListSection、
     *    onBindViewHolder(v,int)），所以可以在**条目绑定完成的瞬间**
     *    （仍在本次 layout pass 内、这一帧还没绘制）就判定并 GONE。
     *  - 效果：卡片**一次都不会被画出来**，也不再依赖"下一帧再扫全树"。
     *
     * 只挂 App 自己的适配器类（不是框架超类），符合"禁止 hook 框架通用回调"的红线。
     */
    private static void hookListAdapter(View list) {
        try {
            Method getAdapter = null;
            for (Class<?> k = list.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                try { getAdapter = k.getDeclaredMethod("getAdapter"); break; }
                catch (NoSuchMethodException ignored) {}
            }
            if (getAdapter == null) return;
            getAdapter.setAccessible(true);
            Object ad = getAdapter.invoke(list);
            if (ad == null) return;
            final Class<?> ac = ad.getClass();
            if (!bindHooked.add(ac.getName())) return;

            Method bind = null;
            for (Method m : ac.getDeclaredMethods()) {
                if (m.getName().equals("onBindViewHolder") && m.getParameterTypes().length == 2) {
                    bind = m;
                    break;
                }
            }
            if (bind == null) {
                H.log(Log.INFO, MainHook.TAG, "BIND hook miss " + ac.getName());
                return;
            }
            H.module.hook(bind).setId("amapenhancer_bind")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                        @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                Object vh = chain.getArg(0);
                                if (vh != null) {
                                    View item = (View) vh.getClass().getField("itemView").get(vh);
                                    if (item != null) onItemBound(item);
                                }
                            } catch (Throwable ignored) {}
                            return r;
                        }
                    });
            H.log(Log.INFO, MainHook.TAG, "BIND hook on " + ac.getName());
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "BIND hook fail " + t);
        }
    }

    /**
     * 条目刚绑定完：在这一个小子树里找我们登记过的锚点文本，
     * 命中「已关闭」的规则就立刻把整个 item 收掉。
     * 含工具格的 item 一律放过（那是"宫格整体消失"的老坑）。
     *
     * 两个坑都在这儿补掉：
     *  1) 向下多翻几次冒出来的新帖，`onBindViewHolder` 返回时它的文字往往**还没写进去**
     *     （AJX 先绑视图再灌属性）→ 当场判定会判成"放行"。所以额外挂一个
     *     onPreDraw：每帧绘制前再判一次，文字一到就在**同一帧**被盖掉。
     *  2) 帖子标题是用户自由文案（"受累已回，说点 xhs 上没有的实话"），
     *     任何锚点词都匹配不到 → 靠**结构**兜底：半屏宽 + 高卡的 item 就是内容流帖子卡。
     */
    private static void onItemBound(final View item) {
        if (!homeCtx) return;          // 非首页列表（路线备选卡等）一概不判
        if (hiddenWhy.containsKey(item)) {
            // AJX 每次重绑都会把 itemView 重新 setVisibility(VISIBLE)，
            // 但**保留我们写进去的 height=0** —— 于是那张卡被画成"半张、文字被切"。
            // 这里就在重绑的同一次调用里按回去，不留任何一帧的残缺状态。
            reapplyHidden(item);
            return;
        }
        String rule = ruleForItem(item);
        if (rule != null) {
            // 同一条护栏：chips 只摘那一排，不要把整张 item 收掉
            if (chipsRowOnlyInItem(item, rule)) return;
            hideItem(item, rule);
            return;
        }
        // 当场没判出来（文字还没到）→ 挂 preDraw 再判，最多 12 帧
        try {
            item.getViewTreeObserver().addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                private int n;
                @Override public boolean onPreDraw() {
                    try {
                        if (hiddenWhy.containsKey(item)) {
                            item.getViewTreeObserver().removeOnPreDrawListener(this);
                            return true;
                        }
                        String r = ruleForItem(item);
                        if (r != null) {
                            hideItem(item, r);
                            item.getViewTreeObserver().removeOnPreDrawListener(this);
                            return true;
                        }
                        if (++n > 12) item.getViewTreeObserver().removeOnPreDrawListener(this);
                    } catch (Throwable ignored) {}
                    return true;
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 首页那个运营推广卡槽位的**结构**判据。
     *
     * 为什么最后落到结构上：这个槽位是高德的轮播位，标题一天能换好几张 ——
     * 「去幸福路步行街 / 有座不拥挤 / 打车」→「帮我预约车辆 / 通勤高峰… / AI叫车」
     * →「预约顺风车，一口价超便宜 / 去预约」… 盯文案永远追不上。
     *
     * 但它三张卡的**版式一模一样**（真机 uiautomator 实测）：
     *   标题  [185,2093] w≈580~617 h=55
     *   副标  [185,2161] w=150~179 h=39   （两个 chip）
     *   按钮  [845,2130] w=112 h=47       （右侧药丸）
     * 也就是：**整屏宽 + 高 180~320 + 一个长标题 + 一个短按钮**。
     *
     * 两条护栏防止误伤：
     *  · 宫格 item 是 1080x508，高度直接出界；
     *  · 「我的」页那些整屏宽的行（车辆服务 / 达人任务…）里必有 MY_* 文案，一律排除。
     */
    private static boolean isPromoSlotItem(View item) {
        int w = item.getWidth(), h = item.getHeight();
        if (w <= 0 || h <= 0) return false;
        if (h < 180 || h > 320) return false;
        View root = item;
        ViewParent p;
        while ((p = root.getParent()) instanceof View) root = (View) p;
        int sw = root.getWidth() > 0 ? root.getWidth() : 1080;
        if (w < sw * 90 / 100) return false;
        // 必须本身就是列表 item（父级是 AjxList2 / RecyclerView），不是卡片里的某层内胆
        ViewParent pp = item.getParent();
        if (!(pp instanceof View) || !isList((View) pp)) return false;

        boolean[] f = new boolean[3];       // [0]=长标题 [1]=短按钮 [2]=我的页文案
        scanPromo(item, f, 0);
        return f[0] && f[1] && !f[2];
    }

    /**
     * 扫一遍 item 子树，收集三个标志位。
     *
     * 注意：这里**直接读视图上的文案**（AJX 的 Label 把文字挂在 contentDescription 上），
     * 不查 anchors 索引 —— 因为索引里只有"被规则认领过"的文案，
     * 而这张卡的标题本来就是新面孔，等它进索引等于永远等不到。
     */
    private static void scanPromo(View v, boolean[] f, int depth) {
        if (v == null || depth > 16) return;
        String t = textOfView(v);
        if (t != null) {
            if (t.length() >= 5) f[0] = true;
            else if (t.length() >= 2 && !HOME_CHIPS_LABELS.contains(t)) f[1] = true;
            if (MY_ORDER.contains(t) || MY_SERVICE.contains(t) || MY_TASK.contains(t)
                    || MY_PROMO.contains(t) || MY_QUALITY_WORDS.contains(t)) f[2] = true;
            // 天气 / 限行卡也是整屏宽、也有长文案 + 短文案，形状撞得上 —— 单独排除掉
            if (isTempLabel(t) || t.contains("天气") || t.contains("限行")
                    || t.contains("气温") || t.contains("降雨")) f[2] = true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scanPromo(g.getChildAt(i), f, depth + 1);
        }
    }

    /** 取一个视图当前显示的短文案（AJX Label 走 contentDescription，原生走 TextView） */
    private static String textOfView(View v) {
        try {
            CharSequence cd = v.getContentDescription();
            if (cd != null && cd.length() > 0 && cd.length() <= 24) return cd.toString();
            if (v instanceof TextView) {
                CharSequence tx = ((TextView) v).getText();
                if (tx != null && tx.length() > 0 && tx.length() <= 24) return tx.toString();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 布局稳定后再找一遍运营推广卡（绑定那一刻几何还是 0x0，判不了） */
    private static void applyPromoSlot(View decor) {
        if (cfgOn(Config.K_QUICK_CARD)) return;
        View hit = findPromoItem(decor, 0);
        if (hit == null || hiddenWhy.containsKey(hit)) return;
        hideItem(hit, Config.K_QUICK_CARD);
        H.log(Log.INFO, MainHook.TAG, "PROMO-HIDE " + shortName(hit) + " " + geom(hit));
    }

    /** 先序查找：命中的是**最外层**那个符合条件的列表 item */
    private static View findPromoItem(View v, int depth) {
        if (v == null || depth > 24) return null;
        if (isPromoSlotItem(v)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findPromoItem(g.getChildAt(i), depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 判定一个 item 该不该收：先锚点文本，再不济按结构认「内容流帖子卡」 */
    private static String ruleForItem(View item) {
        // 0) 结构优先：首页那个「运营推广卡」槽位
        if (isPromoSlotItem(item)) return Config.K_QUICK_CARD;

        List<View> stack = new ArrayList<>();
        stack.add(item);
        String hitRule = null;
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 400) {
            View v = stack.remove(stack.size() - 1);
            String text;
            synchronized (LOCK) { text = anchors.get(v); }
            if (text != null) {
                if (TOOL_ALIAS.containsKey(text)) return null;   // 工具格所在 item 绝不动
                if (hitRule == null) {
                    String rule = ruleFor(text, v);
                    if (rule != null && !cfgOn(rule)) hitRule = rule;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
        if (hitRule != null) return hitRule;

        // —— 结构兜底：内容流帖子卡 ——
        // 实测：双列布局，每张卡宽 ≈487~514（约屏幕宽的 45%），高 640~990。
        // 天气卡 487x518、语音包 493x252 都够不着高度线。
        // 注意：这里必须用**屏幕宽**，不能用 screenOf()（那个给的是根高度）。
        if (!cfgOn(Config.K_FEED_CONTENT)) {
            int w = item.getWidth(), h = item.getHeight();
            if (w > 0 && h > 0) {
                View root = item;
                ViewParent p;
                while ((p = root.getParent()) instanceof View) root = (View) p;
                int sw = root.getWidth() > 0 ? root.getWidth() : 1080;
                int sh = root.getHeight() > 0 ? root.getHeight() : 2400;
                if (w >= sw * 40 / 100 && w <= sw * 52 / 100 && h >= sh * 25 / 100) {
                    return Config.K_FEED_CONTENT + "#shape";
                }
            }
        }
        return null;
    }

    /**
     * 「设置家 / 设置单位 / 常去地点」那一排的专用处理：
     * 只把**这一排**收掉（最小、且子树里挂着 >=2 个 chips 文案的祖先），
     * 绝不收它所在的整个列表 item。返回 true = 已经处理完，调用方别再动 item。
     */
    private static boolean hideChipsRowOnly(View anchor, String rule) {
        if (!Config.K_HOME_CHIPS.equals(rule)) return false;
        View row = smallestWithLabels(anchor, HOME_CHIPS_LABELS, 2);
        if (row == null || row == anchor) return false;
        if (hiddenWhy.containsKey(row)) return true;
        if (row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp != null && lp.height != 0 && row.getHeight() > 0) {
            lp.height = 0;
            row.setLayoutParams(lp);
        }
        hiddenItems.add(new java.lang.ref.WeakReference<>(row));
        hiddenWhy.put(row, rule);
        H.log(Log.INFO, MainHook.TAG, "CHIPS-HIDE " + shortName(row) + " " + geom(row));
        return true;
    }

    /** 在 item 子树里找一个 chips 锚点，按上面那条护栏处理 */
    private static boolean chipsRowOnlyInItem(View item, String rule) {
        if (!Config.K_HOME_CHIPS.equals(rule)) return false;
        List<View> stack = new ArrayList<>();
        stack.add(item);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 400) {
            View v = stack.remove(stack.size() - 1);
            String text;
            synchronized (LOCK) { text = anchors.get(v); }
            if (text != null && HOME_CHIPS_LABELS.contains(text) && hideChipsRowOnly(v, rule)) return true;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
        return false;
    }

    /** 从锚点往上找最小的、子树里挂着 >=min 个指定文案的祖先 */
    private static View smallestWithLabels(View anchor, Set<String> labels, int min) {
        View cur = anchor;
        for (int i = 0; i < 8 && cur.getParent() instanceof View; i++) {
            View p = (View) cur.getParent();
            Set<String> found = new HashSet<>();
            countLabels(p, labels, found, 0);
            if (found.size() >= min) return p;
            cur = p;
        }
        return null;
    }

    private static void countLabels(View v, Set<String> labels, Set<String> found, int depth) {
        if (v == null || depth > 14 || found.size() >= 6) return;
        synchronized (LOCK) {
            String t = anchors.get(v);
            if (t != null && labels.contains(t)) found.add(t);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) countLabels(g.getChildAt(i), labels, found, depth + 1);
        }
    }

    /** 把一个已经判定要收的节点重新按成 GONE + height 0（AJX 复活它时立刻叫用） */
    private static void reapplyHidden(View item) {
        // 只 GONE，**不再写 lp.height = 0**。
        // 真机实证：AJX 会在重绑时把 itemView 重新 setVisibility(VISIBLE)，
        // 但保留我们写的 height=0 —— 那张卡就变成"半张、文字被切"的残片
        // （用户截图里那张「去幸福路步行街」）。GONE 的节点根本不会绘制，
        // 从机制上就不可能产生这种残片；留白比残片好得多。
        if (item.getVisibility() != View.GONE) item.setVisibility(View.GONE);
    }

    /** 真正把条目收掉 */
    private static void hideItem(View item, String rule) {
        if (hiddenWhy.containsKey(item)) return;
        if (item.getVisibility() != View.GONE) item.setVisibility(View.GONE);
        // 不写 height：写了会留下"半张卡"的残片，见 reapplyHidden 注释
        hiddenItems.add(new java.lang.ref.WeakReference<>(item));
        hiddenWhy.put(item, rule);
        if (loggedOnce.add("bind_" + System.identityHashCode(item))) {
            H.log(Log.INFO, MainHook.TAG, "BIND-HIDE " + rule + " " + geom(item));
        }
    }

    private static boolean isList(View v) {
        for (Class<?> k = v.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.equals("androidx.recyclerview.widget.RecyclerView")
                    || n.endsWith(".RecyclerView") || n.endsWith(".RecyclerViewV2")
                    || n.contains("AjxList")) {
                return true;
            }
        }
        return false;
    }

    private static int screenOf(View v) {
        View root = v;
        ViewParent p;
        while ((p = root.getParent()) instanceof View) root = (View) p;
        return root.getHeight() > 0 ? root.getHeight() : 2400;
    }

    // ══════════════════════════════════════════════════════════ 文本归类

    /**
     * 非工具类锚点 → 配置键。null = 不受本模块管辖。
     * 工具格在调用前已经分流，所以推荐区的子串锚点永远碰不到工具格。
     */
    private static String ruleFor(String t, View v) {
        if (HOME_CHIPS_LABELS.contains(t)) return Config.K_HOME_CHIPS;
        if (isQuickCardAnchor(v, t)) return Config.K_QUICK_CARD;
        if (isTaxiCta(t) && !isToolGridCell(v)) return Config.K_QUICK_CARD;

        if (MY_ORDER.contains(t)) return Config.K_MY_ORDER_ROW;
        if (MY_SERVICE.contains(t)) return Config.K_MY_SERVICE_ROW;
        if (MY_TASK.contains(t)) return Config.K_MY_TASK;
        if (MY_PROMO.contains(t)) return Config.K_MY_PROMO_ROW;
        if (t.equals("猜你喜欢")) return Config.K_MY_GUESS;
        if (MY_QUALITY_WORDS.contains(t)) return Config.K_MY_QUALITY;

        if (FEED_FILTER_LABELS.contains(t)) {
            // 「推荐频道栏」的 chip 实测是 84x110 / 168x110；
            // 达人卡里的粉丝/关注计数是小矮格 85x42 —— 用高度把它们区分开，
            // 否则要么误伤达人卡，要么（按宽度卡）把 84px 宽的频道 chip 漏掉。
            View cell = ascendSmallCell(v);
            return (cell != null && cell.getHeight() >= 80) ? Config.K_FEED_FILTER : null;
        }
        if (isTempLabel(t)) return Config.K_FEED_WEATHER;
        if (isDistanceLabel(t)) return Config.K_FEED_DISTANCE;
        if (containsAny(t, ANCHOR_AI)) return Config.K_FEED_AI;
        if (containsAny(t, ANCHOR_WEATHER)) return Config.K_FEED_WEATHER;
        if (containsAny(t, ANCHOR_SCENIC)) return Config.K_FEED_SCENIC;
        if (containsAny(t, ANCHOR_POSTS)) return Config.K_FEED_POSTS;
        if (containsAny(t, ANCHOR_RANK)) return Config.K_FEED_RANK;
        if (containsAny(t, ANCHOR_CONTENT)) return Config.K_FEED_CONTENT;
        return null;
    }

    /**
     * 是不是首页那张「去XX」快捷打车卡的标题。
     *
     * 判据（两条都要满足）：
     *  1) 文案以「去」开头、长度 2~16、不是工具键也不是 chips 键；
     *  2) 往上 8 层之内能找到挂着 QUICK_CARD_HINTS 里任意一条旁证的小块。
     * 第 2 条是关键护栏 —— 信息流里的「去扫描」之类没有旁证，不会被误判。
     */
    private static boolean isQuickCardAnchor(View v, String t) {
        if (isSelfEvidentQuickCard(t)) return true;
        return looksLikeQuickCardTitle(t) && smallestWithLabels(v, QUICK_CARD_HINTS, 1) != null;
    }

    /**
     * 打车推广卡的**行动按钮**文案。
     *
     * 为什么最后改成盯按钮而不是盯标题：那个槽位是轮播位，标题一天能换好几张
     * （「去幸福路步行街」→「帮我预约车辆」→「帮我叫一辆空气清新的车」…），
     * 但按钮永远是"打车 / 去打车 / AI叫车 / 立即叫车"这一族。盯住按钮才拦得稳。
     * 宫格里的「打车」是工具键，在调用前就分流掉了，不会走到这里。
     */
    private static boolean isTaxiCta(String t) {
        if (t == null || t.length() < 2 || t.length() > 8) return false;
        if (TOOL_ALIAS.containsKey(t)) return false;
        return t.contains("打车") || t.contains("叫车");
    }

    /** 这个锚点是不是落在工具宫格的格子里（是的话就轮不到推广卡规则） */
    private static boolean isToolGridCell(View v) {
        View cell = ascendSmallCell(v);
        return cell != null && looksLikeToolCell(cell);
    }

    /** 文案自己就能说明它是那张卡（长且独特，contains 也不会误伤） */
    private static boolean isSelfEvidentQuickCard(String t) {
        if (t == null || t.length() < 3) return false;
        for (String s : QUICK_CARD_SELF) if (t.contains(s)) return true;
        return false;
    }

    /** 只按文案形状判：以「去」开头、长度 2~16、既不是工具键也不是 chips 键 */
    private static boolean looksLikeQuickCardTitle(String t) {
        if (t == null || t.length() < 2 || t.length() > 16) return false;
        if (t.charAt(0) != '去') return false;
        return !HOME_CHIPS_LABELS.contains(t) && !TOOL_ALIAS.containsKey(t);
    }

    /** 归一：去掉首尾空白与 `- · | ——` 等装饰字符，避免精确匹配被装饰字符废掉。 */
    private static String norm(String s) {
        int a = 0, b = s.length();
        while (a < b && isDecor(s.charAt(a))) a++;
        while (b > a && isDecor(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isDecor(char c) {
        return c == ' ' || c == '\u3000' || c == '-' || c == '\u2014' || c == '\u2013'
                || c == '|' || c == '·' || c == '\u2022' || c == ',' || c == '\uff0c';
    }

    /** 天气卡的温度标签：21° / 18°C —— 结构上足够独特，可安全用于定位天气卡。 */
    private static boolean isTempLabel(String t) {
        return t.length() <= 6 && t.matches("\\d+\\s*°.*");
    }

    /**
     * 内容卡的距离标签。实测有两种：
     *   1.1公里 / 63公里 / 5km      —— 旅行帖、探店帖
     *   597米                        —— 同城卡（**漏了这个会剩一张卡藏不掉**）
     */
    private static boolean isDistanceLabel(String t) {
        return t.length() <= 8 && t.matches("\\d+(\\.\\d+)?\\s*(米|公里|km|KM|Km)");
    }

    private static boolean containsAny(String text, String[] anchors) {
        for (String a : anchors) if (text.contains(a)) return true;
        return false;
    }

    // ══════════════════════════════════════════════════════════ 配置快照

    private static void refreshCfg() {
        try {
            Map<String, ?> all = Config.prefs().getAll();
            Map<String, Boolean> m = new HashMap<>();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Boolean) m.put(e.getKey(), (Boolean) v);
            }
            cfg = m;
        } catch (Throwable t) {
            // 读不到就沿用上一次快照（默认全部显示）
        }
        cfgAt = System.currentTimeMillis();
    }

    /** 快照读：默认 true = 显示（失效安全）。避免每个锚点都走一次 RemotePreferences IPC。 */
    private static boolean cfgOn(String key) {
        if (cfg.isEmpty() || System.currentTimeMillis() - cfgAt > 1500) refreshCfg();
        Boolean b = cfg.get(key);
        return b == null || b;
    }

    /** 扩展工具页：缺省即隐藏（与其它键的"缺省即显示"相反，因为它是额外推荐位） */
    private static boolean cfgExtraPage() {
        if (cfg.isEmpty() || System.currentTimeMillis() - cfgAt > 1500) refreshCfg();
        Boolean b = cfg.get(Config.K_TOOL_EXTRA);
        return b != null && b;
    }

    // ══════════════════════════════════════════════════════════ 诊断

    private static void trace(View v, String t) {
        if (!Config.debugLog() || dumpBudget <= 0 || !dumped.add("L:" + t)) return;
        dumpBudget--;
        final View fv = v;
        final String ft = t;
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (fv.getParent() != null) {
                        H.log(Log.INFO, MainHook.TAG, "LBL " + ft + " :: " + geom(fv)
                                + " | " + chainOf(fv));
                    }
                } catch (Throwable ignored) {}
            }
        }, 700);
    }

    private static String chainOf(View v) {
        StringBuilder sb = new StringBuilder();
        View cur = v;
        for (int i = 0; i < 8 && cur != null; i++) {
            sb.append(shortName(cur)).append('(').append(cur.getWidth()).append('x')
              .append(cur.getHeight()).append(')');
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            if (cur != null) sb.append(" > ");
        }
        return sb.toString();
    }

    private static String shortName(View v) {
        String n = v.getClass().getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }

    private static String geom(View v) {
        return "[" + v.getLeft() + "," + v.getTop() + " " + v.getWidth() + "x" + v.getHeight() + "]";
    }
}
