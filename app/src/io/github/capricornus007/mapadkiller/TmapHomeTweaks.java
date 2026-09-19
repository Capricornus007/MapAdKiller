package io.github.capricornus007.mapadkiller;

import android.app.Activity;
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
            View decor = act.getWindow().getDecorView();
            Resources res = act.getResources();
            applyTabs(decor, res);
            applyFeedHot(decor, res);
            hookScroll(decor, res);   // 大家都在看 是滚动才懒渲染，挂个节流滚动回调
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "tmap_home apply err " + t);
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

    /** 被移除的 tab 槽位：label -> {cell, parent, index}，供配置改回显示时还原 */
    private static final java.util.LinkedHashMap<String, Object[]> removedTabs =
            new java.util.LinkedHashMap<>();

    private static void applyTabs(View root, Resources res) {
        ViewGroup row = tabRowRef != null ? tabRowRef.get() : null;
        if (row == null || row.getParent() == null || !row.isShown()) {
            row = findTabRow(root, 0);
            tabRowRef = row == null ? null : new WeakReference<>(row);
        }
        if (row == null) return;

        // 先还原：配置又打开的标签，放回原来的父容器与位置
        if (!removedTabs.isEmpty()) {
            java.util.Iterator<java.util.Map.Entry<String, Object[]>> it = removedTabs.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, Object[]> e = it.next();
                if (Config.tmapTabVisible(e.getKey())) {
                    View cell = (View) e.getValue()[0];
                    ViewGroup parent = (ViewGroup) e.getValue()[1];
                    int index = (Integer) e.getValue()[2];
                    if (cell.getParent() == null && parent != null) {
                        parent.addView(cell, Math.min(index, parent.getChildCount()));
                    }
                    it.remove();
                }
            }
        }

        if (row.getChildCount() < 2) return;

        // 腾讯这条栏是自定义 ViewGroup，按「固定槽位数」排版，GONE 掉仍占位→
        // 只剩两个标签时中间和两头全是空（用户说的「四分之一很丑」）。所以直接
        // removeView 把不要的槽位摘掉，让剩下的标签平分整条栏。
        boolean changed = false;
        for (int i = row.getChildCount() - 1; i >= 0; i--) {
            View cell = row.getChildAt(i);
            String label = tabLabel(cell, res);
            if (label == null) continue;                       // 认不出的槽位不动
            if (Config.tmapTabVisible(label)) {
                if (cell.getVisibility() == View.GONE) cell.setVisibility(View.VISIBLE);
                continue;
            }
            if (!removedTabs.containsKey(label)) {
                removedTabs.put(label, new Object[]{cell, row, i});
                row.removeViewAt(i);
                changed = true;
                if (logged.add("tab_" + label))
                    H.log(Log.INFO, MainHook.TAG, "TMAP-TAB-HIDE " + label);
            }
        }

        // 兜底：若这栏其实是 LinearLayout，让可见格等宽平分（自定义栏靠上面的 removeView 已生效）
        for (int i = 0; i < row.getChildCount(); i++) {
            View cell = row.getChildAt(i);
            ViewGroup.LayoutParams lp = cell.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                if (llp.width != 0 || llp.weight != 1f) { llp.width = 0; llp.weight = 1f; cell.setLayoutParams(llp); changed = true; }
            }
        }
        if (changed) row.requestLayout();
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
        View title = findViewByText(root, "大家都在看", 0);
        if (title == null) return;
        View section = bubbleToSection(title);
        if (section == null) section = title;
        if (section.getVisibility() != View.GONE) {
            section.setVisibility(View.GONE);
            if (section instanceof ViewGroup) {
                // 只清掉「大家都在看」这一块，不动整张首页抽屉
            }
            if (logged.add("feed_hot"))
                H.log(Log.INFO, MainHook.TAG, "TMAP-FEED-HIDE 大家都在看 section="
                        + section.getClass().getName());
            ViewParent p = section.getParent();
            if (p instanceof View) p.requestLayout();
        }
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
