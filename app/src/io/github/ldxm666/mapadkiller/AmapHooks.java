package io.github.ldxm666.mapadkiller;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

/**
 * 高德地图 com.autonavi.minimap
 * 证据：
 *  - 开屏广告状态机: SplashScreenServiceImpl.tryShowSplashView -> za6.g(int,String):za6$a
 *      za6$a.a == 1 → 应用走自家 "NO_SPLASH" 分支，正常进首页（安全）
 *  - 实时拉取: SplashScreenServiceImpl.fetchRealTime()V
 *  - 首页横幅: BannerManager.b(String,ZZ,OnLoadBannerListener)V (static load) / .a(I,String)BannerItem
 *  - 网络解析: BannerParser.a(JSONObject)BannerResult → null
 *  - 后台推送消息(运营弹窗): BackgroundMsgManager.a(IBackgroundMsgFetchListener)V
 *  - JS联动数据: NativesModuleSplashScreen.getLinkageMsg(I)/getCurrentLinkageMsg() JSONObject
 *  - 搜索页模板开屏: SplashModel.getData()/getTemplate() 置空
 */
public final class AmapHooks {

    private AmapHooks() {}

    public static void install(ClassLoader cl) {
        // ---- 开屏广告：数据层强制“无广告”，让 app 自己走 NO_SPLASH 收尾 ----
        Class<?> za6 = H.cls(cl, "za6");
        if (za6 != null) {
            H.hook(za6, "g", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object res = p.result;
                    if (res == null) return;
                    try {
                        XposedHelpers.setIntField(res, "a", 1); // 1 = no ad available
                        MainHook.log("AMAP splash forced NO_SPLASH");
                    } catch (Throwable ignored) {}
                }
            }, int.class, String.class);
        }
        Class<?> splashSvc = H.cls(cl, "com.autonavi.minimap.impl.SplashScreenServiceImpl");
        H.swallow(splashSvc, "fetchRealTime");
        H.returnsFalse(splashSvc, "isSplashShowing");
        H.returnsFalse(splashSvc, "isContinueLaunchMaskViewShowing");

        // ---- 首页 banner ----
        Class<?> bannerMgr = H.cls(cl, "com.autonavi.bundle.banner.manager.BannerManager");
        H.swallow(bannerMgr, "b");
        H.returnsNull(bannerMgr, "a");
        Class<?> bannerParser = H.cls(cl, "com.autonavi.bundle.banner.net.BannerParser");
        H.returnsNull(bannerParser, "a");

        // ---- 后台推送运营消息 ----
        Class<?> bgMsg = H.cls(cl, "com.autonavi.minimap.bundle.msgbox.push.BackgroundMsgManager");
        H.hookByName(bgMsg, "a", H.VOID);

        // ---- 开屏联动数据 (喂给 AJX 首页) ----
        Class<?> natives = H.cls(cl, "com.autonavi.minimap.splashscreen.ajx.NativesModuleSplashScreen");
        H.returnsNull(natives, "getLinkageMsg", int.class);
        H.returnsNull(natives, "getCurrentLinkageMsg");

        // ---- 搜索页 splash 模板 ----
        Class<?> sModel = H.cls(cl, "com.autonavi.minimap.search.inter.splash.SplashModel");
        H.returnsNull(sModel, "getData");
        H.returnsNull(sModel, "getTemplate");
        H.returnsNull(sModel, "getXmlUrl");
        H.returnsNull(sModel, "getCssUrl");

        // ---- 视图层兜底 ----
        Sweeper.install(new ViewKiller("AMAP-KILL",
                "^(com\\.autonavi\\.bundle\\.banner\\.view\\.DBanner)$",
                ":id/.*(banner|splash_ad|ad_container|home_ad)$"));

        MainHook.log("AMAP hooks done ok=" + H.ok + " miss=" + H.fail);
    }
}

