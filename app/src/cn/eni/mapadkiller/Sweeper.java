package cn.eni.mapadkiller;

import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 在 Activity onResume 后延迟清扫视图树（异步广告兜底） */
public final class Sweeper {

    private Sweeper() {}

    public static void install(ViewKiller killer) {
        try {
            XposedBridge.hookAllMethods(android.app.Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final android.app.Activity act = (android.app.Activity) param.thisObject;
                    Handler h = new Handler(Looper.getMainLooper());
                    h.postDelayed(new Runnable() {
                        @Override public void run() { killer.sweep(act); }
                    }, 150);
                    h.postDelayed(new Runnable() {
                        @Override public void run() { killer.sweep(act); }
                    }, 1500);
                }
            });
            MainHook.log("sweeper installed");
        } catch (Throwable t) {
            MainHook.log("sweeper fail " + t);
        }
    }
}
