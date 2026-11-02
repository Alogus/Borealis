package io.borealis;

import android.content.Context;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Borealis's own way of asking for text on a TV (no Android keyboard): the request, its suggestion sources and the
 * entry point. TextEntryView draws the keyboard. Anything that needs a word or a place from the person uses this: the
 * name, the country and the city. An Activity that can show it implements Host.
 */
final class TextEntry {
    private TextEntry() {}

    /** The Activity that puts the keyboard on screen (HomeActivity and SettingsActivity). */
    interface Host { void askText(Request r); }

    static final class Option {
        final String label;
        final Object tag;
        Option(String label, Object tag) { this.label = label; this.tag = tag; }
    }

    /** `forText` is the text the options are for: an answer to something already overwritten is ignored. `message` may be null. */
    interface Callback { void done(String forText, List<Option> options, String message); }

    interface Source { void query(String text, Callback cb); }

    /** `option` is null when plain text was accepted. */
    interface Chosen { void picked(String text, Option option); }

    static final class Request {
        String title = "", hint = "", initial = "";
        boolean words = true;     // a capital at the start and after each space
        boolean mustPick;         // only one of the suggestions can be the answer (country, city)
        boolean slow;             // the source asks the network: wait a moment after each letter
        int maxLen = 30;
        Source source;            // null for free text (the name)
        Chosen done;
    }

    static void show(Context c, Request r) {
        if (c instanceof Host) ((Host) c).askText(r);
    }

    /** Lower case without accents, to compare what was typed with a name. */
    static String plain(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    /** The countries, filtered as the person types: the names that start with it first, then the ones that contain it. */
    static Source countries() {
        final List<String[]> all = Places.countries();
        return (text, cb) -> {
            String q = plain(text);
            List<Option> starts = new ArrayList<>(), has = new ArrayList<>();
            for (String[] c : all) {
                String n = plain(c[1]);
                if (q.isEmpty() || n.startsWith(q)) starts.add(new Option(c[1], c));
                else if (n.contains(" " + q) || n.contains(q)) has.add(new Option(c[1], c));
            }
            starts.addAll(has);
            if (starts.size() > 40) starts = new ArrayList<>(starts.subList(0, 40));
            cb.done(text, starts, q.isEmpty() ? Ui.tr("Digite para filtrar", "Type to filter", "Escribe para filtrar") : starts.isEmpty() ? Ui.tr("Sem resultados", "No matches", "Sin coincidencias") : null);
        };
    }

    /** The cities of that country that match what was typed (Open-Meteo's search; from the second letter on). */
    static Source cities(final String country) {
        return (text, cb) -> {
            String t = text.trim();
            if (t.length() < 2) {
                cb.done(text, new ArrayList<Option>(), Ui.tr("Digite pelo menos 2 letras", "Type at least 2 letters", "Escribe al menos 2 letras"));
                return;
            }
            Places.search(country, t, (list, offline) -> {
                List<Option> out = new ArrayList<>();
                for (Places.Match m : list) out.add(new Option(m.label, m));
                cb.done(text, out, offline ? Ui.tr("Sem conexão", "No connection", "Sin conexión")
                        : out.isEmpty() ? Ui.tr("Sem resultados", "No matches", "Sin coincidencias") : null);
            });
        };
    }
}
