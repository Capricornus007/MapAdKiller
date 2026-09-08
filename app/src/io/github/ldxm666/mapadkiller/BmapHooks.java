package io.github.ldxm666.mapadkiller;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/**
 * 百度地图 com.baidu.BaiduMap（21.18 与 21.20.30 双版本验证）
 *  - 开屏闸门: WelcomeScreen.t()/HomeSplashPresenter 以 F()&&z() 决定是否等待 → 强制 false
 *  - 加载链: G/H/n/m(Context,ii.a) → SplashAdProvider.k/m → 6 家 ADN Loader.I
 *  - 卡屏根治: HomeSplashPresenter.n() 会 addContentView 全屏品牌遮罩(splash_layers)，
 *    广告流程被吞后其完成事件永不到达 → n() 返回后立即摘除遮罩（含 300ms 补刀）
 *  - 中部横幅: HomeMidBannerPresenter.show/onCreateView + MidBannerRepo.c/onEvent
 *  - 悬浮黄条: YellowBannerPresenter try/show/showSwitcher/showViewSwitcher/showYbBannerAnim/onResumeForYB
 *  - BMAd*Provider + IBMapAdLoader c/d/x（三方广告开放封装）
 */
public final class BmapHooks {

    private static final String P = "com.baidu.baidumaps.";

    private BmapHooks() {}

    public static void install(ClassLoader cl) {
        // ---- 开屏闸门（注意：不能吞 L/BMapAdEngine.i/m —— 品牌开屏完成事件也走它们，会卡到超时）----
        Class<?> mgr = H.cls(cl, P + "splash.SplashAdManager");
        H.hookAll(mgr, "F", "bmap_gate_F", H.FALSE);
        H.hookAll(mgr, "z", "bmap_gate_z", H.FALSE);
        H.hookAll(mgr, "G", "bmap_G", H.VOID);
        H.hookAll(mgr, "H", "bmap_H", H.VOID);
        H.hookAll(mgr, "n", "bmap_n", H.VOID);
        H.hookAll(mgr, "m", "bmap_m", H.VOID);

        Class<?> provider = H.cls(cl, P + "commonadprovider.SplashAdProvider");
        H.hookAll(provider, "k", "bmap_prov_k", H.VOID);
        H.hookAll(provider, "m", "bmap_prov_m", H.VOID);

        // ---- 品牌开屏遮罩摘除（v1.0.2 卡屏修复核心）----
        Class<?> hsp = H.cls(cl, P + "operation.splash.HomeSplashPresenter");
        H.hookSig(hsp, "n", "bmap_rip_overlay", new io.github.libxposed.api.XposedInterface.Hooker() {
            @Override public Object hook(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                final Object thiz = chain.getThisObject();
                ripOverlay(thiz);
                try {
                    Object act = H.getObjectField(thiz, "z");
                    if (act instanceof Activity) {
                        ((Activity) act).getWindow().getDecorView().postDelayed(new Runnable() {
                            @Override public void run() { ripOverlay(thiz); }
                        }, 300);
                    }
                } catch (Throwable ignored) {}
                return r;
            }
        }, String.class, boolean.class, String.class);

        // ---- 开屏广告容器釜底抽薪（真机验证：缓存广告经 SplashViewContainer 直接 addContentView 展示，
        //      绕过 SplashAdManager.G/n 加载闸）----
        // 任何路径 attach 即摘除，覆盖 brand + 淘宝闪购等 SDK 广告。
        Class<?> svc = H.cls(cl, P + "splash.view.SplashViewContainer");
        H.hookAll(svc, "onAttachedToWindow", "bmap_splashview_kill", new io.github.libxposed.api.XposedInterface.Hooker() {
            @Override public Object hook(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                final View v = (View) chain.getThisObject();
                v.post(new Runnable() {
                    @Override public void run() {
                        try {
                            ViewParent p = v.getParent();
                            if (p instanceof ViewGroup) {
                                ((ViewGroup) p).removeView(v);
                                H.log(Log.INFO, MainHook.TAG, "BMAP SplashViewContainer removed");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                return r;
            }
        });

        // ---- 各 ADN Loader 加载入口 I ----
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

        // ---- 首页中部横幅 ----
        Class<?> presenter = H.cls(cl, P + "aihome.panel.presenter.HomeMidBannerPresenter");
        H.hookAll(presenter, "show", "bmap_mid_show", H.VOID);
        H.hookAll(presenter, "onCreateView", "bmap_mid_create", H.VOID);
        Class<?> repo = H.cls(cl, P + "base.yellowbanner.MidBannerRepo");
        H.hookAll(repo, "c", "bmap_repo_c", H.VOID);
        H.hookAll(repo, "onEvent", "bmap_repo_evt", H.VOID);

        // ---- 悬浮运营黄条（21.20 私有 showViewSwitcher/showYbBannerAnim）----
        Class<?> yb = H.cls(cl, P + "aihome.map.presenter.YellowBannerPresenter");
        H.hookAll(yb, "tryShowYellowBanner", "bmap_yb_try", H.VOID);
        H.hookAll(yb, "showYellowBanner", "bmap_yb_show", H.VOID);
        H.hookAll(yb, "showSwitcherBanner", "bmap_yb_sw", H.VOID);
        H.hookAll(yb, "showViewSwitcher", "bmap_yb_vsw", H.VOID);
        H.hookAll(yb, "showYbBannerAnim", "bmap_yb_anim", H.VOID);
        H.hookAll(yb, "onResumeForYB", "bmap_yb_res", H.VOID);

        // ---- 视图兜底 ----
        Sweeper.install(new ViewKiller("BMAP-KILL",
                "^(com\\.baidu\\.baidumaps\\.integratedads\\.view\\.BannerAdView|" +
                "com\\.baidu\\.baidumaps\\.integratedads\\.gromore\\.view\\.BannerUIView|" +
                "com\\.baidu\\.baidumaps\\.splash\\.view\\.SplashViewContainer)$",
                ":id/(banner_ad|ad_banner|splash_ad_view|mid_banner_container|home_ad_view)$"));

        H.done(MainHook.PKG_BMAP);
    }

    private static void ripOverlay(Object thiz) {
        try {
            Object e = H.getObjectField(thiz, "E");
            if (e instanceof View) {
                View v = (View) e;
                ViewParent parent = v.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(v);
                    H.log(Log.INFO, MainHook.TAG, "BMAP brand splash overlay removed");
                }
            }
        } catch (Throwable t) {
            H.log(Log.WARN, MainHook.TAG, "BMAP overlay rip err " + t);
        }
    }
}
