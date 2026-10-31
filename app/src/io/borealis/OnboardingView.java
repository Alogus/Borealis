package io.borealis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The welcome: a big dark, translucent panel over the wallpaper in use (a separate, drifting view: OnboardingBackdrop),
 * with small progress dots. Steps: language (with the greeting in three languages) → name and avatar → what the home
 * screen shows (continue watching or recommendations, with a sample of each) → the notification access that "Continue
 * watching" needs (only if it was chosen; a switch) → done. It can be skipped (after a warning) and repeated from Settings.
 * Everything is drawn on one canvas in a 1920 × 1080 space, like the settings, and read by TalkBack (Spoken).
 */
final class OnboardingView extends View {
    /** `welcome` is true when the person finished it (the opening for new people plays), false when they skipped it. */
    interface Done { void done(boolean welcome); }

    private static final int S_WELCOME = 0, S_LANG = 1, S_NAME = 2, S_HOME = 3, S_PERM = 4, S_DONE = 5, S_SHELF = 6;
    private static boolean langChosen;    // nothing is selected on the language screen until the person picks
    private static boolean homeChosen;   // nothing is selected on "what do you want to see first" until the person picks
    private static int savedStep;   // the step's id; survives the activity being rebuilt (a language change, a system screen)

    // MARK: - Whether to show it

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("onboarding", Context.MODE_PRIVATE);
    }

    /** True until the person finishes or skips it (and again when they ask for it from Settings). */
    static boolean pending(Context c) {
        return !prefs(c).getBoolean("done", false);
    }

    /** Settings > Preferences > Replay the welcome: the next time the home screen is up it shows again. */
    static void repeat(Context c) {
        savedStep = S_WELCOME;
        homeChosen = false;
        langChosen = false;
        avatarRolled = false;
        prefs(c).edit().putBoolean("done", false).apply();
    }

    private static void markDone(Context c) {
        savedStep = S_WELCOME;
        prefs(c).edit().putBoolean("done", true).apply();
    }

    // MARK: - Elements

    private static final int ROW = 0, LANGUAGE = 1, AVATAR = 2, OPTION = 3, SWITCH = 4, BTN = 5, PRIMARY = 6, LINK = 7, EDIT = 8;
    private static final int BUTTON_ROW = 9, LINK_ROW = 10;

    private static final class El {
        int kind, row, idx;
        String label, value, spoken;
        boolean selected, enabled = true;
        final RectF r = new RectF();
        Runnable act;
    }

    // Design space (1920 x 1080): the panel and its parts.
    private static final float MX = 240, MY = 100, MW = 1440, MH = 880, RADIUS = 28;
    private static final float BUTTONS_Y = MY + 740, BUTTON_H = 62, LINK_Y = MY + 818, LINK_H = 44;
    private static final String[] LANGS = {"es", "en", "pt", "auto"};

    private final Done done;
    private final ShelfDemo shelfDemo = new ShelfDemo();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Click click;
    private final Spoken spoken;
    private final List<El> els = new ArrayList<>();
    private final Spring[] pop = new Spring[40];
    private final Spring enter = new Spring(0.34f, 0.9f, 1), appear = new Spring(0.45f, 0.9f, 0);
    private int step, focus, w, h, enterDir = 1;
    private boolean confirm;    // a warning panel: "Skip?" or (with warnPerm) "No access, no Continue watching"
    private boolean warnPerm, pickingAvatar, backDown, built, permWasOn;
    private float k = 1;
    private long lastNanos, shownAt = SystemClock.uptimeMillis();
    private String intro = "";   // the step's title and text, said with the first item until a key is pressed
    private boolean showFocus = true;   // on the name step the focus stays hidden until the first key
    private int lastCyc = -1, lastPv = -1;
    private float tf = 1;   // alpha of the texts that change language
    private static boolean avatarRolled;
    private boolean cycling = true;   // the greeting keeps changing language until one is picked

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint title, greet, body, label, value, small, button, stepName, link, option, hint;
    private float paintK;
    private final Map<String, StaticLayout> layouts = new HashMap<>();
    private final RectF tmp = new RectF(), box = new RectF(), tmp2 = new RectF();
    private final Path clip = new Path();
    private final Runnable tick = this::invalidate;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow()) return;
            if (playbackOn()) permWasOn = true;
            built = false;   // a permission may have changed in the system screen
            invalidate();
            handler.postDelayed(this, 800);
        }
    };

    OnboardingView(Context c, Done done) {
        super(c);
        this.done = done;
        click = new Click(c);
        step = indexOf(savedStep);
        cycling = Prefs.language().equals("auto");
        for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
        setFocusable(true);
        spoken = new Spoken(this, new Spoken.Source() {
            @Override public void items(List<Integer> out) { ensureLayout(); for (int i = 1; i <= els.size(); i++) out.add(i); }
            @Override public String text(int id) {
                ensureLayout();
                if (id < 1 || id > els.size()) return "";
                String t = els.get(id - 1).spoken;
                return id == focus + 1 && !intro.isEmpty() ? intro + " " + t : t;
            }
            @Override public boolean bounds(int id, Rect out) {
                ensureLayout();
                if (id < 1 || id > els.size()) return false;
                els.get(id - 1).r.round(out);
                return out.width() > 0 && out.height() > 0;
            }
            @Override public int focused() { return focus + 1; }
            @Override public void focus(int id) { if (id >= 1 && id <= els.size()) { focus = id - 1; invalidate(); } }
            @Override public void click(int id) { if (id >= 1 && id <= els.size()) { focus = id - 1; activate(); } }
        });
        spoken.setTitle(() -> confirm ? T("Borealis, pular as boas-vindas?", "Borealis, skip the welcome?", "Borealis, ¿saltar la bienvenida?")
                : T("Borealis, boas-vindas. Passo ", "Borealis, welcome. Step ", "Borealis, bienvenida. Paso ") + (step + 1)
                + T(" de ", " of ") + flow().length + ": " + stepName(stepId()) + ".");
        enterStep(0);
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.postDelayed(poll, 800);
        spoken.readAgain();
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        click.release();
        super.onDetachedFromWindow();
    }

    /** The home screen came back (from a system screen): the permission may have changed. */
    void resume() {
        if (playbackOn()) permWasOn = true;
        built = false;
        invalidate();
        spoken.focusMoved();
    }

    // MARK: - The flow

    /** The steps in order: the notification access is only asked when "Continue watching" was chosen. */
    private int[] flow() {
        return !homeChosen || Prefs.cardsMode().equals("recent") ? new int[] {S_WELCOME, S_LANG, S_NAME, S_HOME, S_PERM, S_SHELF, S_DONE} : new int[] {S_WELCOME, S_LANG, S_NAME, S_HOME, S_SHELF, S_DONE};
    }

    private int indexOf(int id) {
        int[] f = flow();
        for (int i = 0; i < f.length; i++) if (f[i] == id) return i;
        return 0;
    }

    private int stepId() {
        int[] f = flow();
        return f[Math.max(0, Math.min(f.length - 1, step))];
    }

    private String stepName(int id) {
        switch (id) {
            case S_WELCOME: return T("Boas-vindas", "Welcome", "Bienvenida");
            case S_LANG: return T("Idioma", "Language", "Idioma");
            case S_NAME: return T("Nome", "Name", "Nombre");
            case S_HOME: return T("Tela inicial", "Home screen", "Pantalla de inicio");
            case S_PERM: return T("Permissão", "Permission", "Permiso");
            case S_SHELF: return T("Estante", "Shelf", "Estante");
            default: return T("Pronto", "Done", "Listo");
        }
    }

    // MARK: - Texts

    private static final long PERIOD = 2800, FADE = 500;

    /** Which language the rotating texts show now: 0 Spanish, 1 English, 2 Portuguese (it changes in the middle of each fade). */
    private int cycIndex() {
        int first = Ui.es ? 0 : Ui.en ? 1 : 2;
        long t = SystemClock.uptimeMillis() - shownAt, phase = t % PERIOD;
        return (first + (int) (t / PERIOD) + (phase >= PERIOD - FADE / 2 ? 1 : 0)) % 3;
    }

    private boolean rotating() {
        return cycling && !confirm && Prefs.language().equals("auto") && stepId() == S_WELCOME;
    }

    /** Ui.tr, except that on the first two screens, before a language is picked, it follows the rotation. */
    /** On the language step the texts follow the card that has the focus (not the click): 0 es, 1 en, 2 pt, -1 the TV's own language. */
    private int previewIndex() {
        if (stepId() != S_LANG || confirm || focus < 0 || focus >= els.size() || els.get(focus).kind != LANGUAGE) return -1;
        int i = els.get(focus).idx;
        return i < 3 ? i : -1;
    }

    private String T(String pt, String en, String es) {
        int pv = previewIndex();
        if (pv >= 0) return pv == 0 ? es : pv == 1 ? en : pt;
        if (!rotating()) return Ui.tr(pt, en, es);
        int i = cycIndex();
        return i == 0 ? es : i == 1 ? en : pt;
    }

    private String T(String pt, String en) {
        int pv = previewIndex();
        if (pv >= 0) return pv == 2 ? pt : en;
        if (!rotating()) return Ui.tr(pt, en);
        return cycIndex() == 2 ? pt : en;
    }

    private String stepTitle() {
        if (confirm && warnPerm) return T("Sem este acesso não há “Continuar assistindo”", "Without this access there is no “Continue watching”", "Sin este acceso no hay “Continuar viendo”");
        if (confirm) return T("Tem certeza de que quer pular?", "Are you sure you want to skip?", "¿Seguro que quieres saltar?");
        switch (stepId()) {
            case S_WELCOME: return T("Damos as boas-vindas a você", "We welcome you", "Te damos la bienvenida");
            case S_LANG: return T("Em que idioma você se sente mais à vontade?", "Which language feels most like home?", "¿En qué idioma te sientes más cómodo?");
            case S_NAME: return pickingAvatar ? T("Escolha seu avatar", "Choose your avatar", "Elige tu avatar")
                    : T("Como você quer que eu te chame?", "What should we call you?", "¿Cómo quieres que te llame?");
            case S_HOME: return T("O que você quer ver primeiro?", "What do you want to see first?", "¿Qué quieres ver primero?");
            case S_PERM: return T("Para continuar de onde você parou", "To pick up where you left off", "Para continuar donde lo dejaste");
            case S_SHELF: return T("Personalize o seu estante", "Make the shelf yours", "Personaliza tu estante");
            default: {
                String n = Prefs.name();
                return n.isEmpty() ? T("Tudo pronto", "All set", "Todo listo") : T("Tudo pronto, ", "All set, ", "Todo listo, ") + n;
            }
        }
    }

    private String stepBody() {
        if (confirm && warnPerm) return T("Sem o acesso às notificações o Borealis não consegue mostrar o que você deixou pela metade. Se continuar, ativamos o modo Recomendações; você pode mudar quando quiser em Configurações.",
                "Without notification access Borealis can't show what you left half-watched. If you go on, we'll turn on Recommendations mode; you can change it any time in Settings.",
                "Sin el acceso a las notificaciones Borealis no puede mostrarte lo que dejaste a medias. Si sigues, activamos el modo Recomendaciones; puedes cambiarlo cuando quieras en Configuración.");
        if (confirm) return T("Você poderá usar a sua TV normalmente, mas a tela inicial ficará com a configuração padrão e “Continuar assistindo” só funcionará quando você ativá-lo em Configurações. "
                        + "Dá para ver estas boas-vindas de novo em Configurações > Preferências.",
                "You can use your TV as usual, but the home screen will keep its default setup and “Continue watching” will only work once you turn it on in Settings. "
                        + "You can see this welcome again in Settings > Preferences.",
                "Podrás usar tu TV con normalidad, pero la pantalla de inicio quedará con la configuración por defecto y “Continuar viendo” solo funcionará cuando lo actives en Configuración. "
                        + "Puedes volver a ver esta bienvenida en Configuración > Preferencias.");
        switch (stepId()) {
            case S_WELCOME: return T("Um launcher leve e bonito para a sua TV.", "A light, beautiful launcher for your TV.", "Un launcher ligero y bonito para tu TV.");
            case S_LANG: return T("Escolha o idioma do Borealis. Dá para mudar depois em Configurações.", "Choose Borealis's language. You can change it later in Settings.", "Elige el idioma de Borealis. Puedes cambiarlo después en Configuración.");
            case S_NAME: return pickingAvatar ? T("Escolha o que mais combina com você.", "Pick the one that suits you.", "Elige el que más vaya contigo.")
                    : T("Você pode mudar isso depois em Configurações.", "You can change this later in Settings.", "Puedes cambiarlo después en Configuración.");
            case S_HOME: return T("Escolha como será a sua tela inicial. Dá para mudar quando quiser em Configurações.",
                    "Choose how your home screen will look. You can change it any time in Settings.",
                    "Elige cómo se verá tu pantalla de inicio. Puedes cambiarlo cuando quieras en Configuración.");
            case S_PERM: return T("O Borealis precisa saber qual app você está usando para mostrar o que ficou pela metade. Ele não vê o conteúdo e nada sai da sua TV.",
                    "Borealis needs to know which app you are using to show what you left half-watched. It doesn't see the content and nothing leaves your TV.",
                    "Borealis necesita saber qué app estás usando para mostrarte lo que dejaste a medias. No ve el contenido y nada sale de tu TV.");
            case S_SHELF: return T("Segure OK sobre um app do estante para movê-lo, removê-lo ou ocultá-lo. Os apps ocultos ficam no fim de Todos os apps, e de lá você pode mostrá-los de novo.",
                    "Hold OK on a shelf app to move it, remove it or hide it. Hidden apps wait at the end of All apps, and you can bring them back from there.",
                    "Mantén OK sobre una app del estante para moverla, quitarla u ocultarla. Las apps ocultas quedan al final de Todas las apps y desde ahí puedes volver a mostrarlas.");
            default: return T("Você pode ajustar o que quiser em Configurações, como o botão de ajustes do controle e o som de abertura.",
                    "You can adjust anything in Settings, like the remote's settings button and the startup sound.",
                    "Puedes ajustar lo que quieras en Configuración, como el botón de ajustes del control y el sonido de inicio.");
        }
    }

    private String languageLabel(int i) {
        return i == 0 ? "Español" : i == 1 ? "English" : i == 2 ? "Português" : T("Automático", "Automatic", "Automático");
    }

    private String languageName() {
        String l = Prefs.language();
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(l)) return languageLabel(i);
        return languageLabel(3);
    }

    private String nameValue() {
        return Prefs.name().isEmpty() ? T("Toque OK para escrever", "Press OK to type", "Pulsa OK para escribir") : Prefs.name();
    }

    private boolean playbackOn() {
        return Watched.permitted(getContext());
    }

    private String yes() { return T("Ativado", "On", "Activado"); }

    private String no() { return T("Desativado", "Off", "Sin activar"); }

    private String modeName(boolean recent) {
        return recent ? T("Continuar assistindo", "Continue watching", "Continuar viendo") : T("Recomendações", "Recommendations", "Recomendaciones");
    }

    private static final String[] AV_PT = {"Astronauta", "Astronauta", "Astronauta", "Foguete", "Satélite", "Óvni", "Lua", "Vênus", "Terra", "Marte", "Júpiter", "Saturno", "Urano", "Alienígena", "Alienígena", "Galáxia", "Estrela branca", "Estrela lilás", "Estrela pêssego"};
    private static final String[] AV_EN = {"Astronaut", "Astronaut", "Astronaut", "Rocket", "Satellite", "UFO", "Moon", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Alien", "Alien", "Galaxy", "White star", "Lilac star", "Peach star"};
    private static final String[] AV_ES = {"Astronauta", "Astronauta", "Astronauta", "Cohete", "Satélite", "Ovni", "Luna", "Venus", "Tierra", "Marte", "Júpiter", "Saturno", "Urano", "Alienígena", "Alienígena", "Galaxia", "Estrella blanca", "Estrella lila", "Estrella durazno"};

    private String avatarName(int i) {
        String n = T(AV_PT[i], AV_EN[i], AV_ES[i]);
        return i < 3 ? n + " " + (i + 1) : i == 13 || i == 14 ? n + " " + (i - 12) : n;
    }

    // MARK: - Layout

    private void ensureLayout() {
        if (w == 0) {
            w = getWidth();
            h = getHeight();
            k = w > 0 ? w / 1920f : 1;
        }
        if (built && !els.isEmpty()) return;   // rebuilt only when something changed (dirty = built false)
        built = true;
        els.clear();
        float cx = MX + MW / 2;
        if (confirm) {
            El stay = button(PRIMARY, warnPerm ? T("Ativar o acesso", "Turn on access", "Activar el acceso") : T("Continuar configurando", "Keep setting up", "Seguir configurando"), 360,
                    warnPerm ? this::warnActivate : this::cancelSkip);
            El leave = button(BTN, warnPerm ? T("Seguir com Recomendações", "Go on with Recommendations", "Seguir con Recomendaciones") : T("Pular mesmo assim", "Skip anyway", "Saltar de todos modos"), 360,
                    warnPerm ? this::warnRecommendations : this::skipNow);
            placeButtons(leave, stay);
            if (focus >= els.size()) focus = els.size() - 1;
            return;
        }
        int id = stepId();
        boolean picking = id == S_NAME && pickingAvatar;
        if (id == S_LANG) {
            float cw = 280, gap = 24, x = cx - (4 * cw + 3 * gap) / 2;
            String cur = Prefs.language();
            for (int p = 0; p < 4; p++) {   // the default (automatic) first
                int i = (p + 3) % 4;
                El e = new El();
                e.kind = LANGUAGE;
                e.row = 0;
                e.idx = i;
                e.label = languageLabel(i);
                e.selected = langChosen && LANGS[i].equals(cur);
                e.spoken = e.label + (i == 3 ? ", " + T("o idioma da TV", "the TV's language", "el idioma de la TV") : "")
                        + (e.selected ? ", " + T("selecionado", "selected", "seleccionado") : "");
                final int which = i;
                e.act = () -> pickLanguage(which);
                e.r.set((x + p * (cw + gap)) * k, (MY + 340) * k, (x + p * (cw + gap) + cw) * k, (MY + 590) * k);
                els.add(e);
            }
        } else if (picking) {
            float d = 104, gap = 18, x0 = cx - (10 * d + 9 * gap) / 2;
            String cur = Avatars.current();
            for (int i = 0; i < Avatars.IDS.length; i++) {
                El e = new El();
                e.kind = AVATAR;
                e.row = i / 10;
                e.idx = i;
                e.label = Avatars.IDS[i];
                e.selected = Avatars.IDS[i].equals(cur);
                e.spoken = T("Avatar: ", "Avatar: ", "Avatar: ") + avatarName(i) + (e.selected ? ", " + T("selecionado", "selected", "seleccionado") : "");
                final int which = i;
                e.act = () -> pickAvatar(which);
                float x = x0 + (i % 10) * (d + gap), y = MY + 270 + (i / 10) * (d + gap + 10);
                e.r.set(x * k, y * k, (x + d) * k, (y + d) * k);
                els.add(e);
            }
            El cancel = new El();
            cancel.kind = LINK;
            cancel.row = LINK_ROW;
            cancel.label = T("Cancelar", "Cancel", "Cancelar");
            cancel.spoken = cancel.label;
            cancel.r.set((cx - 90) * k, (MY + 700) * k, (cx + 90) * k, (MY + 700 + LINK_H) * k);
            cancel.act = this::closePicker;
            els.add(cancel);
            if (focus >= els.size()) focus = els.size() - 1;
            return;
        } else if (id == S_NAME) {
            El edit = new El();
            edit.kind = EDIT;
            edit.row = 0;
            edit.label = Avatars.current();
            int ai = java.util.Arrays.asList(Avatars.IDS).indexOf(edit.label);
            edit.spoken = T("Seu avatar: ", "Your avatar: ", "Tu avatar: ") + (ai >= 0 ? avatarName(ai) : "") + ". " + T("Aperte OK para mudar.", "Press OK to change it.", "Presiona OK para cambiarlo.");
            edit.act = this::openPicker;
            edit.r.set((cx - 80) * k, (MY + 262) * k, (cx + 80) * k, (MY + 422) * k);   // the avatar, with the name under it
            els.add(edit);
            El name = new El();
            name.kind = ROW;
            name.row = 1;
            name.label = T("Seu nome", "Your name", "Tu nombre");
            name.value = nameValue();
            name.spoken = name.label + ", " + name.value;
            name.r.set((cx - 380) * k, (MY + 486) * k, (cx + 380) * k, (MY + 570) * k);
            name.act = this::askName;
            els.add(name);
        } else if (id == S_HOME) {
            float cw = 600, gap = 40, x = cx - (2 * cw + gap) / 2;
            boolean recent = Prefs.cardsMode().equals("recent");
            for (int i = 0; i < 2; i++) {
                El e = new El();
                e.kind = OPTION;
                e.row = 0;
                e.idx = i;
                boolean rec = i == 0;
                e.label = modeName(rec);
                e.value = rec ? T("Retome o que você deixou pela metade nos seus apps.", "Pick up what you left half-watched in your apps.", "Retoma lo que dejaste a medias en tus apps.")
                        : T("Descubra filmes e séries nos seus serviços.", "Discover movies and shows in your services.", "Descubre películas y series en tus servicios.");
                e.selected = homeChosen && rec == recent;
                e.spoken = e.label + ". " + e.value + " " + (e.selected ? T("Selecionado.", "Selected.", "Seleccionado.") : T("Aperte OK para selecionar.", "Press OK to select.", "Presiona OK para seleccionar."));
                e.act = () -> pickMode(rec);
                e.r.set((x + i * (cw + gap)) * k, (MY + 245) * k, (x + i * (cw + gap) + cw) * k, (MY + 700) * k);
                els.add(e);
            }
        } else if (id == S_PERM) {
            El sw = new El();
            sw.kind = SWITCH;
            sw.row = 0;
            boolean on = playbackOn();
            sw.selected = on;
            sw.label = T("Continuar assistindo", "Continue watching", "Continuar viendo");
            sw.value = T("Acesso às notificações", "Notification access", "Acceso a las notificaciones");
            sw.spoken = sw.label + ", " + (on ? yes() : no()) + ". " + (on ? T("Aperte OK para mudar.", "Press OK to change it.", "Presiona OK para cambiarlo.")
                    : T("Aperte OK para ativar.", "Press OK to turn it on.", "Presiona OK para activarlo."));
            sw.r.set((cx - 440) * k, (MY + 528) * k, (cx + 440) * k, (MY + 614) * k);
            sw.act = this::askPlayback;
            els.add(sw);
        }
        // The buttons (the same width), centered in the panel; "Skip" as a link under them.
        boolean last = id == S_DONE;
        float bw = last ? 320 : 280;
        El primary = button(PRIMARY, id == S_WELCOME ? T("Começar", "Get started", "Empezar")
                : last ? T("Começar a usar", "Start using Borealis", "Empezar a usar") : T("Seguinte", "Next", "Siguiente"),
                bw, last ? this::complete : this::next);
        if (id == S_NAME && Prefs.name().isEmpty()) {
            primary.enabled = false;
            primary.spoken = primary.label + ", " + T("desativado. Escreva seu nome para continuar.", "disabled. Type your name to continue.",
                    "desactivado. Escribe tu nombre para continuar.");
        } else if (id == S_LANG && !langChosen) {
            primary.enabled = false;
            primary.spoken = primary.label + ", " + T("desativado. Escolha um idioma para continuar.", "disabled. Pick a language to continue.",
                    "desactivado. Elige un idioma para continuar.");
        } else if (id == S_HOME && !homeChosen) {
            primary.enabled = false;
            primary.spoken = primary.label + ", " + T("desativado. Escolha uma opção para continuar.", "disabled. Pick an option to continue.",
                    "desactivado. Elige una opción para continuar.");
        }
        El back = step > 0 ? button(BTN, T("Voltar", "Back", "Atrás"), bw, this::previous) : null;
        placeButtons(back, primary);
        if (!last) {
            El skip = new El();
            skip.kind = LINK;
            skip.row = LINK_ROW;
            skip.label = T("Pular", "Skip", "Saltar");
            skip.spoken = skip.label + ", " + T("fecha as boas-vindas", "closes the welcome", "cierra la bienvenida");
            skip.r.set((cx - 90) * k, LINK_Y * k, (cx + 90) * k, (LINK_Y + LINK_H) * k);
            skip.act = this::askSkip;
            els.add(skip);
        }
        if (focus >= els.size()) focus = els.size() - 1;
    }

    private El button(int kind, String text, float width, Runnable act) {
        El e = new El();
        e.kind = kind;
        e.row = BUTTON_ROW;
        e.label = text;
        e.spoken = text;
        e.act = act;
        e.r.set(0, BUTTONS_Y * k, width * k, (BUTTONS_Y + BUTTON_H) * k);   // placed (centered) by placeButtons
        return e;
    }

    /** One or two buttons side by side, centered in the panel. `first` is optional (the one at the left). */
    private void placeButtons(El first, El second) {
        float gap = 20, total = second.r.width() / k + (first == null ? 0 : first.r.width() / k + gap);
        float x = MX + MW / 2 - total / 2;
        if (first != null) {
            float fw = first.r.width() / k;
            first.r.set(x * k, BUTTONS_Y * k, (x + fw) * k, (BUTTONS_Y + BUTTON_H) * k);
            els.add(first);
            x += fw + gap;
        }
        float sw = second.r.width() / k;
        second.r.set(x * k, BUTTONS_Y * k, (x + sw) * k, (BUTTONS_Y + BUTTON_H) * k);
        els.add(second);
    }

    // MARK: - Steps and actions

    private void enterStep(int dir) {
        enterDir = dir;
        savedStep = stepId();
        built = false;
        if (stepId() == S_NAME) {
            if (!avatarRolled || Avatars.current().isEmpty()) {   // a different suggestion each time (the person can change it)
                avatarRolled = true;
                Prefs.setAvatar(Avatars.IDS[new java.util.Random().nextInt(Avatars.IDS.length)]);
            }
            Avatars.preload(getContext());
        }
        if (stepId() == S_PERM && playbackOn()) permWasOn = true;
        ensureLayout();
        showFocus = confirm || stepId() != S_NAME || pickingAvatar || dir < 0;
        // The name goes first on its step; lists start on what is selected; the last step and the warnings on the main button.
        focus = -1;
        if (!confirm && stepId() != S_DONE) {
            if (stepId() == S_NAME) focus = pickingAvatar ? Math.max(0, java.util.Arrays.asList(Avatars.IDS).indexOf(Avatars.current())) : 0;   // the avatar comes first
            else for (int i = 0; i < els.size() && focus < 0; i++) if (els.get(i).selected && els.get(i).kind != SWITCH) focus = i;
            if (focus < 0) focus = 0;
        }
        if (focus < 0 || (els.get(focus).kind == BTN || els.get(focus).kind == LINK)) focus = primaryIndex();
        intro = stepTitle() + ". " + stepBody() + (confirm ? "" : " " + T("Passo ", "Step ", "Paso ") + (step + 1) + T(" de ", " of ") + flow().length + ".");
        enter.snap(0);
        enter.target = 1;
        for (Spring s : pop) s.snap(0);
        if (w > 0) {
            invalidate();
            postDelayed(spoken::refocus, 300);
        }
    }

    private int primaryIndex() {
        for (int i = 0; i < els.size(); i++) if (els.get(i).kind == PRIMARY) return i;
        return Math.max(0, els.size() - 1);
    }

    private void next() {
        if (stepId() == S_LANG && !langChosen) return;
        if (stepId() == S_NAME && Prefs.name().isEmpty()) return;
        if (stepId() == S_HOME && !homeChosen) return;
        if (stepId() == S_PERM && !playbackOn()) {   // no access: say what that means before going on
            warnPerm = true;
            confirm = true;
            click.open();
            enterStep(1);
            return;
        }
        if (step < flow().length - 1) { step++; enterStep(1); click.open(); }
    }

    private void previous() {
        if (step > 0) { step--; enterStep(-1); click.move(); }
    }

    private void askSkip() {
        confirm = true;
        warnPerm = false;
        click.open();
        enterStep(1);
    }

    private void cancelSkip() {
        confirm = false;
        warnPerm = false;
        click.move();
        enterStep(-1);
    }

    /** The warning's "Turn on access": back to the step, and straight to the system screen. */
    private void warnActivate() {
        confirm = false;
        warnPerm = false;
        enterStep(-1);
        askPlayback();
    }

    /** The warning's "Go on with Recommendations": the mode changes (it cannot work without the access) and the welcome goes on. */
    private void warnRecommendations() {
        confirm = false;
        warnPerm = false;
        Prefs.setCardsMode("recs");
        homeChosen = true;
        step = indexOf(S_DONE);
        click.open();
        enterStep(1);
    }

    /** The welcome is over: if Borealis is not yet the TV's home, turn it on now (the TV may ask to allow debugging: "Always allow"). */
    private void turnOnBorealis() {
        if (!HomeMode.isFree(getContext())) HomeMode.request(getContext(), HomeMode.FREE);
    }

    private void skipNow() {
        markDone(getContext());
        turnOnBorealis();
        click.open();
        done.done(false);
    }

    private void complete() {
        markDone(getContext());
        turnOnBorealis();
        click.open();
        done.done(true);
    }

    private void pickLanguage(int i) {
        cycling = false;
        langChosen = true;
        Prefs.setLanguage(LANGS[i]);
        click.move();
        intro = "";
        built = false;
        invalidate();
        spoken.focusMoved();
    }

    private void openPicker() {
        pickingAvatar = true;
        click.open();
        enterStep(1);
    }

    private void closePicker() {
        pickingAvatar = false;
        click.move();
        enterStep(-1);
        focus = 0;   // back on the avatar
        spoken.focusMoved();
    }

    private void pickAvatar(int i) {
        Prefs.setAvatar(Avatars.IDS[i]);
        closePicker();
    }

    private void pickMode(boolean recent) {
        Prefs.setCardsMode(recent ? "recent" : "recs");
        homeChosen = true;
        click.move();
        built = false;
        invalidate();
        spoken.focusMoved();
    }

    private void askName() {
        final android.widget.EditText input = new android.widget.EditText(getContext());
        input.setSingleLine(true);
        input.setText(Prefs.name());
        input.setSelection(input.getText().length());
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        new android.app.AlertDialog.Builder(getContext())
                .setTitle(T("Como você quer que eu te chame?", "What should we call you?", "¿Cómo quieres que te llame?"))
                .setView(input)
                .setPositiveButton("OK", (d, which) -> {
                    Prefs.setName(input.getText().toString());
                    built = false;
                    invalidate();
                    spoken.focusMoved();
                })
                .setNegativeButton(T("Cancelar", "Cancel", "Cancelar"), null)
                .show();
    }

    /** The notification access: always opens the system screen (turn it on, or change it if it is already on). */
    private void askPlayback() {
        click.open();
        Watched.openAccessSettings(getContext());
    }

    private void activate() {
        if (focus < 0 || focus >= els.size()) return;
        El e = els.get(focus);
        if (!e.enabled) return;
        if (e.act != null) e.act.run();
        built = false;
        invalidate();
        spoken.focusMoved();
    }

    // MARK: - Keys

    private boolean okDown;

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        if (down) intro = "";
        ensureLayout();
        boolean nav = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!showFocus && nav) {
            if (down && e.getRepeatCount() == 0) { showFocus = true; focus = 0; click.move(); invalidate(); spoken.focusMoved(); }
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveSideways(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveVertically(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
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
                // Only a Back that began here counts: the "up" of a Back pressed in a system screen must not go back a step.
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else if (backDown) {
                    backDown = false;
                    if (pickingAvatar) closePicker();
                    else if (confirm) { cancelSkip(); spoken.focusMoved(); }
                    else if (step > 0) { previous(); spoken.focusMoved(); }
                    else { askSkip(); spoken.focusMoved(); }
                }
                return true;
            default:
                return false;
        }
    }

    private void moveSideways(int dir) {
        if (focus < 0 || focus >= els.size()) return;
        El cur = els.get(focus);
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < els.size(); i++) {
            El e = els.get(i);
            if (i == focus || e.row != cur.row) continue;
            float d = (e.r.centerX() - cur.r.centerX()) * dir;
            if (d > 0 && d < bestD) { bestD = d; best = i; }
        }
        if (best >= 0) { focus = best; click.move(); invalidate(); spoken.focusMoved(); }
    }

    private void moveVertically(int dir) {
        if (focus < 0 || focus >= els.size()) return;
        El cur = els.get(focus);
        int bestRow = 0;
        boolean found = false;
        for (El e : els) {
            // The disabled "Next" still takes the focus when it is the only way down; it just can't be activated.
            if ((e.row - cur.row) * dir <= 0) continue;
            if (!found || (e.row - bestRow) * dir < 0) { bestRow = e.row; found = true; }
        }
        if (!found) return;
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < els.size(); i++) {
            El e = els.get(i);
            if (e.row != bestRow) continue;
            if (bestRow == BUTTON_ROW && e.kind == PRIMARY) { best = i; break; }   // down lands on "Next"; "Back" is a deliberate step sideways
            float d = Math.abs(e.r.centerX() - cur.r.centerX());
            if (d < bestD) { bestD = d; best = i; }
        }
        if (best >= 0) { focus = best; click.move(); invalidate(); spoken.focusMoved(); }
    }

    // MARK: - Drawing

    private void paints() {
        if (paintK == k && title != null) return;
        paintK = k;
        layouts.clear();
        title = Ui.text(Ui.displaySemibold, 52 * k, 0xFFFFFFFF);
        title.setTextAlign(Paint.Align.CENTER);
        greet = Ui.text(Ui.displaySemibold, 70 * k, 0xFFFFFFFF);
        greet.setTextAlign(Paint.Align.CENTER);
        body = Ui.text(Ui.regular, 28 * k, 0xCCFFFFFF);
        label = Ui.text(Ui.medium, 30 * k, 0xFFFFFFFF);
        value = Ui.text(Ui.regular, 27 * k, 0xCCFFFFFF);
        value.setTextAlign(Paint.Align.RIGHT);
        small = Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF);
        small.setTextAlign(Paint.Align.CENTER);
        hint = Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF);
        button = Ui.text(Ui.medium, 27 * k, 0xFFFFFFFF);
        button.setTextAlign(Paint.Align.CENTER);
        option = Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF);
        stepName = Ui.text(Ui.medium, 21 * k, 0xB3FFFFFF);
        stepName.setTextAlign(Paint.Align.CENTER);
        link = Ui.text(Ui.medium, 26 * k, 0xB3FFFFFF);
        link.setTextAlign(Paint.Align.CENTER);
    }

    private StaticLayout wrapped(String text, float width, Paint p, int maxLines) {
        String key = text + "|" + Math.round(width) + "|" + p.getTextSize();
        StaticLayout l = layouts.get(key);
        if (l == null) {
            l = StaticLayout.Builder.obtain(text, 0, text.length(), new TextPaint(p), Math.round(width))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(maxLines)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0, 1.12f).build();
            if (layouts.size() > 24) layouts.clear();
            layouts.put(key, l);
        }
        return l;
    }

    private void drawWrapped(Canvas c, String text, float cx, float top, float width, Paint p, int maxLines) {
        drawWrapped(c, text, cx, top, width, p, maxLines, 1f);
    }

    /** With alpha < 1 it is drawn in a layer (the cached layout keeps the paint it was built with, so the paint's alpha cannot be used). */
    private void drawWrapped(Canvas c, String text, float cx, float top, float width, Paint p, int maxLines, float alpha) {
        StaticLayout l = wrapped(text, width, p, maxLines);
        if (alpha < 0.999f) c.saveLayerAlpha(cx - width / 2, top, cx + width / 2, top + l.getHeight(), Math.round(255 * alpha));
        else c.save();
        c.translate(cx - width / 2, top);
        l.draw(c);
        c.restore();
    }

    private Shader gradient(RectF r) {
        return new LinearGradient(r.left, 0, r.right, 0, Brand.accents(), null, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas c) {
        if (getWidth() != w || getHeight() != h) built = false;
        w = getWidth();
        h = getHeight();
        if (w == 0) return;
        k = w / 1920f;
        paints();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        appear.target = 1;
        boolean moving = appear.step(dt);
        moving |= enter.step(dt);
        tf = 1;
        int pvNow = previewIndex();
        if (pvNow != lastPv) { lastPv = pvNow; built = false; }
        if (rotating()) {
            int ci = cycIndex();
            if (ci != lastCyc) { lastCyc = ci; built = false; }
            long phase = (SystemClock.uptimeMillis() - shownAt) % PERIOD, fs = PERIOD - FADE;
            if (phase >= fs) { tf = Math.abs(phase - (PERIOD - FADE / 2f)) / (FADE / 2f); moving = true; }
            else { handler.removeCallbacks(tick); handler.postDelayed(tick, fs - phase); }
        }
        ensureLayout();
        for (int i = 0; i < pop.length; i++) {
            pop[i].target = i == focus && showFocus ? 1 : 0;
            moving |= pop[i].step(dt);
        }
        float a = Ui.clamp(appear.value);
        float e = Ui.clamp(enter.value);

        // The panel: night with some transparency. (The wallpaper is the view behind.)
        c.save();
        float s = 0.97f + 0.03f * a;
        c.scale(s, s, w / 2f, h / 2f);
        box.set(MX * k, MY * k, (MX + MW) * k, (MY + MH) * k);
        fill.setShader(null);
        fill.setColor(Ui.alpha(Brand.ink(0xD9), a));
        c.drawRoundRect(box, RADIUS * k, RADIUS * k, fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * k);
        line.setColor(Ui.alpha(0x33FFFFFF, a));
        tmp.set(box.left + k, box.top + k, box.right - k, box.bottom - k);
        c.drawRoundRect(tmp, RADIUS * k - k, RADIUS * k - k, line);
        line.setStyle(Paint.Style.FILL);

        // Layers only while something fades (they are costly on a weak TV); at rest everything is drawn directly.
        boolean fading = a < 0.999f || e < 0.999f;
        int layer = fading ? c.saveLayerAlpha(box.left, box.top, box.right, box.bottom, Math.round(255 * a)) : -1;
        if (!confirm) drawDots(c);
        int layer2 = fading ? c.saveLayerAlpha(box.left, box.top, box.right, (MY + 725) * k, Math.round(255 * e)) : -1;
        c.save();
        c.translate((1 - e) * 36 * k * enterDir, 0);
        moving |= drawContent(c);
        for (int i = 0; i < els.size(); i++) if (els.get(i).row < BUTTON_ROW) drawElement(c, els.get(i), i);
        c.restore();
        if (layer2 >= 0) c.restoreToCount(layer2);
        for (int i = 0; i < els.size(); i++) if (els.get(i).row >= BUTTON_ROW) drawElement(c, els.get(i), i);
        if (layer >= 0) c.restoreToCount(layer);
        c.restore();

        if (moving) postInvalidateOnAnimation();
    }

    /** Draws the step's own content; true while it is animating on its own (the greeting changing language). */
    private boolean drawContent(Canvas c) {
        float cx = w / 2f, top = MY * k;
        if (confirm) {
            c.drawText(stepTitle(), cx, top + 230 * k, title);
            drawWrapped(c, stepBody(), cx, top + 276 * k, 940 * k, body, 7);
            return false;
        }
        switch (stepId()) {
            case S_WELCOME: {
                float u = 0.66f * k;
                Logo.drawLockup(c, getContext(), cx - 500 * u, top + 100 * k - 269.6f * u, u);
                greet.setAlpha(Math.round(255 * tf));
                c.drawText(stepTitle(), cx, top + 520 * k, greet);
                greet.setAlpha(255);
                drawWrapped(c, stepBody(), cx, top + 556 * k, 900 * k, body, 1, tf);
                return false;
            }
            case S_LANG: {
                c.drawText(stepTitle(), cx, top + 150 * k, title);
                drawWrapped(c, stepBody(), cx, top + 186 * k, 1000 * k, body, 2);
                return false;
            }
            case S_NAME:
                if (pickingAvatar) {
                    c.drawText(stepTitle(), cx, top + 110 * k, title);
                    drawWrapped(c, stepBody(), cx, top + 136 * k, 900 * k, body, 1);
                    return false;
                }
                c.drawText(stepTitle(), cx, top + 110 * k, title);
                drawWrapped(c, stepBody(), cx, top + 136 * k, 900 * k, body, 1);
                return false;
            case S_HOME:
                c.drawText(stepTitle(), cx, top + 110 * k, title);
                drawWrapped(c, stepBody(), cx, top + 136 * k, 1000 * k, body, 2);
                return false;
            case S_PERM: {
                c.drawText(stepTitle(), cx, top + 106 * k, title);
                drawWrapped(c, stepBody(), cx, top + 130 * k, 1100 * k, body, 2);
                // The picture: the home screen with "Continue watching", and the bell for the notification access.
                RectF pic = new RectF(cx - 240 * k, top + 250 * k, cx + 240 * k, top + 470 * k);
                drawPreview(c, pic, true);
                float br = 32 * k, bx = pic.right - 6 * k, by = pic.top + 6 * k;
                tmp.set(bx - br, by - br, bx + br, by + br);
                fill.setShader(gradient(tmp));
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(bx, by, br, fill);
                fill.setShader(null);
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(4 * k);
                line.setColor(Brand.ink(0xFF));
                c.drawCircle(bx, by, br, line);
                line.setStyle(Paint.Style.FILL);
                Symbols.draw(getContext(), c, "bell", bx, by, 34 * k, Brand.INK);
                boolean on = playbackOn();
                String msg = on ? T("Pronto: “Continuar assistindo” já funciona.", "Done: “Continue watching” already works.", "Listo: “Continuar viendo” ya funciona.")
                        : permWasOn ? T("Você desativou o acesso: sem ele não dá para ver “Continuar assistindo”.", "You turned the access off: without it “Continue watching” can't be shown.",
                        "Desactivaste el acceso: sin él no podrás ver “Continuar viendo”.")
                        : T("Ao ativar, a TV abrirá uma lista: procure “Borealis”, ative-o e volte com Voltar.",
                        "When you turn it on, the TV opens a list: find “Borealis”, switch it on and come back with Back.",
                        "Al activarlo, la TV abrirá una lista: busca “Borealis”, actívalo y vuelve con Atrás.");
                drawWrapped(c, msg, cx, top + 636 * k, 900 * k, hint, 2);
                return false;
            }
            case S_SHELF: {
                c.drawText(stepTitle(), cx, top + 106 * k, title);
                drawWrapped(c, stepBody(), cx, top + 130 * k, 1000 * k, body, 3);
                tmp.set(cx - 400 * k, top + 270 * k, cx + 400 * k, top + 270 * k + 450 * k);
                shelfDemo.draw(c, getContext(), tmp);
                return true;
            }
            default: {
                float r = 74 * k, cy = top + 160 * k;
                String id = Avatars.current();
                tmp.set(cx - r - 8 * k, cy - r - 8 * k, cx + r + 8 * k, cy + r + 8 * k);
                fill.setShader(gradient(tmp));
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, r + 8 * k, fill);
                fill.setShader(null);
                if (!id.isEmpty()) Avatars.draw(c, getContext(), id, cx, cy, r, 1f);
                c.drawText(stepTitle(), cx, top + 322 * k, title);
                drawWrapped(c, stepBody(), cx, top + 346 * k, 940 * k, body, 2);
                drawSummary(c, cx, top + 470 * k);
                return false;
            }
        }
    }

    private void drawSummary(Canvas c, float cx, float y) {
        String[] names = {T("Idioma", "Language", "Idioma"), T("Tela inicial", "Home screen", "Pantalla de inicio"),
                T("Continuar assistindo", "Continue watching", "Continuar viendo")};
        String[] vals = {languageName(), modeName(Prefs.cardsMode().equals("recent")), playbackOn() ? yes() : no()};
        int n = Prefs.cardsMode().equals("recent") ? 3 : 2;
        small.setColor(0xCCFFFFFF);
        for (int i = 0; i < n; i++) {
            String text = names[i] + ": " + vals[i];
            float ly = y + i * 44 * k, tw = small.measureText(text), x = cx - (34 * k + tw) / 2;
            boolean good = i < 2 || playbackOn();
            Symbols.draw(getContext(), c, good ? "check" : "chevron_right", x + 11 * k, ly - 8 * k, 22 * k, good ? Brand.LILY : 0x80FFFFFF);
            small.setTextAlign(Paint.Align.LEFT);
            c.drawText(text, x + 34 * k, ly, small);
            small.setTextAlign(Paint.Align.CENTER);
        }
        small.setColor(0xB3FFFFFF);
    }

    /** The progress dots at the top: the current step is a gradient pill, the ones done are solid, the rest dim. */
    private void drawDots(Canvas c) {
        int n = flow().length;
        float cy = (MY + 44) * k, r = 7 * k, gap = 26 * k, pill = 40 * k;
        float total = (n - 1) * (2 * r + gap) + pill, x = w / 2f - total / 2;
        for (int i = 0; i < n; i++) {
            if (i == step) {
                tmp.set(x, cy - r, x + pill, cy + r);
                fill.setShader(gradient(tmp));
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(tmp, r, r, fill);
                fill.setShader(null);
                x += pill + gap;
            } else {
                fill.setShader(null);
                fill.setColor(i < step ? 0xCCFFFFFF : 0x40FFFFFF);
                c.drawCircle(x + r, cy, r, fill);
                x += 2 * r + gap;
            }
        }
    }

    /** The focus ring: the brand gradient (white only on a button that is filled with the gradient). */
    private void drawRing(Canvas c, RectF r, float radius, float width, float alpha) {
        drawRing(c, r, radius, width, alpha, false);
    }

    private void drawRing(Canvas c, RectF r, float radius, float width, float alpha, boolean white) {
        float half = width / 2;
        tmp.set(r.left + half, r.top + half, r.right - half, r.bottom - half);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(width);
        line.setShader(white ? null : gradient(tmp));
        line.setColor(Ui.alpha(0xFFFFFFFF, Math.min(1, alpha)));
        c.drawRoundRect(tmp, Math.max(0, radius - half), Math.max(0, radius - half), line);
        line.setShader(null);
        line.setStyle(Paint.Style.FILL);
    }

    /** The selected mark: the brand gradient as a ring. */
    private void drawSelectedRing(Canvas c, RectF r, float radius, float width) {
        float half = width / 2;
        tmp2.set(r.left + half, r.top + half, r.right - half, r.bottom - half);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(width);
        line.setShader(gradient(tmp2));
        line.setColor(0xFFFFFFFF);
        c.drawRoundRect(tmp2, Math.max(0, radius - half), Math.max(0, radius - half), line);
        line.setShader(null);
        line.setStyle(Paint.Style.FILL);
    }

    private void drawElement(Canvas c, El e, int index) {
        float f = Ui.clamp(index < pop.length ? pop[index].value : (index == focus ? 1 : 0));
        float grow = e.kind == PRIMARY || e.kind == BTN ? 0.05f : e.kind == LINK ? 0 : e.kind == AVATAR ? 0.10f : e.kind == EDIT ? 0.04f : e.kind == OPTION ? 0.025f : 0.02f;
        float sc = 1 + grow * f;
        RectF r = new RectF(e.r);
        float cx = r.centerX(), cy = r.centerY();
        r.set(cx - r.width() * sc / 2, cy - r.height() * sc / 2, cx + r.width() * sc / 2, cy + r.height() * sc / 2);
        float rad = r.height() / 2;
        switch (e.kind) {
            case PRIMARY:
                if (!e.enabled) {
                    fill.setShader(null);
                    fill.setColor(0x26FFFFFF);
                    c.drawRoundRect(r, rad, rad, fill);
                    if (f > 0.01f) drawRing(c, r, rad, 3 * k, f * 0.6f);
                    button.setColor(0x66FFFFFF);
                } else {
                    fill.setShader(gradient(r));
                    fill.setColor(0xFFFFFFFF);
                    c.drawRoundRect(r, rad, rad, fill);
                    fill.setShader(null);
                    if (f > 0.01f) drawRing(c, r, rad, 4 * k, f, true);
                    button.setColor(Brand.INK);
                }
                button.setAlpha(Math.round(button.getAlpha() * tf));
                c.drawText(e.label, r.centerX(), r.centerY() + Ui.halfCap(button), button);
                button.setColor(0xFFFFFFFF);
                break;
            case BTN:
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f + 0.14f * f));
                c.drawRoundRect(r, rad, rad, fill);
                if (f > 0.01f) drawRing(c, r, rad, 3.5f * k, f);
                else {
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(2 * k);
                    line.setColor(0x4DFFFFFF);
                    tmp.set(r.left + k, r.top + k, r.right - k, r.bottom - k);
                    c.drawRoundRect(tmp, rad - k, rad - k, line);
                    line.setStyle(Paint.Style.FILL);
                }
                button.setColor(Ui.alpha(0xFFFFFFFF, 0.78f + 0.22f * f));
                c.drawText(e.label, r.centerX(), r.centerY() + Ui.halfCap(button), button);
                button.setColor(0xFFFFFFFF);
                break;
            case LINK:   // a text link: it gets a soft pill and the white ring when it has the focus
                if (f > 0.01f) {
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(0xFFFFFFFF, 0.12f * f));
                    c.drawRoundRect(r, rad, rad, fill);
                    drawRing(c, r, rad, 3 * k, f);
                }
                link.setColor(Ui.alpha(0xFFFFFFFF, 0.62f + 0.38f * f));
                c.drawText(e.label, r.centerX(), r.centerY() + Ui.halfCap(link), link);
                break;
            case LANGUAGE: {
                float rr = 30 * k;
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, (e.selected ? 0.20f : 0.09f) + 0.10f * f));
                c.drawRoundRect(r, rr, rr, fill);
                if (e.selected) drawSelectedRing(c, r, rr, 3.5f * k);
                if (f > 0.01f) drawRing(c, r, rr, 4 * k, f);
                float fr = 50 * k, fcy = r.top + 40 * k + fr;
                Avatars.drawFlag(c, getContext(), e.idx, r.centerX(), fcy, fr);
                if (e.selected) {   // a check in a gradient circle on the flag
                    float br = 18 * k, bx = r.centerX() + fr * 0.74f, by = fcy + fr * 0.74f;
                    tmp.set(bx - br, by - br, bx + br, by + br);
                    fill.setShader(gradient(tmp));
                    fill.setColor(0xFFFFFFFF);
                    c.drawCircle(bx, by, br, fill);
                    fill.setShader(null);
                    Symbols.draw(getContext(), c, "check", bx, by, 22 * k, Brand.INK);
                }
                float ty = r.bottom - 38 * k + (e.idx == 3 ? -16 * k : 0);
                button.setColor(0xFFFFFFFF);
                c.drawText(e.label, r.centerX(), ty, button);
                if (e.idx == 3) c.drawText(T("Idioma da TV", "TV language", "Idioma de la TV"), r.centerX(), r.bottom - 20 * k, small);
                break;
            }
            case AVATAR: {
                float ar = r.width() / 2;
                Avatars.draw(c, getContext(), e.label, r.centerX(), r.centerY(), ar, 1f);
                if (e.selected) {
                    tmp2.set(r.left, r.top, r.right, r.bottom);
                    drawSelectedRing(c, tmp2, tmp2.width() / 2, 4.5f * k);
                }
                if (f > 0.01f) {
                    tmp2.set(r.left, r.top, r.right, r.bottom);
                    drawRing(c, tmp2, tmp2.width() / 2, 5 * k, f);
                }
                break;
            }
            case EDIT: {   // the big avatar with its pencil: it opens the picker
                float ar = r.width() / 2;
                Avatars.draw(c, getContext(), e.label, r.centerX(), r.centerY(), ar, 1f);
                tmp2.set(r.left - 6 * k, r.top - 6 * k, r.right + 6 * k, r.bottom + 6 * k);
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(2 * k);
                line.setColor(0x40FFFFFF);
                c.drawCircle(tmp2.centerX(), tmp2.centerY(), tmp2.width() / 2 - k, line);
                line.setStyle(Paint.Style.FILL);
                if (f > 0.01f) {
                    tmp2.set(r.left - 14 * k, r.top - 14 * k, r.right + 14 * k, r.bottom + 14 * k);
                    drawRing(c, tmp2, tmp2.width() / 2, 4 * k, f);
                }
                float bx = r.right - 14 * k, by = r.bottom - 14 * k, br = 26 * k;
                tmp.set(bx - br, by - br, bx + br, by + br);
                fill.setShader(gradient(tmp));
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(bx, by, br, fill);
                fill.setShader(null);
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(4 * k);
                line.setColor(Brand.ink(0xFF));
                c.drawCircle(bx, by, br, line);
                line.setStyle(Paint.Style.FILL);
                drawPencil(c, bx, by, 30 * k, Brand.INK);
                break;
            }
            case OPTION: {
                float rr = 26 * k;
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, (e.selected ? 0.14f : 0.08f) + 0.08f * f));
                c.drawRoundRect(r, rr, rr, fill);
                if (e.selected) drawSelectedRing(c, r, rr, 4 * k);
                if (f > 0.01f) drawRing(c, r, rr, 4.5f * k, f);
                float pad = 24 * k;
                RectF pr = new RectF(r.left + pad, r.top + pad, r.right - pad, r.top + pad + 220 * k * sc);
                drawPreview(c, pr, e.idx == 0);
                option.setTextAlign(Paint.Align.LEFT);
                float ty = pr.bottom + 50 * k;
                c.drawText(e.label, r.left + pad, ty, option);
                body.setTextAlign(Paint.Align.LEFT);
                StaticLayout l = wrappedLeft(e.value, r.width() - 2 * pad, body, 2);
                c.save();
                c.translate(r.left + pad, ty + 14 * k);
                l.draw(c);
                c.restore();
                // "Select" / "Selected": a circle with a check (filled with the brand gradient once it is the choice) and the word.
                float cr = 17 * k, cxm = r.left + pad + cr, cym = r.bottom - pad - cr - 2 * k;
                tmp.set(cxm - cr, cym - cr, cxm + cr, cym + cr);
                if (e.selected) {
                    fill.setShader(gradient(tmp));
                    fill.setColor(0xFFFFFFFF);
                    c.drawCircle(cxm, cym, cr, fill);
                    fill.setShader(null);
                    Symbols.draw(getContext(), c, "check", cxm, cym, 22 * k, Brand.INK);
                } else {
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(2.5f * k);
                    line.setColor(0x99FFFFFF);
                    c.drawCircle(cxm, cym, cr - 1.25f * k, line);
                    line.setStyle(Paint.Style.FILL);
                }
                small.setTextAlign(Paint.Align.LEFT);
                small.setColor(e.selected ? 0xFFFFFFFF : 0xB3FFFFFF);
                c.drawText(e.selected ? T("Selecionado", "Selected", "Seleccionado") : T("Selecionar", "Select", "Seleccionar"), cxm + cr + 14 * k, cym + Ui.halfCap(small), small);
                small.setColor(0xB3FFFFFF);
                small.setTextAlign(Paint.Align.CENTER);
                break;
            }
            case SWITCH: {
                float rr = 28 * k;
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.10f + 0.12f * f));
                c.drawRoundRect(r, rr, rr, fill);
                if (f > 0.01f) drawRing(c, r, rr, 4 * k, f);
                float pad = 36 * k;
                Symbols.draw(getContext(), c, "play", r.left + pad + 22 * k, r.centerY(), 44 * k, 0xE6FFFFFF);
                float tx = r.left + pad + 76 * k;
                label.setTextAlign(Paint.Align.LEFT);
                c.drawText(e.label, tx, r.centerY() - 6 * k, label);
                small.setTextAlign(Paint.Align.LEFT);
                c.drawText(e.value, tx, r.centerY() + 32 * k, small);
                small.setTextAlign(Paint.Align.CENTER);
                // The switch: a pill with a knob; the gradient when it is on.
                float sw = 104 * k, sh = 56 * k, sx = r.right - pad - sw, sy = r.centerY() - sh / 2;
                tmp.set(sx, sy, sx + sw, sy + sh);
                if (e.selected) {
                    fill.setShader(gradient(tmp));
                    fill.setColor(0xFFFFFFFF);
                } else {
                    fill.setColor(0x40FFFFFF);
                }
                c.drawRoundRect(tmp, sh / 2, sh / 2, fill);
                fill.setShader(null);
                fill.setColor(0xFFFFFFFF);
                c.drawCircle(e.selected ? sx + sw - sh / 2 : sx + sh / 2, sy + sh / 2, sh / 2 - 6 * k, fill);
                break;
            }
            default: {   // ROW: label left, value right
                float rr = 24 * k;
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.10f + 0.12f * f));
                c.drawRoundRect(r, rr, rr, fill);
                if (f > 0.01f) drawRing(c, r, rr, 3.5f * k, f);
                float pad = 32 * k;
                label.setTextAlign(Paint.Align.LEFT);
                c.drawText(e.label, r.left + pad, r.centerY() + Ui.halfCap(label), label);
                c.drawText(e.value, r.right - pad, r.centerY() + Ui.halfCap(value), value);
            }
        }
    }

    private final Path pencil = new Path();

    /** A pencil, tip to the lower left, centered at (cx, cy) in a box of `size`. */
    private void drawPencil(Canvas c, float cx, float cy, float size, int color) {
        pencil.rewind();
        float s = size;
        pencil.moveTo(-0.13f * s, -0.42f * s);
        pencil.lineTo(0.13f * s, -0.42f * s);
        pencil.lineTo(0.13f * s, 0.14f * s);
        pencil.lineTo(0f, 0.44f * s);
        pencil.lineTo(-0.13f * s, 0.14f * s);
        pencil.close();
        c.save();
        c.translate(cx, cy);
        c.rotate(45);
        fill.setShader(null);
        fill.setColor(color);
        c.drawPath(pencil, fill);
        // the band between the body and the eraser, cut out as a thin line
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(0.05f * s);
        line.setColor(Brand.WHITE);
        c.drawLine(-0.13f * s, -0.28f * s, 0.13f * s, -0.28f * s, line);
        line.setStyle(Paint.Style.FILL);
        c.restore();
    }

    private StaticLayout wrappedLeft(String text, float width, Paint p, int maxLines) {
        String key = "L" + text + "|" + Math.round(width) + "|" + p.getTextSize();
        StaticLayout l = layouts.get(key);
        if (l == null) {
            l = StaticLayout.Builder.obtain(text, 0, text.length(), new TextPaint(p), Math.round(width))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(maxLines)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0, 1.1f).build();
            if (layouts.size() > 24) layouts.clear();
            layouts.put(key, l);
        }
        return l;
    }

    /** A small picture of the home screen with that choice: "continue watching" has wide cards with a progress bar; "recommendations" has tabs and posters. */
    private void drawPreview(Canvas c, RectF r, boolean recent) {
        float rr = 16 * k;
        fill.setShader(null);
        fill.setColor(0xFF211C2B);
        c.drawRoundRect(r, rr, rr, fill);
        c.save();
        clip.rewind();
        clip.addRoundRect(r, rr, rr, Path.Direction.CW);
        c.clipPath(clip);
        fill.setColor(0x1AB79CFE);
        c.drawCircle(r.right - 40 * k, r.top + 30 * k, 130 * k, fill);
        fill.setColor(0x14FE9565);
        c.drawCircle(r.left + 60 * k, r.bottom, 120 * k, fill);
        c.restore();
        float pad = 22 * k, x = r.left + pad, y = r.top + 22 * k;
        // greeting (as lines) and the corner buttons
        fill.setColor(0x80FFFFFF);
        tmp.set(x, y, x + 74 * k, y + 8 * k);
        c.drawRoundRect(tmp, 4 * k, 4 * k, fill);
        fill.setColor(0xE6FFFFFF);
        tmp.set(x, y + 18 * k, x + 170 * k, y + 32 * k);
        c.drawRoundRect(tmp, 6 * k, 6 * k, fill);
        fill.setColor(0x33FFFFFF);
        for (int i = 0; i < 4; i++) c.drawCircle(r.right - pad - 10 * k - i * 28 * k, y + 14 * k, 9 * k, fill);
        float ry = y + 54 * k;
        int[] tint = {Brand.LILY, Brand.PEACH, Brand.MELON, Brand.LILY};
        if (recent) {
            // a section line, then three wide cards with a progress bar
            fill.setColor(0xB3FFFFFF);
            tmp.set(x, ry, x + 96 * k, ry + 9 * k);
            c.drawRoundRect(tmp, 4 * k, 4 * k, fill);
            float cw = (r.width() - 2 * pad - 2 * 14 * k) / 3, ch = 92 * k, cy0 = ry + 26 * k;
            for (int i = 0; i < 3; i++) {
                float cx0 = x + i * (cw + 14 * k);
                tmp.set(cx0, cy0, cx0 + cw, cy0 + ch);
                fill.setColor(Ui.alpha(tint[i], 0.32f));
                c.drawRoundRect(tmp, 10 * k, 10 * k, fill);
                fill.setColor(0x33FFFFFF);
                tmp2.set(cx0 + 10 * k, cy0 + ch - 16 * k, cx0 + cw - 10 * k, cy0 + ch - 10 * k);
                c.drawRoundRect(tmp2, 3 * k, 3 * k, fill);
                fill.setColor(0xFFFFFFFF);
                tmp2.set(cx0 + 10 * k, cy0 + ch - 16 * k, cx0 + 10 * k + (cw - 20 * k) * (0.3f + 0.25f * i), cy0 + ch - 10 * k);
                c.drawRoundRect(tmp2, 3 * k, 3 * k, fill);
            }
            fill.setColor(0x4DFFFFFF);
            for (int i = 0; i < 3; i++) {
                float cx0 = x + i * (cw + 14 * k);
                tmp.set(cx0, cy0 + ch + 10 * k, cx0 + cw * 0.7f, cy0 + ch + 17 * k);
                c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            }
        } else {
            // tabs (the first one selected), then posters
            float tx = x;
            for (int i = 0; i < 3; i++) {
                float tw = 54 * k;
                tmp.set(tx, ry, tx + tw, ry + 18 * k);
                if (i == 0) {
                    fill.setShader(gradient(tmp));
                    fill.setColor(0xFFFFFFFF);
                } else {
                    fill.setColor(0x33FFFFFF);
                }
                c.drawRoundRect(tmp, 9 * k, 9 * k, fill);
                fill.setShader(null);
                tx += tw + 10 * k;
            }
            float pw = (r.width() - 2 * pad - 3 * 12 * k) / 4, ph = 90 * k, py = ry + 34 * k;
            for (int i = 0; i < 4; i++) {
                float px = x + i * (pw + 12 * k);
                tmp.set(px, py, px + pw, py + ph);
                fill.setColor(Ui.alpha(tint[i], 0.30f));
                c.drawRoundRect(tmp, 9 * k, 9 * k, fill);
                fill.setColor(0x4DFFFFFF);
                tmp2.set(px, py + ph + 8 * k, px + pw * 0.75f, py + ph + 14 * k);
                c.drawRoundRect(tmp2, 3 * k, 3 * k, fill);
            }
        }
    }
}
