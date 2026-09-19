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
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable t) {
            scrollHooked.remove(key);   // observer 失效，允许下次重挂
        }
    }

    // -------------------------------------------------------------- 标签栏

    /**
     * 底栏其实是普通 android.widget.LinearLayout（4 个 ViewGroup 槽位固定等宽）。
     *
     * 试过两种错法：
     *  - removeView 摘掉不要的槽位：确实只剩两格，但「我的」从第 3 格被挤到第 1 格，
     *    腾讯按子 View 序号找页面，序号一变点「我的」却开首页内容（截图 192636）；
     *  - 隐藏格 setVisibility(GONE)：腾讯排版时会把 tab 槽位 visibility 拨回 VISIBLE，
     *    GONE 站不住，等于没藏（实测冷启+重启后 4 格仍全在）。
     *
     * 现在：槽位永远留在树里、永远保持 VISIBLE（保住 indexOfChild → 导航不破），
     * 只动 LayoutParams —— 隐藏的宽 0 权重 0（不占位、点不到、且不受 visibility 复原影响），
     * 可见的宽 0 权重 1。于是开了隐藏哪几格，剩下的就自动几等分（留首页/我的即二等分）。
     */
    private static void applyTabs(View root, Resources res) {
        ViewGroup row = tabRowRef != null ? tabRowRef.get() : null;
        if (row == null || row.getParent() == null || !row.isShown()) {
            row = findTabRow(root, 0);
            tabRowRef = row == null ? null : new WeakReference<>(row);
        }
        if (row == null || row.getChildCount() < 2) return;

        boolean changed = false;
        int visibleN = 0, hiddenN = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View cell = row.getChildAt(i);
            String label = tabLabel(cell, res);
            if (label == null) continue;                     // 认不出的槽位保持原样
            boolean vis = Config.tmapTabVisible(label);
            if (vis) visibleN++; else hiddenN++;

            // 强制保持 VISIBLE：不靠 visibility 隐藏，避免被腾讯复原；隐藏靠宽 0 权重 0
            if (cell.getVisibility() != View.VISIBLE) {
                cell.setVisibility(View.VISIBLE); changed = true;
            }

            ViewGroup.LayoutParams lp = cell.getLayoutParams();
            LinearLayout.LayoutParams llp = (lp instanceof LinearLayout.LayoutParams)
                    ? (LinearLayout.LayoutParams) lp
                    : new LinearLayout.LayoutParams(
                            0, lp == null ? ViewGroup.LayoutParams.MATCH_PARENT : lp.height);
            float wantWeight = vis ? 1f : 0f;
            if (llp.width != 0 || llp.weight != wantWeight) {
                llp.width = 0; llp.weight = wantWeight;
                cell.setLayoutParams(llp); changed = true;
            }
        }
        if (changed) row.requestLayout();
        if (hiddenN > 0 && logged.add("tmap_tab_done"))
            H.log(Log.INFO, MainHook.TAG,
                    "TMAP-TAB-HIDE 可见" + visibleN + " 隐藏" + hiddenN + "（等宽平分）");
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
