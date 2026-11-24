package io.borealis;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: the apps panel rises (pinned apps first, then all apps A-Z); ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 96, CONTENT_R = 1824;   // 5% each side, as designed (64 px of 1280)
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: 5:3 icons (210 × 126) in each brand's color, 48 apart, and the glass bar hugging
    // them with 40 of padding (the focused icon grows 20% without touching its edge).
    private static final float SHELF_PAD = 40, TILE_GAP = 27;
    private static final float TILE_RADIUS = 17, TILE_MAX_W = 188;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // "All Apps" sheet.
    private static final int MAX_COLS = 10, VISIBLE_ROWS = 3;
    // Apps panel (opens with Down from the shelf): 7 columns; pinned apps first, a thin line, then all apps A-Z.
    private static final float P_TOP = 0, P_RADIUS = 58, P_FIRST = 186, P_CLIP = 170;
    // The open panel fills the screen: a frosted glass sheet with the title, a line on how OK works and the search field at
    // the top, and the first row of apps at 186. The home (greeting, clock, buttons, cards) slides up and fades as it rises.
    private static final float SEARCH_W = 510, SEARCH_H = 66, SEARCH_CY = 105, HOME_RISE = 70;
    private static final float P_CLOSED_TOP = 631, P_CLOSED_CENTER = 242;   // the panel at rest on the home screen (its top: the bottom of "All" ends 100 above the screen's edge, like the header's top)
    /** The drift's own clock (seconds), and until when it is held still (a screen is being captured to open over the home). */
    private float driftT = 0;
    static volatile long driftFreezeUntil;
    private static final boolean DRIFT = true;   // the background drifts slowly all the time (set false if the TV drops frames)
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, SHEET = 6, TOAST = 7, MENU = 8, INFO = 9, RETURN = 10;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 184, L_HELLO_BASE = 118, L_TABBAR_TOP = 216, L_TABBAR_H = 54, L_CARDS_TOP = 302, L_CARD_H = 432;
    /** Without the tabs (Continue watching) the cards start higher and are taller, ending where the tabbed ones end. */
    private static final float L_CARDS_TOP_FLAT = 296, L_CARD_H_FLAT = 420;   // as designed (280 px of 720); the same air above (to the header) as below (to the apps)
    private static final float L_TALL_W = 336, L_CARD_GAP = 36, L_BUTTON = 72, L_BUTTON_GAP = 32, L_BUTTON_CY = 147;
    private static final float L_PLATTER_TOP = 835, L_TILE_H = 108;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final String[] TABS = {"Explorar", "Filmes", "Séries"};
    private static final String[] TABS_EN = {"Explore", "Movies", "Shows"};
    private static final String[] TABS_ES = {"Explorar", "Películas", "Series"};
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_MENU = 6, Z_PLAYER = 7;
    // Design palette: Lily Blossom, Sweet Peach, Tasty Melon (the focus ring) and Black Night (dark pills).
    private static final int LILY = Brand.LILY, PEACH = Brand.PEACH, MELON = Brand.MELON, PILL = Brand.INK;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Watched watched;
    private final NowPlaying nowPlaying = NowPlaying.get();
    private final Watched.Listener watchedListener = () -> { if (recentCards()) updateCards(); };
    private final NowPlaying.Listener playerListener = () -> onPlayer();
    private boolean accessOk;
    private final Spring[] playerFocus = {new Spring(0.3f, 0.82f, 0), new Spring(0.3f, 0.82f, 0), new Spring(0.3f, 0.82f, 0)};
    private int playerIndex = 1;
    private final RectF playerBox = new RectF();
    private final Path playerPath = new Path();
    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG), barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint playerTitle, playerArtist, chipText;
    private final CardText[] cardTexts = new CardText[3];   // the text inside each card (Borealis' own class)
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, sheetLayer, toastLayer, menuLayer, infoLayer, returnLayer;
    private int zone = Z_TOP;   // starts on the selected tab, so the first focus is obvious

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private List<Recs.Pick> cards = new ArrayList<>();
    private final Spring[] topFocus = new Spring[8];
    private final RectF[] topRects = new RectF[8];
    // HDMI menu
    private final Spring menu = new Spring(0.3f, 0.9f, 0);
    /** Each corner button blinks with the gradient fill when it is clicked. */
    private final Spring[] btnFlash = {new Spring(0.4f, 1f, 0), new Spring(0.4f, 1f, 0), new Spring(0.4f, 1f, 0), new Spring(0.4f, 1f, 0), new Spring(0.4f, 1f, 0)};
    private final List<MRow> menuRows = new ArrayList<>();
    private final RectF menuRow = new RectF();
    private boolean menuOpen, menuDirty;
    private int menuIndex, menuButton;
    private long menuAt;
    private final Spring[] cardFocus = new Spring[3];
    private final Spring[] cardGrow = new Spring[3];   // 1 = the wide (expanded) card, 0 = the compact ones
    private final Rect srcRect = new Rect();
    private final Map<String, Bitmap> ownCards = new HashMap<>();   // the user's own tile art (assets/cards/<key>.png)
    private final RectF[] cardRects = new RectF[3];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lTip;
    private Paint lHello, infoPaint, infoTime, infoDate, infoTemp, iconStroke, btnLabel, menuTag;
    /** The Settings dropdown, as designed (sizes of the 1280-wide design x1.5): title 17, description 13, right text 14. */
    private Paint menuTitle, menuTitleBold, menuDesc, menuRight, menuBorder;
    private static final int GREEN = 0xFF7FE3B0, GRAY = 0x73FFFFFF, ROW_FOCUS = 0xFF231F27;   // aurora-verde (connected) and white at 45 % (off): the brand's state colors
    private final Path iconPath = new Path();
    private Shader sysHi;
    /** While the design is being polished the update row is always shown (a sample); set to false to show it only when there is a new version. */
    private static final boolean SAMPLE_UPDATE = true;
    private final RectF sysBox = new RectF(), sysRow = new RectF(), airRect = new RectF();
    private Paint moreLabel, infoAmPm;
    private Paint panelTitle, sheetDesc, searchText;   // the open panel's top bar, title, description and search field
    private final Spring searchFocus = new Spring(0.26f, 0.85f, 0);
    private boolean searchOn;                 // the focus is on the search field (above the first row)
    private String query = "";                // what the panel is filtered by ("" = every app)
    private String pillSrc = "", pillMain = "", pillSuffix;
    private float[] sysTop = new float[0];
    private float sysRowH;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF(), pillRect = new RectF(), iconRect = new RectF();
    private Bitmap wave;          // the static wave background (assets/wave.jpg)
    private boolean waveTried;
    private int waveIdx = -1;     // which wallpaper `wave` holds (Prefs.wallpaper())
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // The TV's video inputs (the dropdown's "Entradas" row)
    private List<HomeApps.Item> inputItems = new ArrayList<>();

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private List<HomeApps.Item> panel = new ArrayList<>();   // pinned apps + the rest A-Z
    private int pinnedCount;
    /** Empty slots of the 7-app shelf: a dashed outline with a plus; OK on one opens the shelf editor. */
    private final Spring[] phFocus = new Spring[MAX_COLS];
    { for (int i = 0; i < MAX_COLS; i++) phFocus[i] = new Spring(0.3f, 0.82f, 0); }
    // Icons and layout (Settings > Home screen): columns 6 / 8 / 10 and the shape of the tiles. Design units (1920 x 1080).
    private int cols = 8, shape = 0, gridRows = 5;
    private float cellW, rowCell, openPitch, closedPitch, tileW, tileH, tileR;
    {
        cols = Prefs.iconCols();
        shape = Prefs.iconShape();
        gridRows = cols == 6 ? 4 : cols == 10 ? 6 : 5;
        cellW = (1728 - (cols - 1) * 24f) / cols;
        rowCell = (843 - (gridRows - 1) * 21f) / gridRows;
        openPitch = rowCell + 21;
        closedPitch = openPitch * 0.906f;
        float zone = rowCell - 39;   // the tile's room; the name goes under it
        if (shape == 0) {
            tileW = cellW;
            tileH = cellW * 0.5625f;
            if (tileH > zone) { tileH = zone; tileW = zone / 0.5625f; }
            tileR = tileH * 0.2f;
        } else {
            tileW = tileH = Math.min(Math.min(cellW * 0.5625f * 1.25f, cellW), Math.min(zone, cols == 6 ? 192f : 168f));
            tileR = shape == 1 ? tileW * 0.24f : tileW / 2f;
        }
    }
    private String[] nameFit = new String[0];
    private Paint panelName;
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int phCount() { return query.isEmpty() && pinnedCount > 0 && pinnedCount < cols ? 1 : 0; }   // the "+" of the next free place (none while the panel is filtered)
    private int phGrid = -1;   // with the "All Apps" panel open: which empty slot of its first row has the focus (-1 = none)
    /** The empty slot that has the focus now (0 = the first one), on the shelf or in the open panel; -1 if none. */
    private int phSlot() {
        if (phCount() == 0) return -1;
        if (sheetOpen) return phGrid;
        return zone == Z_SHELF && shelfIndex >= pinnedCount ? shelfIndex - pinnedCount : -1;
    }
    private boolean onPlaceholder() { return !sheetOpen && zone == Z_SHELF && phCount() > 0 && shelfIndex >= pinnedCount; }
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, gridDirty, motionDirty = true;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;
    /** A long OK on a shelf app opens its menu (move, hide, remove); "moving" is the mode where the arrows move that app. */
    private boolean moving;
    private final Runnable longOk = this::longOkFired;

    private void longOkFired() {
        if (!okDown || menuOpen || (pinMode && sheetOpen)) return;
        boolean here = sheetOpen ? phGrid < 0 && !searchOn && !hiddenOn && !panel.isEmpty() && gridIndex < panel.size()
                : zone == Z_SHELF && !onPlaceholder() && !shelf.isEmpty() && shelfIndex < shelf.size();
        if (!here) return;
        okDown = false;
        if (!ui.getBoolean("longOkUsed", false)) ui.edit().putBoolean("longOkUsed", true).apply();   // the hint is not needed any more
        openCardMenu();
    }

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint;
    private Paint sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted: the glass reads the background from where it really is. The
            // top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == STATUS || kind == PLATTER ? 0 : getTranslationY());   // the glass sheet is drawn in its open place and only slides
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case SHEET: drawSheet(c); break;
                case MENU: drawMenu(c); break;
                case INFO: applyTone(); drawInfoPill(c); break;
                case RETURN: drawReturn(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reload(); }
        @Override public void onInputAdded(String inputId) { reload(); }
        @Override public void onInputRemoved(String inputId) { reload(); }
    };

    /** While a song plays, its card moves once a second (the progress line and nothing else). */
    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            boolean live = false;
            for (Recs.Pick p : cards) live |= p != null && p.live;
            if (live && !sheetOpen) heroDirty = true;
            handler.postDelayed(this, live ? 250 : 1000);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        watched = Watched.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        menuLayer = new Layer(a, MENU);
        infoLayer = new Layer(a, INFO);
        returnLayer = new Layer(a, RETURN);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame).
        for (View v : new View[] {heroLayer, status, platter, tiles, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        // The menu is above the info pill: when it opens, its dim covers the whole screen, the pill included.
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, sheetLayer, toastLayer, infoLayer, menuLayer, returnLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Borealis");
        spoken.setTitle(this::a11yTitle);
        menuLayer.setVisibility(INVISIBLE);
        returnLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < 8; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardGrow[i] = new Spring(0.42f, 0.86f, i == 0 ? 1 : 0);
            cardRects[i] = new RectF();
        }
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setInputItems(HomeApps.allInputs(a));
    }

    /** Once a day (if left on): asks GitHub for a newer version; once a day per version, a notice says how to get it. */
    private void checkForUpdate() {
        Update.check(getContext(), false, status -> noticeIfNewer());
        noticeIfNewer();
    }

    /** The bell is always the first corner button. */
    private int bellOffset() {
        return !lightButtons.isEmpty() && lightButtons.get(0).kind == HomeApps.UPDATE ? 1 : 0;
    }

    /** True while the bell carries a new version (its dot and its name). */
    private boolean bellUpdate;

    /** The bell stays; when a new version is found (or installed) its name and dot change. The focus stays where it is. */
    private void refreshUpdateButton() {
        boolean want = !Update.newer(getContext()).isEmpty();
        if (want == bellUpdate && !lightButtons.isEmpty()) return;
        makeLightButtons();
        layoutButtons();
        retarget(true);
        status.invalidate();
    }

    /** The bell was pressed: say what is new and offer to install it. */
    private void askUpdate() {
        final String version = Update.newer(getContext());
        if (version.isEmpty() || !(activity instanceof HomeActivity)) return;
        ((HomeActivity) activity).showNotice(false,
                Ui.tr("Nova versão: ", "New version: ", "Nueva versión: ") + version,
                Ui.tr("Atualizar leva menos de um minuto. O Android pedirá uma última confirmação para instalar.",
                        "Updating takes under a minute. Android will ask one last confirmation to install.",
                        "Actualizar toma menos de un minuto. Android te pedirá una última confirmación para instalar."),
                Ui.tr("Atualizar agora", "Update now", "Actualizar ahora"), this::startUpdate,
                Ui.tr("Agora não", "Not now", "Ahora no"), null);
    }

    private void startUpdate() {
        final Context c = getContext();
        if (!Update.canInstall(c)) {   // once: Android's own switch for "install apps from this source"
            if (activity instanceof HomeActivity) {
                ((HomeActivity) activity).showNotice(false,
                        Ui.tr("Uma permissão, só uma vez", "One permission, just once", "Un permiso, solo una vez"),
                        Ui.tr("Na próxima tela ative \"Permitir desta fonte\" e volte. Depois toque no sino de novo.",
                                "On the next screen turn on \"Allow from this source\" and come back. Then press the bell again.",
                                "En la siguiente pantalla activa \"Permitir de esta fuente\" y vuelve. Luego presiona la campana otra vez."),
                        "OK", () -> Update.askPermission(activity), null, null);
            }
            return;
        }
        if (Update.lowSpace(c)) {
            showToast(Update.lowSpaceMessage(c));
            return;
        }
        showToast(Ui.tr("Baixando a atualização…", "Downloading the update…", "Descargando la actualización…"));
        Update.install(c, (ok, error) -> {
            if (!ok && Update.LOW_SPACE.equals(error)) showToast(Update.lowSpaceMessage(c));
            else if (!ok) showToast(Ui.tr("Não foi possível instalar agora (" + error + ").", "Could not install right now (" + error + ").",
                    "No se pudo instalar ahora (" + error + ")."));
        });
    }

    private void noticeIfNewer() {
        refreshUpdateButton();
        String newer = Update.newer(getContext());
        if (newer.isEmpty()) return;
        String key = newer + ":" + System.currentTimeMillis() / (24L * 3600 * 1000);
        if (key.equals(Prefs.updateShown())) return;
        Prefs.setUpdateShown(key);
        showToast(Update.message(newer));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    private boolean keepSheet;
    private long returnFadeAt = -1;   // back from the settings: the wallpaper is there at once and the home comes in over it, softly
    private static final long RETURN_FADE_MS = 420;

    private float returnFade() {
        if (returnFadeAt < 0) return 1;
        float t = (SystemClock.uptimeMillis() - returnFadeAt) / (float) RETURN_FADE_MS;
        if (t >= 1) {
            returnFadeAt = -1;
            return 1;
        }
        float inv = 1 - Ui.clamp(t);
        return 1 - inv * inv * inv;
    }

    void resume() {
        final String fromDream = DreamInfo.takeOpenRequest(getContext());   // OK on the screensaver's widget
        if (fromDream != null && !fromDream.isEmpty()) handler.postDelayed(() -> DreamInfo.launch(activity, fromDream), 250);
        checkForUpdate();
        if (running) return;
        running = true;
        ensureSoftWall();   // ahead of time: for the panel and for Settings
        clockMinute = -1;   // the time format may have changed in Settings
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        watched.addListener(watchedListener);
        nowPlaying.addListener(playerListener);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        handler.removeCallbacks(liveTick);
        handler.postDelayed(liveTick, 1000);
        checkNotices();
        returnPending = true;
        startReturn();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen && !keepSheet) closeSheet();   // (coming back from the hidden apps screen, All apps stays open)
        keepSheet = false;
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(liveTick);
        data.removeListener(this);
        recs.removeListener(this);
        watched.removeListener(watchedListener);
        nowPlaying.removeListener(playerListener);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, sheet closed. */
    void reset(boolean animated) {
        if (animated && sheetOpen) closeSheet();   // Home with all the apps open: the panel goes down (swap down), it does not jump
        if (animated) homePressedAt = SystemClock.uptimeMillis();   // the Home button: the window is still arriving when we resume (see startReturn)
        zone = !tabsVisible() && !cards.isEmpty() ? Z_CARDS : Z_TOP;
        shelfIndex = 0;
        sheetOpen = false;
        if (animated) sheet.target = 0;
        else sheet.snap(0);
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        cardIndex = 0;
        menuOpen = false;
        menu.snap(0);
        if (tab != 0) selectTab(0);
        topIndex = topHome();
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        String newVersion = Update.newer(getContext());
        bellUpdate = !newVersion.isEmpty();
        // One corner button, "System" (Wi-Fi, Bluetooth, inputs, settings, news and the update in its menu); a new version gives it a dot.
        lightButtons.add(HomeApps.action(HomeApps.SYSTEM, "system", Ui.tr("Ajustes", "Settings", "Ajustes"), "settings"));
    }

    private static String[] tabs() {
        return Ui.es ? TABS_ES : Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(815 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame.
        measure(platter, width, height);   // the glass sheet (full screen; it only slides)
        measure(tiles, width, Math.round(270 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(menuLayer, width, height);   // full screen: the Settings menu dims everything behind it
        measure(infoLayer, width, Math.round(190 * kk));   // tall enough for the pill beside the corner buttons
        measure(returnLayer, width, height);
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, 0);
        place(tiles, 0, Math.round(790 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        place(menuLayer, 0, 0);
        place(infoLayer, 0, 0);
        returnLayer.layout(0, 0, width, height);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        startReturn();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
    }

    private void makePaints() {
        panelTitle = sheetDesc = searchText = null;   // made again (at the new size) when the panel next draws
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        // "¿Qué quieres ver hoy?" in Gloock, as designed (42 px of the 1280 design x1.5); the greeting above it stays Montserrat.
        lTitle = Ui.text(Logo.wordmark(getContext()), 63 * k, L_INK);
        lTab = Ui.text(Ui.medium, 24 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 24 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 27 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 22 * k, L_DIM);
        chipText = Ui.text(Ui.semibold, 18 * k, 0xFFFFFFFF);
        for (int i = 0; i < 3; i++) cardTexts[i] = new CardText(k);
        playerTitle = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
        playerArtist = Ui.text(Ui.medium, 17 * k, 0xB8FFFFFF);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lightGradient = null;
        lHello = Ui.text(Ui.medium, 27 * k, L_INK);
        btnLabel = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        menuTag = Ui.text(Ui.medium, 20 * k, 0xFFFFFFFF);
        menuTitle = Ui.text(Ui.semibold, 25.5f * k, 0xFFFFFFFF);
        menuTitleBold = Ui.text(Ui.bold, 25.5f * k, 0xFFFFFFFF);
        menuDesc = Ui.text(Ui.medium, 19.5f * k, 0xBDFFFFFF);
        menuRight = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        moreLabel = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        moreLabel.setShadowLayer(8 * k, 0, 2 * k, 0x99000000);
        menuBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        menuBorder.setStyle(Paint.Style.STROKE);
        menuBorder.setStrokeWidth(1.5f * k);
        menuBorder.setColor(0x24FFFFFF);
        infoPaint = Ui.numbers(Ui.medium, 22 * k, 0xFFFFFFFF);
        // The pill, as designed (Montserrat, sizes of the 1280-wide design x1.5): time bold 22, day medium 16, temperature semibold 16.
        infoTime = Ui.numbers(Ui.bold, 33 * k, 0xFFFFFFFF);
        infoDate = Ui.numbers(Ui.medium, 24 * k, 0xBDFFFFFF);
        infoAmPm = Ui.numbers(Ui.semibold, 20 * k, 0xBDFFFFFF);
        infoTemp = Ui.numbers(Ui.semibold, 24 * k, 0xFFFFFFFF);
        iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);
        iconStroke.setStrokeWidth(3.0f * k);
        iconStroke.setColor(0xFFFFFFFF);
        for (Paint p : new Paint[] {lHello, lTitle, lCardTitle, lCardMeta}) p.setShadowLayer(14 * k, 0, 2 * k, 0x99000000);
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt), growMoving = step(cardGrow, dt);
        if (growMoving) layoutCards();
        if (topMoving) layoutButtons();
        boolean menuMoving = menu.step(dt), flashMoving = false;
        for (Spring sp : btnFlash) flashMoving |= sp.step(dt);
        if (flashMoving) status.invalidate();
        if (menuMoving || menuDirty) {
            menuLayer.invalidate();
            menuDirty = false;
        }
        menuLayer.setVisibility(menu.value > 0.002f || menuOpen || moving ? VISIBLE : INVISIBLE);
        if (afterMenuGone != null) {   // Settings wait until the screen has really been drawn without the menu (a slow TV needs more than a fixed delay)
            if (menuLayer.getVisibility() != VISIBLE) menuGoneFrames++;
            if (menuGoneFrames >= 3 && up - menuGoneSince >= 90) {
                Runnable go = afterMenuGone;
                afterMenuGone = null;
                go.run();
            }
        }
        if (menuOpen && up - menuAt > (screenReader() ? 30000 : 4000)) closeMenu();
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving | growMoving;
        boolean gridMoving = step(gridFocus, dt) | step(phFocus, dt) | step(playerFocus, dt) | scroll.step(dt) | searchFocus.step(dt) | hiddenFocus.step(dt);
        boolean sheetMoving = sheet.step(dt);
        if (pinMode) {
            gridMoving |= stepPin(dt);
            if (!sheetOpen && sheet.value < 0.003f && pinFlyItem == null) pinFinished();
        }
        boolean returning = tickReturn(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (returnFadeAt >= 0) motionDirty = true;
        if (sheetMoving || motionDirty) updateMotion();
        if (DRIFT && w > 0) {   // oversized background, drifting slowly (only a view transform: nothing is redrawn)
            // The drift advances only while the home runs (and stops when a screen is about to open over it): after Settings or any
            // other screen the background continues where it was, matching the captured picture, instead of jumping to the clock.
            if (up >= driftFreezeUntil) driftT += dt;
            float t = driftT;
            sky.setScaleX(1.12f);
            sky.setScaleY(1.12f);
            sky.setTranslationX((float) Math.sin(t * 0.24) * 0.045f * w);
            sky.setTranslationY((float) Math.cos(t * 0.16) * 0.045f * h);
        }
        // Opening and closing the panel only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (heroDirty || heroFading || bgFading) infoLayer.invalidate();
        if (gridMoving || gridDirty || sheetMoving) sheetLayer.invalidate();   // the rows spread out while the panel rises
        if (bgFading) sky.invalidate();
        if (bgFading || (ghost != null && SystemClock.uptimeMillis() - ghostAt < 220)) platter.invalidate();   // (the copy of the screen fades in)
        tilesDirty = statusDirty = heroDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            heroLayer.invalidate();
            infoLayer.invalidate();   // the date and time pill has its own layer (it stays when the panel rises)
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
        }

        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** The apps panel: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float s = Ui.clamp(sheet.value);
        float home = 1 - s;
        // The swap: the home (greeting, cards, clock, buttons) slides up and fades while the full-screen glass rises from the bottom
        // (only cached layers move, all with the panel's own spring, so it is one gesture).
        float away = 1 - smoothstep(0f, 0.55f, s), rise = -HOME_RISE * k * s;
        final float rf = returnFade();
        show(heroLayer, rf * (Ui.LEGACY ? away : 0.30f + 0.70f * away), rise);   // the cards stay faintly visible through the pane
        blurBehind(s);
        show(status, away * rf, rise);
        infoLayer.setAlpha(away * rf);
        infoLayer.setTranslationY(rise);
        // The glass sheet: a pane that rises from the bottom edge over the (sharp) home, drawn once in its open place; no fading,
        // so what is behind it is covered by glass as it passes, like a real pane sliding up.
        show(platter, s > 0.002f ? 1 : 0, (1 - s) * h);
        show(tiles, 0, 0);
        sheetLayer.setVisibility(panel.isEmpty() && !(sheetOpen && !query.isEmpty()) ? INVISIBLE : VISIBLE);   // a search with no result still shows the field
        sheetLayer.setAlpha(rf);
        sheetLayer.setTranslationY((1 - sheet.value) * (P_CLOSED_TOP - P_TOP) * k);   // at rest it shows its first row at the bottom
    }

    private final Object[] blurEffects = new Object[9];   // RenderEffect (Android 12+), kept as Object so older systems never load that class
    private int blurStep = -1;
    private static final float BLUR_MAX = 26f;   // design pixels at the panel's full opening (the same soft look as Settings)

    /** Android 12+: the real home (wallpaper and cards) blurs live while the panel comes up, in steps so the effect is reused. */
    private void blurBehind(float s) {
        if (Ui.LEGACY || android.os.Build.VERSION.SDK_INT < 31) return;
        int step = Math.round(Ui.clamp(s) * (blurEffects.length - 1));
        if (step == blurStep) return;
        blurStep = step;
        Object fx = null;
        if (step > 0) {
            fx = blurEffects[step];
            if (fx == null) {
                fx = Blur31.make(BLUR_MAX * k * step / (blurEffects.length - 1));
                blurEffects[step] = fx;
            }
        }
        Blur31.set(sky, fx);
        Blur31.set(heroLayer, fx);
    }

    /** Only touched on Android 12+ (a separate class, so the older systems never resolve RenderEffect). */
    @android.annotation.TargetApi(31)
    private static final class Blur31 {
        static Object make(float r) {
            return android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP);
        }

        static void set(View v, Object fx) {
            v.setRenderEffect((android.graphics.RenderEffect) fx);
        }
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Recs.Pick x = a.get(i), y = b.get(i);
            if (!Recs.key(x).equals(Recs.key(y)) || x.progress != y.progress || !String.valueOf(x.meta).equals(String.valueOf(y.meta)) || !String.valueOf(x.info).equals(String.valueOf(y.info))
                    || (x.backdrop != null && !x.backdrop.equals(y.backdrop))) return false;   // "Seguir viendo" changes while the key stays
        }
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lHello.setColor(Ui.alpha(tone(L_INK, WHITE), 0.74f));
        lTab.setColor(tone(L_DIM, 0xCCFFFFFF));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFFE0E0E6));
        lTip.setColor(tone(L_INK, WHITE));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            int[] from = frostOf(bgFrom), to = frostOf(bgTo);
            for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
            scene.setFrost(frostMix);
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
        drawBottomShade(c);   // on the full-screen layer (the hero layer is cut at 75% of the height)
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Shelf

    private void setShelf(List<HomeApps.Item> list) {
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!sheetOpen) rebuildPanel();
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k, th = L_TILE_H * k;
        float full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(TILE_MAX_W * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = L_PLATTER_TOP * k;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** The system is low on memory: let go of the finished tile images (they are made again when a tile needs them). */
    void trimMemory() {
        crispLogos.clear();
        ownCards.clear();
        invalidate();
    }

    /**
     * The panel's glass sheet: the whole screen in smoked, frosted glass that blurs the wallpaper behind it (the same soft
     * blur as the Settings screens). It is drawn once, in its open place, and only slides (it changes with the background).
     */
    private void drawPlatter(Canvas c) {
        ringRect.set(0, 0, w, h);
        if (ghost != null) {
            // Like tinted, matte glass: the real home (wallpaper, cards, shelf, greeting) as it was, tiny and blurred, under a veil.
            // It fades in over its first moments (it arrives a frame or two after the panel starts to rise).
            float f = Ui.clamp((SystemClock.uptimeMillis() - ghostAt) / 160f);
            ghostPaint.setAlpha(Math.round(255 * f));
            c.drawBitmap(ghost, null, ringRect, ghostPaint);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF171519, 0.36f * f));
            c.drawRect(ringRect, fill);
        } else if (ghostFailed) {
            scene.glass(c, ringRect, 1, SMOKE, 0.4f, 0, 0, 0.02f, 1, 1);   // no copy of the screen on this TV: plain frosted glass
        } else if (Ui.LEGACY) {
            // Android 11 and older cannot blur the live home: the pane is frosted glass over the blurred wallpaper (as in Settings)
            if (softWall != null && softWallIdx == Prefs.wallpaper()) {
                c.drawBitmap(softWall, null, ringRect, softPaint);
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFF171519, 0.5f));
                c.drawRect(ringRect, fill);
            } else {
                scene.glass(c, ringRect, 1, SMOKE, 0.4f, 0, 0, 0.02f, 1, 1);
            }
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.14f));
            c.drawRect(0, 0, w, Math.max(1f, 1.5f * k), fill);
        } else {
            // Translucent by itself: a smoked veil over the live home (nothing is copied), with a thin light edge on top.
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF171519, 0.58f));
            c.drawRect(ringRect, fill);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.14f));
            c.drawRect(0, 0, w, Math.max(1f, 1.5f * k), fill);
        }
    }

    private long ghostAt;
    private boolean ghostFailed;

    /** Android 11 and older cannot blur the live home: they use the blurred copy of the screen (as in 3.47.25). Android 12+ blurs live. */
    private static boolean screenCopy() {
        return false;   // test 3.47.31: the Settings-style blur of the wallpaper instead (softWall below)
    }

    private Bitmap softWall;   // the wallpaper tiny and blurred, as behind the Settings screens
    private int softWallIdx = -1;
    private boolean softLoading;
    private final Paint softPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private void ensureSoftWall() {
        final int wp = Prefs.wallpaper();
        if (softLoading || (softWall != null && softWallIdx == wp)) return;
        softLoading = true;
        LOADER.execute(() -> {
            Bitmap soft = null;
            try (java.io.InputStream in = getContext().getAssets().open(Prefs.WALLPAPER_FILES[wp])) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap pic = android.graphics.BitmapFactory.decodeStream(in, null, o);
                if (pic != null) {
                    soft = SettingsActivity.SettingsView.softBackdrop(pic);
                    pic.recycle();
                }
            } catch (java.io.IOException | RuntimeException | OutOfMemoryError ignored) {
            }
            final Bitmap result = soft;
            handler.post(() -> {
                softLoading = false;
                if (result != null) {
                    softWall = result;
                    softWallIdx = wp;
                    SettingsActivity.softCache = result;   // Settings opens with it already in place
                    SettingsActivity.softCacheIdx = wp;
                    platter.invalidate();
                }
            });
        });
    }

    private static final int GHOST_W = 192, GHOST_H = 108;
    private Bitmap ghost;   // the screen as it was when the panel opened, tiny and blurred: what shows through the glass
    private final Paint ghostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /**
     * Copies what is on screen (the real home) into a tiny picture and blurs it. The copy is asked of the window and
     * arrives a frame or two later, so the panel's animation is never held up; the glass shows plain until it is ready.
     */
    private boolean snapshotHome() {
        ghost = null;
        ghostFailed = false;
        if (!screenCopy() || w == 0 || h == 0) return false;
        if (android.os.Build.VERSION.SDK_INT < 26) {
            ghostFailed = true;
            return false;
        }
        final long t0 = SystemClock.uptimeMillis();
        copyAt = t0;
        try {
            final Bitmap shot = Bitmap.createBitmap(GHOST_W, GHOST_H, Bitmap.Config.ARGB_8888);
            int[] at = new int[2];
            getLocationInWindow(at);
            android.view.PixelCopy.request(activity.getWindow(), new android.graphics.Rect(at[0], at[1], at[0] + w, at[1] + h), shot,
                    result -> {
                        if (result != android.view.PixelCopy.SUCCESS) {
                            AppLog.w("home snapshot failed: " + result);
                            ghostFailed = true;
                            platter.invalidate();
                            riseSheet();
                            return;
                        }
                        LOADER.execute(() -> {
                            blurGhost(shot);
                            handler.post(() -> {
                                if (!sheetOpen && sheet.value < 0.01f) return;   // closed again in the meantime
                                ghost = shot;
                                ghostAt = SystemClock.uptimeMillis() - 160;   // already there: it is shown at once, and the panel rises over it
                                platter.invalidate();
                                riseSheet();
                                AppLog.w("home snapshot: " + (SystemClock.uptimeMillis() - t0) + " ms");
                            });
                        });
                    }, handler);
        } catch (RuntimeException e) {   // the glass then shows plain
            AppLog.w("home snapshot failed: " + e);
            ghostFailed = true;
            return false;
        }
        return true;
    }

    private long copyAt;
    private final Runnable rise = this::riseSheet;
    private boolean sheetWaiting;

    /** The panel starts to rise (once the copy of the screen is ready, so it never shows half-made). */
    private void riseSheet() {
        handler.removeCallbacks(rise);
        if (!sheetWaiting) return;
        sheetWaiting = false;
        AppLog.w("panel waited " + (SystemClock.uptimeMillis() - copyAt) + " ms for the screen copy");
        if (sheetOpen) sheet.target = 1;
    }

    /** Three box-blur passes over the picture (it is opaque: a screen copy), on a tiny size so it takes a few milliseconds. */
    private static void blurGhost(Bitmap bmp) {
        int bw = bmp.getWidth(), bh = bmp.getHeight(), n = bw * bh;
        int[] px = new int[n];
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh);
        int[][] ch = new int[3][n];   // red, green, blue
        for (int i = 0; i < n; i++) {
            ch[0][i] = (px[i] >> 16) & 255;
            ch[1][i] = (px[i] >> 8) & 255;
            ch[2][i] = px[i] & 255;
        }
        for (int c = 0; c < 3; c++) for (int pass = 0; pass < 3; pass++) boxBlur(ch[c], bw, bh, 4);
        for (int i = 0; i < n; i++) px[i] = 0xFF000000 | (ch[0][i] << 16) | (ch[1][i] << 8) | ch[2][i];
        bmp.setPixels(px, 0, bw, 0, 0, bw, bh);
    }

    /** One box-blur pass of radius `r` over one channel (horizontal, then vertical), edges repeated. */
    private static void boxBlur(int[] v, int bw, int bh, int r) {
        int[] tmp = new int[Math.max(bw, bh)];
        int size = 2 * r + 1;
        for (int y = 0; y < bh; y++) {
            int row = y * bw;
            long sum = 0;
            for (int i = -r; i <= r; i++) sum += v[row + Math.max(0, Math.min(bw - 1, i))];
            for (int x = 0; x < bw; x++) {
                tmp[x] = (int) (sum / size);
                sum += v[row + Math.min(bw - 1, x + r + 1)] - v[row + Math.max(0, x - r)];
            }
            System.arraycopy(tmp, 0, v, row, bw);
        }
        for (int x = 0; x < bw; x++) {
            long sum = 0;
            for (int i = -r; i <= r; i++) sum += v[Math.max(0, Math.min(bh - 1, i)) * bw + x];
            for (int y = 0; y < bh; y++) {
                tmp[y] = (int) (sum / size);
                sum += v[Math.min(bh - 1, y + r + 1) * bw + x] - v[Math.max(0, y - r) * bw + x];
            }
            for (int y = 0; y < bh; y++) v[y * bw + x] = tmp[y];
        }
    }

    private Paint hintPaint;

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                drawShelfTile(c, i);
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    /** The finished tile art for an app (assets/cards/<key>.png), or null to draw the tile from its logo as before. */
    private Bitmap ownCard(String key) {
        if (key == null || tileRects.length == 0 || ownCards.containsKey(key)) return null;   // containsKey: no art for this app
        // Only the finished (shrunk) tile is kept in memory; the original is read again if the size ever changes.
        int tw = Math.round(tileRects[0].width() * (1 + FOCUS_GROW));
        String ck = "card:" + key + "@" + tw;
        Bitmap fit = crispLogos.get(ck);
        if (fit != null) return fit;
        Bitmap raw = null;
        try (java.io.InputStream in = getContext().getAssets().open("cards/" + key + ".png")) {
            raw = android.graphics.BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
        }
        if (raw == null) {
            ownCards.put(key, null);
            return null;
        }
        // Shrunk in steps to the size it has when focused (sharper than letting the GPU shrink it in one go).
        int th = Math.round(tw * raw.getHeight() / (float) raw.getWidth());
        if (tw >= raw.getWidth()) {
            fit = raw;
        } else {
            fit = downscale(raw, tw, th);
            raw.recycle();
        }
        crispLogos.put(ck, fit);
        return fit;
    }

    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Bitmap own = ownCard(it.key);
        if (own != null) {   // the design's finished tile: drawn as is (its corners are already transparent)
            c.drawBitmap(own, null, cell, bitmapPaint);
            if (fc > 0.01f) ring(c, cell, r, fc, 5 * k);
            return;
        }
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            tileFill.setShader(new LinearGradient(0, cell.top, 0, cell.bottom, style[0], style[1], Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, tileFill);
            tileFill.setShader(null);
            if (fc > 0.01f) {
                tileFill.setShader(new LinearGradient(0, cell.top, 0, cell.top + cell.height() * 0.5f,
                        Ui.alpha(0xFFFFFFFF, 0.08f * fc), 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRoundRect(cell, r, r, tileFill);
                tileFill.setShader(null);
            }
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
        if (fc > 0.01f) ring(c, cell, r, fc, 5 * k);   // focus: gradient ring
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 9 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    /** Like roundImage, but the photo keeps its proportions (center-cropped to fill the cell), with an opacity. */
    private final java.util.Map<String, Bitmap> musicCovers = new java.util.HashMap<>();

    /** A song's cover from its file (small: decoded once and kept while the file stays the same). */
    private Bitmap musicCoverBitmap(java.io.File f) {
        String key = f.getPath() + f.lastModified();
        Bitmap b = musicCovers.get(key);
        if (b == null) {
            b = android.graphics.BitmapFactory.decodeFile(f.getPath());
            if (b == null) return null;
            if (musicCovers.size() > 4) musicCovers.clear();
            musicCovers.put(key, b);
        }
        return b;
    }

    /**
     * A song's cover, square and sharp, in the middle of the card and over its shade. On the big card it is centered
     * between the top edge and the title (the text stops before it); on a narrow card it sits above the text. Returns where the text must stop.
     */
    private float drawMusicCover(Canvas c, RectF cell, Recs.Pick p, boolean wide, float titleTop) {
        Bitmap bmp = musicCoverBitmap(p.cover);
        if (bmp == null) return cell.right;
        float size = Math.min(cell.height() * 0.5f, cell.width() * 0.62f);
        float cx = cell.centerX(), top = cell.centerY() - size / 2;
        if (wide) {   // centered between the card's top edge and where the song's title begins
            float zone = titleTop - cell.top;
            size = Math.min(size, zone - 24 * k);
            top = cell.top + (zone - size) / 2;
        } else {
            float textTop = cell.bottom - (104 + 34 + 30) * k;   // where the title begins on a narrow card
            top = Math.max(cell.top + 14 * k, Math.min(top, textTop - 10 * k - size));
        }
        tmp.set(cx - size / 2, top, cx + size / 2, top + size);
        float rad = size * 0.1f;
        shadowDeep.setColor(0x99000000);
        shadows.draw(c, tmp, rad, 26 * k, shadowDeep);
        clip.reset();
        clip.addRoundRect(tmp, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        bitmapPaint.setAlpha(255);
        c.drawBitmap(bmp, null, tmp, bitmapPaint);
        c.restore();
        return wide ? cx - size / 2 - 20 * k : cell.right;
    }

    private void coverImage(Canvas c, Bitmap image, RectF cell, float r, float alpha) {
        float iw = image.getWidth(), ih = image.getHeight();
        float scale = Math.max(cell.width() / iw, cell.height() / ih);
        if (!Ui.LEGACY) {
            float sw = cell.width() / scale, sh = cell.height() / scale;
            srcRect.set(Math.round((iw - sw) / 2), Math.round((ih - sh) / 2), Math.round((iw + sw) / 2), Math.round((ih + sh) / 2));
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            bitmapPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
            c.drawBitmap(image, srcRect, cell, bitmapPaint);
            bitmapPaint.setAlpha(255);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(cell.centerX() - iw * scale / 2, cell.centerY() - ih * scale / 2);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        imageFill.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setAlpha(255);
        imageFill.setShader(null);
    }

    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size: when focused it is drawn pixel for
     * pixel, without resampling.
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null);
        if (logo == null) return;
        float scale = s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width());
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Video inputs

    private void setInputItems(List<HomeApps.Item> list) {
        inputItems = list;
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && (zone == Z_TOP || zone == Z_MENU) ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        heroChanged |= aim(cardGrow, cards.isEmpty() ? 0 : cardIndex, snap);
        if (snap) layoutCards();
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        aim(playerFocus, !sheetOpen && zone == Z_PLAYER ? playerIndex : -1, snap);
        retargetGrid(snap);
        gridDirty = true;
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        rebuildPanel();
    }

    /** Pinned apps (the shelf's) first, then every other app in alphabetical order. */
    private void rebuildPanel() {
        List<HomeApps.Item> all = new ArrayList<>();
        List<HomeApps.Item> overflow = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.APP) { if (all.size() < cols) all.add(it); else overflow.add(it); }   // more apps than columns: the last ones leave the shelf (still in All apps)
        java.util.Set<String> pinned = new java.util.HashSet<>();
        for (HomeApps.Item it : all) if (it.pkg != null) pinned.add(it.pkg);
        pinnedCount = all.size();
        List<HomeApps.Item> rest = new ArrayList<>(overflow);
        for (HomeApps.Item it : others) if (it.pkg == null || !pinned.contains(it.pkg)) rest.add(it);   // no duplicates
        java.util.Collections.sort(rest, (x, y) -> x.label.toLowerCase(java.util.Locale.ROOT).compareTo(y.label.toLowerCase(java.util.Locale.ROOT)));
        all.addAll(rest);
        if (!query.isEmpty()) {   // the search field: only the apps whose name has what was typed (the pinned ones stay first)
            String q = TextEntry.plain(query);
            List<HomeApps.Item> kept = new ArrayList<>();
            int keptPinned = 0;
            for (int i = 0; i < all.size(); i++) {
                HomeApps.Item it = all.get(i);
                if (!TextEntry.plain(it.label).contains(q)) continue;
                kept.add(it);
                if (i < pinnedCount) keptPinned++;
            }
            all = kept;
            pinnedCount = keptPinned;
        }
        panel = all;
        nameFit = new String[panel.size()];
        gridFocus = new Spring[panel.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridIndex = Math.min(gridIndex, Math.max(0, panel.size() - 1));
        retargetGrid(true);
        phGrid = -1;
        gridDirty = true;
    }

    private void openSheet() {
        if (panel.isEmpty()) return;
        hiddenOn = false;
        ensureSoftWall();
        boolean copying = snapshotHome();   // the home as it rests, before anything moves
        sheetOpen = true;
        searchOn = false;
        platter.invalidate();   // the glass is drawn again with the home as it is now
        gridIndex = zone == Z_SHELF ? Math.min(onPlaceholder() ? pinnedCount : shelfIndex, panel.size() - 1) : 0;   // the same app stays focused
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheetWaiting = true;
        if (copying) handler.postDelayed(rise, 250);   // the copy of the screen takes a frame or two; do not wait more than this
        else riseSheet();
        gridDirty = true;
    }

    /** Opens Borealis's keyboard for the search field: the matching apps show as suggestions (OK on one opens it); OK on the typed text filters the panel. */
    private void openSearch() {
        TextEntry.Request r = new TextEntry.Request();
        r.title = Ui.tr("Buscar app", "Search app", "Buscar app");
        r.hint = Ui.tr("Nome do app", "App name", "Nombre de la app");
        r.initial = query;
        r.words = false;
        r.maxLen = 24;
        final List<HomeApps.Item> pool = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.APP) pool.add(it);
        for (HomeApps.Item it : others) if (it.kind == HomeApps.APP && !pool.contains(it)) pool.add(it);
        r.source = (text, cb) -> {
            String q = TextEntry.plain(text);
            List<TextEntry.Option> starts = new ArrayList<>(), has = new ArrayList<>();
            if (!q.isEmpty()) {
                for (HomeApps.Item it : pool) {
                    String name = TextEntry.plain(it.label);
                    if (name.startsWith(q)) starts.add(new TextEntry.Option(it.label, it));
                    else if (name.contains(q)) has.add(new TextEntry.Option(it.label, it));
                }
            }
            starts.addAll(has);
            cb.done(text, starts, null);
        };
        r.done = (text, option) -> {
            if (option != null && option.tag instanceof HomeApps.Item) {   // a suggestion: open that app
                HomeApps.Item it = (HomeApps.Item) option.tag;
                click.open();
                boolean opened = HomeApps.open(activity, it, this, Math.round(w / 2f - 96 * k), Math.round(h / 2f - 54 * k),
                        Math.round(192 * k), Math.round(108 * k));
                if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ", "No se pudo abrir ") + it.label + ".");
                return;
            }
            setQuery(text);
        };
        TextEntry.show(getContext(), r);
    }

    /** Filters the panel by name ("" shows every app) and puts the focus back on the first result. */
    private void setQuery(String text) {
        String t = text == null ? "" : text.trim();
        if (t.equals(query)) return;
        query = t;
        gridIndex = 0;
        phGrid = -1;
        scroll.snap(0);
        rebuildPanel();
        gridDirty = true;
    }

    private void closeSheet() {
        searchOn = false;
        hiddenOn = false;
        sheetWaiting = false;
        handler.removeCallbacks(rise);
        if (!query.isEmpty()) {   // the filter ends with the panel
            query = "";
            rebuildPanel();
        }
        if (phGrid >= 0) {   // from an empty slot: back on the same empty slot of the shelf
            shelfIndex = pinnedCount + phGrid;
            phGrid = -1;
        } else if (gridIndex < Math.min(cols, pinnedCount)) shelfIndex = gridIndex;   // back on the same app of the first row
        else shelfIndex = Math.min(shelfIndex, Math.max(0, Math.min(cols, shelf.size()) - 1));
        zone = Z_SHELF;
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
    }

    private int pinnedRows() {
        return (pinnedCount + cols - 1) / cols;
    }

    private int panelRows() {
        return pinnedRows() + (panel.size() - pinnedCount + cols - 1) / cols;
    }

    private int rowOf(int i) {
        return i < pinnedCount ? i / cols : pinnedRows() + (i - pinnedCount) / cols;
    }

    private int colOf(int i) {
        return i < pinnedCount ? i % cols : (i - pinnedCount) % cols;
    }

    /** The item at a row and column (the last of the row if it is a short one). */
    private int idxAt(int row, int col) {
        int pr = pinnedRows();
        if (row < pr) return Math.min(row * cols + col, pinnedCount - 1);
        return Math.min(pinnedCount + (row - pr) * cols + col, panel.size() - 1);
    }

    /** Panel metrics blend from the resting state (home screen) to the open one. */
    private float openness() {
        return Ui.clamp(sheet.value);
    }

    private float firstTop() {
        float closed = P_CLOSED_CENTER - tileH / 2f, open = P_FIRST + (rowCell - 39 - tileH) / 2f;
        return closed + (open - closed) * openness();
    }

    private float pitch() {
        return closedPitch + (openPitch - closedPitch) * openness();
    }

    /** Cell of item i with the panel scrolled by `scrollRows` rows. */
    private void panelRect(int i, float scrollRows, RectF out) {
        float x = (MARGIN + colOf(i) * (cellW + 24) + (cellW - tileW) / 2f) * k;
        float y = (firstTop() + (rowOf(i) - scrollRows) * pitch()) * k;
        out.set(x, y, x + tileW * k, y + tileH * k);
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < phFocus.length; i++) {
            phFocus[i].target = phSlot() == i ? 1 : 0;
            if (snap) phFocus[i].snap(phFocus[i].target);
        }
        searchFocus.target = sheetOpen && searchOn ? 1 : 0;
        if (snap) searchFocus.snap(searchFocus.target);
        hiddenFocus.target = sheetOpen && hiddenOn ? 1 : 0;
        if (snap) hiddenFocus.snap(hiddenFocus.target);
        int focusAt = sheetOpen ? (phGrid >= 0 || searchOn || hiddenOn ? -1 : gridIndex) : zone == Z_SHELF && !onPlaceholder() ? shelfIndex : -1;   // closed: the home screen's first row (an empty slot is no app of the panel)
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = i == focusAt ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        if (!sheetOpen) {
            scroll.target = 0;
            if (snap) scroll.snap(0);
            spoken.focusMoved();
            return;
        }
        float room = (h - 24 * k) / k - P_FIRST - rowCell - (hiddenAvailable() ? HIDDEN_ROOM : 0);   // how far below the first row's top a row may still sit fully visible
        float rowsBelow = room / openPitch;
        int r = hiddenOn ? panelRows() - 1 : phGrid >= 0 ? 0 : rowOf(gridIndex);
        float target = scroll.target;
        if (r < target) target = r;
        else if (r - target > rowsBelow) target = r - rowsBelow;
        float max = Math.max(0, panelRows() - 1 - rowsBelow);
        scroll.target = Math.max(0, Math.min(max, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        if (pinMode) {
            drawPinSheet(c);
            return;
        }
        // The glass sheet itself is the platter layer (blurred wallpaper); here only what sits on it: the handle, the title,
        // the description and the search field. At rest the apps sit straight on the wallpaper.
        float shell = Ui.clamp(openness() * 1.5f);
        if (shell > 0.01f) {
            drawPanelHead(c, Ui.clamp(openness() * 2 - 0.6f));
        }
        drawPlayer(c);
        int n = panel.size();
        if (n == 0) return;
        c.save();
        // At rest only the first row shows (the second one is cut off just below it); the clip opens up with the panel.
        float o = openness(), closedBottom = (P_CLOSED_CENTER + tileH / 2f + 26) * k;
        float openBottom = hiddenAvailable() ? h - HIDDEN_ROOM * k : h;   // the grid ends above the "Hidden apps" button
        float closedTop = P_CLOSED_CENTER - tileH / 2f;
        c.clipRect(0, (closedTop - 40 + (P_CLIP - closedTop + 40) * Ui.clamp(o * 2)) * k, w,
                closedBottom + (openBottom - closedBottom) * Ui.clamp(o * 2));
        float sc = scroll.value;
        if (pinnedCount > 0 && pinnedCount < n) {   // thin white line between the pinned apps and all the apps
            float y = (firstTop() + (pinnedRows() - sc) * pitch() - (pitch() - tileH) / 2f) * k;
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.16f * Ui.clamp(openness() * 2)));
            c.drawRect(MARGIN * k, y - 0.75f * k, CONTENT_R * k, y + 0.75f * k, fill);
        }
        int focused = -1;
        for (int i = 0; i < n; i++) {
            panelRect(i, sc, tmp);
            if (tmp.bottom < P_CLIP * k - 20 * k || tmp.top + sheetLayer.getTranslationY() > h) continue;
            if (sheetOpen && phGrid < 0 && i == gridIndex) focused = i;
            if (i != focused) drawPanelCell(c, i);
        }
        if (focused >= 0) drawPanelCell(c, focused);   // the focused one last, over its neighbours
        for (int i = 0; i < phCount(); i++) drawPlaceholder(c, i, sc);
        c.restore();
        if (hiddenAvailable()) drawHiddenButton(c, sc);
        drawMoreHint(c);
    }

    /** The "Hidden apps" button under the last row: the same pill as the search field, with the crossed-out eye. */
    private void drawHiddenButton(Canvas c, float sc) {
        float a = Ui.clamp(openness() * 2 - 0.6f);
        if (a <= 0.01f || searchText == null) return;
        float f = Ui.clamp(hiddenFocus.value), s = 1 + 0.04f * f;
        float cx = w / 2f, cy = h - (HIDDEN_BOTTOM + SEARCH_H / 2f) * k;   // fixed at the bottom of the screen
        float hw = 215 * k * s, hh = SEARCH_H * k * s / 2, rad = hh;
        RectF box = new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
        if (f > 0.01f) {
            tmp.set(box);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.3f * f * a));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
        }
        fill.setShader(null);
        if (f < 0.99f) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f * (1 - f) * a));
            c.drawRoundRect(box, rad, rad, fill);
            menuBorder.setAlpha(Math.round(56 * (1 - f) * a));
            tmp.set(box.left + 0.75f * k, box.top + 0.75f * k, box.right - 0.75f * k, box.bottom - 0.75f * k);
            c.drawRoundRect(tmp, rad - 0.75f * k, rad - 0.75f * k, menuBorder);
        }
        if (f > 0.01f) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.16f * f * a));
            c.drawRoundRect(box, rad, rad, fill);
            ring(c, box, rad, f * a, 4.5f * k);
        }
        int ink = 0xFFFFFFFF;
        String label = Ui.tr("Apps ocultos", "Hidden apps", "Apps ocultas");
        int count = Prefs.hiddenApps().size();
        if (count > 0) label += " (" + count + ")";
        float isz = 30 * k * s, tw = searchText.measureText(label), gap = 14 * k;
        float x0 = cx - (isz + gap + tw) / 2f;
        Symbols.draw(getContext(), c, "visibility_off", x0 + isz / 2, cy, isz, Ui.alpha(ink, 0.85f * a));
        searchText.setColor(Ui.alpha(ink, 0.9f * a));
        c.drawText(label, x0 + isz + gap, cy + Ui.halfCap(searchText), searchText);
    }

    /** The open panel's head: title, how OK works, and the search field (it fades in as the panel opens). */
    private void drawPanelHead(Canvas c, float a) {
        if (a <= 0.01f) return;
        if (panelTitle == null) {
            panelTitle = Ui.text(Logo.wordmark(getContext()), 51 * k, 0xFFFFFFFF);
            sheetDesc = Ui.text(Ui.medium, 21 * k, 0xFFFFFFFF);
            searchText = Ui.text(Ui.medium, 22.5f * k, 0xFFFFFFFF);
        }
        String title = Ui.tr("Todos os apps", "All apps", "Todas las apps");
        String desc = Ui.tr("Segure OK sobre um app para movê-lo, fixá-lo ou removê-lo",
                "Hold OK on an app to move, pin or remove it", "Mantén OK sobre una app para moverla, fijarla o quitarla");
        float x = MARGIN * k;
        panelTitle.setAlpha(Math.round(255 * a));
        c.drawText(title, x - Ui.leftBearing(panelTitle, title), 108 * k, panelTitle);
        sheetDesc.setAlpha(Math.round(158 * a));
        if (!searchAvailable()) {   // few apps: no search field; the hint takes its place, on the right
            sheetDesc.setAlpha(Math.round(158 * a));
            c.drawText(desc, CONTENT_R * k - sheetDesc.measureText(desc), SEARCH_CY * k + Ui.halfCap(sheetDesc), sheetDesc);
            return;
        }
        c.drawText(desc, x - Ui.leftBearing(sheetDesc, desc), 146 * k, sheetDesc);
        // The search field: a pill like the clock's (white 8% and a thin border); focused, it turns white like the corner buttons.
        float f = Ui.clamp(searchFocus.value), s = 1 + 0.04f * f;
        float cx = (CONTENT_R - SEARCH_W / 2) * k, cy = SEARCH_CY * k, hw = SEARCH_W * k * s / 2, hh = SEARCH_H * k * s / 2;
        RectF box = new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
        float rad = hh;
        int layer = a < 0.99f ? c.saveLayerAlpha(box.left - 30 * k, box.top - 30 * k, box.right + 30 * k, box.bottom + 40 * k, Math.round(255 * a)) : -1;
        if (f > 0.01f) {
            tmp.set(box);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, 0.3f * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
        }
        fill.setShader(null);
        if (f < 0.99f) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f * (1 - f)));
            c.drawRoundRect(box, rad, rad, fill);
            menuBorder.setAlpha(Math.round(56 * (1 - f)));
            tmp.set(box.left + 0.75f * k, box.top + 0.75f * k, box.right - 0.75f * k, box.bottom - 0.75f * k);
            c.drawRoundRect(tmp, rad - 0.75f * k, rad - 0.75f * k, menuBorder);
        }
        if (f > 0.01f) {   // focused: the glass lights up a little and gets the ring; the text stays white
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.16f * f));
            c.drawRoundRect(box, rad, rad, fill);
            ring(c, box, rad, f, 4.5f * k);
        }
        // The magnifier and the text: the typed filter, or "Search app" in a softer tone.
        float ix = box.left + 36 * k * s, isz = 30 * k * s;
        int ink = 0xFFFFFFFF;
        drawMagnifier(c, ix, cy, isz, Ui.alpha(ink, query.isEmpty() ? 0.7f : 1f));
        String shown = query.isEmpty() ? Ui.tr("Buscar app", "Search app", "Buscar app") : query;
        searchText.setColor(Ui.alpha(ink, query.isEmpty() ? 0.7f : 1f));
        c.drawText(shown, ix + isz / 2 + 14 * k, cy + Ui.halfCap(searchText), searchText);
        if (layer >= 0) c.restoreToCount(layer);
    }

    /** A magnifying glass drawn with a circle and a line (Borealis' own, like the System button's sliders). */
    private void drawMagnifier(Canvas c, float cx, float cy, float size, int color) {
        float u = size / 24f, old = iconStroke.getStrokeWidth();
        iconStroke.setStrokeWidth(1.9f * u);
        iconStroke.setColor(color);
        c.drawCircle(cx - 1.5f * u, cy - 1.5f * u, 6.5f * u, iconStroke);
        c.drawLine(cx + 3.2f * u, cy + 3.2f * u, cx + 9 * u, cy + 9 * u, iconStroke);
        iconStroke.setStrokeWidth(old);
    }

    /** Under the first row of apps, a small down arrow and "All" say there are more; it fades out as the panel opens. */
    private void drawMoreHint(Canvas c) {
        float a = 1 - Ui.clamp(openness() * 4);
        if (a <= 0.01f || moreLabel == null) return;
        String label = Ui.tr("Ver todos os apps", "See all apps", "Ver todas las apps");
        float tw = moreLabel.measureText(label), icon = 30 * k, gap = 8 * k;
        float x = w / 2f - (icon + gap + tw) / 2f, cy = (P_CLOSED_CENTER + tileH / 2f + 42) * k;
        Symbols.draw(getContext(), c, "chevron_down", x + icon / 2, cy, icon, Ui.alpha(0xFFFFFFFF, 0.8f * a));
        moreLabel.setColor(Ui.alpha(0xFFFFFFFF, 0.85f * a));
        c.drawText(label, x + icon + gap, cy + Ui.halfCap(moreLabel), moreLabel);
    }

    /**
     * The player strip on the glass above the first row of apps: cover, title and artist, and previous / pause or play /
     * next. It appears only while a session of audio exists, and fades out as the "All Apps" sheet rises.
     */
    private void drawPlayer(Canvas c) {
        if (!PLAYER_STRIP || !nowPlaying.visible() || playerTitle == null) return;
        float a = 1 - Ui.clamp(sheet.value * 3);
        if (a <= 0.01f) return;
        float top = 112 * k, size = 54 * k, x0 = MARGIN * k;
        int layer = -1;
        if (a < 0.99f) layer = c.saveLayerAlpha(x0 - 10 * k, top - 14 * k, x0 + 640 * k, top + size + 14 * k, Math.round(255 * a));
        // Cover (or a gradient with a note when the app gives none).
        playerBox.set(x0, top, x0 + size, top + size);
        if (nowPlaying.cover != null) {
            roundImage(c, nowPlaying.cover, playerBox, 12 * k);
        } else {
            barPaint.setStyle(Paint.Style.FILL);
            barPaint.setShader(new LinearGradient(x0, top, x0 + size, top + size, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(playerBox, 12 * k, 12 * k, barPaint);
            barPaint.setShader(null);
            iconFill.setStyle(Paint.Style.FILL);
            iconFill.setColor(PILL);
            float nx = playerBox.centerX(), ny = playerBox.centerY();
            c.drawCircle(nx - 6 * k, ny + 9 * k, 6 * k, iconFill);
            c.drawRect(nx - 1 * k, ny - 14 * k, nx + 3 * k, ny + 9 * k, iconFill);
            c.drawRect(nx + 3 * k, ny - 14 * k, nx + 12 * k, ny - 9 * k, iconFill);
        }
        // Title and artist.
        float tx = x0 + size + 16 * k, textW = 330 * k;
        String title = TextUtils.ellipsize(nowPlaying.title, new TextPaint(playerTitle), textW, TextUtils.TruncateAt.END).toString();
        if (nowPlaying.artist.isEmpty()) {
            c.drawText(title, tx, top + size / 2 + Ui.halfCap(playerTitle), playerTitle);
        } else {
            c.drawText(title, tx, top + size / 2 - 5 * k, playerTitle);
            c.drawText(TextUtils.ellipsize(nowPlaying.artist, new TextPaint(playerArtist), textW, TextUtils.TruncateAt.END).toString(),
                    tx, top + size / 2 + 20 * k, playerArtist);
        }
        // Controls.
        float bx = tx + textW + 14 * k;
        for (int i = 0; i < 3; i++) {
            float f = Ui.clamp(playerFocus[i].value), s = 1 + 0.1f * f;
            float cx = bx + size / 2 + i * (size + 12 * k), cy = top + size / 2, rad = size * s / 2;
            playerBox.set(cx - rad, cy - rad, cx + rad, cy + rad);
            barPaint.setStyle(Paint.Style.FILL);
            barPaint.setShader(null);
            barPaint.setColor(f > 0.01f ? Ui.alpha(0xFFFFFFFF, 0.14f + 0.8f * f) : 0x24FFFFFF);
            c.drawCircle(cx, cy, rad, barPaint);
            if (f > 0.01f) ring(c, playerBox, rad, f, 4 * k);
            drawTransport(c, i, cx, cy, 24 * k * s, f > 0.5f ? PILL : 0xFFFFFFFF);
        }
        if (layer >= 0) c.restoreToCount(layer);
    }

    /** Previous (0), play or pause (1) and next (2), drawn as shapes. */
    private void drawTransport(Canvas c, int kind, float cx, float cy, float size, int color) {
        iconFill.setStyle(Paint.Style.FILL);
        iconFill.setColor(color);
        float u = size / 2;
        playerPath.reset();
        if (kind == 1) {
            if (nowPlaying.playing) {
                c.drawRoundRect(cx - u * 0.68f, cy - u * 0.8f, cx - u * 0.12f, cy + u * 0.8f, 2 * k, 2 * k, iconFill);
                c.drawRoundRect(cx + u * 0.12f, cy - u * 0.8f, cx + u * 0.68f, cy + u * 0.8f, 2 * k, 2 * k, iconFill);
            } else {
                playerPath.moveTo(cx - u * 0.5f, cy - u * 0.85f);
                playerPath.lineTo(cx - u * 0.5f, cy + u * 0.85f);
                playerPath.lineTo(cx + u * 0.85f, cy);
                playerPath.close();
                c.drawPath(playerPath, iconFill);
            }
        } else if (kind == 0) {
            c.drawRoundRect(cx - u * 0.85f, cy - u * 0.75f, cx - u * 0.5f, cy + u * 0.75f, 2 * k, 2 * k, iconFill);
            playerPath.moveTo(cx + u * 0.85f, cy - u * 0.8f);
            playerPath.lineTo(cx - u * 0.3f, cy);
            playerPath.lineTo(cx + u * 0.85f, cy + u * 0.8f);
            playerPath.close();
            c.drawPath(playerPath, iconFill);
        } else {
            c.drawRoundRect(cx + u * 0.5f, cy - u * 0.75f, cx + u * 0.85f, cy + u * 0.75f, 2 * k, 2 * k, iconFill);
            playerPath.moveTo(cx - u * 0.85f, cy - u * 0.8f);
            playerPath.lineTo(cx + u * 0.3f, cy);
            playerPath.lineTo(cx - u * 0.85f, cy + u * 0.8f);
            playerPath.close();
            c.drawPath(playerPath, iconFill);
        }
    }

    private boolean recentCards() {
        return Prefs.cardsMode().equals("recent");
    }

    /** The Explore / Movies / Shows tabs only exist in "Recommended" mode; "Continue watching" mode has a section title instead. */
    private boolean tabsVisible() {
        return !recentCards();
    }

    /** The first thing that can hold focus in the top row: the selected tab, or (without tabs) the first corner button. */
    private int topHome() {
        return tabsVisible() ? tab : 3 + bellOffset();
    }

    private static final boolean PLAYER_STRIP = false;   // the player strip is off until its place on the screen is decided

    private boolean playerShown() {
        return PLAYER_STRIP && nowPlaying.visible() && !sheetOpen;
    }

    /** The audio session changed (a song, play/pause, or it ended): the strip redraws, and the focus leaves it if it went away. */
    private void onPlayer() {
        if (!nowPlaying.visible() && zone == Z_PLAYER) {
            zone = Z_SHELF;
            retarget(false);
        }
        gridDirty = true;
    }

    private void activatePlayer() {
        click.open();
        if (playerIndex == 0) nowPlaying.previous();
        else if (playerIndex == 2) nowPlaying.next();
        else nowPlaying.toggle();
    }

    /** The "+" of the next free place of the shelf, in the row of the panel. */
    private void drawPlaceholder(Canvas c, int slot, float sc) {
        // Row 0, the column after the last pinned app (panelRect would treat the index as an unpinned app on row 1).
        float px = (MARGIN + (pinnedCount + slot) * (cellW + 24) + (cellW - tileW) / 2f) * k, py = (firstTop() - sc * pitch()) * k;
        tmp.set(px, py, px + tileW * k, py + tileH * k);
        float f = phFocus[slot].value;
        drawSlot(c, tmp, f, 0, false, 1);
        if (!sheetOpen && f > 0.05f) {   // on the home, the focused "+" says what it is for
            pinPaints();
            plusLabel.setColor(Ui.alpha(0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(Ui.tr("Fixar apps", "Pin apps", "Fijar apps"), tmp.centerX(), tmp.bottom + (12 + 12 * Ui.clamp(f)) * k + Ui.halfCap(plusLabel) * 2, plusLabel);
        }
    }

    private final RectF slotBox = new RectF(), slotIn = new RectF();
    private android.graphics.DashPathEffect slotDash;

    /**
     * An empty slot: dashed rounded outline and a plus, the size of an app tile. `lily` (0-1) tints the outline and the plus
     * with Lily (the next free place in the pin mode); `faint` is the thin outline of the places after it (no plus).
     */
    private void drawSlot(Canvas c, RectF base, float f, float lily, boolean faint, float alpha) {
        if (alpha <= 0.01f) return;
        float fc = Ui.clamp(f), s = 1 + 0.07f * f;
        float cx = base.centerX(), cy = base.centerY(), hw = base.width() * s / 2, hh = base.height() * s / 2, rad = tileR * k * s;
        slotBox.set(cx - hw, cy - hh, cx + hw, cy + hh);
        if (fc > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.14f * fc * alpha));
            c.drawRoundRect(slotBox, rad, rad, fill);
        }
        if (slotDash == null) slotDash = new android.graphics.DashPathEffect(new float[] {14 * k, 12 * k}, 0);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth((faint ? 1.5f : 2.5f + 1.5f * fc) * k);
        dashPaint.setStrokeCap(Paint.Cap.ROUND);
        dashPaint.setPathEffect(slotDash);
        int white = Ui.alpha(0xFFFFFFFF, faint ? 0.16f : 0.45f + 0.5f * fc);
        int col = lily > 0.01f && !faint ? blend(white, LILY, lily) : white;
        dashPaint.setColor(Ui.alpha(col, alpha * ((col >>> 24) / 255f)));
        slotIn.set(slotBox);
        slotIn.inset(2 * k, 2 * k);
        c.drawRoundRect(slotIn, rad, rad, dashPaint);
        dashPaint.setPathEffect(null);
        if (faint) return;
        float arm = Math.min(hw, hh) * 0.26f;
        dashPaint.setStrokeWidth(5 * k);
        c.drawLine(cx - arm, cy, cx + arm, cy, dashPaint);
        c.drawLine(cx, cy - arm, cx, cy + arm, dashPaint);
    }

    private void drawPanelCell(Canvas c, int i) {
        HomeApps.Item it = panel.get(i);
        RectF base = new RectF();
        panelRect(i, scroll.value, base);
        float f = gridFocus[i].value, fc = Ui.clamp(f);
        Bitmap img = shape == 0 ? ownCard(it.key) : null;
        if (img == null) img = shape != 0 && it.sq != null ? it.sq : it.tile;
        drawCard(c, img, base, tileR * k, f, 0.07f);
        drawPanelName(c, i, it, base, fc);
        if (sheetOpen && i == gridIndex) focusedCell.set(cell);
        if (fc > 0.01f) {   // the ring floats 9 px outside the app, as designed (it used to touch the edge and got lost)
            float air = 9 * k;
            airRect.set(cell.left - air, cell.top - air, cell.right + air, cell.bottom + air);
            ring(c, airRect, tileR * k * (1 + 0.07f * f) + air, fc, 4.5f * k);
        }
    }

    /** The app's name under its tile in the open panel (fades in as the panel opens). */
    private void drawPanelName(Canvas c, int i, HomeApps.Item it, RectF base, float fc) {
        float a = Ui.clamp(openness() * 2 - 0.9f);
        if (a <= 0.01f || it.label == null) return;
        if (panelName == null) panelName = Ui.text(Ui.medium, 19.5f * k, 0xFFFFFFFF);
        panelName.setTextSize((cols == 10 ? 18 : 19.5f) * k);
        if (nameFit.length != panel.size()) nameFit = new String[panel.size()];
        if (nameFit[i] == null) nameFit[i] = android.text.TextUtils.ellipsize(it.label, new android.text.TextPaint(panelName), cellW * k, android.text.TextUtils.TruncateAt.END).toString();
        panelName.setColor(Ui.alpha(0xFFFFFFFF, (0.78f + 0.22f * fc) * a));
        panelName.setTextAlign(Paint.Align.CENTER);
        // The focused card grows and its ring floats outside it: the name steps down so the ring never covers it.
        c.drawText(nameFit[i], base.centerX(), base.bottom + (12 + 12 * fc) * k + Ui.halfCap(panelName) * 2, panelName);
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            sheen.setShader(new LinearGradient(0, cell.top, 0, cell.top + cell.height() * 0.5f,
                    Ui.alpha(0xFFFFFFFF, 0.1f * fc), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, sheen);
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Pin apps ("Tus apps a la mano"): the "+" of the home opens All Apps in a mode to choose the pinned apps

    // The design's measures (1280 x 720 canvas x 1.5).
    private static final float PIN_HEAD_CY = 72, PIN_ROW_TOP = 138, PIN_LINE_Y = 294, PIN_LABEL_Y = 318, PIN_GRID_TOP = 360,
            PIN_FOOT_B = 24, PIN_PILL_H = 54;
    private static final int PIN_GROUP_ROW = 11, PIN_GROUP_GRID = 12;
    private boolean pinMode, pinRow;
    private int pinRowIndex, pinGridIndex;
    private List<HomeApps.Item> pinAll = new ArrayList<>();           // every app A-Z (the pinned ones too)
    private Spring[] pinGridFocus = new Spring[0];
    private final Spring[] pinRowFocus = new Spring[MAX_COLS];
    private final Spring pinScroll = new Spring(0.34f, 1f, 0), pinMorph = new Spring(0.34f, 1f, 1);
    private final java.util.Map<String, Spring> pinSlots = new java.util.HashMap<>();   // pinned app -> the slot it is drawn in
    private final java.util.Map<String, String> pinFit = new java.util.HashMap<>();     // app name fitted to the cell
    { for (int i = 0; i < MAX_COLS; i++) pinRowFocus[i] = new Spring(0.3f, 0.82f, 0); }
    private HomeApps.Item pinFlyItem;                                   // the app that is flying to its place
    private final RectF pinFlyFrom = new RectF(), pinFlyTo = new RectF(), pinRect = new RectF(), pinBox = new RectF();
    private long pinFlyAt, pinFullAt;
    private long pinEnterAt;   // entering the mode with the panel already open: the head, line, label and checks come in
    private float pinEnterE = 1;
    private int pinMsg;   // 0: no free spots left, 1: at least one app must stay pinned
    private Paint pinTitle, pinPillText, pinInstr, pinLabel, pinFoot, pinFullText, pinCheck, plusLabel;
    private android.view.animation.PathInterpolator pinCurve;

    /** The pinned apps: the first `cols` of the shelf (all of them are apps). */
    private int pinCount() {
        return Math.min(cols, shelf.size());
    }

    private boolean pinIsPinned(HomeApps.Item it) {
        int n = pinCount();
        for (int i = 0; i < n; i++) if (shelf.get(i).pkg != null && shelf.get(i).pkg.equals(it.pkg)) return true;
        return false;
    }

    private float pinNom() {
        return Math.min(cellW * 0.5625f, rowCell - 39);
    }

    private float pinOff() {
        return (pinNom() - tileH) / 2f;   // the squares and circles sit centered in the height of a card
    }

    private float pinPitch() {
        return pinNom() + 60;
    }

    private float pinColX(float slot) {
        return (MARGIN + slot * (cellW + 24) + (cellW - tileW) / 2f) * k;
    }

    /** Top of the pinned apps: at rest on the home, at the head of the panel when it is open. */
    private float pinRowY() {
        float closed = P_CLOSED_CENTER - tileH / 2f;
        float normal = P_FIRST + (rowCell - 39 - tileH) / 2f;
        float open = normal + (PIN_ROW_TOP + pinOff() - normal) * Ui.clamp(pinMorph.value);
        return (closed + (open - closed) * openness()) * k;
    }

    private void pinRowRect(float slot, RectF out) {
        float x = pinColX(slot), y = pinRowY();
        out.set(x, y, x + tileW * k, y + tileH * k);
    }

    private void pinGridRect(int i, RectF out) {
        float x = pinColX(i % cols);
        float y = (PIN_GRID_TOP + pinOff() + (i / cols - pinScroll.value) * pinPitch()) * k;
        out.set(x, y, x + tileW * k, y + tileH * k);
    }

    private void buildPinAll() {
        List<HomeApps.Item> all = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.APP && it.pkg != null && seen.add(it.pkg)) all.add(it);
        for (HomeApps.Item it : others) if (it.kind == HomeApps.APP && it.pkg != null && seen.add(it.pkg)) all.add(it);
        java.util.Collections.sort(all, (x, y) -> x.label.toLowerCase(java.util.Locale.ROOT).compareTo(y.label.toLowerCase(java.util.Locale.ROOT)));
        pinAll = all;
        pinFit.clear();
        pinGridFocus = new Spring[all.size()];
        for (int i = 0; i < pinGridFocus.length; i++) pinGridFocus[i] = new Spring(0.3f, 0.82f, 0);
    }

    /** Every pinned app has a slot that slides to its new place when another one leaves. */
    private void pinSyncSlots(boolean snap) {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (int i = 0; i < shelf.size(); i++) {
            String p = shelf.get(i).pkg;
            if (p == null) continue;
            live.add(p);
            Spring s = pinSlots.get(p);
            if (s == null) {
                s = new Spring(0.3f, 0.9f, i);
                pinSlots.put(p, s);
            } else {
                s.target = i;
                if (snap) s.snap(i);
            }
        }
        pinSlots.keySet().retainAll(live);
    }

    private boolean stepPin(float dt) {
        boolean moving = pinScroll.step(dt) | pinMorph.step(dt);
        moving |= step(pinRowFocus, dt) | step(pinGridFocus, dt);
        for (Spring s : pinSlots.values()) moving |= s.step(dt);
        return moving;
    }

    private void openPinMode() {
        if (pinMode && sheetOpen) return;
        boolean fromSheet = sheetOpen;
        buildPinAll();
        pinMode = true;
        phGrid = -1;
        pinRow = false;
        pinRowIndex = 0;
        pinGridIndex = 0;
        for (int i = 0; i < pinAll.size(); i++) if (!pinIsPinned(pinAll.get(i))) { pinGridIndex = i; break; }   // the first app not pinned yet
        pinSlots.clear();
        pinSyncSlots(true);
        pinScroll.snap(0);
        pinFlyItem = null;
        pinFullAt = 0;
        pinEnterAt = 0;
        if (fromSheet) {
            pinMorph.snap(0);
            pinMorph.target = 1;
            pinEnterAt = SystemClock.uptimeMillis();
        } else {
            pinMorph.snap(1);
            openSheet();
            if (!sheetOpen) {   // nothing to show
                pinMode = false;
                return;
            }
        }
        click.open();
        pinRetarget(true);
        gridDirty = true;
    }

    private void pinRetarget(boolean snap) {
        for (int i = 0; i < pinRowFocus.length; i++) {
            pinRowFocus[i].target = pinMode && sheetOpen && pinRow && i == pinRowIndex ? 1 : 0;
            if (snap) pinRowFocus[i].snap(pinRowFocus[i].target);
        }
        for (int i = 0; i < pinGridFocus.length; i++) {
            pinGridFocus[i].target = pinMode && sheetOpen && !pinRow && i == pinGridIndex ? 1 : 0;
            if (snap) pinGridFocus[i].snap(pinGridFocus[i].target);
        }
        if (!pinRow) {   // the row of the focused app stays in view
            float room = h / k - (PIN_FOOT_B + PIN_PILL_H + 16) - PIN_GRID_TOP - pinNom() - 40;
            float rowsBelow = room / pinPitch();
            int r = pinGridIndex / cols, rows = (pinAll.size() + cols - 1) / cols;
            float target = pinScroll.target;
            if (r < target) target = r;
            else if (r - target > rowsBelow) target = r - rowsBelow;
            pinScroll.target = Math.max(0, Math.min(Math.max(0, rows - 1 - rowsBelow), target));
            if (snap) pinScroll.snap(pinScroll.target);
        }
        spoken.focusMoved();
    }

    private void movePin(int dx, int dy) {
        int n = pinAll.size();
        if (pinRow) {
            int pc = pinCount();
            if (dx != 0) {
                if (pc < 2) return;
                pinRowIndex = (pinRowIndex + dx + pc) % pc;   // endless, as the shelf
            } else if (dy > 0 && n > 0) {
                pinRow = false;
                pinGridIndex = Math.min(pinRowIndex, n - 1);
                pinScroll.target = 0;
            } else {
                return;
            }
        } else {
            if (n == 0) return;
            int r = pinGridIndex / cols, c = pinGridIndex % cols;
            if (dx != 0) {
                int rowEnd = Math.min(n, (r + 1) * cols) - 1;
                int next = pinGridIndex + dx;
                if (next < r * cols) next = rowEnd;
                else if (next > rowEnd) next = r * cols;
                if (next == pinGridIndex) return;
                pinGridIndex = next;
            } else if (dy < 0) {
                if (r > 0) pinGridIndex -= cols;
                else if (pinCount() > 0) {   // up from the first row: the pinned apps
                    pinRow = true;
                    pinRowIndex = Math.min(c, pinCount() - 1);
                } else return;
            } else {
                if (pinGridIndex + cols < n) pinGridIndex += cols;
                else if ((r + 1) * cols < n) pinGridIndex = n - 1;
                else return;
            }
        }
        pinRetarget(false);
        click.move();
        gridDirty = true;
    }

    private void activatePin() {
        if (pinFlyItem != null) return;   // while one flies, OK waits
        if (pinRow) {
            if (pinRowIndex >= pinCount()) return;
            pinRemove(shelf.get(pinRowIndex));
            if (pinCount() == 0) {   // none left: the focus goes to the apps
                pinRow = false;
            } else {
                pinRowIndex = Math.min(pinRowIndex, pinCount() - 1);
            }
            pinRetarget(false);
            return;
        }
        if (pinAll.isEmpty()) return;
        HomeApps.Item it = pinAll.get(pinGridIndex);
        if (pinIsPinned(it)) {
            pinRemove(it);
        } else if (pinCount() >= cols) {
            pinMsg = 0;
            pinFullAt = SystemClock.uptimeMillis();
            announceForAccessibility(Ui.tr("Não há mais lugares livres.", "No free spots left.", "Ya no quedan lugares libres."));
            gridDirty = true;
        } else {
            pinAdd(it);
        }
        pinRetarget(false);
    }

    private void pinCommit(List<HomeApps.Item> list) {
        setShelf(list);
        List<String> pkgs = new ArrayList<>();
        for (HomeApps.Item it : list) if (it.pkg != null) pkgs.add(it.pkg);
        Prefs.setShelf(pkgs);
        pinSyncSlots(false);
        gridDirty = true;
    }

    private void pinAdd(HomeApps.Item it) {
        pinGridRect(pinGridIndex, pinFlyFrom);
        List<HomeApps.Item> copy = new ArrayList<>(shelf.subList(0, pinCount()));
        copy.add(it);
        pinFlyItem = it;
        pinFlyAt = SystemClock.uptimeMillis();
        pinCommit(copy);
        pinRowRect(copy.size() - 1, pinFlyTo);
        Spring s = pinSlots.get(it.pkg);
        if (s != null) s.snap(copy.size() - 1);   // the place is taken at once: nothing in the row moves
        List<HomeApps.Item> rest = new ArrayList<>();
        for (HomeApps.Item o : others) if (o.pkg == null || !o.pkg.equals(it.pkg)) rest.add(o);
        others = rest;
        click.open();
        announceForAccessibility(it.label + Ui.tr(" fixado, ", " pinned, ", " fijada, ") + copy.size() + Ui.tr(" de ", " of ") + cols + Ui.tr(" lugares", " spots", " lugares"));
    }

    private void pinRemove(HomeApps.Item it) {
        if (pinCount() <= 1 && pinIsPinned(it)) {   // the home keeps at least one app on its row
            pinMsg = 1;
            pinFullAt = SystemClock.uptimeMillis();
            gridDirty = true;
            return;
        }
        List<HomeApps.Item> copy = new ArrayList<>();
        for (HomeApps.Item o : shelf) if (o.pkg == null || !o.pkg.equals(it.pkg)) copy.add(o);
        pinCommit(copy);
        boolean there = false;
        for (HomeApps.Item o : others) there |= o.pkg != null && o.pkg.equals(it.pkg);
        if (!there) {
            List<HomeApps.Item> more = new ArrayList<>(others);
            more.add(it);
            others = more;
        }
        click.open();
        announceForAccessibility(it.label + Ui.tr(" não está mais fixado", " is no longer pinned", " ya no está fijada"));
    }

    /** "Atrás": everything goes back to the home, with the "+" focused. */
    private void closePin() {
        pinFlyItem = null;
        pinRow = false;
        pinRetarget(false);
        for (Spring s : pinGridFocus) s.target = 0;
        closeSheet();
        rebuildPanel();   // (pinnedCount and the "+" of the home follow what was chosen)
        int apps = pinCount();
        shelfIndex = apps < cols ? apps : Math.max(0, apps - 1);   // the "+" if there is a free place
        retarget(false);
        refreshSheet = true;
        reload();
    }

    /** The panel finished closing: the normal panel takes over again. */
    private void pinFinished() {
        pinMode = false;
        pinFlyItem = null;
        rebuildPanel();
        gridDirty = true;
    }

    private String pinFitted(String label) {
        String s = pinFit.get(label);
        if (s == null) {
            Paint p = pinNamePaint();
            s = TextUtils.ellipsize(label, new TextPaint(p), cellW * k, TextUtils.TruncateAt.END).toString();
            pinFit.put(label, s);
        }
        return s;
    }

    private Paint pinNamePaint() {
        if (panelName == null) panelName = Ui.text(Ui.medium, 19.5f * k, 0xFFFFFFFF);
        panelName.setTextSize((cols == 10 ? 18 : 19.5f) * k);
        panelName.setTextAlign(Paint.Align.CENTER);
        return panelName;
    }

    private void pinPaints() {
        if (pinTitle != null) return;
        pinTitle = Ui.text(Logo.wordmark(getContext()), 45 * k, 0xFFFFFFFF);
        pinPillText = Ui.text(Ui.bold, 21 * k, 0xFFFFFFFF);
        pinInstr = Ui.text(Ui.medium, 22.5f * k, 0xFFFFFFFF);
        pinLabel = Ui.text(Ui.bold, 18 * k, PEACH);
        pinLabel.setLetterSpacing(0.1f);
        pinFoot = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
        pinFullText = Ui.text(Ui.semibold, 22.5f * k, MELON);
        pinFullText.setTextAlign(Paint.Align.CENTER);
        pinCheck = new Paint(Paint.ANTI_ALIAS_FLAG);
        pinCheck.setStyle(Paint.Style.STROKE);
        pinCheck.setStrokeCap(Paint.Cap.ROUND);
        pinCheck.setStrokeJoin(Paint.Join.ROUND);
        plusLabel = Ui.text(Ui.semibold, 22.5f * k, 0xFFFFFFFF);
        plusLabel.setTextAlign(Paint.Align.CENTER);
        pinCurve = new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f);
    }

    private void drawPinSheet(Canvas c) {
        pinPaints();
        float o = openness();
        float pa = Ui.clamp(o * 2 - 0.6f) * Ui.clamp(pinMorph.value);   // what is not the pinned row fades in with the panel
        long now = SystemClock.uptimeMillis();
        pinEnterE = 1;
        if (pinEnterAt != 0) {   // 240 ms, entry curve, 80 ms after the row; rising 18 px
            float p = Ui.clamp((now - pinEnterAt - 80) / 240f);
            pinEnterE = pinCurve.getInterpolation(p);
            if (p >= 1) pinEnterAt = 0;
            else gridDirty = true;
        }
        float head = Ui.clamp(o * 2 - 0.6f) * pinEnterE;
        if (head > 0.01f) {
            c.save();
            c.translate(0, (1 - pinEnterE) * 18 * k);
            drawPinHead(c, head);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.12f * head));
            c.drawRect(MARGIN * k, PIN_LINE_Y * k, CONTENT_R * k, (PIN_LINE_Y + 1.5f) * k, fill);
            pinLabel.setAlpha(Math.round(255 * head));
            String label = Ui.tr("TODOS OS APPS", "ALL APPS", "TODAS LAS APPS");
            c.drawText(label, MARGIN * k, PIN_LABEL_Y * k + Ui.halfCap(pinLabel) * 2, pinLabel);
            c.restore();
        }
        if (pa > 0.01f) {
            c.save();
            c.clipRect(0, (PIN_LABEL_Y + 22) * k, w, h - (PIN_FOOT_B + PIN_PILL_H + 8) * k);
            int focused = -1;
            for (int i = 0; i < pinAll.size(); i++) {
                pinGridRect(i, pinRect);
                if (pinRect.bottom < (PIN_LABEL_Y + 22) * k - 30 * k || pinRect.top > h) continue;
                if (!pinRow && i == pinGridIndex) { focused = i; continue; }
                drawPinCell(c, i, pa);
            }
            if (focused >= 0) drawPinCell(c, focused, pa);   // the focused one over its neighbours
            c.restore();
        }
        drawPinRow(c, o, pa, now);
        if (pa > 0.01f) {
            drawPinFull(c, now, pa);
            drawPinFoot(c, pa);
        }
        drawPinFly(c, now);
    }

    private void drawPinHead(Canvas c, float a) {
        int cap = pinCount();
        float cy = PIN_HEAD_CY * k;
        String title = Ui.tr("Seus apps à mão", "Your apps at hand", "Tus apps a la mano");
        pinTitle.setAlpha(Math.round(255 * a));
        float x = MARGIN * k;
        c.drawText(title, x - Ui.leftBearing(pinTitle, title), cy + Ui.halfCap(pinTitle), pinTitle);
        x += pinTitle.measureText(title) + 24 * k;
        boolean full = cap >= cols;
        String count = cap + Ui.tr(" de ", " of ") + cols + Ui.tr(" lugares", " spots", " lugares");
        float pw = pinPillText.measureText(count) + 42 * k, ph = 48 * k;
        pinBox.set(x, cy - ph / 2, x + pw, cy + ph / 2);
        fill.setShader(null);
        fill.setColor(Ui.alpha(full ? MELON : LILY, (full ? 0.18f : 0.16f) * a));
        c.drawRoundRect(pinBox, ph / 2, ph / 2, fill);
        pinPillText.setColor(Ui.alpha(full ? MELON : 0xFFFFFFFF, a));
        c.drawText(count, x + 21 * k, cy + Ui.halfCap(pinPillText), pinPillText);
        x += pw + 24 * k;
        pinInstr.setColor(Ui.alpha(0xFFFFFFFF, 0.72f * a));
        String instr = Ui.tr("Escolha o app que quer fixar e pressione \"OK\" para confirmar.",
                "Choose the app you want to pin and press \"OK\" to confirm.",
                "Elige la app que quieres fijar y presiona \"OK\" para confirmar.");
        c.drawText(instr, x, cy + Ui.halfCap(pinInstr), pinInstr);
    }

    private void drawPinCell(Canvas c, int i, float pa) {
        HomeApps.Item it = pinAll.get(i);
        pinGridRect(i, pinRect);
        boolean on = pinIsPinned(it);
        float a = pa * (on ? 0.55f : pinCount() >= cols ? 0.6f : 1f);
        drawPinTile(c, it, pinRect, pinGridFocus[i].value, a, on, pinFitted(it.label), 1);
    }

    /** A card with its focus, the check of a pinned app, and its name under it (when `nameA` > 0). */
    private void drawPinTile(Canvas c, HomeApps.Item it, RectF base, float f, float alpha, boolean check, String name, float nameA) {
        if (alpha <= 0.01f) return;
        float fc = Ui.clamp(f);
        Bitmap img = shape == 0 ? ownCard(it.key) : null;
        if (img == null) img = shape != 0 && it.sq != null ? it.sq : it.tile;
        int layer = alpha < 0.99f ? c.saveLayerAlpha(base.left - 40 * k, base.top - 40 * k, base.right + 40 * k, base.bottom + 100 * k, Math.round(255 * alpha)) : -1;
        drawCard(c, img, base, tileR * k, f, 0.07f);
        if (fc > 0.01f) {
            float air = 9 * k;
            airRect.set(cell.left - air, cell.top - air, cell.right + air, cell.bottom + air);
            ring(c, airRect, tileR * k * (1 + 0.07f * f) + air, fc, 4.5f * k);
        }
        if (check) drawPinCheck(c, cell);
        if (name != null && nameA > 0.01f) {
            Paint p = pinNamePaint();
            p.setColor(Ui.alpha(0xFFFFFFFF, (0.78f + 0.22f * fc) * nameA));
            c.drawText(name, base.centerX(), base.bottom + (12 + 12 * fc) * k + Ui.halfCap(p) * 2, p);
        }
        if (layer >= 0) c.restoreToCount(layer);
    }

    /** The Lily badge with a check, on the corner of a pinned app. */
    private void drawPinCheck(Canvas c, RectF cardRect) {
        float ea = pinEnterE;
        if (ea <= 0.01f) return;
        float cx = cardRect.right - 7.5f * k, cy = cardRect.top + 7.5f * k + (1 - ea) * 18 * k, r = 21 * k;
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF1C1920, ea));
        c.drawCircle(cx, cy, r, fill);
        fill.setColor(Ui.alpha(LILY, ea));
        c.drawCircle(cx, cy, r - 4.5f * k, fill);
        float u = 19.5f * k / 24f;
        pinCheck.setColor(Ui.alpha(PILL, ea));
        pinCheck.setStrokeWidth(4.8f * k);
        playerPath.reset();
        playerPath.moveTo(cx - 7 * u, cy + 0.5f * u);
        playerPath.lineTo(cx - 2.5f * u, cy + 5 * u);
        playerPath.lineTo(cx + 7 * u, cy - 4.5f * u);
        c.drawPath(playerPath, pinCheck);
    }

    private void drawPinRow(Canvas c, float o, float pa, long now) {
        int n = pinCount();
        for (int i = n; i < cols; i++) {   // the free places (only the next one shows on the home)
            boolean next = i == n;
            if (!next && pa < 0.01f) break;
            pinRowRect(i, pinRect);
            float a = next ? 1 : pa;
            if (next) drawSlot(c, pinRect, sheetOpen ? 0 : phFocus[0].value, Ui.clamp(o * 1.6f), false, a);
            else drawSlot(c, pinRect, 0, 0, true, a);
        }
        int focused = -1;
        for (int i = 0; i < n; i++) {
            if (pinRow && i == pinRowIndex) { focused = i; continue; }
            drawPinRowApp(c, i, now, pa);
        }
        if (focused >= 0) drawPinRowApp(c, focused, now, pa);
    }

    private void drawPinRowApp(Canvas c, int i, long now, float pa) {
        HomeApps.Item it = shelf.get(i);
        float alpha = 1;
        if (it == pinFlyItem) alpha = Ui.clamp((now - pinFlyAt - 450) / 200f);   // the real app appears when the copy lands
        Spring s = pinSlots.get(it.pkg);
        pinRowRect(s != null ? s.value : i, pinRect);
        float f = pinRowFocus[i].value;
        boolean named = f > 0.01f;
        drawPinTile(c, it, pinRect, f, alpha, false, named ? pinFitted(it.label) : null, Ui.clamp(f));
    }

    /** The copy of the card that flies from the grid to its place: 420 ms with the entry curve, from scale 1.06 to 1. */
    private void drawPinFly(Canvas c, long now) {
        if (pinFlyItem == null) return;
        long t = now - pinFlyAt;
        if (t >= 620) {
            pinFlyItem = null;
            gridDirty = true;   // (one more frame: the real app is fully there)
            return;
        }
        float p = Ui.clamp((t - 30) / 420f), e = pinCurve.getInterpolation(p);
        pinRect.set(pinFlyFrom.left + (pinFlyTo.left - pinFlyFrom.left) * e, pinFlyFrom.top + (pinFlyTo.top - pinFlyFrom.top) * e,
                pinFlyFrom.right + (pinFlyTo.right - pinFlyFrom.right) * e, pinFlyFrom.bottom + (pinFlyTo.bottom - pinFlyFrom.bottom) * e);
        Bitmap img = shape == 0 ? ownCard(pinFlyItem.key) : null;
        if (img == null) img = shape != 0 && pinFlyItem.sq != null ? pinFlyItem.sq : pinFlyItem.tile;
        drawCard(c, img, pinRect, tileR * k, 1 - e, 0.06f);
        float ring = 4.5f * k;
        airRect.set(cell.left - ring, cell.top - ring, cell.right + ring, cell.bottom + ring);
        ring(c, airRect, tileR * k * (1 + 0.06f * (1 - e)) + ring, 1, ring);
        gridDirty = true;
    }

    /** "Ya no quedan lugares libres...": Melon, centered above the footer, for 1.8 s. */
    private void drawPinFull(Canvas c, long now, float pa) {
        if (pinFullAt == 0) return;
        long t = now - pinFullAt;
        if (t > 2000) {
            pinFullAt = 0;
            return;
        }
        float a = t < 200 ? t / 200f : t > 1800 ? (2000 - t) / 200f : 1;
        pinFullText.setAlpha(Math.round(255 * a * pa));
        String msg = pinMsg == 1 ? Ui.tr("Deixe pelo menos um app à mão.", "Keep at least one app at hand.", "Deja al menos una app a la mano.")
                : Ui.tr("Não há mais lugares livres. Tire um app de cima para abrir espaço para outro.",
                "No free spots left. Remove an app above to make room for another.",
                "Ya no quedan lugares libres. Quita una app de arriba para hacerle espacio a otra.");
        c.drawText(msg, w / 2f, 957 * k + Ui.halfCap(pinFullText), pinFullText);
        gridDirty = true;
    }

    private void drawPinFoot(Canvas c, float pa) {
        String[] texts = {
            Ui.tr("\"OK\" · Fixar ou tirar", "\"OK\" · Pin or remove", "\"OK\" · Fijar o quitar"),
            Ui.tr("\"Para cima\" · Ir aos seus apps", "\"Up\" · Go to your apps", "\"Arriba\" · Ir a tus apps"),
            Ui.tr("\"Voltar\" · Pronto", "\"Back\" · Done", "\"Atrás\" · Listo")};
        float gap = 18 * k, ph = PIN_PILL_H * k, total = -gap;
        float[] widths = new float[3];
        for (int i = 0; i < 3; i++) {
            widths[i] = pinFoot.measureText(texts[i]) + 48 * k;
            total += widths[i] + gap;
        }
        float x = (w - total) / 2f, top = h - PIN_FOOT_B * k - ph;
        pinFoot.setColor(Ui.alpha(0xFFFFFFFF, 0.8f * pa));
        for (int i = 0; i < 3; i++) {
            pinBox.set(x, top, x + widths[i], top + ph);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f * pa));
            c.drawRoundRect(pinBox, ph / 2, ph / 2, fill);
            c.drawText(texts[i], x + 24 * k, top + ph / 2 + Ui.halfCap(pinFoot), pinFoot);
            x += widths[i] + gap;
        }
    }

    // TalkBack in the pin mode: the pinned apps, then every app.
    private String pinText(int group, int i) {
        if (group == PIN_GROUP_ROW) {
            if (i >= pinCount()) return "";
            return shelf.get(i).label + Ui.tr(", fixado, OK para tirar", ", pinned, OK to remove", ", fijada, OK para quitar");
        }
        if (i >= pinAll.size()) return "";
        HomeApps.Item it = pinAll.get(i);
        boolean on = pinIsPinned(it);
        return it.label + (on ? Ui.tr(", fixado", ", pinned", ", fijada") : "") + ", "
                + (on ? Ui.tr("OK para tirar", "OK to remove", "OK para quitar") : Ui.tr("OK para fixar", "OK to pin", "OK para fijar"));
    }

    private boolean pinBounds(int group, int i, Rect out) {
        if (group == PIN_GROUP_ROW) {
            if (i >= pinCount()) return false;
            pinRowRect(i, a11yRect);
        } else {
            if (i >= pinAll.size()) return false;
            pinGridRect(i, a11yRect);
            if (a11yRect.top < (PIN_LABEL_Y + 20) * k || a11yRect.bottom > h - 70 * k) return false;
        }
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private void pinFocus(int group, int i) {
        if (group == PIN_GROUP_ROW) {
            if (i >= pinCount()) return;
            pinRow = true;
            pinRowIndex = i;
        } else {
            if (i >= pinAll.size()) return;
            pinRow = false;
            pinGridIndex = i;
        }
        pinRetarget(false);
        click.move();
        gridDirty = true;
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> in = HomeApps.allInputs(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                setShelf(s);
                setStatusItems(st);
                setInputItems(in);
                if (!sheetOpen || refreshSheet) {
                    refreshSheet = false;
                    setOthers(o);
                }
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (moving) {   // moving a shelf app: left and right move it; OK or Back finish
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) moveShelfApp(kc == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || kc == KeyEvent.KEYCODE_BACK) {
                if (down && e.getRepeatCount() == 0) {
                    moving = false;
                    menuDirty = true;
                    click.open();
                    showToast(Ui.tr("Posição guardada.", "Position saved.", "Posición guardada."));
                }
                return true;
            }
            return false;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        handler.removeCallbacks(longOk);
                        handler.postDelayed(longOk, 650);   // held: the shelf app's menu
                    }
                } else {
                    handler.removeCallbacks(longOk);
                    if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    // MARK: - The "Hidden apps" card at the end of All Apps

    // MARK: - Card menu (long OK on an app): the same dropdown as HDMI and Settings, hanging from the card

    private boolean cardMenu, menuAbove, refreshSheet;
    private final RectF menuCard = new RectF();
    private float menuTy;
    private final Paint moveArrow = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Where the focused card is, in screen coordinates (the shelf's tile, or the cell of the open panel). */
    private boolean cardRect(RectF out) {
        if (sheetOpen) {
            if (phGrid >= 0 || gridIndex < 0 || gridIndex >= panel.size()) return false;
            panelRect(gridIndex, scroll.value, out);
        } else {
            if (shelfIndex < 0 || shelfIndex >= shelf.size()) return false;
            panelRect(shelfIndex, 0, out);
        }
        out.offset(0, sheetLayer.getTranslationY());
        return true;
    }

    private MRow cardRow(String symbol, String label, Runnable run) {
        MRow r = new MRow();
        r.symbol = symbol;
        r.label = label;
        r.run = run;
        return r;
    }

    private void openCardMenu() {
        RectF r = new RectF();
        if (!cardRect(r)) return;
        final HomeApps.Item it = sheetOpen ? panel.get(gridIndex) : shelf.get(shelfIndex);
        if (it.pkg == null || it.kind != HomeApps.APP) return;
        boolean pinned = !sheetOpen || gridIndex < pinnedCount;
        menuRows.clear();
        if (pinned) {   // an app of the shelf: move it or take it off
            menuRows.add(cardRow("swap_horiz", Ui.tr("Mover", "Move", "Mover"), this::startMoving));
            menuRows.add(cardRow("close", Ui.tr("Desafixar", "Unpin", "Dejar de fijar"), () -> removeFromShelf(it)));
        } else {        // any other app of the panel: hide it
            menuRows.add(cardRow("visibility_off", Ui.tr("Ocultar", "Hide", "Ocultar"), () -> hideApp(it)));
        }
        float boxH = menuRows.size() * 62 * k + 28 * k;
        menuAbove = r.bottom + 30 * k + boxH + 12 * k > h;   // no room below: it hangs above the card
        menuCard.set(r);
        menuTy = menuAbove ? r.top - 30 * k - boxH : r.bottom;
        menuLayer.setTranslationY(menuTy);
        cardMenu = true;
        menuOpen = true;
        menuIndex = 0;
        menuAt = SystemClock.uptimeMillis();
        menu.target = 1;
        menuDirty = true;
        click.open();
        spoken.focusMoved();
    }

    private void activateCardMenu() {
        if (menuIndex < 0 || menuIndex >= menuRows.size()) return;
        Runnable run = menuRows.get(menuIndex).run;
        cardMenu = false;
        menuOpen = false;
        menu.target = 0;
        menuDirty = true;
        click.open();
        if (run != null) run.run();
    }

    private List<String> shelfPackagesNow() {
        List<String> out = new ArrayList<>();
        for (HomeApps.Item it : shelf) if (it.pkg != null) out.add(it.pkg);
        return out;
    }

    private void removeFromShelf(HomeApps.Item it) {
        List<String> pkgs = shelfPackagesNow();
        pkgs.remove(it.pkg);
        Prefs.setShelf(pkgs);
        showToast(it.label + Ui.tr(" não está mais fixado.", " is no longer pinned.", " ya no está fijada."));
        refreshSheet = true;
        reload();
    }

    private void hideApp(HomeApps.Item it) {
        List<String> hidden = Prefs.hiddenApps();
        if (!hidden.contains(it.pkg)) hidden.add(it.pkg);
        Prefs.setHiddenApps(hidden);
        showToast(it.label + Ui.tr(" oculto. Para mostrar: Configurações → Apps ocultos.",
                " hidden. To show it again: Settings → Hidden apps.",
                " oculta. Para mostrarla: Ajustes → Apps ocultas."));
        refreshSheet = true;
        reload();
    }

    private void startMoving() {
        moving = true;
        updateMoveArrows();
        showToast(Ui.tr("Mova com as setas esquerda e direita. OK para terminar.",
                "Move it with the left and right arrows. OK to finish.",
                "Muévela con las flechas izquierda y derecha. OK para terminar."));
    }

    /** The arrows around the card being moved live in the menu layer, which is moved to the card's height. */
    private void updateMoveArrows() {
        RectF r = new RectF();
        if (cardRect(r)) {
            menuTy = r.centerY() - 120 * k;
            menuLayer.setTranslationY(menuTy);
        }
        menuDirty = true;
    }

    private void drawMoveArrows(Canvas c) {
        RectF r = new RectF();
        if (!cardRect(r)) return;
        r.offset(0, -menuTy);
        int index = sheetOpen ? gridIndex : shelfIndex, count = sheetOpen ? pinnedCount : Math.min(cols, shelf.size());
        moveArrow.setStyle(Paint.Style.STROKE);
        moveArrow.setStrokeWidth(4.5f * k);
        moveArrow.setStrokeCap(Paint.Cap.ROUND);
        moveArrow.setStrokeJoin(Paint.Join.ROUND);
        for (int side = -1; side <= 1; side += 2) {
            if ((side < 0 && index <= 0) || (side > 0 && index >= count - 1)) continue;
            float cx = side < 0 ? r.left + 30 * k : r.right - 30 * k, cy = r.centerY();
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFF000000, 0.55f));
            c.drawCircle(cx, cy, 24 * k, fill);
            Path chevron = new Path();
            chevron.moveTo(cx - side * 5 * k, cy - 11 * k);
            chevron.lineTo(cx + side * 6 * k, cy);
            chevron.lineTo(cx - side * 5 * k, cy + 11 * k);
            moveArrow.setColor(0xFFFFFFFF);
            c.drawPath(chevron, moveArrow);
        }
    }

    /** One step left or right for the app being moved: the shelf (and the open panel) change on screen at once and it is saved. */
    private void moveShelfApp(int dir) {
        int from = sheetOpen ? gridIndex : shelfIndex, to = from + dir;
        int limit = sheetOpen ? pinnedCount : Math.min(cols, shelf.size());
        if (from < 0 || from >= shelf.size() || to < 0 || to >= limit) return;
        List<HomeApps.Item> copy = new ArrayList<>(shelf);
        java.util.Collections.swap(copy, from, to);
        setShelf(copy);
        if (sheetOpen) {
            rebuildPanel();
            gridIndex = to;
            retargetGrid(false);
            gridDirty = true;
        }
        shelfIndex = Math.min(to, Math.max(0, shelf.size() - 1));
        List<String> pkgs = new ArrayList<>();
        for (HomeApps.Item it : copy) if (it.pkg != null) pkgs.add(it.pkg);
        Prefs.setShelf(pkgs);
        retarget(false);
        updateMoveArrows();
        click.move();
    }

    private void back() {
        if (menuOpen && cardMenu) {
            closeMenu();
            click.move();
            return;
        }
        if (zone == Z_MENU) {
            closeMenu();
            click.move();
            return;
        }
        if (sheetOpen) {
            if (pinMode) closePin();
            else closeSheet();
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (menuOpen && cardMenu) {
            moveMenu(dx, dy);
            return;
        }
        if (sheetOpen) {
            if (pinMode) movePin(dx, dy);
            else moveGrid(dx, dy);
            return;
        }
        if (zone == Z_MENU) {
            moveMenu(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int slots = Math.min(shelf.size(), cols) + phCount();   // the apps and the empty slots
                    if (slots < 2) return;
                    int next = (shelfIndex + dx + slots) % slots;   // endless: past the last one comes the first, and the other way round
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[Math.min(shelfIndex, tileRects.length - 1)].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (panel.isEmpty()) return;
                    openSheet();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            default:
                return;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private static final float HIDDEN_BOTTOM = 36, HIDDEN_ROOM = 112;   // the button's distance from the bottom edge; the height the grid gives up for it
    private boolean hiddenOn;   // the focus is on the "Hidden apps" button under the last row
    private final Spring hiddenFocus = new Spring(0.26f, 0.85f, 0);

    /** The "Hidden apps" button under the last row of All apps (not while filtering). */
    private boolean hiddenAvailable() {
        return query.isEmpty() && !panel.isEmpty();
    }

    private void openHiddenApps() {
        keepSheet = true;
        // Only a fade in, from transparent to the screen (nothing grows): the home stays where it is underneath.
        final android.content.Intent intent = new android.content.Intent(activity, SettingsActivity.class).putExtra("hidden", true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        ScreenTransition.open(activity, () -> {
            try {
                activity.startActivity(intent, android.app.ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle());
            } catch (RuntimeException ignored) {
            }
        });
    }

    private static final int SEARCH_MIN_APPS = 24;   // the search field only shows with more apps than this

    private boolean searchAvailable() {
        if (!query.isEmpty()) return true;
        int n = 0;
        for (HomeApps.Item it : shelf) if (it.kind == HomeApps.APP) n++;
        for (HomeApps.Item it : others) if (it.kind == HomeApps.APP) n++;
        return n > SEARCH_MIN_APPS;
    }

    /** The focus goes up onto the search field, above the first row (or back to the Home when there is none). */
    private void goSearch() {
        if (!searchAvailable()) {   // few apps: there is no search field, so Up from the first row goes back to the Home
            closeSheet();
            click.move();
            return;
        }
        searchOn = true;
        hiddenOn = false;
        phGrid = -1;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void moveGrid(int dx, int dy) {
        int n = panel.size();
        if (hiddenOn) {   // on the "Hidden apps" button: Up goes back to the last row; nothing else moves
            if (dy < 0 && n > 0) {
                hiddenOn = false;
                gridIndex = idxAt(panelRows() - 1, cols / 2);
                retargetGrid(false);
                click.move();
                gridDirty = true;
            }
            return;
        }
        if (searchOn) {   // on the search field: Down goes back to the apps, Up closes the panel
            if (dy > 0 && n > 0) {
                searchOn = false;
                retargetGrid(false);
                click.move();
                gridDirty = true;
            } else if (dy < 0) {
                closeSheet();
                click.move();
            }
            return;
        }
        if (n == 0) {
            if (dy < 0) goSearch();
            return;
        }
        if (phGrid >= 0) {   // on an empty slot of the first row
            if (dx != 0) {
                int slot = phGrid + dx;
                if (slot < 0) {
                    phGrid = -1;
                    gridIndex = Math.max(0, pinnedCount - 1);
                } else if (slot < phCount()) {
                    phGrid = slot;
                } else {   // endless: past the last empty slot comes the first app of the row
                    phGrid = -1;
                    gridIndex = 0;
                }
            } else if (dy < 0) {
                goSearch();
                return;
            } else {
                if (panelRows() < 2) {
                    if (hiddenAvailable()) {
                        phGrid = -1;
                        hiddenOn = true;
                        retargetGrid(false);
                        click.move();
                        gridDirty = true;
                    }
                    return;
                }
                int col = pinnedCount + phGrid;
                phGrid = -1;
                gridIndex = idxAt(1, col);
            }
            retargetGrid(false);
            click.move();
            gridDirty = true;
            return;
        }
        int r = rowOf(gridIndex), col = colOf(gridIndex);
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && rowOf(candidate) == r) next = candidate;
            else if (dx > 0 && r == 0 && phCount() > 0 && gridIndex == pinnedCount - 1) {   // past the last app: the first empty slot
                phGrid = 0;
                retargetGrid(false);
                click.move();
                gridDirty = true;
                return;
            } else if (dx < 0 && r == 0 && phCount() > 0 && gridIndex == 0) {   // before the first app: the last empty slot
                phGrid = phCount() - 1;
                retargetGrid(false);
                click.move();
                gridDirty = true;
                return;
            } else {
                next = idxAt(r, dx > 0 ? 0 : cols - 1);   // endless along the row, as on the shelf
            }
        } else {
            int targetRow = r + dy;
            if (targetRow < 0) {   // up from the first row: the search field
                goSearch();
                return;
            }
            if (targetRow == 0 && phCount() > 0 && col >= pinnedCount && col - pinnedCount < phCount()) {   // up into an empty slot of the first row
                phGrid = col - pinnedCount;
                retargetGrid(false);
                click.move();
                gridDirty = true;
                return;
            }
            if (targetRow >= panelRows() && dy > 0 && hiddenAvailable()) {   // down from the last row: the "Hidden apps" button
                hiddenOn = true;
                retargetGrid(false);
                click.move();
                gridDirty = true;
                return;
            }
            if (targetRow < panelRows()) next = idxAt(targetRow, col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        if (menuOpen && cardMenu) {
            activateCardMenu();
            return;
        }
        if (zone == Z_MENU) {
            activateMenu();
            return;
        }
        if (pinMode && sheetOpen) {
            activatePin();
            return;
        }
        if (zone == Z_PLAYER && !sheetOpen) {
            activatePlayer();
            return;
        }
        if (sheetOpen && hiddenOn) {   // the "Hidden apps" button
            click.open();
            openHiddenApps();
            return;
        }
        if (sheetOpen && searchOn) {   // the search field: Borealis's keyboard
            click.open();
            openSearch();
            return;
        }
        if (sheetOpen && phGrid >= 0) {   // the "+": choose the pinned apps
            openPinMode();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (sheetOpen) {
            if (panel.isEmpty()) return;
            it = panel.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            if (p.music && nowPlaying.visible() && !nowPlaying.playing) nowPlaying.toggle();   // the song goes on where it was
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            r = cardRects[cardIndex];
        } else {
            if (onPlaceholder() || shelf.isEmpty() || shelfIndex >= shelf.size()) {
                if (onPlaceholder()) openPinMode();   // the "+": choose the pinned apps
                return;
            }
            it = shelf.get(shelfIndex);
            r = new RectF();
            panelRect(shelfIndex, 0, r);
            r.offset(0, sheetLayer.getTranslationY());
        }
        click.open();
        if (!sheetOpen && zone == Z_TOP && topIndex >= 3 && topIndex - 3 < btnFlash.length) {   // the corner button blinks
            btnFlash[topIndex - 3].snap(1);
            btnFlash[topIndex - 3].target = 0;
            status.invalidate();
        }
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.HIDDEN_CARD) {   // (the old card; the button under the last row replaced it)
            openHiddenApps();
            return;
        }
        if (it.kind == HomeApps.UPDATE) {   // the bell: the update, or the news again
            if (bellUpdate) askUpdate();
            else if (activity instanceof HomeActivity) ((HomeActivity) activity).showNewsHistory();
            return;
        }
        if (!sheetOpen && zone == Z_TOP && (it.kind == HomeApps.SYSTEM || it.kind == HomeApps.INPUTS || it.kind == HomeApps.WIFI || it.kind == HomeApps.BLUETOOTH || it.kind == HomeApps.SETTINGS)) {   // only the corner buttons open menus
            openMenu(it.kind);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
        else if (zone == Z_SHELF && it.kind == HomeApps.APP) rememberLaunch(it, r);   // the card zooms back when we return
    }

    // MARK: - Coming back from an app: its card zooms down from the full screen to its place

    private HomeApps.Item returnItem;
    private final RectF returnTo = new RectF(), returnBox = new RectF();
    private boolean returning, zoomShown, returnPending;
    private final Spring returnSpring = new Spring(0.5f, 0.92f, 0);   // coming back: unhurried
    private static final long RETURN_MAX_MS = 20 * 60_000L, HOME_HOLD_MS = 320;
    private long homePressedAt;
    private long returnHoldUntil, returnHoldMax;
    private boolean waitingFocus;

    /** An app was opened from the shelf or the panel: remember its card (in case this process is gone when we come back). */
    private void rememberLaunch(HomeApps.Item it, RectF r) {
        returnItem = it;
        returnTo.set(r);
        ui.edit().putString("ret_pkg", it.pkg).putFloat("ret_l", r.left).putFloat("ret_t", r.top)
                .putFloat("ret_r", r.right).putFloat("ret_b", r.bottom).putLong("ret_at", System.currentTimeMillis()).apply();
    }

    /** The home screen is back: if an app was opened from here a moment ago, its card shrinks down into its place. */
    private void startReturn() {
        if (!returnPending || w == 0) return;   // too early (the view has no size yet): onSizeChanged calls this again
        returnPending = false;
        boolean viaHome = SystemClock.uptimeMillis() - homePressedAt < 1500;
        HomeApps.Item it = returnItem;
        RectF from = new RectF(returnTo);
        long at = ui.getLong("ret_at", 0);
        String pkg = ui.getString("ret_pkg", null);
        if (it == null && pkg != null) {   // this process was restarted while the app ran
            for (HomeApps.Item p : shelf) if (pkg.equals(p.pkg)) it = p;
            for (HomeApps.Item p : panel) if (it == null && pkg.equals(p.pkg)) it = p;
            from.set(ui.getFloat("ret_l", 0), ui.getFloat("ret_t", 0), ui.getFloat("ret_r", 0), ui.getFloat("ret_b", 0));
        }
        ui.edit().remove("ret_pkg").apply();
        // The soft Home (no ADB): the window arrives late and the full-screen card would sit there waiting for it. No zoom.
        if (HomeMode.soft(getContext()) && !HomeMode.isFree(getContext())) {
            endReturn();
            return;
        }
        boolean tooOld = at == 0 || System.currentTimeMillis() - at > RETURN_MAX_MS;
        AppLog.w("zoom back: item=" + (it == null ? "none" : it.pkg) + (tooOld ? " (too long ago)" : ""));
        if (it == null || tooOld || (panel.isEmpty() && shelf.isEmpty())) {
            endReturn();
            return;
        }
        if (!sheetOpen && sheet.value > 0.01f) {   // Home was pressed with the panel open: it closes now, behind the card
            sheet.snap(0);
            updateMotion();
        }
        if (sheetOpen) {
            returnTo.set(from);
        } else {
            // Its place in the panel's first row (the shelf) or, if it isn't there, the middle of that row.
            int idx = -1;
            String want = pkg != null ? pkg : it.pkg;
            for (int i = 0; i < shelf.size() && i < cols; i++) if (want != null && want.equals(shelf.get(i).pkg)) idx = i;   // the shelf is the panel's first row
            if (idx >= 0) {
                panelRect(idx, 0, returnTo);
            } else {
                panelRect(0, 0, returnTo);
                returnTo.offset(w / 2f - returnTo.centerX(), 0);
            }
            returnTo.offset(0, (P_CLOSED_TOP - P_TOP) * k);
        }
        returnItem = it;
        zoomShown = true;
        returning = true;
        returnSpring.snap(1);
        returnSpring.target = 0;
        // With the Home button the home window is still fading in when we resume: hold the full-screen card a moment so
        // the zoom is seen (with Back the window is already there).
        // The card waits (full screen) until our window is really the one on screen: how soon that happens depends on
        // the app we leave and on Home or Back. Then a short beat, and it shrinks.
        long now = SystemClock.uptimeMillis();
        waitingFocus = !hasWindowFocus();
        returnHoldMax = now + 800;
        returnHoldUntil = now + (viaHome ? HOME_HOLD_MS : 120);
        returnLayer.setVisibility(VISIBLE);
        returnLayer.invalidate();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && waitingFocus) {
            waitingFocus = false;
            returnHoldUntil = Math.max(returnHoldUntil, SystemClock.uptimeMillis() + 160);
        }
    }

    private void endReturn() {
        returning = false;
        zoomShown = false;
        waitingFocus = false;
        returnItem = null;
        returnLayer.setVisibility(INVISIBLE);
    }

    private boolean tickReturn(float dt) {
        if (!returning) return false;
        long clock = SystemClock.uptimeMillis();
        if (clock < returnHoldUntil || (waitingFocus && clock < returnHoldMax)) {
            returnLayer.invalidate();
            return true;
        }
        boolean moving = returnSpring.step(dt);
        returnLayer.invalidate();
        if (!moving) {
            returning = false;
            endReturn();   // arrived back: the real card is there
        }
        return moving;
    }

    private void drawReturn(Canvas c) {
        HomeApps.Item it = returnItem;
        if (it == null || !zoomShown) return;
        float t = Math.max(0, Math.min(1.04f, returnSpring.value));
        // The full screen, a bit larger (so the card's rounded corners stay out of sight when it fills the screen).
        float grow = 0.12f * w;
        float bl = -grow, bt = -grow * h / w, br = w + grow, bb = h + grow * h / w;
        returnBox.set(returnTo.left + (bl - returnTo.left) * t, returnTo.top + (bt - returnTo.top) * t,
                returnTo.right + (br - returnTo.right) * t, returnTo.bottom + (bb - returnTo.bottom) * t);
        float r = TILE_RADIUS * k * (1 - Ui.clamp(t));
        int alpha = Math.round(255 * Ui.clamp(t * 2.2f));   // it fades out as it lands
        if (alpha <= 0) return;
        Bitmap img = ownCard(it.key);
        if (img == null) img = it.tile;
        if (img != null) {
            bitmapPaint.setAlpha(alpha);
            imageFill.setAlpha(alpha);
            roundImage(c, img, returnBox, r);
            bitmapPaint.setAlpha(255);
            imageFill.setAlpha(255);
            return;
        }
        int[] style = TILE_STYLE.get(it.key);
        tileFill.setShader(style != null ? new LinearGradient(0, returnBox.top, 0, returnBox.bottom, style[0], style[1], Shader.TileMode.CLAMP) : null);
        tileFill.setColor(0xFF161618);
        tileFill.setAlpha(alpha);
        c.drawRoundRect(returnBox, r, r, tileFill);
        tileFill.setShader(null);
        tileFill.setAlpha(255);
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    private static final int GRID = 9, A11Y_ALL = 8, WRAP = 10;   // A11Y_ALL: the "All apps" entry under the shelf (what Down does for the remote)

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (menuOpen) {   // the dropdown under a corner button (HDMI, Wi-Fi, Bluetooth, TV): its rows are all there is to reach
            for (int i = 0; i < menuRows.size(); i++) out.add(a11yId(Z_MENU, i));
            return;
        }
        if (sheetOpen && pinMode) {
            for (int i = 0; i < pinCount(); i++) out.add(a11yId(PIN_GROUP_ROW, i));
            for (int i = 0; i < pinAll.size(); i++) out.add(a11yId(PIN_GROUP_GRID, i));
            return;
        }
        if (sheetOpen) {
            for (int i = 0; i < panel.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        // The row the person is in shows all its items; the other rows show only the one the remote would land on
        // (the first the first time, then the one left behind). So TalkBack's Up and Down end where the remote ends.
        boolean other = zone != Z_TOP && zone != Z_CARDS && zone != Z_SHELF;   // a zone we don't list: everything stays reachable
        if (zone == Z_TOP || other) {
            for (int i = tabsVisible() ? 0 : 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        } else if (topHome() < 3 + lightButtons.size()) {
            out.add(a11yId(Z_TOP, topHome()));
        }
        int nc = Math.min(3, cards.size());
        if (zone == Z_CARDS || other) {
            for (int i = 0; i < nc; i++) out.add(a11yId(Z_CARDS, i));
        } else if (nc > 0) {
            out.add(a11yId(Z_CARDS, Math.min(cardIndex, nc - 1)));
        }
        if (zone == Z_SHELF || other) {
            for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        } else if (!shelf.isEmpty()) {
            out.add(a11yId(Z_SHELF, Math.min(shelfIndex, shelf.size() - 1)));
        }
        // Past the last card or app comes the first, and before the first the last (as the remote does): two nodes at the ends.
        if (!other && zone == Z_CARDS && nc >= 2) { out.add(a11yId(WRAP, 0)); out.add(a11yId(WRAP, 1)); }
        if (!other && zone == Z_SHELF && shelf.size() >= 2) { out.add(a11yId(WRAP, 2)); out.add(a11yId(WRAP, 3)); }
        if (!panel.isEmpty()) out.add(a11yId(A11Y_ALL, 0));
    }

    /** What TalkBack says for the whole screen: where the person is, never just "Borealis". */
    private String a11yTitle() {
        if (menuOpen && menuButton < lightButtons.size()) return label(lightButtons, menuButton);
        if (sheetOpen && pinMode) return Ui.tr("Seus apps à mão", "Your apps at hand", "Tus apps a la mano");
        if (sheetOpen) return Ui.tr("Todas as apps", "All apps", "Todas las apps");
        return Ui.tr("Borealis, início", "Borealis, home", "Borealis, inicio");
    }

    /** The screen changed under TalkBack (a list opened or closed): ask it to move to the focused item and read it. */
    private void a11yRefocus() {
        postDelayed(spoken::refocus, 350);
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case Z_MENU: {
                if (i >= menuRows.size()) return "";
                MRow r = menuRows.get(i);
                return r.label + (r.choices == null ? "" : ", " + r.choices.get(r.choice).label + ", " + r.choices.get(r.choice).desc) + (r.desc == null ? "" : ", " + r.desc) + (r.right == null ? "" : ", " + r.right) + (r.tag == null ? "" : ", " + r.tag) + ", " + (i + 1) + Ui.tr(" de ", " of ") + menuRows.size();
            }
            case PIN_GROUP_ROW:
            case PIN_GROUP_GRID:
                return pinText(group, i);
            case GRID:
                if (i >= panel.size()) return "";
                return label(panel, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + panel.size();
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return tabs()[i] + Ui.tr(", aba ", ", tab ") + (i + 1) + Ui.tr(" de 3", " of 3");
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF: return label(shelf, i);
            case WRAP: {   // 0/1: before the first / after the last card; 2/3: the same on the shelf. They read the item they lead to.
                boolean cardsRow = i < 2, after = i % 2 == 1;
                if (cardsRow) return cards.isEmpty() ? "" : pickText(cards.get(after ? 0 : Math.min(3, cards.size()) - 1));
                return shelf.isEmpty() ? "" : label(shelf, after ? 0 : shelf.size() - 1);
            }
            case A11Y_ALL: return Ui.tr("Todas as apps, abrir", "All apps, open", "Todas las apps, abrir");
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the sheet's hidden rows) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case WRAP: {
                boolean cardsRow = i < 2, after = i % 2 == 1;
                RectF[] rects = cardsRow ? cardRects : tileRects;
                int count = cardsRow ? Math.min(3, cards.size()) : Math.min(shelf.size(), rects.length);
                if (count < 2) return false;
                RectF edge = rects[after ? count - 1 : 0];
                float gap = 6 * k, width = 18 * k;
                if (after) a11yRect.set(edge.right + gap, edge.top, edge.right + gap + width, edge.bottom);
                else a11yRect.set(edge.left - gap - width, edge.top, edge.left - gap, edge.bottom);
                if (a11yRect.left < 0 || a11yRect.right > getWidth()) return false;
                dy = (cardsRow ? heroLayer : tiles).getTranslationY();
                break;
            }
            case A11Y_ALL: {
                if (panel.isEmpty() || sheetOpen) return false;
                a11yRect.set(0, h - 24 * k, getWidth(), h);
                dy = 0;
                break;
            }
            case Z_MENU: {
                if (!menuRowRect(i, a11yRect)) return false;
                dy = menuLayer.getTranslationY();
                break;
            }
            case PIN_GROUP_ROW:
            case PIN_GROUP_GRID:
                return sheetOpen && pinMode && pinBounds(group, i, out);
            case GRID: {
                if (!sheetOpen || i >= panel.size()) return false;
                panelRect(i, scroll.target, a11yRect);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (menuOpen) return menuRows.isEmpty() ? -1 : a11yId(Z_MENU, Math.min(menuIndex, menuRows.size() - 1));
        if (sheetOpen && pinMode) return pinRow ? a11yId(PIN_GROUP_ROW, pinRowIndex) : pinAll.isEmpty() ? -1 : a11yId(PIN_GROUP_GRID, pinGridIndex);
        if (sheetOpen) return panel.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() || shelfIndex >= shelf.size() ? -1 : a11yId(Z_SHELF, shelfIndex);
            default: return -1;
        }
    }

    /** TalkBack moved to an item: the remote's focus goes there. */
    private void a11yFocus(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == PIN_GROUP_ROW || group == PIN_GROUP_GRID) {
            if (sheetOpen && pinMode) pinFocus(group, i);
            return;
        }
        if (group == GRID) {
            if (!sheetOpen || i >= panel.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (group == Z_MENU) {
            if (!menuOpen || i >= menuRows.size() || i == menuIndex) return;
            menuIndex = i;
            menuAt = SystemClock.uptimeMillis();
            menuDirty = true;
            click.move();
            return;
        }
        if (group == WRAP) {
            boolean cardsRow = i < 2, after = i % 2 == 1;
            if (cardsRow) {
                int n = Math.min(3, cards.size());
                if (n < 2) return;
                cardIndex = after ? 0 : n - 1;
                zone = Z_CARDS;
            } else {
                if (shelf.size() < 2) return;
                shelfIndex = after ? 0 : shelf.size() - 1;
                zone = Z_SHELF;
            }
            retarget(false);
            click.move();
            a11yRefocus();
            return;
        }
        if (group == A11Y_ALL) {   // going down from the shelf: the all-apps panel opens, as it does with the remote
            if (!sheetOpen && !panel.isEmpty()) {
                openSheet();
                click.move();
                a11yRefocus();
            }
            return;
        }
        if (sheetOpen) return;
        // Entering another row of the screen (up or down): the item the remote would land on (the first one the first time,
        // then the one left behind), not the one TalkBack found nearest.
        final int asked = i;
        if (group != zone) {
            if (group == Z_CARDS) i = Math.max(0, Math.min(cardIndex, cards.size() - 1));
            else if (group == Z_SHELF) i = Math.max(0, Math.min(shelfIndex, shelf.size() - 1));
            else if (group == Z_TOP) i = topHome();
        }
        switch (group) {
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return;
                topIndex = i;
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
        spoken.focusMoved();   // the rows show/hide their items as the row changes
        if (i != asked) a11yRefocus();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 20 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 14 * k;
        }
        layoutButtons();
        layoutCards();
        heroDirty = statusDirty = true;
    }

    private float cardsTop() { return (tabsVisible() ? L_CARDS_TOP : L_CARDS_TOP_FLAT) * k; }
    private float cardsHeight() { return (tabsVisible() ? L_CARD_H : L_CARD_H_FLAT) * k; }

    /** The three cards side by side: the expanded one is wide, the others compact, and each width follows its spring. */
    private void layoutCards() {
        float top = cardsTop(), ch = cardsHeight(), g = L_CARD_GAP * k, x = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        for (int i = 0; i < 3; i++) {
            float cw = tall + (wide - tall) * cardGrow[i].value;
            cardRects[i].set(x, top, x + cw, top + ch);
            x += cw + g;
        }
        heroDirty = true;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        accessOk = Watched.permitted(getContext());
        if (recentCards() && tab != 0) {   // no tabs in this mode: everything
            tab = 0;
            cardIndex = 0;
        }
        List<Recs.Pick> list;
        if (recentCards()) {
            // What was being watched first; if there are fewer than three, TMDB's picks fill the rest (never an empty card).
            list = watched.picks(null);
            if (list.size() < 3) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Recs.Pick p : list) seen.add(p.title == null ? "" : p.title.toLowerCase(java.util.Locale.ROOT));
                for (Recs.Pick r : recs.now(null)) {
                    if (list.size() >= 3) break;
                    if (seen.add(r.title == null ? "" : r.title.toLowerCase(java.util.Locale.ROOT))) list.add(r);
                }
            }
        } else {
            list = recs.now(TAB_KIND[tab]);
        }
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        if (!recentCards()) {   // the three tabs preloaded (as many as fit in memory): switching tabs is instant
            int budget = recs.capacity();
            for (Recs.Pick p : cards) {
                recs.backdrop(p);
                budget--;
            }
            for (Recs.Pick p : recs.wanted()) {
                if (budget <= 0) break;
                if (!cards.contains(p)) {
                    recs.backdrop(p);
                    budget--;
                }
            }
        }
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(0));
        if (cards.isEmpty() || b != null) showBackdrop(b);
        layoutCards();   // the cards sit higher and taller when there are no tabs
        if (!tabsVisible() && zone == Z_TOP && topIndex < 3) {   // the tabs are gone: the focus goes to the first corner button
            topIndex = 3;
            retarget(false);
        }
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        cardIndex = 0;   // a new tab opens with its first card expanded
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        int wallpaper = Prefs.wallpaper();
        if (wallpaper != waveIdx) {   // another wallpaper picked in Settings
            waveIdx = wallpaper;
            wave = null;
            waveTried = false;
        }
        if (wave == null && !waveTried) {
            waveTried = true;
            try (java.io.InputStream in = getContext().getAssets().open(Prefs.WALLPAPER_FILES[wallpaper])) {
                // Decoded at the screen's size (the file is 1920 wide): a 720p TV needs less than half the memory.
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                if (w > 0 && w < 1920) {
                    o.inScaled = true;
                    o.inDensity = 1920;
                    o.inTargetDensity = w;
                }
                wave = android.graphics.BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) {
            }
        }
        if (wave != null) {   // the design's wave background replaces the blurred photo
            drawImage(c, wave, 1);
            return;
        }
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
        }
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private Paint scrimPaint;
    private int scrimH;

    /** A soft shade at the bottom of the home, like the one on the playing cards: it grounds the shelf over any wallpaper. */
    private void drawBottomShade(Canvas c) {
        if (w == 0 || h == 0) return;
        if (scrimPaint == null || scrimH != h) {
            scrimPaint = new Paint();
            scrimH = h;
            // A vignette: dark at the bottom edge, fading to nothing towards the top (like the base of the playing cards).
            scrimPaint.setShader(new LinearGradient(0, h * 0.36f, 0, h,
                    new int[] {0x00171519, Ui.alpha(0xFF171519, 0.10f), Ui.alpha(0xFF171519, 0.42f), Ui.alpha(0xFF171519, 0.86f)},
                    new float[] {0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        }
        c.drawRect(0, h * 0.36f, w, h, scrimPaint);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        String name = Prefs.name();
        String hi = name.isEmpty() ? Ui.tr("Olá!", "Hi!", "¡Hola!")
                : Ui.tr("Olá, " + name + "!", "Hi, " + name + "!", "¡Hola, " + name + "!");
        String ask = Ui.tr("O que você quer assistir hoje?", "What do you want to watch today?", "¿Qué quieres ver hoy?");
        // The profile avatar, round, to the left of the greeting (the text moves over to make room).
        float off = 0;
        String avatar = Avatars.current();
        if (!avatar.isEmpty()) {
            float ar = 44 * k;
            float acx = MARGIN * k + ar, acy = L_BUTTON_CY * k;   // level with the corner buttons
            Avatars.draw(c, getContext(), avatar, acx, acy, ar, 1f);
            ringPaint.setStyle(Paint.Style.STROKE);   // the white border the shortcuts have
            ringPaint.setShader(null);
            ringPaint.setStrokeWidth(3 * k);
            ringPaint.setColor(0xFFFFFFFF);
            c.drawCircle(acx, acy, ar + 1.5f * k, ringPaint);
            off = 2 * ar + 26 * k;
        }
        c.drawText(hi, MARGIN * k + off - Ui.leftBearing(lHello, hi), L_HELLO_BASE * k, lHello);
        c.drawText(ask, MARGIN * k + off - Ui.leftBearing(lTitle, ask), L_TITLE_BASE * k, lTitle);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        if (tabsVisible()) {
            for (int i = 0; i < 3; i++) {
                RectF r = topRects[i];
                float focus = Ui.clamp(topFocus[i].value);
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                if (i == tab) {
                    // Selected tab: filled with the lily → peach → melon gradient; focus adds a white border.
                    fill.setShader(new LinearGradient(cell.left, 0, cell.right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                    c.drawRoundRect(cell, rr, rr, fill);
                    fill.setShader(null);
                    if (focus > 0.01f) {
                        border.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * focus));
                        tmp.set(cell.left + k, cell.top + k, cell.right - k, cell.bottom - k);
                        c.drawRoundRect(tmp, rr - k, rr - k, border);
                    }
                } else {
                    // Other tabs: dark pill; focus only adds the gradient ring (like the corner buttons), no fill.
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(PILL, 0.7f + 0.25f * focus));
                    c.drawRoundRect(cell, rr, rr, fill);
                    if (focus > 0.01f) ring(c, cell, rr, focus, 4.5f * k);
                }
                Paint p = i == tab ? lTabOn : lTab;
                if (i == tab) lTabOn.setColor(0xFFFFFFFF);
                int old = p.getColor();
                if (i != tab && focus > 0.01f) p.setColor(Ui.alpha(0xFFFFFFFF, 0.75f + 0.25f * focus));
                float tx = r.centerX() - p.measureText(tabs()[i]) / 2, ty = r.centerY() + Ui.halfCap(p);
                c.drawText(tabs()[i], tx, ty, p);
                p.setColor(old);
            }
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < 3; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (cards.isEmpty() && recentCards()) drawNothingToContinue(c);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        if (p == null && recentCards()) return;    // fewer than three: no empty boxes
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        if (b != null && b.tall != null && b.wide != null) {
            float e = Ui.clamp(cardGrow[i].value);
            coverImage(c, b.tall, cell, r, 1f);                      // compact crop underneath, wide crop fades in on top
            if (e > 0.01f) coverImage(c, b.wide, cell, r, e);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            sheen.setShader(new LinearGradient(cell.left, cell.top, cell.left + cell.width() * 0.5f, cell.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.22f * f), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, sheen);
        }
        float shown = progressOf(p);
        boolean hasBar = p != null && shown >= 0;
        if (p != null) cardTexts[i].drawScrim(c, cell, r);   // under the progress line and its labels, which stay readable
        float textRight = cell.right;
        if (p != null && p.music && p.cover != null) {   // over the shade: sharp and clear
            float coverLift = hasBar ? (p.music && !p.live ? 104 : 76) : 88;   // the same lift the title uses
            textRight = drawMusicCover(c, cell, p, cardGrow[i].value > 0.5f, cell.bottom - (coverLift + 34) * k - 22 * k);
        }
        if (hasBar) drawProgress(c, cell, r, shown, p.music && !p.live ? p.left : null, Ui.clamp(cardGrow[i].value), Math.max(f, Ui.clamp(cardGrow[i].value)), playLabel(p),
                p.live && nowPlaying.dur > 0 ? NowPlaying.remaining(nowPlaying.dur - nowPlaying.position()) : p.remain);
        if (p != null) {   // title and one line inside the card; with a progress line, "time left" is already above the bar
            String line = p.meta;
            if (p.left != null) line = hasBar || (p.music && p.live) ? p.detail : p.detail == null || p.detail.isEmpty() ? p.left : p.left + " · " + p.detail;
            // Where the line sits: right over the progress line (where "Quedan 3 min" used to be); higher when a song that
            // is not playing keeps its "Escuchaste hace…" label there; above the play button row on cards with no line.
            float lift = hasBar ? (p.music && !p.live ? 104 : 76) : 88;
            cardTexts[i].drawText(c, cell, cardGrow[i].value > 0.5f, lift, p.title, line, textRight);
            drawChip(c, cell, p.chip != null ? p.chip : Recs.serviceName(p));
        }
        ring(c, cell, r, f, 5 * k);
        float vis = Math.max(f, Ui.clamp(cardGrow[i].value));   // the open card always shows its button; the others when focused
        if (vis > 0.05f && p != null) drawPlay(c, cell, Ui.clamp(cardGrow[i].value), f, vis, playLabel(p));
    }

    /** The platform tag at the top left of a card ("Netflix", "Paramount+"): a small dark pill with white text. */
    private void drawChip(Canvas c, RectF card, String text) {
        if (text == null || text.isEmpty() || chipText == null) return;
        float padX = 14 * k, hgt = 34 * k, max = card.width() - 36 * k;
        String t = TextUtils.ellipsize(text, new TextPaint(chipText), max - 2 * padX, TextUtils.TruncateAt.END).toString();
        float wd = chipText.measureText(t) + 2 * padX;
        tmp.set(card.left + 16 * k, card.top + 16 * k, card.left + 16 * k + wd, card.top + 16 * k + hgt);
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setShader(null);
        barPaint.setColor(Brand.ink(0xB3));
        c.drawRoundRect(tmp, hgt / 2, hgt / 2, barPaint);
        c.drawText(t, tmp.left + padX, tmp.centerY() + Ui.halfCap(chipText), chipText);
    }

    /** "Seguir viendo": a soft dark fade at the bottom of the card and the progress line (lily → peach → melon). */
    /** How far a card has got: for the song playing now, from the live position; otherwise what the history saved. */
    private float progressOf(Recs.Pick p) {
        if (p == null) return -1;
        if (p.live && nowPlaying.dur > 0) return Ui.clamp(nowPlaying.position() / (float) nowPlaying.dur);
        return p.progress;
    }

    /** The card's button: "Seguir escuchando" for a song, "Continuar viendo" for the rest of the history, "Ver ahora" for suggestions. */
    private static String playLabel(Recs.Pick p) {
        if (p.music && p.live) return Ui.tr("Abrir ", "Open ", "Abrir ") + (p.label == null ? "" : p.label);   // it is playing: just go there
        if (p.music) return Ui.tr("Continuar ouvindo", "Keep listening", "Seguir escuchando");
        return p.left != null ? Ui.tr("Continuar assistindo", "Continue watching", "Continuar viendo") : Ui.tr("Assistir", "Watch now", "Ver ahora");
    }

    private void drawProgress(Canvas c, RectF card, float radius, float progress, String label, float expanded, float vis, String button, String countdown) {
        barPaint.setStyle(Paint.Style.FILL);
        barPaint.setShader(new LinearGradient(0, card.bottom - card.height() * 0.5f, 0, card.bottom,
                0x00000000, 0xCC000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(card, radius, radius, barPaint);
        // On the play button's row, to its left with a gap and centered with it (the room is always reserved, focused or not);
        // what was watched in color, what is left in white, the time left above the bar.
        float inset = 22 * k, bh = 9 * k, btnH = 50 * k, left = card.left + inset;
        float btnW = expanded > 0.5f ? 22 * k + 26 * k + 10 * k + btnLabel.measureText(button) + 24 * k : btnH;
        float right = card.right - inset - (btnW + 36 * k) * vis;   // the whole width until the button shows
        float y = card.bottom - inset - btnH / 2 - bh / 2;
        // The time left sits at the end of the line, on its row (a song playing counts down; a video shows what is left).
        float cw = countdown != null && chipText != null ? chipText.measureText(countdown) + 14 * k : 0;
        float barRight = right - cw;
        tmp.set(left, y, barRight, y + bh);
        barPaint.setShader(null);
        barPaint.setColor(0xE6FFFFFF);
        c.drawRoundRect(tmp, bh / 2, bh / 2, barPaint);
        tmp.set(left, y, left + Math.max(bh, (barRight - left) * Ui.clamp(progress)), y + bh);
        barPaint.setShader(new LinearGradient(left, 0, barRight, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(tmp, bh / 2, bh / 2, barPaint);
        barPaint.setShader(null);
        if (cw > 0) c.drawText(countdown, right - (cw - 14 * k), y + bh / 2 + Ui.halfCap(chipText), chipText);
        if (label != null && !label.isEmpty() && chipText != null) {   // a song that is not playing: "Escuchaste hace 2 h"
            String t = TextUtils.ellipsize(label, new TextPaint(chipText), Math.max(0, right - left - 16 * k), TextUtils.TruncateAt.END).toString();
            c.drawText(t, left, y - 12 * k, chipText);
        }
    }

    /** The cards area when "Seguir viendo" has nothing to continue yet (or lacks the permission). */
    private void drawNothingToContinue(Canvas c) {
        float top = cardsTop(), left = MARGIN * k, right = CONTENT_R * k, hgt = cardsHeight(), r = 18 * k;
        tmp.set(left, top, right, top + hgt);
        fill.setShader(null);
        fill.setColor(Ui.alpha(PILL, 0.55f));
        c.drawRoundRect(tmp, r, r, fill);
        border.setColor(0x26FFFFFF);
        c.drawRoundRect(tmp, r, r, border);
        String one, two;
        if (!accessOk) {
            one = Ui.tr("Ative o acesso à reprodução para continuar o que você estava assistindo",
                    "Turn on playback access to continue what you were watching",
                    "Activa el acceso a reproducción para retomar lo que estabas viendo");
            two = Ui.tr("Configurações do Borealis \u203A Acesso à reprodução",
                    "Borealis Settings \u203A Playback access",
                    "Ajustes de Borealis \u203A Acceso a reproducción");
        } else {
            one = Ui.tr("Aqui aparecerá o que você estiver assistindo",
                    "What you watch will show up here",
                    "Aquí aparecerá lo que estés viendo");
            two = Ui.tr("Reproduza algo e volte ao início para continuar de onde parou",
                    "Play something and come back home to pick it up where you left off",
                    "Reproduce algo y vuelve al inicio para retomarlo donde lo dejaste");
        }
        float cx = tmp.centerX(), cy = tmp.centerY();
        Paint.Align a1 = lCardTitle.getTextAlign(), a2 = lCardMeta.getTextAlign();
        lCardTitle.setTextAlign(Paint.Align.CENTER);
        lCardMeta.setTextAlign(Paint.Align.CENTER);
        float maxW = tmp.width() - 80 * k;
        c.drawText(TextUtils.ellipsize(one, new TextPaint(lCardTitle), maxW, TextUtils.TruncateAt.END).toString(), cx, cy - 6 * k, lCardTitle);
        c.drawText(TextUtils.ellipsize(two, new TextPaint(lCardMeta), maxW, TextUtils.TruncateAt.END).toString(), cx, cy + 36 * k, lCardMeta);
        lCardTitle.setTextAlign(a1);
        lCardMeta.setTextAlign(a2);
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) drawLightButton(c, i);
    }

    /** One corner button (also drawn again by the Settings menu, above its dim). The Settings button grows 16% when focused and stays centered. */
    private void drawLightButton(Canvas c, int i) {
        {
            HomeApps.Item it = lightButtons.get(i);
            boolean sys = it.kind == HomeApps.SYSTEM;
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + (sys ? 0.16f : 0.04f) * f;
            float cw = r.width() * s, ch = r.height() * s, rad = ch / 2;
            if (sys) cell.set(r.centerX() - cw / 2, r.centerY() - ch / 2, r.centerX() + cw / 2, r.centerY() + ch / 2);
            else cell.set(r.right - cw, r.centerY() - ch / 2, r.right, r.centerY() + ch / 2);   // grows to the left, the right edge stays
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            if (sys) {   // like the time pill beside it (same height): flat white 8%, plus a 1 px (of the 1280 design) white 22% border
                if (f > 0.01f) {
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.2f) * f));
                    shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
                }
                if (f < 0.99f) {
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f * (1 - f)));
                    c.drawRoundRect(cell, rad, rad, fill);
                    menuBorder.setAlpha(Math.round(56 * (1 - f)));
                    tmp.set(cell.left + 0.75f * k, cell.top + 0.75f * k, cell.right - 0.75f * k, cell.bottom - 0.75f * k);
                    c.drawRoundRect(tmp, rad - 0.75f * k, rad - 0.75f * k, menuBorder);
                }
            } else {
                shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
                shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
                scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                        tone(0.6f, 0.45f), 1 - f);
            }
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(PILL, 0.92f * f));
                c.drawRoundRect(cell, rad, rad, fill);
                ring(c, cell, rad, f, 4.5f * k);
            }
            if (i < btnFlash.length && btnFlash[i].value > 0.01f) {   // the blink when it is clicked: the gradient fill
                fill.setShader(new LinearGradient(cell.left, 0, cell.right, 0, new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
                fill.setAlpha(Math.round(255 * Ui.clamp(btnFlash[i].value)));
                c.drawRoundRect(cell, rad, rad, fill);
                fill.setShader(null);
                fill.setAlpha(255);
            }
            if (it.kind == HomeApps.SYSTEM) drawSliders(c, cell.left + ch / 2, cell.centerY(), 36 * k * s);
            else Symbols.draw(getContext(), c, it.symbol, cell.left + ch / 2, cell.centerY(), 28 * k * s, 0xFFFFFFFF);
            if ((it.kind == HomeApps.UPDATE && bellUpdate) || (sys && (bellUpdate || SAMPLE_UPDATE))) {   // a small dot: there is something new
                fill.setShader(null);
                fill.setColor(MELON);
                c.drawCircle(cell.left + ch * 0.72f, cell.top + ch * 0.26f, 7 * k * s, fill);
            }
            if (it.kind == HomeApps.SYSTEM) {   // the name appears under the button
                if (f > 0.05f) {
                    btnLabel.setAlpha(Math.round(255 * f));
                    c.drawText(it.label, cell.centerX() - btnLabel.measureText(it.label) / 2, cell.bottom + 38 * k, btnLabel);
                    btnLabel.setAlpha(255);
                }
            } else if (f > 0.35f) {   // the name appears once the pill is wide enough
                btnLabel.setAlpha(Math.round(255 * Ui.clamp((f - 0.35f) / 0.65f)));
                c.drawText(it.label, cell.left + ch - 2 * k, cell.centerY() + Ui.halfCap(btnLabel), btnLabel);
                btnLabel.setAlpha(255);
            }
        }
    }

    /** Positions the corner buttons from the right edge: the focused one stretches into a pill and pushes the rest left. */
    private void layoutButtons() {
        if (btnLabel == null) return;
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            float f = Ui.clamp(topFocus[3 + i].value);
            float extra = lightButtons.get(i).kind == HomeApps.SYSTEM ? 0 : (btnLabel.measureText(lightButtons.get(i).label) + 24 * k) * f;
            topRects[3 + i].set(right - b - extra, cy - b / 2, right, cy + b / 2);
            right -= b + extra + L_BUTTON_GAP * k;
        }
    }

    // MARK: - HDMI menu: opens under its button; Up/Down choose, OK switches, Back (or 4 s of silence) closes

    /** One input of the "Entradas" row. */
    private static final class Choice {
        String label, desc, symbol;
        int accent = 0xFFFFFFFF;
        boolean dot, on, dim;   // the status dot (green / gray) and a grayed look
        HomeApps.Item item;
        Runnable run;
    }

    /** One row of the dropdown menu. */
    private static final class MRow {
        List<Choice> choices;   // a row that changes with Left/Right (the video inputs)
        int choice;
        String label, tag, symbol;
        String desc, right;     // second line under the label; text at the right edge ("82 %")
        HomeApps.Item item;
        int bars = -1;          // 0..4 draws the Wi-Fi signal at the right edge
        int accent = 0xFFFFFFFF;   // color of the icon
        boolean dim, dot;       // dim = grayed out; dot = status dot at the right edge (HDMI)
        boolean on;             // dot: green (connected) or peach (not)
        boolean sepBefore;      // a divider line above this row
        boolean strong;         // bold label and a chevron (Borealis settings)
        boolean highlight;      // filled with the brand gradient (the update)
        Runnable run;           // the card menu's action
    }

    /** Opens the Settings dropdown under its corner button. */
    private void openMenu(int kind) {
        cardMenu = false;
        menuLayer.setTranslationY(0);
        menuTy = 0;
        menuRows.clear();
        for (int i = 0; i < lightButtons.size(); i++) if (lightButtons.get(i).kind == kind) menuButton = i;
        systemRows();
        layoutSystemMenu();
        menuOpen = true;
        menuIndex = 0;
        menuAt = SystemClock.uptimeMillis();
        zone = Z_MENU;
        menu.target = 1;
        menuDirty = true;
        retarget(false);
        spoken.focusMoved();
    }

    /** The menu: the update, Wi-Fi, Bluetooth and every video input (quick), then (after a divider) the TV's settings and ours. */
    private void systemRows() {
        if (bellUpdate || SAMPLE_UPDATE) {
            String name = bellUpdate ? Update.newer(getContext()) : "3.48.0";
            // Until a real update shows up, this row carries the big news: the 2,000 downloads celebration.
            MRow up = cardRow("sync", bellUpdate ? Ui.tr("Atualizar para ", "Update to ", "Actualizar a ") + name
                            : Ui.tr("Grandes novidades", "Big news", "Grandes noticias"),
                    bellUpdate ? this::askUpdate : () -> { if (activity instanceof HomeActivity) ((HomeActivity) activity).showMilestoneAgain(); });
            up.desc = bellUpdate ? Ui.tr("Nova versão disponível", "New version available", "Nueva versión disponible")
                    : Ui.tr("Passamos de 2 mil downloads em uma semana", "We passed 2,000 downloads in one week", "Sumamos más de 2 mil descargas en una semana");
            up.highlight = true;   // filled with the brand gradient: it must not look like one more row
            menuRows.add(up);
        }
        askLocationOnce();
        MRow wifi = wifiRow();
        wifi.item = HomeApps.action(HomeApps.WIFI, "wifi", "Wi-Fi", "wifi");
        wifi.accent = LILY;
        menuRows.add(wifi);
        MRow bt = btRow();
        bt.accent = PEACH;
        menuRows.add(bt);
        MRow in = new MRow();   // all the video inputs in ONE row: Left/Right changes which one it shows, OK switches to it
        in.label = Ui.tr("Entradas", "Inputs", "Entradas");
        in.choices = new ArrayList<>();
        int firstOn = -1;
        for (HomeApps.Item it : inputItems) {
            if (it.kind != HomeApps.INPUT) continue;
            Choice ch = new Choice();
            ch.item = it;
            ch.label = it.label;
            if (it.analog) {   // AV, VGA...: the TV doesn't say if a cable is in, so no dot and no "connected"
                ch.symbol = it.inputType == android.media.tv.TvInputInfo.TYPE_VGA ? "d_vga"
                        : it.inputType == android.media.tv.TvInputInfo.TYPE_DVI || it.inputType == android.media.tv.TvInputInfo.TYPE_DISPLAY_PORT ? "d_hdmi" : "d_av";
                ch.accent = MELON;
                ch.desc = Ui.tr("Disponível", "Available", "Disponible");
            } else {           // HDMI: green dot connected, gray not (red-ish tones mean "update")
                ch.symbol = it.symbol.equals("hdmi") ? "d_hdmi" : it.symbol;
                ch.accent = it.connected ? MELON : GRAY;
                ch.dot = true;
                ch.on = it.connected;
                ch.dim = !it.connected;
                ch.desc = it.connected ? Ui.tr("Conectado", "Connected", "Conectado") : Ui.tr("Desconectado", "Disconnected", "Desconectado");
                if (it.connected && firstOn < 0) firstOn = in.choices.size();
            }
            in.choices.add(ch);
        }
        if (!in.choices.isEmpty()) {
            in.choice = Math.max(0, firstOn);   // opens on the first one with a device
            applyChoice(in);
            menuRows.add(in);
        }
        MRow tv = new MRow();
        tv.item = HomeApps.action(HomeApps.ANDROID_SETTINGS, "android_settings", Ui.tr("Ajustes da TV", "TV settings", "Ajustes de la TV"), "tv_settings");
        tv.symbol = "d_tv";
        tv.label = tv.item.label;
        tv.sepBefore = true;
        menuRows.add(tv);
        MRow own = new MRow();
        own.item = HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Ajustes do Borealis", "Borealis settings", "Ajustes de Borealis"), "settings");
        own.symbol = "d_borealis";   // the logo's waves, flat white
        own.label = own.item.label;
        own.strong = true;
        menuRows.add(own);
        // Shorten what is too long once, now (not on every frame).
        float room = 600 * k - 30 * k - 24 * k - 39 * k - 21 * k - 24 * k - 84 * k;
        for (MRow r : menuRows) {
            r.label = TextUtils.ellipsize(r.label, new TextPaint(r.strong ? menuTitleBold : menuTitle), room, TextUtils.TruncateAt.END).toString();
            if (r.desc != null) r.desc = TextUtils.ellipsize(r.desc, new TextPaint(menuDesc), room, TextUtils.TruncateAt.END).toString();
            if (r.choices != null) {
                for (Choice ch : r.choices) {   // the name sits between two arrows: 190 wide at most
                    ch.label = TextUtils.ellipsize(ch.label, new TextPaint(menuTitle), 190 * k, TextUtils.TruncateAt.END).toString();
                }
            }
        }
    }

    /** The "Entradas" row shows its chosen input: its icon, and what OK does. */
    private void applyChoice(MRow r) {
        Choice ch = r.choices.get(r.choice);
        r.item = ch.item;
        r.run = ch.run;
        r.symbol = ch.symbol;
        r.accent = ch.accent;
    }

    /** Left/Right on the "Entradas" row: the previous or next input (endless). */
    private void cycleChoice(MRow r, int dx) {
        int n = r.choices.size();
        if (n < 2) return;
        r.choice = (r.choice + dx + n) % n;
        applyChoice(r);
        menuDirty = true;
        click.move();
        spoken.focusMoved();
    }

    /**
     * Where the menu and its rows are (design x1.5: 600 wide, rows 90 high with 6 between, a 25 divider, 15 padding),
     * right edge 12 left of the button's, below its name. If it does not fit the screen's height, the rows get closer.
     */
    private void layoutSystemMenu() {
        int n = menuRows.size();
        float pad = 15 * k, row = 90 * k, gap = 6 * k, sep = 25.5f * k;
        float top = topRects[3 + menuButton].bottom + 64 * k;
        float rows = n * row, gaps = 0;
        for (int i = 1; i < n; i++) gaps += menuRows.get(i).sepBefore ? sep : gap;
        float want = 2 * pad + rows + gaps, avail = h - 24 * k - top;
        float sc = want > avail ? Math.max(0.6f, (avail - 2 * pad) / (rows + gaps)) : 1;
        row *= sc;
        gap *= sc;
        sep *= sc;
        sysRowH = row;
        if (sysTop.length < n) sysTop = new float[n];
        float y = pad;
        for (int i = 0; i < n; i++) {
            if (i > 0) y += menuRows.get(i).sepBefore ? sep : gap;
            sysTop[i] = y;
            y += row;
        }
        float width = 600 * k, right = topRects[3 + menuButton].right - 12 * k;
        sysBox.set(right - width, top, right, top + y + pad);
        // The update row's fill: a deeper Lily to Peach, left to right (drawn translucent over the dark menu).
        sysHi = new LinearGradient(sysBox.left + pad, 0, sysBox.right - pad, 0, new int[] {0xFF8C72E0, PEACH}, null, Shader.TileMode.CLAMP);
    }

    /** Android only gives the Wi-Fi network's name to apps that may see the location: asked once, the first time the Wi-Fi menu opens. */
    private void askLocationOnce() {
        try {
            if (getContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            android.content.SharedPreferences sp = getContext().getSharedPreferences("asked", Context.MODE_PRIVATE);
            if (sp.getBoolean("location", false)) return;
            sp.edit().putBoolean("location", true).apply();
            activity.requestPermissions(new String[] {android.Manifest.permission.ACCESS_FINE_LOCATION}, 77);
        } catch (RuntimeException ignored) {
        }
    }

    /** Wi-Fi: "Wi-Fi" with the network's name (and band) under it and the signal at the right; cable; or not connected. */
    private MRow wifiRow() {
        MRow r = new MRow();
        r.symbol = "wifi";
        r.label = "Wi-Fi";
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                r.label = "Ethernet";
                r.desc = Ui.tr("Conectado por cabo", "Wired connection", "Conectado por cable");
                return r;
            }
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && info != null) {
                String ssid = info.getSSID();
                if (ssid != null && ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length() - 1);
                if (ssid == null || ssid.isEmpty() || ssid.contains("unknown ssid")) ssid = Ui.tr("Rede Wi-Fi", "Wi-Fi network", "Red Wi-Fi");
                int f = info.getFrequency();
                String band = f <= 0 ? null : f > 5900 ? "6 GHz" : f > 4900 ? "5 GHz" : "2.4 GHz";
                r.desc = band == null ? ssid : ssid + " · " + band;
                r.bars = Math.max(0, Math.min(4, android.net.wifi.WifiManager.calculateSignalLevel(info.getRssi(), 5)));
                return r;
            }
        } catch (RuntimeException ignored) {
        }
        r.desc = Ui.tr("Sem conexão", "Not connected", "Sin conexión");
        r.dim = true;
        return r;
    }

    /** Bluetooth: "Bluetooth" with the first connected device (remote, headphones, gamepad) under it and its battery at the right. */
    private MRow btRow() {
        MRow r = new MRow();
        r.symbol = "bluetooth";
        r.label = "Bluetooth";
        r.item = HomeApps.action(HomeApps.BLUETOOTH, "bluetooth", "Bluetooth", "bluetooth");
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ad == null) {
                r.desc = Ui.tr("Indisponível", "Unavailable", "No disponible");
                r.dim = true;
                return r;
            }
            if (!ad.isEnabled()) {
                r.desc = Ui.tr("Desligado", "Off", "Apagado");
                r.dim = true;
                return r;
            }
            for (android.bluetooth.BluetoothDevice d : ad.getBondedDevices()) {
                boolean on = false;
                try {
                    on = (Boolean) d.getClass().getMethod("isConnected").invoke(d);
                } catch (Exception ignored) {
                }
                if (!on) continue;
                int battery = -1;
                try {
                    battery = (Integer) d.getClass().getMethod("getBatteryLevel").invoke(d);
                } catch (Exception ignored) {
                }
                r.desc = d.getName() == null ? d.getAddress() : d.getName();
                if (battery >= 0 && battery <= 100) r.right = battery + " %";
                return r;
            }
            r.desc = Ui.tr("Nenhum dispositivo conectado", "No connected devices", "Sin dispositivos conectados");
        } catch (RuntimeException e) {
            r.desc = Ui.tr("Indisponível", "Unavailable", "No disponible");
            r.dim = true;
        }
        return r;
    }

    /** A spoken-feedback service (TalkBack) is on: it needs more time than the 4 s of the dropdown. Our own key service doesn't count. */
    private boolean screenReader() {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && !am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void closeMenu() {
        if (cardMenu) {   // the card's menu: the focus never left the card
            cardMenu = false;
            menuOpen = false;
            menu.target = 0;
            menuDirty = true;
            spoken.focusMoved();
            return;
        }
        menuOpen = false;
        menu.target = 0;
        menuDirty = true;
        zone = Z_TOP;
        topIndex = 3 + menuButton;
        retarget(false);
        spoken.focusMoved();
    }

    private Runnable afterMenuGone;
    private int menuGoneFrames;
    private long menuGoneSince;

    /** Runs `go` once the menu is gone from the drawn screen: three frames without it (and at least 90 ms), or 700 ms at most. */
    private void whenMenuGone(final Runnable go) {
        afterMenuGone = go;
        menuGoneFrames = 0;
        menuGoneSince = SystemClock.uptimeMillis();
        postDelayed(() -> {
            if (afterMenuGone == go) {
                afterMenuGone = null;
                go.run();
            }
        }, 700);
    }

    /** The remote's Settings button: the same way in as the dropdown's row. The menu closes completely first, so the screen that is captured (and comes back) has none. */
    void openSettingsFromKey() {
        if (menuOpen || menu.value > 0.002f) {
            cardMenu = false;
            menuOpen = false;
            menu.target = 0;
            menuDirty = true;
            zone = Z_TOP;
            topIndex = 3 + menuButton;
            retarget(false);
        }
        menu.snap(0);
        invalidateAll();
        final HomeApps.Item it = HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Ajustes do Borealis", "Borealis settings", "Ajustes de Borealis"), "settings");
        whenMenuGone(() -> HomeApps.open(activity, it, this, 0, 0, 1, 1));
    }

    private void moveMenu(int dx, int dy) {
        menuAt = SystemClock.uptimeMillis();
        if (dy < 0 && menuIndex == 0) {
            closeMenu();
            click.move();
        } else if (dx != 0) {
            if (menuIndex < menuRows.size() && menuRows.get(menuIndex).choices != null) cycleChoice(menuRows.get(menuIndex), dx);
        } else if (dy != 0) {
            int next = Math.max(0, Math.min(menuRows.size() - 1, menuIndex + dy));
            if (next != menuIndex) {
                menuIndex = next;
                menuDirty = true;
                click.move();
                spoken.focusMoved();
            }
        }
    }

    private void activateMenu() {
        if (menuIndex >= menuRows.size()) return;
        MRow row = menuRows.get(menuIndex);
        HomeApps.Item it = row.item;
        RectF r = new RectF(menuRow);
        menuOpen = false;
        menu.target = 0;
        menuDirty = true;
        zone = Z_TOP;
        topIndex = 3 + menuButton;
        retarget(false);
        click.open();
        if (row.run != null) {   // the update, the news, the inputs list: they do their own thing
            row.run.run();
            return;
        }
        if (it.kind == HomeApps.SETTINGS) {   // the menu closes completely first, so the screen that is captured (and comes back) has no menu
            menu.snap(0);
            invalidateAll();
            whenMenuGone(() -> HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height())));
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    private void drawBars(Canvas c, float cx, float cy, int level, float alpha) {
        float bw = 5 * k, gap = 3 * k, total = 4 * bw + 3 * gap, x = cx - total / 2;
        for (int i = 0; i < 4; i++) {
            float h = (7 + 5 * i) * k;
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, alpha * (i < level ? 1f : 0.28f)));
            tmp.set(x + i * (bw + gap), cy + 13 * k - h, x + i * (bw + gap) + bw, cy + 13 * k);
            c.drawRoundRect(tmp, 1.5f * k, 1.5f * k, fill);
        }
    }

    /** Where row i of the open dropdown is (same measures as drawMenu); false while it can't be placed yet. */
    private boolean menuRowRect(int i, RectF out) {
        if (!menuOpen || i < 0 || i >= menuRows.size() || btnLabel == null || menuTag == null) return false;
        if (!cardMenu) {   // the Settings menu (layoutSystemMenu)
            if (i >= sysTop.length) return false;
            out.set(sysBox.left + 15 * k, sysBox.top + sysTop[i], sysBox.right - 15 * k, sysBox.top + sysTop[i] + sysRowH);
            return true;
        }
        float rowH = 62 * k, pad = 14 * k, wantW = 290 * k;
        for (MRow r : menuRows) {
            float need = 64 * k + btnLabel.measureText(r.label) + (r.tag == null ? 0 : 30 * k + menuTag.measureText(r.tag)) + 34 * k;
            wantW = Math.max(wantW, need + 2 * pad);
        }
        float w = Math.min(wantW, 620 * k);
        RectF btn = new RectF(cardMenu ? menuCard : topRects[3 + menuButton]);
        if (cardMenu) btn.offset(0, -menuTy);
        float left = btn.centerX() - w / 2;
        if (left + w > CONTENT_R * k + 24 * k) left = CONTENT_R * k + 24 * k - w;
        if (left < 10 * k) left = 10 * k;
        float top = cardMenu && menuAbove ? btn.top - 30 * k - (menuRows.size() * rowH + 2 * pad) : btn.bottom + 30 * k;
        out.set(left + pad, top + pad + i * rowH, left + w - pad, top + pad + i * rowH + rowH - 8 * k);
        return true;
    }

    private void drawMenu(Canvas c) {
        if (moving) drawMoveArrows(c);
        float m = Ui.clamp(menu.value);
        if (m <= 0.005f || menuRows.isEmpty() || btnLabel == null) return;
        if (cardMenu) drawCardMenu(c, m);
        else drawSystemMenu(c, m);
    }

    /** The Settings menu, as designed: the screen dims, the button stays lit above the dim, and the rows have colored icons. */
    private void drawSystemMenu(Canvas c, float m) {
        int n = menuRows.size();
        if (sysTop.length < n) return;
        fill.setShader(null);
        fill.setColor(Ui.alpha(Brand.INK, 0.45f * m));
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        if (menuButton < lightButtons.size()) {
            applyTone();
            drawLightButton(c, menuButton);
        }
        float pad = 15 * k, radius = 36 * k;
        c.save();
        c.translate(0, (1 - m) * -14 * k);
        tmp.set(sysBox);
        tmp.offset(0, 16 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.55f * m));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * 1.6f * k, shadow);
        fill.setColor(Ui.alpha(Brand.INK, 0.9f * m));
        c.drawRoundRect(sysBox, radius, radius, fill);
        menuBorder.setAlpha(Math.round(36 * m));
        tmp.set(sysBox.left + 0.75f * k, sysBox.top + 0.75f * k, sysBox.right - 0.75f * k, sysBox.bottom - 0.75f * k);
        c.drawRoundRect(tmp, radius, radius, menuBorder);
        for (int i = 0; i < n; i++) {
            MRow it = menuRows.get(i);
            float top = sysBox.top + sysTop[i];
            sysRow.set(sysBox.left + pad, top, sysBox.right - pad, top + sysRowH);
            if (it.sepBefore && i > 0) {   // the divider between quick access and the settings
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.14f * m));
                float ly = top - (sysTop[i] - sysTop[i - 1] - sysRowH) / 2;
                c.drawRect(sysRow.left + 24 * k, ly - 0.75f * k, sysRow.right - 24 * k, ly + 0.75f * k, fill);
            }
            boolean on = i == menuIndex;
            if (on) menuRow.set(sysRow);
            if (it.highlight && sysHi != null) {   // the update: a filled row, so it stands out from the rest
                fill.setShader(sysHi);
                fill.setAlpha(Math.round(255 * m * (on ? 0.85f : 0.6f)));
                c.drawRoundRect(sysRow, 27 * k, 27 * k, fill);
                fill.setShader(null);
                fill.setAlpha(255);
            } else if (on) {
                fill.setColor(Ui.alpha(ROW_FOCUS, m));
                c.drawRoundRect(sysRow, 27 * k, 27 * k, fill);   // corners as designed: 18 px of the 1280 design, not a full pill
            }
            if (on) ring(c, sysRow, 27 * k, m, 4.5f * k);
            float a = (it.dim ? 0.72f : 1f) * m;
            float cy = sysRow.centerY();
            drawRowIcon(c, it.symbol, sysRow.left + 43.5f * k, cy, 32 * k, Ui.alpha(it.accent, m));
            if (it.choices != null) {
                drawChoiceRow(c, it, on, sysRow, cy, m);
                continue;
            }
            Paint title = it.strong ? menuTitleBold : menuTitle;
            title.setColor(Ui.alpha(0xFFFFFFFF, a));
            float tx = sysRow.left + 84 * k;
            if (it.desc != null) {
                c.drawText(it.label, tx, cy - 4 * k, title);
                menuDesc.setColor(Ui.alpha(it.highlight ? 0xE6FFFFFF : 0xBDFFFFFF, a));
                c.drawText(it.desc, tx, cy + 23 * k, menuDesc);
            } else {
                c.drawText(it.label, tx, cy + Ui.halfCap(title), title);
            }
            float right = sysRow.right - 24 * k;
            if (it.bars >= 0) {
                drawSignal(c, right, cy, it.bars, m);
            } else if (it.dot) {
                fill.setColor(Ui.alpha(it.on ? GREEN : GRAY, m));
                c.drawCircle(right - 7.5f * k, cy, 7.5f * k, fill);
            } else if (it.right != null) {
                menuRight.setColor(Ui.alpha(0xFFFFFFFF, a));
                c.drawText(it.right, right - menuRight.measureText(it.right), cy + Ui.halfCap(menuRight), menuRight);
            } else if (it.strong) {
                Symbols.draw(getContext(), c, "chevron_right", right - 15 * k, cy, 26 * k, Ui.alpha(0xFFFFFFFF, m));
            }
        }
        c.restore();
    }

    /**
     * The "Entradas" row: the name at the left; at the right "‹ HDMI 1 ›" with its state under it (a dot before the
     * state: green connected, gray not). The arrows light up when the row is focused: Left/Right change the input.
     */
    private void drawChoiceRow(Canvas c, MRow it, boolean on, RectF row, float cy, float m) {
        Choice ch = it.choices.get(it.choice);
        menuTitle.setColor(Ui.alpha(0xFFFFFFFF, m));
        c.drawText(it.label, row.left + 84 * k, cy + Ui.halfCap(menuTitle), menuTitle);
        float bw = 250 * k, bx = row.right - 24 * k - bw / 2;
        menuTitle.setColor(Ui.alpha(0xFFFFFFFF, (ch.dim ? 0.8f : 1f) * m));
        c.drawText(ch.label, bx - menuTitle.measureText(ch.label) / 2, cy - 4 * k, menuTitle);
        menuTitle.setColor(0xFFFFFFFF);
        if (it.choices.size() > 1) {
            int arrow = Ui.alpha(0xFFFFFFFF, m * (on ? 1f : 0.4f));
            drawChevron(c, bx - bw / 2 + 10 * k, cy - 13 * k, 8 * k, -1, arrow);
            drawChevron(c, bx + bw / 2 - 10 * k, cy - 13 * k, 8 * k, 1, arrow);
        }
        float dotW = ch.dot ? 24 * k : 0;
        float x = bx - (dotW + menuDesc.measureText(ch.desc)) / 2;
        if (ch.dot) {
            fill.setColor(Ui.alpha(ch.on ? GREEN : GRAY, m));
            c.drawCircle(x + 7.5f * k, cy + 15 * k, 7.5f * k, fill);
        }
        menuDesc.setColor(Ui.alpha(0xBDFFFFFF, m));
        c.drawText(ch.desc, x + dotW, cy + 23 * k, menuDesc);
    }

    /** A small "‹" (dir -1) or "›" (dir 1) centered at (cx, cy), `s` tall each way. */
    private void drawChevron(Canvas c, float cx, float cy, float s, int dir, int color) {
        float old = iconStroke.getStrokeWidth();
        iconStroke.setStrokeWidth(2.6f * k);
        iconStroke.setStrokeJoin(Paint.Join.ROUND);
        iconStroke.setColor(color);
        iconPath.reset();
        iconPath.moveTo(cx - dir * s * 0.4f, cy - s);
        iconPath.lineTo(cx + dir * s * 0.4f, cy);
        iconPath.lineTo(cx - dir * s * 0.4f, cy + s);
        c.drawPath(iconPath, iconStroke);
        iconStroke.setStrokeWidth(old);
    }

    /**
     * A row's icon. The ones named "d_..." are drawn from the design's line icons (24-unit grid, 1.7 stroke, round ends);
     * the rest are the Material symbols.
     */
    private void drawRowIcon(Canvas c, String name, float cx, float cy, float size, int color) {
        if (!name.startsWith("d_")) {
            Symbols.draw(getContext(), c, name, cx, cy, size, color);
            return;
        }
        if (name.equals("d_borealis")) {
            Logo.drawFlat(c, cx, cy, size * 0.95f, color);
            return;
        }
        float u = size / 24f, old = iconStroke.getStrokeWidth();
        iconStroke.setStrokeWidth(1.7f * u);
        iconStroke.setStrokeJoin(Paint.Join.ROUND);
        iconStroke.setColor(color);
        if (name.equals("d_tv")) {
            iconRect.set(cx - 9 * u, cy - 7 * u, cx + 9 * u, cy + 5 * u);
            c.drawRoundRect(iconRect, 2 * u, 2 * u, iconStroke);
            c.drawLine(cx - 4 * u, cy + 9 * u, cx + 4 * u, cy + 9 * u, iconStroke);
            c.drawLine(cx, cy + 5 * u, cx, cy + 9 * u, iconStroke);
        } else if (name.equals("d_hdmi")) {
            iconRect.set(cx - 9 * u, cy - 5 * u, cx + 9 * u, cy + 5 * u);
            c.drawRoundRect(iconRect, 2 * u, 2 * u, iconStroke);
            c.drawLine(cx - 5 * u, cy + 5 * u, cx - 5 * u, cy + 7 * u, iconStroke);
            c.drawLine(cx + 5 * u, cy + 5 * u, cx + 5 * u, cy + 7 * u, iconStroke);
            c.drawLine(cx - 5 * u, cy - 1 * u, cx + 5 * u, cy - 1 * u, iconStroke);
        } else if (name.equals("d_av")) {   // the three RCA plugs
            for (int i = 0; i < 3; i++) {
                float x = cx + (-6.5f + 6.5f * i) * u;
                c.drawCircle(x, cy, 2.9f * u, iconStroke);
                iconStroke.setStyle(Paint.Style.FILL);
                c.drawCircle(x, cy, 0.9f * u, iconStroke);
                iconStroke.setStyle(Paint.Style.STROKE);
            }
            c.drawLine(cx - 9 * u, cy + 6.5f * u, cx + 9 * u, cy + 6.5f * u, iconStroke);
        } else {   // d_vga: the D-shaped plug with its two rows of pins
            iconPath.reset();
            iconPath.moveTo(cx - 9 * u, cy - 5 * u);
            iconPath.lineTo(cx + 9 * u, cy - 5 * u);
            iconPath.lineTo(cx + 7 * u, cy + 5 * u);
            iconPath.lineTo(cx - 7 * u, cy + 5 * u);
            iconPath.close();
            c.drawPath(iconPath, iconStroke);
            iconStroke.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 5; i++) c.drawCircle(cx + (-5 + 2.5f * i) * u, cy - 1.6f * u, 0.95f * u, iconStroke);
            for (int i = 0; i < 4; i++) c.drawCircle(cx + (-3.75f + 2.5f * i) * u, cy + 1.7f * u, 0.95f * u, iconStroke);
            iconStroke.setStyle(Paint.Style.STROKE);
        }
        iconStroke.setStrokeWidth(old);
    }

    /** Wi-Fi signal (design: 4 bars, the last ones fainter when the signal is weak), its right edge at `right`. */
    private void drawSignal(Canvas c, float right, float cy, int level, float m) {
        float x0 = right - 42 * k, base = cy + 15 * k;
        for (int i = 0; i < 4; i++) {
            float bh = (10.5f + 7.5f * i) * k, bx = x0 + (1.5f + 10.5f * i) * k;
            fill.setColor(Ui.alpha(0xFFFFFFFF, m * (i < level ? 1f : 0.3f)));
            tmp.set(bx, base - bh, bx + 6 * k, base);
            c.drawRoundRect(tmp, 1.5f * k, 1.5f * k, fill);
        }
    }

    /** The little menu under an app card (move, remove, hide). */
    private void drawCardMenu(Canvas c, float m) {
        int n = menuRows.size();
        float rowH = 62 * k, pad = 14 * k;
        float wantW = 290 * k;
        for (MRow r : menuRows) {
            float need = 64 * k + btnLabel.measureText(r.label) + (r.tag == null ? 0 : 30 * k + menuTag.measureText(r.tag)) + 34 * k;
            wantW = Math.max(wantW, need + 2 * pad);
        }
        float w = Math.min(wantW, 620 * k), h = n * rowH + 2 * pad;
        RectF btn = new RectF(cardMenu ? menuCard : topRects[3 + menuButton]);
        if (cardMenu) btn.offset(0, -menuTy);
        float left = btn.centerX() - w / 2;
        if (left + w > CONTENT_R * k + 24 * k) left = CONTENT_R * k + 24 * k - w;
        if (left < 10 * k) left = 10 * k;
        float top = cardMenu && menuAbove ? btn.top - 30 * k - h : btn.bottom + 30 * k;
        c.save();
        c.translate(0, (1 - m) * -14 * k);
        RectF box = new RectF(left, top, left + w, top + h);
        tmp.set(box);
        tmp.offset(0, 10 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.35f * m));
        shadows.draw(c, tmp, 30 * k, SHADOW_BLUR * k, shadow);
        fill.setShader(null);
        fill.setColor(Ui.alpha(PILL, 0.96f * m));
        c.drawRoundRect(box, 30 * k, 30 * k, fill);
        Path arrow = new Path();
        float ax = Math.max(left + 44 * k, Math.min(left + w - 44 * k, btn.centerX()));
        if (cardMenu && menuAbove) {   // hanging above the card: the arrow points down at it
            arrow.moveTo(ax - 13 * k, top + h - 1);
            arrow.lineTo(ax, top + h + 15 * k);
            arrow.lineTo(ax + 13 * k, top + h - 1);
        } else {
            arrow.moveTo(ax - 13 * k, top + 1);
            arrow.lineTo(ax, top - 15 * k);
            arrow.lineTo(ax + 13 * k, top + 1);
        }
        arrow.close();
        c.drawPath(arrow, fill);
        for (int i = 0; i < n; i++) {
            MRow it = menuRows.get(i);
            RectF row = new RectF(left + pad, top + pad + i * rowH, left + w - pad, top + pad + i * rowH + rowH - 8 * k);
            boolean on = i == menuIndex;
            if (on) {
                menuRow.set(row);
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.1f * m));
                c.drawRoundRect(row, row.height() / 2, row.height() / 2, fill);
                ring(c, row, row.height() / 2, m, 3.5f * k);
            }
            float a = (on ? 1f : 0.72f) * (it.dim ? 0.6f : 1f) * m;
            if (it.bars >= 0) drawBars(c, row.left + 32 * k, row.centerY(), it.bars, a);
            else Symbols.draw(getContext(), c, it.symbol, row.left + 32 * k, row.centerY(), 26 * k, Ui.alpha(0xFFFFFFFF, a));
            btnLabel.setColor(Ui.alpha(0xFFFFFFFF, a));
            c.drawText(it.label, row.left + 64 * k, row.centerY() + Ui.halfCap(btnLabel), btnLabel);
            if (it.tag != null) {
                menuTag.setColor(Ui.alpha(0xFFFFFFFF, a * 0.8f));
                float tw = menuTag.measureText(it.tag), tx = row.right - 24 * k - tw;
                c.drawText(it.tag, tx, row.centerY() + Ui.halfCap(menuTag), menuTag);
                if (it.dot) {
                    float dx = tx - 16 * k, dr = 6 * k;
                    if (it.on) {
                        fill.setColor(Ui.alpha(LILY, m));
                        c.drawCircle(dx, row.centerY(), dr, fill);
                    } else {
                        iconStroke.setColor(Ui.alpha(0xFFFFFFFF, 0.45f * m));
                        c.drawCircle(dx, row.centerY(), dr - 1 * k, iconStroke);
                        iconStroke.setColor(0xFFFFFFFF);
                    }
                }
            }
        }
        btnLabel.setColor(0xFFFFFFFF);
        c.restore();
    }

    /** The play button on the focused card: "Watch now" on the wide one, only the icon on a compact one. */
    private void drawPlay(Canvas c, RectF card, float expanded, float f, float vis, String label) {
        float h = 50 * k, margin = 22 * k;
        boolean wide = expanded > 0.5f;
        float w = wide ? 22 * k + 26 * k + 10 * k + btnLabel.measureText(label) + 24 * k : h;
        ringRect.set(card.right - margin - w, card.bottom - margin - h, card.right - margin, card.bottom - margin);
        fill.setShader(null);
        fill.setColor(Ui.alpha(PILL, 0.9f * vis));
        c.drawRoundRect(ringRect, h / 2, h / 2, fill);
        pillRect.set(ringRect);
        ring(c, pillRect, h / 2, f, 3 * k);
        Symbols.draw(getContext(), c, "play", wide ? pillRect.left + 22 * k + 13 * k : pillRect.centerX(), pillRect.centerY(), 26 * k, Ui.alpha(0xFFFFFFFF, vis));
        if (wide) {
            btnLabel.setAlpha(Math.round(255 * vis));
            c.drawText(label, pillRect.left + 22 * k + 26 * k + 10 * k, pillRect.centerY() + Ui.halfCap(btnLabel), btnLabel);
            btnLabel.setAlpha(255);
        }
    }


    /** Focus ring: a lily → peach → melon gradient stroke inside `r`, with corner radius `radius`. */
    private void ring(Canvas c, RectF r, float radius, float alpha, float width) {
        if (alpha <= 0.01f) return;
        float half = width / 2;
        ringRect.set(r.left + half, r.top + half, r.right - half, r.bottom - half);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(width);
        ringPaint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[] {LILY, PEACH, MELON}, null, Shader.TileMode.CLAMP));
        ringPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        float rr = Math.max(0, radius - half);
        c.drawRoundRect(ringRect, rr, rr, ringPaint);
        ringPaint.setShader(null);
    }

    /**
     * Time, date and weather in a soft pill (white at 8%), to the left of the corner buttons and level with them.
     * Time bold, day medium, temperature semibold with its scale ("16°C"). If the greeting is long (Portuguese, a
     * long name) and the pill would touch it, the date is left out and the time and the weather stay.
     */
    private void drawInfoPill(Canvas c) {
        if (infoTime == null || lTitle == null) return;
        String time = clockText;
        String date = Ui.dateShort(System.currentTimeMillis());
        HomeData.Weather wx = data.weather;
        boolean hasWx = !Double.isNaN(wx.temp) && Prefs.weather();
        String temp = hasWx ? HomeData.degreesScale(wx.temp) : "";
        String wxIcon = hasWx ? Symbols.weather(wx.code, wx.isDay) : null;
        float icon = 26 * k, gapIcon = 8 * k, gap = 21 * k, padX = 33 * k, ph = 72 * k;
        if (!time.equals(pillSrc)) {   // "10:38 PM": the AM/PM is drawn small after the digits (cached: it changes once a minute)
            pillSrc = time;
            boolean ap = time.endsWith(" AM") || time.endsWith(" PM");
            pillMain = ap ? time.substring(0, time.length() - 3) : time;
            pillSuffix = ap ? time.substring(time.length() - 2) : null;
        }
        float mainW = infoTime.measureText(pillMain);
        float tw = mainW + (pillSuffix == null ? 0 : 6 * k + infoAmPm.measureText(pillSuffix)), dw = infoDate.measureText(date);
        float sw = hasWx ? Symbols.width(getContext(), wxIcon, icon) : 0;
        float wxw = hasWx ? sw + gapIcon + infoTemp.measureText(temp) : 0;
        int n = lightButtons.size();
        float buttonsLeft = CONTENT_R * k - n * L_BUTTON * k - Math.max(0, n - 1) * L_BUTTON_GAP * k;
        float right = buttonsLeft - 24 * k;   // 16 px of the 1280 design
        String ask = Ui.tr("O que você quer assistir hoje?", "What do you want to watch today?", "\u00BFQu\u00E9 quieres ver hoy?");
        float greetEnd = MARGIN * k + (Avatars.current().isEmpty() ? 0 : 114 * k) + lTitle.measureText(ask);
        boolean showDate = true;
        float total = tw + gap + dw + (hasWx ? gap + wxw : 0);
        if (right - total - 2 * padX < greetEnd + 40 * k) {
            showDate = false;
            total = tw + (hasWx ? gap + wxw : 0);
        }
        float cy = L_BUTTON_CY * k;
        pillRect.set(right - total - 2 * padX, cy - ph / 2, right, cy + ph / 2);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f));
        c.drawRoundRect(pillRect, ph / 2, ph / 2, fill);
        float x = pillRect.left + padX;
        c.drawText(pillMain, x, cy + Ui.halfCap(infoTime), infoTime);
        if (pillSuffix != null) c.drawText(pillSuffix, x + mainW + 6 * k, cy + Ui.halfCap(infoTime), infoAmPm);
        x += tw + gap;
        if (showDate) {
            c.drawText(date, x, cy + Ui.halfCap(infoDate), infoDate);
            x += dw + gap;
        }
        if (hasWx) {
            Symbols.draw(getContext(), c, wxIcon, x + sw / 2, cy, icon, 0xFFFFFFFF);
            x += sw + gapIcon;
            c.drawText(temp, x, cy + Ui.halfCap(infoTemp), infoTemp);
        }
    }

    /** The System button's icon (two sliders), drawn with lines and circles on a 24-unit grid: Borealis' own, no image file. */
    private void drawSliders(Canvas c, float cx, float cy, float size) {
        float u = size / 24f;
        float old = iconStroke.getStrokeWidth();
        iconStroke.setStrokeWidth(1.8f * u);
        iconStroke.setColor(0xFFFFFFFF);
        c.drawLine(cx - 8 * u, cy - 5 * u, cx + 1 * u, cy - 5 * u, iconStroke);
        c.drawLine(cx + 5 * u, cy - 5 * u, cx + 8 * u, cy - 5 * u, iconStroke);
        c.drawLine(cx - 8 * u, cy + 5 * u, cx - 5 * u, cy + 5 * u, iconStroke);
        c.drawLine(cx - 1 * u, cy + 5 * u, cx + 8 * u, cy + 5 * u, iconStroke);
        c.drawCircle(cx + 3 * u, cy - 5 * u, 2 * u, iconStroke);
        c.drawCircle(cx - 3 * u, cy + 5 * u, 2 * u, iconStroke);
        iconStroke.setStrokeWidth(old);
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0 || shelf.isEmpty()) return false;
                if (playerShown()) {
                    zone = Z_PLAYER;
                } else if (cards.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = topHome();
                } else {
                    zone = Z_CARDS;
                    cardIndex = Math.min(cardIndex, cards.size() - 1);   // goes back to the card we left
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int count = cards.size();
                    if (count == 0) return true;
                    int next = (cardIndex + dx + count) % count;   // endless, like the shelf: past the last comes the first
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = topHome();     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else if (playerShown()) {
                    zone = Z_PLAYER;
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = Math.min(shelfIndex, shelf.size() - 1);   // goes back to the app we left
                }
                break;
            case Z_PLAYER:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(2, playerIndex + dx));
                    if (next == playerIndex) return true;
                    playerIndex = next;
                } else if (dy < 0) {
                    if (cards.isEmpty()) {
                        zone = Z_TOP;
                        topIndex = topHome();
                    } else {
                        zone = Z_CARDS;
                        cardIndex = Math.min(cardIndex, cards.size() - 1);
                    }
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = Math.min(shelfIndex, shelf.size() - 1);
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(tabsVisible() ? 0 : 3, Math.min(7, topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;   // focus only moves the ring; OK selects the tab
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = playerShown() ? Z_PLAYER : Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = Math.min(cardIndex, cards.size() - 1);
                    }
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
