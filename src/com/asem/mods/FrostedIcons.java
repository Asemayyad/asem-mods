package com.asem.mods;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ASEM Mods - "Frosted blur" icon plates for the XOS Launcher (16.3.0.380).
 * XOS already keeps a blurred copy of the wallpaper (also the live "pictorial" one) for its
 * folder / edit screens: BlurState.updateGaoSiBg(Bitmap), cached file o4.d.D(Context).
 * Behind every app icon (BubbleTextView) the matching piece of that blurred wallpaper is drawn,
 * cut to the XOS icon shape (u8.e.b() shape bitmap, as an ALPHA_8 mask + BitmapShader = one draw
 * call per icon), plus a light white veil. The icons themselves are baked with an empty plate
 * (LauncherIcons). Wide (scrolling) wallpapers follow the home-page scroll like the wallpaper.
 */
final class FrostedIcons {
    private static final String TAG = "ASEM Mods (frosted): ";
    private static final int VEIL = 0x4DFFFFFF;            // ~30 % white over the blur (lighter frost)
    private static volatile Bitmap blur;
    private static Bitmap maskSrc;                          // XOS icon shape (any size)
    private static Bitmap mask;                             // ALPHA_8 copy at the last icon size
    private static int maskSize;
    private static WeakReference<Activity> launcherRef = new WeakReference<>(null);
    private static boolean loggedSize;
    private static final Paint SHADER_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint VEIL_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Matrix M = new Matrix();
    private static final Rect R = new Rect();
    private static final int[] LOC = new int[2];

    private FrostedIcons() {}

    private static void log(String m, Throwable t) { XposedBridge.log(TAG + m + (t == null ? "" : ": " + t)); }

    static void hook(ClassLoader cl) {
        VEIL_PAINT.setColor(VEIL);
        // blurred wallpaper updates from XOS
        XposedHelpers.findAndHookMethod("com.transsion.launcher3.BlurState", cl, "updateGaoSiBg", Bitmap.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { setBlur((Bitmap) p.args[0]); }
                });
        // remember the launcher activity (for the blurred wallpaper file + page scroll)
        XposedHelpers.findAndHookMethod("com.transsion.launcher3.TrLauncher", cl, "onCreate", android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        launcherRef = new WeakReference<>((Activity) p.thisObject);
                    }
                });
        XposedHelpers.findAndHookMethod("com.android.launcher3.BubbleTextView", cl, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try { draw((View) p.thisObject, (Canvas) p.args[0], cl); } catch (Throwable t) { log("draw", t); }
                    }
                });
        log("hooked", null);
    }

    private static void setBlur(Bitmap b) {
        if (b == null || b.isRecycled()) return;
        blur = b;
        if (!loggedSize) { loggedSize = true; log("blurred wallpaper " + b.getWidth() + "x" + b.getHeight(), null); }
    }

    private static Bitmap blurBitmap(ClassLoader cl) {
        Bitmap b = blur;
        if (b != null && !b.isRecycled()) return b;
        Activity l = launcherRef.get();
        if (l == null) return null;
        try {   // the one XOS already holds
            Object st = XposedHelpers.getObjectField(l, "blurState");
            if (st != null) {
                Object bm = XposedHelpers.getObjectField(st, "mBlurBitmap");
                if (bm instanceof Bitmap && !((Bitmap) bm).isRecycled()) { setBlur((Bitmap) bm); return (Bitmap) bm; }
            }
        } catch (Throwable ignored) { }
        try {   // XOS's cached blurred wallpaper file
            File f = (File) XposedHelpers.findClass("o4.d", cl).getMethod("D", Context.class).invoke(null, l);
            if (f != null && f.exists()) {
                Bitmap d = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (d != null) { setBlur(d); return d; }
            }
        } catch (Throwable t) { log("blur file", t); }
        return null;
    }

    private static Bitmap shapeMask(ClassLoader cl, int size) {
        if (mask != null && maskSize == size) return mask;
        try {
            if (maskSrc == null || maskSrc.isRecycled())
                maskSrc = (Bitmap) XposedHelpers.findClass("u8.e", cl).getMethod("b").invoke(null);
        } catch (Throwable t) { maskSrc = null; }
        Bitmap a = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(a);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (maskSrc != null) c.drawBitmap(maskSrc, null, new Rect(0, 0, size, size), p);
        else c.drawRoundRect(0, 0, size, size, size * 0.24f, size * 0.24f, p);   // fallback shape
        mask = a; maskSize = size;
        return a;
    }

    private static final Paint FILL = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint SHADOW = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Paint GLOSS = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static Bitmap tintFrom;
    private static int tintColor = 0x66E94848, solidColor = 0xFFF6D9DC;

    /** Colors that match the wallpaper, from XOS's blurred copy of it (saturation-weighted hue). */
    private static void wallpaperColors(Bitmap b) {
        if (b == null || b == tintFrom) return;
        tintFrom = b;
        int w = b.getWidth(), h = b.getHeight(), step = Math.max(1, Math.min(w, h) / 24);
        float sx = 0, sy = 0, ws = 0, sv = 0; int n = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < h; y += step) for (int x = 0; x < w; x += step) {
            Color.colorToHSV(b.getPixel(x, y), hsv);
            float wgt = hsv[1] * hsv[2] + 0.02f;
            double r = Math.toRadians(hsv[0]);
            sx += Math.cos(r) * wgt; sy += Math.sin(r) * wgt; ws += hsv[1] * wgt; sv += wgt; n++;
        }
        float hue = (float) ((Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360);
        float sat = sv > 0 ? ws / sv : 0.3f;
        tintColor = (0x70 << 24) | (Color.HSVToColor(new float[]{hue, Math.max(0.45f, sat), 0.78f}) & 0xffffff);
        solidColor = Color.HSVToColor(new float[]{hue, Math.max(0.12f, Math.min(0.32f, sat * 0.45f)), 0.97f});
    }

    private static void draw(View v, Canvas canvas, ClassLoader cl) throws Exception {
        String style = LauncherIcons.plateModePublic();
        if (!LauncherIcons.live(style)) return;
        if ("auto".equals(style)) style = LauncherIcons.night(v.getContext()) ? "frosted_dark" : "frosted";
        int display = XposedHelpers.getIntField(v, "mDisplay");
        // home pages, dock, folders, app drawer, desktop
        if (display != 0 && display != 10 && display != 2 && display != 1 && display != 12 && display != 13) return;
        // only views that really show an app icon (not folder names / labels)
        try { if (v.getClass().getMethod("getIcon").invoke(v) == null) return; } catch (Throwable ignored) { }
        v.getClass().getMethod("getIconBounds", Rect.class).invoke(v, R);
        if (R.width() <= 0) return;
        Bitmap m = shapeMask(cl, R.width());
        Bitmap b = blurBitmap(cl);
        wallpaperColors(b);

        if (LauncherIcons.fx3d()) {                                   // 3D: soft shadow under the plate
            SHADOW.setColor(0x59000000);
            SHADOW.setMaskFilter(new android.graphics.BlurMaskFilter(R.width() * 0.07f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            int save = canvas.save();
            canvas.translate(0, R.height() * 0.05f);
            canvas.drawBitmap(m, null, R, SHADOW);
            canvas.restoreToCount(save);
        }

        boolean blurStyle = "frosted".equals(style) || "frosted_dark".equals(style) || "tinted".equals(style);
        if (blurStyle && b != null) {                                 // blurred wallpaper in the icon shape
            DisplayMetrics dm = v.getResources().getDisplayMetrics();
            int sw = dm.widthPixels, sh = dm.heightPixels;
            float s = (float) sh / b.getHeight();
            float wide = b.getWidth() * s - sw;
            float x0 = wide > 1 ? wide * pageProgress() : (b.getWidth() * s - sw) / 2f;
            v.getLocationOnScreen(LOC);
            M.setScale(s, s);
            M.postTranslate(-x0 - LOC[0] + v.getScrollX(), -LOC[1] + v.getScrollY());
            BitmapShader sh0 = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sh0.setLocalMatrix(M);
            SHADER_PAINT.setShader(sh0);
            canvas.drawBitmap(m, null, R, SHADER_PAINT);
        }
        int veil;
        switch (style) {
            case "frosted_dark": veil = 0x80141414; break;            // smoky black glass
            case "tinted": veil = tintColor; break;                  // wallpaper-colored glass
            case "clear": veil = 0x38FFFFFF; break;                  // see-through, wallpaper stays sharp
            case "solid": veil = solidColor; break;                  // not see-through, wallpaper pastel
            default: veil = VEIL;                                    // frosted light
        }
        FILL.setColor(veil);
        canvas.drawBitmap(m, null, R, FILL);

        if (LauncherIcons.fx3d()) {                                   // 3D: glossy light on top, shade at the bottom
            GLOSS.setShader(new android.graphics.LinearGradient(0, R.top, 0, R.bottom,
                    new int[]{0x66FFFFFF, 0x00FFFFFF, 0x00000000, 0x2E000000}, new float[]{0f, 0.5f, 0.6f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawBitmap(m, null, R, GLOSS);
        }
    }

    /** 0..1 position across the home pages (for wallpapers wider than the screen). */
    private static float pageProgress() {
        try {
            Activity l = launcherRef.get();
            if (l == null) return 0.5f;
            View ws = (View) l.getClass().getMethod("getWorkspace").invoke(l);
            int pages = (Integer) ws.getClass().getMethod("getPageCount").invoke(ws);
            if (pages <= 1) return 0.5f;
            int max = (Integer) ws.getClass().getMethod("getScrollForPage", int.class).invoke(ws, pages - 1);
            if (max <= 0) return 0.5f;
            float pr = ws.getScrollX() / (float) max;
            if (ws.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) pr = 1f - pr;
            return Math.max(0f, Math.min(1f, pr));
        } catch (Throwable t) { return 0.5f; }
    }
}
