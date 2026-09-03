package cn.eni.mapadkiller;

/**
 * 百度地图 com.baidu.BaiduMap
 * 证据（jadx/smali）：
 *  - 开屏: WelcomeScreen -> SplashAdManager.G(Context, oi.a) / .H(...)  [静态]
 *          G -> SplashAdProvider.k(Context, c4.a)  ← 所有 ADN 加载的总闸门
 *          .n(ctx,z,ViewGroup,openApiUrl,b) OPENAPI渠道展示; .m(api.f) 预加载
 *          .i/.j 实例版 fetch
 *  - commonadprovider 各 ADN Loader 子类: IAdLoader.I(Context,c4.a) 入口
 *  - 首页中部横幅: HomeMidBannerPresenter.show(presenter.s) + MidBannerRepo.c()/onEvent
 *  - 三方广告视图: com.baidu.baidumaps.integratedads.view.BannerAdView / gromore.view.BannerUIView
 *  - ad 开放封装: BMAdSplashProvider.c/d, BMAdNativeProvider, BMAdRewardProvider, IBMapAdLoader.x(Context)
 */
public final class BmapHooks {

    private static final String P = "com.baidu.baidumaps.";

    private BmapHooks() {}

    public static void install(ClassLoader cl) {
        // ---- 开屏：总闸门 ----
        // 关键：HomeSplashPresenter 与 WelcomeScreen.t() 都以 F() && z() 决定是否等待开屏。
        // 强制 false → 应用走自家"无广告"分支立即进主页（只吞 G 会卡在等事件超时）。
        // G/H/n/m 吞掉是第二道闸（防止任何路径仍发起加载）。
        Class<?> mgr = H.cls(cl, P + "splash.SplashAdManager");
        H.returnsFalse(mgr, "F");
        H.returnsFalse(mgr, "z");
        H.swallow(mgr, "G");
        H.swallow(mgr, "H");
        H.swallow(mgr, "n");
        H.swallow(mgr, "m");

        Class<?> provider = H.cls(cl, P + "commonadprovider.SplashAdProvider");
        H.swallow(provider, "k");
        H.swallow(provider, "m");

        // ---- 各 ADN Loader 基类/子类加载入口 I(Context,c4.a)V ----
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
            if (c == null) continue;
            H.hookByName(c, "I", H.VOID);
        }

        // ---- ad 开放封装 providers ----
        String[] bmad = {
            P + "ad.BMAdSplashProvider",
            P + "ad.BMAdNativeProvider",
            P + "ad.BMAdRewardProvider",
            P + "ad.api.IBMapAdLoader",
        };
        for (String ln : bmad) {
            Class<?> c = H.cls(cl, ln);
            if (c == null) continue;
            H.hookByName(c, "c", H.VOID);
            H.hookByName(c, "d", H.VOID);
            H.hookByName(c, "x", H.VOID);
        }

        // ---- 首页中部横幅 (运营banner) ----
        Class<?> presenter = H.cls(cl, P + "aihome.panel.presenter.HomeMidBannerPresenter");
        H.swallow(presenter, "show",
                H.cls(cl, P + "aihome.panel.presenter.s"));
        H.hookByName(presenter, "onCreateView", new de.robv.android.xposed.XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                MainHook.log("BMAP mid-banner onCreateView suppressed by data kill");
            }
        });
        Class<?> repo = H.cls(cl, P + "base.yellowbanner.MidBannerRepo");
        H.swallow(repo, "c");
        H.swallow(repo, "onEvent", H.cls(cl, P + "base.yellowbanner.b"));

        // ---- 首页悬浮运营黄条（"做任务领现金"类） ----
        Class<?> yb = H.cls(cl, P + "aihome.map.presenter.YellowBannerPresenter");
        H.swallow(yb, "tryShowYellowBanner");
        H.swallow(yb, "showYellowBanner");
        H.swallow(yb, "showSwitcherBanner");

        // ---- 视图兜底 ----
        Sweeper.install(new ViewKiller("BMAP-KILL",
                "^(com\\.baidu\\.baidumaps\\.integratedads\\.view\\.BannerAdView|" +
                "com\\.baidu\\.baidumaps\\.integratedads\\.gromore\\.view\\.BannerUIView)$",
                ":id/(banner_ad|ad_banner|splash_ad_view|mid_banner_container|home_ad_view)$"));

        MainHook.log("BMAP hooks done ok=" + H.ok + " miss=" + H.fail);
    }
}

