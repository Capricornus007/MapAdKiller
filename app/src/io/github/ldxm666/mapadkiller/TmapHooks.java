package io.github.ldxm666.mapadkiller;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 腾讯地图 com.tencent.map
 * 证据：
 *  - GDT 广点通: com.qq.e.comm.managers.GDTADManager
 *      initWith(Context,String)Z / initPlugin()V / isInitialized()Z / preRequestDNS()V
 *      （GDT 插件与 tangramsplash/TGSplashAD 全部依赖 initWith 成功才会走）
 *  - 自家开屏流水线: init.tasks.optional.SplashRequestTask.run /
 *      launch.v2.task.t2.SplashManagerInitTask.run / init.tasks.DecodeSplashTask.run
 *  - POI 列表广告卡: kuiklyPoi...special.AdCardView / kuiklyexplore...special.AdCardView
 *  - 首页: newhome.widget.HomeBannerItem(setItemData) / home.view.OperationCardView /
 *      view id view_stub_home_banner_view / banner_layout (uiautomator 实测)
 *  - 路线运营横幅: route.components.operation.OperationBannerView /
 *      BusOperationBannerView / sinan.components.ActivityBannerView
 */
public final class TmapHooks {

    private TmapHooks() {}

    public static void install(ClassLoader cl) {
        // ---- GDT 广点通 总闸 ----
        Class<?> gdt = H.cls(cl, "com.qq.e.comm.managers.GDTADManager");
        H.returnsFalse(gdt, "initWith", android.content.Context.class, String.class);
        H.swallow(gdt, "initPlugin");
        H.returnsFalse(gdt, "isInitialized");
        H.swallow(gdt, "preRequestDNS");

        // ---- 开屏流水线任务 ----
        Class[] tasks = {
            H.cls(cl, "com.tencent.map.init.tasks.optional.SplashRequestTask"),
            H.cls(cl, "com.tencent.map.launch.v2.task.t2.SplashManagerInitTask"),
            H.cls(cl, "com.tencent.map.init.tasks.DecodeSplashTask"),
        };
        for (Class<?> t : tasks) H.swallow(t, "run");

        // ---- 首页 banner 数据绑定 ----
        Class<?> bannerItem = H.cls(cl, "com.tencent.map.ama.newhome.widget.HomeBannerItem");
        H.swallow(bannerItem, "setItemData");

        // ---- 视图兜底（GONE + 移除子树）----
        Sweeper.install(new ViewKiller("TMAP-KILL",
                "^(com\\.tencent\\.map\\.kuiklyPoi\\.pages\\.list\\.item\\.special\\.AdCardView|" +
                "com\\.tencent\\.map\\.kuiklyexplore\\.components\\.poiCard\\.special\\.AdCardView|" +
                "com\\.tencent\\.map\\.ama\\.newhome\\.widget\\.HomeBannerItem|" +
                "com\\.tencent\\.map\\.ama\\.mainpage\\.business\\.pages\\.home\\.view\\.OperationCardView|" +
                "com\\.tencent\\.map\\.route\\.components\\.operation\\.OperationBannerView|" +
                "com\\.tencent\\.map\\.ama\\.route\\.bus\\.operation\\.BusBillboardView|" +
                "com\\.tencent\\.map\\.route\\.components\\.common\\.HistoryRoutes\\.bus\\.BannerView\\.BusOperationBannerView|" +
                "com\\.tencent\\.map\\.sinan\\.components\\.ActivityBannerView)$",
                ":id/(view_stub_home_banner_view|banner_layout|home_ad_banner|poi_list_ad_card)$"));

        MainHook.log("TMAP hooks done ok=" + H.ok + " miss=" + H.fail);
    }
}
