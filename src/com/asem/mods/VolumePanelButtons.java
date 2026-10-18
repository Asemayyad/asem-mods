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
    // mixer: three faders with their knobs at different heights (per-app volume)
    private static final String GLYPH_MIXER =
            "M5,3h2v18h-2z M2.5,13h7v3.5h-7z M11,3h2v18h-2z M8.5,6h7v3.5h-7z M17,3h2v18h-2z M14.5,10.5h7v3.5h-7z";

    // per-app sliders currently shown in the panel
    private static final java.util.List<View> APP_ROWS = new java.util.ArrayList<>();
    private static final java.util.List<String> APP_PKGS = new java.util.ArrayList<>();
    /** XOS keeps separate panels (portrait / landscape): everything below is kept per panel */
    private static final class Panel {
        ImageView mixerIcon; View mixerBtn;
        int appExtra, colMin, baseMain = -1, baseRoot = -1, setMain = -1, setRoot = -1;
    }
    private static final java.util.Map<Object, Panel> PANELS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static Panel panel(Object dlg) {
        Panel p = PANELS.get(dlg);
        if (p == null) { p = new Panel(); PANELS.put(dlg, p); }
        return p;
    }
    private static Object rowsDlg;                 // panel the per-app sliders are open in
    private static View buttonsCol;
    // XOS gives the panel root and the slider block FIXED widths (one slider). Extra width we add:
    // per-app sliders (appExtra) and, in landscape, the row of buttons (colMin = width it needs).
    private static final int ROW_GAP_DP = 10;

    private VolumePanelButtons() {}

    private static void log(String m, Throwable t) { XposedBridge.log(TAG + m + (t == null ? "" : ": " + t)); }

    static void hook(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("com.android.systemui.volume.TrVolumeDialogImpl", cl, "initDialog",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try { add(p.thisObject); } catch (Throwable t) { log("add", t); }
                    }
                });
        // XOS lets touches through everywhere except its panel views: add our buttons to that area
        XposedHelpers.findAndHookMethod("com.android.systemui.volume.TrVolumeDialogImpl", cl, "onComputeInternalInsets",
                "android.view.ViewTreeObserver$InternalInsetsInfo", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            View c = buttonsCol;
                            if (c == null || !c.isAttachedToWindow() || c.getVisibility() != View.VISIBLE) return;
                            int[] at = new int[2];
                            c.getLocationInWindow(at);
                            Object info = p.args[0];
                            android.graphics.Region r = (android.graphics.Region) XposedHelpers.getObjectField(info, "touchableRegion");
                            r.op(at[0], at[1], at[0] + c.getWidth(), at[1] + c.getHeight(), android.graphics.Region.Op.UNION);
                        } catch (Throwable t) { log("touch area", t); }
                    }
                });
        XposedHelpers.findAndHookMethod("com.android.systemui.volume.TrVolumeDialogImpl", cl, "updateMainHeight", boolean.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try { fixWidths(p.thisObject); } catch (Throwable t) { log("widths", t); }
                    }
                });
        // panel closes: per-app sliders go away (fresh list next time)
        XposedHelpers.findAndHookMethod("com.android.systemui.volume.TrVolumeDialogImpl", cl, "dismissH", int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try { closeAppRows(); } catch (Throwable t) { log("close", t); }
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
        boolean ringerBtn = Hook.prefOn("vol_ringer_btn"), hapticBtn = Hook.prefOn("vol_haptic_btn"), appBtn = Hook.prefOn("vol_app_btn");
        if (!ringerBtn && !hapticBtn && !appBtn) return;
        ViewGroup dialogView = (ViewGroup) XposedHelpers.getObjectField(dlg, "mDialogView");
        if (!(dialogView instanceof RelativeLayout)) { log("unexpected panel layout", null); return; }
        Context ctx = dialogView.getContext();
        boolean land = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean showRinger = ringerBtn && !land;  // landscape: only 3D haptics + per-app volume
        if (!showRinger && !hapticBtn && !appBtn) return;
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
        buttonsCol = col;

        AudioManager am = ctx.getSystemService(AudioManager.class);
        final ImageView[] ringerIcon = new ImageView[1];
        final int[] shownMode = {-1};
        if (showRinger) {
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

        if (appBtn) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(new Glyph(GLYPH_MIXER));
            View b = button(ctx, ringerBox, capIcon, iv, v -> {
                try {
                    if (APP_ROWS.isEmpty()) openAppRows(dlg); else closeAppRows();
                    keepOpen(dlg);
                } catch (Throwable t) { log("app volume", t); }
            });
            b.setContentDescription("App volume");
            Panel pn = panel(dlg);
            pn.mixerIcon = iv;
            pn.mixerBtn = b;
            col.addView(b);
            // only while 2+ apps play sound; checked again every time this panel is drawn
            b.setVisibility(AppVolume.count >= 2 ? View.VISIBLE : View.GONE);
            AppVolume.setListener(() -> b.post(() -> {
                try { onPlayingChanged(dlg); } catch (Throwable t) { log("playing", t); }
            }));
        }

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        RelativeLayout.LayoutParams mlp = (RelativeLayout.LayoutParams) main.getLayoutParams();
        Panel st = panel(dlg);
        st.baseMain = st.baseRoot = st.setMain = st.setRoot = -1;
        st.appExtra = 0;
        st.colMin = 0;
        if (land) {
            // landscape: a row at the bottom of the panel's outer frame, under the slider; the panel
            // gets that much bottom padding so nothing overlaps, and the row's right edge follows the
            // slider's real position on screen (XOS shifts its views, so no layout rules here)
            FrameLayout root = (FrameLayout) XposedHelpers.getObjectField(dlg, "mDialogRootView");
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
            root.addView(col, flp);
            col.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            st.colMin = col.getMeasuredWidth() + dp(ctx, 40);
            View ringer = dialogView.findViewById(res(ctx, "id", "ringer"));
            int basePad = dialogView.getPaddingBottom();
            int[] ra = new int[2], ma = new int[2], ga = new int[2];
            // after every layout: column right under the slider (or under XOS's captions box when shown),
            // right edges lined up, and only as much room below the panel as the visible buttons need
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    root.getLocationInWindow(ra);
                    main.getLocationInWindow(ma);
                    int bottom = ma[1] + main.getHeight();
                    if (ringer != null && ringer.getVisibility() == View.VISIBLE && ringer.getHeight() > 0) {
                        ringer.getLocationInWindow(ga);
                        bottom = Math.max(bottom, ga[1] + ringer.getHeight());
                    }
                    int top = Math.max(0, bottom - ra[1] - dp(ctx, 4));
                    int right = Math.max(0, (ra[0] + root.getWidth()) - (ma[0] + main.getWidth()));
                    if (flp.topMargin != top || flp.rightMargin != right) {
                        flp.topMargin = top; flp.rightMargin = right; col.setLayoutParams(flp);
                    }
                    col.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    int pad = basePad + col.getMeasuredHeight() + dp(ctx, 4);
                    if (dialogView.getPaddingBottom() != pad)
                        dialogView.setPadding(dialogView.getPaddingLeft(), dialogView.getPaddingTop(), dialogView.getPaddingRight(), pad);
                } catch (Throwable e) { log("landscape buttons", e); }
            });
        } else {
            // portrait: above the media slider - right edges lined up with the slider block
            lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            lp.addRule(RelativeLayout.ALIGN_RIGHT, main.getId());
            lp.rightMargin = Math.max(0, (dimen(ctx, "volume_dialog_panel_width", 64) - dimen(ctx, "volume_dialog_width_custom", 48)) / 2);
            dialogView.addView(col, lp);
            mlp.addRule(RelativeLayout.BELOW, col.getId());
        }
        main.setLayoutParams(mlp);
        dialogView.getViewTreeObserver().addOnPreDrawListener(() -> {
            try {
                Panel pp = panel(dlg);
                if (pp.mixerBtn != null) {        // this panel's mixer follows the apps playing now
                    int want = AppVolume.count >= 2 ? View.VISIBLE : View.GONE;
                    if (pp.mixerBtn.getVisibility() != want) pp.mixerBtn.setVisibility(want);
                }
                fixWidths(dlg);
            } catch (Throwable t) { log("widths", t); }
            return true;
        });

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
        log((land ? "landscape " : "") + "buttons added (sound mode=" + showRinger + ", 3D haptics=" + hapticBtn + ", app volume=" + appBtn + ")", null);
    }

    // ------------------------------------------------------------------ per-app volume sliders
    /** apps started / stopped: show the mixer button only for 2+ apps, keep the open sliders in sync */
    private static void onPlayingChanged(Object dlg) throws Exception {
        java.util.List<AppVolume.App> apps = AppVolume.playing();
        synchronized (PANELS) {
            for (Panel p : PANELS.values())
                if (p.mixerBtn != null) p.mixerBtn.setVisibility(apps.size() >= 2 ? View.VISIBLE : View.GONE);
        }
        if (APP_ROWS.isEmpty()) return;
        if (rowsDlg != null) dlg = rowsDlg;
        if (apps.size() < 2) { closeAppRows(); return; }
        java.util.List<String> now = new java.util.ArrayList<>();
        for (AppVolume.App a : apps) now.add(a.pkg);
        if (!now.equals(APP_PKGS)) openAppRows(dlg);
    }

    /** one XOS slider row per playing app, left of the media slider, with the app icon at the bottom */
    private static void openAppRows(Object dlg) throws Exception {
        closeAppRows();
        ViewGroup rows = (ViewGroup) XposedHelpers.getObjectField(dlg, "mDialogRowsView");
        Context ctx = rows.getContext();
        View template = null;
        for (int i = rows.getChildCount() - 1; i >= 0; i--)
            if (rows.getChildAt(i).getVisibility() == View.VISIBLE) { template = rows.getChildAt(i); break; }
        if (template == null) { log("no media slider to copy", null); return; }
        int sliderId = res(ctx, "id", "volume_row_slider"), contId = res(ctx, "id", "volume_slider_container");
        View tSlider = template.findViewById(sliderId), tCont = template.findViewById(contId);
        View tIcon = template.findViewById(res(ctx, "id", "volume_row_icon"));
        android.view.LayoutInflater li = ((android.app.Dialog) XposedHelpers.getObjectField(dlg, "mDialog")).getLayoutInflater();
        int layout = res(ctx, "layout", "tr_volume_dialog_custom_row");
        ClassLoader cl = tSlider.getClass().getClassLoader();
        for (AppVolume.App a : AppVolume.playing()) {
            View row = li.inflate(layout, null);
            View slider = row.findViewById(sliderId), cont = row.findViewById(contId);
            ViewGroup.LayoutParams sl = slider.getLayoutParams();
            sl.width = tSlider.getLayoutParams().width;
            sl.height = tSlider.getLayoutParams().height;
            slider.setLayoutParams(sl);
            if (cont != null && tCont != null) {
                ViewGroup.LayoutParams cp = cont.getLayoutParams();
                cp.height = tCont.getLayoutParams().height;
                cont.setLayoutParams(cp);
            }
            View settings = row.findViewById(res(ctx, "id", "settings"));
            if (settings instanceof ImageView) ((ImageView) settings).setImageDrawable(null);
            View rowIcon = row.findViewById(res(ctx, "id", "volume_row_icon"));
            if (rowIcon instanceof ImageView) {
                ((ImageView) rowIcon).setImageDrawable(null);
                rowIcon.setVisibility(tIcon != null ? tIcon.getVisibility() : View.INVISIBLE);
                rowIcon.setClickable(false);
            }
            glassShadow(slider, tSlider, cl);
            row.setContentDescription(a.label + " volume");
            setupAppSlider(dlg, slider, a, cl);
            LinearLayout.LayoutParams rlp = template.getLayoutParams() instanceof LinearLayout.LayoutParams
                    ? new LinearLayout.LayoutParams((LinearLayout.LayoutParams) template.getLayoutParams())
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMarginEnd(dp(ctx, ROW_GAP_DP));       // space between the sliders
            rows.addView(row, 0, rlp);
            APP_ROWS.add(row);
            APP_PKGS.add(a.pkg);
        }
        int rowW = template.getWidth() > 0 ? template.getWidth() : dimen(ctx, "volume_dialog_panel_width", 52);
        Panel pn = panel(dlg);
        rowsDlg = dlg;
        pn.appExtra = APP_ROWS.size() * (rowW + dp(ctx, ROW_GAP_DP));
        fixWidths(dlg);
        if (pn.mixerIcon != null) {
            Integer c = Hook.iconColorOrNull(ctx);
            pn.mixerIcon.setImageTintList(ColorStateList.valueOf(c != null ? c : 0xFF2E9D6B));
        }
        log("app sliders: " + APP_PKGS, null);
    }

    static void closeAppRows() {
        for (View r : APP_ROWS) if (r.getParent() instanceof ViewGroup) ((ViewGroup) r.getParent()).removeView(r);
        APP_ROWS.clear();
        APP_PKGS.clear();
        Object d = rowsDlg;
        rowsDlg = null;
        if (d == null) return;
        Panel pn = panel(d);
        pn.appExtra = 0;
        try { fixWidths(d); } catch (Throwable t) { log("widths", t); }
        if (pn.mixerIcon != null) pn.mixerIcon.setImageTintList(ColorStateList.valueOf(0xFF000000));
    }

    /** XOS's fixed widths + what we need: slider block grows by the app sliders, root also fits the buttons */
    private static void fixWidths(Object dlg) {
        Panel st = panel(dlg);
        View main = (View) XposedHelpers.getObjectField(dlg, "mMainContainer");
        View root = (View) XposedHelpers.getObjectField(dlg, "mDialogRootView");
        if (main != null) {
            ViewGroup.LayoutParams l = main.getLayoutParams();
            if (l != null && l.width > 0) {
                if (l.width != st.setMain) st.baseMain = l.width;    // XOS (re)set it
                int want = st.baseMain + st.appExtra;
                st.setMain = want;
                if (l.width != want) { l.width = want; main.setLayoutParams(l); }
            }
        }
        if (root != null) {
            ViewGroup.LayoutParams l = root.getLayoutParams();
            if (l != null && l.width > 0) {
                if (l.width != st.setRoot) st.baseRoot = l.width;
                int want = Math.max(st.baseRoot + st.appExtra, st.colMin);
                st.setRoot = want;
                if (l.width != want) { l.width = want; root.setLayoutParams(l); }
            }
        }
    }

    /** same glass shadow XOS gives its own sliders (TrVolumeDialogHelper.setLiquidGlassShadow) */
    private static void glassShadow(View slider, View template, ClassLoader cl) {
        try {
            Class<?> h = XposedHelpers.findClass("com.android.systemui.volume.TrVolumeDialogHelper", cl);
            Object inst = XposedHelpers.getStaticObjectField(h, "sInstance");
            Object radius = XposedHelpers.getObjectField(inst, "mCornerRadius");
            for (java.lang.reflect.Method m : h.getDeclaredMethods())
                if (m.getName().equals("setLiquidGlassShadow") && m.getParameterCount() == 3) {
                    m.setAccessible(true);
                    m.invoke(inst, radius, slider, false);
                    return;
                }
        } catch (Throwable t) { log("glass shadow", t); }
        slider.setElevation(template.getElevation());
        slider.setTranslationZ(template.getTranslationZ());
        slider.setOutlineProvider(template.getOutlineProvider());
        slider.setClipToOutline(template.getClipToOutline());
    }

    /** 0..1000 slider = 0..100 % of the media volume for this app; app icon in full color at the bottom */
    private static void setupAppSlider(Object dlg, View slider, AppVolume.App a, ClassLoader cl) throws Exception {
        XposedHelpers.setFloatField(slider, "mMin", 0f);
        XposedHelpers.setFloatField(slider, "mMax", 1000f);
        slider.getClass().getMethod("setProgress", float.class).invoke(slider, AppVolume.level(a.pkg) * 1000f);
        if (a.icon != null) {
            int size = 0;
            try { size = XposedHelpers.getIntField(slider, "mIconSize"); } catch (Throwable ignored) { }
            if (size <= 0) size = dp(slider.getContext(), 22);
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            Drawable d = a.icon.getConstantState() != null ? a.icon.getConstantState().newDrawable().mutate() : a.icon;
            d.setBounds(0, 0, size, size);
            d.draw(new Canvas(bm));
            XposedHelpers.setObjectField(slider, "mBpIconFg", bm);
        }
        Class<?> lc = XposedHelpers.findClass("com.transsion.systemui.widget.TrSeekbar$OnProgressChangedListener", cl);
        Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{lc}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "onProgressChanged":
                    if ((Boolean) args[2]) AppVolume.setLevel(a.pkg, ((Integer) args[1]) / 1000f, true);
                    keepOpen(dlg);
                    return null;
                case "onStartTrackingTouch":
                    keepOpen(dlg);
                    return null;
                case "onStopTrackingTouch":
                    try {
                        int p = (Integer) slider.getClass().getMethod("getProgress").invoke(slider);
                        AppVolume.setLevel(a.pkg, p / 1000f, true);
                    } catch (Throwable ignored) { }
                    keepOpen(dlg);
                    return null;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "ASEM app volume " + a.pkg;
                default: return null;
            }
        });
        slider.getClass().getMethod("setOnProgressChangedListener", lc).invoke(slider, listener);
        slider.invalidate();
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
