package io.github.ldxm666.mapadkiller;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MapAdKiller — 高德/百度/腾讯地图 去广告 Xposed 模块
 *
 * 证据链来自 base.apk 的 androguard 类普查 + apktool/jadx 反编译：
 *  - com.autonavi.minimap : 自研广告体系  bundle.splashscreen / banner / msgbox.push / feed
 *  - com.baidu.BaiduMap   : com.baidu.baidumaps.splash.SplashAdManager + ad.BMAd*Provider + mobads 聚合(KS/UBIX/Octopus...) + 三方SDK
 *  - com.tencent.map      : GDT com.qq.e + com.tencent.ad.tangram + ama.splash + route.operation 运营位
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "MapAdKiller";

    public static final String PKG_AMAP  = "com.autonavi.minimap";
    public static final String PKG_BMAP  = "com.baidu.BaiduMap";
    public static final String PKG_TMAP  = "com.tencent.map";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        // 只挂主进程（广告 UI 都在主进程；百度有 :background / 腾讯有 :xweb 等子进程）
        if (lpparam.processName == null || lpparam.processName.contains(":")) {
            log("skip subprocess " + lpparam.packageName + "/" + lpparam.processName);
            return;
        }
        try {
            switch (lpparam.packageName) {
                case PKG_AMAP:
                    log("=== Amap hooks installing (pid) ===");
                    AmapHooks.install(lpparam.classLoader);
                    break;
                case PKG_BMAP:
                    log("=== BaiduMap hooks installing ===");
                    BmapHooks.install(lpparam.classLoader);
                    break;
                case PKG_TMAP:
                    log("=== TencentMap hooks installing ===");
                    TmapHooks.install(lpparam.classLoader);
                    break;
                default:
                    break;
            }
            if (lpparam.packageName.equals("io.github.ldxm666.mapadkiller")) {
                de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                    "io.github.ldxm666.mapadkiller.StatusCheck", lpparam.classLoader, "amEnabled",
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(Boolean.TRUE); }
                    });
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
            log("FATAL installing hooks in " + lpparam.packageName + ": " + t);
        }
    }

    static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
