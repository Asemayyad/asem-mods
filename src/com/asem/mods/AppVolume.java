package com.asem.mods;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * ASEM Mods - per-app volume (runs in SystemUI).
 * Every app that plays sound is a "player" in AudioService. SystemUI holds MODIFY_AUDIO_ROUTING,
 * so it sees each player's app and its PlayerProxy, and PlayerProxy.setVolume(0..1) scales just
 * that app on top of the normal media volume (YouTube at 30 % while a game stays at 100 %).
 * Levels are remembered per package and re-applied whenever a player of that app starts.
 * Switch: "vol_app_btn" in the ASEM Mods app (off = every app back to 100 %).
 */
final class AppVolume {
    private static final String TAG = "ASEM Mods (app volume): ";
    private static final int STATE_STARTED = 2;          // AudioPlaybackConfiguration.PLAYER_STATE_STARTED

    static final class App {
        final String pkg; final int uid; final CharSequence label; final Drawable icon;
        App(String pkg, int uid, CharSequence label, Drawable icon) { this.pkg = pkg; this.uid = uid; this.label = label; this.icon = icon; }
    }

    private static Context app;
    private static AudioManager am;
    private static SharedPreferences store;
    private static final Map<String, Float> LEVELS = new HashMap<>();
    private static final Map<Integer, Float> APPLIED = new HashMap<>();   // piid -> volume we set
    private static Runnable listener;                                     // volume panel UI
    private static Method mUid, mState, mPiid, mProxy, mSetVol;

    private AppVolume() {}

    private static void log(String m, Throwable t) { XposedBridge.log(TAG + m + (t == null ? "" : ": " + t)); }

    static synchronized void init(Context ctx) {
        if (app != null) return;
        app = ctx;
        am = ctx.getSystemService(AudioManager.class);
        store = ctx.getSharedPreferences("asem_app_volume", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : store.getAll().entrySet())
            if (e.getValue() instanceof Float) LEVELS.put(e.getKey(), (Float) e.getValue());
        try {
            Class<?> c = AudioPlaybackConfiguration.class;
            mUid = c.getMethod("getClientUid");
            mState = c.getMethod("getPlayerState");
            mPiid = c.getMethod("getPlayerInterfaceId");
            mProxy = c.getMethod("getPlayerProxy");
            mSetVol = Class.forName("android.media.PlayerProxy").getMethod("setVolume", float.class);
        } catch (Throwable t) { log("API", t); return; }
        am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                apply(configs);
                Runnable l = listener;
                if (l != null) l.run();
            }
        }, new Handler(Looper.getMainLooper()));
        apply(am.getActivePlaybackConfigurations());
        applyMix();
        // the ASEM Mods app pokes this when a switch changes
        ctx.getContentResolver().registerContentObserver(android.provider.Settings.Global.getUriFor("asem_qs_refresh"), false,
                new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean self) {
                        applyMix();
                        if (am != null) apply(am.getActivePlaybackConfigurations());
                    }
                });
        watchCallsAndRecording(ctx);
        log("ready (" + LEVELS.size() + " saved apps)", null);
    }

    static boolean on() { return Hook.prefOn("vol_app_btn"); }

    private static Boolean mixState;
    private static boolean busy;      // a call or a recording is going on

    /**
     * While apps play together, a call (phone or VoIP) or a recording (camera video, voice recorder,
     * voice notes) pauses every playing app - some of those apps ask for audio focus like a music
     * app would, so the focus system alone would not stop the others.
     */
    private static void watchCallsAndRecording(Context ctx) {
        Handler main = new Handler(Looper.getMainLooper());
        try {
            am.addOnModeChangedListener(ctx.getMainExecutor(), mode -> onBusy(isBusy()));
        } catch (Throwable t) { log("call watch", t); }
        try {
            am.registerAudioRecordingCallback(new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<android.media.AudioRecordingConfiguration> c) { onBusy(isBusy()); }
            }, main);
        } catch (Throwable t) { log("recording watch", t); }
    }

    private static boolean isBusy() {
        int mode = am.getMode();
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING) return true;
        try {
            for (android.media.AudioRecordingConfiguration r : am.getActiveRecordingConfigurations()) {
                if (r.isClientSilenced()) continue;
                int src = r.getClientAudioSource();
                // real recordings only: mic, camera video, calls, unprocessed / performance mic
                if (src == android.media.MediaRecorder.AudioSource.DEFAULT || src == android.media.MediaRecorder.AudioSource.MIC
                        || src == android.media.MediaRecorder.AudioSource.CAMCORDER || src == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        || src == android.media.MediaRecorder.AudioSource.UNPROCESSED || src == android.media.MediaRecorder.AudioSource.VOICE_PERFORMANCE)
                    return true;
            }
        } catch (Throwable t) { log("recordings", t); }
        return false;
    }

    private static void onBusy(boolean now) {
        if (now == busy) return;
        busy = now;
        if (!now || !on()) return;
        // pause everything that is playing (media sessions: YouTube, music players, browsers ...)
        try {
            android.media.session.MediaSessionManager msm = app.getSystemService(android.media.session.MediaSessionManager.class);
            int n = 0;
            for (android.media.session.MediaController mc : msm.getActiveSessions(null)) {
                android.media.session.PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                    mc.getTransportControls().pause();
                    n++;
                }
            }
            log("call / recording started: paused " + n + " app(s)", null);
        } catch (Throwable t) { log("pause all", t); }
    }

    /**
     * Apps play together: Android's own "multi audio focus" (AudioService.setMultiAudioFocusEnabled).
     * A new media app then gets focus without the playing one being told to pause.
     * Ringing / calls still take focus from everyone (MediaFocusControl keeps that path).
     */
    static void applyMix() {
        boolean want = on();
        if (mixState != null && mixState == want) return;
        try {
            Method gs = AudioManager.class.getDeclaredMethod("getService");
            gs.setAccessible(true);
            Object svc = gs.invoke(null);
            svc.getClass().getMethod("setMultiAudioFocusEnabled", boolean.class).invoke(svc, want);
            mixState = want;
            log("apps play together: " + want, null);
        } catch (Throwable t) { log("play together", t); }
    }

    static void setListener(Runnable r) { listener = r; }

    private static boolean mediaLike(AudioPlaybackConfiguration c) {
        AudioAttributes a = c.getAudioAttributes();
        int u = a == null ? AudioAttributes.USAGE_UNKNOWN : a.getUsage();
        return u == AudioAttributes.USAGE_MEDIA || u == AudioAttributes.USAGE_GAME || u == AudioAttributes.USAGE_UNKNOWN;
    }

    private static String pkgOf(int uid) {
        try {
            String[] p = app.getPackageManager().getPackagesForUid(uid);
            return p != null && p.length > 0 ? p[0] : null;
        } catch (Throwable t) { return null; }
    }

    /** apps playing media / game sound right now, in the order they started */
    static List<App> playing() {
        List<App> out = new ArrayList<>();
        if (am == null || mUid == null) return out;
        Map<String, Integer> seen = new LinkedHashMap<>();
        try {
            for (AudioPlaybackConfiguration c : am.getActivePlaybackConfigurations()) {
                if ((Integer) mState.invoke(c) != STATE_STARTED || !mediaLike(c)) continue;
                int uid = (Integer) mUid.invoke(c);
                String pkg = pkgOf(uid);
                if (pkg != null && !seen.containsKey(pkg) && !pkg.equals(app.getPackageName())) seen.put(pkg, uid);
            }
        } catch (Throwable t) { log("list", t); }
        PackageManager pm = app.getPackageManager();
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            CharSequence label = e.getKey(); Drawable icon = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(e.getKey(), 0);
                label = pm.getApplicationLabel(ai);
                icon = pm.getApplicationIcon(ai);
            } catch (Throwable ignored) { }
            out.add(new App(e.getKey(), e.getValue(), label, icon));
        }
        return out;
    }

    static float level(String pkg) { Float f = LEVELS.get(pkg); return f == null ? 1f : f; }

    /** live while dragging; saved when save = true */
    static void setLevel(String pkg, float v, boolean save) {
        v = Math.max(0f, Math.min(1f, v));
        if (v >= 0.995f) LEVELS.remove(pkg); else LEVELS.put(pkg, v);
        if (save) {
            SharedPreferences.Editor e = store.edit();
            if (v >= 0.995f) e.remove(pkg); else e.putFloat(pkg, v);
            e.apply();
        }
        if (am != null) apply(am.getActivePlaybackConfigurations());
    }

    /** give every player of a saved app its level (and 100 % back when the level or the switch is gone) */
    private static synchronized void apply(List<AudioPlaybackConfiguration> configs) {
        if (mSetVol == null || configs == null) return;
        boolean enabled = on();
        for (AudioPlaybackConfiguration c : configs) {
            try {
                if (!mediaLike(c)) continue;
                int piid = (Integer) mPiid.invoke(c);
                String pkg = pkgOf((Integer) mUid.invoke(c));
                Float want = enabled && pkg != null ? LEVELS.get(pkg) : null;
                if (want == null) {
                    if (APPLIED.remove(piid) == null) continue;          // never touched: leave it alone
                    want = 1f;
                } else {
                    APPLIED.put(piid, want);
                }
                Object proxy = mProxy.invoke(c);
                if (proxy != null) mSetVol.invoke(proxy, want);
            } catch (Throwable t) { log("apply", t); }
        }
    }
}
