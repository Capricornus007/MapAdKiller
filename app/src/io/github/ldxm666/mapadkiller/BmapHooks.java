package io.github.ldxm666.mapadkiller;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.libxposed.api.XposedInterface;

/**
 * 百度地图 com.baidu.BaiduMap（21.18 ~ 21.20.50 适配）
 *
 * 21.20.50（versionCode 1645）实测链路（smali + 真机日志证据）：
 *  - SplashAdManager Kotlin 化重写，F()/z()/G()/H() 仍在，但自营运营开屏
 *    （fetchBizSplashAd，如淘宝闪购）不再经过 F/z 闸门，数据层拦截对其失效；
 *  - 缓存广告直接 addView 进 SplashViewContainer（FrameLayout）展示，
 *    吞调用/拦加载会造成开屏完成事件永不到达 → 卡开屏（21.20.30 真机复现）；
 *  - v1.0.2 的 SplashViewContainer"onAttachedToWindow"Hook 实际命中的是父类
 *    android.view.View 的同名方法（该类在 21.20.50 已不覆写此方法），
 *    等于 Hook 全局所有 View 的 attach → 白屏元凶。
 *
 *  v1.0.3 方案：
 *  1) 数据层保留 F/z 布尔闸门 + ADN Loader.I + BMAd 拦截（不吞 G/H/n/m、k/m，
 *     避免开屏必经路径被断）；
 *  2) 视图层 hook SplashViewContainer.addView：新增子树命中已知 AD SDK 特征
 *     或「跳过」按钮 → 整个子树 GONE。广告倒计时由 Handler 驱动照常走完，
 *     onAdFinish/onSkip 正常回调 → 不白屏、不卡开屏，广告零曝光。
 */
public final class BmapHooks {

    private static final String P = "com.baidu.baidumaps.";
    private static volatile boolean sPassthroughLogged = false;

    private BmapHooks() {}

    public static void install(ClassLoader cl) {
        // ---- 开屏闸门：仅 F/z 布尔闸（对仍有闸门的版本生效，21.20.50 自营链路已绕开）----
        Class<?> mgr = H.cls(cl, P + "splash.SplashAdManager");
        H.hookAll(mgr, "F", "bmap_gate_F", H.FALSE);
        H.hookAll(mgr, "z", "bmap_gate_z", H.FALSE);

        // ---- 开屏 SDK 广告隐藏（addView 探测，见类注释）----
        // 注意：只挂 SplashViewContainer **自己声明** 的 addView 重载。
        // 绝不能用 hookAll —— 那会沿父类链挂到 ViewGroup.addView，等于全局所有 addView，
        // 正是 v1.0.2 白屏事故的同款走法。
        Class<?> svc = H.cls(cl, P + "splash.view.SplashViewContainer");
        if (svc != null) {
            for (java.lang.reflect.Method m : svc.getDeclaredMethods()) {
                if (!m.getName().equals("addView")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 0 || !View.class.isAssignableFrom(ps[0])) continue;
                H.module.hook(m).setId("bmap_svc_addview" + ps.length)
                        .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                final View child = (View) chain.getArg(0);
                                // 先立即探一次（子树可能已经填好）
                                if (child != null) probeAndHide(child, 0);
                                Object r = chain.proceed();
                                // 广告内容常常是 addView 之后才异步填进去的：
                                // 1) 极短间隔连探（尽早）
                                // 2) 再挂 onPreDraw —— 每帧绘制前都探一次，
                                //    广告内容一出现就在**同一帧**被盖掉，不出现可见闪烁
                                if (child != null) {
                                    final long[] delays = {16, 40, 90, 180, 350, 700, 1200};
                                    for (final long d : delays) {
                                        if (child.getVisibility() == View.GONE) break;
                                        child.postDelayed(new Runnable() {
                                            @Override public void run() { probeAndHide(child, d); }
                                        }, d);
                                    }
                                    try {
                                        child.getViewTreeObserver().addOnPreDrawListener(
                                                new android.view.ViewTreeObserver.OnPreDrawListener() {
                                            private int n;
                                            @Override public boolean onPreDraw() {
                                                try {
                                                    probeAndHide(child, -1);
                                                    if (++n > 20 || child.getVisibility() == View.GONE) {
                                                        child.getViewTreeObserver()
                                                             .removeOnPreDrawListener(this);
                                                    }
                                                } catch (Throwable ignored) {}
                                                return true;
                                            }
                                        });
                                    } catch (Throwable ignored) {}
                                }
                                return r;
                            }
                        });
            }
            H.log(Log.INFO, MainHook.TAG, "BMAP splash addView hooked on "
                    + (svc == null ? "?" : svc.getName()));
        }

        // ---- 各 ADN Loader 加载入口 I（三方 SDK 广告数据层拦截）----
        String[] loaders = {
            P + "commonadprovider.api.IAdLoader",
            P + "commonadprovider.business.BusinessAdLoader",
            P + "commonadprovider.gromore.GromoreAdLoader",
            P + "commonadprovider.ms.MeishuAdLoader",
            P + "commonadprovider.octopus.OctopusAdLoader",
            P + "commonadprovider.qumeng.QumengAdLoader",
            P + "commonadprovider.recommend.RecommendAdLoader1",
            P + "commonadprovider.recommend.RecommendAdLoader2",
        };
        for (String ln : loaders) {
            Class<?> c = H.cls(cl, ln);
            if (c != null) H.hookAll(c, "I", "bmap_loader_" + ln.substring(ln.lastIndexOf('.') + 1), H.VOID);
        }

        // ---- BMAd 开放封装 ----
        String[] bmad = {
            P + "ad.BMAdSplashProvider",
            P + "ad.BMAdNativeProvider",
            P + "ad.BMAdRewardProvider",
            P + "ad.api.IBMapAdLoader",
        };
        for (String ln : bmad) {
            Class<?> c = H.cls(cl, ln);
            if (c == null) continue;
            String s = ln.substring(ln.lastIndexOf('.') + 1);
            H.hookAll(c, "c", "bmap_" + s + "_c", H.VOID);
            H.hookAll(c, "d", "bmap_" + s + "_d", H.VOID);
            H.hookAll(c, "x", "bmap_" + s + "_x", H.VOID);
        }

        // ---- 首页中部横幅（onCreateView 返回 View，VOID 会产生 null，仅拦 show）----
        Class<?> presenter = H.cls(cl, P + "aihome.panel.presenter.HomeMidBannerPresenter");
        H.hookAll(presenter, "show", "bmap_mid_show", H.VOID);
        Class<?> repo = H.cls(cl, P + "base.yellowbanner.MidBannerRepo");
        H.hookAll(repo, "c", "bmap_repo_c", H.VOID);
        H.hookAll(repo, "onEvent", "bmap_repo_evt", H.VOID);

        // ---- 悬浮运营黄条 ----
        Class<?> yb = H.cls(cl, P + "aihome.map.presenter.YellowBannerPresenter");
        H.hookAll(yb, "tryShowYellowBanner", "bmap_yb_try", H.VOID);
        H.hookAll(yb, "showYellowBanner", "bmap_yb_show", H.VOID);
        H.hookAll(yb, "showSwitcherBanner", "bmap_yb_sw", H.VOID);
        H.hookAll(yb, "showViewSwitcher", "bmap_yb_vsw", H.VOID);
        H.hookAll(yb, "showYbBannerAnim", "bmap_yb_anim", H.VOID);
        H.hookAll(yb, "onResumeForYB", "bmap_yb_res", H.VOID);

        // ---- 视图兜底（仅杀专属广告视图；SplashViewContainer 由 addView 探测处理）----
        // 说明：百度首页左上角那个"悬浮推广气泡"是**自营轮播位**，内容走 WebView，
        // 既不是三方 SDK、也不进无障碍树，SDK 层完全拦不到 —— 只能按宿主资源 id 处理。
        // （真机 uiautomator dump 实测到的宿主：floatCommonContentLayout / webshell_loading_layout2）
        Sweeper.install(new ViewKiller("BMAP-KILL",
                "^(com\\.baidu\\.baidumaps\\.integratedads\\.view\\.BannerAdView|" +
                "com\\.baidu\\.baidumaps\\.integratedads\\.gromore\\.view\\.BannerUIView)$",
                ":id/(banner_ad|ad_banner|splash_ad_view|mid_banner_container|home_ad_view|" +
                "floatCommonContentLayout|floatSliderLayout|float_slider|promo_float)$"));

        H.done(MainHook.PKG_BMAP);
    }

    /** 探测 + 隐藏：命中 AD SDK 特征则对整棵新增子树 GONE，否则放行（品牌层不动） */
    private static void probeAndHide(View root, long atMs) {
        try {
            if (root.getVisibility() == View.GONE) return;
            AdProbe.Result res = AdProbe.scan(root);
            if (res.hit != null) {
                // 关键：**绝对不能 GONE**。
                // 真机实证（21.20.x）：把 addView 进来的广告根 GONE 掉之后，
                // 开屏的「跳过 5」倒计时 / 收尾回调整条链路一起死，
                // 表现就是用户报的「一直卡在启动界面，要手动按返回键才进主页」。
                // alpha=0 只让它看不见：视图照常 measure/layout/跑动画/收回调，
                // 开屏按自己的节奏正常收尾，广告零曝光。
                root.setAlpha(0f);
                H.log(Log.INFO, MainHook.TAG,
                        "BMAP splash ad hidden(alpha0) at=" + (atMs < 0 ? "preDraw" : atMs + "ms")
                                + " hit=" + res.hit
                                + " root=" + root.getClass().getName());
            } else if (!sPassthroughLogged) {
                sPassthroughLogged = true;
                H.log(Log.INFO, MainHook.TAG,
                        "BMAP splash addview passthrough root=" + root.getClass().getName()
                                + " tree=" + res.tree);
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "BMAP probe err " + t);
        }
    }

    /**
     * 已知 AD SDK / 聚合 / 推广位的类名特征（真机实测校准）。
     *
     * 关键教训：旧表只有 qq.e/bytedance/kwad/gdt/sigmob 这些，
     * 结果百度开屏的聚合广告位 `com.qumeng.advlib.__remote__.ui.elements.SplashCountdownView`
     * **一个都命中不了**，只能等"跳过"文字出现才认出来 → 广告先亮 1 秒才被盖掉。
     * 现在补上 qumeng / advlib / splashcountdown 等实测命中项。
     */
    private static final class AdProbe {
        private static final String[] TOKENS = {
            // 第三方广告 SDK
            "qq.e", "gdt", "openadsdk", "bytedance", "pangle", "csj", "ttadview",
            "kwad", "ksad", "kwai", "sigmob", "mintegral", "mbridge",
            "gromore", "msdk", "octopus", "meishu", "beizi", "tanx", "windmill",
            "mobads", "adview", "splashad",
            // 百度开屏聚合位（真机实证命中）
            "qumeng", "advlib", "splashcountdown",
            // 通用广告位命名
            "adloader", "iadloader", "adcontainer",
        };

        private static final class Result {
            final String hit;
            final String tree;
            Result(String hit, String tree) { this.hit = hit; this.tree = tree; }
        }

        static Result scan(View root) {
            StringBuilder tree = new StringBuilder();
            String[] hit = new String[1];
            walk(root, tree, hit, 0);
            return new Result(hit[0], tree.toString());
        }

        private static boolean walk(View v, StringBuilder tree, String[] hit, int depth) {
            if (v == null || depth > 24 || tree.length() > 4000) return hit[0] != null;
            String cn = v.getClass().getName();
            if (tree.length() < 3000) tree.append(cn).append(' ');
            String low = cn.toLowerCase();
            for (String t : TOKENS) {
                if (low.contains(t)) { hit[0] = t + ":" + cn; return true; }
            }
            String lb = labelOf(v);
            if (lb != null && (lb.contains("跳过") || lb.contains("跳转") || lb.contains("广告")
                    || lb.equalsIgnoreCase("Ad") || lb.equalsIgnoreCase("AD"))) {
                hit[0] = "label:" + lb + ":" + cn;
                return true;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (walk(g.getChildAt(i), tree, hit, depth + 1)) return true;
                }
            }
            return false;
        }

        /** 短文本标签（跳过/广告 之类角标），限制长度避免把正文当广告标识 */
        private static String labelOf(View v) {
            try {
                CharSequence cd = v.getContentDescription();
                if (cd != null && cd.length() > 0 && cd.length() <= 8) return cd.toString();
                if (v instanceof TextView) {
                    CharSequence tx = ((TextView) v).getText();
                    if (tx != null && tx.length() > 0 && tx.length() <= 8) return tx.toString();
                }
            } catch (Throwable ignored) {}
            return null;
        }
    }
}
