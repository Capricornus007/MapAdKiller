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
        TOOL_ALIAS.put("收藏夹", "更多工具");
    }

    /**
     * 工具宫格"扩展页"的文案：首页宫格往下还藏着一排推荐工具
     * （实测 景点游玩 / 离线地图 / 通行费助手 / 收藏夹 / 旅游度假）。
     * 它们不是用户勾选的那 10 个工具，单独由 Config.K_TOOL_EXTRA 控制，
     * **默认隐藏** —— 否则首页往下拉就会冒出一整排没被关掉的格子。
     */
    private static final Set<String> TOOL_EXTRA_LABELS = new HashSet<>(Arrays.asList(
            "景点游玩", "离线地图", "通行费助手", "收藏夹", "旅游度假"));

    /** 推荐频道栏 —— 只在"宽格子"里算数，避免误伤达人卡里的粉丝/关注计数（85px 窄格）。 */
    private static final Set<String> FEED_FILTER_LABELS =
            new HashSet<>(Arrays.asList("关注", "附近", "美食", "周末出游", "休闲玩乐"));

    private static final Set<String> HOME_CHIPS_LABELS =
            new HashSet<>(Arrays.asList("设置家", "设置单位", "常去地点"));

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
                            channelDone = false;
                            channelSince = System.currentTimeMillis();
                            scheduled = false;
                            // 收敛尾巴：布局稳定前后各压几轮，然后停手（不再 600ms 常驻轮询）
                            for (long d : new long[]{100, 350, 800, 1500, 2600, 4200}) {
                                MAIN.postDelayed(APPLY, d);
                            }
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
        if (toolKey == null && rule == null) return;

        synchronized (LOCK) { anchors.put(v, t); }
        trace(v, t);

        String key = toolKey;
        if (key == null) {
            View cell = ascendSmallCell(v);
            String ck = cell == null ? null : cellKey.get(cell);
            if (ck != null) key = ck;   // 轮播格的其它文案复用本格已登记的键
        }
        if (key != null && !cfgOn(Config.K_TOOL_PREFIX + key)) {
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
        } else if (rule != null && !cfgOn(rule)) {
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

        applyTabs(decor);

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

        List<View> views = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<View, String> e : anchors.entrySet()) {
                views.add(e.getKey());
                texts.add(e.getValue());
            }
        }

        applyTools(views, texts);

        for (int i = 0; i < views.size(); i++) {
            String t = texts.get(i);
            if (TOOL_ALIAS.containsKey(t)) continue;
            String rule = ruleFor(t, views.get(i));
            if (rule == null || cfgOn(rule)) continue;
            collapseHost(views.get(i), rule);
        }

        reassert();
    }

    /** 每轮重申（AJX 重渲染会复活可见性 / 复位高度）。 */
    private static void reassert() {
        for (Iterator<java.lang.ref.WeakReference<View>> it = hiddenItems.iterator(); it.hasNext(); ) {
            View v = it.next().get();
            if (v == null) { it.remove(); continue; }
            if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
            // AJX 把 item 复活成有高度时才补 0，避免无谓的 LayoutParams 抖动
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != 0 && v.getHeight() > 0) {
                lp.height = 0;
                v.setLayoutParams(lp);
            }
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
            String key = cellKey.get(cell);
            if (key == null) { key = toolKey; cellKey.put(cell, key); }
            // 记下该格当前显示的 Label：优先保留"真的画出来了"（width>0）的那个实例
            java.lang.ref.WeakReference<View> prev = cellLabel.get(cell);
            View prevV = prev == null ? null : prev.get();
            if (prevV == null || (prevV.getWidth() <= 0 && anchor.getWidth() > 0)) {
                cellLabel.put(cell, new java.lang.ref.WeakReference<>(anchor));
            }
            protectTool(cell, key);

            // 扩展页整排单独控制（默认隐藏）：不看单个工具的开头，只看扩展页开关
            if (TOOL_EXTRA_LABELS.contains(t) && !cfgExtraPage()) {
                hideCell(cell, "extra:" + t, dirtyRows);
                continue;
            }

            if (cfgOn(Config.K_TOOL_PREFIX + key)) continue;

            hideCell(cell, key, dirtyRows);
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
    private static void squashRowIfEmpty(ViewGroup row) {
        int visible = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c.getVisibility() != View.GONE && c.getWidth() > 0 && c.getHeight() > 0) visible++;
        }
        if (visible > 0) return;

        if (row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp != null && lp.height != 0) {
            lp.height = 0;
            row.setLayoutParams(lp);
        }
        squashedRows.add(new java.lang.ref.WeakReference<>(row));
        if (loggedOnce.add("emptyrow_" + System.identityHashCode(row))) {
            H.log(Log.INFO, MainHook.TAG, "TOOL-ROW-EMPTY " + geom(row));
        }

        ViewParent sp = row.getParent();
        if (!(sp instanceof ViewGroup)) return;
        ViewGroup grid = (ViewGroup) sp;
        int gv = 0;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (c.getVisibility() != View.GONE && c.getWidth() > 0 && c.getHeight() > 0) gv++;
        }
        if (gv == 0 && grid.getVisibility() != View.GONE) {
            grid.setVisibility(View.GONE);
            if (loggedOnce.add("emptygrid_" + System.identityHashCode(grid))) {
                H.log(Log.INFO, MainHook.TAG, "TOOL-GRID-EMPTY " + geom(grid));
            }
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

    private static void applyTabs(View root) {
        try {
            ViewGroup row = tabRow;
            if (row == null || row.getParent() == null) {
                row = findTabRow(root, 0);
                tabRow = row;
            }
            if (row == null || row.getChildCount() <= 1) return;

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
            if (visible == 0) return;   // 全关也不收条，避免只剩 0 宽的残条

            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (c.getVisibility() == View.GONE) continue;
                if (!(c.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams)) return;
                android.widget.LinearLayout.LayoutParams lp =
                        (android.widget.LinearLayout.LayoutParams) c.getLayoutParams();
                if (lp.width != 0 || lp.weight != 1f) {
                    lp.width = 0;
                    lp.weight = 1f;
                    c.setLayoutParams(lp);
                }
            }
        } catch (Throwable ignored) {}
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
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp != null && lp.height != 0 && h > 0) {
            lp.height = 0;
            item.setLayoutParams(lp);
        }
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
     */
    private static void onItemBound(View item) {
        if (hiddenWhy.containsKey(item)) return;
        List<View> stack = new ArrayList<>();
        stack.add(item);
        String hitRule = null;
        boolean hasTool = false;
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 400) {
            View v = stack.remove(stack.size() - 1);
            String text;
            synchronized (LOCK) { text = anchors.get(v); }
            if (text != null) {
                if (TOOL_ALIAS.containsKey(text)) {
                    hasTool = true;
                    break;
                }
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
        if (hasTool || hitRule == null) return;

        if (item.getVisibility() != View.GONE) item.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = item.getLayoutParams();
        if (lp != null && lp.height != 0 && item.getHeight() > 0) {
            lp.height = 0;
            item.setLayoutParams(lp);
        }
        hiddenItems.add(new java.lang.ref.WeakReference<>(item));
        hiddenWhy.put(item, hitRule);
        if (loggedOnce.add("bind_" + System.identityHashCode(item))) {
            H.log(Log.INFO, MainHook.TAG, "BIND-HIDE " + hitRule + " " + geom(item));
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
