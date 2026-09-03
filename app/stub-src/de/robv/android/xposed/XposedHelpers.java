package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

public final class XposedHelpers {
    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) throws ClassNotFoundException { return null; }
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) { return null; }
    public static Class<?> findAndInitClass(String className, ClassLoader classLoader, Object... additionalInstances) throws Throwable { return null; }
    public static Method findMethodIfExists(Class<?> classToAdd, String methodName, Object... parameterTypes) { return null; }
    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args) { return null; }
    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) throws NoSuchMethodException { return null; }
    public static Member findFieldIfExists(Class<?> clazz, String fieldName) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) { return null; }
    public static Object callMethod(Object obj, String methodName, Object... args) throws NoSuchMethodException { return null; }
    public static Object callMethod(Object obj, String methodName, Class<?>[] parameterTypes, Object... args) throws NoSuchMethodException { return null; }
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) throws NoSuchMethodException { return null; }
    public static Object getObjectField(Object obj, String fieldName) throws NoSuchFieldError { return null; }
    public static Object getStaticObjectField(Class<?> clazz, String fieldName) throws NoSuchFieldError { return null; }
    public static int getIntField(Object obj, String fieldName) throws NoSuchFieldError { return 0; }
    public static long getLongField(Object obj, String fieldName) throws NoSuchFieldError { return 0; }
    public static boolean getBooleanField(Object obj, String fieldName) throws NoSuchFieldError { return false; }
    public static void setIntField(Object obj, String fieldName, int value) throws NoSuchFieldError {}
    public static void setBooleanField(Object obj, String fieldName, boolean value) throws NoSuchFieldError {}
    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) throws NoSuchFieldError {}
    public static void setObjectField(Object obj, String fieldName, Object value) throws NoSuchFieldError {}
    public static Object newInstance(Class<?> clazz, Object... args) throws Throwable { return null; }
    public static Object newInstance(String className, ClassLoader classLoader, Object... args) throws Throwable { return null; }
    public static Constructor<?> getConstructorBestMatch(Class<?> clazz, Object... args) throws NoSuchMethodException { return null; }
    public static void setAdditionalInstanceField(Object obj, String key, Object value) {}
    public static Object getAdditionalInstanceField(Object obj, String key) { return null; }
    public static boolean instanceOf(Object obj, String className) { return false; }
    public static void hookAllMethodsHelper(Class<?> cls, String method, XC_MethodHook cb) {}
}
