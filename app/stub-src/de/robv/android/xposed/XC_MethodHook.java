package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public abstract class XC_MethodHook {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public Method hookedMethod;
        public boolean isUnhooked() { return false; }
        public Object getResult() throws Throwable { return result; }
        public void setResult(Object result) { this.result = result; }
    }

    public static abstract class Callback {
        public void beforeHookedMethod(MethodHookParam param) throws Throwable {}
        public void afterHookedMethod(MethodHookParam param) throws Throwable {}
    }

    public Unhook unhook() { return null; }

    public abstract static class Unhook {
        public Method getMethod() { return null; }
        public Constructor<?> getConstructor() { return null; }
        public void unhook() {}
    }
}
