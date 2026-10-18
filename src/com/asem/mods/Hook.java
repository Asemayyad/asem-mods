package com.asem.mods;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ASEM Mods by Asem (t.me/Asemfuad) - Infinix NOTE 50 Pro 4G (X6855), XOS 16.
 *  1. XOS power menu (TrGlobalActionsDialogLite): adds "Recovery" and "Restart SystemUI"
 *     under Power off / Restart, built like the XOS items (same icon view class, circle,
 *     60dp icon, same label style). Tap once -> "Tap again" -> runs (no accidental taps).
 *  2. Lock / unlock: louder sound (x2 .. MAX, no clipping) + vibration synced with it
 *     (KeyguardViewMediator.playSound), both with switches in the app. The X6855 vibrator has no amplitude control,
 *     so every pulse is already full strength; the rhythm follows the sound.
 * Everything is wrapped in try/catch: a failure only logs to LSPosed, SystemUI keeps running.
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "ASEM Mods: ";
    private static final String SYSUI = "com.android.systemui";

    // {delay, on, off, on, ...} in ms - cut from the envelope of the lock / unlock sounds
    // (Lock: click at ~100 ms; Unlock: hit at ~30 ms), pulses stretched so they are felt.
    private static final long[] LOCK_PATTERN = {100, 80};
    private static final long[] UNLOCK_PATTERN = {30, 60};

    // Material icons (Apache 2.0), 24x24 viewport: "build" (wrench) and "settings_backup_restore"
    private static final String GLYPH_RECOVERY =
            "M22.7,19l-9.1,-9.1c0.9,-2.3 0.4,-5 -1.5,-6.9 -2,-2 -5,-2.4 -7.4,-1.3L9,6 6,9 1.6,4.7C0.4,7.1 0.9,10.1 2.9,12.1c1.9,1.9 4.6,2.4 6.9,1.5l9.1,9.1c0.4,0.4 1,0.4 1.4,0l2.3,-2.3c0.5,-0.4 0.5,-1.1 0.1,-1.4z";
    private static final String GLYPH_SYSUI =
            "M14,12c0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2 0.9,2 2,2 2,-0.9 2,-2zM12,3c-4.97,0 -9,4.03 -9,9H0l4,4 4,-4H5c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.51,0 -2.91,-0.49 -4.06,-1.3l-1.42,1.44C8.04,20.3 9.94,21 12,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if ("com.transsion.launcher3".equals(lp.packageName)) {
            try { LauncherIcons.hook(lp.classLoader); } catch (Throwable t) { log("launcher icons hook failed", t); }
            try { FrostedIcons.hook(lp.classLoader); } catch (Throwable t) { log("frosted icons hook failed", t); }
            return;
        }
        if (!SYSUI.equals(lp.packageName)) return;
        try { hookPowerMenu(lp.classLoader); } catch (Throwable t) { log("power menu hook failed", t); }
        try { hookLockHaptics(lp.classLoader); } catch (Throwable t) { log("lock haptics hook failed", t); }
        try { hookQsTiles(lp.classLoader); } catch (Throwable t) { log("QS tiles hook failed", t); }
        try { hookSliders(lp.classLoader); } catch (Throwable t) { log("volume panel hook failed", t); }
        try { VolumePanelButtons.hook(lp.classLoader); } catch (Throwable t) { log("volume panel buttons hook failed", t); }
        try { MusicHaptics.hook(lp.classLoader); } catch (Throwable t) { log("music haptics hook failed", t); }
    }

    // ------------------------------------------------------------------ QS tiles in the personalization color
    // XOS (TrQSTileView.updateIconFrameColors) gives the active icon circle the personalization
    // color ONLY for Wi-Fi / mobile data; all other tiles get white (ts_icon_bg_color_active_light)
    // with a colored icon. Here every active tile gets the Wi-Fi color (computed by XOS itself, so it
    // follows Settings > Personalization), semi-transparent, with a white icon - like the Wi-Fi tile.
    private static final int QS_ACTIVE_ALPHA = 0xB3;   // ~70 %
    private static final ThreadLocal<Boolean> IN_QS = new ThreadLocal<>();
    // QS tile views seen so far (weak) -> tile spec, to re-color them live when the icon color changes
    private static final java.util.Map<View, String> TILES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static boolean watching;

    /** Color published by the launcher part (LauncherIcons.KEY_COLOR), or null = use the XOS accent. */
    private static de.robv.android.xposed.XSharedPreferences sysuiPrefs;

    /** ASEM Mods app switch "Quick Settings follow icon color" (default on). */
    private static boolean qsFollowsIconColor() {
        try {
            if (sysuiPrefs == null) sysuiPrefs = new de.robv.android.xposed.XSharedPreferences("com.asem.mods", "asem_prefs");
            if (sysuiPrefs.hasFileChanged()) sysuiPrefs.reload();
            return sysuiPrefs.getBoolean("qs_icon_color", true);
        } catch (Throwable t) { return true; }
    }

    static int prefInt(String key, int def) {
        try {
            if (sysuiPrefs == null) sysuiPrefs = new de.robv.android.xposed.XSharedPreferences("com.asem.mods", "asem_prefs");
            if (sysuiPrefs.hasFileChanged()) sysuiPrefs.reload();
            return sysuiPrefs.getInt(key, def);
        } catch (Throwable t) { return def; }
    }

    static boolean prefOn(String key) {
        try {
            if (sysuiPrefs == null) sysuiPrefs = new de.robv.android.xposed.XSharedPreferences("com.asem.mods", "asem_prefs");
            if (sysuiPrefs.hasFileChanged()) sysuiPrefs.reload();
            return sysuiPrefs.getBoolean(key, true);
        } catch (Throwable t) { return true; }
    }

    private static Integer publishedIconColor(Context ctx) {
        if (!qsFollowsIconColor()) return null;                 // -> XOS accent, like stock
        return rawIconColor(ctx);
    }

    // ------------------------------------------------------------------ side volume panel (volume keys)
    // XOS volume dialog slider = com.transsion.systemui.widget.TrSeekbar (custom View). With the
    // XOS volume dialog its fill (mSecondTrackColor) is forced to white in setEnabled(). Only this
    // slider is changed - Quick Settings brightness / volume stay stock.
    private static final java.util.Map<View, Integer> SLIDERS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());   // view -> XOS original fill

    private static void hookSliders(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("com.transsion.systemui.widget.TrSeekbar", cl, "onDraw", android.graphics.Canvas.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try { tintVolumePanel((View) p.thisObject); } catch (Throwable t) { log("volume panel", t); }
                    }
                });
        // slider icons (speaker / stream icon at the bottom, "..." at the top): XOS bakes them into bitmaps
        // in its gray volume_seekbar_icon_color - make them white instead
        XC_MethodHook whiteIcons = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    XposedHelpers.setIntField(p.thisObject, "mBgIconFgColor", 0xFFFFFFFF);
                    XposedHelpers.setIntField(p.thisObject, "mBgSettingIconFgColor", 0xFFFFFFFF);
                } catch (Throwable t) { log("volume panel icons", t); }
            }
            // XOS draws the "..." at 80 % and the speaker waves at 50 %: make the baked icons solid white
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    for (String f : new String[]{"mBpIconFg", "mBpSettingIconFg"}) {
                        Object b = XposedHelpers.getObjectField(p.thisObject, f);
                        if (b instanceof android.graphics.Bitmap) XposedHelpers.setObjectField(p.thisObject, f, solidWhite((android.graphics.Bitmap) b));
                    }
                } catch (Throwable t) { log("volume panel icons", t); }
            }
        };
        XposedHelpers.findAndHookMethod("com.transsion.systemui.widget.TrSeekbar", cl, "setIconDrawable",
                android.graphics.drawable.Drawable.class, whiteIcons);
        XposedHelpers.findAndHookMethod("com.transsion.systemui.widget.TrSeekbar", cl, "setSettingIconDrawable",
                android.graphics.drawable.Drawable.class, whiteIcons);
        log("volume panel hooked", null);
    }

    private static android.graphics.Bitmap solidWhite(android.graphics.Bitmap src) {
        if (src.isRecycled()) return src;
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int a = Math.min(255, (px[i] >>> 24) * 2);   // 50 % -> full, edges stay smooth
            px[i] = (a << 24) | 0xFFFFFF;
        }
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static void tintVolumePanel(View v) throws Exception {
        java.lang.reflect.Field f = XposedHelpers.findClass("com.transsion.systemui.widget.TrSeekbar", v.getClass().getClassLoader())
                .getField("mSecondTrackColor");
        int cur = f.getInt(v);
        Integer want = (v.isEnabled() && prefOn("vol_icon_color")) ? rawIconColor(v.getContext()) : null;
        if (want != null) {
            if (cur != want) {
                if (!SLIDERS.containsKey(v)) SLIDERS.put(v, cur);       // remember XOS's own fill
                f.setInt(v, want);
            }
        } else {
            Integer orig = SLIDERS.remove(v);                            // switched off: give XOS's fill back
            if (orig != null && cur != orig) f.setInt(v, orig);
        }
    }

    private static void refreshSliders() {
        java.util.List<View> list;
        synchronized (SLIDERS) { list = new java.util.ArrayList<>(SLIDERS.keySet()); }
        for (View v : list) if (v != null) v.postInvalidate();
    }

    static Integer iconColorOrNull(Context ctx) { return rawIconColor(ctx); }

    private static Integer rawIconColor(Context ctx) {
        try {
            String s = android.provider.Settings.Global.getString(ctx.getContentResolver(), "asem_icon_color");
            if (s == null || s.isEmpty()) return null;
            return android.graphics.Color.parseColor(s);
        } catch (Throwable t) { return null; }
    }

    private static void watchIconColor(Context ctx) {
        if (watching) return;
        watching = true;
        Handler main = new Handler(Looper.getMainLooper());
        android.database.ContentObserver obs = new android.database.ContentObserver(main) {
            @Override public void onChange(boolean self) {
                try { recolorTiles(); } catch (Throwable t) { log("recolor", t); }
                try { refreshSliders(); } catch (Throwable t) { log("sliders refresh", t); }
            }
        };
        android.content.ContentResolver cr = ctx.getApplicationContext().getContentResolver();
        cr.registerContentObserver(android.provider.Settings.Global.getUriFor("asem_icon_color"), false, obs);
        // poked by the ASEM Mods app when its Quick Settings switch changes
        cr.registerContentObserver(android.provider.Settings.Global.getUriFor("asem_qs_refresh"), false, obs);
    }

    /** Icon color changed: let every tile recompute its colors and re-tint its active circle. */
    private static void recolorTiles() {
        java.util.List<java.util.Map.Entry<View, String>> list;
        synchronized (TILES) { list = new java.util.ArrayList<>(TILES.entrySet()); }
        for (java.util.Map.Entry<View, String> e : list) {
            try {
                View tile = e.getKey();
                if (tile == null) continue;
                tile.getClass().getMethod("updateIconFrameColors", Context.class, String.class)
                        .invoke(tile, tile.getContext(), e.getValue());
                Object frame = XposedHelpers.getObjectField(tile, "mIconFrame");
                if (frame == null) continue;
                int state = XposedHelpers.getIntField(frame, "mState");
                frame.getClass().getField("mTintColor").setInt(frame, 0);    // force a re-tint
                frame.getClass().getMethod("onTintColorChanged", int.class).invoke(frame, state);
                ((View) frame).invalidate();
            } catch (Throwable t) { log("recolor tile", t); }
        }
        log("QS tiles re-colored (" + list.size() + ")", null);
    }

    private static void hookQsTiles(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("com.transsion.systemui.qs.tileimpl.TrQSTileView", cl,
                "updateIconFrameColors", Context.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (Boolean.TRUE.equals(IN_QS.get())) return;
                        try {
                            Object frame = XposedHelpers.getObjectField(p.thisObject, "mIconFrame");
                            if (frame == null) return;
                            Context ctx0 = (Context) p.args[0];
                            TILES.put((View) p.thisObject, (String) p.args[1]);
                            watchIconColor(ctx0);
                            Integer iconColor = publishedIconColor(ctx0);
                            int accent;
                            if (iconColor != null) {
                                // the color picked in Settings > Wallpaper & personalization > Icon > Color
                                accent = iconColor;
                            } else if ("wifi".equals(p.args[1])) {
                                accent = XposedHelpers.getIntField(frame, "mColorActive");
                            } else {
                                // let XOS compute the Wi-Fi (personalization) color for this tile
                                IN_QS.set(true);
                                try {
                                    java.lang.reflect.Method m = p.thisObject.getClass().getMethod(
                                            "updateIconFrameColors", Context.class, String.class);
                                    m.invoke(p.thisObject, p.args[0], "wifi");
                                    accent = XposedHelpers.getIntField(frame, "mColorActive");
                                } finally { IN_QS.set(false); }
                            }
                            int c = (QS_ACTIVE_ALPHA << 24) | (accent & 0x00ffffff);
                            frame.getClass().getField("mColorActive").setInt(frame, c);
                        } catch (Throwable t) { log("QS frame color", t); }
                    }
                });
        // active icons white (on the colored circle), like the Wi-Fi tile
        XposedHelpers.findAndHookMethod("com.android.systemui.qs.tileimpl.TrQSIconViewImpl", cl,
                "getColor", int.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if ((Integer) p.args[0] != 2) return;
                            if (Boolean.TRUE.equals(XposedHelpers.getObjectField(p.thisObject, "mInCustomizer"))) return;
                            p.setResult(0xffffffff);
                        } catch (Throwable t) { log("QS icon color", t); }
                    }
                });
        // flashlight: XOS tints its foreground (beam) with the circle color -> would vanish; keep it white
        XposedHelpers.findAndHookMethod("com.transsion.systemui.qs.tileimpl.TrQSTileView", cl,
                "handleStateChanged", XposedHelpers.findClass("com.android.systemui.plugins.qs.QSTile$State", cl),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object st = p.args[0];
                            if (XposedHelpers.getIntField(st, "state") != 2) return;
                            if (!"osflashlight".equals(XposedHelpers.getObjectField(st, "spec"))) return;
                            Object icon = XposedHelpers.getObjectField(p.thisObject, "mIcon");
                            Object fg = icon.getClass().getMethod("getForegroundIcon").invoke(icon);
                            if (fg instanceof ImageView)
                                ((ImageView) fg).setImageTintList(android.content.res.ColorStateList.valueOf(0xffffffff));
                        } catch (Throwable t) { log("QS flashlight", t); }
                    }
                });
        log("QS tiles hooked", null);
    }

    private static void log(String msg, Throwable t) {
        XposedBridge.log(TAG + msg + (t == null ? "" : ": " + t));
    }

    // ------------------------------------------------------------------ lock / unlock sound + haptics
    // Louder lock / unlock sounds (ASEM Mods app: switch + level slider x2 .. MAX, clean gain only - no limiter / clipping). The sounds ship
    // in the app's assets (sounds/lock_N.ogg, unlock_N.ogg, N = slider position). They are loaded into
    // KeyguardViewMediator's own SoundPool after setupLocked(); playSound(id) is then pointed at the
    // chosen level - live, no restart. Switch off = XOS's own sound files (also loaded here, so it
    // does not depend on Settings.Global lock_sound, which the old separate x8 mod changed).
    static final int SOUND_LEVELS = 6;
    private static final int[] LOCK_IDS = new int[SOUND_LEVELS], UNLOCK_IDS = new int[SOUND_LEVELS];
    private static int stockLockId, stockUnlockId;

    private static void hookLockHaptics(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("com.android.systemui.keyguard.KeyguardViewMediator", cl,
                "setupLocked", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try { loadLockSounds(p.thisObject); } catch (Throwable t) { log("lock sounds", t); }
                    }
                });
        XposedHelpers.findAndHookMethod("com.android.systemui.keyguard.KeyguardViewMediator", cl,
                "playSound", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            int id = (Integer) p.args[0];
                            if (id == 0) return;
                            boolean lock = id == XposedHelpers.getIntField(p.thisObject, "mLockSoundId");
                            boolean unlock = !lock && id == XposedHelpers.getIntField(p.thisObject, "mUnlockSoundId");
                            if (!lock && !unlock) return;
                            if (prefOn("lock_haptics")) {
                                Context ctx = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                                vibrate(ctx, lock ? LOCK_PATTERN : UNLOCK_PATTERN);
                            }
                            int lv = Math.max(0, Math.min(SOUND_LEVELS - 1, prefInt("lock_sound_level", 2)));
                            int swap = prefOn("lock_sound_boost") ? (lock ? LOCK_IDS[lv] : UNLOCK_IDS[lv])
                                    : (lock ? stockLockId : stockUnlockId);
                            if (swap != 0) p.args[0] = swap;
                        } catch (Throwable t) { log("lock sound / haptic", t); }
                    }
                });
        log("lock/unlock sound + haptics hooked", null);
    }

    private static void loadLockSounds(Object kvm) throws Exception {
        android.media.SoundPool sp = (android.media.SoundPool) XposedHelpers.getObjectField(kvm, "mLockSounds");
        Context ctx = (Context) XposedHelpers.getObjectField(kvm, "mContext");
        if (sp == null || ctx == null) return;
        stockLockId = sp.load("/tr_product/media/audio/ui/Lock.ogg", 1);
        stockUnlockId = sp.load("/tr_product/media/audio/ui/Unlock.ogg", 1);
        android.content.res.AssetManager am = ctx.createPackageContext("com.asem.mods", 0).getAssets();
        int n = 0;
        for (int i = 0; i < SOUND_LEVELS; i++) {
            try (android.content.res.AssetFileDescriptor l = am.openFd("sounds/lock_" + i + ".ogg");
                 android.content.res.AssetFileDescriptor u = am.openFd("sounds/unlock_" + i + ".ogg")) {
                LOCK_IDS[i] = sp.load(l, 1);
                UNLOCK_IDS[i] = sp.load(u, 1);
                n++;
            } catch (Throwable t) { log("lock sound level " + i, t); }
        }
        log("lock sounds loaded: " + n + " levels (stock " + stockLockId + "/" + stockUnlockId + ")", null);
    }

    private static void vibrate(Context ctx, long[] pattern) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am != null && am.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return;
        VibratorManager vm = ctx.getSystemService(VibratorManager.class);
        Vibrator v = vm != null ? vm.getDefaultVibrator() : null;
        if (v == null || !v.hasVibrator()) return;
        VibrationAttributes attrs = new VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK).build();
        v.vibrate(VibrationEffect.createWaveform(pattern, -1), attrs);
    }

    // ------------------------------------------------------------------ power menu
    private static void hookPowerMenu(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(
                "com.transsion.systemui.globalactions.TrGlobalActionsDialogLite$TrActionsDialogLite", cl,
                "initializeLayout", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try { addButtons(p.thisObject, cl); } catch (Throwable t) { log("adding power menu buttons", t); }
                    }
                });
        log("power menu hooked", null);
    }

    private static void addButtons(Object dialog, ClassLoader cl) {
        ViewGroup container = (ViewGroup) XposedHelpers.getObjectField(dialog, "mContainer");
        View power = (View) XposedHelpers.getObjectField(dialog, "mPowerOffActionView");
        View reboot = (View) XposedHelpers.getObjectField(dialog, "mRebootActionView");
        if (!(container instanceof RelativeLayout) || power == null || reboot == null) {
            log("unexpected power menu layout - buttons not added", null);
            return;
        }
        Context ctx = container.getContext();
        Resources r = ctx.getResources();
        ImageView refImg = reboot.findViewById(r.getIdentifier("global_action_image", "id", SYSUI));
        TextView refText = reboot.findViewById(r.getIdentifier("global_action_text", "id", SYSUI));
        View cover = container.findViewById(r.getIdentifier("global_view_cover", "id", SYSUI));
        if (refImg == null || refText == null) { log("reference views not found", null); return; }

        boolean liquid = false;
        try {
            Object o = XposedHelpers.findClass("com.android.systemui.qs.util.QsTilesUtil", cl)
                    .getMethod("useLiquidGlassEffect").invoke(null);
            liquid = Boolean.TRUE.equals(o);
        } catch (Throwable ignored) { }

        boolean ar = "ar".equals(Locale.getDefault().getLanguage());
        String again = ar ? "اضغط مرة أخرى" : "Tap again";
        View recovery = makeItem(ctx, refImg, refText, reboot, GLYPH_RECOVERY, liquid,
                ar ? "الريكفري" : "Recovery", again, () -> {
                    dismiss(dialog);
                    // XOS SystemUI has no REBOOT permission: ask the ASEM Mods app (root) to do it
                    try {
                        android.content.Intent i = new android.content.Intent("com.asem.mods.REBOOT_RECOVERY")
                                .setClassName("com.asem.mods", "com.asem.mods.RebootReceiver")
                                .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                                        | android.content.Intent.FLAG_RECEIVER_FOREGROUND);
                        ctx.sendBroadcast(i);
                    } catch (Throwable t) { log("reboot recovery", t); }
                });
        View sysui = makeItem(ctx, refImg, refText, reboot, GLYPH_SYSUI, liquid,
                ar ? "إعادة تشغيل الواجهة" : "Restart SystemUI", again, () -> {
                    dismiss(dialog);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 300);
                });

        int w = power.getLayoutParams() != null ? power.getLayoutParams().width : ViewGroup.LayoutParams.WRAP_CONTENT;
        RelativeLayout.LayoutParams l1 = new RelativeLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        l1.addRule(RelativeLayout.BELOW, power.getId());
        l1.addRule(RelativeLayout.ALIGN_PARENT_START);
        RelativeLayout.LayoutParams l2 = new RelativeLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        l2.addRule(RelativeLayout.BELOW, reboot.getId());
        l2.addRule(RelativeLayout.ALIGN_PARENT_END);
        container.addView(recovery, l1);
        container.addView(sysui, l2);

        // follow the XOS items: same show/hide animation state, hidden while a slide-to-confirm is open
        container.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                try {
                    boolean coverShown = cover != null && cover.getVisibility() == View.VISIBLE;
                    mirror(power, recovery, coverShown);
                    mirror(reboot, sysui, coverShown);
                } catch (Throwable t) { log("preDraw", t); }
                return true;
            }
        });
        log("power menu buttons added (liquid=" + liquid + ")", null);
    }

    private static void mirror(View src, View dst, boolean hide) {
        float a = hide || src.getVisibility() != View.VISIBLE ? 0f : src.getAlpha();
        if (dst.getAlpha() != a) dst.setAlpha(a);
        dst.setEnabled(a > 0.5f);
        if (dst.getScaleX() != src.getScaleX()) dst.setScaleX(src.getScaleX());
        if (dst.getScaleY() != src.getScaleY()) dst.setScaleY(src.getScaleY());
        if (dst.getTranslationY() != src.getTranslationY()) dst.setTranslationY(src.getTranslationY());
    }

    private static void dismiss(Object dialog) {
        try { ((android.app.Dialog) dialog).dismiss(); } catch (Throwable t) { log("dismiss", t); }
    }

    private static View makeItem(Context ctx, ImageView refImg, TextView refText, View refItem, String glyph,
                                 boolean liquid, String label, String again, Runnable action) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setMinimumHeight(refItem.getMinimumHeight());

        ImageView img;
        try { img = (ImageView) refImg.getClass().getConstructor(Context.class).newInstance(ctx); }
        catch (Throwable t) { img = new ImageView(ctx); }
        ViewGroup.LayoutParams rl = refImg.getLayoutParams();
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(rl.width, rl.height);
        if (rl instanceof ViewGroup.MarginLayoutParams) il.topMargin = ((ViewGroup.MarginLayoutParams) rl).topMargin;
        img.setScaleType(refImg.getScaleType());
        img.setImageDrawable(new GlyphDrawable(glyph, !liquid));
        item.addView(img, il);

        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, refText.getTextSize());
        tv.setTextColor(refText.getTextColors());
        tv.setTypeface(refText.getTypeface());
        tv.setPadding(refText.getPaddingLeft(), refText.getPaddingTop(), refText.getPaddingRight(), refText.getPaddingBottom());
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setMaxLines(3);
        tv.setText(label);
        item.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Handler h = new Handler(Looper.getMainLooper());
        final boolean[] armed = {false};
        item.setOnClickListener(v -> { try { onTap(ctx, tv, label, again, armed, h, action); } catch (Throwable t) { log("tap", t); } });
        item.setContentDescription(label);
        return item;
    }

    private static void onTap(Context ctx, TextView tv, String label, String again, boolean[] armed, Handler h, Runnable action) {
        {
            // respect the lock screen / XOS anti-theft: no recovery or SystemUI restart while locked
            try {
                android.app.KeyguardManager km = ctx.getSystemService(android.app.KeyguardManager.class);
                if (km != null && km.isDeviceLocked()) {
                    boolean ar = "ar".equals(Locale.getDefault().getLanguage());
                    tv.setText(ar ? "افتح القفل أولاً" : "Unlock first");
                    h.postDelayed(() -> tv.setText(label), 2500);
                    return;
                }
            } catch (Throwable t) { log("keyguard check", t); }
            if (armed[0]) { action.run(); return; }
            armed[0] = true;
            tv.setText(again);
            h.postDelayed(() -> { armed[0] = false; tv.setText(label); }, 3000);
        }
    }

    /** Same look as XOS ic_*_v3: 20% black circle + white glyph (circle left out in liquid-glass mode). */
    static final class GlyphDrawable extends Drawable {
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path glyph;
        private final boolean drawCircle;

        GlyphDrawable(String pathData, boolean drawCircle) {
            this.drawCircle = drawCircle;
            circle.setColor(0x33000000);
            fg.setColor(0xffffffff);
            Path p = null;
            try {
                p = (Path) Class.forName("android.util.PathParser")
                        .getMethod("createPathFromPathData", String.class).invoke(null, pathData);
            } catch (Throwable t) { log("path parse", t); }
            glyph = p;
        }

        @Override public void draw(Canvas c) {
            try { drawInner(c); } catch (Throwable t) { log("icon draw", t); }
        }

        private void drawInner(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            if (drawCircle) c.drawCircle(cx, cy, s / 2f, circle);
            if (glyph != null) {
                // XOS glyphs span ~73/180 of the icon; Material glyphs ~20/24 of their box
                float scale = s * 0.40f / 20f;
                Matrix m = new Matrix();
                m.setTranslate(-12f, -12f);
                m.postScale(scale, scale);
                m.postTranslate(cx, cy);
                Path out = new Path();
                glyph.transform(m, out);
                c.drawPath(out, fg);
            }
        }
        @Override public void setAlpha(int a) { fg.setAlpha(a); circle.setAlpha(a * 0x33 / 255); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { fg.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return -1; }
        @Override public int getIntrinsicHeight() { return -1; }
    }
}
