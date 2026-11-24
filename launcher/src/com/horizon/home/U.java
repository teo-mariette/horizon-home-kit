package com.horizon.home;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small helpers shared by the whole launcher. Sizes are written in "design units" on a 1280x720 board. */
final class U {
    static float k = 1.5f;
    static int W = 1920, H = 1080;

    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final ExecutorService IO = Executors.newFixedThreadPool(3);

    static final Typeface THIN = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REG = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MED = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    static final Typeface COND = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    // Preference keys
    static final String P_MODE = "mode";               // "scenes" | "nuvio"
    static final String P_SCENE_SECS = "scene_secs";
    static final String P_SCENES_OFF = "scenes_off";   // comma separated ids that are switched off
    static final String P_START_MARS = "start_mars";
    static final String P_LIFT = "lift_astronaut";
    static final String P_AMBIENT_MIN = "ambient_min";
    static final String P_HERO_SECS = "hero_secs";
    static final String P_HERO_FILL = "hero_fill";
    static final String P_NAME = "name";
    static final String P_GREET = "greet";
    static final String P_GLASS = "glass";
    static final String P_SOUND = "scene_sound";

    static final String MODE_SCENES = "scenes";
    static final String MODE_NUVIO = "nuvio";

    static final String NUVIO = "com.nuvio.app";

    private U() {}

    static void init(Activity a) {
        DisplayMetrics dm = new DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        W = Math.max(dm.widthPixels, dm.heightPixels);
        H = Math.min(dm.widthPixels, dm.heightPixels);
        k = W / 1280f;
    }

    static int s(float v) { return Math.round(v * k); }
    static float f(float v) { return v * k; }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static String mode(Context c) { return MODE_NUVIO; }

    static TextView text(Context c, String s, float size, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, f(size));
        t.setTextColor(color);
        int w = tf == THIN || tf == LIGHT ? 300 : tf == MED ? 600 : tf == BOLD ? 800 : tf == COND ? 700 : 450;
        t.setTypeface(V.font(c, w));
        t.setIncludeFontPadding(false);
        return t;
    }

    static int alpha(int color, float a) {
        return Color.argb(Math.round(Math.max(0, Math.min(1, a)) * 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    static int mix(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static GradientDrawable round(int color, float radiusUnits) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(f(radiusUnits));
        return g;
    }

    static GradientDrawable ring(int fill, int stroke, float strokeUnits, float radiusUnits) {
        GradientDrawable g = round(fill, radiusUnits);
        g.setStroke(Math.max(1, s(strokeUnits)), stroke);
        return g;
    }

    static FrameLayout.LayoutParams flp(int w, int h, int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.gravity = gravity;
        return p;
    }

    static FrameLayout.LayoutParams flp(int w, int h, int gravity, float l, float t, float r, float b) {
        FrameLayout.LayoutParams p = flp(w, h, gravity);
        p.setMargins(s(l), s(t), s(r), s(b));
        return p;
    }

    static LinearLayout.LayoutParams llp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams llp(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(s(l), s(t), s(r), s(b));
        return p;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static final int WRAP = FrameLayout.LayoutParams.WRAP_CONTENT;
    static final int MATCH = FrameLayout.LayoutParams.MATCH_PARENT;

    static void gone(View v, boolean gone) { if (v != null) v.setVisibility(gone ? View.GONE : View.VISIBLE); }

    static String greeting(int hour) {
        if (hour < 5) return "Good night";
        if (hour < 12) return "Good morning";
        if (hour < 17) return "Good afternoon";
        return "Good evening";
    }
}
