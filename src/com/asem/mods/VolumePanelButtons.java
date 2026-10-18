package com.asem.mods;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ASEM Mods - two buttons on top of the XOS side volume panel (volume keys), styled like its
 * Captions button (same white rounded background as the "ringer" box, same icon tint/size):
 *   1. Sound mode: icon = active mode (sound / vibrate / silent), tap cycles them
 *   2. 3D haptics: speaker with vibration lines; on = music / video drives the vibrator (MusicHaptics)
 * Each one can be switched off in the ASEM Mods app. Panel: TrVolumeDialogImpl.initDialog().
 */
final class VolumePanelButtons {
    private static final String TAG = "ASEM Mods (volume panel): ";
    private static final String SYSUI = "com.android.systemui";

    // Material-style glyph, 24x24: speaker in the middle, vibration bars left and right
    private static final String GLYPH_HAPTICS =
            "M7,9v6h4l5,5V4l-5,5H7z M0,15h2V9H0v6z M3,17h2V7H3v10z M22,9v6h2V9h-2z M19,17h2V7h-2v10z";

    private VolumePanelButtons() {}

    private static void log(String m, Throwable t) { XposedBridge.log(TAG + m + (t == null ? "" : ": " + t)); }

    static void hook(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("com.android.systemui.volume.TrVolumeDialogImpl", cl, "initDialog",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try { add(p.thisObject); } catch (Throwable t) { log("add", t); }
                    }
                });
        log("hooked", null);
    }

    private static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    private static int res(Context c, String type, String name) {
        return c.getResources().getIdentifier(name, type, SYSUI);
    }

    private static void add(Object dlg) {
        boolean ringerBtn = Hook.prefOn("vol_ringer_btn"), hapticBtn = Hook.prefOn("vol_haptic_btn");
        if (!ringerBtn && !hapticBtn) return;
        ViewGroup dialogView = (ViewGroup) XposedHelpers.getObjectField(dlg, "mDialogView");
        if (!(dialogView instanceof RelativeLayout)) { log("unexpected panel layout", null); return; }
        Context ctx = dialogView.getContext();
        if (ctx.getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT) return;
        View main = dialogView.findViewById(res(ctx, "id", "main_container"));
        if (main == null) { log("main_container not found", null); return; }
        View ringerBox = (View) XposedHelpers.getObjectField(dlg, "mRinger");
        ImageView capIcon = (ImageView) XposedHelpers.getObjectField(dlg, "mODICaptionsIcon");

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setId(View.generateViewId());
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(0, dp(ctx, 10), 0, 0);

        AudioManager am = ctx.getSystemService(AudioManager.class);
        final ImageView[] ringerIcon = new ImageView[1];
        final int[] shownMode = {-1};
        if (ringerBtn) {
            ImageView iv = new ImageView(ctx);
            ringerIcon[0] = iv;
            col.addView(button(ctx, ringerBox, capIcon, iv, v -> {
                try {
                    int m = ringerMode(am);
                    int next = m == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                            : m == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT
                            : AudioManager.RINGER_MODE_NORMAL;
                    Object controller = XposedHelpers.getObjectField(dlg, "mController");
                    controller.getClass().getMethod("setRingerMode", int.class, boolean.class).invoke(controller, next, false);
                    setRingerIcon(ctx, iv, next);
                    shownMode[0] = next;
                    keepOpen(dlg);
                } catch (Throwable t) { log("ringer", t); }
            }));
            int m = ringerMode(am);
            setRingerIcon(ctx, iv, m);
            shownMode[0] = m;
        }
        if (hapticBtn) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(new Glyph(GLYPH_HAPTICS));
            ColorStateList base = iconTint(ctx);
            applyHapticState(ctx, iv, base);
            col.addView(button(ctx, ringerBox, capIcon, iv, v -> {
                try {
                    MusicHaptics.setEnabled(ctx, !MusicHaptics.isEnabled(ctx));
                    applyHapticState(ctx, iv, base);
                    keepOpen(dlg);
                } catch (Throwable t) { log("haptics", t); }
            }));
        }

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        dialogView.addView(col, lp);
        RelativeLayout.LayoutParams mlp = (RelativeLayout.LayoutParams) main.getLayoutParams();
        mlp.addRule(RelativeLayout.BELOW, col.getId());
        main.setLayoutParams(mlp);

        // ringer mode can change elsewhere (keys, quick settings): keep the icon right
        if (ringerIcon[0] != null) {
            col.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    try {
                        int m = ringerMode(am);
                        if (m != shownMode[0]) { shownMode[0] = m; setRingerIcon(ctx, ringerIcon[0], m); }
                    } catch (Throwable t) { log("ringer refresh", t); }
                    return true;
                }
            });
        }
        log("buttons added (sound mode=" + ringerBtn + ", 3D haptics=" + hapticBtn + ")", null);
    }

    /** One button in the Captions style: white rounded "ringer" background, 22dp icon, same tint. */
    private static View button(Context ctx, View ringerBox, ImageView capIcon, ImageView icon, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(ctx);
        Drawable bg = null;
        try {
            if (ringerBox != null && ringerBox.getBackground() != null && ringerBox.getBackground().getConstantState() != null)
                bg = ringerBox.getBackground().getConstantState().newDrawable(ctx.getResources()).mutate();
        } catch (Throwable ignored) { }
        int w = dimen(ctx, "volume_dialog_width_custom", 48), h = dimen(ctx, "volume_dialog_odi_captions_height_custom", 48);
        if (bg == null) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFFFFFFFF);
            g.setCornerRadius(Math.min(w, h) / 2f);
            bg = g;
        }
        b.setBackground(bg);
        b.setClipToOutline(true);
        if (ringerBox != null) {
            b.setTranslationZ(ringerBox.getTranslationZ());
            b.setOutlineSpotShadowColor(ringerBox.getOutlineSpotShadowColor());
            b.setOutlineAmbientShadowColor(ringerBox.getOutlineAmbientShadowColor());
        }
        int is = capIcon != null && capIcon.getLayoutParams() != null && capIcon.getLayoutParams().width > 0
                ? capIcon.getLayoutParams().width : dp(ctx, 22);
        if (icon.getImageTintList() == null) icon.setImageTintList(iconTint(ctx));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.addView(icon, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
        b.setOnClickListener(click);
        b.setClickable(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(w, h);
        blp.bottomMargin = dp(ctx, 10);
        b.setLayoutParams(blp);
        return b;
    }

    /** Black icons on the white button background. */
    private static ColorStateList iconTint(Context ctx) {
        return ColorStateList.valueOf(0xFF000000);
    }

    private static int dimen(Context ctx, String name, int fallbackDp) {
        int id = res(ctx, "dimen", name);
        return id != 0 ? ctx.getResources().getDimensionPixelSize(id) : dp(ctx, fallbackDp);
    }

    private static int ringerMode(AudioManager am) {
        try { return (Integer) am.getClass().getMethod("getRingerModeInternal").invoke(am); }
        catch (Throwable t) { return am.getRingerMode(); }
    }

    private static void setRingerIcon(Context ctx, ImageView iv, int mode) {
        String n = mode == AudioManager.RINGER_MODE_SILENT ? "ic_volume_ringer_mute"
                : mode == AudioManager.RINGER_MODE_VIBRATE ? "ic_volume_ringer_vibrate" : "ic_volume_ringer";
        int id = res(ctx, "drawable", n);
        if (id != 0) iv.setImageResource(id);
        iv.setContentDescription(mode == AudioManager.RINGER_MODE_SILENT ? "Silent"
                : mode == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : "Sound");
    }

    private static void applyHapticState(Context ctx, ImageView iv, ColorStateList base) {
        boolean on = MusicHaptics.isEnabled(ctx);
        Integer c = on ? Hook.iconColorOrNull(ctx) : null;
        if (on) iv.setImageTintList(ColorStateList.valueOf(c != null ? c : 0xFF2E9D6B));
        else iv.setImageTintList(base);
        iv.setAlpha(on ? 1f : 0.85f);
        iv.setContentDescription(on ? "3D haptics on" : "3D haptics off");
    }

    /** keep the panel open a little longer after a tap (XOS/AOSP rescheduleTimeoutH) */
    private static void keepOpen(Object dlg) {
        try { XposedHelpers.findClass("com.android.systemui.volume.TrVolumeDialogImpl", dlg.getClass().getClassLoader());
              java.lang.reflect.Method m = dlg.getClass().getMethod("rescheduleTimeoutH"); m.invoke(dlg); }
        catch (Throwable ignored) { }
    }

    /** Glyph from 24x24 path data (android.util.PathParser), drawn in the ImageView tint. */
    static final class Glyph extends Drawable {
        private final Path path;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ColorStateList tint;

        Glyph(String data) {
            Path pp = null;
            try { pp = (Path) Class.forName("android.util.PathParser").getMethod("createPathFromPathData", String.class).invoke(null, data); }
            catch (Throwable t) { log("glyph", t); }
            path = pp;
            p.setColor(0xFF333333);
        }
        @Override public void draw(Canvas c) {
            if (path == null) return;
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
            Path out = new Path();
            path.transform(m, out);
            c.drawPath(out, p);
        }
        @Override public void setTintList(ColorStateList t) { tint = t; if (t != null) p.setColor(t.getColorForState(getState(), t.getDefaultColor())); invalidateSelf(); }
        @Override public boolean isStateful() { return tint != null && tint.isStateful(); }
        @Override protected boolean onStateChange(int[] st) { if (tint != null) { p.setColor(tint.getColorForState(st, tint.getDefaultColor())); return true; } return false; }
        @Override public void setAlpha(int a) { p.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 72; }
        @Override public int getIntrinsicHeight() { return 72; }
    }
}
