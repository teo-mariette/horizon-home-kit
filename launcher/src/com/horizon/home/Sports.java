package com.horizon.home;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Live and upcoming games for the "Live sports" row on Home.
 * Schedules, scores and team logos come from ESPN's public scoreboard feed (no key needed).
 * Which leagues show up is a comma-separated list of keys in the "sports_leagues" setting
 * (see ALL below); the default is DEFAULT.
 */
final class Sports {
    static final String PREF = "sports_leagues";
    static final String DEFAULT = "epl,ucl,laliga,ligue1,mls,"
            + "wc,intl,nations,euro,copa,gold,cnl,wcq-uefa,wcq-conmebol,wcq-concacaf,wcq-caf,wcq-afc,"
            + "nfl,mlb";
    static final String SOCCER = "Soccer", FOOTBALL = "American Football", BASEBALL = "Baseball";

    /** Show games that start within this many hours (live games always show). */
    static final long LOOKAHEAD_MS = 30L * 3600 * 1000;
    /** Most tiles in the row. */
    static final int MAX_TILES = 30;

    private static final String API = "https://site.api.espn.com/apis/site/v2/sports/";

    static final class League {
        final String key, sport, path, name, abbr;
        League(String key, String sport, String path, String name, String abbr) {
            this.key = key; this.sport = sport; this.path = path; this.name = name; this.abbr = abbr;
        }
    }

    /** Every league Home knows about. Add a line here to support another one. */
    static final League[] ALL = {
        // club soccer
        new League("epl", SOCCER, "soccer/eng.1", "Premier League", "EPL"),
        new League("ucl", SOCCER, "soccer/uefa.champions", "Champions League", "UCL"),
        new League("uel", SOCCER, "soccer/uefa.europa", "Europa League", "UEL"),
        new League("laliga", SOCCER, "soccer/esp.1", "La Liga", "LA LIGA"),
        new League("ligue1", SOCCER, "soccer/fra.1", "Ligue 1", "LIGUE 1"),
        new League("bundesliga", SOCCER, "soccer/ger.1", "Bundesliga", "BUNDES."),
        new League("seriea", SOCCER, "soccer/ita.1", "Serie A", "SERIE A"),
        new League("mls", SOCCER, "soccer/usa.1", "MLS", "MLS"),
        // international soccer
        new League("wc", SOCCER, "soccer/fifa.world", "FIFA World Cup", "WORLD CUP"),
        new League("intl", SOCCER, "soccer/fifa.friendly", "International Friendly", "FRIENDLY"),
        new League("nations", SOCCER, "soccer/uefa.nations", "UEFA Nations League", "NATIONS"),
        new League("euro", SOCCER, "soccer/uefa.euro", "UEFA Euro", "EURO"),
        new League("copa", SOCCER, "soccer/conmebol.america", "Copa America", "COPA"),
        new League("gold", SOCCER, "soccer/concacaf.gold", "CONCACAF Gold Cup", "GOLD CUP"),
        new League("cnl", SOCCER, "soccer/concacaf.nations.league", "CONCACAF Nations League", "CNL"),
        new League("afcon", SOCCER, "soccer/caf.nations", "Africa Cup of Nations", "AFCON"),
        new League("asiancup", SOCCER, "soccer/afc.asian.cup", "AFC Asian Cup", "ASIAN CUP"),
        new League("wcq-uefa", SOCCER, "soccer/fifa.worldq.uefa", "World Cup Qualifying (Europe)", "WCQ"),
        new League("wcq-conmebol", SOCCER, "soccer/fifa.worldq.conmebol", "World Cup Qualifying (South America)", "WCQ"),
        new League("wcq-concacaf", SOCCER, "soccer/fifa.worldq.concacaf", "World Cup Qualifying (CONCACAF)", "WCQ"),
        new League("wcq-caf", SOCCER, "soccer/fifa.worldq.caf", "World Cup Qualifying (Africa)", "WCQ"),
        new League("wcq-afc", SOCCER, "soccer/fifa.worldq.afc", "World Cup Qualifying (Asia)", "WCQ"),
        // American football
        new League("nfl", FOOTBALL, "football/nfl", "NFL", "NFL"),
        new League("ncaaf", FOOTBALL, "football/college-football", "College Football", "NCAAF"),
        // baseball
        new League("mlb", BASEBALL, "baseball/mlb", "MLB", "MLB"),
    };

    static final class Match {
        String id, sport, leagueName, leagueAbbr;
        String state = "pre";          // pre = not started, in = live
        String detail = "";            // live clock / period, e.g. 78' or 4:12 - 3rd
        String venue = "", broadcast = "";
        long startMs;
        String homeName = "", homeAbbr = "", homeLogo = "", homeScore = "";
        String awayName = "", awayAbbr = "", awayLogo = "", awayScore = "";
        int order;                     // position of the league in your list

        boolean live() { return "in".equals(state); }
        /** Soccer lists the home side first; US sports list the away side first ("away @ home"). */
        boolean homeFirst() { return SOCCER.equals(sport); }
        String score() {
            if (TextUtils.isEmpty(homeScore) || TextUtils.isEmpty(awayScore)) return "";
            return homeFirst() ? homeScore + " - " + awayScore : awayScore + " - " + homeScore;
        }
        String title() {
            return homeFirst() ? homeName + " v " + awayName : awayName + " @ " + homeName;
        }
    }

    static final class Result {
        final List<Match> matches = new ArrayList<>();
        int total, failed;
    }

    private Sports() {}

    static int color(String sport) {
        if (SOCCER.equals(sport)) return 0xFF1F8A4C;
        if (FOOTBALL.equals(sport)) return 0xFFB5651D;
        return 0xFF2F6FDB;
    }

    /** A small picture of the sport for the tile: soccer ball, American football, baseball. */
    static String emoji(String sport) {
        if (SOCCER.equals(sport)) return "\u26BD";
        if (FOOTBALL.equals(sport)) return "\uD83C\uDFC8";
        return "\u26BE";
    }

    /** The leagues chosen in settings, in the order they were listed. */
    static List<League> chosen(Context c) {
        String s = U.prefs(c).getString(PREF, DEFAULT);
        List<League> out = new ArrayList<>();
        for (String k : s.split(",")) {
            k = k.trim().toLowerCase(Locale.ROOT);
            for (League l : ALL) if (l.key.equals(k) && !out.contains(l)) out.add(l);
        }
        if (out.isEmpty()) {
            for (String k : DEFAULT.split(",")) for (League l : ALL) if (l.key.equals(k)) out.add(l);
        }
        return out;
    }

    /** Blocking: call from a background thread. Leagues are fetched a few at a time. */
    static Result load(Context c) {
        final Result r = new Result();
        final List<League> leagues = chosen(c);
        r.total = leagues.size();
        final long now = System.currentTimeMillis();
        SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd", Locale.US);
        final String range = df.format(new Date(now - 24L * 3600 * 1000)) + "-" + df.format(new Date(now + 24L * 3600 * 1000));

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(6, leagues.size())));
        List<Future<List<Match>>> jobs = new ArrayList<>();
        for (int i = 0; i < leagues.size(); i++) {
            final League lg = leagues.get(i);
            final int order = i;
            jobs.add(pool.submit(new Callable<List<Match>>() {
                @Override public List<Match> call() throws Exception {
                    List<Match> out = new ArrayList<>();
                    parse(lg, order, Http.get(API + lg.path + "/scoreboard?dates=" + range + "&limit=100"), now, out);
                    return out;
                }
            }));
        }
        for (Future<List<Match>> job : jobs) {
            try {
                r.matches.addAll(job.get());
            } catch (Throwable t) {
                r.failed++;
            }
        }
        pool.shutdown();

        Collections.sort(r.matches, new Comparator<Match>() {
            @Override public int compare(Match a, Match b) {
                if (a.live() != b.live()) return a.live() ? -1 : 1;
                if (a.startMs != b.startMs) return a.startMs < b.startMs ? -1 : 1;
                return a.order - b.order;
            }
        });
        while (r.matches.size() > MAX_TILES) r.matches.remove(r.matches.size() - 1);
        return r;
    }

    private static void parse(League lg, int order, String body, long now, List<Match> out) throws Exception {
        JSONArray events = new JSONObject(body).optJSONArray("events");
        if (events == null) return;
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            JSONObject status = ev.optJSONObject("status");
            JSONObject type = status == null ? null : status.optJSONObject("type");
            String state = type == null ? "pre" : type.optString("state", "pre");
            String name = type == null ? "" : type.optString("name", "");
            if (!"in".equals(state) && !"pre".equals(state)) continue;           // finished games stay off the row
            if (name.contains("POSTPONED") || name.contains("CANCELED") || name.contains("CANCELLED")) continue;
            long start = parseDate(ev.optString("date"));
            if ("pre".equals(state) && (start == 0 || start > now + LOOKAHEAD_MS)) continue;

            JSONArray comps = ev.optJSONArray("competitions");
            if (comps == null || comps.length() == 0) continue;
            JSONObject comp = comps.getJSONObject(0);
            JSONArray cs = comp.optJSONArray("competitors");
            if (cs == null) continue;
            JSONObject home = null, away = null;
            for (int k = 0; k < cs.length(); k++) {
                JSONObject cj = cs.getJSONObject(k);
                if ("home".equals(cj.optString("homeAway"))) home = cj; else if ("away".equals(cj.optString("homeAway"))) away = cj;
            }
            if (home == null || away == null) continue;

            Match m = new Match();
            m.id = ev.optString("id");
            m.sport = lg.sport;
            m.leagueName = lg.name;
            m.leagueAbbr = lg.abbr;
            m.order = order;
            m.state = state;
            m.startMs = start;
            if (type != null) m.detail = type.optString("shortDetail", "");
            fill(m, home, true);
            fill(m, away, false);
            JSONObject venue = comp.optJSONObject("venue");
            if (venue != null) m.venue = venue.optString("fullName", "");
            JSONArray bc = comp.optJSONArray("broadcasts");
            if (bc != null && bc.length() > 0) {
                JSONArray names = bc.getJSONObject(0).optJSONArray("names");
                if (names != null && names.length() > 0) m.broadcast = names.optString(0, "");
            }
            if (!"in".equals(state)) { m.homeScore = ""; m.awayScore = ""; }
            out.add(m);
        }
    }

    private static void fill(Match m, JSONObject c, boolean isHome) {
        JSONObject team = c.optJSONObject("team");
        String nm = "", ab = "", logo = "";
        if (team != null) {
            nm = team.optString("displayName", team.optString("shortDisplayName", ""));
            ab = team.optString("abbreviation", "");
            logo = team.optString("logo", "");
        }
        if (TextUtils.isEmpty(ab) && nm.length() >= 3) ab = nm.substring(0, 3).toUpperCase(Locale.ROOT);
        String score = c.optString("score", "");
        if (isHome) { m.homeName = nm; m.homeAbbr = ab; m.homeLogo = logo; m.homeScore = score; }
        else { m.awayName = nm; m.awayAbbr = ab; m.awayLogo = logo; m.awayScore = score; }
    }

    private static long parseDate(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        String[] formats = {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String f : formats) {
            try {
                SimpleDateFormat d = new SimpleDateFormat(f, Locale.US);
                d.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date dt = d.parse(s);
                if (dt != null) return dt.getTime();
            } catch (Throwable ignored) {}
        }
        return 0;
    }
}
