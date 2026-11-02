package io.borealis;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What the weather location needs from the network and from Android: the countries (named in the app's language), the
 * search of a city (Open-Meteo's geocoding, no key: only the text typed and the country code leave the TV) and a quick
 * look at the weather of the city picked (for the picture). The screens are PlaceScreen (Settings) and the welcome.
 */
final class Places {
    private Places() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Match {
        String label, name;
        double lat, lon;
    }

    interface Found { void done(List<Match> matches, boolean offline); }

    interface Weather { void done(String symbol, String temp); }

    private static String lang() {
        return Ui.es ? "es" : Ui.en ? "en" : "pt";
    }

    /** {code, name} for each country, in alphabetical order of the app's language; the TV's own country goes first. */
    static List<String[]> countries() {
        final Locale ui = new Locale(lang());
        final Collator col = Collator.getInstance(ui);
        List<String[]> list = new ArrayList<>();
        for (String cc : Locale.getISOCountries()) {
            String n = new Locale("", cc).getDisplayCountry(ui);
            if (n != null && !n.isEmpty()) list.add(new String[] {cc, n});
        }
        Collections.sort(list, (a, b) -> col.compare(a[1], b[1]));
        String home = Locale.getDefault().getCountry();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[0].equals(home)) { list.add(0, list.remove(i)); break; }
        }
        return list;
    }

    static String countryName(String code) {
        if (code == null || code.isEmpty()) return "";
        return new Locale("", code).getDisplayCountry(new Locale(lang()));
    }

    /** Looks the city up; `found` runs on the main thread (offline = the request failed, as opposed to no matches). */
    static void search(final String country, final String text, final Found found) {
        new Thread(() -> {
            final List<Match> out = new ArrayList<>();
            boolean failed = false;
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(text, "UTF-8")
                        + "&count=10&format=json&language=" + lang() + "&countryCode=" + country;
                JSONObject root = new JSONObject(HomeData.get(url));
                JSONArray results = root.optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject o = results.getJSONObject(i);
                    String name = o.optString("name", "");
                    String region = o.optString("admin1", "");
                    if (name.isEmpty()) continue;
                    Match m = new Match();
                    m.name = name;
                    m.label = region.isEmpty() || region.equals(name) ? name : name + ", " + region;
                    m.lat = o.getDouble("latitude");
                    m.lon = o.getDouble("longitude");
                    out.add(m);
                }
            } catch (Exception e) {
                AppLog.w("city search: " + e.getMessage());
                failed = true;
            }
            final boolean offline = failed;
            MAIN.post(() -> found.done(out, offline));
        }, "city-search").start();
    }

    /** The weather now in that place: the symbol and the temperature (in the units in use). Both empty when it fails. */
    static void weatherNow(final double lat, final double lon, final Weather result) {
        new Thread(() -> {
            String symbol = "", temp = "";
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day&timezone=auto";
                JSONObject cur = new JSONObject(HomeData.get(url)).getJSONObject("current");
                symbol = Symbols.weather(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
                temp = HomeData.degrees(cur.getDouble("temperature_2m"));
            } catch (Exception e) {
                AppLog.w("city weather: " + e.getMessage());
            }
            final String s = symbol, t = temp;
            MAIN.post(() -> result.done(s, t));
        }, "city-weather").start();
    }
}
