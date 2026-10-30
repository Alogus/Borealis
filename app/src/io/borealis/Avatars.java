package io.borealis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The profile avatars: 19 round space illustrations (assets/avatars/*.png, 192 px). They are small (about 3 MB decoded in all) and all stay cached,
 * so the picker never decodes while it is being drawn.
 */
final class Avatars {
    private Avatars() {}

    /** In the order they are shown: two astronauts (closed helmets), rocket, satellite, moon, planets, aliens, galaxy, stars. */
    static final String[] IDS = {
            "astronauta-1", "astronauta-2", "astronauta-3", "cohete", "satelite", "ovni", "luna", "venus", "tierra", "marte", "jupiter", "saturno", "urano",
            "alien-h", "alien-m", "galaxia", "estrella-blanca", "estrella-lily", "estrella-peach"};
    static final String DEFAULT = "astronauta-1";

    private static final Paint PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final RectF DST = new RectF();
    private static final Map<String, Bitmap> CACHE = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) { return size() > 30; }
    };

    static boolean valid(String id) {
        if (id == null) return false;
        for (String s : IDS) if (s.equals(id)) return true;
        return false;
    }

    /** The avatar the person chose; empty when there is none yet. */
    static String current() {
        String id = Prefs.avatar();
        return valid(id) ? id : "";
    }

    static synchronized Bitmap get(Context c, String id) {
        if (!valid(id)) return null;
        return load(c, "avatars/" + id + ".png");
    }

    /** A round flag for the language cards: 0 Spanish, 1 English, 2 Portuguese, 3 automatic (a globe). */
    static void drawFlag(Canvas c, Context ctx, int index, float cx, float cy, float r) {
        Bitmap b = load(ctx, "flags/" + (index == 0 ? "es" : index == 1 ? "en" : index == 2 ? "pt" : "auto") + ".png");
        if (b == null) return;
        DST.set(cx - r, cy - r, cx + r, cy + r);
        c.drawBitmap(b, null, DST, PAINT);
    }

    private static synchronized Bitmap load(Context c, String path) {
        Bitmap b = CACHE.get(path);
        if (b != null) return b;
        try (InputStream in = c.getApplicationContext().getAssets().open(path)) {
            b = BitmapFactory.decodeStream(in);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
        if (b != null) CACHE.put(path, b);
        return b;
    }

    private static boolean preloaded;

    /** Decodes them all on a background thread (once), so the picker opens instantly. */
    static synchronized void preload(final Context c) {
        if (preloaded) return;
        preloaded = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> { for (String id : IDS) get(app, id); }, "avatars").start();
    }

    /** The avatar, centered at (cx, cy) with radius r. */
    static void draw(Canvas c, Context ctx, String id, float cx, float cy, float r, float alpha) {
        Bitmap b = get(ctx, id);
        if (b == null) return;
        DST.set(cx - r, cy - r, cx + r, cy + r);
        PAINT.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
        c.drawBitmap(b, null, DST, PAINT);
        PAINT.setAlpha(255);
    }

    private static final String[] NAME_PT = {"Astronauta", "Astronauta", "Astronauta", "Foguete", "Satélite", "Óvni", "Lua", "Vênus", "Terra", "Marte", "Júpiter", "Saturno", "Urano", "Alienígena", "Alienígena", "Galáxia", "Estrela branca", "Estrela lilás", "Estrela pêssego"}, NAME_EN = {"Astronaut", "Astronaut", "Astronaut", "Rocket", "Satellite", "UFO", "Moon", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Alien", "Alien", "Galaxy", "White star", "Lilac star", "Peach star"}, NAME_ES = {"Astronauta", "Astronauta", "Astronauta", "Cohete", "Satélite", "Ovni", "Luna", "Venus", "Tierra", "Marte", "Júpiter", "Saturno", "Urano", "Alienígena", "Alienígena", "Galaxia", "Estrella blanca", "Estrella lila", "Estrella durazno"};

    /** The avatar's name for the person (and for TalkBack): "Astronaut 1", "Moon"... */
    static String name(int i) {
        if (i < 0 || i >= IDS.length) return "";
        String n = Ui.tr(NAME_PT[i], NAME_EN[i], NAME_ES[i]);
        return i < 3 ? n + " " + (i + 1) : i == 13 || i == 14 ? n + " " + (i - 12) : n;
    }
}
