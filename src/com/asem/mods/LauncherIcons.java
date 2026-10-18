package com.asem.mods;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ASEM Mods - XOS Launcher (com.transsion.launcher3 16.3.0.380), colored icon mode
 * (Settings > Wallpaper & personalization > Icon > Color).
 *
 * XOS builds colored icons in Z3.b.applyIconColorForAdaptiveIconDrawable():
 *   AdaptiveIconDrawable(ColorDrawable(white plate), monochrome tinted with the chosen color)
 * and leaves apps WITHOUT a monochrome layer (Sanad, AtoZ, ...) unchanged; old bitmap icons go
 * through Z3.b.t(). This class:
 *   - plate "glass": the white plate becomes a see-through milky glass (white, ~25 %)
 *   - silhouettes: apps without a themed icon get an auto-made two-tone silhouette
 *     (saturated parts in the 2nd color, the rest in the 1st color, like the XOS scheme)
 * Settings come from the ASEM Mods app (XSharedPreferences "asem_prefs").
 * Obfuscated names (Z3.b, u8.e.a, u8.a.a) are for this launcher version only; if they are
 * missing, nothing is hooked and only a log line is written.
 */
final class LauncherIcons {
    private static final String TAG = "ASEM Mods (launcher): ";
    static final int GLASS_PLATE = 0x40FFFFFF;          // white, 25 % -> wallpaper shows through
    private static final int LAYER = 216;                // working size of one adaptive layer (px)
    private static XSharedPreferences prefs;

    private LauncherIcons() {}

    private static void log(String m, Throwable t) { XposedBridge.log(TAG + m + (t == null ? "" : ": " + t)); }

    /** Plate styles offered by the ASEM Mods app. Everything except "stock" is drawn live
     *  (FrostedIcons) behind icons baked with an empty plate. */
    static final java.util.List<String> STYLES = java.util.Arrays.asList(
            "stock", "frosted", "frosted_dark", "auto", "tinted", "clear", "solid");
    private static long lastCheck;
    private static String cachedStyle = "frosted";
    private static boolean cachedFx;

    private static void refresh() {
        long now = android.os.SystemClock.uptimeMillis();
        if (prefs != null && now - lastCheck < 800) return;     // throttled: read at most ~1x/s
        lastCheck = now;
        if (prefs == null) prefs = new XSharedPreferences("com.asem.mods", "asem_prefs");
        if (prefs.hasFileChanged()) prefs.reload();
        String m = prefs.getString("plate", "frosted");
        if ("glass".equals(m)) m = "frosted";
        cachedStyle = STYLES.contains(m) ? m : "frosted";
        cachedFx = prefs.getBoolean("fx3d", false);
    }

    private static String plateMode() {
        try { refresh(); } catch (Throwable ignored) { }
        return cachedStyle;
    }

    static boolean fx3d() {
        try { refresh(); } catch (Throwable ignored) { }
        return cachedFx;
    }

    static boolean live(String mode) { return !"stock".equals(mode); }

    static boolean night(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** dark plate styles -> the dark (unsaturated) parts of the symbol become light */
    static boolean darkStyle(Context ctx, String mode) {
        return "frosted_dark".equals(mode) || ("auto".equals(mode) && night(ctx));
    }

    static Bitmap lightenGlyph(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        float[] hsv = new float[3];
        for (int i = 0; i < px.length; i++) {
            int a = px[i] >>> 24;
            if (a == 0) continue;
            Color.colorToHSV(px[i] | 0xff000000, hsv);
            if (hsv[1] < 0.35f && hsv[2] < 0.55f) px[i] = (a << 24) | 0x00F2F2F2;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static boolean silhouettes() {
        try {
            if (prefs == null) prefs = new XSharedPreferences("com.asem.mods", "asem_prefs");
            return prefs.getBoolean("silhouette", true);
        } catch (Throwable t) { return true; }
    }

    static void hook(ClassLoader cl) {
        Class<?> api = XposedHelpers.findClass("Z3.b", cl);
        XposedHelpers.findAndHookMethod(api, "applyIconColorForAdaptiveIconDrawable",
                Context.class, AdaptiveIconDrawable.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object r = p.getResult();
                            publishColor((Context) p.args[0], schemeColors(cl));
                            AdaptiveIconDrawable in = (AdaptiveIconDrawable) p.args[1];
                            Drawable out = restyle((Context) p.args[0], in, r instanceof AdaptiveIconDrawable ? (AdaptiveIconDrawable) r : null, cl);
                            if (out != null) { OURS.put(out, Boolean.TRUE); p.setResult(out); }
                        } catch (Throwable t) { log("adaptive icon", t); }
                    }
                });
        // old (non-adaptive) bitmap icons
        XposedHelpers.findAndHookMethod(api, "t", android.content.ComponentName.class, Context.class,
                Drawable.class, android.os.UserHandle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!(p.args[2] instanceof BitmapDrawable) || !silhouettes()) return;
                            int[] c = schemeColors(cl);
                            if (c == null) return;
                            Bitmap src = ((BitmapDrawable) p.args[2]).getBitmap();
                            if (src == null) return;
                            Bitmap fg = silhouetteFromLegacy(src, c);
                            Drawable o2 = new AdaptiveIconDrawable(new ColorDrawable(plateColor(c)),
                                    new BitmapDrawable(((Context) p.args[1]).getResources(), fg));
                            OURS.put(o2, Boolean.TRUE);
                            p.setResult(o2);
                        } catch (Throwable t) { log("bitmap icon", t); }
                    }
                });
        // Frosted: XOS draws a glossy "liquid glass" rim on every icon afterwards
        // (Z3.b.applyIconLiquidDrawableEffect) -> skip it, so the frosted plate is one clean piece
        XposedHelpers.findAndHookMethod(api, "applyIconLiquidDrawableEffect", Context.class,
                XposedHelpers.findClass("com.android.launcher3.icons.liquid.TrOSIconLiquidDrawable", cl),
                Canvas.class, Drawable.class, int.class, android.graphics.Path.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!live(plateMode()) || !themedSeen) return;
                            Drawable d = (Drawable) p.args[3];
                            int n = (Integer) p.args[4];
                            if (d == null || n <= 0) return;
                            Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
                            android.graphics.Rect old = d.copyBounds();
                            d.setBounds(0, 0, n, n);
                            d.draw(new Canvas(b));
                            d.setBounds(old);
                            p.setResult(b);
                        } catch (Throwable t) { log("liquid rim", t); }
                    }
                });
        // XOS themed icons come from Z3.b.u() (returns early in getIcon); remember them
        XposedHelpers.findAndHookMethod(api, "u", Context.class, android.content.pm.ApplicationInfo.class,
                int.class, boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object r = p.getResult();
                        if (r != null) { THEMED.put(r, Boolean.TRUE); themedSeen = true; }
                    }
                });
        // every launcher icon passes through Z3.b.getIcon(Context, ActivityInfo, UserHandle)
        XposedHelpers.findAndHookMethod(api, "getIcon", Context.class, android.content.pm.ActivityInfo.class,
                android.os.UserHandle.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object r = p.getResult();
                            if (!(r instanceof Drawable) || OURS.containsKey(r)) return;
                            Context ctx = (Context) p.args[0];
                            String pkg = ((android.content.pm.ActivityInfo) p.args[1]).packageName;
                            int[] c = schemeColors(cl);
                            publishColor(ctx, c);
                            boolean themed = THEMED.containsKey(r);
                            int size = iconSize(p.thisObject);
                            Drawable out = finalIcon(ctx, (Drawable) r, themed, c, size, cl);
                            if (debugLeft > 0) {
                                debugLeft--;
                                log("icon " + pkg + ": " + r.getClass().getSimpleName() + (themed ? " themed" : "")
                                        + " size=" + size + " -> " + (out == null ? "unchanged" : "restyled"), null);
                            }
                            if (out != null) p.setResult(out);
                        } catch (Throwable t) { log("getIcon", t); }
                    }
                });
        log("hooked (plate=" + plateMode() + ", silhouettes=" + silhouettes() + ")", null);
        // tell the ASEM Mods app that the launcher part is active (shown in its status line)
        XposedHelpers.findAndHookMethod("android.app.Application", cl, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    android.provider.Settings.Global.putString(((Context) p.thisObject).getContentResolver(),
                            KEY_HOOKED, String.valueOf(System.currentTimeMillis()));
                } catch (Throwable t) { log("status", t); }
            }
        });
    }

    private static final java.util.Map<Object, Boolean> THEMED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static volatile boolean themedSeen;
    private static final java.util.Map<Object, Boolean> OURS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static int debugLeft = 25;

    static String plateModePublic() { return plateMode(); }

    private static int iconSize(Object api) {
        try { return (Integer) api.getClass().getMethod("getIconSize").invoke(api); }
        catch (Throwable t) { return 192; }
    }

    /** Plate alpha baked into the icon: stock = keep, glass = 25 % white, frosted = clear (drawn live). */
    private static int bakedPlate() {
        String m = plateMode();
        return live(m) ? 0x00FFFFFF : GLASS_PLATE;
    }

    private static Drawable finalIcon(Context ctx, Drawable d, boolean themed, int[] c, int size, ClassLoader cl) {
        if (!themedSeen) return null;                       // XOS colored icons not in use
        String mode = plateMode();
        if (size <= 0) size = 192;
        Bitmap src = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Rect old = d.copyBounds();
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(src));
        d.setBounds(old);
        Bitmap out;
        if (themed) {
            if ("stock".equals(mode)) return null;
            out = replacePlate(src, bakedPlate());
            if (live(mode)) out = clearEdge(out, cl);
        } else {
            if (!silhouettes() || c == null) return null;
            out = silhouetteIcon(src, c, "stock".equals(mode) ? Color.WHITE : bakedPlate(), cl);
            if (live(mode)) out = clearEdge(out, cl);
        }
        if (darkStyle(ctx, mode)) out = lightenGlyph(out);
        return new BitmapDrawable(ctx.getResources(), out);
    }

    /** Frosted: remove a thin band along the icon shape's edge (old plate rim / XOS liquid edge
     *  highlight), so the live frosted plate under it shows as one clean piece. */
    static Bitmap clearEdge(Bitmap src, ClassLoader cl) {
        int n = src.getWidth();
        Bitmap shape = null;
        try { shape = (Bitmap) XposedHelpers.findClass("u8.e", cl).getMethod("b").invoke(null); } catch (Throwable ignored) { }
        Bitmap inner = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8);
        Canvas ci = new Canvas(inner);
        android.graphics.Paint pp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        int inset = Math.max(2, Math.round(n * 0.06f));
        android.graphics.Rect r = new android.graphics.Rect(inset, inset, n - inset, n - inset);
        if (shape != null) ci.drawBitmap(shape.extractAlpha(), null, r, pp);
        else ci.drawRoundRect(inset, inset, n - inset, n - inset, n * 0.22f, n * 0.22f, pp);
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas co = new Canvas(out);
        android.graphics.Paint keep = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        keep.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        co.drawBitmap(inner, 0, 0, keep);          // keep only what is inside the inset shape
        return out;
    }

    /** White plate pixels (bright, unsaturated) -> plate color; symbol pixels and edges stay. */
    static Bitmap replacePlate(Bitmap src, int plate) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int pa = plate >>> 24, prgb = plate & 0x00ffffff;
        for (int i = 0; i < px.length; i++) {
            int p = px[i], a = p >>> 24;
            if (a == 0) continue;
            int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            int mn = Math.min(r, Math.min(g, b)), mx = Math.max(r, Math.max(g, b));
            if (mn < 200 || mx - mn > 28) continue;
            // how "plate-white" this pixel is (soft, so anti-aliased glyph edges blend)
            float k = Math.min(1f, (mn - 200) / 40f);
            int na = Math.round(a * (1 - k) + (a * pa / 255f) * k);
            int nr = Math.round(r * (1 - k) + ((prgb >> 16) & 0xff) * k);
            int ng = Math.round(g * (1 - k) + ((prgb >> 8) & 0xff) * k);
            int nb = Math.round(b * (1 - k) + (prgb & 0xff) * k);
            px[i] = (na << 24) | (nr << 16) | (ng << 8) | nb;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Non-themed app icon -> two-tone silhouette of its logo on the plate, in the XOS icon shape. */
    static Bitmap silhouetteIcon(Bitmap src, int[] c, int plate, ClassLoader cl) {
        int n = src.getWidth();
        int[] px = new int[n * n];
        src.getPixels(px, 0, n, 0, 0, n, n);
        int in = n / 6;
        int bg = cornerColor(px, n, in, n - in);
        int[] ink = new int[n * n];
        int busy = 0, area = 0;
        for (int i = 0; i < px.length; i++) {
            ink[i] = ink(px[i], diffAlpha(px[i], bg), c);
            int x = i % n, y = i / n;
            if (x > in && x < n - in && y > in && y < n - in) { area++; if ((ink[i] >>> 24) > 128) busy++; }
        }
        // more than half of the visible area is "ink" -> a picture, not a logo: keep it as is
        Bitmap inkBmp = busy > area * 0.55f ? src : Bitmap.createBitmap(ink, n, n, Bitmap.Config.ARGB_8888);
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        android.graphics.Paint pp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        // plate in the XOS icon shape
        Bitmap shape = null;
        try { shape = (Bitmap) XposedHelpers.findClass("u8.e", cl).getMethod("b").invoke(null); } catch (Throwable ignored) { }
        if (shape != null) {
            Bitmap a = shape.extractAlpha();
            pp.setColor(plate);
            cv.drawBitmap(a, null, new android.graphics.Rect(0, 0, n, n), pp);
        } else {
            pp.setColor(plate);
            cv.drawRoundRect(0, 0, n, n, n * 0.24f, n * 0.24f, pp);
        }
        // logo, a bit smaller and centered (like the XOS themed glyphs)
        float s = 0.72f;
        int o = Math.round(n * (1 - s) / 2f);
        pp.setColor(0xff000000);
        cv.drawBitmap(inkBmp, null, new android.graphics.Rect(o, o, n - o, n - o), pp);
        return out;
    }

    static final String KEY_COLOR = "asem_icon_color";       // read by SystemUI (QS tiles)
    static final String KEY_HOOKED = "asem_launcher_hooked";  // read by the ASEM Mods app
    private static int lastPublished;

    /** Publish the colorful color of the icon scheme (red of red+black, green of green+gray). */
    private static void publishColor(Context ctx, int[] c) {
        if (ctx == null || c == null) return;
        int pick = saturation(c[1]) >= saturation(c[0]) ? c[1] : c[0];
        if (pick == lastPublished) return;
        lastPublished = pick;
        try {
            android.provider.Settings.Global.putString(ctx.getContentResolver(), KEY_COLOR,
                    String.format("#%08X", pick | 0xff000000));
            log("published icon color " + String.format("#%06X", pick & 0xffffff), null);
        } catch (Throwable t) { log("publish color", t); }
    }

    private static float saturation(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[1] * hsv[2];
    }

    /** XOS icon color scheme: u8.e.a (static u8.a) -> int[] a = {color 1, color 2, (plate)}. */
    private static int[] schemeColors(ClassLoader cl) {
        try {
            Object bean = XposedHelpers.findClass("u8.e", cl).getField("a").get(null);
            if (bean == null) return null;
            int[] a = (int[]) bean.getClass().getField("a").get(bean);
            if (a == null || a.length < 2 || (a[0] == 0 && a[1] == 0)) return null;
            return a;
        } catch (Throwable t) { log("scheme colors", t); return null; }
    }

    private static int plateColor(int[] c) {
        if ("stock".equals(plateMode())) return c.length > 2 && c[2] != 0 ? c[2] : Color.WHITE;
        return GLASS_PLATE;
    }

    private static Drawable restyle(Context ctx, AdaptiveIconDrawable in, AdaptiveIconDrawable xos, ClassLoader cl) {
        boolean themedByXos = xos != null && xos != in && in.getMonochrome() != null;
        int[] c = schemeColors(cl);
        if (themedByXos) {
            if ("stock".equals(plateMode())) return null;               // keep XOS as is
            return new AdaptiveIconDrawable(new ColorDrawable(GLASS_PLATE), xos.getForeground());
        }
        if (!silhouettes() || c == null) return null;
        Bitmap fg = silhouetteFromAdaptive(in, c);
        if (fg == null) return null;
        return new AdaptiveIconDrawable(new ColorDrawable(plateColor(c)), new BitmapDrawable(ctx.getResources(), fg));
    }

    // ---------------------------------------------------------------- silhouettes
    /** From an adaptive icon: the foreground layer's own shape when it is a logo on a plain
     *  background; otherwise the pixels that differ from the background color. */
    static Bitmap silhouetteFromAdaptive(AdaptiveIconDrawable in, int[] c) {
        Drawable f = in.getForeground(), b = in.getBackground();
        if (f == null) return null;
        Bitmap fg = Bitmap.createBitmap(LAYER, LAYER, Bitmap.Config.ARGB_8888);
        f.setBounds(0, 0, LAYER, LAYER);
        f.draw(new Canvas(fg));
        int[] px = new int[LAYER * LAYER];
        fg.getPixels(px, 0, LAYER, 0, 0, LAYER, LAYER);
        // coverage of the visible (safe) zone: 72/108 of the layer
        int s0 = LAYER * 18 / 108, s1 = LAYER * 90 / 108, cov = 0, tot = 0;
        for (int y = s0; y < s1; y++) for (int x = s0; x < s1; x++, tot++) if ((px[y * LAYER + x] >>> 24) > 128) cov++;
        int[] out = new int[LAYER * LAYER];
        if (cov < tot * 0.70f) {
            for (int i = 0; i < px.length; i++) out[i] = ink(px[i], px[i] >>> 24, c);
        } else {
            // full-bleed artwork: compose and take what differs from the icon's background color
            Bitmap all = Bitmap.createBitmap(LAYER, LAYER, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(all);
            if (b != null) { b.setBounds(0, 0, LAYER, LAYER); b.draw(cv); }
            cv.drawBitmap(fg, 0, 0, null);
            all.getPixels(px, 0, LAYER, 0, 0, LAYER, LAYER);
            int bg = cornerColor(px, LAYER, s0, s1);
            for (int i = 0; i < px.length; i++) out[i] = ink(px[i], diffAlpha(px[i], bg), c);
        }
        fadeOutsideSafeZone(out, LAYER, s0, s1);
        return Bitmap.createBitmap(out, LAYER, LAYER, Bitmap.Config.ARGB_8888);
    }

    /** Old bitmap icons: differ-from-background ink, placed in the adaptive safe zone. */
    static Bitmap silhouetteFromLegacy(Bitmap src, int[] c) {
        int n = LAYER * 66 / 108;                          // like AOSP's legacy icon scale
        Bitmap sc = Bitmap.createScaledBitmap(src, n, n, true);
        int[] px = new int[n * n];
        sc.getPixels(px, 0, n, 0, 0, n, n);
        int bg = cornerColor(px, n, n / 8, n - n / 8);
        int[] ink = new int[n * n];
        for (int i = 0; i < px.length; i++) {
            int a = px[i] >>> 24;
            ink[i] = ink(px[i], Math.min(a, (bg >>> 24) < 128 ? a : diffAlpha(px[i], bg)), c);
        }
        Bitmap out = Bitmap.createBitmap(LAYER, LAYER, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(Bitmap.createBitmap(ink, n, n, Bitmap.Config.ARGB_8888), (LAYER - n) / 2f, (LAYER - n) / 2f, null);
        return out;
    }

    private static int ink(int px, int alpha, int[] c) {
        if (alpha < 24) return 0;
        float[] hsv = new float[3];
        Color.colorToHSV(px | 0xff000000, hsv);
        int col = (hsv[1] > 0.45f && hsv[2] > 0.35f) ? c[1] : c[0];
        return (Math.min(255, alpha) << 24) | (col & 0x00ffffff);
    }

    private static int diffAlpha(int px, int bg) {
        int dr = Math.abs(Color.red(px) - Color.red(bg)), dg = Math.abs(Color.green(px) - Color.green(bg)),
                db = Math.abs(Color.blue(px) - Color.blue(bg));
        int d = Math.max(dr, Math.max(dg, db));
        int a = d < 40 ? 0 : Math.min(255, (d - 40) * 3);
        return a * (px >>> 24) / 255;
    }

    private static int cornerColor(int[] px, int w, int s0, int s1) {
        int[] idx = {s0 * w + s0, s0 * w + s1 - 1, (s1 - 1) * w + s0, (s1 - 1) * w + s1 - 1};
        int r = 0, g = 0, b = 0, a = 0;
        for (int i : idx) { int p = px[i]; r += Color.red(p); g += Color.green(p); b += Color.blue(p); a += p >>> 24; }
        return Color.argb(a / 4, r / 4, g / 4, b / 4);
    }

    private static void fadeOutsideSafeZone(int[] out, int w, int s0, int s1) {
        int m = (s1 - s0) / 12;
        for (int y = 0; y < w; y++) for (int x = 0; x < w; x++) {
            int d = Math.min(Math.min(x - s0, s1 - 1 - x), Math.min(y - s0, s1 - 1 - y));
            if (d >= m) continue;
            int i = y * w + x;
            if (d < 0) { out[i] = 0; continue; }
            int a = (out[i] >>> 24) * d / m;
            out[i] = (a << 24) | (out[i] & 0x00ffffff);
        }
    }
}
