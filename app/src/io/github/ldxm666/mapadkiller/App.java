package io.github.ldxm666.mapadkiller;

import android.app.Application;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 进程：绑定 LSPosed 服务（用于写 RemotePreferences）。
 * Hook 进程不经过本类（Config 走 XposedModule#getRemotePreferences）。
 */
public final class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static volatile XposedService service;

    public static XposedService svc() { return service; }

    /** App 侧写入；返回 false 表示服务未连接或写入未落盘（UI 提示稍后重试） */
    public static boolean writeBoolean(String key, boolean value) {
        XposedService s = service;
        if (s == null) return false;
        try {
            // 必须用 commit()：apply() 是异步落盘，用户切完开关立刻退出设置页/清后台时
            // 这次写入会被丢掉，表现就是"设完再打开又全变回开启"。
            return s.getRemotePreferences(Config.PREF_GROUP)
                    .edit().putBoolean(key, value).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 恢复默认：清空全部配置键（全部显示） */
    public static boolean clearAll() {
        XposedService s = service;
        if (s == null) return false;
        try {
            return s.getRemotePreferences(Config.PREF_GROUP).edit().clear().commit();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            XposedServiceHelper.registerListener(this);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onServiceBind(XposedService s) {
        service = s;
    }

    @Override
    public void onServiceDied(XposedService s) {
        if (service == s) service = null;
    }
}
