package com.horizon.home;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Horizon Home, Veil-style: artwork (or a living XGIMI scene) fills the wall, a calm hero tells you
 * what's next, and three pill rows hold Continue watching, Nuvio's streaming services and your apps.
 */
public class HomeActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static float EDGE = 64;
    /** Backdrops sit under a dark shade and drift slowly: 1280 px wide looks the same and uses half the memory of 1920. */
    static final int WALL_W = 1280;

    private SharedPreferences prefs;
    private FrameLayout root, ui;
    private String mode;
    private ScenePlayer player;
    private HeroWall wall;
    private Shade shade;

    // top bar
    private TextView clock, weatherText, privacyText, memText;
    private FrameLayout memBar;
    private View memFill;
    private final Runnable memTick = new Runnable() {
        @Override public void run() {
            updateMemory();
            if (started) U.MAIN.postDelayed(this, 15000);
        }
    };
    private Icon weatherIcon, wifiIcon, privacyIcon;

    // hero
    private LinearLayout hero;
    private TextView heroLabel, heroTitle, heroLine, heroRemaining;
    private ImageView heroLogo;
    private LinearLayout heroProgRow;
    private View heroProgFill;
    private Object heroKey;
    private int heroToken;

    // rows
    private LinearLayout cwBlock, cwRow, svcRow, appsRow, sportsRow;
    private TextView sportsExtra;
    private Sports.Result sports;
    private long sportsAt;
    private ScrollView homeScroll;
    /** Width of a game tile in Veil dp. 112 is exactly the Streaming services tile width. */
    private static final int SPORTS_TILE_W = 112;
    private TextView cwCount, cwTonight;
    private int defaultFocusId = View.NO_ID;
    private boolean firstFocusDone;

    // data
    private List<Apps.App> apps = new ArrayList<>();
    private Nuvio.Result nuvio;
    private long nuvioAt;
    private final List<Nuvio.Item> cwItems = new ArrayList<>();

    // state
    private boolean ambient, started, coldStart = true, swallowUp, receiversOn;
    private long lastInput = System.currentTimeMillis();
    private ConnectivityManager.NetworkCallback netCb;
    private View gifHost;
    private AnimatedImageDrawable gifPlaying;

    private final Runnable idleTick = new Runnable() {
        @Override public void run() {
            if (!started) return;
            int mins = prefs.getInt(U.P_AMBIENT_MIN, 3);
            if (!ambient && mins > 0 && System.currentTimeMillis() - lastInput > mins * 60000L) enterAmbient();
            U.MAIN.postDelayed(this, 10000);
        }
    };

    private final Runnable resumeWall = () -> { if (started && !ambient && wall != null) wall.resume(); };

    private final BroadcastReceiver timeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    private final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ================================================================== lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        U.init(this);
        V.init(this);
        prefs = U.prefs(this);
        applyIntentExtras(getIntent());
        prefs.registerOnSharedPreferenceChangeListener(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(V.BG);
        setContentView(root);
        getWindow().setBackgroundDrawable(null); // root paints the background; skip one full-screen fill per frame
        V.unclipAll(root);
        askPermissions();
        build();
        loadApps();
        Img.trim(this);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        lastInput = System.currentTimeMillis();
        registerReceivers();
        updateClock();
        updateNetwork();
        updatePrivacy();
        loadWeather();
        if (NextDnsStats.configured(this)) U.IO.execute(() -> { NextDnsStats.load(getApplicationContext()); U.MAIN.post(this::updatePrivacy); });
        startBackground();
        U.MAIN.removeCallbacks(idleTick);
        U.MAIN.postDelayed(idleTick, 10000);
        U.MAIN.removeCallbacks(memTick);
        U.MAIN.post(memTick);
    }

    @Override protected void onResume() {
        super.onResume();
        lastInput = System.currentTimeMillis();
        if (System.currentTimeMillis() - nuvioAt > 45000) loadNuvio();
        if (System.currentTimeMillis() - sportsAt > 120000) loadSports();
    }

    @Override protected void onStop() {
        super.onStop();
        started = false;
        if (player != null) player.stop();
        if (wall != null) wall.pause();
        stopGif();
        U.MAIN.removeCallbacks(idleTick);
        U.MAIN.removeCallbacks(memTick);
        U.MAIN.removeCallbacks(resumeWall);
        unregisterReceivers();
        if (ambient) exitAmbient(false);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // Home is out of sight (a film is playing): hand back cached pictures and animations.
            Img.clear();
            stopGif();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyIntentExtras(intent);
        if (ambient) exitAmbient(true);
        focusDefault();
    }

    @Override public void onBackPressed() {
        if (ambient) { exitAmbient(true); return; }
        View f = getCurrentFocus();
        if (f != null && f.getId() != defaultFocusId) focusDefault(); else enterAmbient();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        lastInput = System.currentTimeMillis();
        if (ambient) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(e);
                exitAmbient(true);
                swallowUp = true;
            }
            return true;
        }
        if (swallowUp && e.getAction() == KeyEvent.ACTION_UP) { swallowUp = false; return true; }
        if (wall != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            wall.pause();
            U.MAIN.removeCallbacks(resumeWall);
            U.MAIN.postDelayed(resumeWall, 1500);
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (key == null || key.startsWith("last_") || key.startsWith("count_") || key.equals("svc")) return;
        if (U.P_MODE.equals(key) || "edge".equals(key) || "theme".equals(key) || "art_colours".equals(key)) {
            V.loadTheme(this);
            if (player != null) player.stop();
            build();
            if (started) startBackground();
            fillRows();
        } else if (U.P_SCENE_SECS.equals(key) || U.P_SCENES_OFF.equals(key) || U.P_LIFT.equals(key) || U.P_SOUND.equals(key)) {
            if (player != null) { player.stop(); configurePlayer(); if (started) player.start(false); }
        } else if (U.P_NAME.equals(key) || U.P_GREET.equals(key)) {
            if (heroKey == null) showGreeting();
        } else if (U.P_HERO_FILL.equals(key) || "tmdb_key".equals(key)) {
            loadNuvio();
            fillRows();
        } else if (Sports.PREF.equals(key)) {
            loadSports();
        } else if (key.startsWith("cat_") || key.startsWith("hide_") || key.startsWith("pin_")) {
            loadApps();
        }
    }

    private void applyIntentExtras(Intent i) {
        if (i == null) return;
        String m = i.getStringExtra("mode");
        if (U.MODE_NUVIO.equals(m) || U.MODE_SCENES.equals(m)) U.prefs(this).edit().putString(U.P_MODE, m).apply();
        String nid = i.getStringExtra("nextdns_id"), nkey = i.getStringExtra("nextdns_key");
        if (nid != null) U.prefs(this).edit().putString("nextdns_id", nid.trim()).apply();
        if (nkey != null) U.prefs(this).edit().putString("nextdns_key", nkey.trim()).apply();
        String os = i.getStringExtra("open_service");
        if (os != null) startActivity(new Intent(this, ServiceActivity.class).putExtra("service", os));
        String tk = i.getStringExtra("tmdb");
        if (tk != null) U.prefs(this).edit().putString("tmdb_key", tk.trim()).apply();
        String nm = i.getStringExtra("name");
        if (nm != null) U.prefs(this).edit().putString(U.P_NAME, nm.trim()).apply();
        String sp = i.getStringExtra("sports");   // leagues for the Live sports row, e.g. epl,ucl,nfl,mlb
        if (sp != null) U.prefs(this).edit().putString(Sports.PREF, sp.trim()).apply();
        String spk = i.getStringExtra("sports_pkg");   // package id of your sports app, only if it isn't found by name
        if (spk != null) U.prefs(this).edit().putString("sports_pkg", spk.trim()).apply();
        String lt = i.getStringExtra("livetv");   // your Live TV addon link (without /manifest.json)
        if (lt != null) U.prefs(this).edit().putString(GuideActivity.P_SRC, lt.trim().replaceAll("/manifest\\.json$", "")).apply();
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                "com.android.providers.tv.permission.READ_EPG_DATA", "android.permission.READ_TV_LISTINGS"}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) try { requestPermissions(need.toArray(new String[0]), 7); } catch (Throwable ignored) {}
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (player != null) { player.stop(); configurePlayer(); if (started) player.start(true); }
        loadNuvio();
    }

    // ================================================================== build

    private boolean artMode() { return U.MODE_NUVIO.equals(mode); }

    private void build() {
        mode = U.mode(this);
        EDGE = V.edge(this);
        panel = null; memPanel = null; scrim = null;
        root.removeAllViews();
        root.setBackgroundColor(V.BG);
        player = null;
        wall = null;
        heroKey = null;
        ambient = false;
        V.artAccent = null;

        if (artMode()) {
            wall = new HeroWall(this);
            wall.setDriftMs(24000);
            root.addView(wall, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        } else {
            player = new ScenePlayer(this);
            root.addView(player, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            configurePlayer();
            player.setListener(new ScenePlayer.Listener() {
                @Override public void onScene(Scene s, int index, int count) {
                    if (prefs.getBoolean("art_colours", true)) setAccent(s.accent);
                    if (heroKey == null) showGreeting();
                }
                @Override public void onProgress(float f) {}
            });
        }
        shade = new Shade(this);
        root.addView(shade, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        shade.set(V.BG, null);

        ui = new FrameLayout(this);
        root.addView(ui, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        buildTopBar();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.BOTTOM);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        // The rows sit in a scroller that starts just under the top bar, so when there are more rows than
        // the screen holds, the page scrolls instead of sliding up under the bar.
        homeScroll = new ScrollView(this);
        homeScroll.setVerticalScrollBarEnabled(false);
        homeScroll.setFillViewport(true);
        homeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        homeScroll.setClipToPadding(false);
        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(U.MATCH, U.MATCH);
        scrollLp.topMargin = V.dp(26 + (EDGE - 48) / 2 + 34 + 6);
        ui.addView(homeScroll, scrollLp);
        homeScroll.addView(col, new FrameLayout.LayoutParams(U.MATCH, U.WRAP));
        col.setPadding(0, 0, 0, V.dp(22 + (EDGE - 48) / 2));
        buildHero(col);
        col.addView(space(24));
        buildContinue(col);
        buildServices(col);
        buildSports(col);
        col.addView(space(14));
        buildApps(col);
        showGreeting();
        updateClock();
        updateNetwork();
        updatePrivacy();
        updateMemory();
        loadWeather();
    }

    private View space(float dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, V.dp(dp)));
        return v;
    }

    // ------------------------------------------------------------------ top bar

    private void buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setClipChildren(false);
        ui.addView(bar, U.flp(U.MATCH, V.dp(34), Gravity.TOP, 0, 0, 0, 0));
        topBar = bar;
        ((FrameLayout.LayoutParams) bar.getLayoutParams()).topMargin = V.dp(26 + (EDGE - 48) / 2);
        bar.setPadding(V.dp(EDGE), 0, V.dp(EDGE), 0);

        // Left: Horizon menu pill (customise Home)
        LinearLayout menu = V.pill(this, 34, 12, 12);
        Icon mi = new Icon(this, Icon.ORBIT);
        menu.addView(mi, new LinearLayout.LayoutParams(V.dp(14), V.dp(14)));
        TextView mt = V.text(this, "horizon", 15, 800, V.TEXT);
        mt.setLetterSpacing(-0.02f);
        menu.addView(mt, lp(U.WRAP, U.WRAP, 7, 0, 6, 0));
        Icon chev = new Icon(this, Icon.CHEVRON_DOWN);
        menu.addView(chev, new LinearLayout.LayoutParams(V.dp(11), V.dp(11)));
        V.tint(menu, V.TEXT);
        menu.setContentDescription("Customise Home");
        menu.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout left = U.row(this);
        left.addView(menu, new LinearLayout.LayoutParams(U.WRAP, V.dp(34)));
        bar.addView(left, U.flp(U.WRAP, U.WRAP, Gravity.START | Gravity.CENTER_VERTICAL));

        // Centre: clock + weather
        LinearLayout mid = U.row(this);
        clock = V.text(this, "", 17, 400, V.TEXT);
        clock.setLetterSpacing(0.02f);
        mid.addView(clock);
        weatherIcon = new Icon(this, Icon.SUN);
        weatherIcon.setColor(U.alpha(V.TEXT, 0.9f));
        weatherIcon.setVisibility(View.GONE);
        mid.addView(weatherIcon, lp(V.dp(14), V.dp(14), 12, 0, 4, 0));
        weatherText = V.text(this, "", 14, 400, U.alpha(V.TEXT, 0.9f));
        mid.addView(weatherText);
        left.addView(mid, lp(U.WRAP, U.WRAP, 22, 0, 0, 0));

        // Memory: free RAM, refreshed every 15 s (fixed width so updates never re-layout the page)
        final LinearLayout memRow = V.pill(this, 30, 12, 12);
        memText = V.text(this, "", 11, 500, U.alpha(V.TEXT, 0.75f));
        memText.setSingleLine(true);
        memBar = new FrameLayout(this);
        memBar.setBackground(V.shape(0x33FFFFFF, 2, 0));
        memFill = new View(this);
        memFill.setPivotX(0);
        memFill.setBackground(V.shape(V.accent(), 2, 0));
        memBar.addView(memFill, new FrameLayout.LayoutParams(V.dp(36), U.MATCH));
        memRow.addView(memBar, new LinearLayout.LayoutParams(V.dp(36), V.dp(3)));
        memRow.addView(memText, lp(V.dp(74), U.WRAP, 7, 0, 0, 0));
        memRow.setContentDescription("Memory");
        memRow.setOnFocusChangeListener((v, has) -> {
            V.stylePill(memRow, has, 15, null);
            memBar.setBackground(V.shape(has ? 0x33000000 : 0x33FFFFFF, 2, 0));
            U.MAIN.removeCallbacks(openMem);
            if (has) U.MAIN.postDelayed(openMem, 200); else hideMem();
        });
        memRow.setOnClickListener(v -> freeUpMemory());

        // Right: privacy pill + HDMI, Wi-Fi, projector settings
        LinearLayout right = U.row(this);
        right.setClipChildren(false);
        LinearLayout priv = V.pill(this, 30, 12, 14);
        privacyIcon = new Icon(this, Icon.SHIELD);
        priv.addView(privacyIcon, new LinearLayout.LayoutParams(V.dp(13), V.dp(13)));
        privacyText = V.text(this, "", 10, 500, V.TEXT);
        priv.addView(privacyText, lp(U.WRAP, U.WRAP, 8, 0, 0, 0));
        priv.setOnClickListener(v -> Toast.makeText(this, privacyMessage(), Toast.LENGTH_LONG).show());
        priv.setOnFocusChangeListener((v, has) -> {
            V.stylePill(priv, has, 15, null);
            U.MAIN.removeCallbacks(openPanel);
            if (has) U.MAIN.postDelayed(openPanel, 200); else hidePanel();
        });
        V.tint(priv, V.TEXT);
        right.addView(memRow, lp(U.WRAP, V.dp(30), 0, 0, 9, 0));
        right.addView(priv);
        right.addView(roundButton(Icon.HDMI, "HDMI", v -> openHdmi()), lp(V.dp(30), V.dp(30), 9, 0, 0, 0));
        wifiIcon = new Icon(this, Icon.WIFI);
        View wifi = V.iconButton(this, wifiIcon, "Wi-Fi");
        wifi.setOnClickListener(v -> openSetting(Settings.ACTION_WIFI_SETTINGS));
        right.addView(wifi, lp(V.dp(30), V.dp(30), 9, 0, 0, 0));
        right.addView(roundButton(Icon.GEAR, "Projector settings", v -> openProjectorSettings()), lp(V.dp(30), V.dp(30), 9, 0, 0, 0));
        bar.addView(right, U.flp(U.WRAP, U.WRAP, Gravity.END | Gravity.CENTER_VERTICAL));
    }

    // ------------------------------------------------------------------ privacy panel (NextDNS)

    private LinearLayout panel;
    private View scrim, topBar;
    private final Runnable openPanel = this::showPanel;

    private void showPanel() {
        if (panel == null) {
            panel = U.col(this);
            panel.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(16));
            panel.setBackground(V.shape(U.alpha(U.mix(V.BG, 0xFFFFFFFF, 0.16f), 0.98f), 20, 0x40FFFFFF));
            FrameLayout.LayoutParams lp = U.flp(V.dp(330), U.WRAP, Gravity.TOP | Gravity.END);
            lp.topMargin = V.dp(26 + (EDGE - 48) / 2 + 34 + 8);
            lp.rightMargin = V.dp(EDGE + 3 * 39);
            if (scrim == null) {
                scrim = new View(this);
                scrim.setBackgroundColor(0x8C000000);
                scrim.setAlpha(0f);
                ui.addView(scrim, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            }
            ui.addView(panel, lp);
            panel.setAlpha(0f);
        }
        scrim.bringToFront();
        topBar.bringToFront();
        panel.bringToFront();
        scrim.animate().cancel();
        scrim.animate().alpha(1f).setDuration(200).start();
        fillPanel(NextDnsStats.cached());
        panel.animate().cancel();
        panel.setTranslationY(-V.dpf(12));
        panel.animate().alpha(1f).translationY(0).setDuration(200).withLayer().start();
        if (NextDnsStats.configured(this)) {
            U.IO.execute(() -> {
                final NextDnsStats.Stats st = NextDnsStats.load(getApplicationContext());
                U.MAIN.post(() -> { if (panel != null && panel.getAlpha() > 0) fillPanel(st); updatePrivacy(); });
            });
        }
    }

    // ------------------------------------------------------------------ memory panel

    private LinearLayout memPanel;
    private final Runnable openMem = this::showMem;

    private void showMem() {
        if (memPanel == null) {
            memPanel = U.col(this);
            memPanel.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(16));
            memPanel.setBackground(V.shape(U.alpha(U.mix(V.BG, 0xFFFFFFFF, 0.16f), 0.98f), 20, 0x40FFFFFF));
            FrameLayout.LayoutParams lp = U.flp(V.dp(330), U.WRAP, Gravity.TOP | Gravity.END);
            lp.topMargin = V.dp(26 + (EDGE - 48) / 2 + 34 + 8);
            lp.rightMargin = V.dp(EDGE + 3 * 39);
            if (scrim == null) {
                scrim = new View(this);
                scrim.setBackgroundColor(0x8C000000);
                scrim.setAlpha(0f);
                ui.addView(scrim, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            }
            ui.addView(memPanel, lp);
            memPanel.setAlpha(0f);
        }
        scrim.bringToFront();
        topBar.bringToFront();
        memPanel.bringToFront();
        fillMem(MemStats.cached(), null);
        scrim.animate().cancel();
        scrim.animate().alpha(1f).setDuration(200).start();
        memPanel.animate().cancel();
        memPanel.setTranslationY(-V.dpf(12));
        memPanel.animate().alpha(1f).translationY(0).setDuration(200).withLayer().start();
        memLoading = true;
        U.IO.execute(() -> {
            final MemStats.Stats st = MemStats.load(getApplicationContext(), false);
            U.MAIN.post(() -> { memLoading = false; if (memPanel != null && memPanel.getAlpha() > 0) fillMem(st, null); });
        });
        U.MAIN.postDelayed(() -> {
            if (memLoading && "waiting".equals(AdbLocal.state) && memPanel != null && memPanel.getAlpha() > 0) {
                MemStats.Stats w = new MemStats.Stats();
                w.noPermission = true;
                MemStats.Stats c = MemStats.cached();
                if (c != null) { w.totalKb = c.totalKb; w.availKb = c.availKb; }
                fillMem(c == null ? null : w, null);
                if (c == null) fillMem(w, null);
            }
        }, 1500);
    }

    private void hideMem() {
        if (scrim != null) { scrim.animate().cancel(); scrim.animate().alpha(0f).setDuration(120).start(); }
        if (memPanel != null) memPanel.animate().alpha(0f).translationY(-V.dpf(12)).setDuration(120).withLayer().start();
    }

    private boolean freeing, memLoading;

    private void freeUpMemory() {
        if (freeing) return;
        freeing = true;
        if (memPanel != null) fillMem(MemStats.cached(), "Closing background apps\u2026");
        U.IO.execute(() -> {
            final long freed = MemStats.freeUp(getApplicationContext());
            final MemStats.Stats st = MemStats.cached();
            U.MAIN.post(() -> {
                freeing = false;
                updateMemory();
                if (memPanel != null && memPanel.getAlpha() > 0) fillMem(st, freed > 1024 ? "Freed " + MemStats.mb(freed) : "Nothing more to free right now");
            });
        });
    }

    private void fillMem(MemStats.Stats st, String note) {
        memPanel.removeAllViews();
        LinearLayout head = U.row(this);
        LinearLayout ht = U.col(this);
        ht.addView(V.text(this, "Memory", 13, 800, V.TEXT));
        String sub = st == null ? "Checking\u2026" : MemStats.mb(st.availKb) + " free of " + MemStats.mb(st.totalKb);
        ht.addView(V.dim(V.text(this, sub, 9, 500, V.TEXT), 0.7f), lp(U.WRAP, U.WRAP, 0, 2, 0, 0));
        head.addView(ht, new LinearLayout.LayoutParams(0, U.WRAP, 1f));
        memPanel.addView(head);
        if (st != null) {
            FrameLayout track = new FrameLayout(this);
            track.setBackground(V.shape(0x33FFFFFF, 3, 0));
            View fill = new View(this);
            float used = 1f - st.availKb / (float) Math.max(1, st.totalKb);
            fill.setBackground(V.shape(used > 0.85f ? 0xFFFF6B5C : V.accent(), 3, 0));
            track.addView(fill, new FrameLayout.LayoutParams(Math.round(V.dp(298) * used), U.MATCH));
            memPanel.addView(track, lp(U.MATCH, V.dp(5), 0, 10, 0, 0));
        }
        if (st != null && st.noPermission) {
            String msg;
            if ("waiting".equals(AdbLocal.state)) msg = "On screen now: \u201cAllow network debugging?\u201d \u2014 tick Always allow and choose Allow. Horizon only uses it to read memory and close background apps.";
            else if ("denied".equals(AdbLocal.state)) msg = "Permission was declined. Open this again to get the prompt back.";
            else msg = "Network debugging is off on the projector, so per-app memory can't be read. Turn it on in Developer options to see it here.";
            memPanel.addView(V.dim(V.text(this, msg, 10, 500, V.TEXT), 0.8f), lp(U.MATCH, U.WRAP, 0, 12, 0, 0));
        } else if (st != null) {
            memPanel.addView(V.dim(V.caps(this, "Using the most", 8, V.TEXT), 0.6f), lp(U.WRAP, U.WRAP, 0, 14, 0, 6));
            long max = st.apps.isEmpty() ? 1 : st.apps.get(0).kb;
            int shown = 0;
            for (MemStats.App a : st.apps) {
                if (shown >= 7) break;
                shown++;
                LinearLayout r = U.row(this);
                TextView n = V.text(this, a.label + (a.self ? " (this home screen)" : ""), 10, 600, V.TEXT);
                V.ellipsize(n);
                r.addView(n, new LinearLayout.LayoutParams(0, U.WRAP, 1f));
                FrameLayout bar = new FrameLayout(this);
                View f = new View(this);
                f.setBackground(V.shape(a.system ? 0x59FFFFFF : V.accent(), 2, 0));
                bar.addView(f, new FrameLayout.LayoutParams(Math.max(V.dp(2), Math.round(V.dp(60) * a.kb / (float) max)), U.MATCH));
                r.addView(bar, lp(V.dp(60), V.dp(4), 8, 0, 8, 0));
                TextView mbv = V.dim(V.text(this, MemStats.mb(a.kb), 10, 500, V.TEXT), 0.75f);
                mbv.setGravity(Gravity.END);
                r.addView(mbv, new LinearLayout.LayoutParams(V.dp(52), U.WRAP));
                memPanel.addView(r, lp(U.MATCH, U.WRAP, 0, 3, 0, 3));
            }
        }
        String foot = note != null ? note : "OK closes background apps you aren't using \u00b7 grey = Android itself";
        memPanel.addView(V.dim(V.text(this, foot, 8.5f, 500, V.TEXT), note != null ? 0.9f : 0.5f), lp(U.WRAP, U.WRAP, 0, 12, 0, 0));
    }

    private void hidePanel() {
        if (scrim != null) { scrim.animate().cancel(); scrim.animate().alpha(0f).setDuration(120).start(); }
        if (panel != null) panel.animate().alpha(0f).translationY(-V.dpf(12)).setDuration(120).withLayer().start();
    }

    private void fillPanel(NextDnsStats.Stats st) {
        panel.removeAllViews();
        boolean on = nextDnsOn();
        LinearLayout head = U.row(this);
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(V.shape(V.GLASS_STRONG, 8, 0));
        Icon sh = new Icon(this, on ? Icon.SHIELD : Icon.SHIELD_OFF);
        sh.setColor(V.TEXT);
        badge.addView(sh, U.flp(V.dp(15), V.dp(15), Gravity.CENTER));
        head.addView(badge, new LinearLayout.LayoutParams(V.dp(30), V.dp(30)));
        LinearLayout ht = U.col(this);
        ht.addView(V.text(this, "NextDNS", 13, 800, V.TEXT));
        ht.addView(V.dim(V.text(this, "Encrypted DNS \u00b7 DNS-over-TLS", 8.5f, 500, V.TEXT), 0.6f), lp(U.WRAP, U.WRAP, 0, 2, 0, 0));
        head.addView(ht, new LinearLayout.LayoutParams(0, U.WRAP, 1f));
        ((LinearLayout.LayoutParams) ht.getLayoutParams()).leftMargin = V.dp(10);
        TextView chip = V.caps(this, on ? "Active" : "Off", 8, on ? V.INK : U.alpha(V.TEXT, 0.75f));
        chip.setPadding(V.dp(8), V.dp(3), V.dp(8), V.dp(3));
        chip.setBackground(V.shape(on ? V.accent() : 0x1FFFFFFF, 10, 0));
        head.addView(chip);
        panel.addView(head);

        if (st == null) {
            panel.addView(V.dim(V.text(this, NextDnsStats.configured(this) ? "Loading today's numbers\u2026" : "Add your NextDNS API key to see what's being blocked.", 10, 500, V.TEXT), 0.75f), lp(U.WRAP, U.WRAP, 0, 12, 0, 0));
            return;
        }
        if (st.error != null) {
            panel.addView(V.dim(V.text(this, "Couldn't reach NextDNS right now.", 10, 500, V.TEXT), 0.75f), lp(U.WRAP, U.WRAP, 0, 12, 0, 0));
            return;
        }
        java.text.NumberFormat nf = java.text.NumberFormat.getIntegerInstance();
        panel.addView(V.text(this, nf.format(st.blocked) + " trackers stopped at the door.", 14, 800, V.TEXT), lp(U.WRAP, U.WRAP, 0, 12, 0, 0));
        TextView body = V.dim(V.text(this, "Every lookup your projector makes is encrypted and filtered before it leaves the house. Ads and trackers never get an answer.", 9, 500, V.TEXT), 0.7f);
        body.setLineSpacing(V.dpf(2), 1f);
        panel.addView(body, lp(U.MATCH, U.WRAP, 0, 6, 0, 0));

        LinearLayout boxes = U.row(this);
        long pct = st.total > 0 ? Math.round(st.blocked * 100.0 / st.total) : 0;
        boxes.addView(statBox(nf.format(st.blocked), "Blocked", true), new LinearLayout.LayoutParams(0, U.WRAP, 1f));
        boxes.addView(statBox(nf.format(st.total), "Lookups", false), lp(0, U.WRAP, 8, 0, 0, 0));
        ((LinearLayout.LayoutParams) boxes.getChildAt(1).getLayoutParams()).weight = 1f;
        boxes.addView(statBox(pct + "%", "Blocked %", false), lp(0, U.WRAP, 8, 0, 0, 0));
        ((LinearLayout.LayoutParams) boxes.getChildAt(2).getLayoutParams()).weight = 1f;
        panel.addView(boxes, lp(U.MATCH, U.WRAP, 0, 12, 0, 0));

        if (!st.topBlocked.isEmpty()) {
            panel.addView(V.dim(V.caps(this, "Most blocked \u00b7 24 h", 8, V.TEXT), 0.6f), lp(U.WRAP, U.WRAP, 0, 14, 0, 6));
            for (String[] d : st.topBlocked) {
                LinearLayout r = U.row(this);
                View dot = new View(this);
                dot.setBackground(V.shape(V.accent(), 3, 0));
                r.addView(dot, new LinearLayout.LayoutParams(V.dp(5), V.dp(5)));
                TextView dn = V.text(this, d[0], 10, 600, V.TEXT);
                V.ellipsize(dn);
                r.addView(dn, lp(0, U.WRAP, 8, 0, 8, 0));
                ((LinearLayout.LayoutParams) dn.getLayoutParams()).weight = 1f;
                r.addView(V.dim(V.text(this, d[1], 10, 500, V.TEXT), 0.7f));
                panel.addView(r, lp(U.MATCH, U.WRAP, 0, 3, 0, 3));
            }
        }

        panel.addView(V.dim(V.caps(this, "This week", 8, V.TEXT), 0.6f), lp(U.WRAP, U.WRAP, 0, 14, 0, 4));
        LinearLayout wk = U.row(this);
        wk.setGravity(Gravity.BOTTOM);
        LinearLayout wl = U.col(this);
        LinearLayout big = U.row(this);
        big.setGravity(Gravity.BOTTOM);
        TextView n = V.text(this, nf.format(st.weekBlocked), 16, 800, V.accent());
        big.addView(n);
        big.addView(V.dim(V.text(this, " blocked", 9, 500, V.TEXT), 0.7f));
        wl.addView(big);
        if (st.topWeek != null) wl.addView(V.dim(V.text(this, "Top: " + st.topWeek, 8.5f, 500, V.TEXT), 0.6f), lp(U.WRAP, U.WRAP, 0, 3, 0, 0));
        wk.addView(wl, new LinearLayout.LayoutParams(0, U.WRAP, 1f));
        long max = 1;
        for (long x : st.week) max = Math.max(max, x);
        LinearLayout bars = U.row(this);
        bars.setGravity(Gravity.BOTTOM);
        for (int i = 0; i < 7; i++) {
            View b = new View(this);
            b.setBackground(V.shape(i == 6 ? V.accent() : 0x40FFFFFF, 2, 0));
            int h = Math.max(V.dp(2), Math.round(V.dp(26) * st.week[i] / (float) max));
            bars.addView(b, lp(V.dp(7), h, 2, 0, 2, 0));
        }
        wk.addView(bars, new LinearLayout.LayoutParams(U.WRAP, V.dp(28)));
        panel.addView(wk, lp(U.MATCH, U.WRAP, 0, 0, 0, 0));
        panel.addView(V.dim(V.text(this, "Last 24 hours \u00b7 profile " + NextDnsStats.profile(this), 8, 500, V.TEXT), 0.5f), lp(U.WRAP, U.WRAP, 0, 12, 0, 0));
    }

    private View statBox(String value, String label, boolean accent) {
        LinearLayout b = U.col(this);
        b.setPadding(V.dp(10), V.dp(8), V.dp(10), V.dp(8));
        b.setBackground(V.shape(V.GLASS, 10, 0));
        TextView v = V.text(this, value, 14, 800, accent ? V.accent() : V.TEXT);
        V.ellipsize(v);
        b.addView(v);
        b.addView(V.dim(V.text(this, label, 8, 500, V.TEXT), 0.6f), lp(U.WRAP, U.WRAP, 0, 3, 0, 0));
        return b;
    }

    private void updateMemory() {
        if (memText == null) return;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            float used = 1f - mi.availMem / (float) mi.totalMem;
            memFill.setScaleX(Math.max(0.02f, Math.min(1f, used)));
            memFill.setBackground(V.shape(used > 0.85f ? 0xFFFF6B5C : V.accent(), 2, 0));
            String free = mi.availMem >= 1L << 30
                    ? String.format(java.util.Locale.US, "%.1f GB", mi.availMem / (float) (1L << 30))
                    : (mi.availMem >> 20) + " MB";
            memText.setText(free + " free");
        } catch (Throwable ignored) {}
    }

    private View roundButton(int icon, String label, View.OnClickListener l) {
        View b = V.iconButton(this, new Icon(this, icon), label);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(V.dp(l), V.dp(t), V.dp(r), V.dp(b));
        return p;
    }

    // ------------------------------------------------------------------ hero

    private void buildHero(LinearLayout col) {
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(V.dp(EDGE), 0, V.dp(EDGE), 0);
        heroLabel = V.caps(this, "", 10, V.accent());
        hero.addView(heroLabel);
        heroLogo = V.image(this, ImageView.ScaleType.FIT_START);
        heroLogo.setAdjustViewBounds(true);
        heroLogo.setMaxHeight(V.dp(96));
        heroLogo.setMaxWidth(V.dp(420));
        heroLogo.setVisibility(View.GONE);
        hero.addView(heroLogo, lp(U.WRAP, U.WRAP, 0, 8, 0, 0));
        heroTitle = V.text(this, "", 44, 800, V.TEXT);
        heroTitle.setLetterSpacing(-0.03f);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setLineSpacing(0, 0.92f);
        heroTitle.setMaxWidth(V.dp(520));
        hero.addView(heroTitle, lp(U.WRAP, U.WRAP, 0, 6, 0, 0));
        heroLine = V.text(this, "", 14, 400, U.alpha(V.TEXT, 0.85f));
        V.ellipsize(heroLine);
        heroLine.setMaxWidth(V.dp(560));
        hero.addView(heroLine, lp(U.WRAP, U.WRAP, 0, 8, 0, 0));
        heroProgRow = U.row(this);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(V.shape(0x40FFFFFF, 2, 0));
        heroProgFill = new View(this);
        heroProgFill.setBackground(V.shape(V.accent(), 2, 0));
        heroProgFill.setPivotX(0);
        track.addView(heroProgFill, new FrameLayout.LayoutParams(V.dp(120), U.MATCH));
        heroProgRow.addView(track, new LinearLayout.LayoutParams(V.dp(120), V.dp(2.5f)));
        heroRemaining = V.text(this, "", 11, 400, U.alpha(V.TEXT, 0.8f));
        heroProgRow.addView(heroRemaining, lp(U.WRAP, U.WRAP, 9, 0, 0, 0));
        hero.addView(heroProgRow, lp(U.WRAP, U.WRAP, 0, 10, 0, 0));
        col.addView(hero, new LinearLayout.LayoutParams(U.MATCH, V.dp(150)));
        hero.setGravity(Gravity.BOTTOM);
    }

    /** Hero text glides in: old fades out quickly, new fades in and rises 12 dp. */
    private Runnable pendingHero;

    private void setHero(final Object key, final String label, final String title, final String logoUrl, final String line,
                         final float progress, final String remaining, final String art) {
        if (key != null && key.equals(heroKey)) { if (pendingHero != null) U.MAIN.removeCallbacks(pendingHero); return; }
        if (pendingHero != null) U.MAIN.removeCallbacks(pendingHero);
        pendingHero = () -> setHeroNow(key, label, title, logoUrl, line, progress, remaining, art);
        U.MAIN.postDelayed(pendingHero, heroKey == null && "greet".equals(key) ? 0 : 140);
    }

    private void setHeroNow(Object key, String label, String title, String logoUrl, String line,
                            float progress, String remaining, String art) {
        if (key != null && key.equals(heroKey)) return;
        heroKey = key;
        final int token = ++heroToken;
        hero.animate().cancel();
        hero.animate().alpha(0f).setDuration(140).withLayer().withEndAction(() -> {
            if (token != heroToken) return;
            heroLabel.setText(label);
            heroLabel.setTextColor(V.accent());
            heroTitle.setText(title);
            heroTitle.setVisibility(View.VISIBLE);
            heroLogo.setVisibility(View.GONE);
            heroLogo.setImageDrawable(null);
            heroLine.setText(line == null ? "" : line);
            U.gone(heroLine, TextUtils.isEmpty(line));
            heroProgRow.setVisibility(progress > 0 || !TextUtils.isEmpty(remaining) ? View.VISIBLE : View.GONE);
            heroProgFill.setScaleX(Math.max(0.01f, progress));
            heroProgFill.setVisibility(progress > 0 ? View.VISIBLE : View.INVISIBLE);
            ((View) heroProgFill.getParent()).setVisibility(progress > 0 ? View.VISIBLE : View.GONE);
            heroRemaining.setText(remaining == null ? "" : remaining);
            hero.setTranslationY(V.dpf(12));
            hero.animate().alpha(ambient ? 0f : 1f).translationY(0).setStartDelay(60).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).withLayer().start();
            if (!TextUtils.isEmpty(logoUrl)) {
                Img.load(this, logoUrl, V.dp(420), bm -> {
                    if (token != heroToken || bm == null) return;
                    heroLogo.setImageBitmap(bm);
                    heroLogo.setVisibility(View.VISIBLE);
                    heroTitle.setVisibility(View.GONE);
                });
            }
        }).start();
        if (!TextUtils.isEmpty(art)) {
            // Full-screen fades are the heaviest thing this GPU draws, so the backdrop only changes once
            // focus has rested; flicking along a row moves just the text. The colour glow follows after
            // the backdrop has finished fading, never at the same time.
            final boolean first = wall == null || !wall.hasImage();
            U.MAIN.postDelayed(() -> {
                if (token != heroToken) return;
                Img.load(this, art, WALL_W, bm -> {
                    if (token != heroToken || bm == null) return;
                    if (wall != null) {
                        wall.show(bm);
                        if (root.getBackground() != null) root.setBackground(null); // the picture covers the screen now
                    }
                    if (artMode() && prefs.getBoolean("art_colours", true)) {
                        U.IO.execute(() -> {
                            final Integer c = ArtColor.extract(bm);
                            U.MAIN.postDelayed(() -> { if (token == heroToken) setAccent(c); }, 750);
                        });
                    }
                });
            }, first ? 0 : 420);
        }
    }

    private void setAccent(Integer raw) {
        V.artAccent = raw == null ? null : ArtColor.asAccent(raw);
        shade.set(V.BG, raw == null ? null : ArtColor.asGlow(raw));
        heroLabel.setTextColor(V.accent());
        heroProgFill.setBackground(V.shape(V.accent(), 2, 0));
        Ui.accent = V.accent();
    }

    private void showGreeting() {
        Calendar cal = Calendar.getInstance();
        String name = prefs.getString(U.P_NAME, "");
        String hi = U.greeting(cal.get(Calendar.HOUR_OF_DAY)) + (TextUtils.isEmpty(name) ? "" : ", " + name);
        String label;
        label = "Horizon";
        heroKey = null;
        setHero("greet", label, hi, null, DateFormat.format("EEEE, d MMMM", new Date()).toString(), 0, null, null);
    }

    // ------------------------------------------------------------------ rows

    private TextView header(LinearLayout block, String title) {
        LinearLayout h = U.row(this);
        h.setGravity(Gravity.BOTTOM);
        h.setPadding(V.dp(EDGE), 0, V.dp(EDGE), 0);
        h.addView(V.text(this, title, 12, 700, V.TEXT));
        TextView extra = V.text(this, "", 9, 400, U.alpha(V.TEXT, 0.6f));
        h.addView(extra, lp(U.WRAP, U.WRAP, 7, 0, 0, 1));
        block.addView(h);
        return extra;
    }

    private LinearLayout row(LinearLayout block, float spacing) {
        HorizontalScrollView hs = new AnchoredRow(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setHorizontalFadingEdgeEnabled(false);
        hs.setFocusable(false);
        hs.setClipToPadding(false);
        LinearLayout r = U.row(this);
        r.setPadding(V.dp(EDGE), V.dp(6), V.dp(EDGE), V.dp(8));
        r.setClipChildren(false);
        r.setTag(spacing);
        hs.addView(r, new FrameLayout.LayoutParams(U.WRAP, U.WRAP));
        block.addView(hs, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
        return r;
    }

    private void buildContinue(LinearLayout col) {
        cwBlock = U.col(this);
        LinearLayout h = U.row(this);
        h.setGravity(Gravity.BOTTOM);
        h.setPadding(V.dp(EDGE), 0, V.dp(EDGE), 0);
        h.addView(V.text(this, "Continue watching", 12, 700, V.TEXT));
        cwCount = V.text(this, "", 9, 400, U.alpha(V.TEXT, 0.6f));
        h.addView(cwCount, lp(U.WRAP, U.WRAP, 7, 0, 0, 1));
        cwTonight = V.text(this, "", 9, 400, U.alpha(V.TEXT, 0.6f));
        h.addView(cwTonight, lp(U.WRAP, U.WRAP, 7, 0, 0, 1));
        cwBlock.addView(h);
        cwRow = row(cwBlock, 11);
        cwBlock.addView(space(8));
        col.addView(cwBlock, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
        cwBlock.setVisibility(View.GONE);
    }

    private void buildServices(LinearLayout col) {
        LinearLayout block = U.col(this);
        header(block, "Streaming services").setText("on Nuvio");
        svcRow = row(block, 12);
        col.addView(block, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
    }

    private void buildApps(LinearLayout col) {
        LinearLayout block = U.col(this);
        appsRow = row(block, 10);
        col.addView(block, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
    }

    private void addToRow(LinearLayout r, View v) {
        float sp = (Float) r.getTag();
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        LinearLayout.LayoutParams p = v.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) v.getLayoutParams()
                : new LinearLayout.LayoutParams(U.WRAP, U.WRAP);
        p.rightMargin = V.dp(sp);
        r.addView(v, p);
    }

    private void fillRows() {
        if (cwRow == null) return;
        boolean hadFocus = getCurrentFocus() != null && ui.hasFocus();
        fillContinue();
        fillServices();
        fillSports();
        fillApps();
        defaultFocusId = cwRow.getChildCount() > 0 ? cwRow.getChildAt(0).getId()
                : svcRow.getChildCount() > 0 ? svcRow.getChildAt(0).getId()
                : appsRow.getChildCount() > 0 ? appsRow.getChildAt(0).getId() : View.NO_ID;
        if (!hadFocus || (!firstFocusDone && !apps.isEmpty())) {
            focusDefault();
            if (!apps.isEmpty()) firstFocusDone = true;
        }
    }

    private void focusDefault() {
        if (defaultFocusId == View.NO_ID) return;
        final View v = findViewById(defaultFocusId);
        if (homeScroll != null) homeScroll.smoothScrollTo(0, 0);
        if (v != null) v.post(() -> {
            v.requestFocus();
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null && p.getParent() instanceof HorizontalScrollView) ((HorizontalScrollView) p.getParent()).smoothScrollTo(0, 0);
        });
    }

    // --- continue watching (from Nuvio's own Continue Watching list)

    private void fillContinue() {
        cwRow.removeAllViews();
        cwItems.clear();
        if (nuvio != null) for (Nuvio.Item it : nuvio.continueWatching) { if (cwItems.size() < 12) cwItems.add(it); }
        cwBlock.setVisibility(cwItems.isEmpty() ? View.GONE : View.VISIBLE);
        int queued = 0;
        for (int i = 0; i < cwItems.size(); i++) {
            final Nuvio.Item it = cwItems.get(i);
            final int idx = i;
            long left = it.durationMs > 0 ? Math.max(0, it.durationMs - it.positionMs) : 0;
            queued += left / 60000;
            LinearLayout p = V.pill(this, 54, 6, 17);
            FrameLayout thumb = new FrameLayout(this);
            thumb.setBackground(V.shape(0x1AFFFFFF, 21, 0));
            V.round(thumb, -1);
            ImageView iv = V.image(this, ImageView.ScaleType.CENTER_CROP);
            thumb.addView(iv, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            Img.load(this, Img.sized(it.cardArt(), "w500"), V.dp(80), b -> { if (b != null) iv.setImageBitmap(b); });
            if (it.progress() > 0) {
                View shadeV = Ui.gradient(this, GradientDrawable.Orientation.TOP_BOTTOM, 0x00000000, 0x8C000000);
                thumb.addView(shadeV, U.flp(U.MATCH, V.dp(14), Gravity.BOTTOM));
                FrameLayout bar = new FrameLayout(this);
                bar.setBackground(V.shape(0x59FFFFFF, 2, 0));
                View fill = new View(this);
                fill.setBackground(V.shape(V.accent(), 2, 0));
                fill.setPivotX(0);
                fill.setScaleX(it.progress());
                bar.addView(fill, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
                FrameLayout.LayoutParams blp = U.flp(U.MATCH, V.dp(2), Gravity.BOTTOM);
                blp.setMargins(V.dp(13), 0, V.dp(13), V.dp(5));
                thumb.addView(bar, blp);
            }
            p.addView(thumb, new LinearLayout.LayoutParams(V.dp(74), V.dp(42)));
            LinearLayout tx = U.col(this);
            TextView t = V.text(this, it.title, 12, 700, V.TEXT);
            V.ellipsize(t);
            String sub = !TextUtils.isEmpty(it.episode) ? it.episode : remainingLine(it);
            TextView s = V.dim(V.text(this, sub == null ? "Movie" : sub, 9.5f, 500, V.TEXT), 0.72f);
            V.ellipsize(s);
            // Fixed width from the longer line (wrap_content cut titles short on this TV).
            int tw = (int) Math.ceil(Math.max(t.getPaint().measureText(t.getText().toString()),
                    s.getPaint().measureText(s.getText().toString()))) + V.dp(2);
            tw = Math.min(tw, V.dp(220));
            tx.addView(t, new LinearLayout.LayoutParams(tw, U.WRAP));
            tx.addView(s, lp(tw, U.WRAP, 0, 3, 0, 0));
            p.addView(tx, lp(U.WRAP, U.WRAP, 10, 0, 0, 0));
            final float r = 27;
            p.setOnFocusChangeListener((v, has) -> {
                V.stylePill(p, has, r, null);
                if (has) {
                    cwCount.setText(cwItems.size() > 1 ? (idx + 1) + " of " + cwItems.size() : "");
                    showItem(it, "Continue");
                }
            });
            p.setOnClickListener(v -> Nuvio.open(this, it));
            addToRow(cwRow, p);
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        cwTonight.setText((hour >= 17 || hour < 3) && queued >= 10 ? "·  Tonight · about " + Nuvio.duration(queued * 60000L) + " to catch up" : "");
    }

    private String remainingLine(Nuvio.Item it) {
        if (it.durationMs <= 0) return it.meta();
        long left = Math.max(0, it.durationMs - it.positionMs);
        return Nuvio.duration(left) + " left";
    }

    private void showItem(Nuvio.Item it, String label) {
        String line = !TextUtils.isEmpty(it.episode) ? it.episode : it.meta();
        String remaining = null;
        if (it.durationMs > 0) {
            long left = Math.max(0, it.durationMs - it.positionMs);
            Calendar c = Calendar.getInstance();
            c.add(Calendar.MILLISECOND, (int) left);
            remaining = Nuvio.duration(left) + " left · done by " + DateFormat.getTimeFormat(this).format(c.getTime());
        }
        String logo = it.logo;
        setHero(it.key(), label, it.title, logo, line, it.progress(), remaining, Img.sized(it.art(), "w1280"));
        if (TextUtils.isEmpty(logo) && it.tmdbId > 0) {
            final Object k = heroKey;
            U.IO.execute(() -> {
                String l = Streaming.tmdbLogo(getApplicationContext(), it);
                if (l != null) U.MAIN.post(() -> {
                    it.logo = l;
                    if (k.equals(heroKey)) Img.load(this, l, V.dp(420), bm -> {
                        if (bm != null && k.equals(heroKey)) { heroLogo.setImageBitmap(bm); heroLogo.setVisibility(View.VISIBLE); heroTitle.setVisibility(View.GONE); }
                    });
                });
            });
        }
    }

    // --- streaming services (Nuvio Personalized folders)

    private void fillServices() {
        svcRow.removeAllViews();
        int th = V.dp(63), tw = V.dp(112);
        addLiveTile(tw, th);
        for (final Streaming.Service sv : Streaming.ALL) {
            final FrameLayout t = new FrameLayout(this);
            t.setFocusable(true);
            t.setFocusableInTouchMode(true);
            t.setLayoutParams(new LinearLayout.LayoutParams(tw, th));
            V.round(t, 12);
            t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] {U.mix(sv.color, 0xFF000000, 0.55f), 0xFF08090C}));
            final TextView name = V.text(this, sv.name, 12, 800, V.TEXT);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            t.addView(name, U.flp(U.MATCH, U.WRAP, Gravity.CENTER, 4, 0, 4, 0));
            final ImageView cover = V.image(this, ImageView.ScaleType.CENTER_CROP);
            t.addView(cover, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            Img.load(this, sv.cover, tw, b -> { if (b != null) { cover.setImageBitmap(b); name.setVisibility(View.GONE); } });
            final ImageView gif = V.image(this, ImageView.ScaleType.CENTER_CROP);
            gif.setAlpha(0f);
            t.addView(gif, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            final View ring = new View(this);
            GradientDrawable rg = V.shape(0, 11, 0);
            rg.setStroke(V.dp(2), V.TEXT);
            ring.setBackground(new android.graphics.drawable.InsetDrawable(rg, V.dp(1)));
            ring.setAlpha(0f);
            t.addView(ring, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            t.setContentDescription(sv.name);
            t.setOnFocusChangeListener((v, has) -> {
                t.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                        .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
                ring.animate().alpha(has ? 1f : 0f).setDuration(150).start();
                if (has) {
                    playGif(t, gif, Streaming.gif(sv.key), tw, th);
                    setHero("svc:" + sv.key, "Streaming on Nuvio", sv.name, sv.logo,
                            "Top 10 movies · Popular series · New releases", 0, null, sv.hero);
                } else if (gifHost == t) {
                    stopGif();
                }
            });
            t.setOnClickListener(v -> openService(sv.key));
            addToRow(svcRow, t);
        }
    }

    // --- live sports (games from ESPN's public feed; a tap opens your sports app)

    private void buildSports(LinearLayout col) {
        LinearLayout block = U.col(this);
        sportsExtra = header(block, "Live sports");
        sportsExtra.setText("on SportzX");
        sportsRow = row(block, 12);
        col.addView(block, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
    }

    private void loadSports() {
        sportsAt = System.currentTimeMillis();
        U.IO.execute(() -> {
            final Sports.Result r = Sports.load(getApplicationContext());
            U.MAIN.post(() -> {
                sports = r;
                View f = getCurrentFocus();
                boolean onRow = f != null && f.getParent() == sportsRow;
                fillSports();
                if (onRow && sportsRow != null && sportsRow.getChildCount() > 0) sportsRow.getChildAt(0).requestFocus();
            });
        });
    }

    private void fillSports() {
        if (sportsRow == null) return;
        sportsRow.removeAllViews();
        int tw = V.dp(SPORTS_TILE_W), th = V.dp(63);   // 63 is the Streaming tiles' height; the width is SPORTS_TILE_W
        if (sports == null) { addSportsNote(tw, th, "Loading games\u2026", "", ""); return; }
        if (sports.matches.isEmpty()) {
            boolean failed = sports.total > 0 && sports.failed >= sports.total;
            addSportsNote(tw, th, failed ? "Couldn't load games" : "No games soon", failed ? "Focus for details" : "Check back later",
                    failed ? "Couldn't reach the sports feed" + (TextUtils.isEmpty(sports.error) ? "" : " \u00b7 " + sports.error)
                           : "Nothing live or starting in the next day for your leagues");
            return;
        }
        for (Sports.Match m : sports.matches) addMatchTile(m, tw, th);
    }

    private void addSportsNote(int tw, int th, final String title, final String sub, final String detail) {
        final FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(tw, th));
        V.round(t, 12);
        t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF1B2A22, 0xFF08090C}));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        c.setPadding(V.dp(10), 0, V.dp(8), 0);
        TextView a = V.text(this, title, 10, 800, V.TEXT);
        a.setMaxLines(2);
        a.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(a, new LinearLayout.LayoutParams(-2, -2));
        if (!TextUtils.isEmpty(sub)) {
            TextView b = V.dim(V.text(this, sub, 8.5f, 500, V.TEXT), 0.7f);
            b.setSingleLine(true);
            c.addView(b, lp(-2, -2, 0, 2, 0, 0));
        }
        t.addView(c, new FrameLayout.LayoutParams(tw, th, Gravity.CENTER));
        final View ring = new View(this);
        GradientDrawable rg = V.shape(0, 11, 0);
        rg.setStroke(V.dp(2), V.TEXT);
        ring.setBackground(new android.graphics.drawable.InsetDrawable(rg, V.dp(1)));
        ring.setAlpha(0f);
        t.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        t.setContentDescription(title);
        t.setOnFocusChangeListener((v, has) -> {
            t.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(150).start();
            if (has) setHero("sports:note", "Live sports", title, null, TextUtils.isEmpty(detail) ? sub : detail, 0, null, null);
        });
        t.setOnClickListener(v -> openSportsApp());
        addToRow(sportsRow, t);
    }

    /** A team logo from the feed (dark-background version first). If none loads, the team's letter code shows instead. */
    private View sportsLogo(final String url, String abbr, float units) {
        final FrameLayout f = new FrameLayout(this);
        f.setBackground(V.shape(0x29FFFFFF, units / 2f, 0));
        final TextView tv = V.text(this, abbr == null ? "" : abbr, units * 0.34f, 800, V.TEXT);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        f.addView(tv, new FrameLayout.LayoutParams(U.MATCH, U.WRAP, Gravity.CENTER));
        final ImageView iv = V.image(this, ImageView.ScaleType.FIT_CENTER);
        f.addView(iv, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        final int px = V.dp(units * 2.4f);
        final Img.Cb show = b -> {
            if (b == null) return;
            iv.setImageBitmap(b);
            tv.setVisibility(View.GONE);
            f.setBackground(null);
        };
        final String dark = url != null && url.contains("/500/") ? url.replace("/500/", "/500-dark/") : null;
        if (dark != null) {
            Img.load(this, dark, px, b -> { if (b != null) show.done(b); else Img.load(this, url, px, show); });
        } else {
            Img.load(this, url, px, show);
        }
        return f;
    }

    /** One team in a tile: logo, letter code, and the score while the game is live. */
    private View sportsTeamRow(String logo, String abbr, String score, boolean live) {
        LinearLayout r = U.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(sportsLogo(logo, abbr, 15), new LinearLayout.LayoutParams(V.dp(15), V.dp(15)));
        TextView a = V.text(this, abbr, 9.5f, 800, V.TEXT);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ap = lp(0, U.WRAP, 4, 0, 2, 0);
        ap.weight = 1f;
        r.addView(a, ap);
        if (live && !TextUtils.isEmpty(score)) {
            TextView s = V.text(this, score, 10.5f, 800, V.TEXT);
            s.setSingleLine(true);
            r.addView(s);
        }
        return r;
    }

    private String timeBig(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (!DateFormat.is24HourFormat(this)) { h = h % 12; if (h == 0) h = 12; }
        return h + ":" + String.format(Locale.US, "%02d", c.get(Calendar.MINUTE));
    }

    private String timeSmall(Sports.Match m) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(m.startMs);
        if (DateFormat.is24HourFormat(this)) return m.leagueAbbr;
        String ap = c.get(Calendar.HOUR_OF_DAY) < 12 ? "AM" : "PM";
        return m.leagueAbbr.length() <= 4 ? ap + " \u00b7 " + m.leagueAbbr : ap;
    }

    /** The big number for a live game: the clock (soccer), the quarter (football) or the inning (baseball). */
    private String liveBig(Sports.Match m) {
        String d = m.detail == null ? "" : m.detail.trim();
        if (d.isEmpty()) return "LIVE";
        if (Sports.SOCCER.equals(m.sport)) return d;
        int dash = d.indexOf(" - ");
        if (dash > 0) return d.substring(dash + 3).trim();
        if (d.startsWith("Top ") || d.startsWith("Bot ") || d.startsWith("Mid ") || d.startsWith("End "))
            return d.substring(0, 1) + d.substring(4).replaceAll("[^0-9]", "");
        return d;
    }

    private String liveSmall(Sports.Match m) {
        String d = m.detail == null ? "" : m.detail.trim();
        int dash = d.indexOf(" - ");
        if (!Sports.SOCCER.equals(m.sport) && dash > 0) return d.substring(0, dash).trim();
        return m.leagueAbbr;
    }

    private String dayLabel(long ms) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int d = (c.get(Calendar.YEAR) - now.get(Calendar.YEAR)) * 366 + (c.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR));
        if (d == 0) return "Today";
        if (d == 1) return "Tomorrow";
        if (d == -1) return "Yesterday";
        return DateFormat.format("EEE", c).toString();
    }

    private String whenText(Sports.Match m) {
        if (m.live()) return "\u25cf LIVE" + (TextUtils.isEmpty(m.detail) ? "" : " " + m.detail);
        return dayLabel(m.startMs) + " " + DateFormat.getTimeFormat(this).format(new Date(m.startMs));
    }

    /** "Today", otherwise the weekday (Wed). Short enough for the tile's left column. */
    private String dayShort(long ms) {
        String d = dayLabel(ms);
        if ("Today".equals(d)) return d;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return DateFormat.format("EEE", c).toString();
    }

    /** Option D: sport and big clock or start time on the left, the two teams (logo, code, score) stacked on the right. */
    private void addMatchTile(final Sports.Match m, int tw, int th) {
        final FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(tw, th));
        V.round(t, 12);
        t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {U.mix(Sports.color(m.sport), 0xFF000000, 0.4f), 0xFF08090C}));

        final boolean live = m.live();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(V.dp(6), V.dp(4), V.dp(6), V.dp(4));

        // left: sport picture + LIVE or the day, the big clock or start time, then league (or the clock under a live football quarter)
        LinearLayout left = U.col(this);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView top = V.text(this, Sports.emoji(m.sport) + " " + (live ? "LIVE" : dayShort(m.startMs)), 7, 800,
                live ? 0xFFFF6B6B : U.alpha(V.TEXT, 0.88f));
        top.setSingleLine(true);
        top.setGravity(Gravity.CENTER);
        left.addView(top, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
        String big = live ? liveBig(m) : timeBig(m.startMs);
        TextView clock = V.text(this, big, big.length() <= 3 ? 16 : big.length() <= 4 ? 15 : big.length() <= 5 ? 12 : 10, 800, V.TEXT);
        clock.setSingleLine(true);
        clock.setEllipsize(TextUtils.TruncateAt.END);
        clock.setGravity(Gravity.CENTER);
        left.addView(clock, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
        TextView low = V.dim(V.text(this, live ? liveSmall(m) : timeSmall(m), 7, 700, V.TEXT), 0.8f);
        low.setSingleLine(true);
        low.setEllipsize(TextUtils.TruncateAt.END);
        low.setGravity(Gravity.CENTER);
        left.addView(low, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
        c.addView(left, new LinearLayout.LayoutParams(V.dp(38), U.WRAP));

        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);
        c.addView(divider, lp(Math.max(1, V.dp(0.7f)), V.dp(38), 4, 0, 0, 0));

        // right: the two teams (soccer lists home first, US sports list away first)
        boolean hf = m.homeFirst();
        LinearLayout right = U.col(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(sportsTeamRow(hf ? m.homeLogo : m.awayLogo, hf ? m.homeAbbr : m.awayAbbr, hf ? m.homeScore : m.awayScore, live),
                new LinearLayout.LayoutParams(U.MATCH, V.dp(22)));
        right.addView(sportsTeamRow(hf ? m.awayLogo : m.homeLogo, hf ? m.awayAbbr : m.homeAbbr, hf ? m.awayScore : m.homeScore, live),
                lp(U.MATCH, V.dp(22), 0, 2, 0, 0));
        LinearLayout.LayoutParams rp = lp(0, U.WRAP, 5, 0, 0, 0);
        rp.weight = 1f;
        c.addView(right, rp);

        t.addView(c, new FrameLayout.LayoutParams(tw, th));
        final View ring = new View(this);
        GradientDrawable rg = V.shape(0, 11, 0);
        rg.setStroke(V.dp(2), V.TEXT);
        ring.setBackground(new android.graphics.drawable.InsetDrawable(rg, V.dp(1)));
        ring.setAlpha(0f);
        t.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        t.setContentDescription(m.title() + ", " + whenText(m));
        t.setOnFocusChangeListener((v, has) -> {
            t.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(150).start();
            if (has) showMatch(m);
        });
        t.setOnClickListener(v -> openSportsApp());
        addToRow(sportsRow, t);
    }

    /** The hero text follows the focused game: teams, league, time, score, venue and channel. */
    private void showMatch(Sports.Match m) {
        StringBuilder line = new StringBuilder(m.leagueName);
        line.append(" \u00b7 ").append(m.live() ? (TextUtils.isEmpty(m.detail) ? "Live" : "Live \u00b7 " + m.detail)
                : dayLabel(m.startMs) + " " + DateFormat.getTimeFormat(this).format(new Date(m.startMs)));
        if (m.live() && !TextUtils.isEmpty(m.score())) line.append(" \u00b7 ").append(m.score());
        if (!TextUtils.isEmpty(m.venue)) line.append(" \u00b7 ").append(m.venue);
        if (!TextUtils.isEmpty(m.broadcast)) line.append(" \u00b7 ").append(m.broadcast);
        setHero("sports:" + m.id, (m.live() ? "Live now \u00b7 " : "Coming up \u00b7 ") + m.sport, m.title(), null, line.toString(), 0, null, null);
    }

    private void openSportsApp() {
        String pkg = prefs.getString("sports_pkg", "");
        if (!TextUtils.isEmpty(pkg)) { Apps.launchPackage(this, pkg); return; }
        for (Apps.App a : apps) {
            if (a.label != null && a.label.toLowerCase(Locale.ROOT).replace(" ", "").contains("sportzx")) { Apps.launch(this, a); return; }
        }
        Toast.makeText(this, "SportzX wasn't found on this TV", Toast.LENGTH_LONG).show();
    }

    private void addLiveTile(int tw, int th) {
        final FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(tw, th));
        V.round(t, 12);
        t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF7A1420, 0xFF1A0A0E}));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        col.setPadding(V.dp(14), 0, V.dp(10), 0);
        TextView dot = V.caps(this, "● Live", 8.5f, 0xFFFF6B6B);
        dot.setSingleLine(true);
        col.addView(dot, new LinearLayout.LayoutParams(-2, -2));
        TextView name = V.text(this, "TV Guide", 13, 800, V.TEXT);
        name.setSingleLine(true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = V.dp(2);
        col.addView(name, np);
        t.addView(col, new FrameLayout.LayoutParams(tw, th, Gravity.CENTER));
        final View ring = new View(this);
        GradientDrawable rg = V.shape(0, 11, 0);
        rg.setStroke(V.dp(2), V.TEXT);
        ring.setBackground(new android.graphics.drawable.InsetDrawable(rg, V.dp(1)));
        ring.setAlpha(0f);
        t.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        t.setContentDescription("Live TV guide");
        t.setOnFocusChangeListener((v, has) -> {
            t.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(150).start();
            if (has) {
                stopGif();
                setHero("svc:live", "Live TV", "TV Guide", null,
                        "Channels with a full TV guide · watch here or in Nuvio", 0, null, null);
            }
        });
        t.setOnClickListener(v -> startActivity(new Intent(this, GuideActivity.class)));
        addToRow(svcRow, t);
    }

    private void playGif(final View host, final ImageView target, String url, int w, int h) {
        stopGif();
        gifHost = host;
        if (url == null) return;
        Anim.load(this, url, w, h, d -> {
            if (gifHost != host || !host.hasFocus() || d == null) return;
            target.setImageDrawable(d);
            if (d instanceof AnimatedImageDrawable) { gifPlaying = (AnimatedImageDrawable) d; gifPlaying.start(); }
            target.animate().alpha(1f).setDuration(220).start();
        });
    }

    private void stopGif() {
        if (gifPlaying != null) { try { gifPlaying.stop(); } catch (Throwable ignored) {} gifPlaying = null; }
        if (gifHost != null) {
            View g = ((ViewGroup) gifHost).getChildAt(2);
            if (g instanceof ImageView) { g.animate().alpha(0f).setDuration(150).start(); final ImageView gi = (ImageView) g; g.postDelayed(() -> { if (gifHost == null || gifHost != gi.getParent()) gi.setImageDrawable(null); }, 200); }
        }
        gifHost = null;
    }

    private void openService(String key) {
        Streaming.Service s = Streaming.find(key);
        if (!Streaming.canBrowse(this, s)) { Apps.launchPackage(this, U.NUVIO); return; }
        startActivity(new Intent(this, ServiceActivity.class).putExtra("service", key));
    }

    // --- apps (pinned) + All apps

    private void fillApps() {
        appsRow.removeAllViews();
        for (final Apps.App a : pinned()) {
            LinearLayout p = V.pill(this, 42, 6, 16);
            ImageView ic = V.image(this, ImageView.ScaleType.CENTER_CROP);
            ic.setImageDrawable(a.icon);
            ic.setBackground(V.shape(V.GLASS_STRONG, 15, 0));
            V.circle(ic);
            p.addView(ic, new LinearLayout.LayoutParams(V.dp(30), V.dp(30)));
            p.addView(V.text(this, a.label, 11.5f, 600, V.TEXT), lp(U.WRAP, U.WRAP, 10, 0, 0, 0));
            p.setContentDescription(a.label);
            p.setOnClickListener(v -> Apps.launch(this, a));
            p.setOnLongClickListener(v -> { appMenu(a); return true; });
            addToRow(appsRow, p);
        }
        LinearLayout all = V.pill(this, 42, 6, 18);
        FrameLayout dot = new FrameLayout(this);
        dot.setBackground(V.shape(0x24FFFFFF, 15, 0));
        Icon g = new Icon(this, Icon.GRID4);
        dot.addView(g, U.flp(V.dp(13), V.dp(13), Gravity.CENTER));
        all.addView(dot, new LinearLayout.LayoutParams(V.dp(30), V.dp(30)));
        all.addView(V.text(this, "All apps", 11.5f, 600, V.TEXT), lp(U.WRAP, U.WRAP, 10, 0, 0, 0));
        V.tint(all, V.TEXT);
        all.setOnClickListener(v -> startActivity(new Intent(this, AllAppsActivity.class)));
        addToRow(appsRow, all);
    }

    /** Pinned apps: your own pins first, otherwise your most-used apps (Nuvio always first). */
    private List<Apps.App> pinned() {
        List<Apps.App> out = new ArrayList<>();
        for (Apps.App a : apps) if (prefs.getBoolean("pin_" + a.pkg, false)) out.add(a);
        if (out.size() < 3) {
            for (Apps.App a : Apps.favourites(this, apps, 7)) {
                if (out.size() >= 7) break;
                if (!out.contains(a)) out.add(a);
            }
        }
        return out;
    }

    private void appMenu(final Apps.App a) {
        final boolean pinnedNow = prefs.getBoolean("pin_" + a.pkg, false);
        final String[] items = {pinnedNow ? "Unpin from Home" : "Pin to Home", "Hide from Home", "App info"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items, (d, which) -> {
                    if (which == 0) prefs.edit().putBoolean("pin_" + a.pkg, !pinnedNow).apply();
                    else if (which == 1) prefs.edit().putBoolean("hide_" + a.pkg, true).apply();
                    else Apps.start(this, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                })
                .show();
    }

    // ================================================================== data

    private void loadApps() {
        U.IO.execute(() -> {
            final List<Apps.App> list = Apps.load(getApplicationContext());
            U.MAIN.post(() -> { apps = list; fillRows(); });
        });
    }

    private void loadNuvio() {
        nuvioAt = System.currentTimeMillis();
        final boolean fill = prefs.getBoolean(U.P_HERO_FILL, true);
        U.IO.execute(() -> {
            final Nuvio.Result r = Nuvio.load(getApplicationContext(), fill);
            U.MAIN.post(() -> {
                nuvio = r;
                View f = getCurrentFocus();
                boolean onCw = f != null && f.getParent() == cwRow;
                fillContinue();
                defaultFocusId = cwRow.getChildCount() > 0 ? cwRow.getChildAt(0).getId()
                        : svcRow.getChildCount() > 0 ? svcRow.getChildAt(0).getId() : defaultFocusId;
                if (onCw || getCurrentFocus() == null) focusDefault();
            });
        });
    }

    private void loadWeather() {
        if (!prefs.getBoolean("weather", true)) { weatherIcon.setVisibility(View.GONE); weatherText.setText(""); return; }
        U.IO.execute(() -> {
            try {
                double lat = Double.longBitsToDouble(prefs.getLong("wx_lat", Double.doubleToLongBits(Double.NaN)));
                double lon = Double.longBitsToDouble(prefs.getLong("wx_lon", Double.doubleToLongBits(Double.NaN)));
                if (Double.isNaN(lat)) {
                    JSONObject loc = new JSONObject(Http.get("https://ipapi.co/json/"));
                    lat = loc.getDouble("latitude");
                    lon = loc.getDouble("longitude");
                    prefs.edit().putLong("wx_lat", Double.doubleToLongBits(lat)).putLong("wx_lon", Double.doubleToLongBits(lon))
                            .putString("wx_city", loc.optString("city")).apply();
                }
                JSONObject w = new JSONObject(Http.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day")).getJSONObject("current");
                final int temp = (int) Math.round(w.getDouble("temperature_2m"));
                final int code = w.optInt("weather_code");
                final boolean day = w.optInt("is_day", 1) == 1;
                U.MAIN.post(() -> {
                    weatherIcon.setType(code >= 51 ? Icon.RAIN : code >= 2 ? Icon.CLOUD : day ? Icon.SUN : Icon.MOON);
                    weatherIcon.setVisibility(View.VISIBLE);
                    weatherText.setText(temp + "°");
                });
            } catch (Throwable ignored) {}
        });
    }

    // ================================================================== background

    private void configurePlayer() {
        if (player == null) return;
        player.configure(Scene.rotation(this), prefs.getInt(U.P_SCENE_SECS, 30),
                prefs.getBoolean(U.P_LIFT, true), prefs.getBoolean(U.P_SOUND, false));
    }

    private void startBackground() {
        if (player != null) {
            if (player.count() == 0) configurePlayer();
            player.start(coldStart && prefs.getBoolean(U.P_START_MARS, true));
            coldStart = false;
        }
        if (wall != null) wall.resume();
        loadNuvio();
    }

    // ================================================================== ambient

    private void enterAmbient() {
        if (ambient) return;
        ambient = true;
        stopGif();
        ui.animate().cancel();
        ui.animate().alpha(0f).setDuration(900).withLayer().start();
        shade.animate().alpha(0.35f).setDuration(900).start();
        if (player != null) player.animateLift(0f);
    }

    private void exitAmbient(boolean animate) {
        ambient = false;
        int d = animate ? 420 : 0;
        ui.animate().cancel();
        ui.animate().alpha(1f).setDuration(d).withLayer().start();
        hero.setAlpha(1f);
        shade.animate().alpha(1f).setDuration(d).start();
        if (player != null) player.animateLift(1f);
        if (getCurrentFocus() == null) focusDefault();
    }

    // ================================================================== top bar data

    private void updateClock() {
        if (clock == null) return;
        clock.setText(DateFormat.getTimeFormat(this).format(new Date()));
    }

    private boolean nextDnsOn() {
        try {
            String m = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            String h = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
            return "hostname".equals(m) && h != null && h.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void updatePrivacy() {
        boolean on = nextDnsOn();
        privacyIcon.setType(on ? Icon.SHIELD : Icon.SHIELD_OFF);
        NextDnsStats.Stats st = NextDnsStats.cached();
        String count = st != null && st.error == null ? " \u00b7 " + java.text.NumberFormat.getIntegerInstance().format(st.blocked) + " blocked" : "";
        privacyText.setText(on ? "No ads \u00b7 No tracking" + count : "Ad blocking off");
    }

    private String privacyMessage() {
        if (!nextDnsOn()) return "Ad and tracker blocking is off.";
        String h = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
        return "Encrypted DNS through " + (h != null && h.contains("nextdns") ? "NextDNS" : h) + ": ads and trackers are blocked before they load.";
    }

    private void updateNetwork() {
        if (wifiIcon == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) { wifiIcon.setType(Icon.WIFI_OFF); return; }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) { wifiIcon.setType(Icon.ETHERNET); return; }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            int level = wi == null ? 0 : WifiManager.calculateSignalLevel(wi.getRssi(), 5);
            wifiIcon.setType(Icon.WIFI);
            wifiIcon.setLevel(Math.max(1, level));
        } catch (Throwable t) {
            wifiIcon.setType(Icon.WIFI);
        }
    }

    private void registerReceivers() {
        if (receiversOn) return;
        receiversOn = true;
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeRx, tf);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(pkgRx, pf);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { U.MAIN.post(HomeActivity.this::updateNetwork); }
                @Override public void onLost(Network network) { U.MAIN.post(HomeActivity.this::updateNetwork); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities c) { U.MAIN.post(HomeActivity.this::updateNetwork); }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Throwable ignored) {}
    }

    private void unregisterReceivers() {
        if (!receiversOn) return;
        receiversOn = false;
        try { unregisterReceiver(timeRx); } catch (Throwable ignored) {}
        try { unregisterReceiver(pkgRx); } catch (Throwable ignored) {}
        try { if (netCb != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb); } catch (Throwable ignored) {}
        netCb = null;
    }

    // ================================================================== actions

    private void openSetting(String action) {
        if (!Apps.start(this, new Intent(action))) Apps.start(this, new Intent(Settings.ACTION_SETTINGS));
    }

    private void openProjectorSettings() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"));
        if (!Apps.start(this, i)) openSetting(Settings.ACTION_SETTINGS);
    }

    private void openHdmi() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName("com.xgimi.xhplayer", "com.xgimi.xhplayer.InputActivity"));
        if (Apps.start(this, i)) return;
        try {
            TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
            if (tim != null) for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI) {
                    if (Apps.start(this, new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId())))) return;
                }
            }
        } catch (Throwable ignored) {}
        Toast.makeText(this, "Couldn't open the HDMI input", Toast.LENGTH_SHORT).show();
    }

    /**
     * Rows scroll so the focused card always sits at the left margin (like Google TV / Apple TV),
     * so nothing is ever pushed against the screen edge where the focus ring and scale get clipped.
     */
    static final class AnchoredRow extends HorizontalScrollView {
        AnchoredRow(Context c) { super(c); setSmoothScrollingEnabled(true); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
            if (getChildCount() == 0) return 0;
            int max = Math.max(0, getChildAt(0).getWidth() - getWidth());
            int target = Math.max(0, Math.min(max, r.left - V.dp(EDGE)));
            return target - getScrollX();
        }
    }
}
