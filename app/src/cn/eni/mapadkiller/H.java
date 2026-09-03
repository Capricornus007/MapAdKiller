package cn.eni.mapadkiller;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 通用挂钩工具：计数 + 容错。只用真实存在的 API（不依赖 XC_MethodReplacement 之外的助手）。 */
public final class H {

    public static final AtomicInteger ok = new AtomicInteger();
    public static final AtomicInteger fail = new AtomicInteger();

    private H() {}

    /** 吞掉方法：before 里 setResult(null)（void 方法安全；boolean 请用 RETURNS_FALSE） */
    public static final XC_MethodHook VOID = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null);
        }
    };

    public static final XC_MethodHook RETURNS_FALSE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(Boolean.FALSE);
        }
    };

    public static final XC_MethodHook RETURNS_NULL = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null);
        }
    };

    public static Class<?> cls(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** hook 指定签名方法；params 为空时按名 hook 全部重载 */
    public static void hook(Class<?> c, String method, XC_MethodHook cb, Object... params) {
        if (c == null) return;
        try {
            if (params.length == 0) {
                XposedBridge.hookAllMethods(c, method, cb);
                count(true, c, method + "*");
                return;
            }
            List<Object> va = new ArrayList<>();
            for (Object p : params) {
                if (p instanceof Class || p instanceof String) va.add(p);
                else if (p == null) { count(false, c, method + " (null param)"); return; }
                else va.add(p.getClass());
            }
            va.add(cb);
            XposedHelpers.findAndHookMethod(c, method, va.toArray());
            count(true, c, method);
        } catch (Throwable t) {
            count(false, c, method + " (" + t.getMessage() + ")");
        }
    }

    /** 按名字 hook 类中所有该方法名 */
    public static void hookByName(Class<?> c, String method, XC_MethodHook cb) {
        if (c == null) return;
        try {
            XposedBridge.hookAllMethods(c, method, cb);
            count(true, c, method + "*");
        } catch (Throwable t) {
            count(false, c, method + "* (" + t.getMessage() + ")");
        }
    }

    public static void swallow(Class<?> c, String method, Object... params) {
        hook(c, method, VOID, params);
    }

    public static void returnsFalse(Class<?> c, String method, Object... params) {
        hook(c, method, RETURNS_FALSE, params);
    }

    public static void returnsNull(Class<?> c, String method, Object... params) {
        hook(c, method, RETURNS_NULL, params);
    }

    private static void count(boolean success, Class<?> c, String m) {
        if (success) {
            ok.incrementAndGet();
            MainHook.log("HOOKED  " + c.getName() + "." + m);
        } else {
            fail.incrementAndGet();
            MainHook.log("miss    " + (c == null ? "?" : c.getName()) + "." + m);
        }
    }
}
