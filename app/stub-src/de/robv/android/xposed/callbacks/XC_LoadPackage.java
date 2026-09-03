package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;
import de.robv.android.xposed.XC_MethodHook;

public abstract class XC_LoadPackage extends XC_MethodHook {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class LoadPackageParam {
        public ApplicationInfo appInfo;
        public String packageName;
        public String processName;
        public boolean isFirstApplication;
        public ClassLoader classLoader;
    }

    public abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
