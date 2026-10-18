package de.robv.android.xposed;
public final class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public boolean hasFileChanged() { return false; }
    public void reload() {}
    public String getString(String key, String defValue) { return defValue; }
    public boolean getBoolean(String key, boolean defValue) { return defValue; }
    public int getInt(String key, int defValue) { return defValue; }
}
