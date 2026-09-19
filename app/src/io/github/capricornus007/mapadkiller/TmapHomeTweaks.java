package io.github.capricornus007.mapadkiller;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 腾讯地图首页 UI 自定义（com.tencent.map，11.6.0 实机）：
 *  - 底部标签栏：只留「首页 / 我的」，藏掉「探索 / 行程」（按 Config.tmapTabVisible 逐格判定）；
 *  - 「大家都在看」推荐流：整块藏掉（按 Config.tmapFeedHotVisible）。
 *
 * 与高德 HomeTweaks 同思路，但腾讯的标签栏是原生 View（不是 AJX 虚拟 DOM），
 * 且类名会随版本漂移，所以这里一律用「稳定 resource-id + 文本」当锚点，不写死类名：
 *  每个 tab 槽位 = ViewGroup > (selected_layout | unselected_layout) > selected_text/unselected_text。
 * 挂 Activity.onResume 后延迟扫树（异步/懒加载内容兜底），与 Sweeper 一致。
 */
public final class TmapHomeTweaks {

    private TmapHomeTweaks() {}

    private static final Set<String> logged = new HashSet<>();

    private static WeakReference<ViewGroup> tabRowRef;

    /** 液态色块 onDraw hook 只挂一次。 */
    private static volatile boolean liquidHooked;

    public static void install(ClassLoader cl) {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            H.module.hook(onResume)
              .setId("tmap_home_sweep")
              .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
              .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                  @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                      Object r = chain.proceed();
                      final Activity act = (Activity) chain.getThisObject();
                      Handler h = new Handler(Looper.getMainLooper());
                      h.postDelayed(new Runnable() {
                          @Override public void run() { applyHome(act); }
                      }, 200);
                      h.postDelayed(new Runnable() {
                          @Override public void run() { applyHome(act); }
                      }, 1200);
                      h.postDelayed(new Runnable() {
                          @Override public void run() { applyHome(act); }
                      }, 3000);
                      return r;
                  }
              });
            H.log(Log.INFO, MainHook.TAG, "tmap_home_tweaks installed");
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "tmap_home_tweaks install fail " + t);
        }
    }

    private static void applyHome(Activity act) {
        if (act == null || act.isFinishing()) return;
        try {
            installRotationGuard(act);
            View decor = act.getWindow().getDecorView();
            Resources res = act.getResources();
            applyTabs(decor, res);
            applyFeedHot(decor, res);
            applyMine(decor, res);
            hookScroll(decor, res);   // 大家都在看 是滚动才懒渲染，挂个节流滚动回调
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "tmap_home apply err " + t);
        }
    }

    // 腾讯地图启动会强开系统「自动旋转」(accelerometer_rotation=1)，害得离开地图后别的应用也跟着转。
    // 在地图进程内挂个 ContentObserver：值一变 1 就立刻拨回 0；首次挂上时也直接归零一次。
    private static volatile boolean rotationGuard;
    private static void installRotationGuard(Context ctx) {
        if (rotationGuard) return;
        rotationGuard = true;
        try {
            final ContentResolver cr = ctx.getContentResolver();
            android.provider.Settings.System.putInt(cr,
                    android.provider.Settings.System.ACCELEROMETER_ROTATION, 0);
            android.net.Uri uri = android.provider.Settings.System
                    .getUriFor(android.provider.Settings.System.ACCELEROMETER_ROTATION);
            cr.registerContentObserver(uri, false,
                    new android.database.ContentObserver(new android.os.Handler(
                            android.os.Looper.getMainLooper())) {
                        private boolean logged = false;
                        @Override public void onChange(boolean selfChange) {
                            try {
                                if (android.provider.Settings.System.getInt(cr,
                                        android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1) {
                                    android.provider.Settings.System.putInt(cr,
                                            android.provider.Settings.System.ACCELEROMETER_ROTATION, 0);
                                    if (!logged) { logged = true;
                                        H.log(Log.INFO, MainHook.TAG, "TMAP-BLOCK 自动旋转被拨回 0");
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            H.log(Log.INFO, MainHook.TAG, "tmap rotation guard installed");
        } catch (Throwable t) {
            rotationGuard = false;   // 失败允许下次重试
            H.log(Log.WARN, MainHook.TAG, "tmap rotation guard fail " + t);
        }
    }

    // 滚动节流：只在用户真的滚动时复查（不轮询，避免空烧 CPU/发热）
    private static final java.util.Set<Integer> scrollHooked =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static volatile long lastScrollRun;

    private static void hookScroll(final View decor, final Resources res) {
        final int key = System.identityHashCode(decor);
        if (!scrollHooked.add(key)) return;   // 每个 decor 只挂一次
        try {
            decor.getViewTreeObserver().addOnScrollChangedListener(
                    new android.view.ViewTreeObserver.OnScrollChangedListener() {
                        @Override public void onScrollChanged() {
                            long now = System.currentTimeMillis();
                            if (now - lastScrollRun < 400) return;   // 节流 400ms
                            lastScrollRun = now;
                            try {
                                applyTabs(decor, res);
                                applyFeedHot(decor, res);
                                applyMine(decor, res);
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable t) {
            scrollHooked.remove(key);   // observer 失效，允许下次重挂
        }
    }

    // -------------------------------------------------------------- 标签栏

    /**
     * 底栏是普通 android.widget.LinearLayout（4 个槽位），它的父 FrameLayout 才是那条
     * 深色圆角背景。用户要「底栏整体缩到刚好包住剩下的标签、居中」，做法：
     *  - 隐藏格：宽 0、权重 0、保持 VISIBLE —— 不靠 visibility 隐藏（免得被腾讯排版
     *    复原），宽 0 在 wrap 父容器里不占位；槽位仍留在树里 → indexOfChild 序号不变
     *    → 点「我的」导航不破；
     *  - 可见格：宽 WRAP_CONTENT（自然宽度）；
     *  - 标签行 + 背景父容器：都改成 WRAP_CONTENT 并在各自父级里水平居中，于是整条栏
     *    （含深色背景）缩到刚好包住剩下的标签、两侧留白。
     * 没藏任何格时（全显示）把行/背景容器还原 MATCH_PARENT、槽位改回等宽平分，恢复原样。
     */
    private static void applyTabs(View root, Resources res) {
        ViewGroup row = tabRowRef != null ? tabRowRef.get() : null;
        if (row == null || row.getParent() == null || !row.isShown()) {
            row = findTabRow(root, 0);
            tabRowRef = row == null ? null : new WeakReference<>(row);
        }
        if (row == null || row.getChildCount() < 2) return;

        // 先数要藏几格，决定「缩栏居中」还是「恢复原样」
        // 「至少留一个」保护：若用户把四个都关了，强制显示「首页」，避免底栏缩成空的。
        final boolean forceHome = Config.tmapVisibleTabCount() == 0;
        int hiddenN = 0, visibleN = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            String label = tabLabel(row.getChildAt(i), res);
            if (label == null) continue;
            boolean vis = Config.tmapTabVisible(label) || (forceHome && "首页".equals(label));
            if (vis) visibleN++; else hiddenN++;
        }
        boolean shrink = hiddenN > 0;
        // 缩栏时每个保留标签给一个「正常标签宽度」（约等于原来 4 等分的一格），
        // 这样栏只是缩到刚好放得下这几个、居中，不会挤成一坨文字。
        int perTab = (int) (res.getDisplayMetrics().widthPixels * 0.22f + 0.5f);

        boolean changed = false;
        for (int i = 0; i < row.getChildCount(); i++) {
            View cell = row.getChildAt(i);
            String label = tabLabel(cell, res);
            if (label == null) continue;
            boolean vis = Config.tmapTabVisible(label) || (forceHome && "首页".equals(label));

            if (cell.getVisibility() != View.VISIBLE) { cell.setVisibility(View.VISIBLE); changed = true; }

            ViewGroup.LayoutParams lp = cell.getLayoutParams();
            LinearLayout.LayoutParams llp = (lp instanceof LinearLayout.LayoutParams)
                    ? (LinearLayout.LayoutParams) lp
                    : new LinearLayout.LayoutParams(
                            0, lp == null ? ViewGroup.LayoutParams.MATCH_PARENT : lp.height);
            int wantW; float wantWt;
            if (!shrink) { wantW = 0; wantWt = 1f; }                                  // 全显示：等宽平分
            else { wantW = vis ? perTab : 0; wantWt = 0f; }                           // 缩栏：保留格正常宽、隐藏 0
            if (llp.width != wantW || llp.weight != wantWt) {
                llp.width = wantW; llp.weight = wantWt;
                cell.setLayoutParams(llp); changed = true;
            }
        }

        ViewParent bar = row.getParent();                 // 深色圆角背景容器
        changed |= wrapCenter(row, shrink);
        if (bar instanceof View) changed |= wrapCenter((View) bar, shrink);

        // 液态色块由这个 group 实例的 onDraw 画；用实例的真实 Class 挂 hook
        // （按类名 loadClass 会拿到别的 classloader 的副本、hook 不触发）。
        if (bar instanceof View) hookLiquid((View) bar);

        if (changed) {
            row.requestLayout();
            if (bar instanceof View) ((View) bar).requestLayout();
        }
        if (shrink && logged.add("tmap_tab_done"))
            H.log(Log.INFO, MainHook.TAG, "TMAP-TAB 缩栏居中 可见" + visibleN + " 隐藏" + hiddenN);
    }

    /** 用 group 实例自己的 Class 挂 onDraw hook，跳过即去掉液态色块；只挂一次。 */
    private static void hookLiquid(View group) {
        if (liquidHooked) return;
        liquidHooked = true;
        try {
            Method onDraw = group.getClass().getDeclaredMethod("onDraw", android.graphics.Canvas.class);
            H.module.hook(onDraw)
              .setId("tmap_liquid_off")
              .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
              .intercept(new io.github.libxposed.api.XposedInterface.Hooker() {
                  @Override public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                      int mode = Config.tmapBlobMode();
                      if (mode == Config.BLOB_OFF) return null;             // 不画
                      if (mode == Config.BLOB_FULL) return chain.proceed(); // 原样
                      // 半透明：只给色块这一层降 alpha（图标/文字是子 View，走 dispatchDraw 不受影响）
                      android.graphics.Canvas c = (android.graphics.Canvas) chain.getArg(0);
                      int saved = c.saveLayerAlpha((android.graphics.RectF) null, 110);
                      try { return chain.proceed(); } finally { c.restoreToCount(saved); }
                  }
              });
            H.log(Log.INFO, MainHook.TAG, "tmap liquid hook on " + group.getClass().getName());
        } catch (Throwable t) {
            liquidHooked = false;
            H.log(Log.WARN, MainHook.TAG, "tmap liquid hook fail " + t);
        }
    }

    /** 把 v 在其父容器里改成 WRAP_CONTENT + 水平居中；shrink=false 时还原 MATCH_PARENT。 */
    private static boolean wrapCenter(View v, boolean shrink) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return false;
        boolean ch = false;
        int wantW = shrink ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp.width != wantW) { lp.width = wantW; ch = true; }
        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams flp =
                    (android.widget.FrameLayout.LayoutParams) lp;
            if (shrink) {
                int base = flp.gravity < 0 ? 0 : flp.gravity;
                // 0x00800007 = RELATIVE_LAYOUT_DIRECTION | HORIZONTAL(LEFT/RIGHT/CENTER_H)
                int g = (base & ~0x00800007) | android.view.Gravity.CENTER_HORIZONTAL;
                if (flp.gravity != g) { flp.gravity = g; ch = true; }
            }
        }
        if (ch) v.setLayoutParams(lp);
        return ch;
    }

    // --------------------------------------------------------- 我的页分区

    /**
     * 「我的」页的分区（积分中心 / 个性化设置 / …）是 Kuikly 自绘（KRView），
     * 只能按标题文字定位、再上溯找「整块分区」容器隐藏。天生脆弱：改版可能失效、
     * Kuikly 可能重绘回来，所以在 onResume + 滚动时反复补做。仅在「我的」页动手。
     */
    private static void applyMine(View root, Resources res) {
        int screenW = res.getDisplayMetrics().widthPixels;
        for (String sec : Config.TMAP_MINE_SECTIONS) {
            if (Config.tmapMineVisible(sec)) continue;
            View title = findViewByText(root, sec, 0);
            if (title == null) continue;
            View cont = sectionContainer(title, screenW);
            if (cont == null || cont.getVisibility() == View.GONE) continue;
            cont.setVisibility(View.GONE);
            if (logged.add("mine_" + sec))
                H.log(Log.INFO, MainHook.TAG, "TMAP-MINE-HIDE " + sec
                        + " cont=" + cont.getClass().getSimpleName()
                        + " w=" + cont.getWidth() + " h=" + cont.getHeight());
            ViewParent p = cont.getParent();
            if (p instanceof View) p.requestLayout();
        }
    }

    /** 从标题往上找第一个「全宽、且明显比标题行高」的祖先 = 整块分区容器（跳过标题自身那层）。 */
    private static View sectionContainer(View title, int screenW) {
        ViewParent pp = title.getParent();
        View cur = (pp instanceof View) ? (View) pp : null;   // 从标题的父级往上找，避免只藏标题
        int minH = Math.max(title.getHeight() * 2, 160);
        int guard = 0;
        while (cur != null && guard++ < 14) {
            int w = cur.getWidth(), h = cur.getHeight();
            if (w >= screenW * 0.85f && h >= minH) return cur;
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            cur = (View) p;
        }
        return null;
    }

    /**
     * 找底部标签栏容器：≥3 个子 View，其中至少 2 个子 View 内含
     * selected_layout / unselected_layout（腾讯 tab 槽位的稳定结构）。
     */
    private static ViewGroup findTabRow(View v, int depth) {
        if (depth > 24 || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        if (g.getChildCount() >= 3 && looksLikeTabRow(g)) return g;
        for (int i = 0; i < g.getChildCount(); i++) {
            ViewGroup r = findTabRow(g.getChildAt(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static boolean looksLikeTabRow(ViewGroup g) {
        int hit = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (containsIdNamed(c, "selected_layout") || containsIdNamed(c, "unselected_layout")) hit++;
        }
        return hit >= 2;
    }

    /** 取一个 tab 槽位的文本（selected_text 或 unselected_text 后代的 text） */
    private static String tabLabel(View cell, Resources res) {
        TextView tv = findTextViewByIdName(cell, "selected_text");
        if (tv == null) tv = findTextViewByIdName(cell, "unselected_text");
        if (tv == null) return null;
        CharSequence t = tv.getText();
        if (t == null) return null;
        String s = t.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // --------------------------------------------------------- 大家都在看

    private static void applyFeedHot(View root, Resources res) {
        if (Config.tmapFeedHotVisible()) return;
        // 只在「首页」动手：首页内容根 home_hippy_main / home_page_card_view 存在才继续，
        // 否则（我的页 / 探索页等）绝不隐藏任何东西——之前 matcher 太宽会在别的页误伤。
        if (!containsIdNamed(root, "home_hippy_main") && !containsIdNamed(root, "home_page_card_view")) return;
        View section = findFeedSectionByStructure(root, 0);
        if (section == null) return;
        if (section.getVisibility() != View.GONE) {
            section.setVisibility(View.GONE);
            if (logged.add("feed_hot"))
                H.log(Log.INFO, MainHook.TAG, "TMAP-FEED-HIDE 大家都在看 section="
                        + section.getClass().getName() + " h=" + section.getHeight());
            ViewParent p = section.getParent();
            if (p instanceof View) p.requestLayout();
        }
    }

    /**
     * DFS 找最小的「大家都在看」区块：一个全宽容器，其直接子里
     *   一个「含横向分类 chips 条」——且该 chips 条有 ≥5 个子项（全部/美食/景点/酒店/去哪玩/本地优惠/运动户外），且
     *   另一个「高度 > 400」（下面的卡片流）。
     * 要求 chips≥5 是为了排除「我的」页那种只有两三项的横向行，避免误伤。先递归子、优先最内层匹配。
     */
    private static View findFeedSectionByStructure(View v, int depth) {
        if (depth > 40 || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = findFeedSectionByStructure(g.getChildAt(i), depth + 1);
            if (r != null) return r;
        }
        if (g.getChildCount() >= 2 && g.getWidth() > 1000 && !containsIdNamed(g, "home_hippy_main")) {
            boolean chipBar = false, tallCards = false;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getHeight() > 400) tallCards = true;
                if (isChipBar(c)) chipBar = true;
            }
            if (chipBar && tallCards) return g;
        }
        return null;
    }

    /** c 或其子代里是否有一个「≥5 个子项的横向滚动条」（分类 chips）。 */
    private static boolean isChipBar(View c) {
        if (c instanceof android.widget.HorizontalScrollView && countHScrollItems(c) >= 5) return true;
        if (c instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) c;
            for (int i = 0; i < g.getChildCount(); i++) if (isChipBar(g.getChildAt(i))) return true;
        }
        return false;
    }

    /** 横向滚动条里的可点项数（取内部容器的 childCount）。 */
    private static int countHScrollItems(View hs) {
        if (hs instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) hs;
            int best = g.getChildCount();
            for (int i = 0; i < g.getChildCount(); i++) {
                View ch = g.getChildAt(i);
                if (ch instanceof ViewGroup) best = Math.max(best, ((ViewGroup) ch).getChildCount());
            }
            return best;
        }
        return 0;
    }

    /**
     * 从「大家都在看」标题上溯到它所在的推荐区块：往上找第一个「同时含横向分类 chips
     * (HorizontalScrollView) 且不是整张首页抽屉」的祖先。找不到就退回标题本身，
     * 顶多藏掉标题一行，不会误伤搜索框/工具宫格。
     */
    private static View bubbleToSection(View title) {
        View best = null;
        View cur = title;
        for (int i = 0; i < 6; i++) {
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            cur = (View) p;
            // 含搜索框/工具宫格的容器＝整张抽屉，太大，停在上一个
            if (containsIdNamed(cur, "home_hippy_main") || containsIdNamed(cur, "home_page_card_view")) {
                break;
            }
            if (containsHScroll(cur)) { best = cur; break; }
            best = cur;
        }
        return best;
    }

    private static boolean containsHScroll(View v) {
        if (v instanceof android.widget.HorizontalScrollView) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (containsHScroll(g.getChildAt(i))) return true;
        }
        return false;
    }

    // -------------------------------------------------------------- 工具

    private static boolean containsIdNamed(View v, String idName) {
        if (v.getId() != View.NO_ID && v.getId() != 0) {
            try {
                String n = v.getResources().getResourceName(v.getId());
                if (n != null && n.endsWith(":id/" + idName)) return true;
            } catch (Throwable ignored) {}
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (containsIdNamed(g.getChildAt(i), idName)) return true;
        }
        return false;
    }

    private static TextView findTextViewByIdName(View v, String idName) {
        if (v instanceof TextView && v.getId() != 0) {
            try {
                String n = v.getResources().getResourceName(v.getId());
                if (n != null && n.endsWith(":id/" + idName)) return (TextView) v;
            } catch (Throwable ignored) {}
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findTextViewByIdName(g.getChildAt(i), idName);
                if (t != null) return t;
            }
        }
        return null;
    }

    /**
     * 深度优先找文本完全等于 target 的 View。
     * 腾讯首页大量内容（含「大家都在看」）是 Kuikly 自绘，文字只经
     * AccessibilityNodeInfo 暴露、不是 TextView.getText()，所以这里三路取文本：
     *   TextView.getText() → getContentDescription() → AccessibilityNodeInfo.getText()。
     */
    private static View findViewByText(View v, String target, int depth) {
        if (depth > 48) return null;
        String t = viewText(v);
        if (t != null && target.equals(t.trim())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findViewByText(g.getChildAt(i), target, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static String viewText(View v) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) return t.toString();
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) return cd.toString();
        // Kuikly 自绘文字：文字写在 view 的无障碍节点上（onInitializeAccessibilityNodeInfo），
        // 既不是 getText 也不是 contentDescription。用公开 API 主动问一次节点文本。
        try {
            android.view.accessibility.AccessibilityNodeInfo node =
                    android.view.accessibility.AccessibilityNodeInfo.obtain(v);
            v.onInitializeAccessibilityNodeInfo(node);
            CharSequence t = node.getText();
            node.recycle();
            if (t != null && t.length() > 0) return t.toString();
        } catch (Throwable ignored) {}
        return null;
    }
}
