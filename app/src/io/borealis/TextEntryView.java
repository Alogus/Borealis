package io.borealis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Borealis's keyboard for the TV: a full screen (the wallpaper blurred behind) with what is being typed, the
 * suggestions that match it (they update with every letter) and a keyboard of letters moved with the remote. The Activity
 * hands it the keys; a physical keyboard types too. See TextEntry for what it is asked and what it returns.
 */
final class TextEntryView extends View {
    interface Closed { void closed(); }

    private static final int CH = 0, SHIFT = 1, BACK = 2, ACCENT = 3, SPACE = 4, CANCEL = 5, OK = 6;
    private static final float ROW_W = 1100, KEY_H = 80, KEY_GAP = 10, KEY_Y0 = 618, SUG_Y = 292, SUG_H = 56, SUG_GAP = 6, BOX_W = 840;
    private static final int SUG_ROWS = 5;

    private static final class Key {
        final String label;
        final int kind;
        final float weight;
        float x, w, cx;   // design units
        Key(String label, int kind, float weight) { this.label = label; this.kind = kind; this.weight = weight; }
    }

    private final TextEntry.Request req;
    private final Closed onClosed;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder text = new StringBuilder();
    private final List<List<Key>> rows = new ArrayList<>();
    private List<TextEntry.Option> options = new ArrayList<>();
    private String message = "";
    private boolean searching, shift, accent, okDown, backDown, closed;
    private int zone;   // 0 = the keys, 1 = the suggestions
    private int kr, kc, sel, scroll;
    private final Spring appear = new Spring(0.45f, 0.9f, 0), pop = new Spring(0.28f, 0.85f, 1);
    private long lastNanos;
    private Bitmap wall;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), photo = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF(), tmp = new RectF(), r2 = new RectF();
    private Paint titleP, inputP, hintP, keyP, sugP, msgP;
    private float paintK;
    private final Runnable ask = this::runQuery;

    TextEntryView(Context c, TextEntry.Request req, Closed onClosed) {
        super(c);
        this.req = req;
        this.onClosed = onClosed;
        click = new Click(c);
        text.append(req.initial == null ? "" : req.initial);
        setContentDescription(req.title);
        setFocusable(false);
        shift = req.words && (text.length() == 0 || text.charAt(text.length() - 1) == ' ');
        buildRows();
        wall = Backdrop.panel(c);   // blurred ahead of time: nothing to wait for
        runQuery();
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        click.release();
        super.onDetachedFromWindow();
    }

    // MARK: - The keys

    private void buildRows() {
        rows.add(letters("qwertyuiop", null, null));
        rows.add(letters("asdfghjklñ", null, null));
        rows.add(letters("zxcvbnm", new Key(Ui.tr("Aa", "Aa", "Aa"), SHIFT, 1.5f), new Key(Ui.tr("Apagar", "Delete", "Borrar"), BACK, 1.5f)));
        List<Key> last = new ArrayList<>();
        last.add(new Key("´", ACCENT, 1));
        last.add(new Key("-", CH, 1));
        last.add(new Key(Ui.tr("espaço", "space", "espacio"), SPACE, 4));
        last.add(new Key(Ui.tr("Cancelar", "Cancel", "Cancelar"), CANCEL, 2));
        last.add(new Key(Ui.tr("Pronto", "Done", "Listo"), OK, 2));
        rows.add(last);
        for (List<Key> row : rows) {
            float sum = 0;
            for (Key k : row) sum += k.weight;
            float unit = (ROW_W - KEY_GAP * (row.size() - 1)) / sum, x = (1920 - ROW_W) / 2f;
            for (Key k : row) {
                k.x = x;
                k.w = unit * k.weight;
                k.cx = x + k.w / 2;
                x += k.w + KEY_GAP;
            }
        }
        kr = 1;
        kc = 4;
    }

    private List<Key> letters(String s, Key first, Key last) {
        List<Key> row = new ArrayList<>();
        if (first != null) row.add(first);
        for (int i = 0; i < s.length(); i++) row.add(new Key(String.valueOf(s.charAt(i)), CH, 1));
        if (last != null) row.add(last);
        return row;
    }

    // MARK: - Typing and suggestions

    private static String accented(char c) {
        switch (c) {
            case 'a': return "á";
            case 'e': return "é";
            case 'i': return "í";
            case 'o': return "ó";
            case 'u': return "ú";
            default: return String.valueOf(c);
        }
    }

    private void type(String s) {
        if (text.length() >= req.maxLen) return;
        if (accent && s.length() == 1) { s = accented(s.charAt(0)); }
        accent = false;
        if (shift) s = s.toUpperCase(Locale.ROOT);
        text.append(s);
        shift = req.words && s.equals(" ");
        changed();
    }

    private void space() {
        if (text.length() == 0 || text.charAt(text.length() - 1) == ' ') return;
        type(" ");
        shift = req.words;
    }

    private void erase() {
        if (text.length() == 0) return;
        text.deleteCharAt(text.length() - 1);
        accent = false;
        shift = req.words && (text.length() == 0 || text.charAt(text.length() - 1) == ' ');
        changed();
    }

    private void changed() {
        message = "";
        runQueryLater();
        invalidate();
    }

    private void runQueryLater() {
        handler.removeCallbacks(ask);
        if (req.source == null) return;
        if (req.slow && text.toString().trim().length() >= 2) {
            searching = true;
            handler.postDelayed(ask, 320);   // wait for the next letter before asking the network
        } else {
            runQuery();
        }
    }

    private void runQuery() {
        if (req.source == null) return;
        final String q = text.toString();
        req.source.query(q, (forText, opts, msg) -> {
            if (!forText.equals(text.toString()) || closed) return;   // the text moved on
            searching = false;
            options = opts == null ? new ArrayList<TextEntry.Option>() : opts;
            message = msg == null ? "" : msg;
            sel = 0;
            scroll = 0;
            if (zone == 1 && options.isEmpty()) zone = 0;
            invalidate();
        });
    }

    // MARK: - Actions

    private void finish(Runnable action) {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        onClosed.closed();
        if (action != null) action.run();
    }

    private void accept() {
        if (req.mustPick) {
            if (options.isEmpty()) return;
            pickOption(Math.max(0, Math.min(options.size() - 1, sel)));
        } else {
            final String t = text.toString().trim();
            if (t.isEmpty()) return;
            click.open();
            finish(() -> req.done.picked(t, null));
        }
    }

    private void pickOption(int i) {
        if (i < 0 || i >= options.size()) return;
        final TextEntry.Option o = options.get(i);
        click.open();
        finish(() -> req.done.picked(o.label, o));
    }

    private void cancel() {
        click.open();
        finish(null);
    }

    private void press() {
        if (zone == 1) { pickOption(sel); return; }
        Key k = rows.get(kr).get(kc);
        switch (k.kind) {
            case CH: click.move(); type(k.label); break;
            case SHIFT: click.move(); shift = !shift; invalidate(); break;
            case BACK: click.move(); erase(); break;
            case ACCENT: click.move(); accent = !accent; invalidate(); break;
            case SPACE: click.move(); space(); break;
            case CANCEL: cancel(); break;
            default: accept(); break;
        }
    }

    // MARK: - Focus

    private int nearest(int row, float x) {
        List<Key> list = rows.get(row);
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            float d = Math.abs(list.get(i).cx - x);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private void focusMoved() {
        pop.snap(0);
        pop.target = 1;
        click.move();
        invalidate();
    }

    private void move(int code) {
        int last = rows.size() - 1;
        if (zone == 1) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (sel > 0) {
                    sel--;
                    if (sel < scroll) scroll = sel;
                } else {   // around: from the first suggestion to the bottom row of keys
                    zone = 0;
                    kr = last;
                    kc = nearest(last, 960);
                }
                focusMoved();
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (sel < options.size() - 1) {
                    sel++;
                    if (sel >= scroll + SUG_ROWS) scroll = sel - SUG_ROWS + 1;
                } else {
                    zone = 0;
                    kr = 0;
                    kc = nearest(0, 960);
                }
                focusMoved();
            }
            return;
        }
        List<Key> row = rows.get(kr);
        float x = row.get(kc).cx;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                kc = (kc - 1 + row.size()) % row.size();   // around the row
                focusMoved();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                kc = (kc + 1) % row.size();
                focusMoved();
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (kr > 0) { kr--; kc = nearest(kr, x); }
                else if (!options.isEmpty()) zone = 1;   // from the top row up to the suggestions
                else { kr = last; kc = nearest(last, x); }
                focusMoved();
                break;
            default:
                if (kr < last) kr++; else kr = 0;   // from the bottom row down to the top
                kc = nearest(kr, x);
                focusMoved();
                break;
        }
    }

    /** The remote moves and presses; a physical keyboard types. Everything but the volume stays here. */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) {
            if (down) type(String.valueOf((char) ('a' + code - KeyEvent.KEYCODE_A)));
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DEL:
                if (down) erase();
                return true;
            case KeyEvent.KEYCODE_SPACE:
                if (down) space();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(code);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled()) press();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else if (backDown) {
                    backDown = false;
                    cancel();
                }
                return true;
            default:
                return true;
        }
    }

    // MARK: - Drawing

    private void paints(float k) {
        if (paintK == k && titleP != null) return;
        paintK = k;
        titleP = Ui.text(Ui.displaySemibold, 52 * k, 0xFFFFFFFF);
        titleP.setTextAlign(Paint.Align.CENTER);
        inputP = Ui.text(Ui.medium, 42 * k, 0xFFFFFFFF);
        hintP = Ui.text(Ui.regular, 36 * k, 0x80FFFFFF);
        keyP = Ui.text(Ui.medium, 32 * k, 0xFFFFFFFF);
        keyP.setTextAlign(Paint.Align.CENTER);
        sugP = Ui.text(Ui.medium, 29 * k, 0xFFFFFFFF);
        msgP = Ui.text(Ui.regular, 28 * k, 0x99FFFFFF);
        msgP.setTextAlign(Paint.Align.CENTER);
    }

    private Shader gradient(RectF b) {
        return new LinearGradient(b.left, 0, b.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP);
    }

    private void ring(Canvas c, RectF b, float radius, float width) {
        float half = width / 2;
        tmp.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(width);
        line.setShader(gradient(tmp));
        line.setColor(0xFFFFFFFF);
        c.drawRoundRect(tmp, Math.max(0, radius - half), Math.max(0, radius - half), line);
        line.setShader(null);
        line.setStyle(Paint.Style.FILL);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        float k = w / 1920f;
        paints(k);
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        appear.target = 1;
        appear.step(dt);
        pop.step(dt);
        float a = Ui.clamp(appear.value);

        // The same blurred backdrop as the screens behind (at once, so nothing flashes), a little darker for typing: the extra dark fades in.
        fill.setShader(null);
        fill.setColor(Brand.INK);
        c.drawRect(0, 0, w, h, fill);
        if (wall != null) {
            box.set(0, 0, w, h);
            photo.setAlpha(204);
            c.drawBitmap(wall, null, box, photo);
        }
        fill.setColor(Ui.alpha(Brand.INK, 0.38f * a));
        c.drawRect(0, 0, w, h, fill);

        int layer = a < 0.999f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a)) : -1;
        float cx = w / 2f;
        c.drawText(req.title, cx, 118 * k, titleP);

        // What is being typed.
        float bx = cx - BOX_W * k / 2, by = 160 * k, bh = 96 * k;
        box.set(bx, by, bx + BOX_W * k, by + bh);
        fill.setColor(0x1FFFFFFF);
        c.drawRoundRect(box, 28 * k, 28 * k, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * k);
        line.setColor(0x55FFFFFF);
        tmp.set(box.left + k, box.top + k, box.right - k, box.bottom - k);
        c.drawRoundRect(tmp, 27 * k, 27 * k, line);
        line.setStyle(Paint.Style.FILL);
        float pad = 34 * k, ty = box.centerY() + Ui.halfCap(inputP);
        String shown = text.toString();
        float tw = 0;
        if (shown.isEmpty()) {
            c.drawText(req.hint, box.left + pad, box.centerY() + Ui.halfCap(hintP), hintP);
        } else {
            String fit = TextUtils.ellipsize(shown, new TextPaint(inputP), box.width() - 2 * pad - 12 * k, TextUtils.TruncateAt.START).toString();
            c.drawText(fit, box.left + pad, ty, inputP);
            tw = inputP.measureText(fit);
        }
        if ((SystemClock.uptimeMillis() / 520) % 2 == 0) {   // the caret
            fill.setColor(Brand.LILY);
            c.drawRect(box.left + pad + tw + 4 * k, box.centerY() - 28 * k, box.left + pad + tw + 8 * k, box.centerY() + 28 * k, fill);
        }

        // The suggestions.
        float sx = cx - BOX_W * k / 2;
        if (options.isEmpty()) {
            String m = searching ? Ui.tr("Buscando…", "Searching…", "Buscando…")
                    : !message.isEmpty() ? message : req.mustPick ? Ui.tr("Digite para ver sugestões", "Type to see suggestions", "Escribe para ver sugerencias") : "";
            if (!m.isEmpty()) c.drawText(m, cx, (SUG_Y + 40) * k, msgP);
        } else {
            for (int i = 0; i < SUG_ROWS; i++) {
                int idx = scroll + i;
                if (idx >= options.size()) break;
                float ry = (SUG_Y + i * (SUG_H + SUG_GAP)) * k;
                r2.set(sx, ry, sx + BOX_W * k, ry + SUG_H * k);
                boolean focused = zone == 1 && idx == sel;
                boolean first = zone == 0 && req.mustPick && idx == 0;   // what "Done" would pick
                fill.setColor(Ui.alpha(0xFFFFFFFF, focused ? 0.20f : first ? 0.14f : 0.08f));
                c.drawRoundRect(r2, 20 * k, 20 * k, fill);
                if (focused) ring(c, r2, 20 * k, 3.5f * k);
                String label = TextUtils.ellipsize(options.get(idx).label, new TextPaint(sugP), r2.width() - 64 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(label, r2.left + 30 * k, r2.centerY() + Ui.halfCap(sugP), sugP);
            }
            if (options.size() > SUG_ROWS) {   // a thin scroll bar
                float trackTop = SUG_Y * k, trackH = (SUG_ROWS * (SUG_H + SUG_GAP) - SUG_GAP) * k;
                float barH = Math.max(28 * k, trackH * SUG_ROWS / options.size());
                float barTop = trackTop + (trackH - barH) * (scroll / (float) Math.max(1, options.size() - SUG_ROWS));
                fill.setColor(0x59FFFFFF);
                tmp.set(sx + BOX_W * k + 12 * k, barTop, sx + BOX_W * k + 18 * k, barTop + barH);
                c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            }
        }

        // The keyboard.
        for (int ri = 0; ri < rows.size(); ri++) {
            List<Key> row = rows.get(ri);
            for (int ci = 0; ci < row.size(); ci++) {
                Key key = row.get(ci);
                boolean focused = zone == 0 && ri == kr && ci == kc;
                float f = focused ? Ui.clamp(pop.value) : 0;
                float sc = 1 + 0.06f * f;
                float kcx = key.cx * k, kcy = (KEY_Y0 + ri * (KEY_H + KEY_GAP) + KEY_H / 2) * k;
                float kw = key.w * k * sc, kh = KEY_H * k * sc;
                r2.set(kcx - kw / 2, kcy - kh / 2, kcx + kw / 2, kcy + kh / 2);
                boolean on = key.kind == SHIFT && shift || key.kind == ACCENT && accent;
                boolean off = key.kind == OK && (req.mustPick ? options.isEmpty() : text.toString().trim().isEmpty());
                if (key.kind == OK && !off) {
                    fill.setShader(gradient(r2));
                    fill.setColor(0xFFFFFFFF);
                    c.drawRoundRect(r2, 22 * k, 22 * k, fill);
                    fill.setShader(null);
                } else {
                    fill.setColor(Ui.alpha(0xFFFFFFFF, on ? 0.30f : 0.10f + 0.12f * f));
                    c.drawRoundRect(r2, 22 * k, 22 * k, fill);
                }
                if (on) ring(c, r2, 22 * k, 2.5f * k);
                if (focused) ring(c, r2, 22 * k, 4 * k);
                String label = key.label;
                if (key.kind == CH && shift) label = label.toUpperCase(Locale.ROOT);
                keyP.setColor(key.kind == OK ? (off ? 0x66FFFFFF : Brand.INK) : off ? 0x66FFFFFF : 0xFFFFFFFF);
                keyP.setTextSize((key.kind == CH || key.kind == ACCENT ? 36 : 27) * k);
                c.drawText(label, r2.centerX(), r2.centerY() + Ui.halfCap(keyP), keyP);
            }
        }
        if (layer >= 0) c.restoreToCount(layer);
        postInvalidateOnAnimation();   // the caret blinks
    }
}
