package com.asem.mods;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * ASEM Mods settings - by Asem (t.me/Asemfuad). Opened from its app icon or from LSPosed.
 * Look: ASEM Recovery style (dark #0E1116 / light #F3F5F8, teal accent, rounded cards),
 * follows the phone's dark / light mode. Header + splash: the ASEM letters from the logo,
 * on black (dark mode) or on the logo's green (light mode).
 */
public class SettingsActivity extends Activity {
    private static final String TG_USER = "Asemfuad";
    private static final String LSPOSED = "org.lsposed.manager";

    private boolean ar, night, lsposed;
    private SharedPreferences prefs;
    // palette (ASEM Recovery)
    private int cBg, cSurface, cSurface2, cLine, cText, cMuted, cAccent, cAccentInk, cTrack;
    private static final int CREAM = 0xFFF5FAE8;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private String t(String en, String arabic) { return ar ? arabic : en; }
    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private int res(String name) { return getResources().getIdentifier(name, "drawable", getPackageName()); }

    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        try {
            // LSPosed makes this readable for the hooked apps (XSharedPreferences); throws when the module is off
            SharedPreferences p = getSharedPreferences("asem_prefs", Context.MODE_WORLD_READABLE);
            lsposed = true;
            return p;
        } catch (Throwable t) {
            lsposed = false;
            return getSharedPreferences("asem_prefs", Context.MODE_PRIVATE);
        }
    }

    private void palette() {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            cBg = 0xFF0E1116; cSurface = 0xFF171B22; cSurface2 = 0xFF1F242D; cLine = 0xFF262C36;
            cText = 0xFFE8ECF2; cMuted = 0xFF8A94A6; cAccent = 0xFF00C2A8; cAccentInk = 0xFF04211D; cTrack = 0xFF2A313C;
        } else {
            cBg = 0xFFF3F5F8; cSurface = 0xFFFFFFFF; cSurface2 = 0xFFEAEEF3; cLine = 0xFFDDE2E9;
            cText = 0xFF121722; cMuted = 0xFF5F6B7D; cAccent = 0xFF00A892; cAccentInk = 0xFFFFFFFF; cTrack = 0xFFD3D9E1;
        }
    }

    // ------------------------------------------------------------------ small view helpers
    private GradientDrawable round(int color, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean medium) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (medium) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return tv;
    }

    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    private ImageView glyph(int r, int color, float sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(r);
        iv.setImageTintList(ColorStateList.valueOf(color));
        iv.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    private void section(LinearLayout root, int icon, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(glyph(icon, cAccent, 15));
        TextView tv = text(ar ? title : title.toUpperCase(Locale.ROOT), 11.5f, cAccent, false);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(ar ? 0f : 0.12f);
        tv.setPaddingRelative(dp(8), 0, 0, 0);
        row.addView(tv);
        LinearLayout.LayoutParams l = lp(MATCH, WRAP);
        l.setMargins(dp(26), dp(22), dp(26), dp(8));
        root.addView(row, l);
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(cSurface, 20, cLine, 1));
        c.setClipToOutline(true);
        LinearLayout.LayoutParams l = lp(MATCH, WRAP);
        l.setMargins(dp(14), 0, dp(14), 0);
        root.addView(c, l);
        return c;
    }

    private void divider(LinearLayout card) {
        if (card.getChildCount() == 0) return;
        View v = new View(this);
        v.setBackgroundColor(cLine);
        card.addView(v, lp(MATCH, Math.max(1, dp(0.8f))));
    }

    /** icon box + title + hint, and a trailing view (switch) */
    private LinearLayout item(LinearLayout card, int icon, String title, String hint, View trailing) {
        divider(card);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        FrameLayout box = new FrameLayout(this);
        box.setBackground(round(cSurface2, 11, 0, 0));
        box.addView(glyph(icon, cAccent, 19), new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        row.addView(box, lp(dp(34), dp(34)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPaddingRelative(dp(12), 0, dp(10), 0);
        tx.addView(text(title, 14.5f, cText, true));
        if (hint != null) {
            TextView h = text(hint, 12, cMuted, false);
            h.setPadding(0, dp(2), 0, 0);
            tx.addView(h);
        }
        row.addView(tx, new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (trailing != null) row.addView(trailing);
        card.addView(row, lp(MATCH, WRAP));
        return row;
    }

    /** ASEM-style switch: white knob, accent track when on */
    private Switch toggle(boolean on) {
        Switch s = new Switch(this);
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        knob.setColor(0xFFFFFFFF);
        knob.setSize(dp(20), dp(20));
        s.setThumbDrawable(new InsetDrawable(knob, dp(3)));
        StateListDrawable track = new StateListDrawable();
        GradientDrawable tOn = round(cAccent, 13, 0, 0); tOn.setSize(dp(44), dp(26));
        GradientDrawable tOff = round(cTrack, 13, 0, 0); tOff.setSize(dp(44), dp(26));
        track.addState(new int[]{android.R.attr.state_checked}, tOn);
        track.addState(new int[]{}, tOff);
        s.setTrackDrawable(track);
        s.setThumbTintList(null);
        s.setTrackTintList(null);
        s.setShowText(false);
        s.setSwitchMinWidth(dp(44));
        s.setChecked(on);
        return s;
    }

    private Switch switchItem(LinearLayout card, int icon, String title, String hint, String key, boolean def, Runnable after) {
        Switch s = toggle(prefs.getBoolean(key, def));
        View row = item(card, icon, title, hint, s);
        row.setOnClickListener(v -> s.toggle());
        s.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(key, on).commit();
            if (after != null) after.run();
        });
        return s;
    }

    /** indented area under an item (chips / buttons) */
    private LinearLayout sub(LinearLayout card) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPaddingRelative(dp(62), dp(2), dp(16), dp(14));
        card.addView(s, lp(MATCH, WRAP));
        return s;
    }

    private TextView valueLabel(LinearLayout sub, String label) {
        LinearLayout l = new LinearLayout(this);
        l.addView(text(label, 12, cMuted, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView b = text("", 12, cAccent, false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        l.addView(b);
        LinearLayout.LayoutParams p = lp(MATCH, WRAP);
        p.bottomMargin = dp(8);
        sub.addView(l, p);
        return b;
    }

    interface Pick { void on(int i); }

    /** segmented chips (x2 .. MAX, Low / Mid / Strong) */
    private LinearLayout seg(String[] opts, int sel, Pick pick) {
        LinearLayout s = new LinearLayout(this);
        s.setBackground(round(cSurface2, 12, 0, 0));
        s.setPadding(dp(3), dp(3), dp(3), dp(3));
        TextView[] chips = new TextView[opts.length];
        int[] cur = {sel};
        Runnable paint = () -> {
            for (int i = 0; i < chips.length; i++) {
                boolean on = i == cur[0];
                chips[i].setBackground(on ? round(cAccent, 9, 0, 0) : null);
                chips[i].setTextColor(on ? cAccentInk : cMuted);
            }
        };
        for (int i = 0; i < opts.length; i++) {
            TextView c = text(opts[i], 12.5f, cMuted, true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(7), 0, dp(7));
            final int idx = i;
            c.setOnClickListener(v -> {
                if (!v.isEnabled()) return;
                cur[0] = idx; paint.run(); pick.on(idx);
            });
            chips[i] = c;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1f);
            p.setMarginStart(i == 0 ? 0 : dp(2));
            s.addView(c, p);
        }
        paint.run();
        return s;
    }

    private static void setEnabledDeep(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.45f);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) setEnabledDeepNoAlpha(((ViewGroup) v).getChildAt(i), on);
    }

    private static void setEnabledDeepNoAlpha(View v, boolean on) {
        v.setEnabled(on);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) setEnabledDeepNoAlpha(((ViewGroup) v).getChildAt(i), on);
    }

    private void poke() {   // tell SystemUI to re-color now (needs root, like Apply)
        new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "settings put global asem_qs_refresh " + System.currentTimeMillis()}).waitFor();
            } catch (Throwable ignored) { }
        }).start();
    }

    // ------------------------------------------------------------------ screen
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ar = "ar".equals(Locale.getDefault().getLanguage());
        prefs = openPrefs();
        palette();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        LinearLayout.LayoutParams hl = lp(MATCH, WRAP);
        hl.setMargins(dp(14), dp(6), dp(14), dp(4));
        root.addView(hero(), hl);
        buildLock(root);
        buildVolume(root);
        buildQs(root);
        buildIcons(root);

        TextView foot = text(t("Always on: power menu Recovery + Restart SystemUI",
                "مفعّل دائماً: زر الريكفري وإعادة تشغيل الواجهة في قائمة الطاقة"), 11.5f, cMuted, false);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fl = lp(MATCH, WRAP);
        fl.setMargins(dp(20), dp(16), dp(20), dp(8));
        root.addView(foot, fl);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(cBg);
        sv.setClipToPadding(false);
        sv.addView(root);

        FrameLayout frame = new FrameLayout(this);
        frame.addView(sv);
        View splash = b == null ? splash() : null;
        if (splash != null) frame.addView(splash);
        // Android 15+ draws apps edge-to-edge: keep the content clear of the status / navigation bars
        frame.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets bars = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(bars.left, bars.top + dp(6), bars.right, bars.bottom + dp(20));
            if (splash != null) splash.setPadding(0, bars.top, 0, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(frame);
        // light mode: the green splash wants light status icons, the light page dark ones
        barIcons(splash == null && !night);
    }

    private void barIcons(boolean dark) {
        try {
            WindowInsetsController c = getWindow().getInsetsController();
            int m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance(dark ? m : 0, m);
        } catch (Throwable ignored) { }
    }

    /** black (dark) or logo-green (light) card with the ASEM letters, MODS, by-line and status chips */
    private View hero() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(20), dp(22), dp(20), dp(18));
        if (night) {
            GradientDrawable base = round(0xFF050607, 24, 0xFF1B2027, 1);
            GradientDrawable glow = new GradientDrawable();
            glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            glow.setColors(new int[]{0x2E00C2A8, 0x0000C2A8});
            glow.setGradientCenter(0.92f, 0.05f);
            glow.setGradientRadius(dp(160));
            h.setBackground(new LayerDrawable(new Drawable[]{base, glow}));
        } else {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF4DC96F, 0xFF1FA774, 0xFF017F72});
            g.setCornerRadius(dp(24));
            h.setBackground(g);
        }
        h.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(24)); }
        });
        h.setClipToOutline(true);

        ImageView wm = new ImageView(this);
        wm.setImageResource(res("asem_wordmark"));
        wm.setAdjustViewBounds(true);
        wm.setScaleType(ImageView.ScaleType.FIT_START);
        h.addView(wm, lp(WRAP, dp(52)));

        TextView mods = text("MODS", 12, CREAM, true);
        mods.setLetterSpacing(0.75f);
        mods.setAlpha(0.9f);
        mods.setPaddingRelative(dp(4), dp(8), 0, 0);
        h.addView(mods);

        // by Asem · t.me/Asemfuad -> opens @Asemfuad in Telegram
        TextView by = text((ar ? "من تطوير عاصم" : "by Asem") + " · t.me/" + TG_USER, 12.5f, 0xC8F5FAE8, false);
        by.setPaintFlags(by.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        by.setPadding(0, dp(10), dp(10), dp(4));
        by.setOnClickListener(v -> openTelegram());
        h.addView(by, lp(WRAP, WRAP));

        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(10), 0, 0);
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) { }
        chips.addView(chip("v" + ver, 0));
        chips.addView(chip("LSPosed", lsposed ? 1 : -1));
        chips.addView(chip("System UI", hookedThisBoot("asem_sysui_hooked") ? 1 : -1));
        chips.addView(chip(t("Launcher", "الشاشة الرئيسية"), hookedThisBoot("asem_launcher_hooked") ? 1 : -1));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        h.addView(hs);
        return h;
    }

    /** the hooked app writes a timestamp when ASEM Mods starts in it; newer than this boot = active */
    private boolean hookedThisBoot(String key) {
        try {
            String v = android.provider.Settings.Global.getString(getContentResolver(), key);
            long boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
            return v != null && Long.parseLong(v) > boot;
        } catch (Throwable t) { return false; }
    }

    /** state: 0 plain, 1 active (green dot), -1 not active (red, ✕, opens LSPosed) */
    private TextView chip(String s, int state) {
        TextView c = text(state < 0 ? "✕  " + s : s, 11.5f, 0, true);
        if (state < 0) {
            c.setTextColor(night ? 0xFFFF8A8E : 0xFFFFFFFF);
            c.setBackground(night ? round(0x33FF5A5F, 99, 0x80FF5A5F, 1) : round(0xFFD93A40, 99, 0x55FFFFFF, 1));
            c.setOnClickListener(v -> openLsposed());
        } else {
            c.setTextColor(night ? 0xFFC9D1DC : 0xFFFFFFFF);
            c.setBackground(night ? round(0xFF141920, 99, 0xFF232A33, 1) : round(0x2EFFFFFF, 99, 0x47FFFFFF, 1));
        }
        c.setPadding(dp(10), dp(5), dp(10), dp(5));
        c.setGravity(Gravity.CENTER_VERTICAL);
        if (state > 0) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(night ? 0xFF3DDC84 : 0xFFFFFFFF);
            dot.setSize(dp(7), dp(7));
            c.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            c.setCompoundDrawablePadding(dp(6));
        }
        LinearLayout.LayoutParams l = lp(WRAP, WRAP);
        l.setMarginEnd(dp(8));
        c.setLayoutParams(l);
        return c;
    }

    private void openTelegram() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=" + TG_USER)));
        } catch (Throwable e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/" + TG_USER))); }
            catch (Throwable ignored) { }
        }
    }

    /** LSPosed manager, opened on the ASEM Mods module page (same link as LSPosed's own notification) */
    private void openLsposed() {
        int user = android.os.Process.myUserHandle().hashCode();
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(LSPOSED, LSPOSED + ".ui.activity.MainActivity"))
                    .setData(Uri.parse("module://" + getPackageName() + ":" + user)));
            return;
        } catch (Throwable ignored) { }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(LSPOSED);
            if (i != null) { startActivity(i); return; }
        } catch (Throwable ignored) { }
        Toast.makeText(this, t("LSPosed > Modules > ASEM Mods: enable it, tick System UI + XOS Launcher, reboot",
                "LSPosed > Modules > ASEM Mods: فعّلها واختر System UI و XOS Launcher ثم أعد التشغيل"), Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ sections
    private void buildLock(LinearLayout root) {
        section(root, res("ic_s_lock"), t("Lock screen", "شاشة القفل"));
        LinearLayout c = card(root);
        String[] sl = {"x2", "x4", "x8", "x12", "x16", "MAX"};
        LinearLayout[] subRef = new LinearLayout[1];
        Switch snd = switchItem(c, res("ic_s_volume"), t("Louder lock / unlock sound", "صوت قفل / فتح القفل أعلى"),
                t("Clean boost, no distortion", "تعلية نقية بدون تشويش"), "lock_sound_boost", true,
                () -> setEnabledDeep(subRef[0], prefs.getBoolean("lock_sound_boost", true)));
        LinearLayout s = sub(c);
        subRef[0] = s;
        TextView val = valueLabel(s, t("Loudness", "مستوى الصوت"));
        int cur = Math.max(0, Math.min(sl.length - 1, prefs.getInt("lock_sound_level", 2)));
        val.setText(sl[cur]);
        s.addView(seg(sl, cur, i -> {
            prefs.edit().putInt("lock_sound_level", i).commit();
            val.setText(sl[i]);
            preview(i);
        }), lp(MATCH, WRAP));
        TextView test = text(t("Test sound", "تجربة الصوت"), 12.5f, cAccent, true);
        Drawable play = getDrawable(res("ic_s_play")).mutate();
        play.setTint(cAccent);
        play.setBounds(0, 0, dp(14), dp(14));
        test.setCompoundDrawablesRelative(play, null, null, null);
        test.setCompoundDrawablePadding(dp(6));
        test.setGravity(Gravity.CENTER_VERTICAL);
        test.setBackground(round(0, 99, cAccent, 1));
        test.setPadding(dp(14), dp(6), dp(14), dp(6));
        test.setOnClickListener(v -> preview(Math.max(0, Math.min(sl.length - 1, prefs.getInt("lock_sound_level", 2)))));
        LinearLayout.LayoutParams tl = lp(WRAP, WRAP);
        tl.topMargin = dp(10);
        s.addView(test, tl);
        setEnabledDeep(s, snd.isChecked());

        switchItem(c, res("ic_s_vibrate"), t("Vibration on lock / unlock", "اهتزاز عند القفل / فتح القفل"),
                t("In sync with the sound", "متزامن مع الصوت"), "lock_haptics", true, null);
    }

    private void buildVolume(LinearLayout root) {
        section(root, res("ic_s_speaker"), t("Volume panel", "لوحة الصوت"));
        LinearLayout c = card(root);
        String restart = t(" · after Restart SystemUI", " · بعد إعادة تشغيل الواجهة");
        switchItem(c, res("ic_s_bell"), t("Sound mode button", "زر وضع الصوت"),
                t("Sound / vibrate / silent on top of the panel", "صوت / اهتزاز / صامت أعلى اللوحة") + restart, "vol_ringer_btn", true, null);
        LinearLayout[] subRef = new LinearLayout[1];
        Switch hap = switchItem(c, res("ic_s_haptics"), t("3D haptics button", "زر الاهتزاز ثلاثي الأبعاد"),
                t("Phone vibrates with the beat of music / video", "الهاتف يهتز مع إيقاع الموسيقى / الفيديو") + restart, "vol_haptic_btn", true,
                () -> setEnabledDeep(subRef[0], prefs.getBoolean("vol_haptic_btn", true)));
        LinearLayout s = sub(c);
        subRef[0] = s;
        String[] lv = {t("Low", "خفيف"), t("Mid", "متوسط"), t("Strong", "قوي")};
        TextView val = valueLabel(s, t("3D haptics strength", "قوة الاهتزاز ثلاثي الأبعاد"));
        int cur = Math.max(0, Math.min(2, prefs.getInt("haptic_level", 1)));
        val.setText(lv[cur]);
        s.addView(seg(lv, cur, i -> { prefs.edit().putInt("haptic_level", i).commit(); val.setText(lv[i]); }), lp(MATCH, WRAP));
        setEnabledDeep(s, hap.isChecked());
        switchItem(c, res("ic_s_mixer"), t("Per-app volume button", "زر صوت كل تطبيق"),
                t("Apps play together (no auto pause); a slider per app with its icon when 2+ play", "التطبيقات تعمل معاً بدون إيقاف؛ شريط لكل تطبيق بأيقونته عند تشغيل تطبيقين أو أكثر") + restart,
                "vol_app_btn", true, this::poke);
        switchItem(c, res("ic_s_palette"), t("Slider in your icon color", "شريط الصوت بلون الأيقونات"),
                t("Off = XOS white", "إيقاف = أبيض XOS"), "vol_icon_color", true, this::poke);
    }

    private void buildQs(LinearLayout root) {
        section(root, res("ic_s_grid"), t("Quick settings", "الإعدادات السريعة"));
        LinearLayout c = card(root);
        switchItem(c, res("ic_s_grid"), t("Tiles in your icon color", "الأزرار بلون الأيقونات"),
                t("Settings > Icon > Color. Off = XOS accent color", "الإعدادات > الأيقونة > اللون. إيقاف = لون XOS"), "qs_icon_color", true, this::poke);
    }

    private void buildIcons(LinearLayout root) {
        section(root, res("ic_s_home"), t("Home screen icons", "أيقونات الشاشة الرئيسية"));
        LinearLayout c = card(root);
        // {key, title, hint, swatch base, veil (0 none, -1 half light / half dark)}
        Object[][] st = {
                {"stock", t("Stock", "الأصلية"), t("XOS white", "أبيض XOS"), 0xFFF4F4F4, 0},
                {"frosted", t("Frosted light", "زجاج فاتح"), t("Blurred glass", "زجاج مموه"), 0xFFC96A6A, 0x99FFFFFF},
                {"frosted_dark", t("Frosted dark", "زجاج داكن"), t("Smoky glass", "زجاج دخاني"), 0xFFC96A6A, 0xB3141414},
                {"auto", t("Auto", "تلقائي"), t("Follows mode", "يتبع الوضع"), 0xFFEEEEEE, -1},
                {"tinted", t("Tinted", "ملوّن"), t("Wallpaper hue", "لون الخلفية"), 0xFFC96A6A, 0x55E94848},
                {"clear", t("Clear", "شفاف"), t("No blur", "بدون تمويه"), 0xFFC96A6A, 0x38FFFFFF},
                {"solid", t("Solid", "مصمت"), t("Soft pastel", "لون هادئ"), 0xFFF6D9DC, 0}};
        String curStyle = prefs.getString("plate", "frosted");
        if ("glass".equals(curStyle)) curStyle = "frosted";
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(14), dp(12), dp(14), dp(14));
        View[] tiles = new View[st.length];
        String[] sel = {curStyle};
        Runnable paint = () -> {
            for (int i = 0; i < st.length; i++)
                tiles[i].setBackground(st[i][0].equals(sel[0]) ? round(cSurface, 14, cAccent, 2) : round(cSurface, 14, cLine, 1));
        };
        LinearLayout row = null;
        for (int i = 0; i < st.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rl = lp(MATCH, WRAP);
                if (i > 0) rl.topMargin = dp(8);
                grid.addView(row, rl);
            }
            LinearLayout tile = new LinearLayout(this);
            tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(dp(10), dp(10), dp(10), dp(10));
            View sw = new View(this);
            sw.setBackground(swatch((Integer) st[i][3], (Integer) st[i][4]));
            tile.addView(sw, lp(dp(30), dp(30)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPaddingRelative(dp(10), 0, 0, 0);
            tx.addView(text((String) st[i][1], 12.5f, cText, true));
            tx.addView(text((String) st[i][2], 10.5f, cMuted, false));
            tile.addView(tx);
            final String key = (String) st[i][0];
            tile.setOnClickListener(v -> { sel[0] = key; paint.run(); prefs.edit().putString("plate", key).apply(); });
            tiles[i] = tile;
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            if (i % 2 == 1) tlp.setMarginStart(dp(8));
            row.addView(tile, tlp);
        }
        if (st.length % 2 == 1) {
            LinearLayout.LayoutParams e = new LinearLayout.LayoutParams(0, 1, 1f);
            e.setMarginStart(dp(8));
            row.addView(new View(this), e);
        }
        paint.run();
        c.addView(grid, lp(MATCH, WRAP));

        switchItem(c, res("ic_s_layers"), t("3D effect", "تأثير ثلاثي الأبعاد"),
                t("Soft shadow + glossy top light", "ظل ناعم ولمعة في الأعلى"), "fx3d", false, null);
        switchItem(c, res("ic_s_contrast"), t("Silhouettes for all apps", "صور ظلية لكل التطبيقات"),
                t("Apps without a themed icon", "التطبيقات التي ليس لها أيقونة ملونة"), "silhouette", true, null);

        LinearLayout apply = new LinearLayout(this);
        apply.setGravity(Gravity.CENTER);
        apply.setBackground(round(cAccent, 18, 0, 0));
        apply.setPadding(dp(16), dp(15), dp(16), dp(15));
        apply.addView(glyph(res("ic_s_refresh"), cAccentInk, 18));
        TextView at = text(t("Apply to home screen", "تطبيق على الشاشة الرئيسية"), 15, cAccentInk, false);
        at.setTypeface(Typeface.DEFAULT_BOLD);
        at.setPaddingRelative(dp(8), 0, 0, 0);
        apply.addView(at);
        apply.setOnClickListener(v -> applyNow());
        LinearLayout.LayoutParams al = lp(MATCH, WRAP);
        al.setMargins(dp(14), dp(14), dp(14), 0);
        root.addView(apply, al);
        TextView note = text(t("Rebuilds the launcher icons (root)", "يعيد بناء أيقونات الشاشة الرئيسية (روت)"), 11.5f, cMuted, false);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nl = lp(MATCH, WRAP);
        nl.topMargin = dp(8);
        root.addView(note, nl);
    }

    /** small preview tile of an icon style: base color + veil (-1 = half light / half dark) */
    private Drawable swatch(int base, int veil) {
        float r = dp(9);
        GradientDrawable b = new GradientDrawable();
        b.setColor(base);
        b.setCornerRadius(r);
        if (base == 0xFFF4F4F4 || base == 0xFFEEEEEE) b.setStroke(Math.max(1, dp(1)), cLine);
        if (veil == 0) return b;
        GradientDrawable o = new GradientDrawable();
        if (veil == -1) {
            o.setColor(0xFF222222);
            o.setCornerRadii(ar ? new float[]{r, r, 0, 0, 0, 0, r, r} : new float[]{0, 0, r, r, r, r, 0, 0});
            LayerDrawable l = new LayerDrawable(new Drawable[]{b, o});
            l.setLayerInsetRelative(1, dp(15), 0, 0, 0);
            return l;
        }
        o.setColor(veil);
        o.setCornerRadius(r);
        return new LayerDrawable(new Drawable[]{b, o});
    }

    // ------------------------------------------------------------------ splash
    /** ASEM splash: black + teal glow (dark) / logo green (light); fades into the settings */
    private View splash() {
        FrameLayout f = new FrameLayout(this);
        f.setClickable(true);
        if (night) {
            GradientDrawable base = new GradientDrawable();
            base.setColor(0xFF0E1116);
            GradientDrawable glow = new GradientDrawable();
            glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            glow.setColors(new int[]{0x2900C2A8, 0x0000C2A8});
            glow.setGradientRadius(dp(220));
            f.setBackground(new LayerDrawable(new Drawable[]{base, glow}));
        } else {
            f.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF4DC96F, 0xFF1FA774, 0xFF017F72}));
        }
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView wm = new ImageView(this);
        wm.setImageResource(res("asem_wordmark"));
        wm.setAdjustViewBounds(true);
        mid.addView(wm, lp(dp(230), WRAP));
        TextView mods = text("MODS", 15, CREAM, true);
        mods.setLetterSpacing(0.8f);
        mods.setAlpha(0.9f);
        mods.setPadding(dp(12), dp(14), 0, 0);
        mid.addView(mods, lp(WRAP, WRAP));
        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(round(night ? 0xFF232A33 : 0x40FFFFFF, 2, 0, 0));
        bar.setClipToOutline(true);
        View fill = new View(this);
        fill.setBackground(round(night ? cAccent : CREAM, 2, 0, 0));
        bar.addView(fill, new FrameLayout.LayoutParams(dp(54), MATCH));
        LinearLayout.LayoutParams bl = lp(dp(120), dp(4));
        bl.topMargin = dp(46);
        mid.addView(bar, bl);
        f.addView(mid, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        TextView by = text((ar ? "من تطوير عاصم" : "by Asem") + " · t.me/" + TG_USER, 12.5f, night ? cMuted : 0xCCF5FAE8, false);
        FrameLayout.LayoutParams byl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        byl.bottomMargin = dp(34);
        f.addView(by, byl);

        ValueAnimator run = ValueAnimator.ofFloat(-dp(54), dp(120));
        run.setDuration(700);
        run.setRepeatCount(ValueAnimator.INFINITE);
        run.addUpdateListener(a -> fill.setTranslationX((Float) a.getAnimatedValue() * (ar ? -1 : 1)));
        run.start();
        f.postDelayed(() -> {
            barIcons(!night);
            f.animate().alpha(0f).setDuration(260).withEndAction(() -> {
                run.cancel();
                if (f.getParent() != null) ((ViewGroup) f.getParent()).removeView(f);
            }).start();
        }, 750);
        return f;
    }

    // ------------------------------------------------------------------ actions
    private android.media.MediaPlayer player;

    /** Play the lock sound at this level, at the lock screen's volume (system / UI sounds). */
    private void preview(int level) {
        try {
            if (player != null) player.release();
            player = new android.media.MediaPlayer();
            player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            try (android.content.res.AssetFileDescriptor fd = getAssets().openFd("sounds/lock_" + level + ".ogg")) {
                player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            }
            player.prepare();
            player.start();
        } catch (Throwable t) { Toast.makeText(this, String.valueOf(t), Toast.LENGTH_SHORT).show(); }
    }

    private void applyNow() {
        Toast.makeText(this, t("Rebuilding home screen icons...", "جارٍ إعادة بناء الأيقونات..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int rc;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        // restart the launcher with an empty ICON cache (app_icons.db only - never launcher.db,
                        // which holds the home screen layout) so every icon is rebuilt with these settings
                        "D=/data/user_de/0/com.transsion.launcher3/databases; am force-stop com.transsion.launcher3; "
                                + "rm -f $D/app_icons.db $D/app_icons.db-journal $D/app_icons.db-wal $D/app_icons.db-shm; "
                                + "am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null"});
                rc = p.waitFor();
            } catch (Throwable e) { rc = -1; }
            final int r = rc;
            runOnUiThread(() -> Toast.makeText(this, r == 0 ? t("Done", "تم")
                    : t("Root denied - allow ASEM Mods in Magisk", "تم رفض الروت - اسمح لـ ASEM Mods في Magisk"), Toast.LENGTH_LONG).show());
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
