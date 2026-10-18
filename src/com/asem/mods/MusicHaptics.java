package com.asem.mods;

import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ASEM Mods - "3D haptics": the vibrator plays along with music / video.
 * Runs inside SystemUI (it already holds RECORD_AUDIO + MODIFY_AUDIO_SETTINGS + VIBRATE), so no
 * extra app, notification or permission is needed. While media is playing, a Visualizer on the
 * output mix (session 0) is read every ~16 ms; the bass band (~45-180 Hz) of its FFT is compared
 * with its own running average and every kick / bass hit becomes a short pulse.
 * This vibrator has no amplitude control, so hit strength is expressed by pulse length.
 * On / off = Settings.Global "asem_music_haptics" (button in the volume panel).
 */
final class MusicHaptics {
    private static final String TAG = "ASEM Mods (3D haptics): ";
    static final String KEY = "asem_music_haptics";

    private static Context app;
    private static Handler worker;
    private static Visualizer vis;
    private static boolean running;
    private static byte[] fft;
    private static float avg, var;
    private static long lastHit;
    private static Vibrator vibrator;
    private static VibrationAttributes attrs;
    // strength from the ASEM Mods app slider: 0 low, 1 mid, 2 strong (pulse length + sensitivity)
    private static volatile int level = 1;
    private static final int[] MIN_MS = {10, 18, 30}, MAX_MS = {30, 50, 80}, GAP_MS = {170, 120, 95};
    private static final float[] SENS = {1.6f, 1.3f, 1.1f};

    private MusicHaptics() {}

    private static void log(String m, Throwable t) { XposedBridge.log(TAG + m + (t == null ? "" : ": " + t)); }

    static void hook(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { init((Context) p.thisObject); } catch (Throwable t) { log("init", t); }
            }
        });
    }

    static boolean isEnabled(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), KEY, 0) == 1;
    }

    static void setEnabled(Context ctx, boolean on) {
        Settings.Global.putInt(ctx.getContentResolver(), KEY, on ? 1 : 0);   // SystemUI has WRITE_SECURE_SETTINGS
    }

    private static synchronized void init(Context ctx) {
        if (app != null) return;
        app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        // "System UI: active" chip in the ASEM Mods app (SystemUI holds WRITE_SECURE_SETTINGS)
        try { Settings.Global.putString(app.getContentResolver(), "asem_sysui_hooked", String.valueOf(System.currentTimeMillis())); }
        catch (Throwable t) { log("status", t); }
        HandlerThread ht = new HandlerThread("asem-haptics", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        ht.start();
        worker = new Handler(ht.getLooper());
        VibratorManager vm = app.getSystemService(VibratorManager.class);
        vibrator = vm != null ? vm.getDefaultVibrator() : null;
        // same usage as the lock / unlock haptics (not muted by the "media vibration" setting)
        attrs = new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK).build();
        app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY), false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean self) { worker.post(MusicHaptics::watch); }
                });
        worker.post(MusicHaptics::watch);
        log("ready (on=" + isEnabled(app) + ")", null);
    }

    /** Once a second while switched on: run the analyzer only while media is actually playing. */
    private static final Runnable WATCH = MusicHaptics::watch;

    private static void watch() {
        worker.removeCallbacks(WATCH);
        boolean on = isEnabled(app);
        boolean playing = false;
        if (on) {
            level = Math.max(0, Math.min(2, Hook.prefInt("haptic_level", 1)));
            AudioManager am = app.getSystemService(AudioManager.class);
            playing = am != null && am.isMusicActive();
        }
        if (on && playing && !running) start();
        else if ((!on || !playing) && running) stop();
        if (on) worker.postDelayed(WATCH, 1000);
    }

    private static void start() {
        try {
            vis = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(1024, range[1]));
            vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);   // same feel at any volume
            vis.setEnabled(true);
            fft = new byte[vis.getCaptureSize()];
            avg = 0; var = 0; lastHit = 0;
            running = true;
            worker.post(TICK);
            log("started (capture " + fft.length + ", rate " + vis.getSamplingRate() + ")", null);
        } catch (Throwable t) {
            log("Visualizer failed", t);
            release();
        }
    }

    private static void stop() {
        running = false;
        worker.removeCallbacks(TICK);
        release();
        log("stopped", null);
    }

    private static void release() {
        try { if (vis != null) { vis.setEnabled(false); vis.release(); } } catch (Throwable ignored) { }
        vis = null;
    }

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            if (!running || vis == null) return;
            try { analyze(); } catch (Throwable t) { log("analyze", t); }
            worker.postDelayed(this, 16);
        }
    };

    private static void analyze() {
        if (vis.getFft(fft) != Visualizer.SUCCESS) return;
        // bin k = k * fs / N; fs in milliHz
        float binHz = vis.getSamplingRate() / 1000f / fft.length;
        int lo = Math.max(1, Math.round(45 / binHz)), hi = Math.max(lo, Math.round(180 / binHz));
        float e = 0;
        for (int k = lo; k <= hi && 2 * k + 1 < fft.length; k++) {
            float re = fft[2 * k], im = fft[2 * k + 1];
            e += re * re + im * im;
        }
        e /= (hi - lo + 1);
        // running average / variance (~0.5 s)
        float d = e - avg;
        avg += 0.06f * d;
        var = 0.94f * (var + 0.06f * d * d);
        float sd = (float) Math.sqrt(var);
        long now = SystemClock.uptimeMillis();
        int L = level;
        if (e > 40 && e > avg + SENS[L] * sd && e > avg * (0.05f + SENS[L]) && now - lastHit > GAP_MS[L]) {
            lastHit = now;
            float strength = Math.min(1f, (e - avg) / Math.max(1f, avg * 2f));   // 0..1
            pulse(Math.round(MIN_MS[L] + (MAX_MS[L] - MIN_MS[L]) * strength));
        }
    }

    private static void pulse(int ms) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am != null && am.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return;
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE), attrs);
    }
}
