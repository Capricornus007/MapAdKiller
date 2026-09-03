package de.robv.android.xposed;

public abstract class XC_MethodReplacement extends XC_MethodHook {
    public XC_MethodReplacement() {}
    public XC_MethodReplacement(int priority) {}

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        replaceHookedMethod(param);
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) throws Throwable {}

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    public static XC_MethodReplacement returnConstant(final Object value) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return value;
            }
        };
    }

    public static XC_MethodReplacement returnVoid() {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return null;
            }
        };
    }
}
