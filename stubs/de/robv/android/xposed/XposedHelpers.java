package de.robv.android.xposed;
public final class XposedHelpers {
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader cl, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static int getIntField(Object obj, String fieldName) { return 0; }
    public static void setFloatField(Object obj, String fieldName, float value) { }
    public static Object getStaticObjectField(Class<?> clazz, String fieldName) { return null; }
    public static void setObjectField(Object obj, String fieldName, Object value) { }
    public static void setIntField(Object obj, String fieldName, int value) { }
    public static Class<?> findClass(String className, ClassLoader cl) { return null; }
}
