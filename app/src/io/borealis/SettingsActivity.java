package io.borealis;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Borealis's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Borealis / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity implements TextEntry.Host {
    private SettingsView view;
    private android.widget.FrameLayout root;   // holds the view; the screensaver picker adds its live preview on top
    private Dreamer preview;   // "Screensaver": the screensaver right here, without changing the system setting
    private PlaceScreen place;   // "Weather location": a screen over the settings
    private TextEntryView entry;   // Borealis's keyboard, over everything

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        HomeMode.clearStaleBusy(this);
        // First time, before Borealis is the TV's home: the app's icon goes straight to the welcome (it turns Borealis on at the end).
        if (OnboardingView.pending(this) && !HomeMode.isFree(this)) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }
        Backdrop.preparePanel(this);   // the blurred backdrop of the keyboard and the weather screen, ready before they open
        view = new SettingsView(this);
        root = new android.widget.FrameLayout(this);
        Bitmap[] caps = ScreenTransition.take();   // the screen below, captured just before (the default transition)
        if (caps != null) {
            bg = new ScreenTransition.Bg(this, caps);
            root.addView(bg, new android.widget.FrameLayout.LayoutParams(-1, -1));
            view.coverBg = true;
        }
        root.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        startEntry();
        if (getIntent().getBooleanExtra("hidden", false)) {   // straight onto the hidden apps screen: the list of settings never shows first
            getIntent().removeExtra("hidden");
            view.showHiddenApps();
        }
    }

    /** The settings on screen right now (same process as the remote-key service), or null. */
    static volatile SettingsActivity current;
    static volatile long leftAt;   // when the settings last left the screen
    /** The wallpaper's soft backdrop, kept between openings (the home loads it ahead): Settings opens with its background already there. */
    static volatile Bitmap softCache;
    static volatile int softCacheIdx = -1;

    /** The Home button while the settings are open: the same soft fade as Back, and it always lands on the home screen. */
    void goHomeSoftly() {
        if (isFinishing()) return;
        final android.content.Context app = getApplicationContext();
        finish();   // exactly like Back: the fade-out lands on whatever is underneath (normally the home screen)
        // Opened from inside another app, what is underneath is that app: then Home is started (after the fade).
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (HomeActivity.resumed) return;
            try {
                app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {}
        }, 650);
    }

    // ---- The default transition (see ScreenTransition): in on open, out on Back.
    private ScreenTransition.Bg bg;
    private android.animation.ValueAnimator bgAnim;
    private boolean locked, leaving, realFinish;
    private int lockToken;
    private long leavingAt;

    /** Locks the keys while a transition plays, and always unlocks them: an animation that is cancelled never runs its end action. */
    private void lock(long ms) {
        locked = true;
        final int t = ++lockToken;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (t == lockToken) locked = false;
        }, ms + 200);
    }

    private float lift() {
        return 12f * getResources().getDisplayMetrics().widthPixels / 1280f;
    }

    // Full screens inside Settings (the shelf editor, the key picker, the hidden apps...): the same transition, over the screen they open from.
    private final java.util.ArrayList<Bitmap[]> layers = new java.util.ArrayList<>();

    private Bitmap snapshotRoot() {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(b));
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** A screen is about to open over the one on screen: that one (as it looks now) becomes the blurred background. */
    void screenForward(Runnable change) {
        Bitmap snap = bg == null || leaving || locked ? null : snapshotRoot();
        if (snap == null) {
            change.run();
            return;
        }
        layers.add(bg.pics());
        bg.setPics(new Bitmap[] {snap, ScreenTransition.blurred(snap)});
        bg.q(0);
        change.run();
        animateIn(true);
    }

    /** Leaving that screen: the picture of it fades out over the screen underneath, which is already back. */
    void screenBack(Runnable change) {
        if (bg == null || layers.isEmpty()) {
            change.run();
            return;
        }
        Bitmap snap = leaving || locked ? null : snapshotRoot();
        bg.setPics(layers.remove(layers.size() - 1));
        bg.q(1);
        change.run();
        if (snap == null) return;
        final View ghost = new View(this) {
            @Override protected void onDraw(android.graphics.Canvas c) {
                c.drawBitmap(snap, 0, 0, null);
            }
        };
        ghost.setAlpha(1f);
        root.addView(ghost, new android.widget.FrameLayout.LayoutParams(-1, -1));
        lock(ScreenTransition.OUT_BG_DELAY + ScreenTransition.OUT_BG_MS);
        ghost.animate().alpha(0).setStartDelay(ScreenTransition.OUT_BG_DELAY).setDuration(ScreenTransition.OUT_BG_MS)
                .setInterpolator(ScreenTransition.curveOut()).withEndAction(() -> {
                    root.removeView(ghost);
                    locked = false;
                }).start();
    }

    private void startEntry() {
        animateIn(false);
    }

    /** The default entry: the layers behind and the content rising in. `again` restarts it for a screen opened inside Settings. */
    private void animateIn(boolean again) {
        final android.view.animation.PathInterpolator curve = ScreenTransition.curve();
        final boolean full = bg != null;
        lock(Math.max(ScreenTransition.IN_MS, ScreenTransition.CONTENT_IN_DELAY + ScreenTransition.CONTENT_IN_MS) + 300);
        view.animate().cancel();
        if (bgAnim != null) bgAnim.cancel();
        view.setAlpha(0);
        view.setTranslationY(lift());
        Runnable go = () -> {
            if (isFinishing() || leaving) return;
            if (full) {
                bgAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
                bgAnim.setDuration(ScreenTransition.IN_MS);
                bgAnim.setInterpolator(curve);
                bgAnim.addUpdateListener(a -> bg.q((Float) a.getAnimatedValue()));
                bgAnim.start();
            }
            view.animate().alpha(1).translationY(0).setStartDelay(full ? ScreenTransition.CONTENT_IN_DELAY : 0)
                    .setDuration(full ? ScreenTransition.CONTENT_IN_MS : 250).setInterpolator(curve)
                    .withEndAction(() -> locked = false).start();
        };
        if (again) go.run(); else root.post(go);
    }

    private void playExit() {
        leaving = true;
        leavingAt = SystemClock.uptimeMillis();
        locked = true;
        final android.view.animation.PathInterpolator curve = ScreenTransition.curveOut();
        final boolean full = bg != null;
        view.animate().cancel();
        if (bgAnim != null) bgAnim.cancel();
        if (full) {
            bgAnim = android.animation.ValueAnimator.ofFloat(bg.q(), 0f);
            bgAnim.setStartDelay(ScreenTransition.OUT_BG_DELAY);
            bgAnim.setDuration(ScreenTransition.OUT_BG_MS);
            bgAnim.setInterpolator(curve);
            bgAnim.addUpdateListener(a -> bg.q((Float) a.getAnimatedValue()));
            bgAnim.start();
        }
        view.animate().alpha(0).translationY(lift()).setStartDelay(0)
                .setDuration(full ? ScreenTransition.CONTENT_OUT_MS : 200).setInterpolator(curve).start();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            realFinish = true;
            finish();
        }, full ? ScreenTransition.OUT_MS + 5 : 210);
    }

    /** Leaving the settings (Back) plays the default transition out and lands on the screen below, with no window animation. */
    @Override public void finish() {
        if (!realFinish && view != null && root != null && !isFinishing()) {
            if (!leaving) playExit();
            return;
        }
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onResume() {
        super.onResume();
        if (view == null) return;   // it sent the first run to the welcome
        current = this;
        view.resume();
        // From ADB: am start -n io.borealis/.SettingsActivity --ez screensaver true (straight to the preview).
        if (getIntent().getBooleanExtra("place", false)) {   // from the news modal "Weather location"
            getIntent().removeExtra("place");
            view.askPlace();
        }
        if (getIntent().getBooleanExtra("hidden", false)) {   // from the card "Hidden apps" at the end of All Apps
            getIntent().removeExtra("hidden");
            view.showHiddenApps();
        }
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        final boolean toHidden = intent.getBooleanExtra("hidden", false);
        if (view != null && toHidden) {   // before it is shown again, so the previous screen never flashes
            intent.removeExtra("hidden");
            view.showHiddenApps();
        }
        // The remote's Settings button while the hidden apps screen is open: it leads to Borealis's settings, not nowhere.
        if (view != null && !toHidden && !intent.hasExtra("place") && !intent.hasExtra("screensaver")) {
            view.leaveHiddenApps();
        }
    }

    @Override protected void onPause() {
        if (view == null) { super.onPause(); return; }
        if (current == this) current = null;
        leftAt = SystemClock.uptimeMillis();   // the home screen fades itself in when it comes back (see HomeView.resume)
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // While the screen comes in nothing answers but Back; while it leaves, nothing at all.
        if (leaving && SystemClock.uptimeMillis() - leavingAt > 1500 && !isFinishing()) {   // the exit never finished: finish it now
            realFinish = true;
            finish();
            return true;
        }
        if (leaving || (locked && event.getKeyCode() != KeyEvent.KEYCODE_BACK)) return true;
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        int code = event.getKeyCode();
                        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && preview instanceof DreamVideo) {
                            ((DreamVideo) preview).openCurrent();   // like the real one: OK opens the app that is playing
                        }
                        closePreview();
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (entry != null) return entry.onKey(event) || super.dispatchKeyEvent(event);
        if (place != null) return place.onKey(event) || super.dispatchKeyEvent(event);
        if (view != null && view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** Borealis's keyboard (see TextEntry): over the settings and over any screen of them. */
    @Override public void askText(final TextEntry.Request r) {
        if (root == null || entry != null || covering) return;
        covering = true;
        ScreenTransition.capture(this, caps -> {   // the screen it opens from, blurred, is its background
            covering = false;
            if (root == null || entry != null) return;
            final TextEntryView[] mine = new TextEntryView[1];
            mine[0] = new TextEntryView(this, r, () -> {
                if (mine[0] != null) root.removeView(mine[0]);
                if (entry == mine[0]) entry = null;
            }, caps);
            entry = mine[0];
            root.addView(entry, new android.widget.FrameLayout.LayoutParams(-1, -1));
        });
    }

    private boolean covering;   // a screen is being captured to open over this one

    /** The weather location screen (country and city), over the settings; the buttons close it. */
    void showPlace() {
        if (place != null || root == null || covering) return;
        covering = true;
        ScreenTransition.capture(this, caps -> {
            covering = false;
            if (place != null || root == null) return;
            place = new PlaceScreen(this, () -> {
                if (place != null) root.removeView(place);
                place = null;
                if (view != null) view.invalidate();
            }, caps);
            root.addView(place, new android.widget.FrameLayout.LayoutParams(-1, -1));
        });
    }

    void showPreview() {
        preview = DreamVideo.available(this) ? new DreamVideo(this) : new DreamView(this);
        setContentView((View) preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(root);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, REMOTE_KEYS = 3, PICKER = 4, WALLPAPERS = 6, GROUP = 7, AVATARS = 8, HIDDEN_APPS = 9, CREDITS = 10, ICON_EDIT = 11;
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private Paint headingP, hintP;
        private float listPaintsK;

        /** The title and the hint under a list (made once for the size of the screen, not on every frame). */
        private Paint headingPaint() {
            listPaints();
            return headingP;
        }

        private Paint hintPaint() {
            listPaints();
            return hintP;
        }

        private void listPaints() {
            if (headingP != null && listPaintsK == k) return;
            headingP = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            hintP = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            listPaintsK = k;
        }
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 74, ROW_GAP = 8, CHOICE_H = 104;
        private static final int AMBER = 0xFFFFC266, INK = Brand.INK;
        private static final int LILY = Brand.LILY, PEACH = Brand.PEACH, MELON = Brand.MELON;
        private Bitmap wave;          // the design's wave background (assets/wave.jpg), shared look with the home screen
        private boolean waveTried;
        private int waveIdx = -1;
        private final Rect waveDst = new Rect();

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[20];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;

        // The full screens that open over another one use the default transition (see SettingsActivity.screenForward / screenBack).
        private static boolean full(int s) {
            return s == PICKER || s == HIDDEN_APPS || s == HOME_CHOICE || s == CREDITS || s == ICON_EDIT;
        }

        private void setScreen(int next) {
            if (next == screen) return;
            profLevel = 0;
            clkOpen = false;
            Context ctx = getContext();
            SettingsActivity host = ctx instanceof SettingsActivity ? (SettingsActivity) ctx : null;
            final int to = next;
            if (host != null && !full(screen) && full(to)) host.screenForward(() -> screen = to);
            else if (host != null && full(screen) && !full(to)) host.screenBack(() -> screen = to);
            else screen = to;
        }
        private int group;   // which of the five groups a GROUP screen shows (1–5)
        private float rowR = -1;   // a fixed corner radius for the next row background (cards); -1 = a pill
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private int a11yKey = -1;   // which screen TalkBack was last told about
        private int introId = -1;   // the item that also says the screen's name, until a key is pressed
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint brand, title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !state.optString("busy").isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                // A switch ended with no ADB on the TV (plan B): open the system's accessibility screen so the person can turn Borealis on.
                if (wasBusy && !nowBusy && state.optBoolean("soft") && !freeActive() && !remoteButtonOn()) {
                    openAccessibilityScreen(getContext());
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, 500);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            for (int i = 0; i < gridPop.length; i++) gridPop[i] = new Spring(0.2f, 0.9f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription("Borealis");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) {
                    if (screen == WALLPAPERS) { wpItems(out); return; }
                    for (int i = 1; i <= rowCount(); i++) out.add(i);
                    if (screen == MAIN && hasProfile()) out.add(rowCount() + 1);   // the profile (top right): change the avatar
                    out.add(rowCount() + 2);   // the Back button
                }
                @Override public String text(int id) {
                    if (screen != WALLPAPERS && id == rowCount() + 2) return Ui.tr("Voltar", "Back", "Volver");
                    if (screen == MAIN && id == rowCount() + 1) {
                        return Ui.tr("Meu perfil", "My profile", "Mi perfil") + (Prefs.name().isEmpty() ? "" : ", " + Prefs.name()) + ". " + Ui.tr("Avatar e nome", "Avatar and name", "Avatar y nombre");
                    }
                    String t = screen == WALLPAPERS && id > rowCount() ? wpExtraText(id - rowCount())
                            : id >= 1 && id <= rowCount() ? rowText(id - 1) : "";
                    // The first item after a screen opens also says where we are ("Appearance. Wallpaper, …").
                    if (id == introId && !t.isEmpty()) t = a11yTitle() + ". " + t;
                    return t;
                }
                @Override public boolean bounds(int id, Rect out) {
                    if (screen != WALLPAPERS && id == rowCount() + 2) {
                        out.set(Math.round(BACK_X * k), Math.round(BACK_Y * k), Math.round((BACK_X + BACK_W) * k), Math.round((BACK_Y + BACK_H) * k));
                        return true;
                    }
                    if (screen == MAIN && id == rowCount() + 1) {
                        if (profileRect.isEmpty()) return false;
                        profileRect.round(out);
                        return true;
                    }
                    if (screen == WALLPAPERS && id > rowCount()) return wpExtraBounds(id - rowCount(), out);
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() {
                    if (focus == -2 && screen != WALLPAPERS) return rowCount() + 2;
                    if (screen == MAIN && focus == -1) return rowCount() + 1;
                    if (screen != WALLPAPERS) return focus + 1;
                    int n = rowCount();
                    if (wpOnBack) return n + 6;
                    if (wpRow == 1) return wpSaver ? n + 1 + Math.min(4, wpPill) : n + 5;
                    return focus + 1;
                }
                @Override public void focus(int id) {
                    if (screen == WALLPAPERS) {
                        int e = id - rowCount();
                        if (e <= 0) { wpSetRow(0); wpGo(id - 1); }
                        else if (e == 6) wpSetRow(-1);
                        else { wpSetRow(1); wpPill = wpSaver ? Math.min(4, e - 1) : 4; }
                    } else if (id == rowCount() + 2) {
                        focus = -2;
                    } else if (screen == MAIN && id == rowCount() + 1) {
                        focus = -1;
                    } else {
                        focus = id - 1;
                    }
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            spoken.setTitle(this::a11yScreen);
            return spoken;
        }

        /** The name of the screen the person is in (the card's name inside a card; never just "Borealis"). */
        private String a11yTitle() {
            switch (screen) {
                case MAIN: return Ui.tr("Borealis, configurações", "Borealis settings", "Borealis, configuración");
                case GROUP: return group >= 1 ? groupInfo()[group - 1][0] : "Borealis";
                case HOME_CHOICE: return "Launcher";
                case REMOTE_KEYS: return groupInfo()[3][0];
                case PICKER: return keyRawLabel(Math.max(0, Math.min(Prefs.KEYS.length - 1, pickFor - 1)));
                case AVATARS: return Ui.tr("Escolha seu avatar", "Choose your avatar", "Elige tu avatar");
                case HIDDEN_APPS: return Ui.tr("Apps ocultos", "Hidden apps", "Apps ocultas");
                case ICON_EDIT: return groupLabel(ID_ICONS);
                case CREDITS: return Ui.tr("Sobre o Borealis", "About Borealis", "Acerca de Borealis");
                case WALLPAPERS: return wpSaver ? groupLabel(ID_SAVER) : settingLabel(WALLPAPER_ROW);
                default: return "Borealis";
            }
        }

        /** Said when TalkBack rests on the whole screen instead of an item. */
        private String a11yScreen() {
            return a11yTitle() + ". " + (screen == WALLPAPERS
                    ? Ui.tr("Use as setas para os lados para trocar, e para baixo para os botões.",
                            "Use the left and right arrows to change, and down for the buttons.",
                            "Usa las flechas a los lados para cambiar y abajo para los botones.")
                    : Ui.tr("Use as setas para ver as opções.", "Use the arrows to see the options.", "Usa las flechas para ver las opciones."));
        }

        // The picker's own buttons for TalkBack: ids after the pictures. n+1..n+4 the times, n+5 "Preview" / "Hide elements", n+6 "Back".
        private void wpItems(List<Integer> out) {
            int n = rowCount();
            out.add(n + 6);
            for (int i = 1; i <= n; i++) out.add(i);
            if (wpSaver) for (int p = 1; p <= 4; p++) out.add(n + p);
            out.add(n + 5);
        }

        private String wpExtraText(int e) {
            switch (e) {
                case 6: return Ui.tr("Voltar", "Back", "Volver");
                case 5: return wpSaver ? Ui.tr("Ver agora", "Preview", "Ver ahora")
                        : wpClean.target > 0.5f ? Ui.tr("Mostrar elementos", "Show elements", "Mostrar elementos")
                        : Ui.tr("Ocultar elementos", "Hide elements", "Ocultar elementos");
                default: {
                    if (e < 1 || e > 4 || !wpSaver) return "";
                    boolean chosen = SAVER_MINUTES[e - 1] * 60_000L == saverTimeMs();
                    return Ui.tr("Ativar o protetor após ", "Start the screensaver after ", "Activar protector tras ") + SAVER_MINUTES[e - 1] + " min"
                            + (chosen ? Ui.tr(", escolhido", ", selected", ", seleccionado") : "") + ", " + e + Ui.tr(" de ", " of ") + 4;
                }
            }
        }

        private boolean wpExtraBounds(int e, Rect out) {
            if (w == 0 || e < 1 || e > 6) return false;
            float left = w / 2f - SMP_W * k / 2f, right = left + SMP_W * k, rowTop = ROW_TOP * k, bh = BTN_H * k;
            RectF r = focusBox;
            if (e == 6) {
                r.set(BACK_X * k, BACK_Y * k, (BACK_X + BACK_W) * k, (BACK_Y + BACK_H) * k);
            } else if (e == 5) {
                float bw = (wpSaver ? 200 : 290) * k, cx = wpSaver ? right - bw / 2 : w / 2f;
                r.set(cx - bw / 2, rowTop, cx + bw / 2, rowTop + bh);
            } else {
                if (!wpSaver) return false;
                wpPaints();
                String text = Ui.tr("Ativar o protetor após", "Start the screensaver after", "Activar protector tras");
                float x = left + wpHintStrong().measureText(text) + 30 * k + (e - 1) * (PILL_W + PILL_GAP) * k;
                r.set(x, rowTop, x + PILL_W * k, rowTop + PILL_H * k);
            }
            r.round(out);
            return out.width() > 0 && out.height() > 0;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case CREDITS: return creditsSpoken();
                case ICON_EDIT: return row >= 0 && row < 6 ? iconsCardText(row) : "";
                case AVATARS:
                    return row < 0 || row >= Avatars.IDS.length ? "" : Avatars.name(row)
                            + (Avatars.IDS[row].equals(Avatars.current()) ? ", " + Ui.tr("selecionado", "selected", "seleccionado") : "");
                case MAIN: {
                    String[][] info = groupInfo();
                    int[] order = groupOrder();
                    return row < order.length ? info[order[row] - 1][0] + ", " + info[order[row] - 1][1] : supportTitle() + ", " + supportDesc();
                }
                case GROUP: {
                    int[] ids = groupIds();
                    if (row >= ids.length) return "";
                    if (ids[row] == ID_LAUNCHER) {
                        String busyText = state.optString("busy");
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText)
                                : homeName();
                        return groupLabel(ids[row]) + ", " + value;
                    }
                    return groupLabel(ids[row]) + (groupValue(ids[row]).isEmpty() ? "" : ", " + groupValue(ids[row]));
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Borealis, " + Ui.tr("sem anúncios, com a tela de descanso nova", "no ads, with the new screensaver")
                            : "Original, " + Ui.tr("o Início de fábrica da TV", "the TV's factory home screen");
                    boolean inUse = (row == 0) == homeIsBorealis();
                    return name + (inUse ? Ui.tr(". Em uso", ". In use") : "") + ", " + (row + 1) + Ui.tr(" de 2", " of 2");
                }
                case REMOTE_KEYS: {
                    String[] labels = keyLabels(), values = keyValues();
                    if (row == Prefs.KEYS.length) return groupLabel(ID_REMOTE) + ", " + groupValue(ID_REMOTE);
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", escolhido", ", selected") : "")
                            + ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size();
                }
                case WALLPAPERS: {
                    if (row >= wpNames().length) return "";
                    return wpNames()[row] + (row == wpCurrent() ? Ui.tr(", em uso", ", in use", ", en uso") : "")
                            + ", " + (row + 1) + Ui.tr(" de ", " of ") + wpNames().length;
                }
                case HIDDEN_APPS:
                    return row >= 0 && row < hidItems.size() ? hidItems.get(row).label + ". "
                            + Ui.tr("Aperte OK para mostrar de novo.", "Press OK to show it again.", "Presiona OK para mostrarla de nuevo.") : "";
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (twoCol() && w > 0) {
                if (screen == MAIN) {
                    top = cardTop(row);
                    focusBox.set(C_L * k, top * k, (C_L + C_W) * k, (top + cardH(row)) * k);
                } else {
                    float t = rowTopAt(shownGroup(), row) - panelScroll.value;
                    focusBox.set((P_L + 27) * k, t * k, (P_L + P_W - 27) * k, (t + rowH(shownGroup())) * k);
                }
                return focusBox;
            }
            if (screen == ICON_EDIT) {
                iconsCard(Math.max(0, Math.min(5, row)), focusBox);
                return focusBox;
            }
            if (screen == CREDITS) {
                focusBox.set(0, 0, w, h);
                return focusBox;
            }
            if (screen == HIDDEN_APPS && !hidItems.isEmpty()) {
                hidCell(Math.max(0, Math.min(row, hidItems.size() - 1)), focusBox);
                return focusBox;
            }
            if (screen == AVATARS) {
                avatarCell(Math.max(0, Math.min(row, Avatars.IDS.length - 1)), focusBox);
                return focusBox;
            }
            if (screen == WALLPAPERS) {
                wpItemRect(Math.max(0, Math.min(row, wpFiles().length - 1)), focusBox);
                return focusBox;
            }
            if (screen == MAIN) {
                top = LIST_TOP + 20 + row * (CARD_H + CARD_GAP);
                height = CARD_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }


        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
            if (screen == WALLPAPERS && wpSaver) scheduleLive();
        }

        void pause() {
            running = false;
            if (click != null) {
                click.release();
                click = null;
            }
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
            handler.removeCallbacks(liveStart);
            stopLive();
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy() || !blinkSpring.settled() || screen == CREDITS || (screen == ICON_EDIT && SystemClock.uptimeMillis() - icoT0 < 320);
            for (Spring s : pop) moving |= !s.settled();
            moving |= !wpBackPop.settled() || !wpIn.settled() || !profilePop.settled() || !panelScroll.settled() || profAnimating() || (clkT0 > 0 && SystemClock.uptimeMillis() - clkT0 < 400);
            if (screen == WALLPAPERS) moving |= !wpClean.settled() || !wpScroll.settled() || !wpBackPop.settled() || !wpCarPop.settled()
                    || !wpPillPop[0].settled() || !wpPillPop[1].settled() || !wpPillPop[2].settled() || !wpPillPop[3].settled() || !wpPillPop[4].settled() || wpFading() || (live != null && live.getAlpha() < 1f)
                    || (!wpSaver && SystemClock.uptimeMillis() - wpZoomAt >= 40);
            if (moving || (wave == null && SystemClock.uptimeMillis() - lastSky >= 100)) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            brand = Ui.text(Logo.wordmark(getContext()), 76 * k, 0xFFFFFFFF);
            brand.setTextAlign(Paint.Align.CENTER);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            veilPaint.setColor(Brand.ink(0x66));
            pillPaint.setColor(0x1AFFFFFF);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            // The two-column layout (design board x 1.5).
            pTitle = Ui.text(Logo.wordmark(getContext()), 66 * k, 0xFFFFFFFF);
            pPanelTitle = Ui.text(Logo.wordmark(getContext()), 45 * k, 0xFFFFFFFF);
            pLabel = Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF);
            pLabelB = Ui.text(Ui.bold, 27 * k, 0xFFFFFFFF);
            pValue = Ui.text(Ui.medium, 25.5f * k, 0xBDFFFFFF);
            pValue.setTextAlign(Paint.Align.RIGHT);
            pCardT = Ui.text(Ui.semibold, 28.5f * k, 0xFFFFFFFF);
            pCardTB = Ui.text(Ui.bold, 28.5f * k, 0xFFFFFFFF);
            pCardS = Ui.text(Ui.medium, 19.5f * k, 0xBDFFFFFF);
            pLeftT = Ui.text(Ui.semibold, 25.5f * k, 0xFFFFFFFF);
            pLeftTB = Ui.text(Ui.bold, 25.5f * k, 0xFFFFFFFF);
            pLeftS = Ui.text(Ui.medium, 18 * k, 0xBDFFFFFF);
            pProfT = Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF);
            pProfTB = Ui.text(Ui.bold, 27 * k, 0xFFFFFFFF);
            pFoot = Ui.text(Ui.regular, 20 * k, 0x80FFFFFF);
            pPanel = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        private Paint pLeftT, pLeftTB, pLeftS, pProfT, pProfTB;
        private Paint pTitle, pPanelTitle, pLabel, pLabelB, pValue, pCardT, pCardTB, pCardS, pFoot, pPanel;

        private boolean busy() {
            return !state.optString("busy").isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        /** Borealis is the home screen: through ADB (Borealis mode) or, with no ADB, through the accessibility service (soft Home). */
        private boolean homeIsBorealis() {
            return freeActive() || (state.optBoolean("soft") && remoteButtonOn());
        }

        private String homeName() {
            if (freeActive()) return "Borealis";
            return homeIsBorealis() ? "Borealis (" + Ui.tr("Início suave", "soft Home", "Inicio suave") + ")" : Ui.tr("Original", "Original");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 6;
                case GROUP: return groupIds().length;
                case HOME_CHOICE: return 2;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case AVATARS: return Avatars.IDS.length;
                case CREDITS: return 1;
                case ICON_EDIT: return 6;
                case HIDDEN_APPS: return hidItems.size();
                case WALLPAPERS: return wpFiles().length;
                case REMOTE_KEYS: return Prefs.KEYS.length + 1;   // the four buttons and the remote's settings button
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            int key = screen * 1000 + Math.max(0, group) * 100 + (wpSaver ? 10 : 0) + (screen == PICKER ? pickFor : 0);
            if (key != a11yKey) {   // a new screen: TalkBack goes to its first item and says where it is, instead of "Borealis"
                boolean first = a11yKey < 0;
                a11yKey = key;
                introId = focus + 1;
                if (!first) postDelayed(spoken::refocus, 300);
            }
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() && !(screen == WALLPAPERS && wpOnBack) ? 1 : 0;
                pop[i].step(dt);
            }
            profilePop.target = screen == MAIN && focus == -1 ? 1 : 0;
            wpBackPop.target = focus == -2 && screen != WALLPAPERS ? 1 : 0;
            if (screen != WALLPAPERS) wpBackPop.step(dt);
            wpIn.step(dt);
            profilePop.step(dt);
            boolean inPanel = screen == MAIN && focus == -1;
            fAv.target = inPanel && profLevel == 1 ? 1 : 0;
            fName.target = inPanel && profLevel == 2 ? 1 : 0;
            fAv.step(dt);
            fName.step(dt);
            for (int i = 0; i < gridPop.length; i++) {
                gridPop[i].target = inPanel && profLevel == 3 && i == gridIdx ? 1 : 0;
                gridPop[i].step(dt);
            }
            panelScroll.step(dt);
            blinkSpring.step(dt);
            if (screen == HIDDEN_APPS) {
                for (int i = 0; i < hidPop.length; i++) {
                    hidPop[i].target = focus == i ? 1 : 0;
                    hidPop[i].step(dt);
                }
            }
            if (screen == WALLPAPERS) {
                if (wpHold) {
                    if (wpSnap != null && !wpReady() && SystemClock.uptimeMillis() - wpHoldAt < 500) {
                        c.drawBitmap(wpSnap, 0, 0, null);   // the old screen, still, while the new one loads
                        invalidate();
                        return;
                    }
                    wpRelease();
                    wpIn.step(dt);
                }
                float in = Ui.clamp(wpIn.value);
                if (in >= 0.995f) {
                    dropSnapshot();
                    drawWallpapers(c, dt);
                } else {   // coming in: the old screen dissolves into the new one, once, all together
                    if (wpSnap != null) c.drawBitmap(wpSnap, 0, 0, null); else drawBackground(c);
                    int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * in));
                    drawWallpapers(c, dt);
                    c.restoreToCount(layer);
                }
                return;
            }
            if (wpSnap != null) dropSnapshot();
            if (screen == ICON_EDIT) {   // the icons and layout editor over the blurred Settings
                drawIcons(c);
                return;
            }
            if (screen == CREDITS) {   // the rolling credits over the blurred Settings
                drawBackground(c);
                drawCredits(c, dt, (SystemClock.uptimeMillis() - startedAt) / 1000f);
                return;
            }
            if (screen == HIDDEN_APPS) {   // like the wallpaper picker: its blurred backdrop, a centered title, the Back button
                drawBackground(c);
                drawHiddenApps(c);
                drawBackButton(c);
                return;
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            drawBackground(c);
            if (twoCol()) {
                drawTwoColumns(c, seconds);
                return;
            }
            drawProfile(c);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            Logo.drawVertical(c, getContext(), cx, 210 * k, 232 * k, -1, false, 1f);   // the vertical logo; the name ends where the old title did
            c.drawText(Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 704 * k, version);
            c.drawText(Ui.tr("Desenhado e desenvolvido por Alogus Tov", "Designed and developed by Alogus Tov", "Diseñado y desarrollado por Alogus Tov"), cx, 742 * k, version);
            c.drawText("Powered by Free My TV", cx, 774 * k, version);
            c.drawText(Ui.tr("Usa a API do TMDB, sem apoio nem certificação do TMDB", "Uses the TMDB API; not endorsed or certified by TMDB", "Usa la API de TMDB; sin respaldo ni certificación de TMDB"), cx, 806 * k, version);

            if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == AVATARS) drawAvatarGrid(c); else drawList(c);
            drawBackButton(c);
        }

        /**
         * The chosen wallpaper, tiny and blurred, at 80% over the night black: the same backdrop as the wallpaper picker.
         * A new wallpaper cross-fades in over the one before it.
         */
        boolean coverBg;   // the default transition draws the background behind this view (the screen below, blurred)

        private void drawBackground(Canvas c) {
            if (coverBg && screen != WALLPAPERS) return;
            c.drawColor(Brand.INK);
            final int wallpaper = Prefs.wallpaper();
            if (bgSoft == null && softCache != null && softCacheIdx == wallpaper) {   // ready from before: no pop-in at the first frame
                bgSoft = softCache;
                bgIdx = wallpaper;
                bgAt = 0;
            }
            if (wallpaper != bgIdx && wallpaper != bgLoading) {
                bgLoading = wallpaper;
                new Thread(() -> {
                    Bitmap pic = decodeWallpaper(Prefs.WALLPAPER_FILES[wallpaper], 4, 0);
                    final Bitmap soft = pic == null ? null : softBackdrop(pic);
                    if (pic != null) pic.recycle();
                    handler.post(() -> {
                        if (soft != null) {
                            softCache = soft;
                            softCacheIdx = wallpaper;
                        }
                        bgPrev = bgSoft;
                        bgSoft = soft;
                        bgIdx = wallpaper;
                        bgLoading = -1;
                        bgAt = SystemClock.uptimeMillis();
                        invalidate();
                    });
                }).start();
            }
            float e = Ui.clamp((SystemClock.uptimeMillis() - bgAt) / 300f);
            waveDst.set(0, 0, w, h);
            if (bgPrev != null && e < 1f) {
                bgPaint.setAlpha(Math.round(255 * BACKDROP_ALPHA));
                c.drawBitmap(bgPrev, null, waveDst, bgPaint);
            }
            if (bgSoft != null) {
                bgPaint.setAlpha(Math.round(255 * BACKDROP_ALPHA * (bgPrev != null ? e : 1f)));
                c.drawBitmap(bgSoft, null, waveDst, bgPaint);
            }
            if (e < 1f) invalidate();
        }

        private Bitmap bgSoft, bgPrev;
        private int bgIdx = -1, bgLoading = -1;
        private long bgAt;
        private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        private final Paint wavePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint veilPaint = new Paint();
        private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmp2 = new RectF();

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y, boolean credit) {
            float x = secX;
            header.setColor(PEACH);
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            header.setColor(0x73FFFFFF);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("Sem chave do TMDB: veja o README (config.properties).", "No TMDB key: see the README (config.properties).");
            else if (!error.isEmpty()) line = Ui.tr("Erro: ", "Error: ") + error + ".";
            else if (count > 0) line = Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                    "On · " + count + " titles from the TV's apps, 3 new ones every hour.");
            else line = Ui.tr("Buscando os títulos…", "Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, secR - secX, TextUtils.TruncateAt.END).toString(), x, y + 34 * k, notice);
            if (!credit) return y + 34 * k;   // the TMDB credit lives in "Acerca de Borealis"
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 64 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            return y + 64 * k;
        }

        /** List row: a soft translucent pill; focused, night-black with the lily → peach → melon ring (as the home's pills). Returns how focused it is. */
        /** The blink when something is clicked: the focused row or button fills with the gradient for a moment. */
        private final Spring blinkSpring = new Spring(0.4f, 1f, 0);

        private void blink() {
            blinkSpring.snap(1);
            blinkSpring.target = 0;
            invalidate();
        }

        private void blinkFill(Canvas c, RectF box, float radius, float focus) {
            float a = Ui.clamp(blinkSpring.value) * Ui.clamp((focus - 0.5f) * 2);
            if (a <= 0.01f) return;
            wpFill.setStyle(Paint.Style.FILL);
            wpFill.setColor(0xFFFFFFFF);
            wpFill.setShader(new LinearGradient(box.left, 0, box.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
            wpFill.setAlpha(Math.round(255 * a));
            c.drawRoundRect(box, radius, radius, wpFill);
            wpFill.setShader(null);
            wpFill.setAlpha(255);
        }

        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            float r0 = rowR >= 0 ? rowR : rowRect.height() / 2;
            if (f <= 0.01f) {
                c.drawRoundRect(rowRect, r0, r0, pillPaint);
                return f;
            }
            float s = 1 + 0.02f * f;
            float cx = rowRect.centerX(), cy = rowRect.centerY();
            tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
            float r = rowR >= 0 ? rowR * s : tmp.height() / 2;
            tmp.offset(0, 8 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.35f * f));
            c.drawRoundRect(tmp, r, r, shadow);
            tmp.offset(0, -8 * k * f);
            c.drawRoundRect(tmp, r, r, pillPaint);   // the light pill stays underneath while the dark one comes in: no gap, no blink
            white.setColor(INK);
            white.setAlpha(Math.round(255 * f));
            c.drawRoundRect(tmp, r, r, white);
            blinkFill(c, tmp, r, f);
            float ring = 3.5f * k, half = ring / 2;
            tmp2.set(tmp.left + half, tmp.top + half, tmp.right - half, tmp.bottom - half);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(ring);
            ringPaint.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            ringPaint.setAlpha(Math.round(255 * Ui.clamp(f * 2)));
            c.drawRoundRect(tmp2, r - half, r - half, ringPaint);
            ringPaint.setShader(null);
            rowRect.set(tmp);
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = secX, colA = secR - 190 * k, colB = secR;
            header.setColor(LILY);
            c.drawText(Ui.tr("COMPARAÇÃO", "COMPARISON"), x, y, header);
            header.setColor(0x73FFFFFF);
            cellValue.setColor(0x73FFFFFF);
            c.drawText("Original", colA, y, cellValue);
            c.drawText("Borealis", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Botão Início", "Home button"), "homeMs"}, {Ui.tr("Memória livre", "Free memory"), "memAvailableMB"},
                    {Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (44 + 36 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (44 + 36 * (rows.length - 1)) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = secX;
            header.setColor(MELON);
            c.drawText(Ui.tr("AVISOS", "NOTICES"), x, y, header);
            header.setColor(0x73FFFFFF);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, secR - secX, TextUtils.TruncateAt.END).toString(), x, y + (34 + 32 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("Nenhum ainda.", "None yet."), x, y + 34 * k, notice);
        }

        /** Home screen choice: Borealis or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = headingPaint();
            c.drawText(Ui.tr("Início", "Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Borealis", Ui.tr("Sem anúncios, com a tela de descanso nova", "No ads, with the new screensaver")},
                    {"Original", Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
            int current = homeIsBorealis() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, 0xFFFFFFFF, f));
                rowSub.setColor(blend(0xB3FFFFFF, 0xE6FFFFFF, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, 0xFFFFFFFF, f));
                }
            }
            Paint hint = hintPaint();
            c.drawText(Ui.tr("A troca leva uns segundos e a tela pisca nas medições.",
                    "Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return v + " ms";
            if (key.equals("memAvailableMB") && v >= 1024) return String.format(Ui.locale(), "%.2f GB", v / 1024.0);
            return v + " MB";
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("agora", "now");
            if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
            if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
            return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
        }

        private static String versionName(Context c) {
            try {
                return "Borealis Beta " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Borealis";
            }
        }

        private Click click;

        private Click click() {
            if (click == null) click = new Click(getContext());
            return click;
        }

        private long moveSig() {
            return screen * 1_000_003L + focus * 131L + wpRow * 17L + wpPill * 7L + Math.round(wpScroll.target) * 1009L + (wpOnBack ? 1 : 0) + pickScroll * 53L + group * 3L;
        }

        /** The keys, with the keyboard-click sounds: a light one whenever something moves or changes, a firmer one on OK. */
        boolean onKey(KeyEvent e) {
            long before = moveSig();
            boolean handled = onKey0(e);
            if (e.getAction() == KeyEvent.ACTION_DOWN && handled) {
                int code = e.getKeyCode();
                boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
                boolean side = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
                boolean ownSound = screen == GROUP && group == 1 && clkOpen;   // the sound picker plays its own sounds
                if (!ok && !ownSound && (before != moveSig() || (side && screen == GROUP))) click().move();
            }
            return handled;
        }

        private boolean onKey0(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (down) introId = -1;
            if (screen == WALLPAPERS && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return wallpaperKey(e, down);
            if (screen == AVATARS && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return avatarKey(e, down);
            if (screen == MAIN && focus == -1 && profLevel > 0) return profileKey(e, down);
            if (screen == CREDITS && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return creditsKey(e, down);
            if (screen == ICON_EDIT && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return iconsKey(e, down);
            if (screen == GROUP && group == 1 && clkOpen) return clicksKey(e, down);
            if (screen == HIDDEN_APPS && e.getKeyCode() != KeyEvent.KEYCODE_BACK) return hiddenKey(e, down);
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus == -1) focus = rowCount() - 1;   // the profile: up goes round to the last card
                    else if (down && focus == -2) { /* the Back button is the top */ }
                    else if (down && screen == MAIN && focus == 0 && hasProfile()) focus = -1;   // the first card: up to "Mi perfil"
                    else if (down && screen == MAIN && focus == 0) focus = rowCount() - 1;   // no profile: round to the last
                    else if (down && focus == 0 && (!scrolling() || pickScroll == 0)) focus = -2;   // the first row: up to the Back button
                    else if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < 0) focus = 0;   // from the profile or the Back button: into the list
                    else if (down && screen == MAIN && focus == rowCount() - 1) focus = -1;   // the last card: round to "Mi perfil"
                    else if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == MAIN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                        // the cards are the first column: nothing to their left (Back leaves)
                    } else if (down && screen == MAIN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT && focus >= 0 && focus != SUPPORT) {
                        activate();   // right: into the group's panel
                    } else if (down && (screen == GROUP || screen == REMOTE_KEYS) && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                        goBack();   // left: back to the cards
                    } else if (down && screen == MAIN && focus == -1) {
                        enterProfile();   // right: into the profile's panel
                    } else if (down && focus < 0) {
                        // chrome: nothing to adjust
                    } else if (down && screen == GROUP) {
                        int id = groupIds()[focus];
                        if ((id < 100 && id != WALLPAPER_ROW) || id == ID_SPLASH || id == ID_SOUND || id == ID_UPDATE_AUTO) {
                            adjustSetting(id, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                            invalidate();
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) goBack();
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }


        /** One step back (the BACK key and the "Back" button): up to the first screen, and from there out to the home. */
        private void goBack() {
            if (screen == MAIN) {
                ((android.app.Activity) getContext()).finish();
                return;
            }
            for (Spring sp : pop) sp.snap(0);   // the old row's focus must not carry over to a card
            if (screen == PICKER) {
                focus = keyPos(pickFor - 1);
                setScreen(REMOTE_KEYS);
            } else if (screen == AVATARS) {
                setScreen(MAIN);
                focus = -1;   // back on the profile
            } else if (screen == WALLPAPERS) {
                closeWallpapers();
            } else if (screen == ICON_EDIT) {   // back to the row; what was not applied is dropped
                group = 2;
                setScreen(GROUP);
                focus = groupIndexOf(2, ID_ICONS);
            } else if (screen == CREDITS) {
                group = 5;
                setScreen(GROUP);
                focus = groupIndexOf(5, ID_ABOUT);
            } else if (screen == HIDDEN_APPS) {
                if (hidFromHome) {   // opened from the card in All Apps: back out to the home screen
                    ((android.app.Activity) getContext()).finish();
                    return;
                }
                group = 2;
                setScreen(GROUP);
                focus = groupIndexOf(2, ID_ICONS);
            } else if (screen == GROUP) {
                focus = groupPos(group);
                setScreen(MAIN);
            } else if (screen == HOME_CHOICE) {
                group = 5;
                setScreen(GROUP);
                focus = 0;
            } else {
                focus = screen == REMOTE_KEYS ? groupPos(4) : 0;
                setScreen(MAIN);
            }
            invalidate();
            spoken.focusMoved();
        }

        // MARK: - The five groups

        /** What each group is called and says about itself (the cards of the first screen). */
        private String[][] groupInfo() {
            return new String[][] {
                {Ui.tr("Aparência", "Appearance", "Apariencia"),
                        Ui.tr("Papel de parede e protetor de tela", "Wallpaper and screensaver", "Fondo de pantalla y protector")},
                {Ui.tr("Tela principal", "Home screen", "Pantalla principal"),
                        Ui.tr("Cartões, ícones e widgets", "Cards, icons and widgets", "Tarjetas, iconos y widgets")},
                {Ui.tr("Preferências", "Preferences", "Preferencias"),
                        Ui.tr("Idioma, temperatura e hora", "Language, temperature and time", "Idioma, temperatura y hora")},
                {Ui.tr("Controle remoto", "Remote control", "Control remoto"),
                        Ui.tr("O que abre cada botão", "What each button opens", "Qué abre cada botón")},
                {Ui.tr("Sistema", "System", "Sistema"),
                        Ui.tr("Launcher, TV e estado", "Launcher, TV and status", "Launcher, TV y estado")}};
        }

        /** Alphabetical order in the language in use. */
        private java.text.Collator collator() {
            return java.text.Collator.getInstance(Ui.locale());
        }

        /** The groups (1–5) as the design orders the cards: Appearance, Home screen, Preferences, Remote, System. */
        private int[] groupOrder() {
            return new int[] {1, 2, 3, 4, 5};
        }

        private int groupPos(int g) {
            int[] o = groupOrder();
            for (int i = 0; i < o.length; i++) if (o[i] == g) return i;
            return 0;
        }

        /** The rows of the open group: ids of the old Home Settings rows, plus the ID_* ones. */
        private int[] groupIds() {
            switch (group) {
                case 1: return new int[] {WALLPAPER_ROW, ID_SAVER, ID_SPLASH, ID_SOUND, ID_CLICKS};
                case 2: return new int[] {5, 6, ID_ICONS, 7, ID_PLACE};
                case 3: return new int[] {0, 1, 3, ID_WELCOME, ID_TOUR};
                default: return new int[] {ID_LAUNCHER, ID_TV, ID_UPDATE, ID_UPDATE_AUTO, ID_ABOUT};
            }
        }

        private int groupIndexOf(int g, int id) {
            int was = group;
            group = g;
            int[] ids = groupIds();
            group = was;
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) return i;
            return 0;
        }

        private String iconsValue() {
            int c = Prefs.iconCols(), r = c == 6 ? 4 : c == 10 ? 6 : 5, sh = Prefs.iconShape();
            String name = sh == 1 ? Ui.tr("Quadrada", "Square", "Cuadrada") : sh == 2 ? Ui.tr("Circular", "Circular", "Circular")
                    : Ui.tr("Retangular", "Rectangular", "Rectangular");
            return c + " × " + r + " · " + name;
        }

        private String groupLabel(int id) {
            switch (id) {
                case ID_SAVER: return Ui.tr("Protetor de tela", "Screensaver", "Protector de pantalla");
                case ID_ICONS: return Ui.tr("Ícones e distribuição dos apps", "Icons and layout of the apps", "Iconos y distribución de las apps");
                case ID_HIDDEN: return Ui.tr("Apps ocultos", "Hidden apps", "Apps ocultas");
                case ID_LAUNCHER: return "Launcher";
                case ID_SPLASH: return Ui.tr("Logo do launcher ao ligar a TV", "Launcher logo when the TV turns on", "Logo del launcher al encender la TV");
                case ID_SOUND: return Ui.tr("Som de abertura", "Startup sound", "Sonido de inicio");
                case ID_CLICKS: return Ui.tr("Cliques", "Clicks", "Clics");
                case ID_PLACE: return Ui.tr("Localização do clima", "Weather location", "Ubicación del clima");
                case ID_TV: return Ui.tr("Ajustes da TV", "TV Settings", "Ajustes de la TV");
                case ID_REMOTE: return Ui.tr("Botão de ajustes do controle", "Remote's settings button", "Botón de ajustes del control");
                case ID_WELCOME: return Ui.tr("Repetir as boas-vindas", "Repeat the welcome", "Repetir bienvenida");
                case ID_TOUR: return Ui.tr("Conheça o Borealis", "Get to know Borealis", "Conoce Borealis");
                case ID_UPDATE: return Ui.tr("Atualizações", "Updates", "Actualizaciones");
                case ID_UPDATE_AUTO: return Ui.tr("Buscar atualizações sozinho", "Check for updates by itself", "Buscar actualizaciones solo");
                case ID_ABOUT: return Ui.tr("Sobre o Borealis", "About Borealis", "Acerca de Borealis");
                default: return settingLabel(id);
            }
        }

        private String groupValue(int id) {
            switch (id) {
                case ID_SAVER: {
                    int at = Prefs.screensaver();
                    return at >= 0 && at < Prefs.SAVER_NAMES.length ? Prefs.SAVER_NAMES[at] : "";
                }
                case ID_SPLASH: return onOff(Prefs.splash());
                case ID_SOUND: return onOff(Prefs.startupSound());
                case ID_CLICKS: return clicksValue();
                case ID_PLACE: return Prefs.city().isEmpty() ? Ui.tr("Escolher", "Choose", "Elegir") : Prefs.city();
                case ID_REMOTE: return onOff(remoteButtonOn());
                case ID_ICONS: return iconsValue();
                case ID_TV: return "";
                case ID_WELCOME: return Ui.tr("Configure a sua TV de novo", "Set up your TV again", "Configura tu TV de nuevo");
                case ID_TOUR: return Ui.tr("Veja o que você pode fazer", "See what you can do", "Mira lo que puedes hacer");
                case ID_ABOUT: return Ui.tr("Créditos e versão", "Credits and version", "Créditos y versión");
                case ID_HIDDEN: { int n = Prefs.hiddenApps().size(); return n == 0 ? "" : String.valueOf(n); }
                case ID_UPDATE: {
                    String newer = Update.newer(getContext());
                    return newer.isEmpty() ? Ui.tr("Em dia · ", "Up to date · ", "Al día · ") + Update.installed(getContext())
                            : Ui.tr("Nova versão ", "New version ", "Nueva versión ") + newer;
                }
                case ID_UPDATE_AUTO: return onOff(Prefs.updateAuto());
                case ID_LAUNCHER: return homeName();
                default: return settingValue(id);
            }
        }

        /** True when the accessibility service that sees the remote's buttons (the settings button, the soft transitions) is on. */
        private boolean remoteButtonOn() {
            try {
                String flat = Settings.Secure.getString(getContext().getContentResolver(), "enabled_accessibility_services");
                return flat != null && flat.contains(getContext().getPackageName() + "/");
            } catch (RuntimeException e) {
                return false;
            }
        }

        /**
         * Off: first through the TV's own adb (no computer needed); if it is still off after 9 s, the system's accessibility
         * screen opens. On: that screen opens, to change it. It is never turned on without the person asking for it here.
         */
        private void askRemoteButton() {
            final Context ctx = getContext();
            if (remoteButtonOn()) {
                openAccessibilityScreen(ctx);
                return;
            }
            HomeMode.wantRemoteService(ctx);
            postDelayed(() -> {
                if (!remoteButtonOn() && isAttachedToWindow()) openAccessibilityScreen(ctx);
                invalidate();
            }, 9000);
        }

        private static void openAccessibilityScreen(Context ctx) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }

        /** Rows that open another screen (a chevron at the right) instead of changing a value in place. */
        private static boolean opensScreen(int id) {
            return id == WALLPAPER_ROW || id == ID_SAVER || id == ID_ICONS || id == ID_HIDDEN || id == ID_LAUNCHER || id == ID_TV || id == ID_WELCOME || id == ID_TOUR;
        }

        private Paint profileText;

        private final Spring profilePop = new Spring(0.28f, 0.85f, 0);
        private final RectF profileRect = new RectF();
        private final android.graphics.Path pencilPath = new android.graphics.Path();

        private boolean hasProfile() {
            if (twoCol()) return screen == MAIN;   // "Mi perfil": the first card of the first screen
            return !Avatars.current().isEmpty() || !Prefs.name().isEmpty();
        }

        /** Top left: the profile's avatar and name (when there are any). On the first screen it can be reached (up from the first card) and its pencil opens the avatars. */
        private void drawProfile(Canvas c) {
            String avatar = Avatars.current(), name = twoCol() ? "" : Prefs.name();
            if (avatar.isEmpty() && name.isEmpty()) {
                profileRect.setEmpty();
                return;
            }
            if (profileText == null) {
                profileText = Ui.text(Ui.medium, 28 * k, 0xE6FFFFFF);
            }
            boolean two = twoCol();
            float r = (two ? 30 : 34) * k, cy = two ? 100 * k : (LIST_TOP - 24 - 12) * k;   // the same level as the screen's title
            float textW = name.isEmpty() ? 0 : profileText.measureText(name);
            float inner = (avatar.isEmpty() ? 0 : 2 * r + (name.isEmpty() ? 0 : 20 * k)) + textW;
            float x = two ? (C_L + C_W) * k - inner : LIST_R * k - 26 * k - inner;   // right-aligned with the cards, above them
            float cx = x + r;
            float textX = avatar.isEmpty() ? x : x + 2 * r + 20 * k;
            float endX = textX + textW;
            profileRect.set(x - 16 * k, cy - r - 12 * k, endX + 26 * k, cy + r + 12 * k);
            if (two) profileRect.set(cx - r - 8 * k, cy - r - 8 * k, cx + r + 8 * k, cy + r + 8 * k);
            boolean editable = screen == MAIN && !avatar.isEmpty();
            float f = editable ? Ui.clamp(profilePop.value) : 0;
            if (f > 0.01f && !two) {   // the focus: a dark pill with the gradient ring
                float rr = profileRect.height() / 2;
                white.setColor(INK);
                white.setAlpha(Math.round(220 * f));
                c.drawRoundRect(profileRect, rr, rr, white);
                float ring = 3.5f * k, half = ring / 2;
                tmp2.set(profileRect.left + half, profileRect.top + half, profileRect.right - half, profileRect.bottom - half);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                ringPaint.setShader(new LinearGradient(profileRect.left, 0, profileRect.right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                ringPaint.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp2, rr - half, rr - half, ringPaint);
                ringPaint.setShader(null);
                ringPaint.setColor(0xFFFFFFFF);
            }
            if (!avatar.isEmpty()) Avatars.draw(c, getContext(), avatar, cx, cy, r, 1f);
            if (f > 0.01f && two) avatarRing(c, cx, cy, r, 6 * k, f);   // the focus: the gradient ring flush on the round avatar, no background
            if (!name.isEmpty()) c.drawText(name, textX, cy + Ui.halfCap(profileText), profileText);
            if (editable) drawPencilBadge(c, cx + r * 0.72f, cy + r * 0.72f, 15 * k);
        }

        /** The edit mark: a gradient circle with a dark outline and a pencil (the same as the welcome's avatar). */
        private void drawPencilBadge(Canvas c, float bx, float by, float br) {
            tmp.set(bx - br, by - br, bx + br, by + br);
            white.setShader(new LinearGradient(tmp.left, 0, tmp.right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            white.setColor(0xFFFFFFFF);
            c.drawCircle(bx, by, br, white);
            white.setShader(null);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(2.5f * k);
            ringPaint.setColor(INK);
            c.drawCircle(bx, by, br, ringPaint);
            float s = br * 1.15f;
            pencilPath.rewind();
            pencilPath.moveTo(-0.13f * s, -0.42f * s);
            pencilPath.lineTo(0.13f * s, -0.42f * s);
            pencilPath.lineTo(0.13f * s, 0.14f * s);
            pencilPath.lineTo(0f, 0.44f * s);
            pencilPath.lineTo(-0.13f * s, 0.14f * s);
            pencilPath.close();
            c.save();
            c.translate(bx, by);
            c.rotate(45);
            white.setColor(INK);
            c.drawPath(pencilPath, white);
            c.restore();
            ringPaint.setColor(0xFFFFFFFF);
            white.setColor(0xFFFFFFFF);
        }

        // MARK: - Avatars (opened from the profile)

        private static final int AV_COLS = 5;

        private void avatarCell(int i, RectF out) {
            float d = 132, gap = 22, x = LIST_X + 24 + (i % AV_COLS) * (d + gap), y = LIST_TOP + 40 + (i / AV_COLS) * (d + gap);
            out.set(x * k, y * k, (x + d) * k, (y + d) * k);
        }

        private boolean avatarKey(KeyEvent e, boolean down) {
            int n = Avatars.IDS.length;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down && focus >= 0 && focus % AV_COLS > 0) focus--;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && focus >= 0 && focus % AV_COLS < AV_COLS - 1 && focus + 1 < n) focus++;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus >= AV_COLS) focus -= AV_COLS;
                    else if (down && focus >= 0) focus = -2;   // the first row: up to the Back button
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < 0) focus = 0;
                    else if (down) {
                        if (focus + AV_COLS < n) focus += AV_COLS;
                        else if (focus / AV_COLS < (n - 1) / AV_COLS) focus = n - 1;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                default:
                    return false;
            }
            invalidate();
            spoken.focusMoved();
            return true;
        }

        private void avatarRing(Canvas c, float cx, float cy, float radius, float width, float alpha) {
            float half = width / 2;
            tmp2.set(cx - radius + half, cy - radius + half, cx + radius - half, cy + radius - half);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(width);
            ringPaint.setShader(new LinearGradient(cx - radius, 0, cx + radius, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            ringPaint.setAlpha(Math.round(255 * alpha));
            c.drawOval(tmp2, ringPaint);
            ringPaint.setShader(null);
            ringPaint.setColor(0xFFFFFFFF);
        }

        private void drawAvatarGrid(Canvas c) {
            Paint heading = headingPaint();
            c.drawText(Ui.tr("Escolha seu avatar", "Choose your avatar", "Elige tu avatar"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String cur = Avatars.current();
            for (int i = 0; i < Avatars.IDS.length; i++) {
                avatarCell(i, rowRect);
                float f = Ui.clamp(pop[i].value), cx = rowRect.centerX(), cy = rowRect.centerY(), r = rowRect.width() / 2 * (1 + 0.08f * f);
                Avatars.draw(c, getContext(), Avatars.IDS[i], cx, cy, r, 1f);
                if (Avatars.IDS[i].equals(cur)) {
                    avatarRingColor(c, cx, cy, r, 4.5f * k, 0xFFFFFFFF);
                    drawCheckBadge(c, cx + r * 0.72f, cy + r * 0.72f, 17 * k);
                }
                if (f > 0.01f) avatarRing(c, cx, cy, r, 6 * k, f);   // the focus: flush with the circle
            }
        }

        private void avatarRingColor(Canvas c, float cx, float cy, float radius, float width, int color) {
            float half = width / 2;
            tmp2.set(cx - radius + half, cy - radius + half, cx + radius - half, cy + radius - half);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(width);
            ringPaint.setShader(null);
            ringPaint.setColor(color);
            c.drawOval(tmp2, ringPaint);
        }

        private final android.graphics.Path checkPath = new android.graphics.Path();

        /** The chosen avatar's mark: a gradient circle with a dark outline and a check. */
        private void drawCheckBadge(Canvas c, float bx, float by, float br) {
            tmp.set(bx - br, by - br, bx + br, by + br);
            white.setShader(new LinearGradient(tmp.left, 0, tmp.right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            white.setColor(0xFFFFFFFF);
            c.drawCircle(bx, by, br, white);
            white.setShader(null);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(2.5f * k);
            ringPaint.setColor(INK);
            c.drawCircle(bx, by, br, ringPaint);
            checkPath.rewind();
            checkPath.moveTo(bx - 0.45f * br, by + 0.02f * br);
            checkPath.lineTo(bx - 0.1f * br, by + 0.38f * br);
            checkPath.lineTo(bx + 0.5f * br, by - 0.34f * br);
            ringPaint.setStrokeWidth(3.2f * k);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            ringPaint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(checkPath, ringPaint);
            ringPaint.setStrokeCap(Paint.Cap.BUTT);
            ringPaint.setStrokeJoin(Paint.Join.MITER);
            ringPaint.setColor(0xFFFFFFFF);
        }


        // MARK: - Mi perfil (first card of the left column and its panel; see the design delivery)

        private int profLevel;   // 0 the card only, 1 the avatar in the panel, 2 the name field, 3 the list of avatars
        private int gridIdx;
        private boolean gridOpen;
        private long gridT0, popT0;
        private final Spring fAv = new Spring(0.2f, 0.9f, 0), fName = new Spring(0.2f, 0.9f, 0);
        private final Spring[] gridPop = new Spring[Avatars.IDS.length];
        private final android.view.animation.PathInterpolator profCurve = ScreenTransition.curve();
        private Paint pCaps, pFieldT, pFieldHint, pGreet, pFootP, pPillT, pDesc;

        private String shownAvatar() {
            String a = Avatars.current();
            return a.isEmpty() ? Avatars.DEFAULT : a;
        }

        private int avatarIndex() {
            return Math.max(0, java.util.Arrays.asList(Avatars.IDS).indexOf(shownAvatar()));
        }

        private void enterProfile() {
            profLevel = 1;
            Avatars.preload(getContext());
            invalidate();
            spoken.focusMoved();
        }

        private boolean profAnimating() {
            long now = SystemClock.uptimeMillis();
            if (!fAv.settled() || !fName.settled()) return true;
            for (Spring s : gridPop) if (s != null && !s.settled()) return true;
            return (gridT0 > 0 && now - gridT0 < 420) || (popT0 > 0 && now - popT0 < 520);
        }

        private float pc(float t) {
            return profCurve.getInterpolation(Math.max(0f, Math.min(1f, t)));
        }

        private void closeGrid(boolean choose) {
            long now = SystemClock.uptimeMillis();
            if (choose) {
                Prefs.setAvatar(Avatars.IDS[gridIdx]);
                popT0 = now + 200;
            }
            gridOpen = false;
            gridT0 = now;
            profLevel = 1;
            invalidate();
            spoken.focusMoved();
        }

        private boolean profileKey(KeyEvent e, boolean down) {
            int code = e.getKeyCode(), n = Avatars.IDS.length, cols = 6;
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        if (profLevel == 3) closeGrid(false); else profLevel = 0;
                        invalidate();
                        spoken.focusMoved();
                    }
                    backDown = false;
                }
                return true;
            }
            if (ok) {
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled() && spoken.keyClick()) {
                        click().open();
                        if (profLevel == 1) {
                            gridIdx = avatarIndex();
                            gridOpen = true;
                            gridT0 = SystemClock.uptimeMillis();
                            profLevel = 3;
                        } else if (profLevel == 2) {
                            askName();
                        } else if (profLevel == 3) {
                            closeGrid(true);
                        }
                        invalidate();
                        spoken.focusMoved();
                    }
                    okDown = false;
                }
                return true;
            }
            if (!down) return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            int before = gridIdx * 10 + profLevel;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (profLevel == 2) profLevel = 1;
                    else if (profLevel == 3) {
                        if (gridIdx >= cols) gridIdx -= cols;
                        else {   // round: the same column in the last row (or the last avatar)
                            int at = gridIdx + ((n - 1) / cols) * cols;
                            gridIdx = at < n ? at : n - 1;
                        }
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (profLevel == 1) profLevel = 2;
                    else if (profLevel == 3) {
                        if (gridIdx + cols < n) gridIdx += cols;
                        else if (gridIdx / cols < (n - 1) / cols) gridIdx = n - 1;
                        else gridIdx %= cols;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (profLevel == 3) gridIdx = (gridIdx + n - 1) % n;
                    else profLevel = 0;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (profLevel == 3) gridIdx = (gridIdx + 1) % n;
                    break;
                default:
                    return false;
            }
            if (before != gridIdx * 10 + profLevel) click().move();
            invalidate();
            spoken.focusMoved();
            return true;
        }

        /** The edit mark of the profile: a white circle with a dark outline and a dark pencil. */
        private void drawWhitePencil(Canvas c, float bx, float by, float br, float border) {
            white.setColor(0xFFFFFFFF);
            c.drawCircle(bx, by, br, white);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setShader(null);
            ringPaint.setStrokeWidth(border);
            ringPaint.setColor(INK);
            c.drawCircle(bx, by, br - border / 2, ringPaint);
            float s = br * 1.15f;
            pencilPath.rewind();
            pencilPath.moveTo(-0.13f * s, -0.42f * s);
            pencilPath.lineTo(0.13f * s, -0.42f * s);
            pencilPath.lineTo(0.13f * s, 0.14f * s);
            pencilPath.lineTo(0f, 0.44f * s);
            pencilPath.lineTo(-0.13f * s, 0.14f * s);
            pencilPath.close();
            c.save();
            c.translate(bx, by);
            c.rotate(45);
            white.setColor(INK);
            c.drawPath(pencilPath, white);
            c.restore();
            ringPaint.setColor(0xFFFFFFFF);
            white.setColor(0xFFFFFFFF);
        }

        /** The current avatar's mark in the list: a Lily circle with a panel-colored outline and a dark check. */
        private void drawLilyCheck(Canvas c, float bx, float by, float br) {
            white.setColor(INK);
            c.drawCircle(bx, by, br + 4.5f * k, white);
            white.setColor(LILY);
            c.drawCircle(bx, by, br, white);
            checkPath.rewind();
            checkPath.moveTo(bx - 0.45f * br, by + 0.02f * br);
            checkPath.lineTo(bx - 0.1f * br, by + 0.38f * br);
            checkPath.lineTo(bx + 0.5f * br, by - 0.34f * br);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setShader(null);
            ringPaint.setColor(INK);
            ringPaint.setStrokeWidth(3.6f * k);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            ringPaint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(checkPath, ringPaint);
            ringPaint.setStrokeCap(Paint.Cap.BUTT);
            ringPaint.setStrokeJoin(Paint.Join.MITER);
            ringPaint.setColor(0xFFFFFFFF);
            white.setColor(0xFFFFFFFF);
        }

        /** The first card: avatar (always with its pencil), "Mi perfil" and "<name> · avatar y nombre". */
        private void drawProfileCard(Canvas c) {
            float top = cardTop(-1) * k;
            rowRect.set(C_L * k, top, (C_L + C_W) * k, top + cardH(-1) * k);
            boolean sel = screen == MAIN && focus == -1;
            float f = sel ? Ui.clamp(profilePop.value) : 0, grow = 4 * k * f, r = 33 * k;
            tmp.set(rowRect.left - grow, rowRect.top - grow, rowRect.right + grow, rowRect.bottom + grow);
            if (f > 0.01f) {
                tmp.offset(0, 8 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.35f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -8 * k * f);
            }
            if (sel) {
                pPanel.setColor(0xFF26212B);
                c.drawRoundRect(tmp, r, r, pPanel);
                blinkFill(c, tmp, r, f);
                gradientRing(c, tmp, r, 4.5f * k, 1f);
            } else {
                pPanel.setColor(0x12FFFFFF);
                c.drawRoundRect(tmp, r, r, pPanel);
            }
            float pad = (28 - 4 * f) * k, ar = 39 * k, cy = tmp.centerY(), ax = tmp.left + pad + ar;
            Avatars.draw(c, getContext(), shownAvatar(), ax, cy, ar, 1f);
            drawWhitePencil(c, ax + ar * 0.74f, cy + ar * 0.74f, 15 * k, 3 * k);
            float tx = ax + ar + 21 * k;
            String name = Prefs.name();
            String sub = name.isEmpty() ? Ui.tr("Avatar e nome", "Avatar and name", "Avatar y nombre")
                    : name + " · " + Ui.tr("avatar e nome", "avatar and name", "avatar y nombre");
            c.drawText(Ui.tr("Meu perfil", "My profile", "Mi perfil"), tx, cy - 6 * k, f > 0.5f ? pProfTB : pProfT);
            c.drawText(TextUtils.ellipsize(sub, new TextPaint(pLeftS), tmp.right - 24 * k - tx, TextUtils.TruncateAt.END).toString(), tx, cy + 26 * k, pLeftS);
        }

        private void profPaints() {
            if (pFieldT != null && pFieldT.getTextSize() == 33 * k) return;
            pCaps = Ui.text(Ui.bold, 19.5f * k, 0x99FFFFFF);
            pCaps.setLetterSpacing(0.08f);
            pCaps.setTextAlign(Paint.Align.CENTER);
            pFieldT = Ui.text(Ui.semibold, 33 * k, 0xFFFFFFFF);
            pFieldHint = Ui.text(Ui.semibold, 33 * k, 0x80FFFFFF);
            pGreet = Ui.text(Ui.medium, 22.5f * k, 0xB3FFFFFF);
            pGreet.setTextAlign(Paint.Align.CENTER);
            pFootP = Ui.text(Ui.semibold, 21 * k, 0x99FFFFFF);
            pFootP.setTextAlign(Paint.Align.CENTER);
            pPillT = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
            pDesc = Ui.text(Ui.medium, 24 * k, 0xBDFFFFFF);
        }

        /** The panel of "Mi perfil": the big avatar, the name; and, over it, the list of avatars. */
        private void drawProfilePanel(Canvas c) {
            profPaints();
            long now = SystemClock.uptimeMillis();
            float cx = (P_L + P_W / 2f) * k, cy = (P_T + 178 + 114) * k, r = 114 * k;
            float ca = 1, cs = 1, ga = 0, gs = 1;
            long el = now - gridT0;
            if (gridOpen) {
                float a = pc(el / 200f), g = pc(el / 320f);
                ca = 1 - a;
                cs = 1 - 0.06f * a;
                ga = g;
                gs = 0.55f + 0.45f * g;
            } else if (gridT0 > 0 && el < 340) {
                float g = pc(el / 220f), a = pc((el - 80) / 260f);
                ga = 1 - g;
                gs = 1 - 0.45f * g;
                ca = a;
                cs = 0.94f + 0.06f * a;
            }
            if (ca > 0.01f) {
                c.save();
                c.scale(cs, cs, cx, cy);
                int layer = c.saveLayerAlpha((P_L) * k, P_T * k, (P_L + P_W) * k, (P_T + P_H) * k, Math.round(255 * ca));
                c.drawText(Ui.tr("Meu perfil", "My profile", "Mi perfil"), (P_L + 38) * k, (P_T + 78) * k, pPanelTitle);
                float s = 1;
                long pe = now - popT0;
                if (popT0 > 0 && pe >= 0 && pe < 480) s = pe < 140 ? 1 + 0.12f * pc(pe / 140f) : 1.12f - 0.12f * pc((pe - 140) / 340f);
                float fa = Ui.clamp(fAv.value);
                if (fa > 0.01f) avatarRing(c, cx, cy, r + 13.5f * k, 4.5f * k, fa);
                c.save();
                c.scale(s, s, cx, cy);
                Avatars.draw(c, getContext(), shownAvatar(), cx, cy, r, 1f);
                drawWhitePencil(c, cx + r * 0.74f, cy + r * 0.74f, 33 * k, 4.5f * k);
                c.restore();
                float ringBottom = cy + r + 13.5f * k;
                c.drawText(Ui.tr("SEU NOME", "YOUR NAME", "TU NOMBRE"), cx, ringBottom + 45 * k + 15 * k, pCaps);
                // the field
                float fw = 660 * k, fh = 96 * k, fl = cx - fw / 2, ft = ringBottom + 45 * k + 15 * k + 15 * k + 8 * k;
                float fn = Ui.clamp(fName.value);
                tmp.set(fl, ft, fl + fw, ft + fh);
                pPanel.setColor(fn > 0.5f ? 0xFF26212B : 0xFF211D26);
                c.drawRoundRect(tmp, 27 * k, 27 * k, pPanel);
                if (fn < 0.99f) {
                    float half = 2.25f * k;
                    tmp2.set(tmp.left + half, tmp.top + half, tmp.right - half, tmp.bottom - half);
                    pPanel.setStyle(Paint.Style.STROKE);
                    pPanel.setStrokeWidth(4.5f * k);
                    pPanel.setColor(0x29FFFFFF);
                    c.drawRoundRect(tmp2, 27 * k - half, 27 * k - half, pPanel);
                    pPanel.setStyle(Paint.Style.FILL);
                }
                if (fn > 0.01f) gradientRing(c, tmp, 27 * k, 4.5f * k, fn);
                String name = Prefs.name();
                float tl = tmp.left + 30 * k, room = fw - 60 * k - (fn > 0.5f ? 150 * k : 0);
                Paint fp = name.isEmpty() ? pFieldHint : pFieldT;
                String shown = name.isEmpty() ? Ui.tr("Digite seu nome", "Type your name", "Escribe tu nombre") : name;
                c.drawText(TextUtils.ellipsize(shown, new TextPaint(fp), room, TextUtils.TruncateAt.END).toString(), tl, tmp.centerY() + Ui.halfCap(fp), fp);
                if (fn > 0.01f) {
                    pFootP.setTextAlign(Paint.Align.RIGHT);
                    pFootP.setAlpha(Math.round(0xB3 * fn));
                    c.drawText(Ui.tr("OK · Editar", "OK · Edit", "OK · Editar"), tmp.right - 30 * k, tmp.centerY() + Ui.halfCap(pFootP), pFootP);
                    pFootP.setTextAlign(Paint.Align.CENTER);
                    pFootP.setAlpha(0x99);
                }
                String hi = name.isEmpty() ? Ui.tr("Olá!", "Hi!", "¡Hola!") : Ui.tr("Olá, " + name + "!", "Hi, " + name + "!", "¡Hola, " + name + "!");
                c.drawText(TextUtils.ellipsize(Ui.tr("Assim a tela inicial te cumprimenta: «", "This is how the Home greets you: «", "Así te saluda el Home: «") + hi + "»",
                        new TextPaint(pGreet), fw + 120 * k, TextUtils.TruncateAt.END).toString(), cx, tmp.bottom + 21 * k + 21 * k, pGreet);
                String foot = profLevel == 2 ? Ui.tr("OK · Editar nome      Cima · Avatar", "OK · Edit name      Up · Avatar", "OK · Editar nombre      Arriba · Avatar")
                        : profLevel == 1 ? Ui.tr("OK · Mudar avatar      Baixo · Seu nome", "OK · Change avatar      Down · Your name", "OK · Cambiar avatar      Abajo · Tu nombre")
                        : Ui.tr("OK · Editar perfil", "OK · Edit profile", "OK · Editar perfil");
                c.drawText(foot, cx, (P_T + P_H - 39) * k, pFootP);
                c.restoreToCount(layer);
                c.restore();
            }
            if (ga > 0.01f) {
                c.save();
                c.scale(gs, gs, cx, cy);
                int layer = c.saveLayerAlpha(P_L * k, P_T * k, (P_L + P_W) * k, (P_T + P_H) * k, Math.round(255 * ga));
                c.drawText(Ui.tr("Escolha seu avatar", "Choose your avatar", "Elige tu avatar"), (P_L + 38) * k, (P_T + 78) * k, pPanelTitle);
                c.drawText(Ui.tr("Use as setas e aperte OK para escolher.", "Move with the arrows and press OK to choose.", "Muévete con las flechas y pulsa OK para elegir."),
                        (P_L + 38) * k, (P_T + 78 + 40) * k, pDesc);
                String cur = Avatars.current();
                for (int i = 0; i < Avatars.IDS.length; i++) {
                    float gx = (P_L + 91.5f + (i % 6) * 159) * k + 63 * k, gy = (P_T + 210 + (i / 6) * 165) * k + 63 * k, gr = 63 * k;
                    Avatars.draw(c, getContext(), Avatars.IDS[i], gx, gy, gr, 1f);
                    if (Avatars.IDS[i].equals(cur)) {
                        avatarRingColor(c, gx, gy, gr, 4.5f * k, 0xFFFFFFFF);
                        drawLilyCheck(c, gx + gr * 0.74f, gy + gr * 0.74f, 22.5f * k);
                    }
                    float gp = Ui.clamp(gridPop[i].value);
                    if (gp > 0.01f) avatarRing(c, gx, gy, gr + 13.5f * k, 4.5f * k, gp);
                }
                // the two hints, as pills
                String p1 = Ui.tr("OK · Escolher", "OK · Choose", "OK · Elegir"), p2 = Ui.tr("Voltar · Cancelar", "Back · Cancel", "Atrás · Cancelar");
                float w1 = pPillT.measureText(p1) + 60 * k, w2 = pPillT.measureText(p2) + 60 * k, gap = 18 * k, ph = 60 * k;
                float x0 = cx - (w1 + gap + w2) / 2, py = (P_T + P_H - 33) * k - ph;
                pPanel.setColor(0x14FFFFFF);
                tmp.set(x0, py, x0 + w1, py + ph);
                c.drawRoundRect(tmp, ph / 2, ph / 2, pPanel);
                c.drawText(p1, tmp.centerX() - pPillT.measureText(p1) / 2, tmp.centerY() + Ui.halfCap(pPillT), pPillT);
                tmp.set(x0 + w1 + gap, py, x0 + w1 + gap + w2, py + ph);
                c.drawRoundRect(tmp, ph / 2, ph / 2, pPanel);
                c.drawText(p2, tmp.centerX() - pPillT.measureText(p2) / 2, tmp.centerY() + Ui.halfCap(pPillT), pPillT);
                c.restoreToCount(layer);
                c.restore();
            }
        }

        // MARK: - Acerca de Borealis (rolling credits; see the design delivery)

        private float credS = 780, credSpeed = 1, credH = 1900;   // distance rolled (1920 units), speed factor, height of the roll
        private int credKey;   // -1 up held (rewind), 1 down held (fast)
        private Paint cLabel, cName, cSupport, cSlogan, cThanks, cVer, cFoot, cPill;
        private StaticLayout cNotice;
        private Bitmap tmdbLogo;
        private boolean tmdbTried;

        // MARK: - Icons and layout editor (Home screen > Icons and layout of the apps)

        private static final int[] ICO_COLS = {6, 8, 10};
        private List<HomeApps.Item> icoShelf = new ArrayList<>(), icoOthers = new ArrayList<>();
        private final java.util.HashMap<Integer, List<HomeApps.Item>> icoOrders = new java.util.HashMap<>();
        private final java.util.HashMap<Integer, java.util.HashMap<HomeApps.Item, Integer>> icoIdx = new java.util.HashMap<>();
        private final java.util.HashMap<String, String> icoNames = new java.util.HashMap<>();
        private IconLayout icoFrom, icoTo;
        private long icoT0;
        private boolean icoLoaded;
        private int icoLoad;
        private Paint icoTitle, icoInstr, icoCap, icoHead, icoBig, icoSub, icoMsg, icoPill, icoMiniName, icoMiniText;
        private float icoK;
        private final Paint icoFill = new Paint(Paint.ANTI_ALIAS_FLAG), icoDash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] icoA = new float[4], icoB = new float[4];

        private static final String[] ICO_NAMES = {"Netflix", "YouTube", "Spotify", "Max", "Disney+", "Prime Video", "Plex", "Apple TV",
                "Paramount+", "Kodi", "Twitch", "Crunchyroll", "Pluto TV", "Tubi", "Navegador", "Archivos",
                "Galería", "Juegos", "Play Store", "Calculadora", "Reloj", "Clima", "Radio", "Podcasts",
                "VLC", "Fotos", "Cámara", "Chat", "Mapas", "Libros", "Notas", "Cast",
                "Karaoke", "Deportes", "Noticias", "Tienda", "Ayuda", "Música", "Cine", "Series"};
        private Bitmap icoWall;

        /** The design's sample screen: 7 apps on the shelf, many more in All Apps, colored tiles with no content. */
        private List<HomeApps.Item> icoAll;

        private void openIcons() {
            focus = Prefs.iconCols() == 6 ? 0 : Prefs.iconCols() == 10 ? 2 : 1;
            icoFrom = icoTo = null;
            if (icoAll == null) {   // 64 sample apps: the grid is always full, whatever the user has installed
                icoAll = new ArrayList<>();
                for (int n = 0; n < 64; n++) {
                    HomeApps.Item it = new HomeApps.Item();
                    it.kind = HomeApps.APP;
                    it.label = ICO_NAMES[n % ICO_NAMES.length];
                    it.inputType = n + 1;   // the seed of its dot color
                    icoAll.add(it);
                }
            }
            icoShelf = new ArrayList<>();
            icoOthers = new ArrayList<>(icoAll);
            icoOrders.clear();
            icoIdx.clear();
            icoLoaded = false;   // until the real number of pinned apps is known (a few milliseconds)
            icoWall = null;
            final int token = ++icoLoad;
            final int wp = Prefs.wallpaper();
            final Context app = getContext().getApplicationContext();
            new Thread(() -> {
                int pinned = 0;
                try {
                    pinned = Math.min(HomeApps.MAX_SHELF, HomeApps.shelf(app, false).size());
                } catch (RuntimeException ignored) {
                }
                final int real = pinned;
                handler.post(() -> {
                    if (token != icoLoad) return;
                    icoShelf = new ArrayList<>(icoAll.subList(0, real));   // the real number of pinned apps, shown with sample apps
                    icoOthers = new ArrayList<>(icoAll.subList(real, icoAll.size()));
                    icoOrders.clear();
                    icoIdx.clear();
                    icoLoaded = true;
                    invalidate();
                });
                final Bitmap pic = decodeWallpaper(Prefs.WALLPAPER_FILES[wp], 2, 960);
                handler.post(() -> {
                    if (token != icoLoad) return;
                    icoWall = pic;
                    invalidate();
                });
            }).start();
            setScreen(ICON_EDIT);
            invalidate();
        }

        private int icoPinned(int cols) {
            return Math.min(cols, icoShelf.size());
        }

        /** The grid order for a number of columns: the first `cols` shelf apps, then the rest A-Z (the shelf's overflow too). */
        private List<HomeApps.Item> icoOrder(int cols) {
            List<HomeApps.Item> o = icoOrders.get(cols);
            if (o != null) return o;
            o = new ArrayList<>();
            for (int i = 0; i < icoShelf.size() && i < cols; i++) o.add(icoShelf.get(i));
            List<HomeApps.Item> rest = new ArrayList<>(icoOthers);
            for (int i = cols; i < icoShelf.size(); i++) rest.add(icoShelf.get(i));
            java.util.Collections.sort(rest, (x, y) -> x.inputType - y.inputType);   // the sample keeps the design's order
            o.addAll(rest);
            java.util.HashMap<HomeApps.Item, Integer> idx = new java.util.HashMap<>();
            for (int i = 0; i < o.size(); i++) idx.put(o.get(i), i);
            icoOrders.put(cols, o);
            icoIdx.put(cols, idx);
            return o;
        }

        private int icoPreviewCols() { return focus >= 0 && focus < 3 ? ICO_COLS[focus] : Prefs.iconCols(); }
        private int icoPreviewShape() { return focus >= 3 ? focus - 3 : Prefs.iconShape(); }

        private void iconsApply() {
            if (focus < 0 || focus > 5) return;
            if (focus < 3) Prefs.setIconCols(ICO_COLS[focus]); else Prefs.setIconShape(focus - 3);
            invalidate();
            spoken.focusMoved();
        }

        private boolean iconsKey(KeyEvent e, boolean down) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) focus = (focus + 5) % 6;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) focus = (focus + 1) % 6;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                default:
                    return false;
            }
            invalidate();
            spoken.focusMoved();
            return true;
        }

        /** Card i (0-2 distribution, 3-5 shape) in 1920-wide units. */
        private void iconsCard(int i, RectF out) {
            float left = i < 3 ? 96 : 1032, x = left + (i % 3) * (252 + 18);
            out.set(x * k, 735 * k, (x + 252) * k, (735 + 174) * k);
        }

        private String iconsCardText(int i) {
            String s;
            if (i < 3) {
                int c = ICO_COLS[i], r = c == 6 ? 4 : c == 10 ? 6 : 5;
                s = Ui.tr("Distribuição ", "Layout ", "Distribución ") + c + Ui.tr(" por ", " by ", " por ") + r + ", "
                        + (i == 0 ? Ui.tr("ampla", "wide", "amplia") : i == 1 ? Ui.tr("equilibrada", "balanced", "equilibrada") : Ui.tr("compacta", "compact", "compacta"));
                if (ICO_COLS[i] == Prefs.iconCols()) s += ", " + Ui.tr("aplicada", "applied", "aplicada");
            } else {
                s = Ui.tr("Forma ", "Shape ", "Forma ") + (i == 3 ? Ui.tr("retangular", "rectangular", "rectangular") : i == 4 ? Ui.tr("quadrada", "square", "cuadrada") : "circular");
                if (i - 3 == Prefs.iconShape()) s += ", " + Ui.tr("aplicada", "applied", "aplicada");
            }
            return s;
        }

        private void icoPaints() {
            if (icoK == k && icoTitle != null) return;
            icoK = k;
            icoTitle = Ui.text(Logo.wordmark(getContext()), 51 * k, 0xFFFFFFFF);
            icoInstr = Ui.text(Ui.medium, 22.5f * k, Ui.alpha(0xFFFFFFFF, 0.7f));
            icoCap = Ui.text(Ui.bold, 18 * k, Ui.alpha(0xFFFFFFFF, 0.6f));
            icoCap.setLetterSpacing(0.1f);
            icoHead = Ui.text(Ui.bold, 19.5f * k, LILY);
            icoHead.setLetterSpacing(0.12f);
            icoBig = Ui.text(Ui.bold, 27 * k, 0xFFFFFFFF);
            icoBig.setTextAlign(Paint.Align.CENTER);
            icoSub = Ui.text(Ui.medium, 19.5f * k, Ui.alpha(0xFFFFFFFF, 0.6f));
            icoSub.setTextAlign(Paint.Align.CENTER);
            icoMsg = Ui.text(Ui.semibold, 22.5f * k, 0xFFFFFFFF);
            icoMsg.setTextAlign(Paint.Align.CENTER);
            icoPill = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
            icoMiniName = Ui.text(Ui.medium, 19.5f, Ui.alpha(0xFFFFFFFF, 0.78f));   // drawn inside the scaled miniature
            icoMiniName.setTextAlign(Paint.Align.CENTER);
            icoMiniText = Ui.text(Ui.medium, 22, 0xFFFFFFFF);
            icoMiniText.setTextAlign(Paint.Align.CENTER);
        }

        private boolean icoRect(IconLayout g, int idx, int pinned, boolean home, float[] out) {
            if (idx < 0) return false;
            if (home) {
                if (idx >= pinned) return false;
                out[0] = g.tileX(idx);
                out[1] = g.homeTileY();
                return true;
            }
            int row, col;
            if (idx < pinned) {
                row = idx / g.cols;
                col = idx % g.cols;
            } else {
                row = (pinned + g.cols - 1) / g.cols + (idx - pinned) / g.cols;
                col = (idx - pinned) % g.cols;
            }
            if (row >= g.rows) return false;
            out[0] = g.tileX(col);
            out[1] = g.gridTileY(row);
            return true;
        }

        /** A sample app as the design draws it: a soft white tile with a colored dot in the middle (a different color per app). */
        private void icoTile(Canvas c, HomeApps.Item it, int shape, float x, float y, float tw, float th, float rad, float alpha) {
            tmp2.set(x, y, x + tw, y + th);
            icoFill.setShader(null);
            icoFill.setColor(Ui.alpha(0xFFFFFFFF, 0.13f * alpha));
            c.drawRoundRect(tmp2, rad, rad, icoFill);
            int[] tint = {LILY, PEACH, MELON};
            icoFill.setColor(Ui.alpha(tint[new java.util.Random(it.inputType * 17L).nextInt(3)], 0.9f * alpha));
            c.drawCircle(tmp2.centerX(), tmp2.centerY(), Math.min(tw, th) * 0.17f, icoFill);
        }

        /** The live miniature: the real Home (shelf) or All Apps at 0.4125, with the layout and the shape in preview. */
        private void drawIconsMini(Canvas c, boolean home, float left, float top, float e) {
            IconLayout A = icoFrom, B = icoTo;
            int shapeNow = e < 0.5f ? A.shape : B.shape;
            c.save();
            tmp.set(left * k, top * k, (left + 792) * k, (top + 445.5f) * k);
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(tmp, 24 * k, 24 * k, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            c.translate(tmp.left, tmp.top);
            c.scale(0.4125f * k, 0.4125f * k);
            c.drawColor(Brand.INK);
            Bitmap wall = home ? (icoWall != null ? icoWall : bgSoft) : bgSoft;
            if (wall != null) {
                waveDst.set(0, 0, 1920, 1080);
                bgPaint.setAlpha(255);
                c.drawBitmap(wall, null, waveDst, bgPaint);
            }
            icoFill.setShader(null);
            if (home) {   // the wallpaper as it is, a little darker toward the bottom where the shelf sits
                icoFill.setShader(new LinearGradient(0, 0, 0, 1080, Ui.alpha(Brand.INK, 0.10f), Ui.alpha(Brand.INK, 0.62f), Shader.TileMode.CLAMP));
                c.drawRect(0, 0, 1920, 1080, icoFill);
                icoFill.setShader(null);
            } else {
                icoFill.setColor(Ui.alpha(Brand.INK, 0.58f));
                c.drawRect(0, 0, 1920, 1080, icoFill);
            }
            if (home) {   // the design's sample Home (IconosVista), x1.5: header, clock and three "continue watching" cards
                icoFill.setColor(0xFFFFFFFF);
                c.drawCircle(141, 105, 45, icoFill);
                icoFill.setColor(0xFF3B2F66);
                c.drawCircle(141, 105, 40.5f, icoFill);
                icoMiniText.setTextAlign(Paint.Align.LEFT);
                icoMiniText.setTypeface(Ui.medium);
                icoMiniText.setTextSize(27);
                icoMiniText.setColor(Ui.alpha(0xFFFFFFFF, 0.74f));
                c.drawText(Ui.tr("Olá, Jero", "Hi, Jero", "Hola, Jero"), 216, 90, icoMiniText);
                icoMiniText.setTypeface(Logo.wordmark(getContext()));
                icoMiniText.setTextSize(63);
                icoMiniText.setColor(0xFFFFFFFF);
                c.drawText(Ui.tr("O que quer ver hoje?", "What do you want to watch?", "¿Qué quieres ver hoy?"), 216, 140, icoMiniText);
                icoFill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f));
                c.drawRoundRect(1824 - 292, 69, 1824, 141, 36, 36, icoFill);
                icoMiniText.setTypeface(Ui.bold);
                icoMiniText.setTextSize(33);
                c.drawText("15:32", 1824 - 292 + 33, 114, icoMiniText);
                icoMiniText.setTypeface(Ui.medium);
                icoMiniText.setTextSize(24);
                icoMiniText.setColor(Ui.alpha(0xFFFFFFFF, 0.74f));
                c.drawText("Dom 8 Nov", 1824 - 292 + 33 + 101, 113, icoMiniText);
                icoMiniText.setColor(0xFFFFFFFF);
                float[][] cd = {{96, 960}, {96 + 960 + 36, 336}, {96 + 960 + 36 + 336 + 36, 336}};
                int[][] c1 = {{0xFF4A3A86, 0xFFB79CFE, 0xFFE99794}, {0xFF2C2A31, 0xFF5A4A78, 0xFF5A4A78}, {0xFF5A3A46, 0xFFE99794, 0xFFE99794}};
                for (int n = 0; n < 3; n++) {
                    float cx0 = cd[n][0], cw0 = cd[n][1];
                    float[] ln = n == 0 ? new float[] {135, 93, 825, 783} : new float[] {80.8f, 198.4f, 255.2f, 677.6f};   // CSS 135deg / 160deg
                    float ox = n == 0 ? cx0 : cx0 + (n == 0 ? 0 : 0);
                    icoFill.setShader(new LinearGradient(ox + ln[0] - (n == 0 ? 0 : 0), ln[1], ox + ln[2], ln[3],
                            c1[n], n == 0 ? new float[] {0, 0.55f, 1} : new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
                    c.drawRoundRect(cx0, 228, cx0 + cw0, 648, 30, 30, icoFill);
                    icoFill.setShader(new LinearGradient(0, 648, 0, 648 - 225, Ui.alpha(Brand.INK, 0.92f), 0x00171519, Shader.TileMode.CLAMP));
                    int sv = c.save();
                    clip.rewind();
                    clip.addRoundRect(cx0, 228, cx0 + cw0, 648, 30, 30, android.graphics.Path.Direction.CW);
                    c.clipPath(clip);
                    c.drawRect(cx0, 648 - 225, cx0 + cw0, 648, icoFill);
                    c.restoreToCount(sv);
                    icoFill.setShader(null);
                }
            } else {   // All Apps (no search field): title and hint as in the design, the chevron at the bottom
                icoMiniText.setTextAlign(Paint.Align.LEFT);
                icoMiniText.setTypeface(Logo.wordmark(getContext()));
                icoMiniText.setTextSize(54);
                icoMiniText.setColor(0xFFFFFFFF);
                c.drawText(Ui.tr("Todos os apps", "All apps", "Todas las apps"), 96, 107, icoMiniText);
                icoMiniText.setTypeface(Ui.medium);
                icoMiniText.setTextSize(21);
                icoMiniText.setColor(Ui.alpha(0xFFFFFFFF, 0.62f));
                c.drawText(Ui.tr("Segure OK sobre um app para movê-lo, fixá-lo ou tirá-lo", "Hold OK on an app to move, pin or remove it", "Mantén OK sobre una app para moverla, fijarla o quitarla"), 96, 145, icoMiniText);
                icoMiniText.setColor(0xFFFFFFFF);
                icoDash.setStyle(Paint.Style.STROKE);
                icoDash.setStrokeWidth(2.6f);
                icoDash.setStrokeCap(Paint.Cap.ROUND);
                icoDash.setColor(Ui.alpha(0xFFFFFFFF, 0.5f));
                c.drawLine(948, 1036, 960, 1050, icoDash);
                c.drawLine(972, 1036, 960, 1050, icoDash);
                icoDash.setStyle(Paint.Style.FILL);
            }
            if (!icoLoaded) {
                c.restore();
                return;
            }
            List<HomeApps.Item> oB = icoOrder(B.cols);
            icoOrder(A.cols);
            int pA = icoPinned(A.cols), pB = icoPinned(B.cols);
            java.util.HashMap<HomeApps.Item, Integer> ixA = icoIdx.get(A.cols);
            icoMiniName.setTextSize(B.cols == 10 ? 18 : 19.5f);
            for (int i = 0; i < oB.size(); i++) {
                HomeApps.Item it = oB.get(i);
                Integer ia = ixA.get(it);
                boolean inA = icoRect(A, ia == null ? -1 : ia, pA, home, icoA), inB = icoRect(B, i, pB, home, icoB);
                if (!inA && !inB) continue;
                float x, y, tw, th, rad, alpha = 1;
                if (inA && inB) {
                    x = icoA[0] + (icoB[0] - icoA[0]) * e;
                    y = icoA[1] + (icoB[1] - icoA[1]) * e;
                    tw = A.tileW + (B.tileW - A.tileW) * e;
                    th = A.tileH + (B.tileH - A.tileH) * e;
                    rad = A.tileR + (B.tileR - A.tileR) * e;
                } else if (inB) {
                    x = icoB[0]; y = icoB[1]; tw = B.tileW; th = B.tileH; rad = B.tileR; alpha = e;
                } else {
                    x = icoA[0]; y = icoA[1]; tw = A.tileW; th = A.tileH; rad = A.tileR; alpha = 1 - e;
                }
                if (home && i == 2 && inB) {   // the design focuses the third app: gradient ring (7 px of air) and its name below
                    float air = 10.5f, rw = 4.5f;
                    icoFill.setShader(new LinearGradient(x - air, 0, x + tw + air, 0, new int[] {LILY, PEACH, MELON}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
                    icoFill.setAlpha(Math.round(255 * alpha));
                    c.drawRoundRect(x - air, y - air, x + tw + air, y + th + air, rad + air, rad + air, icoFill);
                    icoFill.setShader(null);
                    icoFill.setColor(0xFF1B1720);
                    c.drawRoundRect(x - air + rw, y - air + rw, x + tw + air - rw, y + th + air - rw, rad + air - rw, rad + air - rw, icoFill);
                }
                icoTile(c, it, shapeNow, x, y, tw, th, rad, alpha);
                if (home && i == 2 && inB) {
                    icoMiniText.setTextAlign(Paint.Align.CENTER);
                    icoMiniText.setTypeface(Ui.semibold);
                    icoMiniText.setTextSize(22.5f);
                    icoMiniText.setColor(0xFFFFFFFF);
                    c.drawText(it.label, x + tw / 2, y + th + 18 + 17, icoMiniText);
                }
                if (!home && it.label != null) {   // the name under each app: 13 px (12 at 10 columns), 500, white 78%
                    float cw = inB ? B.cellW : A.cellW;
                    String key = it.label + "|" + (int) cw + "|" + B.cols;
                    String fit = icoNames.get(key);
                    if (fit == null) {
                        int n = icoMiniName.breakText(it.label, true, cw, null);
                        fit = n >= it.label.length() ? it.label : it.label.substring(0, Math.max(1, n - 1)) + "…";
                        icoNames.put(key, fit);
                    }
                    icoMiniName.setAlpha(Math.round(255 * 0.78f * alpha));
                    c.drawText(fit, x + tw / 2, y + th + 9 + (B.cols == 10 ? 14 : 15), icoMiniName);
                }
            }
            if (home) {   // free places: dotted silhouettes (the first one carries the "+")
                for (int pass = 0; pass < 2; pass++) {
                    IconLayout g = pass == 0 ? A : B;
                    float a = pass == 0 ? 1 - e : e;
                    if (a < 0.02f) continue;
                    int p = pass == 0 ? pA : pB;
                    icoDash.setStyle(Paint.Style.STROKE);
                    icoDash.setStrokeWidth(5.5f);
                    icoDash.setStrokeCap(Paint.Cap.ROUND);
                    icoDash.setPathEffect(new android.graphics.DashPathEffect(new float[] {12, 14}, 0));
                    icoDash.setColor(0xFFFFFFFF);
                    icoDash.setAlpha(Math.round(255 * 0.13f * a));
                    for (int col = p; col < g.cols; col++) {
                        float x = g.tileX(col), y = g.homeTileY();
                        tmp2.set(x, y, x + g.tileW, y + g.tileH);
                        if (col == p) icoDash.setAlpha(Math.round(255 * 0.34f * a));   // the "+" place: a stronger dashed border
                        c.drawRoundRect(tmp2, g.tileR, g.tileR, icoDash);
                        if (col == p) {
                            icoDash.setPathEffect(null);
                            icoDash.setAlpha(Math.round(255 * 0.75f * a));
                            float cx = tmp2.centerX(), cy = tmp2.centerY(), arm = Math.min(g.tileW, g.tileH) * 0.2f;
                            c.drawLine(cx - arm, cy, cx + arm, cy, icoDash);
                            c.drawLine(cx, cy - arm, cx, cy + arm, icoDash);
                            icoDash.setPathEffect(new android.graphics.DashPathEffect(new float[] {12, 14}, 0));
                            icoDash.setAlpha(Math.round(255 * 0.13f * a));
                        }
                    }
                    icoDash.setPathEffect(null);
                    icoDash.setStyle(Paint.Style.FILL);
                }
                icoFill.setColor(Ui.alpha(0xFFFFFFFF, 0.8f));   // "see all apps": a chevron
                float hy = 873 + B.tileH / 2 + 46;
                icoDash.setStyle(Paint.Style.STROKE);
                icoDash.setStrokeWidth(5);
                icoDash.setStrokeCap(Paint.Cap.ROUND);
                icoDash.setColor(Ui.alpha(0xFFFFFFFF, 0.8f));
                c.drawLine(944, hy - 5, 960, hy + 7, icoDash);
                c.drawLine(976, hy - 5, 960, hy + 7, icoDash);
                icoDash.setStyle(Paint.Style.FILL);
            } else if (pB > 0 && pB < oB.size()) {   // the thin line under the shelf row
                float ly = B.gridTileY(1) - (B.pitch - B.tileH) / 2f;
                icoFill.setColor(Ui.alpha(0xFFFFFFFF, 0.16f));
                c.drawRect(96, ly - 1, 1824, ly + 1, icoFill);
            }
            c.restore();
        }

        private void drawIconCard(Canvas c, int i) {
            iconsCard(i, tmp);
            float r = 30 * k;
            boolean applied = i < 3 ? ICO_COLS[i] == Prefs.iconCols() : i - 3 == Prefs.iconShape();
            icoFill.setShader(null);
            icoFill.setColor(applied ? 0xFF26212B : Ui.alpha(0xFFFFFFFF, 0.07f));
            c.drawRoundRect(tmp, r, r, icoFill);
            float cx = tmp.centerX(), top = tmp.top;
            if (i < 3) {
                int cc = ICO_COLS[i], rr = cc == 6 ? 4 : cc == 10 ? 6 : 5;
                float gw = 144 * k, gh = 81 * k, gap = 3 * k, x0 = cx - gw / 2, y0 = top + 24 * k;
                float cw = (gw - (cc - 1) * gap) / cc, ch = (gh - (rr - 1) * gap) / rr;
                icoFill.setColor(applied || focus == i ? LILY : Ui.alpha(0xFFFFFFFF, 0.32f));
                for (int a = 0; a < rr; a++)
                    for (int b = 0; b < cc; b++)
                        c.drawRoundRect(x0 + b * (cw + gap), y0 + a * (ch + gap), x0 + b * (cw + gap) + cw, y0 + a * (ch + gap) + ch, 2 * k, 2 * k, icoFill);
                c.drawText(cc + " × " + rr, cx, top + 138 * k, icoBig);
                c.drawText(i == 0 ? Ui.tr("Ampla", "Wide", "Amplia") : i == 1 ? Ui.tr("Equilibrada", "Balanced", "Equilibrada") : Ui.tr("Compacta", "Compact", "Compacta"), cx, top + 160 * k, icoSub);
            } else {
                int sh = i - 3;
                float sw = sh == 0 ? 105 * k : 81 * k, shh = sh == 0 ? 60 * k : 81 * k, cy = top + 64 * k;
                float sr = sh == 0 ? 12 * k : sh == 1 ? 19.5f * k : 40.5f * k;
                icoFill.setColor(Ui.alpha(0xFFFFFFFF, 0.12f));
                tmp2.set(cx - sw / 2, cy - shh / 2, cx + sw / 2, cy + shh / 2);
                c.drawRoundRect(tmp2, sr, sr, icoFill);
                Paint letter = Ui.text(Logo.wordmark(getContext()), 33 * k, LILY);
                letter.setTextAlign(Paint.Align.CENTER);
                c.drawText("A", cx, cy + Ui.halfCap(letter), letter);
                c.drawText(sh == 0 ? Ui.tr("Retangular", "Rectangular", "Rectangular") : sh == 1 ? Ui.tr("Quadrada", "Square", "Cuadrada") : "Circular", cx, top + 150 * k, icoBig);
            }
            if (applied) drawLilyCheck(c, tmp.right - 30 * k, tmp.top + 30 * k, 22.5f * k);
            if (focus == i) {
                float air = 4 * k, ring = 3 * k, half = ring / 2;
                tmp2.set(tmp.left - air - half, tmp.top - air - half, tmp.right + air + half, tmp.bottom + air + half);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                ringPaint.setShader(new LinearGradient(tmp2.left, tmp2.top, tmp2.right, tmp2.bottom, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                ringPaint.setAlpha(255);
                c.drawRoundRect(tmp2, r + air, r + air, ringPaint);
                ringPaint.setShader(null);
            }
        }

        private void drawIconsPill(Canvas c, float x, float cy, String key, String label) {
            icoPill.setTextAlign(Paint.Align.LEFT);
            float kw = icoPill.measureText(key), lw = icoPill.measureText(" · " + label), pw = kw + lw + 48 * k, ph = 57 * k;
            tmp2.set(x, cy - ph / 2, x + pw, cy + ph / 2);
            icoFill.setShader(null);
            icoFill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f));
            c.drawRoundRect(tmp2, ph / 2, ph / 2, icoFill);
            icoPill.setColor(0xFFFFFFFF);
            c.drawText(key, x + 24 * k, cy + Ui.halfCap(icoPill), icoPill);
            icoPill.setColor(Ui.alpha(0xFFFFFFFF, 0.7f));
            c.drawText(" · " + label, x + 24 * k + kw, cy + Ui.halfCap(icoPill), icoPill);
            icoPill.setColor(0xFFFFFFFF);
        }

        private float icoPillWidth(String key, String label) {
            return icoPill.measureText(key) + icoPill.measureText(" · " + label) + 48 * k;
        }

        private void drawIcons(Canvas c) {
            drawBackground(c);
            c.drawColor(Ui.alpha(Brand.INK, 0.80f));
            icoPaints();
            int pc = icoPreviewCols(), ps = icoPreviewShape();
            long now = SystemClock.uptimeMillis();
            if (icoTo == null) {
                icoTo = icoFrom = IconLayout.of(pc, ps);
                icoT0 = now - 1000;
            } else if (icoTo.cols != pc || icoTo.shape != ps) {
                icoFrom = icoTo;
                icoTo = IconLayout.of(pc, ps);
                icoT0 = now;
            }
            float e = ScreenTransition.curve().getInterpolation(Ui.clamp((now - icoT0) / 260f));
            c.drawText(Ui.tr("Ícones e distribuição dos apps", "Icons and layout of the apps", "Iconos y distribución de las apps"), 96 * k, 51 * k + 40 * k, icoTitle);
            c.drawText(Ui.tr("Escolha uma opção para ver ao vivo e aperte \"OK\" para aplicá-la.",
                    "Pick an option to see it live and press \"OK\" to apply it.",
                    "Selecciona una opción para verla en vivo y presiona \"OK\" para aplicarla."), 96 * k, 123 * k + 8 * k, icoInstr);
            drawIconsMini(c, true, 96, 177, e);
            drawIconsMini(c, false, 1032, 177, e);
            int shelfN = icoShelf.size(), cupo = icoTo.cols;
            // captions
            float cy = 650 * k;
            String left1 = Ui.tr("HOME", "HOME", "HOME");
            c.drawText(left1, 96 * k, cy, icoCap);
            String shelfTxt = Ui.tr("FIXADOS", "PINNED", "FIJADAS") + " · " + Math.min(shelfN, cupo) + Ui.tr(" DE ", " OF ", " DE ") + cupo;
            icoCap.setColor(shelfN > cupo && icoLoaded ? MELON : Ui.alpha(0xFFFFFFFF, 0.6f));
            icoCap.setTextAlign(Paint.Align.RIGHT);
            c.drawText(shelfTxt, 888 * k, cy, icoCap);
            icoCap.setColor(Ui.alpha(0xFFFFFFFF, 0.6f));
            icoCap.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("TODOS OS APPS", "ALL APPS", "TODAS LAS APPS"), 1032 * k, cy, icoCap);
            icoCap.setTextAlign(Paint.Align.RIGHT);
            c.drawText(icoTo.cols + Ui.tr(" COLUNAS · ", " COLUMNS · ", " COLUMNAS · ") + icoTo.rows + Ui.tr(" LINHAS À VISTA", " ROWS IN VIEW", " FILAS A LA VISTA"), 1824 * k, cy, icoCap);
            icoCap.setTextAlign(Paint.Align.LEFT);
            // group headers
            icoHead.setColor(LILY);
            c.drawText(Ui.tr("DISTRIBUIÇÃO", "LAYOUT", "DISTRIBUCIÓN"), 96 * k, 712 * k, icoHead);
            icoHead.setColor(PEACH);
            c.drawText(Ui.tr("FORMA", "SHAPE", "FORMA"), 1032 * k, 712 * k, icoHead);
            for (int i = 0; i < 6; i++) drawIconCard(c, i);
            // message
            if (icoLoaded) {
                String cr = icoTo.cols + " × " + icoTo.rows;
                String msg = null;
                int color = 0;
                if (shelfN > cupo) {
                    int d = shelfN - cupo;
                    msg = Ui.tr("Com " + cr + " cabem " + cupo + " apps fixados. " + (d == 1 ? "O último deixa de ficar fixado, mas continua em Todos os apps." : "Os " + d + " últimos deixam de ficar fixados, mas continuam em Todos os apps."),
                            "With " + cr + " you can pin " + cupo + " apps. " + (d == 1 ? "The last one is unpinned but stays in All apps." : "The last " + d + " are unpinned but stay in All apps."),
                            "Con " + cr + " caben " + cupo + " apps fijadas. " + (d == 1 ? "La última deja de estar fijada, pero sigue en Todas las apps." : "Las " + d + " últimas dejan de estar fijadas, pero siguen en Todas las apps."));
                    color = MELON;
                } else if (cupo > shelfN) {
                    int d = cupo - shelfN;
                    msg = Ui.tr("Com " + cr + " cabem " + cupo + " apps fixados: com o \"+\" você pode somar mais " + d + ".",
                            "With " + cr + " you can pin " + cupo + " apps: use the \"+\" to add " + d + " more.",
                            "Con " + cr + " caben " + cupo + " apps fijadas: con el \"+\" puedes sumar " + d + " más.");
                    color = LILY;
                }
                if (msg != null) {
                    icoMsg.setColor(color);
                    c.drawText(msg, w / 2f, 936 * k + Ui.halfCap(icoMsg), icoMsg);
                }
            }
            // footer pills
            String l1 = Ui.tr("Aplicar", "Apply", "Aplicar"), l2 = Ui.tr("Voltar", "Back", "Volver");
            float w1 = icoPillWidth("OK", l1), w2 = icoPillWidth(Ui.tr("Voltar", "Back", "Atrás"), l2), gap = 24 * k;
            float x0 = w / 2f - (w1 + w2 + gap) / 2f;
            drawIconsPill(c, x0, 1020 * k, "OK", l1);
            drawIconsPill(c, x0 + w1 + gap, 1020 * k, Ui.tr("Voltar", "Back", "Atrás"), l2);
        }

        private void openCredits() {
            credS = 780;   // the first pass starts with the logo in view
            credSpeed = 1;
            credKey = 0;
            setScreen(CREDITS);
            focus = 0;
            invalidate();
        }

        private boolean creditsKey(KeyEvent e, boolean down) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    credKey = down ? 1 : (credKey == 1 ? 0 : credKey);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    credKey = down ? -1 : (credKey == -1 ? 0 : credKey);
                    break;
                default:
                    break;
            }
            invalidate();
            return true;
        }

        private String creditsSpoken() {
            return Ui.tr("Sobre o Borealis. ", "About Borealis. ", "Acerca de Borealis. ")
                    + Ui.tr("Sua TV, sob uma nova luz. Desenhado e desenvolvido por Alogus Tov. Powered by Free My TV. Dados de filmes e séries do TMDB. Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB. Versão ",
                    "Your TV, under a new light. Designed and developed by Alogus Tov. Powered by Free My TV. Movie and TV data from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB. Version ",
                    "Tu TV, bajo una nueva luz. Diseñado y desarrollado por Alogus Tov. Powered by Free My TV. Datos de películas y series de TMDB. Este producto usa la API de TMDB, pero no está avalado ni certificado por TMDB. Versión ")
                    + versionName(getContext()) + ".";
        }

        private void credPaints() {
            if (cLabel != null) return;
            cLabel = Ui.text(Ui.bold, 19.5f, 0xFFFFFFFF);
            cLabel.setLetterSpacing(0.12f);
            cLabel.setTextAlign(Paint.Align.CENTER);
            cName = Ui.text(Logo.wordmark(getContext()), 60, 0xFFFFFFFF);
            cName.setTextAlign(Paint.Align.CENTER);
            cSupport = Ui.text(Ui.medium, 22.5f, 0xB3FFFFFF);
            cSupport.setTextAlign(Paint.Align.CENTER);
            cSlogan = Ui.text(Logo.wordmark(getContext()), 45, 0xFFFFFFFF);
            cSlogan.setTextAlign(Paint.Align.CENTER);
            cThanks = Ui.text(Logo.wordmark(getContext()), 51, 0xFFFFFFFF);
            cThanks.setTextAlign(Paint.Align.CENTER);
            cVer = Ui.text(Ui.semibold, 22.5f, 0xB3FFFFFF);
            cVer.setTextAlign(Paint.Align.CENTER);
            cFoot = Ui.text(Ui.semibold, 21, 0x99FFFFFF);
            cFoot.setTextAlign(Paint.Align.CENTER);
            cPill = Ui.text(Ui.semibold, 22.5f, 0xFFFFFFFF);
            String note = Ui.tr("Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    "Este producto usa la API de TMDB, pero no está avalado ni certificado por TMDB.");
            TextPaint tp = new TextPaint(cSupport);
            tp.setTextAlign(Paint.Align.LEFT);
            cNotice = StaticLayout.Builder.obtain(note, 0, note.length(), tp, 630).setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build();
        }

        /** The official TMDB logo (assets/tmdb.png, as TMDB provides it), or null when the file is not there yet. */
        private Bitmap tmdb() {
            if (!tmdbTried) {
                tmdbTried = true;
                try (java.io.InputStream in = getContext().getAssets().open("tmdb.png")) {
                    tmdbLogo = android.graphics.BitmapFactory.decodeStream(in);
                } catch (Exception | OutOfMemoryError e) {
                    tmdbLogo = null;
                }
            }
            return tmdbLogo;
        }

        private float credLabel(Canvas c, String text, int color, float y) {
            cLabel.setColor(color);
            c.drawText(text, 960, y + 19.5f, cLabel);
            return y + 19.5f;
        }

        /** Draws the roll with its top at y = 0 (1920 units, the canvas already scaled) and returns its height. */
        private float credContent(Canvas c, float seconds) {
            float cx = 960, y = 0, gap = 129;
            // 1. the "Borealis | TV" signature (symbol 117 tall), its Lily halo breathing behind it, and the slogan
            float breath = 0.75f + 0.25f * (0.5f + 0.5f * (float) Math.sin(seconds * 2 * Math.PI / 4));
            float hy = y + 58.5f;
            c.save();
            c.translate(cx, hy);
            c.scale(1f, 390f / 840f);   // the design's halo: 560 x 260 (840 x 390 here), Lily 26 % fading to nothing at its edge
            pPanel.setShader(new android.graphics.RadialGradient(0, 0, 420, new int[] {Ui.alpha(LILY, 0.26f * breath), 0x00B79CFE}, null, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, 420, pPanel);
            pPanel.setShader(null);
            c.restore();
            Logo.drawSignature(c, getContext(), cx, hy, 117, 2.25f, 1f);
            y += 117 + 39;
            c.drawText(Ui.tr("Sua TV, sob uma nova luz.", "Your TV, under a new light.", "Tu TV, bajo una nueva luz."), cx, y + 36, cSlogan);
            y += 45 + gap;
            // 2. design and development
            y = credLabel(c, Ui.tr("DESENHADO E DESENVOLVIDO POR", "DESIGNED AND DEVELOPED BY", "DISEÑADO Y DESARROLLADO POR"), LILY, y);
            c.drawText("Alogus Tov", cx, y + 18 + 48, cName);
            y += 18 + 60 + gap;
            // 3. powered by
            y = credLabel(c, "POWERED BY", PEACH, y);
            c.drawText("Free My TV", cx, y + 18 + 48, cName);
            y += 18 + 60 + 12;
            c.drawText(Ui.tr("O projeto de onde nasce o Borealis.", "The project Borealis is born from.", "El proyecto del que nace Borealis."), cx, y + 22.5f, cSupport);
            y += 22.5f + gap;
            // 4. TMDB
            y = credLabel(c, Ui.tr("DADOS DE FILMES E SÉRIES", "MOVIE AND TV DATA", "DATOS DE PELÍCULAS Y SERIES"), MELON, y);
            y += 24;
            Bitmap logo = tmdb();
            float lh = 69;
            if (logo != null) {
                float lw = lh * logo.getWidth() / logo.getHeight();
                tmp.set(cx - lw / 2, y, cx + lw / 2, y + lh);
                c.drawBitmap(logo, null, tmp, null);
            } else {   // the file is not in the app yet: a plain word, never a made-up logo
                c.drawText("TMDB", cx, y + 48, cName);
            }
            y += lh + 24;
            c.save();
            c.translate(cx - 315, y);
            cNotice.draw(c);
            c.restore();
            y += cNotice.getHeight() + gap;
            // 5. typefaces
            y = credLabel(c, Ui.tr("TIPOGRAFIAS", "TYPEFACES", "TIPOGRAFÍAS"), LILY, y);
            c.drawText("Gloock · Montserrat", cx, y + 18 + 48, cName);
            y += 18 + 60 + 12;
            c.drawText("SIL Open Font License", cx, y + 22.5f, cSupport);
            y += 22.5f + gap;
            // 6. three seals
            String[] seals = {Ui.tr("Grátis", "Free", "Gratis"), Ui.tr("Sem rastreadores", "No trackers", "Sin rastreadores"), Ui.tr("Sem contas", "No accounts", "Sin cuentas")};
            int[] dots = {LILY, PEACH, MELON};
            float total = 0, pg = 18, ph = 51;
            float[] pw = new float[3];
            for (int i = 0; i < 3; i++) {
                pw[i] = 24 + 12 + 12 + cPill.measureText(seals[i]) + 24;
                total += pw[i] + (i > 0 ? pg : 0);
            }
            float x = cx - total / 2;
            for (int i = 0; i < 3; i++) {
                tmp.set(x, y, x + pw[i], y + ph);
                pPanel.setColor(0x12FFFFFF);
                c.drawRoundRect(tmp, ph / 2, ph / 2, pPanel);
                pPanel.setColor(dots[i]);
                c.drawCircle(x + 24 + 6, y + ph / 2, 6, pPanel);
                c.drawText(seals[i], x + 24 + 12 + 12, y + ph / 2 + Ui.halfCap(cPill), cPill);
                x += pw[i] + pg;
            }
            y += ph + gap;
            // 7. thanks and version
            c.drawText(Ui.tr("Obrigado por usar o Borealis", "Thank you for using Borealis", "Gracias por usar Borealis"), cx, y + 40, cThanks);
            y += 51 + 18;
            c.drawText(Ui.tr("Versão ", "Version ", "Versión ") + versionName(getContext()), cx, y + 22.5f, cVer);
            return y + 22.5f;
        }

        private void drawCredits(Canvas c, float dt, float seconds) {
            credPaints();
            float target = credKey == 1 ? 4f : credKey == -1 ? -4f : 1f;
            credSpeed += (target - credSpeed) * Math.min(1f, dt * 8f);
            credS += 95f * credSpeed * dt;
            float loop = credH + 1080;
            if (credS >= loop) credS -= loop;
            if (credS < 0) credS += loop;
            c.drawColor(android.graphics.Color.argb(39, 23, 21, 25));   // a little darker than the usual veil, to read better
            c.save();
            c.scale(k, k);
            c.clipRect(0, 0, 1920, 1080);
            c.save();
            c.translate(0, 1080 - credS);
            credH = credContent(c, seconds);
            c.restore();
            // the edges fade: 195 at the top, 225 at the bottom, from #1c1920 to nothing
            pPanel.setShader(new LinearGradient(0, 0, 0, 195, 0xFF1C1920, 0x001C1920, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1920, 195, pPanel);
            pPanel.setShader(new LinearGradient(0, 1080 - 225, 0, 1080, 0x001C1920, 0xFF1C1920, Shader.TileMode.CLAMP));
            c.drawRect(0, 1080 - 225, 1920, 1080, pPanel);
            pPanel.setShader(null);
            c.drawText(Ui.tr("“Baixo” · Acelerar     “Cima” · Voltar o rolo     “Voltar” · Sair", "“Down” · Speed up     “Up” · Rewind     “Back” · Back",
                    "“Abajo” · Acelerar     “Arriba” · Retroceder     “Atrás” · Volver"), 960, 1080 - 45, cFoot);
            c.restore();
        }

        // MARK: - Sonido de los clics (subpanel of Apariencia; see the design delivery)

        private static final int[] CLK_LEVELS = {0, 20, 40, 60, 80, 100};
        private boolean clkOpen, clkOnCards;
        private int clkCard;
        private long clkT0;
        private Paint cHdr, cCardN, cCardD, cLbl, cLblB, cWarn;
        private final java.util.Map<String, float[]> waves = new java.util.HashMap<>();

        private static String clickName(int i) {
            switch (i) {
                case 0: return "Aurora";
                case 1: return "Cometa";
                case 2: return "Marte";
                case 3: return Ui.tr("Púlsar", "Pulsar", "Púlsar");
                default: return "Polaris";
            }
        }

        private static String clickDesc(int i) {
            switch (i) {
                case 0: return Ui.tr("Cristalino", "Crystalline", "Cristalino");
                case 1: return Ui.tr("Redondo", "Round", "Redondo");
                case 2: return Ui.tr("Quente", "Warm", "Cálido");
                case 3: return Ui.tr("Mínimo", "Minimal", "Mínimo");
                default: return Ui.tr("Neutro", "Neutral", "Neutro");
            }
        }

        private static final int[] CLK_COLORS = {LILY, PEACH, MELON, 0xFFFFFFFF, 0xFFD9CCFF};

        private int clickIndex() {
            return Math.max(0, java.util.Arrays.asList(Prefs.CLICK_SOUNDS).indexOf(Prefs.clickSound()));
        }

        private int levelIndex() {
            int l = Prefs.clickLevel(), best = 0;
            for (int i = 0; i < CLK_LEVELS.length; i++) if (Math.abs(CLK_LEVELS[i] - l) < Math.abs(CLK_LEVELS[best] - l)) best = i;
            return best;
        }

        private String clicksValue() {
            int l = Prefs.clickLevel();
            return l <= 0 ? Ui.tr("Desligados", "Off", "Apagados") : clickName(clickIndex()) + " · " + l + " %";
        }

        private void openClicks() {
            clkOpen = true;
            clkOnCards = false;
            clkCard = clickIndex();
            clkT0 = SystemClock.uptimeMillis();
            click().loadAll();
            invalidate();
            spoken.focusMoved();
        }

        private void closeClicks() {
            clkOpen = false;
            clkT0 = SystemClock.uptimeMillis();
            invalidate();
            spoken.focusMoved();
        }

        private boolean clicksKey(KeyEvent e, boolean down) {
            int code = e.getKeyCode();
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) closeClicks();
                    backDown = false;
                }
                return true;
            }
            if (ok) {
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled() && spoken.keyClick() && clkOnCards) {
                        Prefs.setClickSound(Prefs.CLICK_SOUNDS[clkCard]);
                        click().preview(Prefs.CLICK_SOUNDS[clkCard], true);
                        invalidate();
                        spoken.focusMoved();
                    }
                    okDown = false;
                }
                return true;
            }
            if (!down) return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    int d = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    if (clkOnCards) {
                        int n = Prefs.CLICK_SOUNDS.length;
                        clkCard = (clkCard + d + n) % n;
                        click().preview(Prefs.CLICK_SOUNDS[clkCard], false);   // focusing a card is hearing it
                    } else {
                        int at = Math.max(0, Math.min(CLK_LEVELS.length - 1, levelIndex() + d));
                        if (CLK_LEVELS[at] != Prefs.clickLevel()) {
                            Prefs.setClickLevel(CLK_LEVELS[at]);
                            click().preview(Prefs.clickSound(), false);   // at the new volume
                        }
                    }
                    break;
                }
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (!clkOnCards) {
                        clkOnCards = true;
                        clkCard = clickIndex();
                        click().preview(Prefs.CLICK_SOUNDS[clkCard], false);
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    clkOnCards = false;
                    break;
                default:
                    return false;
            }
            invalidate();
            spoken.focusMoved();
            return true;
        }

        /** The shape of a sound (its OK click): 24 bars of its loudness, read from the WAV. */
        private float[] waveOf(String name) {
            float[] w = waves.get(name);
            if (w != null) return w;
            w = new float[24];
            try (java.io.InputStream in = getContext().getAssets().open("sounds/" + name + "_ok.wav")) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
                byte[] d = bo.toByteArray();
                int start = 44;
                for (int i = 12; i + 8 < d.length; ) {   // the "data" chunk
                    int len = (d[i + 4] & 255) | (d[i + 5] & 255) << 8 | (d[i + 6] & 255) << 16 | (d[i + 7] & 255) << 24;
                    if (d[i] == 'd' && d[i + 1] == 'a' && d[i + 2] == 't' && d[i + 3] == 'a') {
                        start = i + 8;
                        break;
                    }
                    i += 8 + len;
                }
                int samples = (d.length - start) / 2;
                float peak = 1;
                for (int b = 0; b < w.length; b++) {
                    int from = samples * b / w.length, to = Math.max(from + 1, samples * (b + 1) / w.length);
                    float m = 0;
                    for (int s = from; s < to; s++) {
                        int at = start + s * 2;
                        if (at + 1 >= d.length) break;
                        m = Math.max(m, Math.abs((short) ((d[at] & 255) | (d[at + 1] << 8))));
                    }
                    w[b] = m;
                    peak = Math.max(peak, m);
                }
                for (int b = 0; b < w.length; b++) w[b] = Math.max(0.06f, w[b] / peak);
            } catch (Exception e) {
                java.util.Arrays.fill(w, 0.3f);
            }
            waves.put(name, w);
            return w;
        }

        private void clkPaints() {
            if (cHdr != null && cHdr.getTextSize() == 19.5f * k) return;
            cHdr = Ui.text(Ui.bold, 19.5f * k, LILY);
            cHdr.setLetterSpacing(0.08f);
            cCardN = Ui.text(Ui.bold, 25.5f * k, 0xFFFFFFFF);
            cCardD = Ui.text(Ui.medium, 18 * k, 0xB3FFFFFF);
            cLbl = Ui.text(Ui.medium, 19.5f * k, 0x99FFFFFF);
            cLbl.setTextAlign(Paint.Align.CENTER);
            cLblB = Ui.text(Ui.bold, 19.5f * k, 0xFFFFFFFF);
            cLblB.setTextAlign(Paint.Align.CENTER);
            cWarn = Ui.text(Ui.semibold, 22.5f * k, MELON);
        }

        /** Apariencia while its sound picker opens or closes: the rows go back (alpha, scale) and the picker grows from the Clics row. */
        private void drawAppearanceWithClicks(Canvas c, int g, float seconds) {
            clkPaints();
            profPaints();
            long now = SystemClock.uptimeMillis(), el = now - clkT0;
            float ox = (P_L + 368 * 1.5f) * k, oy = (P_T + 400 * 1.5f) * k;
            float ca = 1, cs = 1, ga = 0, gs = 1;
            if (clkOpen) {
                float a = pc(el / 200f), gg = pc(el / 320f);
                ca = 1 - a;
                cs = 1 - 0.06f * a;
                ga = gg;
                gs = 0.55f + 0.45f * gg;
            } else if (el < 340) {
                float gg = pc(el / 220f), a = pc((el - 80) / 260f);
                ga = 1 - gg;
                gs = 1 - 0.45f * gg;
                ca = a;
                cs = 0.94f + 0.06f * a;
            } else {
                ca = 1;
            }
            if (ca > 0.01f) {
                c.save();
                c.scale(cs, cs, ox, oy);
                int layer = c.saveLayerAlpha(P_L * k, P_T * k, (P_L + P_W) * k, (P_T + P_H) * k, Math.round(255 * ca));
                c.drawText(groupInfo()[g - 1][0], (P_L + 38) * k, (P_T + 78) * k, pPanelTitle);
                drawPanelRows(c, g, seconds);
                c.restoreToCount(layer);
                c.restore();
            }
            if (ga > 0.01f) {
                c.save();
                c.scale(gs, gs, ox, oy);
                int layer = c.saveLayerAlpha(P_L * k, P_T * k, (P_L + P_W) * k, (P_T + P_H) * k, Math.round(255 * ga));
                drawClicksPanel(c);
                c.restoreToCount(layer);
                c.restore();
            }
        }

        private void drawClicksPanel(Canvas c) {
            float px = P_L * k, py = P_T * k;
            c.drawText(Ui.tr("Som dos cliques", "Click sounds", "Sonido de los clics"), px + 38 * k, py + 78 * k, pPanelTitle);
            c.drawText(Ui.tr("Foque um som para ouvi-lo. OK o escolhe.", "Focus a sound to hear it. OK chooses it.", "Enfoca un sonido para escucharlo. OK lo elige."),
                    px + 38 * k, py + 118 * k, pDesc);
            // volume
            c.drawText(Ui.tr("VOLUME", "VOLUME", "VOLUMEN"), px + 57 * k, py + 205 * k, cHdr);
            int level = levelIndex();
            float bx = px + 57 * k, by = py + 234 * k, sw = 154.5f * k, sh = 66 * k, sg = 12 * k;
            String[] labels = {Ui.tr("Desligado", "Off", "Apagado"), "20 %", "40 %", "60 %", "80 %", "100 %"};
            float gl = bx + sw + sg;   // the gradient is one piece over the five stops
            LinearGradient grad = new LinearGradient(gl, 0, gl + 5 * sw + 4 * sg, 0, new int[] {LILY, PEACH, MELON}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            for (int i = 0; i < 6; i++) {
                float x = bx + i * (sw + sg);
                tmp.set(x, by, x + sw, by + sh);
                if (i == 0) {
                    pPanel.setShader(null);
                    pPanel.setColor(level == 0 ? Ui.alpha(MELON, 0.28f) : 0x1AFFFFFF);
                } else if (i <= level) {
                    pPanel.setShader(grad);
                    pPanel.setColor(0xFFFFFFFF);
                } else {
                    pPanel.setShader(null);
                    pPanel.setColor(0x1AFFFFFF);
                }
                c.drawRoundRect(tmp, 21 * k, 21 * k, pPanel);
                pPanel.setShader(null);
                if (i == 0) {   // the muted speaker
                    int col = level == 0 ? MELON : 0xCCFFFFFF;
                    Symbols.draw(getContext(), c, "speaker", tmp.centerX(), tmp.centerY(), 33 * k, col);
                    ringPaint.setStyle(Paint.Style.STROKE);
                    ringPaint.setShader(null);
                    ringPaint.setColor(col);
                    ringPaint.setStrokeWidth(3.6f * k);
                    ringPaint.setStrokeCap(Paint.Cap.ROUND);
                    c.drawLine(tmp.centerX() - 15 * k, tmp.centerY() - 15 * k, tmp.centerX() + 15 * k, tmp.centerY() + 15 * k, ringPaint);
                    ringPaint.setStrokeCap(Paint.Cap.BUTT);
                    ringPaint.setColor(0xFFFFFFFF);
                }
                c.drawText(labels[i], tmp.centerX(), tmp.bottom + 13.5f * k + 15 * k, i == level ? cLblB : cLbl);
            }
            if (!clkOnCards) {   // the focus: a gradient ring on the level in use
                float x = bx + level * (sw + sg);
                tmp.set(x - 10.5f * k, by - 10.5f * k, x + sw + 10.5f * k, by + sh + 10.5f * k);   // 6 of air + the 4.5 ring
                gradientRing(c, tmp, 30 * k, 4.5f * k, 1f);
            }
            // sounds
            c.drawText(Ui.tr("SOM", "SOUND", "SONIDO"), px + 57 * k, py + 414 * k, cHdr == null ? pPanel : cHdr);
            float cw = 183 * k, ch = 264 * k, cgap = 18 * k, cy0 = py + 450 * k;
            boolean off = Prefs.clickLevel() == 0;
            int cur = clickIndex();
            for (int i = 0; i < 5; i++) {
                float x = bx + i * (cw + cgap);
                tmp.set(x, cy0, x + cw, cy0 + ch);
                int layer = off ? c.saveLayerAlpha(x - 12 * k, cy0 - 12 * k, x + cw + 12 * k, cy0 + ch + 12 * k, 128) : -1;
                pPanel.setColor(i == cur ? 0xFF26212B : 0x12FFFFFF);
                c.drawRoundRect(tmp, 30 * k, 30 * k, pPanel);
                float[] wv = waveOf(Prefs.CLICK_SOUNDS[i]);
                float ww = 138 * k, wh = 66 * k, wl = x + (cw - ww) / 2, wc = cy0 + 36 * k + wh / 2, bw = ww / wv.length;
                pPanel.setColor(CLK_COLORS[i]);
                for (int b = 0; b < wv.length; b++) {
                    float hh = Math.max(4.5f * k, wh * wv[b]);
                    tmp2.set(wl + b * bw + bw * 0.2f, wc - hh / 2, wl + (b + 1) * bw - bw * 0.2f, wc + hh / 2);
                    c.drawRoundRect(tmp2, bw * 0.3f, bw * 0.3f, pPanel);
                }
                c.drawText(clickName(i), x + 21 * k, cy0 + 168 * k, cCardN);
                c.drawText(clickDesc(i), x + 21 * k, cy0 + 204 * k, cCardD);
                if (clkOnCards && i == clkCard) {
                    tmp.set(x - 6 * k, cy0 - 6 * k, x + cw + 6 * k, cy0 + ch + 6 * k);
                    gradientRing(c, tmp, 36 * k, 4.5f * k, 1f);
                }
                if (i == cur) drawLilyCheck(c, x + cw - 30 * k, cy0 + 30 * k, 22.5f * k);
                if (layer >= 0) c.restoreToCount(layer);
            }
            if (off) {
                Paint.Align was = cWarn.getTextAlign();
                cWarn.setTextAlign(Paint.Align.CENTER);
                c.drawText(Ui.tr("Os cliques estão desligados. Aumente o volume para ouvi-los.", "Clicks are off. Turn the volume up to hear them.",
                        "Los clics están apagados. Sube el volumen para escucharlos."), px + P_W * k / 2, cy0 + ch + 54 * k, cWarn);
                cWarn.setTextAlign(was);
            }
            // the three hints
            String[] hints = {Ui.tr("Esq./Dir. · Volume", "Left/Right · Volume", "Izq./Der. · Volumen"), Ui.tr("OK · Escolher som", "OK · Choose sound", "OK · Elegir sonido"),
                    Ui.tr("Voltar · Voltar", "Back · Back", "Atrás · Volver")};
            float ph = 60 * k, gap = 18 * k, total = 0;
            float[] wd = new float[3];
            for (int i = 0; i < 3; i++) {
                wd[i] = pPillT.measureText(hints[i]) + 60 * k;
                total += wd[i] + (i > 0 ? gap : 0);
            }
            float x0 = px + P_W * k / 2 - total / 2, pyy = py + (P_H - 33) * k - ph;
            for (int i = 0; i < 3; i++) {
                pPanel.setColor(0x14FFFFFF);
                tmp.set(x0, pyy, x0 + wd[i], pyy + ph);
                c.drawRoundRect(tmp, ph / 2, ph / 2, pPanel);
                c.drawText(hints[i], tmp.centerX() - pPillT.measureText(hints[i]) / 2, tmp.centerY() + Ui.halfCap(pPillT), pPillT);
                x0 += wd[i] + gap;
            }
        }

        // MARK: - The two-column layout (the design's 1280 x 720 board x 1.5)

        // The design's group icons: stroked drawings on a 24-unit board (palette, monitor, sliders, remote, sun).
        private static final String[][] ICONS = {
            {"M12 3a9 9 0 1 0 0 18c1.2 0 2-.8 2-1.8 0-1.4-1.2-1.6-1.2-2.8 0-1 .8-1.6 1.8-1.6H17a4 4 0 0 0 4-4C21 6.7 17 3 12 3z",
                "M6.5 11a1 1 0 1 0 2 0a1 1 0 1 0-2 0z", "M9 7.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0z", "M13.5 7.5a1 1 0 1 0 2 0a1 1 0 1 0-2 0z"},
            {"M5 4h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M5 19h4M12 19h7"},
            {"M4 7h9M17 7h3M4 17h3M11 17h9", "M13 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0z", "M7 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0z"},
            {"M12 3a4 4 0 0 1 4 4v10a4 4 0 0 1-4 4a4 4 0 0 1-4-4V7a4 4 0 0 1 4-4z", "M10.8 8a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0-2.4 0z", "M12 12v3"},
            {"M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0z", "M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1"},
            {"M12 21c0 0-7.5-4.6-9.6-9.2C.9 8.3 2.8 4.5 6.5 4.5c2.1 0 3.9 1.2 5.5 3.2 1.6-2 3.4-3.2 5.5-3.2 3.7 0 5.6 3.8 4.1 7.3C19.5 16.4 12 21 12 21z"}};
        private final android.graphics.Path iconPath = new android.graphics.Path();
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private void drawGroupIcon(Canvas c, int g, float cx, float cy, float size, int color) {
            float sc = size / 24f;
            c.save();
            c.translate(cx - size / 2, cy - size / 2);
            c.scale(sc, sc);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(1.7f);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            iconPaint.setColor(color);
            for (String d : ICONS[g - 1]) {
                iconPath.rewind();
                svgPath(iconPath, d);
                c.drawPath(iconPath, iconPaint);
            }
            c.restore();
        }

        /** A small SVG path reader: M L H V C A Z, absolute and relative (all the icons need). */
        private static void svgPath(android.graphics.Path p, String d) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[MmLlHhVvCcAaZz]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)").matcher(d);
            List<String> t = new ArrayList<>();
            while (m.find()) t.add(m.group());
            int i = 0;
            char cmd = 0;
            float x = 0, y = 0, sx = 0, sy = 0;
            while (i < t.size()) {
                String tk = t.get(i);
                if (Character.isLetter(tk.charAt(0))) {
                    cmd = tk.charAt(0);
                    i++;
                    if (cmd == 'z' || cmd == 'Z') {
                        p.close();
                        x = sx;
                        y = sy;
                    }
                    continue;
                }
                boolean rel = Character.isLowerCase(cmd);
                switch (Character.toUpperCase(cmd)) {
                    case 'M': {
                        float nx = Float.parseFloat(t.get(i)) + (rel ? x : 0), ny = Float.parseFloat(t.get(i + 1)) + (rel ? y : 0);
                        i += 2;
                        p.moveTo(nx, ny);
                        x = sx = nx;
                        y = sy = ny;
                        cmd = rel ? 'l' : 'L';
                        break;
                    }
                    case 'L': {
                        float nx = Float.parseFloat(t.get(i)) + (rel ? x : 0), ny = Float.parseFloat(t.get(i + 1)) + (rel ? y : 0);
                        i += 2;
                        p.lineTo(nx, ny);
                        x = nx;
                        y = ny;
                        break;
                    }
                    case 'H': {
                        float nx = Float.parseFloat(t.get(i)) + (rel ? x : 0);
                        i++;
                        p.lineTo(nx, y);
                        x = nx;
                        break;
                    }
                    case 'V': {
                        float ny = Float.parseFloat(t.get(i)) + (rel ? y : 0);
                        i++;
                        p.lineTo(x, ny);
                        y = ny;
                        break;
                    }
                    case 'C': {
                        float[] v = new float[6];
                        for (int j = 0; j < 6; j++) v[j] = Float.parseFloat(t.get(i + j)) + (rel ? (j % 2 == 0 ? x : y) : 0);
                        i += 6;
                        p.cubicTo(v[0], v[1], v[2], v[3], v[4], v[5]);
                        x = v[4];
                        y = v[5];
                        break;
                    }
                    case 'A': {
                        float rx = Float.parseFloat(t.get(i)), ry = Float.parseFloat(t.get(i + 1));
                        boolean large = Float.parseFloat(t.get(i + 3)) != 0, sweep = Float.parseFloat(t.get(i + 4)) != 0;
                        float nx = Float.parseFloat(t.get(i + 5)) + (rel ? x : 0), ny = Float.parseFloat(t.get(i + 6)) + (rel ? y : 0);
                        i += 7;
                        svgArc(p, x, y, rx, ry, large, sweep, nx, ny);
                        x = nx;
                        y = ny;
                        break;
                    }
                    default:
                        i++;
                }
            }
        }

        /** An SVG elliptical arc (no rotation) from (x0, y0) to (x, y), as Path.arcTo wants it: a box and angles. */
        private static void svgArc(android.graphics.Path p, float x0, float y0, float rxIn, float ryIn, boolean large, boolean sweep, float x, float y) {
            double rx = Math.abs(rxIn), ry = Math.abs(ryIn);
            if (rx == 0 || ry == 0) {
                p.lineTo(x, y);
                return;
            }
            double dx = (x0 - x) / 2.0, dy = (y0 - y) / 2.0;
            double lam = dx * dx / (rx * rx) + dy * dy / (ry * ry);
            if (lam > 1) {
                double s = Math.sqrt(lam);
                rx *= s;
                ry *= s;
            }
            double num = rx * rx * ry * ry - rx * rx * dy * dy - ry * ry * dx * dx, den = rx * rx * dy * dy + ry * ry * dx * dx;
            double co = Math.sqrt(Math.max(0, num / den));
            if (large == sweep) co = -co;
            double cxp = co * rx * dy / ry, cyp = -co * ry * dx / rx;
            double cx = cxp + (x0 + x) / 2.0, cy = cyp + (y0 + y) / 2.0;
            double th1 = Math.atan2((dy - cyp) / ry, (dx - cxp) / rx);
            double th2 = Math.atan2((-dy - cyp) / ry, (-dx - cxp) / rx);
            double dth = th2 - th1;
            if (sweep && dth < 0) dth += 2 * Math.PI;
            else if (!sweep && dth > 0) dth -= 2 * Math.PI;
            p.arcTo(new RectF((float) (cx - rx), (float) (cy - ry), (float) (cx + rx), (float) (cy + ry)), (float) Math.toDegrees(th1), (float) Math.toDegrees(dth));
        }

        private static final float P_L = 720, P_T = 72, P_W = 1104, P_H = 936, P_ROWS_T = 177, P_ROW_H = 84, P_PITCH = 90, P_SECT = 66;
        private static final float C_L = 96, C_T = 165, C_W = 570, C_H = 111, C_GAP = 15, SUPPORT_EXTRA = 18;   // six cards: the five groups and, after a thin line, "Apoya a Borealis"
        /** The rows of Sistema are a little shorter (81 / 87) so that its five rows and the status fit. */
        private static float rowH(int g) {
            return g == 5 ? 81 : P_ROW_H;
        }

        private static float rowPitch(int g) {
            return g == 5 ? 87 : P_PITCH;
        }

        private static final int SUPPORT = 5;   // the sixth card: Apoya a Borealis

        /** Left column (1920 units): "Mi perfil" (focus -1) on top, then the five groups, a thin line and "Apoya a Borealis". */
        private static float cardTop(int row) {
            if (row < 0) return 165;
            if (row == SUPPORT) return 870;
            return 312 + row * 108;
        }

        private static float cardH(int row) {
            return row < 0 ? 126 : 96;
        }
        private final Spring panelScroll = new Spring(0.28f, 0.9f, 0);
        private int lastCard = 1, panelShown = -1;
        private float secX, secR;

        /** The first screen and the five groups share one layout: the groups on the left, the open group's rows in a panel. */
        private boolean twoCol() {
            return screen == MAIN || screen == GROUP || screen == REMOTE_KEYS;
        }

        /** The group the panel shows: the one under the cursor on the first screen, the open one inside a group. */
        private int shownGroup() {
            if (screen == GROUP) return group;
            if (screen == REMOTE_KEYS) return 4;
            int[] o = groupOrder();
            if (screen == MAIN && focus >= 0 && focus < o.length) lastCard = o[focus];
            return lastCard;
        }

        private int[] panelIds(int g) {
            if (g == 4) return null;
            int was = group;
            group = g;
            int[] r = groupIds();
            group = was;
            return r;
        }

        /** A heading that starts a block of rows (a divider and a small title above it), or null. */
        private String sectionOf(int g, int id) {
            if (g == 1 && id == WALLPAPER_ROW) return Ui.tr("TELA", "DISPLAY", "PANTALLA");
            if (g == 1 && id == ID_SOUND) return Ui.tr("SONS", "SOUNDS", "SONIDOS");
            if (g == 3 && id == ID_WELCOME) return Ui.tr("BOAS-VINDAS", "WELCOME", "BIENVENIDA");
            if (g == 2 && id == 5) return Ui.tr("INÍCIO", "HOME", "INICIO");
            if (g == 2 && id == 7) return Ui.tr("WIDGETS", "WIDGETS", "WIDGETS");
            return null;
        }

        /** Top of a panel row in 1920-wide units, with the panel not scrolled. */
        private float rowTopAt(int g, int i) {
            float t = P_ROWS_T + i * rowPitch(g);
            int[] ids = panelIds(g);
            if (ids != null) for (int j = 0; j <= i && j < ids.length; j++) if (sectionOf(g, ids[j]) != null) t += P_SECT;
            if (g == 4 && i >= Prefs.KEYS.length) t += P_SECT;   // the remote's own settings button sits under a heading
            return t;
        }

        private static String[] plus(String[] a, String extra) {
            String[] r = java.util.Arrays.copyOf(a, a.length + 1);
            r[a.length] = extra;
            return r;
        }

        private Boolean toggleOf(int id) {
            switch (id) {
                case ID_SPLASH: return Prefs.splash();
                case ID_SOUND: return Prefs.startupSound();
                case ID_UPDATE_AUTO: return Prefs.updateAuto();
                case 7: return Prefs.weather();
                default: return null;
            }
        }

        private void gradientRing(Canvas c, RectF box, float radius, float width, float alpha) {
            float half = width / 2;
            tmp2.set(box.left + half, box.top + half, box.right - half, box.bottom - half);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(width);
            ringPaint.setShader(new LinearGradient(box.left, 0, box.right, 0, new int[] {LILY, PEACH, MELON}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            ringPaint.setAlpha(Math.round(255 * Ui.clamp(alpha)));
            c.drawRoundRect(tmp2, radius - half, radius - half, ringPaint);
            ringPaint.setShader(null);
            ringPaint.setAlpha(255);
        }

        private void drawTwoColumns(Canvas c, float seconds) {
            int g = shownGroup();
            if (panelShown != g) {
                panelShown = g;
                panelScroll.snap(0);
            }
            c.drawText(Ui.tr("Configurações", "Settings", "Ajustes"), C_L * k, 128 * k, pTitle);
            drawCards(c, g);
            drawPanel(c, g, seconds);
        }

        /** The five groups, top left: the open one has the gradient border; the cursor (first screen) moves it. */
        private void drawCards(Canvas c, int shown) {
            String[][] info = groupInfo();
            int[] tints = {LILY, PEACH, MELON, LILY, PEACH};
            int[] order = groupOrder();
            for (int i = 0; i < order.length; i++) {
                int g = order[i];
                float top = cardTop(i) * k;
                rowRect.set(C_L * k, top, (C_L + C_W) * k, top + cardH(i) * k);
                boolean onSupport = screen == MAIN && (focus == SUPPORT || focus == -1);
                boolean sel = g == shown && !onSupport;
                float f = !sel ? 0 : screen == MAIN ? Ui.clamp(pop[i].value) : (focus == -2 ? Ui.clamp(wpBackPop.value) : 0);
                float grow = 4 * k * f;
                tmp.set(rowRect.left - grow, rowRect.top - grow, rowRect.right + grow, rowRect.bottom + grow);
                float r = 27 * k;
                if (f > 0.01f) {
                    tmp.offset(0, 8 * k * f);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.35f * f));
                    c.drawRoundRect(tmp, r, r, shadow);
                    tmp.offset(0, -8 * k * f);
                }
                if (sel) {
                    pPanel.setColor(0xFF26212B);
                    c.drawRoundRect(tmp, r, r, pPanel);
                    blinkFill(c, tmp, r, f);
                    gradientRing(c, tmp, r, 4.5f * k, 1f);
                } else {
                    pPanel.setColor(g == shown && !(screen == MAIN && focus == -1) ? 0x24FFFFFF : 0x12FFFFFF);   // the group whose rows the panel shows, while the cursor is on "Apoya a Borealis"
                    c.drawRoundRect(tmp, r, r, pPanel);
                }
                float cy = tmp.centerY();
                drawGroupIcon(c, g, tmp.left + 46 * k, cy, 36 * k, tints[g - 1]);
                float tx = tmp.left + 86 * k;
                c.drawText(info[g - 1][0], tx, cy - 3 * k, sel ? pLeftTB : pLeftT);
                c.drawText(TextUtils.ellipsize(info[g - 1][1], new TextPaint(pLeftS), tmp.right - 24 * k - tx, TextUtils.TruncateAt.END).toString(), tx, cy + 24 * k, pLeftS);
            }
            drawSupportCard(c);
            drawProfileCard(c);
        }

        private Paint supDesc, supTag;

        /** "Apoya a Borealis" in the panel: a centered title, one line, then the code in a ring (link and label centered under it) and three narrow reasons. */
        private void drawSupportPanel(Canvas c) {
            if (supDesc == null || supDesc.getTextSize() != 24 * k) {
                supDesc = Ui.text(Ui.medium, 24 * k, 0xD1FFFFFF);
                supDesc.setTextAlign(Paint.Align.CENTER);
                supTag = Ui.text(Ui.semibold, 19.5f * k, 0xB3FFFFFF);
                supTag.setTextAlign(Paint.Align.CENTER);
            }
            float t = SystemClock.uptimeMillis() / 1000f;
            int[] tint = {LILY, PEACH, MELON};
            float pcx = (P_L + P_W / 2f) * k;
            c.save();
            c.clipRect((P_L + 6) * k, (P_T + 6) * k, (P_L + P_W - 6) * k, (P_T + P_H - 14) * k);
            // the title, centered with a beating heart before it (this panel is not a list, so it does not start at the left)
            float tw = pPanelTitle.measureText(supportTitle());
            float beat = 1 + 0.1f * (float) Math.sin(t * 4.5f), hs = 20 * k * beat;
            iconPaint.setStyle(Paint.Style.FILL);
            iconPaint.setColor(PEACH);
            c.save();
            float tStart = pcx - (34 * k + tw) / 2;
            c.translate(tStart + 14 * k, (P_T + 62) * k);
            c.scale(hs, hs);
            iconPath.rewind();
            iconPath.moveTo(0, 0.9f);
            iconPath.cubicTo(-1.2f, 0, -0.9f, -0.9f, 0, -0.35f);
            iconPath.cubicTo(0.9f, -0.9f, 1.2f, 0, 0, 0.9f);
            iconPath.close();
            c.drawPath(iconPath, iconPaint);
            c.restore();
            c.drawText(supportTitle(), tStart + 34 * k, (P_T + 78) * k, pPanelTitle);
            c.drawText(Ui.tr("O Borealis é grátis e feito por uma só pessoa. Se você gosta, pode ajudar.",
                    "Borealis is free and made by one person. If you like it, you can help.",
                    "Borealis es gratis y lo hace una sola persona. Si te gusta, puedes ayudar."), pcx, (P_T + 140) * k, supDesc);

            float groupW = 1011, gl = P_L + P_W / 2f - groupW / 2f;
            float qs = 345 * k, qcx = (gl + 202.5f) * k, qx = qcx - qs / 2, qy = (P_T + 250) * k;
            // hearts rising around the code
            for (int h = 0; h < 10; h++) {
                float ph = (t * 0.3f + h / 10f) % 1f;
                float hx = qcx + (float) Math.sin(h * 7.3) * qs * 0.62f + (float) Math.sin(t * 1.4f + h) * 14 * k;
                float hy = qy + qs + 30 * k - ph * (qs + 120 * k), sz = (10 + (h % 3) * 5) * k;
                iconPaint.setColor(Ui.alpha(tint[h % 3], (float) Math.sin(Math.PI * ph) * 0.85f));
                c.save();
                c.translate(hx, hy);
                c.scale(sz, sz);
                c.drawPath(iconPath, iconPaint);
                c.restore();
            }
            // the code on a white card inside the gradient ring
            tmp.set(qx - 14 * k, qy - 14 * k, qx + qs + 14 * k, qy + qs + 14 * k);
            pPanel.setColor(0xFFFFFFFF);
            c.drawRoundRect(tmp, 46 * k, 46 * k, pPanel);
            gradientRing(c, tmp, 46 * k, 8 * k, 1f);
            String[] qr = NoticeView.SPONSOR_QR;
            float cell = (qs - 40 * k) / qr.length, ox = qx + 20 * k, oy = qy + 20 * k;
            pPanel.setColor(Brand.INK);
            for (int y = 0; y < qr.length; y++)
                for (int x = 0; x < qr[y].length(); x++)
                    if (qr[y].charAt(x) == '1') c.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell + 0.5f, oy + (y + 1) * cell + 0.5f, pPanel);
            c.drawText("GITHUB SPONSORS", qcx, qy + qs + 64 * k, supTag);
            Paint.Align was = pCardTB.getTextAlign();
            pCardTB.setTextAlign(Paint.Align.CENTER);
            c.drawText("github.com/sponsors/Alogus", qcx, qy + qs + 108 * k, pCardTB);
            pCardTB.setTextAlign(was);
            // three narrow reasons
            String[] titles = {Ui.tr("É voluntário", "It is voluntary", "Es voluntario"), Ui.tr("Não libera nada", "It unlocks nothing", "No desbloquea nada"), Ui.tr("Ajuda a continuar", "It keeps it going", "Ayuda a que siga")};
            String[] descs = {Ui.tr("Você decide se apoia e quanto.", "You decide if you help and how much.", "Tú decides si apoyar y cuánto."),
                    Ui.tr("O Borealis é igual para todos.", "Borealis is the same for everyone.", "Borealis es igual para todos."),
                    Ui.tr("Tempo e melhorias para o Borealis.", "Time and improvements for Borealis.", "Tiempo y mejoras para Borealis.")};
            float rx = (gl + 405 + 66) * k, rw = 540 * k, rh = 129 * k;
            for (int i = 0; i < 3; i++) {
                float ry = qy - 14 * k + i * (rh + 24 * k);
                tmp.set(rx, ry, rx + rw, ry + rh);
                pPanel.setColor(0x14FFFFFF);
                c.drawRoundRect(tmp, 33 * k, 33 * k, pPanel);
                float icx = rx + 66 * k, icy = ry + rh / 2;
                pPanel.setColor(tint[i]);
                c.drawCircle(icx, icy, 36 * k, pPanel);
                if (i == 0) {
                    float pu = 15 * k * beat;
                    iconPaint.setColor(Brand.INK);
                    c.save();
                    c.translate(icx, icy + k);
                    c.scale(pu, pu);
                    c.drawPath(iconPath, iconPaint);
                    c.restore();
                } else {
                    Symbols.draw(getContext(), c, i == 1 ? "check" : "sparkles", icx, icy, 32 * k, Brand.INK);
                }
                c.drawText(titles[i], rx + 126 * k, icy - 6 * k, pCardT);
                c.drawText(descs[i], rx + 126 * k, icy + 32 * k, supDescL());
            }
            c.restore();
        }

        private Paint supDescLeft;

        private Paint supDescL() {
            if (supDescLeft == null || supDescLeft.getTextSize() != 23 * k) supDescLeft = Ui.text(Ui.medium, 23 * k, 0xD1FFFFFF);
            return supDescLeft;
        }

        private String supportTitle() { return Ui.tr("Apoie o Borealis", "Support Borealis", "Apoya a Borealis"); }

        private String supportDesc() { return Ui.tr("Apoiar, contribuir ou doar", "Sponsor, contribute or donate", "Patrocinar, aportar o donar"); }

        /** The sixth card, after a thin line: a heart, "Apoya a Borealis". OK opens the same modal the welcome ends with. */
        private void drawSupportCard(Canvas c) {
            float top = cardTop(SUPPORT) * k;
            pPanel.setColor(0x24FFFFFF);
            c.drawRect((C_L + 18) * k, top - 15 * k - 1.5f * k, (C_L + C_W - 18) * k, top - 15 * k + 1.5f * k, pPanel);
            rowRect.set(C_L * k, top, (C_L + C_W) * k, top + cardH(SUPPORT) * k);
            boolean sel = screen == MAIN && focus == SUPPORT;
            float f = sel ? Ui.clamp(pop[SUPPORT].value) : 0, grow = 4 * k * f, r = 27 * k;
            tmp.set(rowRect.left - grow, rowRect.top - grow, rowRect.right + grow, rowRect.bottom + grow);
            if (f > 0.01f) {
                tmp.offset(0, 8 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.35f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -8 * k * f);
            }
            if (sel) {
                pPanel.setColor(0xFF26212B);
                c.drawRoundRect(tmp, r, r, pPanel);
                blinkFill(c, tmp, r, f);
                gradientRing(c, tmp, r, 4.5f * k, 1f);
            } else {
                pPanel.setColor(0x12FFFFFF);
                c.drawRoundRect(tmp, r, r, pPanel);
            }
            float cy = tmp.centerY();
            drawGroupIcon(c, 6, tmp.left + 46 * k, cy, 36 * k, PEACH);
            float tx = tmp.left + 86 * k;
            c.drawText(supportTitle(), tx, cy - 3 * k, sel ? pLeftTB : pLeftT);
            c.drawText(TextUtils.ellipsize(supportDesc(), new TextPaint(pLeftS), tmp.right - 24 * k - tx, TextUtils.TruncateAt.END).toString(), tx, cy + 24 * k, pLeftS);
        }

        private void drawPanel(Canvas c, int g, float seconds) {
            RectF box = tmp;
            box.set(P_L * k, P_T * k, (P_L + P_W) * k, (P_T + P_H) * k);
            float r = 42 * k;
            box.offset(0, 24 * k);
            shadow.setColor(0x8C000000);
            c.drawRoundRect(box, r, r, shadow);
            box.offset(0, -24 * k);
            pPanel.setColor(Brand.ink(0xE6));
            c.drawRoundRect(box, r, r, pPanel);
            pPanel.setStyle(Paint.Style.STROKE);
            pPanel.setStrokeWidth(1.5f * k);
            pPanel.setColor(0x24FFFFFF);
            tmp2.set(box.left + k, box.top + k, box.right - k, box.bottom - k);
            c.drawRoundRect(tmp2, r - k, r - k, pPanel);
            pPanel.setStyle(Paint.Style.FILL);
            if (screen == MAIN && focus == -1) {
                drawProfilePanel(c);
                return;
            }
            if (screen == MAIN && focus == SUPPORT) {
                drawSupportPanel(c);
                invalidate();   // the hearts keep rising
                return;
            }
            if (screen == GROUP && g == 1 && (clkOpen || (clkT0 > 0 && SystemClock.uptimeMillis() - clkT0 < 340))) {
                drawAppearanceWithClicks(c, g, seconds);
                return;
            }
            c.drawText(groupInfo()[g - 1][0], (P_L + 38) * k, (P_T + 78) * k, pPanelTitle);
            drawPanelRows(c, g, seconds);
        }

        private void drawPanelRows(Canvas c, int g, float seconds) {
            boolean keys = g == 4;
            if (keys && appPkgs.isEmpty()) loadApps();
            final int[] ids = panelIds(g);
            String[] labels = keys ? plus(keyLabels(), groupLabel(ID_REMOTE)) : new String[ids.length];
            String[] values = keys ? plus(keyValues(), groupValue(ID_REMOTE)) : new String[ids.length];
            if (!keys) for (int i = 0; i < ids.length; i++) { labels[i] = groupLabel(ids[i]); values[i] = groupValue(ids[i]); }
            boolean live = screen != MAIN;
            // The panel scrolls so the row in focus (and the heading above it) is always in view.
            float visH = (P_T + P_H - 24) - P_ROWS_T;
            if (live && focus >= 0 && focus < labels.length) {
                float top = rowTopAt(g, focus) - P_ROWS_T - (ids != null && sectionOf(g, ids[focus]) != null ? P_SECT : 0);
                float bottom = rowTopAt(g, focus) - P_ROWS_T + rowH(g);
                float cur = panelScroll.target;
                if (top < cur) cur = Math.max(0, top);
                if (bottom > cur + visH) cur = bottom - visH;
                panelScroll.target = cur;
            } else if (!live) {
                panelScroll.target = 0;
            }
            c.save();
            c.clipRect((P_L + 6) * k, (P_ROWS_T - 8) * k, (P_L + P_W - 6) * k, (P_T + P_H - 14) * k);
            c.translate(0, -panelScroll.value * k);
            for (int i = 0; i < labels.length; i++) {
                float top = rowTopAt(g, i) * k;
                rowRect.set((P_L + 27) * k, top, (P_L + P_W - 27) * k, top + rowH(g) * k);
                String sec = keys ? (i == Prefs.KEYS.length ? Ui.tr("AJUSTES DO CONTROLE", "REMOTE SETTINGS", "AJUSTES DEL CONTROL") : null) : sectionOf(g, ids[i]);
                if (sec != null) {
                    pPanel.setColor(0x24FFFFFF);
                    if (i > 0) c.drawRect(rowRect.left + 30 * k, top - 50 * k, rowRect.right - 30 * k, top - 48 * k, pPanel);
                    header.setColor(i == 0 && (g == 1 || g == 2) ? PEACH : g == 2 ? MELON : LILY);
                    c.drawText(sec, rowRect.left + 30 * k, top - 14 * k, header);
                }
                float f = live ? Ui.clamp(pop[i].value) : 0;
                int id = keys ? (i == Prefs.KEYS.length ? ID_REMOTE : -1) : ids[i];
                boolean locked = false;
                String value = values[i];
                boolean spin = false;
                if (id == ID_LAUNCHER) {
                    String busyText = state.optString("busy");
                    spin = busy();
                    value = spin ? (busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText) : homeName();
                }
                drawPanelRow(c, rowRect, labels[i], value, keys ? null : toggleOf(id), locked, f, spin ? seconds : -1);
            }
            if (g == 4) {
                float y = rowTopAt(g, labels.length) * k - 20 * k;
                String note = RemoteKeys.supported() ? Ui.tr("Disney e Globoplay: a própria TV controla esses botões.",
                        "Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("Esses são os botões do controle da TCL: nesta TV, os botões funcionam como sempre.",
                        "These are the TCL remote's buttons: on this TV, the buttons work as they always did.");
                StaticLayout sl = StaticLayout.Builder.obtain(note, 0, note.length(), new TextPaint(notice), Math.round((P_W - 114) * k)).build();
                c.save();
                c.translate((P_L + 57) * k, y);
                sl.draw(c);
                c.restore();
            }
            if (g == 5) drawSystemBody(c, (rowTopAt(g, labels.length - 1) + rowH(g) + 22) * k, seconds);
            c.restore();
        }

        /** One row of the panel: a name, then a switch (toggle not null) or a value and a chevron. */
        private void drawPanelRow(Canvas c, RectF rect, String label, String value, Boolean toggle, boolean locked, float f, float spinAt) {
            float cy = rect.centerY();
            if (f > 0.01f) {
                float grow = 3 * k * f;
                RectF b = new RectF(rect.left - grow, rect.top - grow, rect.right + grow, rect.bottom + grow);
                float rr = 28.5f * k;
                pPanel.setColor(0xFF26212B);
                pPanel.setAlpha(Math.round(255 * f));
                c.drawRoundRect(b, rr, rr, pPanel);
                pPanel.setAlpha(255);
                if (toggle == null) blinkFill(c, b, rr, f);   // a switch only moves; the row does not flash
                gradientRing(c, b, rr, 4.5f * k, f * 2);
            }
            Paint lp = f > 0.5f ? pLabelB : pLabel;
            lp.setColor(locked ? 0x73FFFFFF : 0xFFFFFFFF);
            c.drawText(label, rect.left + 30 * k, cy + Ui.halfCap(lp), lp);
            float right = rect.right - 30 * k;
            if (toggle != null) {
                float sw = 78 * k, sh = 45 * k, left = right - sw, top = cy - sh / 2;
                tmp2.set(left, top, right, top + sh);
                wpFill.setStyle(Paint.Style.FILL);
                if (toggle && !locked) {
                    wpFill.setShader(new LinearGradient(left, 0, right, 0, LILY, PEACH, Shader.TileMode.CLAMP));
                    wpFill.setColor(0xFFFFFFFF);
                } else {
                    wpFill.setShader(null);
                    wpFill.setColor(0x33FFFFFF);
                }
                c.drawRoundRect(tmp2, sh / 2, sh / 2, wpFill);
                wpFill.setShader(null);
                float kr = 18 * k;
                wpFill.setColor(toggle && !locked ? 0xFFFFFFFF : 0xCCFFFFFF);
                c.drawCircle(toggle ? right - 4.5f * k - kr : left + 4.5f * k + kr, cy, kr, wpFill);
                return;
            }
            float cw = Symbols.width(getContext(), "chevron_right", 27 * k);
            Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, cy, 27 * k, f > 0.5f ? 0xFFFFFFFF : 0x99FFFFFF);
            float vr = right - cw - 12 * k;
            if (value != null && !value.isEmpty()) {
                pValue.setColor(f > 0.5f ? 0xFFFFFFFF : (locked ? 0x59FFFFFF : 0xBDFFFFFF));
                String shown = TextUtils.ellipsize(value, new TextPaint(pValue), 520 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(shown, vr, cy + Ui.halfCap(pValue), pValue);
                if (spinAt >= 0) {
                    spinner.setColor(0xFFFFFFFF);
                    Ui.spinner(c, vr - pValue.measureText(shown) - 26 * k, cy, 12 * k, spinAt * 1.3f, spinner);
                }
            }
        }

        /** Under the System rows: the notes, the comparison, the picks and the notices, in the panel's width. */
        private void drawSystemBody(Canvas c, float y, float seconds) {
            secX = (P_L + 57) * k;
            secR = (P_L + P_W - 57) * k;
            boolean working = busy();
            String busyText = state.optString("busy");
            String error = state.optString("error");
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."), secX, y + 20 * k, warn);
                y += 40 * k;
            } else if (!working && !error.isEmpty() && !(state.optBoolean("soft") && remoteButtonOn())) {
                int width = Math.round(secR - secX);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(secX, y - errorLayout.getLineBaseline(0) + 24 * k);
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 36 * k;
            }
            pPanel.setColor(0x24FFFFFF);
            c.drawRect(secX, y, secR, y + 2 * k, pPanel);
            y = drawComparison(c, y + 48 * k);
            y = drawRecs(c, y + 44 * k, false);
            drawNotices(c, y + 44 * k);
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] LANGUAGES = {"auto", "pt", "en", "es"}, UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** The numbered rows (0-7: language, temperature, theme, time, name, cards, playback, weather) and the wallpaper's. */
        private static final int WALLPAPER_ROW = 8;
        /** Rows of a group that are not in that list: screensaver, shelf, launcher, TV settings. */
        private static final int ID_SAVER = 100, ID_LAUNCHER = 102, ID_TV = 103, ID_SPLASH = 104, ID_WELCOME = 105, ID_SOUND = 106, ID_REMOTE = 107, ID_UPDATE = 108, ID_UPDATE_AUTO = 109, ID_HIDDEN = 110, ID_CLICKS = 111, ID_PLACE = 112, ID_ABOUT = 113, ID_ICONS = 114, ID_TOUR = 115;
        private static final float CARD_H = 130, CARD_GAP = 14;

        private String settingLabel(int id) {
            switch (id) {
                case 0: return Ui.tr("Idioma", "Language");
                case 1: return Ui.tr("Temperatura", "Temperature");
                case 2: return Ui.tr("Tema", "Theme");
                case 3: return Ui.tr("Formato de hora", "Time format", "Formato de hora");
                case 4: return Ui.tr("Seu nome", "Your name", "Tu nombre");
                case 5: return Ui.tr("Cartões", "Cards", "Tarjetas");
                case 6: return Ui.tr("Acesso à reprodução", "Playback access", "Acceso a reproducción");
                case 7: return Ui.tr("Widget do clima", "Weather widget");
                default: return Ui.tr("Papel de parede", "Wallpaper", "Fondo de pantalla");   // WALLPAPER_ROW
            }
        }

        private String settingValue(int id) {
            switch (id) {
                case 0: {
                    String lang = Prefs.language();
                    return lang.equals("pt") ? "Português" : lang.equals("en") ? "English" : lang.equals("es") ? "Español" : Ui.tr("Automático", "Automatic");
                }
                case 1: {
                    String unit = Prefs.temperature();
                    return unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
                }
                case 2: {
                    String theme = Prefs.theme();
                    return theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark") : Ui.tr("Automático", "Automatic");
                }
                case 3: {
                    String tf = Prefs.timeFormat();
                    return tf.equals("24") ? "24 h" : tf.equals("12") ? "12 h" : Ui.tr("Automático", "Automatic");
                }
                case 4: return Prefs.name().isEmpty() ? Ui.tr("Toque OK para definir", "Press OK to set", "Pulsa OK para definir") : Prefs.name();
                case 5: return Prefs.cardsMode().equals("recent") ? Ui.tr("Continuar assistindo", "Continue watching", "Continuar viendo")
                        : Ui.tr("Recomendados", "Recommended", "Recomendados");
                case 6: return Watched.permitted(getContext()) ? Ui.tr("Ligado", "On", "Activado")
                        : Ui.tr("Desligado \u2014 OK para ligar", "Off \u2014 press OK to turn on", "Desactivado \u2014 OK para activar");
                case 7: return onOff(Prefs.weather());
                default: return wallpaperName(Prefs.wallpaper());   // WALLPAPER_ROW
            }
        }

        private static String wallpaperName(int i) {
            return Prefs.WALLPAPER_NAMES[i];
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }

        /** The remote's buttons by name, in the order of Prefs.KEYS (the stored one). */
        private String keyRawLabel(int idx) {
            return new String[] {Ui.tr("Botão Netflix", "Netflix button", "Botón Netflix"), Ui.tr("Botão YouTube", "YouTube button", "Botón YouTube"),
                    Ui.tr("Botão Prime Video", "Prime Video button", "Botón Prime Video"), Ui.tr("Botão TCL Channel", "TCL Channel button", "Botón TCL Channel")}[idx];
        }

        /** Screen position → button index, alphabetical by name. */
        private int[] keyOrder() {
            final java.text.Collator col = collator();
            Integer[] a = new Integer[Prefs.KEYS.length];
            for (int i = 0; i < a.length; i++) a[i] = i;
            java.util.Arrays.sort(a, (x, y) -> col.compare(keyRawLabel(x), keyRawLabel(y)));
            int[] out = new int[a.length];
            for (int i = 0; i < a.length; i++) out[i] = a[i];
            return out;
        }

        private int keyIdx(int pos) { return keyOrder()[pos]; }

        private int keyPos(int idx) {
            int[] o = keyOrder();
            for (int i = 0; i < o.length; i++) if (o[i] == idx) return i;
            return 0;
        }

        private String[] keyLabels() {
            int[] o = keyOrder();
            String[] v = new String[o.length];
            for (int i = 0; i < v.length; i++) v[i] = keyRawLabel(o[i]);
            return v;
        }

        private String[] keyValues() {
            int[] o = keyOrder();
            String[] v = new String[o.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[o[i]]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            PackageManager pm = getContext().getPackageManager();
            List<String[]> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName()) || !seen.add(pkg)) continue;
                found.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            appLabels.add(Ui.tr("Nada", "Nothing"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes one setting (by its old Home Settings row id) one step. */
        private void adjustSetting(int id, int dir) {
            switch (id) {
                case 0: {
                    int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language());
                    Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.length) % LANGUAGES.length]);
                    break;
                }
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case 3: {
                    String[] formats = {"auto", "24", "12"};
                    int at = java.util.Arrays.asList(formats).indexOf(Prefs.timeFormat());
                    Prefs.setTimeFormat(formats[(at + dir + formats.length) % formats.length]);
                    break;
                }
                case 4: askName(); return;
                case 5: Prefs.setCardsMode(Prefs.cardsMode().equals("recent") ? "recs" : "recent"); break;
                case 6: askPlaybackAccess(); return;
                case 7: Prefs.setWeather(!Prefs.weather()); break;
                case WALLPAPER_ROW: openWallpapers(false, false); return;
                case ID_ICONS: {   // columns (the editor with live previews comes next)
                    int[] opts = {6, 8, 10};
                    int at = java.util.Arrays.binarySearch(opts, Prefs.iconCols());
                    Prefs.setIconCols(opts[(at + dir + opts.length) % opts.length]);
                    return;
                }
                case ID_SPLASH: Prefs.setSplash(!Prefs.splash()); return;
                case ID_UPDATE_AUTO: Prefs.setUpdateAuto(!Prefs.updateAuto()); return;
                case ID_REMOTE: askRemoteButton(); return;
                case ID_CLICKS: openClicks(); return;
                case ID_PLACE: askPlace(); return;
                case ID_SOUND: Prefs.setStartupSound(!Prefs.startupSound()); if (Prefs.startupSound()) StartupSound.play(getContext()); return;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        /**
         * Playback access: Android ties reading what the apps are playing to "Notification access". Opens that screen; TVs
         * that don't have it get the one-line adb command instead.
         */
        private void askPlaybackAccess() {
            if (Watched.permitted(getContext())) {
                new android.app.AlertDialog.Builder(getContext())
                        .setTitle(Ui.tr("Acesso à reprodução", "Playback access", "Acceso a reproducción"))
                        .setMessage(Ui.tr("Já está ligado. Para desligar, retire o Borealis em Acesso a notificações.",
                                "It is already on. To turn it off, remove Borealis from Notification access.",
                                "Ya está activado. Para desactivarlo, quita Borealis en Acceso a notificaciones."))
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            // With the TV's own debugging on it is turned on right here (no system screen needed); else the screen, or the explanation.
            Watched.turnOn(getContext(), this::invalidate, () -> Watched.showNoScreen(getContext()));
        }

        // MARK: - Hidden apps screen

        private static final int HID_COLS = 5;
        private final List<HomeApps.Item> hidItems = new ArrayList<>();
        private Spring[] hidPop = new Spring[0];
        private boolean hidFromHome;
        private Paint hidLabel;
        private float hidLabelK;

        void showHiddenApps() {
            openHiddenApps(true);
        }

        /** From the hidden apps screen to the main list of settings (the remote's Settings button). */
        void leaveHiddenApps() {
            if (screen != HIDDEN_APPS) return;
            hidFromHome = false;
            setScreen(MAIN);
            focus = -1;
            invalidate();
            spoken.focusMoved();
        }

        /** The weather location: country, city, the matches (see Places). From the "new" modal it opens straight away. */
        void askPlace() {
            activity.showPlace();
        }

        private void openHiddenApps(boolean fromHome) {
            hidFromHome = fromHome;
            hidItems.clear();
            for (String pkg : Prefs.hiddenApps()) {
                HomeApps.Item it = HomeApps.item(getContext(), pkg);
                if (it != null) hidItems.add(it);
            }
            setScreen(HIDDEN_APPS);
            focus = hidItems.isEmpty() ? -2 : 0;
            hidSprings();
            invalidate();
            spoken.focusMoved();
        }

        private void hidSprings() {
            hidPop = new Spring[hidItems.size()];
            for (int i = 0; i < hidPop.length; i++) {
                hidPop[i] = new Spring(0.28f, 0.85f, 0);
                if (i == focus) hidPop[i].snap(1);
            }
        }

        /** Card i: rows of up to five, centered, the rows scrolling when the focus goes down (the third row on). */
        private void hidCell(int i, RectF out) {
            int n = hidItems.size();
            int row = i / HID_COLS, col = i % HID_COLS, inRow = Math.min(HID_COLS, n - row * HID_COLS);
            float cw = 300, ch = 169, gap = 32, pitch = 303;
            float rowW = inRow * cw + (inRow - 1) * gap;
            float x = (1920 - rowW) / 2f + col * (cw + gap);
            int focusRow = focus >= 0 ? focus / HID_COLS : 0;
            float y = 250 + row * pitch - Math.max(0, focusRow - 1) * pitch;
            out.set(x * k, y * k, (x + cw) * k, (y + ch) * k);
        }

        private boolean hiddenKey(KeyEvent e, boolean down) {
            int n = hidItems.size();
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down && focus > 0 && focus % HID_COLS > 0) focus--;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && focus >= 0 && focus % HID_COLS < HID_COLS - 1 && focus + 1 < n) focus++;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus >= HID_COLS) focus -= HID_COLS;
                    else if (down && focus >= 0) focus = -2;   // the first row: up to the Back button
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < 0) focus = n > 0 ? 0 : -2;
                    else if (down) {
                        if (focus + HID_COLS < n) focus += HID_COLS;
                        else if (focus / HID_COLS < (n - 1) / HID_COLS) focus = n - 1;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                default:
                    return false;
            }
            invalidate();
            spoken.focusMoved();
            return true;
        }

        /** OK on a card: the app is shown again (it goes back to All Apps) and the card leaves this list. */
        private void hiddenRestore() {
            if (focus < 0 || focus >= hidItems.size()) return;
            HomeApps.Item it = hidItems.remove(focus);
            List<String> now = Prefs.hiddenApps();
            now.remove(it.pkg);
            Prefs.setHiddenApps(now);
            if (hidItems.isEmpty()) focus = -2;
            else focus = Math.min(focus, hidItems.size() - 1);
            hidSprings();
            invalidate();
            spoken.focusMoved();
        }

        private void drawHiddenApps(Canvas c) {
            wpPaints();
            if (hidLabel == null || hidLabelK != k) {
                hidLabelK = k;
                hidLabel = Ui.text(Ui.medium, 24 * k, 0xE6FFFFFF);
                hidLabel.setTextAlign(Paint.Align.CENTER);
            }
            c.drawText(Ui.tr("Apps ocultos", "Hidden apps", "Apps ocultas"), w / 2f, 78 * k, wpTitle);
            c.drawText(hidItems.isEmpty()
                            ? Ui.tr("Nenhum app oculto. Para ocultar um: segure OK sobre ele em Todos os apps.",
                                    "No hidden apps. To hide one: hold OK on it in All Apps.",
                                    "No hay apps ocultas. Para ocultar una: mantén OK sobre ella en Todas las apps.")
                            : Ui.tr("Escolha o app que quer mostrar de novo.", "Pick the app you want to show again.", "Elige la app que quieres volver a mostrar."),
                    w / 2f, 122 * k, wpDesc);
            RectF cell = new RectF();
            for (int i = 0; i < hidItems.size(); i++) {
                hidCell(i, cell);
                if (cell.bottom < 170 * k || cell.top > h) continue;   // scrolled out
                float f = Ui.clamp(i < hidPop.length ? hidPop[i].value : 0);
                float s = 1 + 0.06f * f, cx = cell.centerX(), cy = cell.centerY();
                float bw = cell.width() * s, bh = cell.height() * s, rad = 18 * k * s;
                tmp.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
                if (f > 0.01f) {
                    tmp.offset(0, 10 * k * f);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
                    c.drawRoundRect(tmp, rad, rad, shadow);
                    tmp.offset(0, -10 * k * f);
                }
                HomeApps.Item it = hidItems.get(i);
                int save = c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(tmp, rad, rad, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                if (it.tile != null) c.drawBitmap(it.tile, null, tmp, wpImg);
                if (f < 0.99f) {   // not in focus: a little dimmer
                    wpFill.setStyle(Paint.Style.FILL);
                    wpFill.setShader(null);
                    wpFill.setColor(Ui.alpha(0xFF000000, 0.3f * (1 - f)));
                    c.drawRect(tmp, wpFill);
                }
                c.restoreToCount(save);
                if (f > 0.01f) {
                    float ring = 4.5f * k, half = ring / 2;
                    tmp2.set(tmp.left + half, tmp.top + half, tmp.right - half, tmp.bottom - half);
                    ringPaint.setStyle(Paint.Style.STROKE);
                    ringPaint.setStrokeWidth(ring);
                    ringPaint.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                    ringPaint.setAlpha(Math.round(255 * Ui.clamp(f * 2)));
                    c.drawRoundRect(tmp2, rad - half, rad - half, ringPaint);
                    ringPaint.setShader(null);
                }
                c.drawText(it.label, cx, cell.bottom + 40 * k, hidLabel);
                if (i == focus) {   // the action, under the card in focus
                    wpButton(c, cx, cell.bottom + 60 * k, 280 * k, 56 * k,
                            Ui.tr("Mostrar de novo", "Show again", "Volver a mostrar"), f);
                }
            }
        }

        /** Asks how the user wants to be greeted, with Borealis's own keyboard. */
        private void askName() {
            TextEntry.Request r = new TextEntry.Request();
            r.title = Ui.tr("Seu nome", "Your name", "Tu nombre");
            r.hint = Ui.tr("Digite o seu nome", "Type your name", "Escribe tu nombre");
            r.initial = Prefs.name();
            r.maxLen = 24;
            r.done = (text, o) -> {
                Prefs.setName(text);
                invalidate();
            };
            TextEntry.show(getContext(), r);
        }

        private boolean scrolling() {
            return screen == PICKER;
        }

        private int listSize() {
            return pickLabels.size();
        }

        // MARK: - Wallpaper screen

        private static final float WP_MARGIN = 142, WP_CLIP_R = 1778;
        /** Carousel (1920 space): the sample in the middle, the next ones smaller at each side, each one further out and fainter. */
        private static final float SMP_W = 1100, THUMB_W = 400, THUMB_H = 225, THUMB_R = 20;
        private static final float BACK_X = 60, BACK_Y = 50, BACK_W = 200, BACK_H = 64;
        private static final float BTN_H = 52, PILL_W = 110, PILL_H = 52, PILL_GAP = 14, ROW_TOP = 957, STATUS_R = 16;
        private Bitmap[] wpThumb;                 // the side pictures, rounded
        private Bitmap[] wpSmall;                 // each picture small and square-cornered: what the sample shows until the sharp one is ready
        private Bitmap[] wpFull;                  // the sharp pictures of the one in front and its two neighbors
        private long[] wpFullAt;
        private boolean[] wpPending;
        private Bitmap[] wpBlurs;                 // each picture tiny and blurred: the soft backdrop behind everything
        private int wpBlurFrom = -1;
        private Bitmap wpToLast;                      // the backdrop picture last drawn, and when it first appeared (so it fades in)
        private long wpToAt;
        private long[] wpThumbAt;                     // when each small picture first had its image (it fades in over its empty frame)
        private long wpBlurAt;
        private long wpZoomAt;                    // last frame of the wallpaper sample's slow zoom
        private boolean wpOnBack;                 // focus is on the "Back" button
        private int wpRow;                        // 0 the carousel, 1 the row below it (the times and "Preview", or "Hide elements")
        private int wpPill;                       // in that row: 0-3 a time, 4 the button at the right
        private final Spring wpBackPop = new Spring(0.28f, 0.85f, 0);
        private final Spring wpIn = new Spring(0.32f, 1f, 1);
        private final Spring wpCarPop = new Spring(0.28f, 0.85f, 0);   // the ring around the sample when the carousel has the focus
        private final Spring[] wpPillPop = {new Spring(0.28f, 0.85f, 0), new Spring(0.28f, 0.85f, 0), new Spring(0.28f, 0.85f, 0), new Spring(0.28f, 0.85f, 0), new Spring(0.28f, 0.85f, 0)};   // 4 times + the button at the right
        private static final float BACKDROP_ALPHA = 0.8f;
        private final Spring wpClean = new Spring(0.35f, 0.9f, 0), wpScroll = new Spring(0.3f, 0.9f, 0);   // wpScroll: the carousel's position
        private java.util.concurrent.ExecutorService wpPool;
        private final Paint wpFill = new Paint(Paint.ANTI_ALIAS_FLAG), wpImg = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private Paint wpName, wpSub, wpHint, wpGreetSmall, wpGreetBig, wpCapsuleText, wpPillText, wpBackText, wpBtnText, wpStatus, wpTabOn, wpTabOff, wpSection, wpCardTitle, wpCardMeta, wpChip, wpTitle, wpDesc;
        private float wpK;
        private boolean wpSaver;                  // the same screen picks the screensaver (its picture is a still of the video)
        private int wpSaverCurrent;
        private Paint svClock, svDate, svWeather, svLabel, svTitle, svSub, svBar, svTime;

        private String[] wpFiles() { return wpSaver ? Prefs.SAVER_POSTERS : Prefs.WALLPAPER_FILES; }
        private String[] wpNames() { return wpSaver ? Prefs.SAVER_NAMES : Prefs.WALLPAPER_NAMES; }
        private int wpCurrent() { return wpSaver ? wpSaverCurrent : Prefs.wallpaper(); }
                private final android.graphics.Path wpClip = new android.graphics.Path();

        private boolean wpFromMain;
        // Remembered between visits (small), so coming back is immediate.
        private static Bitmap wpFullCache;
        private static int wpFullCacheIdx = -1;
        private static boolean wpFullCacheSaver;
        private static final Bitmap[][] CACHE_THUMB = new Bitmap[2][], CACHE_SMALL = new Bitmap[2][], CACHE_BLUR = new Bitmap[2][];
        private static Bitmap[] tilesCache;
        private static int pinnedCache;
        private Bitmap wpSnap;                        // the screen we came from, kept still until the new one has everything it needs
        private boolean wpHold;
        private long wpHoldAt;

        /** A still of what is on screen now (null if there is no memory for it). */
        private Bitmap takeSnapshot() {
            if (w <= 0 || h <= 0) return null;
            try {
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                draw(new Canvas(b));
                return b;
            } catch (RuntimeException | OutOfMemoryError e) {
                return null;
            }
        }

        private void dropSnapshot() {
            Bitmap b = wpSnap;
            wpSnap = null;
            wpHold = false;
            if (b != null) b.recycle();
        }

        /** Everything the new screen draws is loaded: the front picture and its backdrop, its two neighbors, and the shelf. */
        private boolean wpReady() {
            int n = wpFiles().length;
            if (n == 0 || focus < 0 || focus >= n) return true;
            if (wpBlurs == null || wpSmall == null || wpFull == null) return false;
            int a = Math.floorMod(focus - 1, n), b = (focus + 1) % n;
            if (wpBlurs[focus] == null || wpFull[focus] == null) return false;
            if (wpSmall[focus] == null || wpSmall[a] == null || wpSmall[b] == null) return false;
            return wpSaver || wpTiles != null;
        }

        /** The hold is over: from here everything is already loaded, so nothing fades on its own: one fade for the whole screen. */
        private void wpRelease() {
            wpHold = false;
            long long_ago = 1;   // any non-zero time far in the past counts as "already faded in"
            if (wpThumbAt != null) for (int i = 0; i < wpThumbAt.length; i++) if (wpSmall != null && i < wpSmall.length && wpSmall[i] != null) wpThumbAt[i] = long_ago;
            wpToLast = wpBlurs != null && focus >= 0 && focus < wpBlurs.length ? wpBlurs[focus] : null;
            wpToAt = 0;
            wpIn.snap(0);
            wpIn.target = 1;
        }

        private void openWallpapers(boolean saver, boolean fromMain) {
            dropSnapshot();
            wpSnap = takeSnapshot();
            wpSaver = saver;
            wpFromMain = fromMain;
            if (saver) wpSaverCurrent = Prefs.screensaver();
            screen = WALLPAPERS;
            focus = wpCurrent();
            wpClean.snap(0);
            wpScroll.snap(focus);
            wpSmall = wpFull = null;
            wpBlurs = null;
            wpBlurFrom = -1;
            wpBlurAt = 0;
            wpOnBack = false;
            wpRow = 0;
            wpPill = 1;
            wpCarPop.snap(0);
            HomeData.get(getContext()).refreshIfStale();
            wpBackPop.snap(0);
            wpIn.snap(0);   // stays at 0 while the old screen is held; wpRelease() starts the one fade
            if (wpSnap != null) { wpHold = true; wpHoldAt = SystemClock.uptimeMillis(); } else wpIn.target = 1;
            for (Spring sp : wpPillPop) sp.snap(0);
            wpPool = java.util.concurrent.Executors.newFixedThreadPool(2);   // the front picture and the shelf load next to the thumbnails, not after them
            int n = wpFiles().length;
            wpFull = new Bitmap[n];
            wpFullAt = new long[n];
            wpThumbAt = new long[n];
            wpToLast = null;
            wpToAt = 0;
            wpPending = new boolean[n];
            if (wpFullCache != null && !wpFullCache.isRecycled() && wpFullCacheSaver == wpSaver && wpFullCacheIdx == focus && focus < n) {
                wpFull[focus] = wpFullCache;   // the picture in use was seen a moment ago: no wait
                wpFullAt[focus] = 1;
            }
            wantFull(focus, true);   // first the picture in front and the shelf; then the thumbnails; the neighbors a little later
            loadTiles();
            loadThumbs();
            handler.postDelayed(() -> { if (wpPool != null && wpFull != null && screen == WALLPAPERS) wantFull(focus); }, 350);
            spoken.focusMoved();
            scheduleLive();
        }

        private void closeWallpapers() {
            handler.removeCallbacks(liveStart);
            stopLive();
            if (wpPool != null) wpPool.shutdownNow();
            wpPool = null;
            wpSmall = wpFull = null;
            wpBlurs = null;
            wpThumb = null;
            wpTiles = null;
            group = 1;
            setScreen(GROUP);
            focus = groupIndexOf(1, wpSaver ? ID_SAVER : WALLPAPER_ROW);
        }

        /** Brings item `idx` to the front by the shortest way around the circle. */
        private void wpGo(int idx) {
            int n = wpFiles().length;
            int cur = Math.round(wpScroll.target);
            int delta = Math.floorMod(idx - cur, n);
            if (delta > n / 2) delta -= n;
            wpScroll.target = cur + delta;
            int was = focus;
            focus = Math.floorMod(Math.round(wpScroll.target), n);
            if (focus != was) {
                wpBlurFrom = was;
                wpBlurAt = SystemClock.uptimeMillis();
            }
            wantFull(focus);
            scheduleLive();
        }

        /** Signed distance (in items) from the front of the carousel, the short way around. */
        private float wpDist(int i) {
            int n = wpFiles().length;
            float d = i - wpScroll.value;
            return d - n * Math.round(d / n);
        }

        private float carWidth(float d) {
            d = Math.abs(d);
            if (d >= 2) return 260;
            if (d < 1) return SMP_W + (THUMB_W - SMP_W) * d;
            return THUMB_W + (260 - THUMB_W) * (d - 1);
        }

        private float carOffset(float d) {
            float a = Math.abs(d), o;
            if (a < 1) o = 800 * a;
            else o = 800 + 380 * (a - 1);
            return d < 0 ? -o : o;
        }

        private float carAlpha(float d) {
            float a = Math.abs(d);
            if (a < 1) return 1 - 0.3f * a;
            return Math.max(0, 0.7f * (1 - (a - 1) / 0.5f));
        }

        private float wpTop() { return 225; }

        /** Where item `i` is right now (screen pixels), written to `out`. */
        private void wpItemRect(int i, RectF out) {
            float d = wpDist(i), iw = carWidth(d) * k, ih = iw * 9f / 16f;
            float cx = w / 2f + carOffset(d) * k, cy = (wpTop() + SMP_W * 9f / 32f) * k;
            out.set(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2);
        }

        private Bitmap decodeWallpaper(String file, int sample, int targetWidth) {
            try (java.io.InputStream in = getContext().getAssets().open(file)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                if (targetWidth > 0 && targetWidth < 1920) {
                    o.inScaled = true;
                    o.inDensity = 1920;
                    o.inTargetDensity = targetWidth;
                }
                return BitmapFactory.decodeStream(in, null, o);
            } catch (Exception | OutOfMemoryError ignored) {
                return null;
            }
        }

        private Bitmap[] wpTiles;   // the home's bottom panel: the pinned apps' cards, then the first of the other apps
        private int wpPinned;       // how many of wpTiles are the pinned ones (the first row)

        private void loadTiles() {
            final java.util.concurrent.ExecutorService pool = wpPool;
            if (wpSaver || pool == null) return;
            wpTiles = tilesCache;   // the last ones, at once; fresh ones replace them when ready
            wpPinned = pinnedCache;
            pool.execute(() -> {
                List<HomeApps.Item> row1 = new ArrayList<>(), row2 = new ArrayList<>();
                try {
                    for (HomeApps.Item it : HomeApps.shelf(getContext(), false)) if (it.kind == HomeApps.APP && row1.size() < 7) row1.add(it);
                    List<HomeApps.Item> rest = HomeApps.others(getContext());
                    for (HomeApps.Item it : rest) if (it.kind == HomeApps.APP && row2.size() < 7) row2.add(it);
                } catch (RuntimeException e) {
                    return;
                }
                final Bitmap[] out = new Bitmap[row1.size() + row2.size()];
                for (int i = 0; i < out.length; i++) {
                    HomeApps.Item it = i < row1.size() ? row1.get(i) : row2.get(i - row1.size());
                    try {
                        out[i] = renderTile(it, 392, 224);
                    } catch (RuntimeException | OutOfMemoryError ignored) {
                    }
                }
                final int pinned = row1.size();
                handler.post(() -> {
                    if (wpPool == pool) {
                        wpTiles = out;
                        wpPinned = pinned;
                        tilesCache = out;
                        pinnedCache = pinned;
                        invalidate();
                    }
                });
            });
        }

        /** One card of the bottom panel as the home draws it: the finished art if there is one, else the app's own TV art. */
        private Bitmap renderTile(HomeApps.Item it, int tw, int th) {
            Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float rr = 17f * tw / 196f;
            RectF box = new RectF(0, 0, tw, th);
            Bitmap art = null;
            try (java.io.InputStream in = getContext().getAssets().open("cards/" + it.key + ".png")) {
                art = BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
            }
            if (art != null) {
                cv.drawBitmap(art, null, box, pt);
                art.recycle();
                return out;
            }
            if (it.tile != null) {
                android.graphics.BitmapShader shader = new android.graphics.BitmapShader(it.tile, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(tw / (float) it.tile.getWidth(), th / (float) it.tile.getHeight());
                shader.setLocalMatrix(m);
                pt.setShader(shader);
                cv.drawRoundRect(box, rr, rr, pt);
            }
            return out;
        }

        private void loadThumbs() {
            final int n = wpFiles().length, kind = wpSaver ? 1 : 0;
            if (CACHE_THUMB[kind] == null || CACHE_THUMB[kind].length != n) {
                CACHE_THUMB[kind] = new Bitmap[n];
                CACHE_SMALL[kind] = new Bitmap[n];
                CACHE_BLUR[kind] = new Bitmap[n];
            }
            final Bitmap[] mine = CACHE_THUMB[kind], small = CACHE_SMALL[kind], blurs = CACHE_BLUR[kind];
            wpThumb = mine;
            wpSmall = small;
            wpBlurs = blurs;
            final int tw = Math.max(32, Math.round(THUMB_W * k)), th = Math.max(18, Math.round(THUMB_H * k));
            final float radius = THUMB_R * k;
            final int start = focus;
            final java.util.concurrent.ExecutorService pool = wpPool;
            pool.execute(() -> {
                for (int q = 0; q < n; q++) {
                    // The one in front first, then its two neighbors (what the screen needs to show), then the rest.
                    int i = n >= 3 && q == 1 ? (start + 1) % n : n >= 3 && q == 2 ? Math.floorMod(start - 1, n) : n >= 3 && q >= 3 ? (start + q - 1) % n : (start + q) % n;
                    if (wpThumb != mine) return;
                    if (mine[i] != null) continue;   // kept from the last visit
                    Bitmap b = decodeWallpaper(wpFiles()[i], Integer.highestOneBit(Math.max(1, 1920 / (tw * 2))), 0);
                    if (b == null) continue;
                    Bitmap sm = Bitmap.createScaledBitmap(b, tw, th, true);
                    Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                    android.graphics.BitmapShader shader = new android.graphics.BitmapShader(sm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    p.setShader(shader);
                    new Canvas(out).drawRoundRect(new RectF(0, 0, tw, th), radius, radius, p);
                    Bitmap soft = softBackdrop(b);
                    if (b != sm) b.recycle();
                    mine[i] = out;
                    small[i] = sm;
                    blurs[i] = soft;
                    handler.post(() -> { if (wpThumb == mine) invalidate(); });
                }
            });
        }

        /** A picture tiny and blurred (3 box passes), so it stays smooth when stretched over the whole screen. */
        static Bitmap softBackdrop(Bitmap b) {
            try {
                Bitmap soft = Bitmap.createScaledBitmap(b, 192, 108, true);
                if (!soft.isMutable()) {
                    Bitmap copy = soft.copy(Bitmap.Config.ARGB_8888, true);
                    if (copy != null) {
                        if (soft != b) soft.recycle();
                        soft = copy;
                    }
                }
                int[] px = new int[192 * 108];
                soft.getPixels(px, 0, 192, 0, 0, 192, 108);
                for (int pass = 0; pass < 3; pass++) boxBlur(px, 192, 108, 7);
                soft.setPixels(px, 0, 192, 0, 0, 192, 108);
                return soft;
            } catch (RuntimeException | OutOfMemoryError e) {
                return null;
            }
        }

        /** Keeps the sharp pictures of item `idx` and its two neighbors (so the next turn of the carousel never waits for a decode). */
        private void wantFull(final int idx) { wantFull(idx, false); }

        private void wantFull(final int idx, final boolean frontOnly) {
            final java.util.concurrent.ExecutorService pool = wpPool;
            final Bitmap[] mine = wpFull;
            if (pool == null || mine == null) return;
            final int n = mine.length;
            final int width = Math.max(480, Math.min(1920, Math.round(SMP_W * k * 1.15f)));
            for (int i = 0; i < n; i++) {
                int dd = Math.floorMod(i - idx, n);
                if (dd > 1 && dd < n - 1) mine[i] = null;   // far away: let it go
            }
            for (int d : new int[] {0, 1, -1}) {
                if (frontOnly && d != 0) continue;
                final int i = Math.floorMod(idx + d, n);
                if (mine[i] != null || wpPending[i]) continue;
                wpPending[i] = true;
                pool.execute(() -> {
                    final Bitmap b = decodeWallpaper(wpFiles()[i], 1, width);
                    handler.post(() -> {
                        wpPending[i] = false;
                        if (wpPool != pool || wpFull != mine || b == null) return;
                        mine[i] = b;
                        wpFullAt[i] = SystemClock.uptimeMillis();
                        if (i == wpCurrent()) { wpFullCache = b; wpFullCacheIdx = i; wpFullCacheSaver = wpSaver; }
                        invalidate();
                    });
                });
            }
        }

        private boolean wpFading() {
            long now = SystemClock.uptimeMillis();
            if (now - wpBlurAt < 320 || now - wpToAt < 320) return true;
            if (wpThumbAt != null) for (long t : wpThumbAt) if (t != 0 && now - t < 280) return true;
            if (wpFullAt != null) for (long t : wpFullAt) if (now - t < 240) return true;
            return false;
        }

        private void wpSetRow(int row) {
            wpOnBack = row < 0;
            wpRow = Math.max(0, row);
        }

        /** One box-blur pass over opaque pixels (horizontal, then vertical), edges repeated. */
        private static void boxBlur(int[] px, int bw, int bh, int r) {
            int[] tmpRow = new int[Math.max(bw, bh)];
            int div = 2 * r + 1;
            for (int y = 0; y < bh; y++) {
                int base = y * bw;
                for (int x = 0; x < bw; x++) tmpRow[x] = px[base + x];
                int sr = 0, sg = 0, sb = 0;
                for (int i = -r; i <= r; i++) {
                    int p = tmpRow[Math.max(0, Math.min(bw - 1, i))];
                    sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
                }
                for (int x = 0; x < bw; x++) {
                    px[base + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                    int out = tmpRow[Math.max(0, x - r)], in = tmpRow[Math.min(bw - 1, x + r + 1)];
                    sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                    sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                    sb += (in & 255) - (out & 255);
                }
            }
            for (int x = 0; x < bw; x++) {
                for (int y = 0; y < bh; y++) tmpRow[y] = px[y * bw + x];
                int sr = 0, sg = 0, sb = 0;
                for (int i = -r; i <= r; i++) {
                    int p = tmpRow[Math.max(0, Math.min(bh - 1, i))];
                    sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
                }
                for (int y = 0; y < bh; y++) {
                    px[y * bw + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                    int out = tmpRow[Math.max(0, y - r)], in = tmpRow[Math.min(bh - 1, y + r + 1)];
                    sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                    sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                    sb += (in & 255) - (out & 255);
                }
            }
        }

        private boolean wallpaperKey(KeyEvent e, boolean down) {
            int code = e.getKeyCode();
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) {
                        boolean dn = code == KeyEvent.KEYCODE_DPAD_DOWN;
                        if (wpOnBack) {
                            if (dn) wpSetRow(0);
                        } else if (dn) {
                            if (wpRow == 0) {
                                if (wpSaver) {
                                    wpPill = 1;
                                    int now = (int) (saverTimeMs() / 60_000);
                                    for (int i = 0; i < SAVER_MINUTES.length; i++) if (SAVER_MINUTES[i] == now) wpPill = i;
                                } else {
                                    wpPill = 4;
                                }
                                wpSetRow(1);
                            }
                        } else {
                            wpSetRow(wpRow == 0 ? -1 : 0);
                        }
                        spoken.focusMoved();
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) {
                        int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                        if (wpOnBack) {
                            // nothing to the sides of "Back"
                        } else if (wpRow == 0) {
                            int n = wpFiles().length;
                            wpGo(Math.floorMod(focus + dir, n));   // circular: the next one comes to the front
                            spoken.focusMoved();
                        } else if (wpSaver) {   // the four times, then "Preview" at the right
                            wpPill = Math.max(0, Math.min(4, wpPill + dir));
                        }
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void wpPaints() {
            if (wpK == k && wpName != null) return;
            wpK = k;
            wpName = Ui.text(Ui.displaySemibold, 36 * k, 0xFFFFFFFF);
            wpName.setTextAlign(Paint.Align.CENTER);
            wpSub = Ui.text(Ui.medium, 23 * k, LILY);
            wpSub.setTextAlign(Paint.Align.CENTER);
            wpHint = Ui.text(Ui.regular, 21 * k, 0x80FFFFFF);
            wpHint.setTextAlign(Paint.Align.CENTER);
            wpTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
            wpTitle.setTextAlign(Paint.Align.CENTER);
            wpDesc = Ui.text(Ui.regular, 22 * k, 0xB3FFFFFF);
            wpDesc.setTextAlign(Paint.Align.CENTER);
            wpGreetSmall = Ui.text(Ui.regular, 26 * k, 0xCCFFFFFF);
            wpGreetBig = Ui.text(Ui.displaySemibold, 52 * k, 0xFFFFFFFF);
            wpCapsuleText = Ui.text(Ui.medium, 26 * k, 0xFFFFFFFF);
            wpCapsuleText.setTextAlign(Paint.Align.CENTER);
            wpPillText = Ui.text(Ui.medium, 19 * k, 0xFFFFFFFF);
            wpPillText.setTextAlign(Paint.Align.CENTER);
            wpBackText = Ui.text(Ui.medium, 26 * k, 0xFFFFFFFF);
            wpBtnText = Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF);
            wpBtnText.setTextAlign(Paint.Align.CENTER);
            wpTabOn = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
            wpTabOn.setTextAlign(Paint.Align.CENTER);
            wpTabOff = Ui.text(Ui.medium, 24 * k, 0xCCFFFFFF);
            wpTabOff.setTextAlign(Paint.Align.CENTER);
            wpSection = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
            wpCardTitle = Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF);
            wpCardMeta = Ui.text(Ui.medium, 22 * k, 0xB3FFFFFF);
            wpChip = Ui.text(Ui.medium, 20 * k, 0xFFFFFFFF);
            wpStatus = Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF);
            wpStatus.setTextAlign(Paint.Align.RIGHT);
        }

        /**
         * The picker: a carousel. The picture in focus is the sample (with the screen's own elements) in the middle; the
         * next ones are smaller and fainter at each side. Under it: the buttons and, for the screensaver, the times.
         */
        private void drawWallpapers(Canvas c, float dt) {
            wpPaints();
            wpClean.step(dt);
            wpScroll.step(dt);
            wpBackPop.target = wpOnBack ? 1 : 0;
            wpBackPop.step(dt);
            wpCarPop.target = !wpOnBack && wpRow == 0 ? 1 : 0;
            wpCarPop.step(dt);
            for (int i = 0; i < wpPillPop.length; i++) {
                wpPillPop[i].target = !wpOnBack && wpRow == 1 && wpPill == i ? 1 : 0;
                wpPillPop[i].step(dt);
            }
            float ui = 1 - Ui.clamp(wpClean.value);
            int n = wpFiles().length;
            c.drawColor(INK);
            // Behind everything: the picture in front, tiny and blurred, over the night black (cross-fading from the one before).
            waveDst.set(0, 0, w, h);
            float eb = Ui.clamp((SystemClock.uptimeMillis() - wpBlurAt) / 300f);
            Bitmap to = wpBlurs != null && focus < wpBlurs.length ? wpBlurs[focus] : null;
            Bitmap from = wpBlurs != null && wpBlurFrom >= 0 && wpBlurFrom < wpBlurs.length && wpBlurFrom != focus ? wpBlurs[wpBlurFrom] : null;
            if (to != wpToLast) {   // a picture's backdrop just arrived (or the focus moved): it fades in
                wpToLast = to;
                wpToAt = SystemClock.uptimeMillis();
            }
            float ea = Ui.clamp((SystemClock.uptimeMillis() - wpToAt) / 300f);
            // Until the picture's own backdrop is ready (and while it fades in) the settings' backdrop stays: no dark gap, no pop.
            Bitmap under = from != null && (eb < 1 || to == null) ? from : (to == null || ea < 1 ? bgSoft : null);
            if (under != null) {
                wpImg.setAlpha(Math.round(255 * BACKDROP_ALPHA));
                c.drawBitmap(under, null, waveDst, wpImg);
            }
            if (to != null) {
                wpImg.setAlpha(Math.round(255 * BACKDROP_ALPHA * (from != null && eb < 1 ? eb : ea)));
                c.drawBitmap(to, null, waveDst, wpImg);
            }
            wpImg.setAlpha(255);

            // Where the sample rests (the live preview is placed there).
            float mw = SMP_W * k, mh = mw * 9f / 16f;
            wpMiniScale = mw / w;
            wpMiniX = w / 2f - mw / 2f;
            wpMiniY = wpTop() * k;

            // The items, the farthest first so the nearest are on top.
            int[] order = wpOrder != null && wpOrder.length == n ? wpOrder : (wpOrder = new int[n]);
            for (int i = 0; i < n; i++) order[i] = i;
            for (int i = 1; i < n; i++) {
                int v = order[i];
                float dv = Math.abs(wpDist(v));
                int j = i - 1;
                while (j >= 0 && Math.abs(wpDist(order[j])) < dv) {
                    order[j + 1] = order[j];
                    j--;
                }
                order[j + 1] = v;
            }
            for (int q = 0; q < n; q++) {
                int i = order[q];
                float d = wpDist(i), alpha = carAlpha(d);
                if (alpha <= 0.01f) continue;
                wpItemRect(i, wpBox);
                float iw = wpBox.width();
                float r = Math.max(12 * k, 24 * k * iw / mw);
                if (Math.abs(d) < 1) {
                    // The sample fades with the same curve as the small pictures, so nothing jumps when it turns into one.
                    int layer = alpha < 0.99f ? c.saveLayerAlpha(wpBox.left - 14 * k, wpBox.top - 14 * k, wpBox.right + 14 * k, wpBox.bottom + 14 * k, Math.round(255 * alpha)) : -1;
                    drawSample(c, wpBox, r, ui, i, i == focus, 1 - Ui.clamp(Math.abs(d) * 1.6f));
                    if (layer >= 0) c.restoreToCount(layer);
                    drawBadge(c, i, wpBox, alpha * Ui.clamp((Math.abs(d) - 0.5f) / 0.5f));
                } else {
                    drawThumb(c, i, wpBox, r, alpha);
                    drawBadge(c, i, wpBox, alpha);
                }
            }

            // Above the sample, at its right: whether this one is in use (a filled check) or can be chosen (an empty circle).
            float left = w / 2f - SMP_W * k / 2f, right = left + SMP_W * k;
            float frontness = 1 - Ui.clamp(Math.abs(wpDist(focus)) * 1.6f);
            boolean inUse = focus == wpCurrent();
            drawStatus(c, right, (wpTop() - 40) * k, inUse, frontness);

            // Under it: the name, small.
            float top = wpTop(), tvCx = w / 2f, nameY = (top + SMP_W * 9f / 16f + 78) * k;
            String title = wpNames()[focus];
            wpName.setAlpha(Math.round(255 * frontness));
            c.drawText(title, tvCx, nameY, wpName);
            wpName.setAlpha(255);

            // The row below: for the screensaver, its times and "Preview" at the right; for wallpapers, "Hide elements" centered.
            float rowTop = ROW_TOP * k, bh = BTN_H * k;
            if (wpSaver) {
                Paint label = wpHintStrong();
                String text = Ui.tr("Ativar o protetor após", "Start the screensaver after", "Activar protector tras");
                label.setTextAlign(Paint.Align.LEFT);
                c.drawText(text, left, rowTop + bh / 2 + Ui.halfCap(label), label);
                float px = left + label.measureText(text) + 30 * k;
                long now = saverTimeMs();
                for (int i = 0; i < SAVER_MINUTES.length; i++) {
                    float x = px + i * (PILL_W + PILL_GAP) * k;
                    wpTimePill(c, x, rowTop, PILL_W * k, PILL_H * k, SAVER_MINUTES[i] + " min", wpPillPop[i].value, SAVER_MINUTES[i] * 60_000L == now);
                }
                float bw = 200 * k;
                wpButton(c, right - bw / 2, rowTop, bw, bh, Ui.tr("Ver agora", "Preview", "Ver ahora"), wpPillPop[4].value);
            } else {
                float bw = 290 * k;
                String hide = wpClean.target > 0.5f ? Ui.tr("Mostrar elementos", "Show elements", "Mostrar elementos")
                        : Ui.tr("Ocultar elementos", "Hide elements", "Ocultar elementos");
                wpButton(c, tvCx, rowTop, bw, bh, hide, wpPillPop[4].value);
            }
            drawBackButton(c);
            c.drawText(wpSaver ? Ui.tr("Protetor de tela", "Screensaver", "Protector de pantalla") : Ui.tr("Papel de parede", "Wallpaper", "Fondo de pantalla"),
                    w / 2f, 78 * k, wpTitle);
            c.drawText(wpSaver ? Ui.tr("Escolha o protetor que mais gostar. Use as setas para os lados para ver os outros.",
                            "Pick the screensaver you like best. Use the left and right arrows to see the others.",
                            "Elige el protector que más te guste. Usa las flechas a los lados para ver los demás.")
                            : Ui.tr("Escolha o papel de parede que mais gostar. Use as setas para os lados para ver os outros.",
                            "Pick the wallpaper you like best. Use the left and right arrows to see the others.",
                            "Elige el fondo que más te guste. Usa las flechas a los lados para ver los demás."),
                    w / 2f, 122 * k, wpDesc);
            if (wpRow == 0) {   // the arrows at the sides of the sample: more to the left and to the right
                float ay = (wpTop() + SMP_W * 9f / 32f) * k, ax = 90 * k;
                Symbols.draw(getContext(), c, "chevron_right", w - ax, ay, 64 * k, 0xCCFFFFFF);
                c.save();
                c.rotate(180, ax, ay);
                Symbols.draw(getContext(), c, "chevron_right", ax, ay, 64 * k, 0xCCFFFFFF);
                c.restore();
            }
        }

        private int[] wpOrder;
        private final RectF wpBox = new RectF();
        private Paint wpLabel;

        private Paint wpHintStrong() {
            if (wpLabel == null || wpK != wpLabelK) {
                wpLabelK = wpK;
                wpLabel = Ui.text(Ui.medium, 26 * k, 0xE6FFFFFF);
                wpLabel.setTextAlign(Paint.Align.CENTER);
            }
            return wpLabel;
        }
        private float wpLabelK;

        /** A side picture: small, rounded, fainter the further it is from the front. */
        private void drawThumb(Canvas c, int i, RectF r, float radius, float alpha) {
            Bitmap sm = wpSmall != null && i < wpSmall.length ? wpSmall[i] : null;
            Bitmap t = wpThumb != null ? wpThumb[i] : null;
            if (wpThumbAt == null || wpThumbAt.length != wpFiles().length) wpThumbAt = new long[wpFiles().length];
            boolean have = sm != null || t != null;
            if (have && wpThumbAt[i] == 0) { wpThumbAt[i] = SystemClock.uptimeMillis(); invalidate(); }
            float ea = !have ? 0 : Ui.clamp((SystemClock.uptimeMillis() - wpThumbAt[i]) / 250f);   // the image fades in over its empty frame
            if (ea < 1) {
                wpFill.setShader(null);
                wpFill.setColor(Ui.alpha(0x33FFFFFF, alpha));
                c.drawRoundRect(r, radius, radius, wpFill);
            }
            if (sm != null) {   // clipped with the same corner radius the sample has, so nothing changes when one becomes the other
                wpClip.reset();
                wpClip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
                c.save();
                c.clipPath(wpClip);
                wpImg.setAlpha(Math.round(255 * alpha * ea));
                c.drawBitmap(sm, null, r, wpImg);
                wpImg.setAlpha(255);
                c.restore();
            } else if (t != null) {
                wpImg.setAlpha(Math.round(255 * alpha * ea));
                c.drawBitmap(t, null, r, wpImg);
                wpImg.setAlpha(255);
            }
            wpFill.setShader(null);
            wpFill.setStyle(Paint.Style.STROKE);
            wpFill.setStrokeWidth(2 * k);
            wpFill.setColor(Ui.alpha(0x40FFFFFF, alpha));
            c.drawRoundRect(r, radius, radius, wpFill);
            wpFill.setStyle(Paint.Style.FILL);
        }

        /** The small check on the picture in use, once it is off to the side (it fades in as the picture leaves the front). */
        private void drawBadge(Canvas c, int i, RectF r, float alpha) {
            if (i != wpCurrent() || alpha <= 0.01f) return;
            float bx = r.right - 16 * k, by = r.top + 16 * k;
            wpFill.setShader(null);
            wpFill.setStyle(Paint.Style.FILL);
            wpFill.setColor(Ui.alpha(LILY, alpha));
            c.drawCircle(bx, by, 11 * k, wpFill);
            Symbols.draw(getContext(), c, "check", bx, by, 15 * k, Ui.alpha(INK, alpha));
        }

        /** The picture in focus, as the TV shows it, with its elements (and the live preview over it once it settles). */
        private void drawSample(Canvas c, RectF box, float mr, float ui, int idx, boolean front, float elements) {
            float scale = box.width() / w, mx = box.left, my = box.top;
            wpClip.reset();
            wpClip.addRoundRect(box, mr, mr, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(wpClip);
            c.translate(mx, my);
            c.scale(scale, scale);
            c.drawColor(INK);
            waveDst.set(0, 0, w, h);
            long nowMs = SystemClock.uptimeMillis();
            wpZoomAt = nowMs;
            float zoom = 1, pan = 0;
            if (!wpSaver) {   // the wallpaper's sample moves like the screensaver does (the real wallpaper stays still)
                pan = DreamVideo.zoomPhase(nowMs) * elements;   // the motion fades as it leaves the front, so it matches the still small picture
                zoom = 1 + DreamVideo.ZOOM_AMOUNT * pan;
            }
            c.save();
            c.translate(-DreamVideo.PAN_X * w * pan, -DreamVideo.PAN_Y * h * pan);
            c.rotate(DreamVideo.TURN_DEGREES * pan, w / 2f, h / 2f);
            c.scale(zoom, zoom, w / 2f, h / 2f);
            // Always this item's own picture: the small one right away, the sharp one fading in over it when it is ready.
            Bitmap small = wpSmall != null && idx < wpSmall.length ? wpSmall[idx] : null;
            Bitmap full = wpFull != null && idx < wpFull.length ? wpFull[idx] : null;
            if (small != null) {
                wavePaint.setAlpha(255);
                c.drawBitmap(small, null, waveDst, wavePaint);
            }
            if (full != null) {
                float fa = small == null ? 1 : Ui.clamp((nowMs - wpFullAt[idx]) / 220f);
                wavePaint.setAlpha(Math.round(255 * fa));
                c.drawBitmap(full, null, waveDst, wavePaint);
                wavePaint.setAlpha(255);
            }
            c.restore();
            float elem = ui * elements;
            float shown = wpSaver && live != null && front ? elem * (1 - live.getAlpha()) : elem;   // the live preview draws its own elements
            if (live != null && front) live.setElementsAlpha(ui);
            if (shown > 0.004f) {
                if (wpSaver) drawSaverElements(c, shown); else drawHomeElements(c, shown);
            }
            c.restore();
            wpFill.setShader(null);
            wpFill.setStyle(Paint.Style.STROKE);
            wpFill.setStrokeWidth(2 * k);
            wpFill.setColor(0x40FFFFFF);
            c.drawRoundRect(box, mr, mr, wpFill);   // the same line the small pictures have
            wpFill.setStyle(Paint.Style.FILL);
            float f = Ui.clamp(wpCarPop.value) * elements;
            if (f > 0.01f) {   // the focus: the same lily → peach → melon ring the buttons have, just outside the sample
                float ring = 4.5f * k, gap = 3 * k + ring / 2;
                tmp.set(box.left - gap, box.top - gap, box.right + gap, box.bottom + gap);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                ringPaint.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
                ringPaint.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, mr + gap, mr + gap, ringPaint);
                ringPaint.setShader(null);
            }
        }

        /** "In use" with a filled check (full color), or "Select" with an empty circle: text on the left, circle on the right. */
        private void drawStatus(Canvas c, float right, float cy, boolean inUse, float alpha) {
            if (alpha <= 0.01f) return;
            float r = STATUS_R * k, cx = right - r;
            if (inUse) {
                wpFill.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, Brand.accents(), null, Shader.TileMode.CLAMP));
                wpFill.setStyle(Paint.Style.FILL);
                wpFill.setColor(Ui.alpha(0xFFFFFFFF, alpha));
                c.drawCircle(cx, cy, r, wpFill);
                wpFill.setShader(null);
                Symbols.draw(getContext(), c, "check", cx, cy, r * 1.35f, Ui.alpha(INK, alpha));
            } else {
                wpFill.setShader(null);
                wpFill.setStyle(Paint.Style.STROKE);
                wpFill.setStrokeWidth(2.5f * k);
                wpFill.setColor(Ui.alpha(0xE6FFFFFF, alpha));
                c.drawCircle(cx, cy, r - 1.25f * k, wpFill);
                wpFill.setStyle(Paint.Style.FILL);
            }
            wpStatus.setAlpha(Math.round(255 * alpha));
            c.drawText(inUse ? Ui.tr("Em uso", "In use", "En uso") : Ui.tr("Selecionar", "Select", "Seleccionar"),
                    cx - r - 14 * k, cy + Ui.halfCap(wpStatus), wpStatus);
        }

        /** A button under the sample: night with a faint line; focused, the lily → peach → melon ring; "in use" is filled with the gradient. */
        private void wpButton(Canvas c, float cx, float top, float bw, float bh, String text, float f) {
            final boolean filled = false;
            float s = 1 + 0.05f * f, cy = top + bh / 2, r = bh * s / 2;
            tmp2.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
            RectF box = new RectF(tmp2);
            if (f > 0.01f) {
                box.offset(0, 6 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(box, r, r, shadow);
                box.offset(0, -6 * k * f);
            }
            wpFill.setStyle(Paint.Style.FILL);
            if (filled) {
                wpFill.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
                c.drawRoundRect(box, r, r, wpFill);
                wpFill.setShader(null);
            } else {
                wpFill.setShader(null);
                wpFill.setColor(blend(Brand.ink(0xE6), Brand.ink(0xFF), f));
                c.drawRoundRect(box, r, r, wpFill);
            }
            if (f < 0.99f && !filled) {
                wpFill.setStyle(Paint.Style.STROKE);
                wpFill.setStrokeWidth(2 * k);
                wpFill.setColor(Ui.alpha(0x59FFFFFF, 1 - f));
                RectF in = new RectF(box.left + k, box.top + k, box.right - k, box.bottom - k);
                c.drawRoundRect(in, r - k, r - k, wpFill);
                wpFill.setStyle(Paint.Style.FILL);
            }
            if (f > 0.01f) {
                float ring = 3.5f * k, half = ring / 2;
                RectF in = new RectF(box.left + half, box.top + half, box.right - half, box.bottom - half);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                ringPaint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
                ringPaint.setAlpha(Math.round(255 * Ui.clamp(f * 2)));
                if (filled) {   // over the gradient fill the ring is white
                    ringPaint.setShader(null);
                    ringPaint.setColor(Ui.alpha(0xFFFFFFFF, f));
                }
                c.drawRoundRect(in, r - half, r - half, ringPaint);
                ringPaint.setShader(null);
            }
            blinkFill(c, box, r, f);
            wpBtnText.setColor(filled ? INK : 0xFFFFFFFF);
            c.drawText(text, cx, cy + Ui.halfCap(wpBtnText), wpBtnText);
        }

        /** A time option: gray; focused, a gradient border; chosen, filled with the gradient. */
        private void wpTimePill(Canvas c, float left, float top, float pw, float ph, String text, float f, boolean chosen) {
            float s = 1 + 0.06f * f, cx = left + pw / 2, cy = top + ph / 2, r = ph * s / 2;
            RectF box = new RectF(cx - pw * s / 2, cy - ph * s / 2, cx + pw * s / 2, cy + ph * s / 2);
            wpFill.setStyle(Paint.Style.FILL);
            if (chosen) {   // always full color, focused or not
                wpFill.setColor(0xFFFFFFFF);
                wpFill.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
            } else {
                wpFill.setShader(null);
                wpFill.setColor(0x40FFFFFF);
                c.drawRoundRect(box, r, r, wpFill);   // the light base stays while the dark comes over it: the pill never vanishes
                wpFill.setColor(Ui.alpha(Brand.ink(0xFF), f));
            }
            c.drawRoundRect(box, r, r, wpFill);
            wpFill.setShader(null);
            if (f > 0.01f) {
                float ring = 3.5f * k, half = ring / 2;
                RectF in = new RectF(box.left + half, box.top + half, box.right - half, box.bottom - half);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                if (chosen) {
                    ringPaint.setShader(null);
                    ringPaint.setColor(Ui.alpha(0xFFFFFFFF, f));
                } else {
                    ringPaint.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom, Brand.accents(), null, Shader.TileMode.CLAMP));
                    ringPaint.setAlpha(Math.round(255 * Ui.clamp(f * 2)));
                }
                c.drawRoundRect(in, r - half, r - half, ringPaint);
                ringPaint.setShader(null);
            }
            blinkFill(c, box, r, f);
            wpBtnText.setColor(chosen ? INK : 0xFFFFFFFF);
            c.drawText(text, cx, cy + Ui.halfCap(wpBtnText), wpBtnText);
        }

        /** "Back", top left: a pill; focused (←) it turns night-black with the lily → peach → melon ring. */
        private void drawBackButton(Canvas c) {
            wpPaints();
            float f = Ui.clamp(wpBackPop.value), s = 1 + 0.05f * f;
            float cx = (BACK_X + BACK_W / 2f) * k, cy = (BACK_Y + BACK_H / 2f) * k;
            float bw = BACK_W * k * s, bh = BACK_H * k * s, r = bh / 2;
            tmp.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
            if (f > 0.01f) {
                tmp.offset(0, 6 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -6 * k * f);
            }
            wpFill.setShader(null);
            wpFill.setColor(blend(Brand.ink(0xB3), Brand.ink(0xFF), f));
            c.drawRoundRect(tmp, r, r, wpFill);
            blinkFill(c, tmp, r, f);
            if (f < 0.99f) {
                wpFill.setStyle(Paint.Style.STROKE);
                wpFill.setStrokeWidth(2 * k);
                wpFill.setColor(Ui.alpha(0x59FFFFFF, 1 - f));
                tmp2.set(tmp.left + k, tmp.top + k, tmp.right - k, tmp.bottom - k);
                c.drawRoundRect(tmp2, r - k, r - k, wpFill);
                wpFill.setStyle(Paint.Style.FILL);
            }
            if (f > 0.01f) {
                float ring = 3.5f * k, half = ring / 2;
                tmp2.set(tmp.left + half, tmp.top + half, tmp.right - half, tmp.bottom - half);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(ring);
                ringPaint.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                ringPaint.setAlpha(Math.round(255 * Ui.clamp(f * 2)));
                c.drawRoundRect(tmp2, r - half, r - half, ringPaint);
                ringPaint.setShader(null);
            }
            float iconX = tmp.left + 36 * k * s;
            c.save();
            c.rotate(180, iconX, tmp.centerY());   // the chevron, pointing left
            Symbols.draw(getContext(), c, "chevron_right", iconX, tmp.centerY(), 26 * k, 0xFFFFFFFF);
            c.restore();
            c.drawText(Ui.tr("Voltar", "Back", "Volver"), iconX + 28 * k, tmp.centerY() + Ui.halfCap(wpBackText), wpBackText);
        }

        // The screensaver picker's live preview: the real screensaver, scaled into the TV (after a short pause on each item).
        private DreamVideo live;
        private float wpMiniScale = 1, wpMiniX, wpMiniY;
        private final Runnable liveStart = new Runnable() {
            @Override public void run() { startLive(); }
        };

        private void scheduleLive() {
            handler.removeCallbacks(liveStart);
            stopLive();
            if (screen == WALLPAPERS && wpSaver && running) handler.postDelayed(liveStart, 450);
        }

        private void startLive() {
            stopLive();
            if (screen != WALLPAPERS || !wpSaver || !running || w == 0 || !(getParent() instanceof android.view.ViewGroup)) return;
            if (!DreamVideo.available(getContext(), focus)) return;
            final DreamVideo d = new DreamVideo(getContext(), focus, true);
            final float radius = 24 * k / wpMiniScale;
            d.setPivotX(0);
            d.setPivotY(0);
            d.setScaleX(wpMiniScale);
            d.setScaleY(wpMiniScale);
            d.setTranslationX(wpMiniX);
            d.setTranslationY(wpMiniY);
            d.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            d.setClipToOutline(true);
            d.setAlpha(0f);
            ((android.view.ViewGroup) getParent()).addView(d, new android.widget.FrameLayout.LayoutParams(w, h));
            d.setElementsAlpha(1 - Ui.clamp(wpClean.value));
            d.start();
            d.animate().alpha(1).setDuration(400).start();
            live = d;
            invalidate();
        }

        private void stopLive() {
            DreamVideo d = live;
            if (d == null) return;
            live = null;
            d.animate().cancel();
            d.stop();
            if (d.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) d.getParent()).removeView(d);
            invalidate();
        }

        /** The home's top, as it looks over the wallpaper: date and time capsule, greeting, round buttons (full-screen coordinates). */
        private void drawHomeElements(Canvas c, float ui) {
            java.util.Date now = new java.util.Date();
            String day = new java.text.SimpleDateFormat("EEE d MMM", Ui.locale()).format(now);
            if (!day.isEmpty()) day = Character.toUpperCase(day.charAt(0)) + day.substring(1).replace(".", "");
            String capsule = day + "     " + android.text.format.DateFormat.getTimeFormat(getContext()).format(now);
            HomeData.Weather wx = HomeData.get(getContext()).weather;
            boolean sampleWx = Double.isNaN(wx.temp);   // before the weather arrives: a sample value, so the capsule is shown complete
            boolean hasWx = Prefs.weather();
            String symbol = hasWx ? (sampleWx ? "cloud" : Symbols.weather(wx.code, wx.isDay)) : null;
            String temp = hasWx ? (sampleWx ? "18°" : HomeData.degrees(wx.temp)) : "";
            float textW = wpCapsuleText.measureText(capsule), iconS = 28 * k;
            float wxW = hasWx ? 28 * k + Symbols.width(getContext(), symbol, iconS) + 10 * k + wpCapsuleText.measureText(temp) : 0;
            float cw = textW + wxW + 80 * k, ch = 58 * k, ctop = 30 * k;
            tmp.set(w / 2f - cw / 2, ctop, w / 2f + cw / 2, ctop + ch);
            wpFill.setStyle(Paint.Style.FILL);
            wpFill.setShader(null);
            wpFill.setColor(Ui.alpha(Brand.ink(0xB3), ui));
            c.drawRoundRect(tmp, ch / 2, ch / 2, wpFill);
            wpCapsuleText.setAlpha(Math.round(255 * ui));
            wpCapsuleText.setTextAlign(Paint.Align.LEFT);
            float tx = tmp.left + 40 * k, base = tmp.centerY() + Ui.halfCap(wpCapsuleText);
            c.drawText(capsule, tx, base, wpCapsuleText);
            if (hasWx) {
                float ix = tx + textW + 28 * k, iw = Symbols.width(getContext(), symbol, iconS);
                Symbols.draw(getContext(), c, symbol, ix + iw / 2, tmp.centerY(), iconS, Ui.alpha(0xFFFFFFFF, ui));
                c.drawText(temp, ix + iw + 10 * k, base, wpCapsuleText);
            }
            wpCapsuleText.setTextAlign(Paint.Align.CENTER);
            String name = Prefs.name();
            wpGreetSmall.setAlpha(Math.round(204 * ui));
            c.drawText(name.isEmpty() ? Ui.tr("Olá!", "Hi!", "¡Hola!") : Ui.tr("Olá, ", "Hi, ", "¡Hola, ") + name + "!", WP_MARGIN * k - 0, 128 * k, wpGreetSmall);
            wpGreetBig.setAlpha(Math.round(255 * ui));
            c.drawText(Ui.tr("O que você quer assistir hoje?", "What do you want to watch today?", "¿Qué quieres ver hoy?"), WP_MARGIN * k, 190 * k, wpGreetBig);
            String[] icons = {"play", "wifi", "bluetooth", "hdmi", "settings"};
            for (int i = 0; i < icons.length; i++) {
                float cx = (WP_CLIP_R - 29 - 88 * (icons.length - 1 - i)) * k, cy = 147 * k, r = 29 * k;
                wpFill.setColor(Ui.alpha(Brand.ink(0xB3), ui));
                c.drawCircle(cx, cy, r, wpFill);
                wpFill.setStyle(Paint.Style.STROKE);
                wpFill.setStrokeWidth(2 * k);
                wpFill.setColor(Ui.alpha(0x40FFFFFF, ui));
                c.drawCircle(cx, cy, r - k, wpFill);
                wpFill.setStyle(Paint.Style.FILL);
                Symbols.draw(getContext(), c, icons[i], cx, cy, 30 * k, Ui.alpha(0xFFFFFFFF, ui));
            }
            boolean recent = Prefs.cardsMode().equals("recent");
            // The tabs (Explore / Movies / Shows) when the user has them on; with "Continue watching", its section title instead.
            if (!recent) {
                String[] tabs = {Ui.tr("Explorar", "Explore", "Explorar"), Ui.tr("Filmes", "Movies", "Películas"), Ui.tr("Séries", "Shows", "Series")};
                float tabX = WP_MARGIN * k, tTop = 216 * k, tH = 54 * k;
                for (int i = 0; i < tabs.length; i++) {
                    Paint tp = i == 0 ? wpTabOn : wpTabOff;
                    float tw = Math.max(wpTabOn.measureText(tabs[i]), wpTabOff.measureText(tabs[i])) + 40 * k;
                    tmp.set(tabX, tTop, tabX + tw, tTop + tH);
                    wpFill.setStyle(Paint.Style.FILL);
                    if (i == 0) {   // the selected one: the lily → peach → melon gradient
                        wpFill.setShader(new LinearGradient(tmp.left, 0, tmp.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
                        wpFill.setColor(Ui.alpha(0xFFFFFFFF, ui));
                    } else {
                        wpFill.setShader(null);
                        wpFill.setColor(Ui.alpha(Brand.ink(0xFF), 0.7f * ui));
                    }
                    c.drawRoundRect(tmp, tH / 2, tH / 2, wpFill);
                    wpFill.setShader(null);
                    tp.setAlpha(Math.round(255 * ui));
                    c.drawText(tabs[i], tmp.centerX(), tmp.centerY() + Ui.halfCap(tp), tp);
                    tabX += tw + 14 * k;
                }
            } else {
                wpSection.setAlpha(Math.round(255 * ui));
                c.drawText(Ui.tr("Continuar reproduzindo", "Continue playing", "Continuar reproduciendo"), WP_MARGIN * k,
                        (216 + 27) * k + Ui.halfCap(wpSection), wpSection);
            }
            // The three cards on the real grid: one wide (it spans what the other two leave), two compact.
            float wide = (WP_CLIP_R - WP_MARGIN) - 2 * 293 - 2 * 54;
            float[] cardWs = {wide, 293, 293};
            int[] tint = {LILY, PEACH, MELON};
            String[] chips = {"Netflix", "YouTube", "Prime Video"};
            float[] done = {0.62f, 0.3f, 0.8f};
            float cx0 = WP_MARGIN * k;
            for (int i = 0; i < 3; i++) {
                float cwid = cardWs[i] * k, top = 302 * k, bot = (302 + 409) * k;
                tmp.set(cx0, top, cx0 + cwid, bot);
                wpFill.setStyle(Paint.Style.FILL);
                wpFill.setShader(new LinearGradient(0, top, 0, bot, Ui.alpha(tint[i], 0.55f * ui), Ui.alpha(Brand.ink(0xE6), ui), Shader.TileMode.CLAMP));
                c.drawRoundRect(tmp, 18 * k, 18 * k, wpFill);
                wpFill.setShader(null);
                if (recent) {   // "Continue watching": a dark fade at the bottom, and the progress line (what was watched in color)
                    wpFill.setShader(new LinearGradient(0, bot - 409 * k * 0.5f, 0, bot, 0x00000000, Ui.alpha(0xCC000000, ui), Shader.TileMode.CLAMP));
                    c.drawRoundRect(tmp, 18 * k, 18 * k, wpFill);
                    wpFill.setShader(null);
                    float inset = 22 * k, bh = 9 * k, btnH = 50 * k, left = cx0 + inset;
                    float right = cx0 + cwid - inset - (i == 0 ? (22 + 26 + 10 + 190 + 24 + 36) * k : 0);
                    float y = bot - inset - btnH / 2 - bh / 2;
                    wpFill.setColor(Ui.alpha(0xE6FFFFFF, ui));
                    c.drawRoundRect(left, y, right, y + bh, bh / 2, bh / 2, wpFill);
                    wpFill.setShader(new LinearGradient(left, 0, right, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
                    wpFill.setColor(Ui.alpha(0xFFFFFFFF, ui));
                    c.drawRoundRect(left, y, left + Math.max(bh, (right - left) * done[i]), y + bh, bh / 2, bh / 2, wpFill);
                    wpFill.setShader(null);
                    wpChip.setAlpha(Math.round(255 * ui));
                    c.drawText(Ui.tr("32 min restantes", "32 min left", "32 min restantes"), left, y - 12 * k, wpChip);
                }
                wpFill.setStyle(Paint.Style.STROKE);
                wpFill.setStrokeWidth(2 * k);
                wpFill.setColor(Ui.alpha(0x40FFFFFF, ui));
                c.drawRoundRect(tmp, 18 * k, 18 * k, wpFill);
                wpFill.setStyle(Paint.Style.FILL);
                // The platform tag at the top left: a small dark pill.
                String chip = chips[i];
                float cwd = wpChip.measureText(chip) + 28 * k;
                tmp.set(cx0 + 16 * k, top + 16 * k, cx0 + 16 * k + cwd, top + 16 * k + 34 * k);
                wpFill.setColor(Ui.alpha(Brand.ink(0xB3), ui));
                c.drawRoundRect(tmp, 17 * k, 17 * k, wpFill);
                wpChip.setAlpha(Math.round(255 * ui));
                c.drawText(chip, tmp.left + 14 * k, tmp.centerY() + Ui.halfCap(wpChip), wpChip);
                if (i == 0) {   // the open card always shows its button
                    String label = recent ? Ui.tr("Continuar assistindo", "Continue watching", "Continuar viendo") : Ui.tr("Assistir", "Watch now", "Ver ahora");
                    float bw = 22 * k + 26 * k + 10 * k + wpChip.measureText(label) * 1.2f + 24 * k;
                    tmp.set(cx0 + cwid - 22 * k - bw, bot - 22 * k - 50 * k, cx0 + cwid - 22 * k, bot - 22 * k);
                    wpFill.setColor(Ui.alpha(0xF2FFFFFF, ui));
                    c.drawRoundRect(tmp, 25 * k, 25 * k, wpFill);
                    Symbols.draw(getContext(), c, "play", tmp.left + 22 * k + 13 * k, tmp.centerY(), 26 * k, Ui.alpha(INK, ui));
                    wpBtnText.setTextSize(22 * k);
                    wpBtnText.setTextAlign(Paint.Align.LEFT);
                    wpBtnText.setColor(Ui.alpha(INK, ui));
                    c.drawText(label, tmp.left + 22 * k + 26 * k + 10 * k, tmp.centerY() + Ui.halfCap(wpBtnText), wpBtnText);
                    wpBtnText.setTextSize(25 * k);
                    wpBtnText.setTextAlign(Paint.Align.CENTER);
                }
                // Title and detail under the card.
                float belowW = cwid;
                wpCardTitle.setAlpha(Math.round(255 * ui));
                c.drawText(TextUtils.ellipsize(Ui.tr("Título de exemplo", "Sample title", "Título de ejemplo"), new TextPaint(wpCardTitle), belowW, TextUtils.TruncateAt.END).toString(),
                        cx0, bot + 40 * k, wpCardTitle);
                String meta = i == 0 ? Ui.tr("Drama · 2 h 10 min · Netflix", "Drama · 2 h 10 min · Netflix", "Drama · 2 h 10 min · Netflix") : "Drama · 2 h";
                wpCardMeta.setAlpha(Math.round(179 * ui));
                c.drawText(TextUtils.ellipsize(meta, new TextPaint(wpCardMeta), belowW, TextUtils.TruncateAt.END).toString(), cx0, bot + 70 * k, wpCardMeta);
                cx0 += cwid + 54 * k;
            }
            // The bottom panel, as the home has it: dark glass across the whole width with the white bar at its center,
            // the pinned apps in its first row and the next row peeking under the edge of the screen.
            float panelTop = 823 * k, rad = 58 * k;
            wpFill.setShader(null);
            wpFill.setStyle(Paint.Style.FILL);
            wpFill.setColor(Ui.alpha(0x73000000, ui));
            tmp.set(0, panelTop, w, h + rad);
            c.drawRoundRect(tmp, rad, rad, wpFill);
            wpFill.setStyle(Paint.Style.STROKE);
            wpFill.setStrokeWidth(2 * k);
            wpFill.setColor(Ui.alpha(0x26FFFFFF, ui));
            c.drawRoundRect(tmp, rad, rad, wpFill);
            wpFill.setStyle(Paint.Style.FILL);
            wpFill.setColor(Ui.alpha(0xE6FFFFFF, ui));
            tmp.set(w / 2f - 62 * k, 852 * k, w / 2f + 62 * k, 862 * k);
            c.drawRoundRect(tmp, 5 * k, 5 * k, wpFill);
            if (wpTiles != null) {
                float colGap = ((WP_CLIP_R - WP_MARGIN) - 7 * 196) / 6f;
                for (int i = 0; i < wpTiles.length; i++) {
                    int row = i < wpPinned ? 0 : 1, col = i < wpPinned ? i : i - wpPinned;
                    float x = (WP_MARGIN + col * (196 + colGap)) * k, y = (891 + row * 154) * k;
                    tmp.set(x, y, x + 196 * k, y + 112 * k);
                    Bitmap t = wpTiles[i];
                    if (t != null) {
                        wpImg.setAlpha(Math.round(255 * ui));
                        c.drawBitmap(t, null, tmp, wpImg);
                        wpImg.setAlpha(255);
                        wpFill.setColor(Ui.alpha(0xFF000000, 0.08f * ui));
                        c.drawRoundRect(tmp, 17 * k, 17 * k, wpFill);
                    } else {
                        wpFill.setColor(Ui.alpha(0x33FFFFFF, ui));
                        c.drawRoundRect(tmp, 17 * k, 17 * k, wpFill);
                    }
                }
            }
        }

        /** The screensaver's own layout over the picture: clock, date and weather in the lower left, a sample of the info box in the lower right. */
        private void drawSaverElements(Canvas c, float ui) {
            if (svClock == null || wpK != svK) {
                svK = k;
                svClock = Ui.numbers(Ui.thin, 250 * k, 0xF2FFFFFF);
                svClock.setLetterSpacing(-0.01f);
                svDate = Ui.numbers(Ui.semibold, 34 * k, 0xE6FFFFFF);
                svWeather = Ui.numbers(Ui.medium, 34 * k, 0xE6FFFFFF);
                svLabel = Ui.text(Ui.semibold, 17 * k, 0xB3FFFFFF);
                svLabel.setLetterSpacing(0.14f);
                svTitle = Ui.text(Ui.semibold, 29 * k, 0xF2FFFFFF);
                svSub = Ui.text(Ui.medium, 21 * k, 0xCCFFFFFF);
                svBar = new Paint(Paint.ANTI_ALIAS_FLAG);
                svTime = Ui.numbers(Ui.medium, 20 * k, 0xE6FFFFFF);
                svTime.setTextAlign(Paint.Align.RIGHT);
            }
            // Darkening at the bottom, as the real screensaver has.
            svBar.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0, Ui.alpha(0xA6000000, ui), Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.5f, w, h, svBar);
            svBar.setShader(null);
            long now = System.currentTimeMillis();
            float x = 110 * k;
            String date = Ui.date(now);
            HomeData.Weather wx = HomeData.get(getContext()).weather;
            boolean sampleWx = Double.isNaN(wx.temp) && Prefs.weather();
            boolean hasWx = (!Double.isNaN(wx.temp) || sampleWx) && Prefs.weather();
            String symbol = hasWx ? (sampleWx ? "cloud" : Symbols.weather(wx.code, wx.isDay)) : null;
            float size = 34 * k, sw = hasWx ? Symbols.width(getContext(), symbol, size) : 0;
            String temp = hasWx ? (sampleWx ? "18°" : HomeData.degrees(wx.temp)) : "";
            float lineW = svDate.measureText(date) + (hasWx ? 36 * k + sw + 12 * k + svWeather.measureText(temp) : 0);
            String time = Ui.clock(now);
            svClock.setTextSize(250 * k);
            float natural = svClock.measureText(time);
            svClock.setTextSize(Math.max(110 * k, Math.min(250 * k, 250 * k * lineW / Math.max(1, natural))));
            svClock.setAlpha(Math.round(242 * ui));
            svDate.setAlpha(Math.round(230 * ui));
            svWeather.setAlpha(Math.round(230 * ui));
            float clockBase = h - 120 * k;
            c.drawText(time, x - 6 * k, clockBase, svClock);
            float line = clockBase - Ui.cap(svClock) - 40 * k;
            c.drawText(date, x, line, svDate);
            if (hasWx) {
                float wx0 = x + svDate.measureText(date) + 36 * k;
                Symbols.draw(getContext(), c, symbol, wx0 + sw / 2, line - Ui.halfCap(svWeather), size, Ui.alpha(0xE6FFFFFF, ui));
                c.drawText(temp, wx0 + sw + 12 * k, line, svWeather);
            }
            // A sample of the box that shows what is playing (cover, label, title, progress).
            float pad = 26 * k, cover = 100 * k, gap = 26 * k, textW = 430 * k, inner = cover + gap + textW;
            float boxW = pad + inner + pad, boxH = 2 * pad + cover + 44 * k;
            float right = w - 110 * k, bottom = h - 120 * k + 18 * k, left = right - boxW, top = bottom - boxH;
            svBar.setStyle(Paint.Style.FILL);
            svBar.setColor(Ui.alpha(Brand.ink(0xCC), ui));
            tmp.set(left, top, right, bottom);
            c.drawRoundRect(tmp, 30 * k, 30 * k, svBar);
            float cx = left + pad, cy = top + pad;
            tmp.set(cx, cy, cx + cover, cy + cover);
            svBar.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            svBar.setAlpha(Math.round(255 * ui));
            c.drawRoundRect(tmp, 18 * k, 18 * k, svBar);
            svBar.setShader(null);
            float tx = cx + cover + gap;
            svLabel.setAlpha(Math.round(179 * ui));
            svTitle.setAlpha(Math.round(242 * ui));
            svSub.setAlpha(Math.round(204 * ui));
            c.drawText(Ui.tr("Tocando agora", "Now playing", "Reproduciendo ahora").toUpperCase(Ui.locale()), tx, cy + 24 * k, svLabel);
            c.drawText(Ui.tr("Título da música", "Song title", "Título de la canción"), tx, cy + 62 * k, svTitle);
            c.drawText(Ui.tr("Artista", "Artist", "Artista"), tx, cy + 94 * k, svSub);
            float by = cy + cover + 34 * k, bh = 10 * k;
            svTime.setAlpha(Math.round(230 * ui));
            c.drawText("-2:41", cx + inner, by - 10 * k, svTime);   // the time left, as the real box shows it
            svBar.setColor(Ui.alpha(0xE6FFFFFF, ui));
            tmp.set(cx, by, cx + inner, by + bh);
            c.drawRoundRect(tmp, bh / 2, bh / 2, svBar);
            tmp.set(cx, by, cx + inner * 0.35f, by + bh);
            svBar.setShader(new LinearGradient(cx, 0, cx + inner, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            svBar.setAlpha(Math.round(255 * ui));
            c.drawRoundRect(tmp, bh / 2, bh / 2, svBar);
            svBar.setShader(null);
        }
        private float svK;

        private static final int[] SAVER_MINUTES = {5, 10, 15, 30};
        private long saverWant, saverWantAt, saverReadAt;
        private long saverRead = 600_000;

        /** Minutes (as milliseconds) the TV waits before the screensaver: what the TV has, or what was just asked for. */
        private long saverTimeMs() {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - saverReadAt > 1_000) {
                saverReadAt = now;
                try { saverRead = Settings.System.getLong(getContext().getContentResolver(), "screen_off_timeout", saverRead); } catch (RuntimeException ignored) {}
            }
            if (saverWant > 0 && (saverRead == saverWant || now - saverWantAt > 12_000)) saverWant = 0;
            return saverWant > 0 ? saverWant : saverRead;
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            pickLabels.addAll(appLabels);
            pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            setScreen(PICKER);
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = headingPaint();
            String title = keyRawLabel(pickFor - 1);
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, 0xFFFFFFFF, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, 0xFFFFFFFF, f));
                }
            }
            Paint hint = hintPaint();
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            final int[] ids = keys ? null : groupIds();
            String[] labels = keys ? keyLabels() : new String[ids.length];
            String[] values = keys ? keyValues() : new String[ids.length];
            if (!keys) for (int i = 0; i < ids.length; i++) { labels[i] = groupLabel(ids[i]); values[i] = groupValue(ids[i]); }
            Paint heading = headingPaint();
            c.drawText(keys ? Ui.tr("Botões do Controle", "Remote Buttons") : groupInfo()[group - 1][0],
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, 0xFFFFFFFF, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xE6FFFFFF, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                boolean nav = !keys && opensScreen(ids[i]);
                c.drawText(values[i], rowRect.right - (nav ? 66 : 30) * k, base, rowValue);
                if (nav) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0xCCFFFFFF, f));
                }
            }
            Paint hint = hintPaint();
            float hy = (LIST_TOP + 20 + labels.length * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint);
            if (keys) {
                c.drawText(RemoteKeys.supported() ? Ui.tr("Disney e Globoplay: a própria TV controla esses botões.",
                        "Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("Esses são os botões do controle da TCL: nesta TV, os botões funcionam como sempre.",
                        "These are the TCL remote's buttons: on this TV, the buttons work as they always did."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        /** "Updates": with a new version waiting, offers to install it; otherwise asks GitHub now and says what it found. */
        private void checkUpdateNow() {
            final Context c = getContext();
            if (!Update.newer(c).isEmpty()) {
                offerInstall();
                return;
            }
            Update.check(c, true, status -> {
                if (status == Update.FOUND) {
                    offerInstall();
                    invalidate();
                    return;
                }
                String text = status == Update.FAILED
                        ? Ui.tr("Não foi possível verificar agora. Veja se a TV tem internet e tente de novo.",
                                "Could not check right now. Make sure the TV is online and try again.",
                                "No se pudo comprobar ahora. Revisa que la TV tenga internet e inténtalo de nuevo.")
                        : Ui.tr("Você está em dia: versão ", "You are up to date: version ", "Estás al día: versión ") + Update.installed(c) + ".";
                tellUpdate(text);
                invalidate();
            });
        }

        private void tellUpdate(String text) {
            new android.app.AlertDialog.Builder(getContext()).setTitle(Ui.tr("Atualizações", "Updates", "Actualizaciones")).setMessage(text)
                    .setPositiveButton("OK", null).show();
        }

        private void offerInstall() {
            final Context c = getContext();
            new android.app.AlertDialog.Builder(c).setTitle(Ui.tr("Atualizações", "Updates", "Actualizaciones"))
                    .setMessage(Ui.tr("Nova versão disponível: " + Update.newer(c) + ". Instalar agora? São uns 7 MB; o Android vai pedir uma última confirmação.",
                            "New version available: " + Update.newer(c) + ". Install it now? It is about 7 MB; Android will ask one last confirmation.",
                            "Nueva versión disponible: " + Update.newer(c) + ". ¿Instalarla ahora? Son unos 7 MB; Android te pedirá una última confirmación."))
                    .setPositiveButton(Ui.tr("Instalar", "Install", "Instalar"), (d, w) -> startInstall())
                    .setNegativeButton(Ui.tr("Agora não", "Not now", "Ahora no"), null).show();
        }

        private void startInstall() {
            final Context c = getContext();
            if (!Update.canInstall(c)) {   // once: Android's own switch for "install apps from this source"
                new android.app.AlertDialog.Builder(c).setTitle(Ui.tr("Atualizações", "Updates", "Actualizaciones"))
                        .setMessage(Ui.tr("O Android precisa da sua permissão, uma só vez, para o Borealis instalar suas atualizações. Na próxima tela ative \"Permitir desta fonte\" e volte. Depois escolha Atualizações de novo.",
                                "Android needs your permission, just once, for Borealis to install its updates. On the next screen turn on \"Allow from this source\" and come back. Then choose Updates again.",
                                "Android necesita tu permiso, una sola vez, para que Borealis instale sus actualizaciones. En la siguiente pantalla activa \"Permitir de esta fuente\" y vuelve. Luego elige Actualizaciones otra vez."))
                        .setPositiveButton("OK", (d, w) -> Update.askPermission(c)).show();
                return;
            }
            if (Update.lowSpace(c)) {   // say it before downloading anything
                tellUpdate(Update.lowSpaceMessage(c));
                return;
            }
            final android.app.AlertDialog wait = new android.app.AlertDialog.Builder(c).setTitle(Ui.tr("Atualizações", "Updates", "Actualizaciones"))
                    .setMessage(Ui.tr("Baixando a atualização…", "Downloading the update…", "Descargando la actualización…")).setCancelable(false).show();
            Update.install(c, (ok, error) -> {
                wait.dismiss();
                if (!ok && Update.LOW_SPACE.equals(error)) {
                    tellUpdate(Update.lowSpaceMessage(c));
                } else if (!ok) {
                    tellUpdate(Ui.tr("Não foi possível instalar agora (" + error + "). Tente de novo mais tarde.",
                            "Could not install right now (" + error + "). Try again later.",
                            "No se pudo instalar ahora (" + error + "). Inténtalo de nuevo más tarde."));
                }
            });
        }

        private void activate() {
            Context c = getContext();
            blink();
            click().open();
            if (focus == -2 && screen != WALLPAPERS) {   // the Back button
                goBack();
                return;
            }
            if (screen == ICON_EDIT) {
                iconsApply();
                return;
            }
            if (screen == HIDDEN_APPS) {   // the app in focus comes back
                hiddenRestore();
                return;
            }
            if (screen == AVATARS) {   // the avatar picked: back to the profile
                if (focus >= 0 && focus < Avatars.IDS.length) Prefs.setAvatar(Avatars.IDS[focus]);
                setScreen(MAIN);
                focus = -1;
                invalidate();
                spoken.focusMoved();
                return;
            }
            if (screen == MAIN && focus == -1) {   // "Mi perfil": into its panel
                enterProfile();
                return;
            }
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                if ((wantFree != freeActive() || (!wantFree && HomeMode.soft(c))) && !busy()) {   // soft Home counts as not-yet-full: choosing Borealis tries ADB again
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                group = 5;
                setScreen(GROUP);
                focus = 0;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = keyPos(pickFor - 1);
                setScreen(REMOTE_KEYS);
            } else if (screen == WALLPAPERS) {
                if (wpOnBack) {
                    closeWallpapers();
                    invalidate();
                    spoken.focusMoved();
                    return;
                }
                if (wpRow == 1) {
                    if (!wpSaver) {   // "Hide elements": the sample shows only the picture; again, with everything
                        wpClean.target = wpClean.target > 0.5f ? 0 : 1;
                    } else if (wpPill == 4) {   // "Preview": the real screensaver, full screen, for the one in front
                        pause();
                        activity.showPreview();
                    } else {   // a screensaver time
                        HomeMode.requestSaverTime(c, SAVER_MINUTES[wpPill]);
                        saverWant = SAVER_MINUTES[wpPill] * 60_000L;
                        saverWantAt = SystemClock.uptimeMillis();
                    }
                    invalidate();
                    spoken.focusMoved();
                    return;
                }
                if (focus == wpCurrent()) return;   // already the one in use
                if (wpSaver) {
                    Prefs.setScreensaver(focus);
                    wpSaverCurrent = focus;
                } else {
                    Prefs.setWallpaper(focus);
                }
                HomeData.get(c).settingsChanged();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == REMOTE_KEYS) {
                if (focus >= Prefs.KEYS.length) {   // the remote's settings button
                    askRemoteButton();
                    invalidate();
                    return;
                }
                openPicker(keyIdx(focus) + 1);
            } else if (screen == GROUP) {
                int id = groupIds()[focus];
                if (id == ID_SAVER) {
                    openWallpapers(true, true);   // the screensaver picker (its "Preview" button plays it)
                } else if (id == ID_HIDDEN) {
                    openHiddenApps(false);
                } else if (id == ID_LAUNCHER) {
                    if (busy()) return;
                    setScreen(HOME_CHOICE);
                    focus = homeIsBorealis() ? 0 : 1;
                } else if (id == ID_WELCOME) {
                    OnboardingView.repeat(c);
                    activity.goHomeSoftly();
                } else if (id == ID_TOUR) {   // the four screens, straight away (no invitation), over the home screen
                    HomeActivity.conocePending = true;
                    activity.goHomeSoftly();
                } else if (id == ID_UPDATE) {
                    checkUpdateNow();
                } else if (id == ID_ABOUT) {
                    openCredits();
                } else if (id == ID_TV) {
                    activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else if (id == WALLPAPER_ROW) {
                    openWallpapers(false, false);
                } else if (id == ID_ICONS) {
                    openIcons();
                } else {
                    adjustSetting(id, 1);
                    invalidate();
                    return;
                }
            } else if (screen == MAIN && focus == SUPPORT) {   // "Apoya a Borealis" shows its content in the panel: nothing to open
                return;
            } else if (groupOrder()[focus] == 4) {   // Remote control: the buttons work from inside other apps through this service
                HomeMode.ensureRemoteService(c, 0);
                loadApps();
                setScreen(REMOTE_KEYS);
                focus = 0;
            } else {   // one of the other groups
                group = groupOrder()[focus];
                setScreen(GROUP);
                focus = 0;
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
