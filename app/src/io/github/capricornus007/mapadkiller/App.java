package io.github.capricornus007.mapadkiller;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
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

    public static boolean writeInt(String key, int value) {
        XposedService s = service;
        if (s == null) return false;
        try {
            return s.getRemotePreferences(Config.PREF_GROUP)
                    .edit().putInt(key, value).commit();
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

    /** 写入字符串集合（自动学习到的广告 SDK 清单走这条路：App 侧写，hook 侧只读） */
    public static boolean writeStringSet(String key, java.util.Set<String> value) {
        XposedService s = service;
        if (s == null) return false;
        try {
            return s.getRemotePreferences(Config.PREF_GROUP)
                    .edit().putStringSet(key, new java.util.LinkedHashSet<>(value)).commit();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- 服务未绑定时收到上报，先挂这里，绑定后补写 ----
    private static final java.util.Set<String> pendingLearned = new java.util.LinkedHashSet<>();

    public static synchronized void addPendingLearned(java.util.Collection<String> roots) {
        pendingLearned.addAll(roots);
    }

    private static synchronized void flushPending() {
        if (pendingLearned.isEmpty()) return;
        try {
            java.util.Set<String> merged = new java.util.LinkedHashSet<>();
            XposedService s = service;
            if (s != null) {
                java.util.Set<String> old = s.getRemotePreferences(Config.PREF_GROUP)
                        .getStringSet("sdk_learned", null);
                if (old != null) merged.addAll(old);
            }
            merged.addAll(pendingLearned);
            if (s != null && s.getRemotePreferences(Config.PREF_GROUP).edit()
                    .putStringSet("sdk_learned", merged).commit()) {
                pendingLearned.clear();
            }
        } catch (Throwable ignored) {}
    }

    // ══════════════════════════════════════════════════════════ 桌面图标隐藏

    /**
     * 「隐藏桌面图标」开关。
     *
     * 实现方式：MainActivity 自己**不带** MAIN/LAUNCHER（因此不进桌面），
     * 桌面入口挂在 <activity-alias name=".LauncherAlias"> 上。
     * 开关只改这个 alias 的组件启用状态；MainActivity 本体始终 enabled + exported，
     * 所以隐藏图标后本设置页依然可以被显式组件名拉起：
     *     adb shell am start -n io.github.capricornus007.mapadkiller/.MainActivity
     *
     * 状态存在模块 App 自己的 SharedPreferences（不进 LSPosed RemotePreferences）——
     * 这是 App 侧组件可见性，与 hook 侧配置无关，也不需要地图 App 重启。
     */
    /**
     * v1.0.8 换过键名与组件名（hide_launcher_icon / .LauncherAlias → hide_icon_v2 / .DesktopAlias）。
     *
     * 原因：旧版把桌面入口禁掉之后，LSPosed 管理器解析模块设置入口用的是同一个
     * MAIN + LAUNCHER 查询，于是入口一起消失 —— 用户被锁在设置页外面。
     * 换名之后旧机上残留的「已禁用」组件覆盖记录不再匹配，新组件默认启用，
     * 升级一次图标就自己回来了；再配上磁贴入口，这个坑不会再踩第二次。
     */
    public static final String LAUNCHER_ALIAS = Config.PKG + ".DesktopAlias";



    @Override
    public void onCreate() {
        super.onCreate();
        syncIconFromConfig(this);
        try {
            XposedServiceHelper.registerListener(this);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onServiceBind(XposedService s) {
        service = s;
        flushPending();          // 补写服务没连上时收到的学习结果
        try { LearnedProvider.flushToRemote(getApplicationContext()); } catch (Throwable ignored) {}
        // 真实配置到手，把桌面图标状态摆正
        try { syncIconFromConfig(getApplicationContext()); } catch (Throwable ignored) {}
    }

    // ────────────────────────────────────────────────── 桌面图标开关

    /** launcher 别名组件（manifest 里的 activity-alias），图标开关就是切它的 enabled */

    private static final String CH_ID = "mapadkiller_entry";
    private static final int NOTI_ID = 0x4DA1;

    /** 从 RemotePreferences 读配置再摆正图标（服务绑上后调用） */
    public static void syncIconFromConfig(Context ctx) {
        boolean hide = false;
        try {
            XposedService s = service;
            if (s != null) {
                hide = s.getRemotePreferences(Config.PREF_GROUP)
                        .getBoolean(Config.K_HIDE_ICON, false);
            }
        } catch (Throwable ignored) {}
        applyIconVisibility(ctx, hide, true);
    }

    /**
     * 切换桌面图标可见性。
     *
     * 隐藏时挂一条常驻通知当"回家的路" —— 这是本模块唯一能保证用户还进得来设置页的手段，
     * 因为图标一旦被禁掉，桌面和 LSPosed 管理器的"打开"都找不到了。
     * 手动兜底：adb shell am start -n io.github.capricornus007.mapadkiller/.MainActivity
     */
    public static void applyIconVisibility(Context ctx, boolean hide, boolean notify) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ComponentName cn = new ComponentName(ctx.getPackageName(), LAUNCHER_ALIAS);
            pm.setComponentEnabledSetting(cn,
                    hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                         : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {}
        if (!notify) return;
        try {
            if (hide) postEntryNotification(ctx);
            else cancelEntryNotification(ctx);
        } catch (Throwable ignored) {}
    }

    /** 通知权限请求码（设置页 onRequestPermissionsResult 用同一个值） */
    public static final int REQ_NOTIFY = 0x4DA2;

    /**
     * 申请通知权限（Android 13+ 才有这个运行时权限）。
     * 只在 Activity 上调用；拿不到就返回，由调用方照常执行隐藏动作。
     */
    public static void ensureNotificationPermission(android.app.Activity act) {
        try {
            if (Build.VERSION.SDK_INT < 33) return;   // 13 之前没有这个运行时权限
            if (act.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED) return;
            act.requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
        } catch (Throwable ignored) {}
    }

    // minSdkVersion=26，所以通知渠道/构造器全部按 8.0 的写法来，不碰已过时 API
    // （javac 往 stderr 打一条 deprecation 注，build.ps1 的 Stop 就会当成构建失败）
    private static void postEntryNotification(Context ctx) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CH_ID, "模块入口", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        Intent it = new Intent(ctx, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, CH_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("MapAdKiller 正在运行")
                .setContentText("桌面图标已隐藏 · 点按回到设置页")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        nm.notify(NOTI_ID, n);
    }

    private static void cancelEntryNotification(Context ctx) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onServiceDied(XposedService s) {
        if (service == s) service = null;
    }
}
