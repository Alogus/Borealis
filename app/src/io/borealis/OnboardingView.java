package io.borealis;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
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

    private static final int S_WELCOME = 0, S_LANG = 1, S_NAME = 2, S_HOME = 3, S_PERM = 4, S_DONE = 5, S_PLACE = 7, S_PREP = 8, S_READY = 9, S_TOUR = 10;
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
    private static final int BUTTON_ROW = 9, LINK_ROW = 10, FORM = 11, FIELD = 12;

    private static final class El {
        int kind, row, idx;
        String label, value, spoken;
        boolean selected, enabled = true;
        final RectF r = new RectF();
        Runnable act;
    }

    // Design space (1920 x 1080): the panel and its parts.
    private static final float MX = 240, MY = 84, MW = 1440, MH = 912, RADIUS = 28;
    private static final float BUTTONS_Y = MY + 723, BUTTON_H = 78, LINK_Y = MY + 835, LINK_H = 44;
    private static final String[] LANGS = {"es", "en", "pt", "auto"};

    private final Done done;
    private final TourDemo tourDemo = new TourDemo();
    private final PlaceWidget placeWidget = new PlaceWidget();
    private final PlaceForm form;
    private float frameDt = 1 / 60f;
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
    private long stepAt;   // when the current step began (the welcome's logo animates from here)
    private boolean reducedMotion;   // animations turned off: the logo only fades in
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
        form = new PlaceForm(c, this::invalidate);
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
                : stepId() == S_PREP ? "Borealis, " + stepName(stepId()) + "."
                : T("Borealis, boas-vindas. ", "Borealis, welcome. ", "Borealis, bienvenida. ") + stepCount() + ": " + stepName(stepId()) + ".");
        loadMiniWall();
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
        form.release();
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
        return !homeChosen || Prefs.cardsMode().equals("recent") ? new int[] {S_WELCOME, S_LANG, S_NAME, S_PLACE, S_HOME, S_PERM, S_PREP, S_DONE}
                : new int[] {S_WELCOME, S_LANG, S_NAME, S_PLACE, S_HOME, S_PREP, S_DONE};
    }

    /** The steps that show progress dots: "Preparing your TV" and "Get to know Borealis" have their own look and no dots. */
    private int[] dotSteps() {
        int[] f = flow(), out = new int[f.length];
        int n = 0;
        for (int s : f) if (s != S_PREP) out[n++] = s;
        return java.util.Arrays.copyOf(out, n);
    }

    /** Position among the dots, or -1 when this step has none. */
    private int dotPos() {
        int id = stepId();
        if (id == S_PREP) return -1;
        int[] d = dotSteps();
        for (int i = 0; i < d.length; i++) if (d[i] == id) return i;
        return 0;
    }

    private String stepCount() {
        int p = dotPos();
        return p < 0 ? "" : T("Passo ", "Step ", "Paso ") + (p + 1) + T(" de ", " of ") + dotSteps().length;
    }

    private int indexOf(int id) {
        if (id == S_READY || id == S_TOUR) id = S_DONE;   // the old steps (a saved position) end at the support step
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
            case S_PLACE: return T("Cidade", "City", "Ciudad");
            case S_HOME: return T("Tela inicial", "Home screen", "Pantalla de inicio");
            case S_PERM: return T("Permissão", "Permission", "Permiso");
            case S_PREP: return T("Preparando a sua TV", "Preparing your TV", "Preparando tu TV");
            case S_READY: return T("Sua TV está pronta", "Your TV is ready", "Tu TV está lista");
            case S_TOUR: return T("Conheça o Borealis", "Get to know Borealis", "Conoce Borealis");
            default: return T("Apoio", "Support", "Apoyo");
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
        if (confirm) return T("Deixamos para depois?", "Shall we leave it for later?", "¿Lo dejamos para después?");
        switch (stepId()) {
            case S_WELCOME: return T("Damos as boas-vindas a você", "We welcome you", "Te damos la bienvenida");
            case S_LANG: return T("Em que idioma você se sente em casa?", "Which language feels like home?", "¿En qué idioma te sientes como en casa?");
            case S_NAME: return pickingAvatar ? T("Escolha seu avatar", "Choose your avatar", "Elige tu avatar")
                    : T("Como você quer que eu te chame?", "What should we call you?", "¿Cómo quieres que te llame?");
            case S_PLACE: return T("De que cidade você quer o clima?", "Which city do you want the weather for?", "¿De qué ciudad quieres el clima?");
            case S_HOME: return T("O que você quer ver primeiro?", "What do you want to see first?", "¿Qué quieres ver primero?");
            case S_PERM: return T("Para continuar de onde você parou", "To pick up where you left off", "Para continuar donde lo dejaste");
            case S_PREP: return T("Preparando a sua TV", "Preparing your TV", "Preparando tu TV");
            case S_READY: return T("Sua TV está pronta.", "Your TV is ready.", "Tu TV está lista.");
            case S_TOUR: return tourTitle(tour);
            default: return T("Apoie o Borealis", "Support Borealis", "Apoya a Borealis");
        }
    }

    private String stepBody() {
        if (confirm && warnPerm) return T("Sem o acesso às notificações o Borealis não consegue mostrar o que você deixou pela metade. Se continuar, ativamos o modo Recomendações; você pode mudar quando quiser em Ajustes.",
                "Without notification access Borealis can't show what you left half-watched. If you go on, we'll turn on Recommendations mode; you can change it any time in Settings.",
                "Sin el acceso a las notificaciones Borealis no puede mostrarte lo que dejaste a medias. Si sigues, activamos el modo Recomendaciones; puedes cambiarlo cuando quieras en Ajustes.");
        if (confirm) return T("A sua TV funciona igual. Isto ficará como vem de fábrica até você mudar:",
                "Your TV works the same. This will stay as it comes from the factory until you change it:",
                "Tu TV funciona igual. Esto quedará como viene de fábrica hasta que lo cambies:");
        switch (stepId()) {
            case S_WELCOME: return T("Um launcher leve e bonito para a sua TV.", "A light, beautiful launcher for your TV.", "Un launcher ligero y bonito para tu TV.");
            case S_LANG: return T("Escolha o idioma do Borealis. Dá para mudar depois em Ajustes.", "Choose Borealis's language. You can change it later in Settings.", "Elige el idioma de Borealis. Puedes cambiarlo después en Ajustes.");
            case S_NAME: return pickingAvatar ? T("Escolha o que mais combina com você.", "Pick the one that suits you.", "Elige el que más vaya contigo.")
                    : T("Você pode mudar isso depois em Ajustes › Meu perfil.", "You can change this later in Settings › My profile.", "Puedes cambiarlo después en Ajustes › Mi perfil.");
            case S_PLACE: return T("Escolha o país e a cidade para ver o clima e o pôr do sol do seu lugar. Dá para mudar depois em Ajustes.",
                    "Pick your country and city to see the weather and sunset of your place. You can change it later in Settings.",
                    "Elige tu país y tu ciudad para ver el clima y el atardecer de tu lugar. Puedes cambiarlo después en Ajustes.");
            case S_HOME: return T("Escolha como será a sua tela inicial. Dá para mudar quando quiser em Ajustes.",
                    "Choose how your home screen will look. You can change it any time in Settings.",
                    "Elige cómo se verá tu pantalla de inicio. Puedes cambiarlo cuando quieras en Ajustes.");
            case S_PERM: return T("O Borealis precisa saber qual app você está usando para mostrar o que ficou pela metade. Ele não vê o conteúdo e nada sai da sua TV.",
                    "Borealis needs to know which app you are using to show what you left half-watched. It doesn't see the content and nothing leaves your TV.",
                    "Borealis necesita saber qué app estás usando para mostrarte lo que dejaste a medias. No ve el contenido y nada sale de tu TV.");
            case S_PREP: return T("Estamos aplicando o que você escolheu.", "We are applying what you chose.", "Estamos aplicando lo que elegiste.");
            case S_READY: return T("Agora, o melhor.", "Now, the best part.", "Ahora, lo mejor.");
            case S_TOUR: return tourBody(tour);
            default: return T("O Borealis é grátis e feito por uma só pessoa. Se você gosta, pode ajudar.",
                    "Borealis is free and made by one person. If you like it, you can help.",
                    "Borealis es gratis y lo hace una sola persona. Si te gusta, puedes ayudar.");
        }
    }

    private boolean recsMode() { return !Prefs.cardsMode().equals("recent"); }

    private String tourTitle(int i) {
        switch (i) {
            case 0: return T("Seus apps, à mão", "Your apps, at hand", "Tus apps, a la mano");
            case 1: return T("Do seu jeito", "Made to measure", "A tu medida");
            case 2: return recsMode() ? T("Descubra o que assistir", "Discover what to watch", "Descubre qué ver")
                    : T("Retome de onde parou", "Pick up where you left off", "Retoma donde ibas");
            default: return T("Sua TV, sob uma nova luz", "Your TV, in a new light", "Tu TV, bajo una nueva luz");
        }
    }

    private String tourBody(int i) {
        switch (i) {
            case 0: return T("Com o “+” você fixa seus favoritos no início. Segure “OK” sobre um para movê-lo.",
                    "With the “+” you pin your favorites on the home screen. Hold “OK” on one to move it.",
                    "Con el “+” fijas tus favoritas en el inicio. Mantén “OK” sobre una para moverla.");
            case 1: return T("Escolha quantos apps ver e a forma deles: ampla, equilibrada ou compacta. Em Ajustes › Tela principal.",
                    "Choose how many apps you see and their shape: wide, balanced or compact. In Settings › Home screen.",
                    "Elige cuántas apps ves y su forma: amplia, equilibrada o compacta. En Ajustes › Pantalla principal.");
            case 2: return recsMode() ? T("O Borealis sugere filmes e séries dos serviços que você tem, sem contas nem cadastros.",
                    "Borealis suggests movies and shows from the services you have, with no accounts or sign-ups.",
                    "Borealis te sugiere películas y series de los servicios que tienes, sin cuentas ni registros.")
                    : T("O Borealis mostra o que você deixou pela metade para continuar com “OK”, sem procurar.",
                    "Borealis shows what you left half-watched so you can go on with “OK”, no searching.",
                    "Borealis te muestra lo que dejaste a medias para que sigas con “OK”, sin buscarlo.");
            default: return T("Fundos e protetores de tela pensados para a sua sala. Mude em Ajustes › Aparência.",
                    "Wallpapers and screensavers made for your living room. Change them in Settings › Appearance.",
                    "Fondos y protectores de pantalla pensados para tu sala. Cámbialos en Ajustes › Apariencia.");
        }
    }

    private String languageLabel(int i) {
        return i == 0 ? "Español" : i == 1 ? "English" : i == 2 ? "Português" : T("Automático", "Automatic", "Automático");
    }

    private String nameValue() {
        return Prefs.name().isEmpty() ? T("Aperte “OK” para escrever", "Press “OK” to type", "Pulsa “OK” para escribir") : Prefs.name();
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
            El stay = button(PRIMARY, warnPerm ? T("Ativar o acesso", "Turn on access", "Activar el acceso") : T("Continuar configurando", "Keep setting up", "Seguir configurando"), warnPerm ? 360 : 390,
                    warnPerm ? this::warnActivate : this::cancelSkip);
            El leave = button(BTN, warnPerm ? T("Seguir com Recomendações", "Go on with Recommendations", "Seguir con Recomendaciones") : T("Pular", "Skip", "Saltar"), warnPerm ? 360 : 330,
                    warnPerm ? this::warnRecommendations : this::skipNow);
            placeButtons(leave, stay);
            if (focus >= els.size()) focus = els.size() - 1;
            return;
        }
        int id = stepId();
        if (id == S_PREP) return;   // no buttons: it moves on by itself
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
                e.selected = LANGS[i].equals(cur);
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
            edit.r.set((cx - 96) * k, (MY + 225) * k, (cx + 96) * k, (MY + 417) * k);   // the avatar (192), with the field under it
            els.add(edit);
            El name = new El();
            name.kind = FIELD;
            name.row = 1;
            name.label = T("Seu nome", "Your name", "Tu nombre");
            name.value = nameValue();
            name.spoken = name.label + ", " + name.value;
            name.r.set((cx - 330) * k, (MY + 491) * k, (cx + 330) * k, (MY + 587) * k);
            name.act = this::askName;
            els.add(name);
        } else if (id == S_PLACE) {
            El place = new El();   // the two fields (PlaceForm draws them and takes their keys)
            place.kind = FORM;
            place.row = 0;
            place.label = T("País e cidade", "Country and city", "País y ciudad");
            place.spoken = place.label + ". " + T("Aperte OK para escolher.", "Press OK to choose.", "Pulsa OK para elegir.");
            place.r.set((cx - PlaceForm.OW / 2) * k, (MY + 501) * k, (cx + PlaceForm.OW / 2) * k, (MY + 501 + PlaceForm.OHEIGHT) * k);
            els.add(place);
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
        boolean last = false;   // the support step is the last one, but it has Back, Next and Skip like the rest
        float bw = 279;
        El primary = button(PRIMARY, id == S_WELCOME ? T("Começar", "Get started", "Empezar") : T("Seguinte", "Next", "Siguiente"),
                id == S_WELCOME ? 330 : bw, id == S_WELCOME ? 84 : BUTTON_H, id == S_DONE ? this::complete : this::next);
        if (id == S_NAME && Prefs.name().isEmpty()) {
            primary.enabled = false;
            primary.spoken = primary.label + ", " + T("desativado. Escreva seu nome para continuar.", "disabled. Type your name to continue.",
                    "desactivado. Escribe tu nombre para continuar.");
        } else if (id == S_HOME && !homeChosen) {
            primary.enabled = false;
            primary.spoken = primary.label + ", " + T("desativado. Escolha uma opção para continuar.", "disabled. Pick an option to continue.",
                    "desactivado. Elige una opción para continuar.");
        }
        El back = step > 0 ? button(BTN, T("Voltar", "Back", "Atrás"), bw, BUTTON_H, this::previous) : null;
        placeButtons(back, primary);
        if (!last) {
            El skip = new El();
            skip.kind = LINK;
            skip.row = LINK_ROW;
            boolean tourSkip = id == S_TOUR;
            skip.label = tourSkip ? T("Pular passeio", "Skip the tour", "Saltar recorrido") : T("Pular", "Skip", "Saltar");
            skip.spoken = skip.label + ", " + (tourSkip ? T("vai para o apoio", "goes to the support step", "va al paso de apoyo")
                    : T("fecha as boas-vindas", "closes the welcome", "cierra la bienvenida"));
            float sw2 = tourSkip ? 150 : 90;
            skip.r.set((cx - sw2) * k, LINK_Y * k, (cx + sw2) * k, (LINK_Y + LINK_H) * k);
            skip.act = tourSkip ? this::skipTour : this::askSkip;
            els.add(skip);
        }
        if (focus >= els.size()) focus = els.size() - 1;
    }

    private El button(int kind, String text, float width, Runnable act) {
        return button(kind, text, width, BUTTON_H, act);
    }

    private El button(int kind, String text, float width, float height, Runnable act) {
        El e = new El();
        e.kind = kind;
        e.row = BUTTON_ROW;
        e.label = text;
        e.spoken = text;
        e.act = act;
        e.r.set(0, (BUTTONS_Y + BUTTON_H / 2 - height / 2) * k, width * k, (BUTTONS_Y + BUTTON_H / 2 + height / 2) * k);   // placed (centered) by placeButtons
        return e;
    }

    /** One or two buttons side by side, centered in the panel. `first` is optional (the one at the left). */
    private void placeButtons(El first, El second) {
        float gap = 20, total = second.r.width() / k + (first == null ? 0 : first.r.width() / k + gap);
        float x = MX + MW / 2 - total / 2;
        if (first != null) {
            float fw = first.r.width() / k, fh = first.r.height() / k;
            first.r.set(x * k, (BUTTONS_Y + BUTTON_H / 2 - fh / 2) * k, (x + fw) * k, (BUTTONS_Y + BUTTON_H / 2 + fh / 2) * k);
            els.add(first);
            x += fw + gap;
        }
        float sw = second.r.width() / k, sh = second.r.height() / k;
        second.r.set(x * k, (BUTTONS_Y + BUTTON_H / 2 - sh / 2) * k, (x + sw) * k, (BUTTONS_Y + BUTTON_H / 2 + sh / 2) * k);
        els.add(second);
    }

    // MARK: - Steps and actions

    private void enterStep(int dir) {
        enterDir = dir;
        stepAt = SystemClock.uptimeMillis();
        reducedMotion = Logo.reducedMotion(getContext());
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
        if (stepId() == S_PREP) startPrep();
        ensureLayout();
        showFocus = confirm || stepId() != S_NAME || pickingAvatar || dir < 0;
        // The name goes first on its step; lists start on what is selected; the last step and the warnings on the main button.
        focus = -1;
        if (!confirm && stepId() != S_DONE) {
            if (stepId() == S_NAME) focus = pickingAvatar ? Math.max(0, java.util.Arrays.asList(Avatars.IDS).indexOf(Avatars.current())) : 0;   // the avatar comes first
            else for (int i = 0; i < els.size() && focus < 0; i++) if (els.get(i).selected && els.get(i).kind != SWITCH) focus = i;
            if (focus < 0) focus = 0;
        }
        if (els.isEmpty()) focus = -1;
        else if (focus < 0 || (els.get(focus).kind == BTN || els.get(focus).kind == LINK)) focus = primaryIndex();
        intro = stepId() == S_TOUR && !confirm ? tourIntro()
                : stepTitle() + ". " + stepBody() + (confirm ? "" : " " + stepCount() + ".");
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
        if (stepId() == S_NAME && Prefs.name().isEmpty()) return;
        if (stepId() == S_HOME && !homeChosen) return;
        if (stepId() == S_PLACE) {
            form.save();   // only when both are chosen
            HomeData.get(getContext()).settingsChanged();   // the weather of the chosen city is fetched while the person goes on
            HomeData.get(getContext()).refreshIfStale();
        }
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
        int id = stepId();
        if (id == S_PREP) return;   // nothing to go back to while the TV is being prepared
        if (step > 0) {
            step--;
            if (stepId() == S_PREP) step--;   // back from the support step: the step before "Preparing your TV" (it is not shown again)
            enterStep(-1);
            click.move();
        }
    }

    // MARK: - Preparing the TV and the tour (timed screens)

    private long auto0, tourAt;      // when the timed screen began / when the current tour screen began
    private int tour;                // 0..3
    private volatile boolean prepDone;
    private long prepEnd;   // when the bar filled and "Everything in its place" appeared (it stays 0.6 s)

    private static boolean screenReaderOn(Context c) {
        try {
            android.view.accessibility.AccessibilityManager am = (android.view.accessibility.AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && !am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What the welcome used to do at the end, now for real while "Preparing your TV" shows: the icons, the weather, the pictures, Borealis as the home. */
    private void startPrep() {
        auto0 = SystemClock.uptimeMillis();
        prepDone = false;
        prepEnd = 0;
        final Context app = getContext().getApplicationContext();
        final long began = System.currentTimeMillis();
        new Thread(() -> {
            try {
                Backdrop.preparePanel(app);
                HomeData data = HomeData.get(app);
                data.refreshIfStale();
                for (int i = 0; i < 60 && data.weatherAt < began; i++) {   // the weather of the city (6 s at most; no network just goes on)
                    if (!data.isFetching()) break;
                    Thread.sleep(100);
                }
            } catch (Exception | OutOfMemoryError ex) {
                AppLog.w("preparing: " + ex);
            }
            handler.post(() -> {
                turnOnBorealis();
                prepDone = true;
                Diag.event("onboarding prepared in " + (SystemClock.uptimeMillis() - auto0) + " ms");
            });
        }, "onboarding-prep").start();
        announceForAccessibility(T("Preparando a sua TV", "Preparing your TV", "Preparando tu TV"));
    }

    /** Called on every frame: the timed screens move on by themselves. True while one of them is on. */
    private boolean tickAuto() {
        if (confirm) return false;
        int id = stepId();
        long now = SystemClock.uptimeMillis();
        if (id == S_PREP) {
            long t = now - auto0;
            if (prepEnd == 0 && ((t >= 4200 && prepDone) || t >= 10000)) {
                prepEnd = now;
                announceForAccessibility(T("Tudo no lugar", "Everything in its place", "Todo en su lugar"));
            }
            if (prepEnd != 0 && now - prepEnd >= 600) { step++; enterStep(1); }
            return true;
        }
        return false;
    }

    private void tourNext(boolean manual) {
        if (tour < 3) {
            tour++;
            tourChanged();
        } else {
            step++;
            enterStep(1);
        }
        if (manual) click.open();
    }

    private void tourPrev() {
        if (tour == 0) return;
        tour--;
        tourChanged();
        click.move();
    }

    private void tourChanged() {
        tourAt = SystemClock.uptimeMillis();
        built = false;
        intro = tourIntro();
        invalidate();
        spoken.focusMoved();
        spoken.readAgain();
    }

    private String tourIntro() {
        return T("Conheça o Borealis, ", "Get to know Borealis, ", "Conoce Borealis, ") + (tour + 1) + T(" de 4. ", " of 4. ") + tourTitle(tour) + ". " + tourBody(tour);
    }

    private void skipTour() {
        step = indexOf(S_DONE);
        click.open();
        enterStep(1);
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
        step = indexOf(S_PREP);
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
        TextEntry.Request r = new TextEntry.Request();
        r.title = T("Como você quer que eu te chame?", "What should we call you?", "¿Cómo quieres que te llame?");
        r.hint = T("Digite o seu nome", "Type your name", "Escribe tu nombre");
        r.initial = Prefs.name();
        r.maxLen = 24;
        r.done = (text, o) -> {
            Prefs.setName(text);
            built = false;
            invalidate();
            spoken.focusMoved();
        };
        TextEntry.show(getContext(), r);
    }

    /**
     * The notification access. Already on: the system screen, to change it. Off: it is turned on with the TV's own debugging
     * when that is on (TVs with no such screen, like TCL, have no other way); else the system screen, or an explanation.
     */
    private void askPlayback() {
        click.open();
        if (playbackOn()) {
            Watched.openAccessSettings(getContext());
            return;
        }
        Watched.turnOn(getContext(), () -> {
            built = false;
            invalidate();
            spoken.focusMoved();
        }, () -> Watched.showNoScreen(getContext()));
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
        if (stepId() == S_PLACE && !confirm && focus >= 0 && focus < els.size() && els.get(focus).kind == FORM && form.onKey(e)) {
            invalidate();   // the country list, the city search: the form uses these keys
            return true;
        }
        boolean nav = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (!showFocus && nav) {
            if (down && e.getRepeatCount() == 0) { showFocus = true; focus = 0; click.move(); invalidate(); spoken.focusMoved(); }
            return true;
        }
        if (stepId() == S_TOUR && !confirm && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {   // the tour: Right goes on, Left goes back
            if (down && e.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) tourNext(true);
                else tourPrev();
            }
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
        title = Ui.text(Logo.wordmark(getContext()), 60 * k, 0xFFFFFFFF);   // Gloock, regular weight
        title.setTextAlign(Paint.Align.CENTER);
        greet = Ui.text(Logo.wordmark(getContext()), 66 * k, 0xFFFFFFFF);
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
        buttonB = Ui.text(Ui.bold, 27 * k, 0xFFFFFFFF);   // "Next": 700
        buttonB.setTextAlign(Paint.Align.CENTER);
        buttonS = Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF);   // "Back": 600
        buttonS.setTextAlign(Paint.Align.CENTER);
        option = Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF);
        stepName = Ui.text(Ui.medium, 21 * k, 0xB3FFFFFF);
        stepName.setTextAlign(Paint.Align.CENTER);
        link = Ui.text(Ui.medium, 26 * k, 0xB3FFFFFF);
        link.setTextAlign(Paint.Align.CENTER);
        prepTitle = Ui.text(Logo.wordmark(getContext()), 78 * k, 0xFFFFFFFF);
        prepTitle.setTextAlign(Paint.Align.CENTER);
        prepSub = Ui.text(Ui.medium, 30 * k, 0xD6FFFFFF);
        prepSub.setTextAlign(Paint.Align.CENTER);
        prepPhrase = Ui.text(Ui.semibold, 27 * k, 0xC7FFFFFF);
        prepPhrase.setTextAlign(Paint.Align.CENTER);
        tourLabel = Ui.text(Ui.bold, 19.5f * k, Brand.LILY);
        tourLabel.setLetterSpacing(0.1f);
        tourLabel.setTextAlign(Paint.Align.CENTER);
        tourTitleP = Ui.text(Logo.wordmark(getContext()), 63 * k, 0xFFFFFFFF);
        tourTitleP.setTextAlign(Paint.Align.CENTER);
        tourBodyP = Ui.text(Ui.medium, 28.5f * k, 0xD6FFFFFF);
    }

    private Paint buttonB, buttonS;
    private Paint prepTitle, prepSub, prepPhrase, tourLabel, tourTitleP, tourBodyP;
    private android.view.animation.PathInterpolator appCurve;

    private float curve(float x) {
        if (appCurve == null) appCurve = ScreenTransition.curve();
        return appCurve.getInterpolation(Ui.clamp(x));
    }

    /** The bar's pace: 0 → 42 % → 58 % → 100 %, each stretch eased, so it feels like work and not like a timer. */
    private static float prepCurve(float u) {
        float[] at = {0f, 0.3f, 0.6f, 1f}, to = {0f, 0.42f, 0.58f, 1f};
        for (int i = 1; i < at.length; i++) {
            if (u <= at[i]) {
                float x = (u - at[i - 1]) / (at[i] - at[i - 1]);
                x = x * x * (3 - 2 * x);
                return to[i - 1] + (to[i] - to[i - 1]) * x;
            }
        }
        return 1f;
    }

    private String prepPhrase(int i) {
        String city = Prefs.city(), name = Prefs.name();
        switch (i) {
            case 0: return T("Organizando seus apps…", "Arranging your apps…", "Acomodando tus apps…");
            case 1: return city.isEmpty() ? T("Buscando o clima da sua cidade…", "Fetching the weather for your city…", "Trayendo el clima de tu ciudad…")
                    : T("Buscando o clima de " + city + "…", "Fetching the weather for " + city + "…", "Trayendo el clima de " + city + "…");
            case 2: return name.isEmpty() ? T("Deixando tudo do seu jeito…", "Setting everything up your way…", "Dejando todo a tu gusto…")
                    : T("Colocando seu nome na porta…", "Putting your name on the door…", "Poniendo tu nombre en la puerta…");
            default: return T("Acendendo a aurora…", "Lighting up the aurora…", "Encendiendo la aurora…");
        }
    }

    /** "Preparing your TV" and "Your TV is ready": no panel, the logo breathing, a bar and a line of text, centered on the screen. */
    private void drawBare(Canvas c, int sid) {
        long now = SystemClock.uptimeMillis(), t = now - auto0;
        boolean ready = prepEnd != 0;
        float cx = w / 2f, y0 = 225 * k;   // the design: the block starts 150 under the top (1280 x 720)
        float ph = (now % 2600) / 2600f, breath = 0.875f + 0.125f * (float) Math.sin(ph * 2 * Math.PI);
        float sc = 1 + 0.04f * ((breath - 0.75f) / 0.25f), lh = 144 * k, ly = y0 + lh / 2f;   // the symbol, 144 tall, breathing
        c.save();
        c.scale(sc, sc, cx, ly);
        Logo.drawSymbol(c, cx - lh * Logo.ASPECT / 2f, y0, lh, -1, false, breath);
        c.restore();
        float titleBase = y0 + (144 + 39) * k + 60 * k;
        c.drawText(stepTitle(), cx, titleBase, prepTitle);
        float subBase = titleBase + (15 + 55) * k;   // the subtitle 15 under the title
        c.drawText(stepBody(), cx, subBase, prepSub);
        float barTop = subBase + (8 + 60) * k, bw = 780 * k, bh = 12 * k;
        float p = ready ? 1f : prepCurve(Math.min(1f, t / 4200f));
        if (!ready && !prepDone) p = Math.min(p, 0.9f);   // it waits at 90 % until the work is done
        fill.setShader(null);
        fill.setColor(0x24FFFFFF);
        tmp.set(cx - bw / 2, barTop, cx + bw / 2, barTop + bh);
        c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
        if (p > 0.005f) {
            tmp.set(cx - bw / 2, barTop, cx - bw / 2 + bw * p, barTop + bh);
            fill.setShader(new LinearGradient(cx - bw / 2, 0, cx + bw / 2, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            fill.setShader(null);
        }
        float phraseBase = barTop + bh + (18 + 22) * k;
        if (ready) {
            String msg = T("Tudo no lugar", "Everything in its place", "Todo en su lugar");
            float tw = prepPhrase.measureText(msg), ic = 30 * k;
            Symbols.draw(getContext(), c, "check", cx - tw / 2 - ic * 0.2f - 6 * k, phraseBase - 9 * k, ic, 0xFFFFFFFF);
            prepPhrase.setTextAlign(Paint.Align.LEFT);
            c.drawText(msg, cx - tw / 2 + ic * 0.5f, phraseBase, prepPhrase);
            prepPhrase.setTextAlign(Paint.Align.CENTER);
        } else {
            int idx = (int) Math.min(3, t / 1050);
            float age = t - idx * 1050L, e = curve(age / 380f);
            prepPhrase.setAlpha(Math.round(199 * e));
            c.drawText(prepPhrase(idx), cx, phraseBase + 10 * k * (1 - e), prepPhrase);
        }
    }

    /** One "Get to know Borealis" screen: the segments, the counter, the title, the text and the picture. */
    private void drawTour(Canvas c, float cx, float top) {
        long now = SystemClock.uptimeMillis();
        boolean auto = !screenReaderOn(getContext());
        float segW = 180 * k, segH = 9 * k, segGap = 12 * k, sx = cx - (4 * segW + 3 * segGap) / 2, sy = top + 42 * k;
        for (int i = 0; i < 4; i++) {
            tmp.set(sx + i * (segW + segGap), sy, sx + i * (segW + segGap) + segW, sy + segH);
            fill.setShader(null);
            fill.setColor(0x38FFFFFF);
            c.drawRoundRect(tmp, segH / 2, segH / 2, fill);
            float fillP = i < tour ? 1f : i == tour ? (auto ? Ui.clamp((now - tourAt) / 6500f) : 1f) : 0f;
            if (fillP > 0.005f) {
                RectF seg = new RectF(tmp.left, tmp.top, tmp.left + segW * fillP, tmp.bottom);
                fill.setShader(new LinearGradient(sx, 0, sx + 4 * segW + 3 * segGap, 0, Brand.accents(), null, Shader.TileMode.CLAMP));
                fill.setColor(0xFFFFFFFF);
                c.drawRoundRect(seg, segH / 2, segH / 2, fill);
                fill.setShader(null);
            }
        }
        c.drawText(T("CONHEÇA O BOREALIS · " + (tour + 1) + " DE 4", "GET TO KNOW BOREALIS · " + (tour + 1) + " OF 4", "CONOCE BOREALIS · " + (tour + 1) + " DE 4"),
                cx, top + 90 * k, tourLabel);
        float e = curve((now - tourAt) / 420f);
        int layer = e < 0.999f ? c.saveLayerAlpha(MX * k, top + 100 * k, (MX + MW) * k, top + 680 * k, Math.round(255 * e)) : -1;
        c.save();
        c.translate(0, 12 * k * (1 - e));
        c.drawText(tourTitle(tour), cx, top + 164 * k, tourTitleP);
        StaticLayout l = wrapped(tourBody(tour), 1080 * k, tourBodyP, 3);
        c.save();
        c.translate(cx - 540 * k, top + 190 * k);
        l.draw(c);
        c.restore();
        tmp.set(MX * k + 240 * k, top + 294 * k, MX * k + 240 * k + 960 * k, top + 294 * k + 360 * k);
        tourDemo.draw(c, getContext(), tmp, tour, now - tourAt, recsMode());
        c.restore();
        if (layer >= 0) c.restoreToCount(layer);
    }

    /** A title in Gloock 60; a very long one (in a warning) shrinks a little to stay inside the panel. */
    private void drawTitle(Canvas c, String text, float cx, float y) {
        float size = title.getTextSize(), max = (MW - 120) * k, wd = title.measureText(text);
        if (wd > max) title.setTextSize(size * max / wd);
        c.drawText(text, cx, y, title);
        title.setTextSize(size);
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
        frameDt = dt;
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
        moving |= tickAuto();
        ensureLayout();
        int sid = stepId();
        if (!confirm && sid == S_PREP) {   // no panel: the logo breathes over the background
            drawBare(c, sid);
            postInvalidateOnAnimation();
            return;
        }
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
        int layer2 = fading ? c.saveLayerAlpha(box.left, box.top, box.right, (MY + 715) * k, Math.round(255 * e)) : -1;
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

    private android.graphics.Path welcomeClipPath;

    /** The panel's rounded shape (for what the design crops at its corners). */
    private android.graphics.Path welcomeClip() {
        if (welcomeClipPath == null) welcomeClipPath = new android.graphics.Path();
        welcomeClipPath.reset();
        welcomeClipPath.addRoundRect(box, RADIUS * k, RADIUS * k, android.graphics.Path.Direction.CW);
        return welcomeClipPath;
    }

    /** Draws the step's own content; true while it is animating on its own (the greeting changing language). */
    private boolean drawContent(Canvas c) {
        float cx = w / 2f, top = MY * k;
        if (confirm && !warnPerm) {
            drawSkip(c, cx, top);
            return false;
        }
        if (confirm) {
            drawTitle(c, stepTitle(), cx, top + 230 * k);
            drawWrapped(c, stepBody(), cx, top + 276 * k, 940 * k, body, 7);
            return false;
        }
        switch (stepId()) {
            case S_WELCOME: {
                // The design: two "curtains" at the back of the panel (cropped by its corners), then the vertical logo (symbol 186) from 105 under the top.
                float pl = MX * k;
                c.save();
                c.clipPath(welcomeClip());
                Logo.drawCurtains(c, pl + 915 * k, top + 225 * k, 1230 * k, 0.12f);
                Logo.drawCurtains(c, pl - 285 * k, top + 450 * k, 930 * k, 0.10f);
                c.restore();
                long lt = SystemClock.uptimeMillis() - stepAt;
                Logo.drawVertical(c, getContext(), cx, top + 105 * k, 186 * k, lt, reducedMotion, 1f);
                greet.setAlpha(Math.round(255 * tf));
                c.drawText(stepTitle(), cx, top + 520 * k, greet);
                greet.setAlpha(255);
                drawWrapped(c, stepBody(), cx, top + 556 * k, 900 * k, body, 1, tf);
                return lt < Logo.ENTRANCE_MS + 50;   // keep drawing while the lights rise
            }
            case S_LANG: {
                drawTitle(c, stepTitle(), cx, top + 150 * k);
                drawWrapped(c, stepBody(), cx, top + 186 * k, 1000 * k, body, 2);
                if (note == null || note.getTextSize() != 22.5f * k) {
                    note = Ui.text(Ui.medium, 22.5f * k, 0x99FFFFFF);
                    note.setTextAlign(Paint.Align.CENTER);
                }
                c.drawText(T("A tela inteira muda de idioma assim que você escolhe um.", "The whole screen changes language as soon as you pick one.",
                        "Toda la pantalla cambia de idioma en cuanto eliges uno."), cx, top + 636 * k, note);
                return false;
            }
            case S_NAME:
                if (pickingAvatar) {
                    drawTitle(c, stepTitle(), cx, top + 137 * k);
                    drawWrapped(c, stepBody(), cx, top + 168 * k, 900 * k, body, 1);
                    drawPills(c, cx, (MY + 735) * k, T("“OK” · Escolher", "“OK” · Choose", "“OK” · Elegir"), T("“Voltar” · Cancelar", "“Back” · Cancel", "“Atrás” · Cancelar"));
                    return false;
                }
                drawTitle(c, stepTitle(), cx, top + 137 * k);
                drawWrapped(c, stepBody(), cx, top + 168 * k, 900 * k, body, 1);
                if (note == null || note.getTextSize() != 22.5f * k) {
                    note = Ui.text(Ui.medium, 22.5f * k, 0x99FFFFFF);
                    note.setTextAlign(Paint.Align.CENTER);
                }
                note.setColor(0xB3FFFFFF);
                String nm = Prefs.name().isEmpty() ? T("seu nome", "your name", "tu nombre") : Prefs.name();
                boolean none = Prefs.name().isEmpty();   // the Home's own greeting
                c.drawText(T("Assim a tela inicial te cumprimenta: «" + (none ? "Olá!" : "Olá, " + nm + "!") + "»", "This is how the home screen greets you: “" + (none ? "Hi!" : "Hi, " + nm + "!") + "”",
                        "Así te saluda el Home: «" + (none ? "¡Hola!" : "¡Hola, " + nm + "!") + "»"), cx, top + 622 * k, note);
                note.setColor(0x99FFFFFF);
                return false;
            case S_PLACE: {
                drawTitle(c, stepTitle(), cx, top + 137 * k);
                drawWrapped(c, stepBody(), cx, top + 168 * k, 1110 * k, body, 2);
                tmp.set(cx - 225 * k, top + 273 * k, cx + 225 * k, top + 474 * k);   // the same card as in the news
                placeWidget.advance(frameDt);
                placeWidget.draw(c, getContext(), tmp, 1f, form.widgetCity(), form.widgetSymbol(), form.widgetTemp());
                return true;
            }
            case S_HOME:
                drawTitle(c, stepTitle(), cx, top + 137 * k);
                drawWrapped(c, stepBody(), cx, top + 168 * k, 1110 * k, body, 2);
                return false;
            case S_PERM: {
                drawTitle(c, stepTitle(), cx, top + 137 * k);
                drawWrapped(c, stepBody(), cx, top + 168 * k, 1100 * k, body, 2);
                // The picture: the home screen with "Continue watching", and the bell for the notification access.
                RectF pic = new RectF(cx - 240 * k, top + 268 * k, cx + 240 * k, top + 488 * k);
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
                        : T("Ao ativar, a TV abrirá uma lista: procure “Borealis”, ative-o e volte com “Voltar”.",
                        "When you turn it on, the TV opens a list: find “Borealis”, switch it on and come back with “Back”.",
                        "Al activarlo, la TV abrirá una lista: busca “Borealis”, actívalo y vuelve con “Atrás”.");
                drawWrapped(c, msg, cx, top + 636 * k, 900 * k, hint, 2);
                return false;
            }
            case S_TOUR:
                drawTour(c, cx, top);
                return true;
            default:
                drawSupport(c, cx, top);
                return true;
        }
    }

    private Paint skipT, skipV;

    /** "Shall we leave it for later?": what stays as it comes from the factory, in three cards. */
    private void drawSkip(Canvas c, float cx, float top) {
        if (skipT == null || skipT.getTextSize() != 25.5f * k) {
            skipT = Ui.text(Ui.bold, 25.5f * k, 0xFFFFFFFF);
            skipT.setTextAlign(Paint.Align.CENTER);
            skipV = Ui.text(Ui.medium, 21 * k, 0xA8FFFFFF);
            skipV.setTextAlign(Paint.Align.CENTER);
        }
        if (note == null || note.getTextSize() != 22.5f * k) {
            note = Ui.text(Ui.medium, 22.5f * k, 0x99FFFFFF);
            note.setTextAlign(Paint.Align.CENTER);
        }
        drawTitle(c, stepTitle(), cx, top + 146 * k);
        drawWrapped(c, stepBody(), cx, top + 183 * k, 990 * k, body, 2);
        String[] t = {T("Seu nome", "Your name", "Tu nombre"), T("O clima", "The weather", "El clima"), T("Continuar assistindo", "Continue watching", "Continuar viendo")};
        String[] v = {T("Sem nome nem avatar", "No name or avatar", "Sin nombre ni avatar"), T("Sem cidade", "No city", "Sin ciudad"), T("Desativado", "Off", "Apagado")};
        int[] tint = {Brand.LILY, Brand.PEACH, Brand.MELON};
        float cw = 294 * k, ch = 225 * k, gap = 27 * k, x0 = cx - (3 * cw + 2 * gap) / 2, y = top + 294 * k;
        for (int i = 0; i < 3; i++) {
            float x = x0 + i * (cw + gap);
            tmp.set(x, y, x + cw, y + ch);
            fill.setShader(null);
            fill.setColor(0x12FFFFFF);
            c.drawRoundRect(tmp, 30 * k, 30 * k, fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f * k);
            line.setColor(0x1AFFFFFF);
            tmp2.set(tmp.left + 0.75f * k, tmp.top + 0.75f * k, tmp.right - 0.75f * k, tmp.bottom - 0.75f * k);
            c.drawRoundRect(tmp2, 29 * k, 29 * k, line);
            float ccx = tmp.centerX(), ccy = y + 72 * k;
            fill.setColor(tint[i]);
            c.drawCircle(ccx, ccy, 39 * k, fill);
            if (i == 0) {   // a person: head and shoulders
                line.setStrokeWidth(3.3f * k);
                line.setStrokeCap(Paint.Cap.ROUND);
                line.setColor(Brand.INK);
                float u = 36 * k / 24f;
                c.drawCircle(ccx, ccy - 3.5f * u, 3.8f * u, line);
                clip.rewind();
                clip.moveTo(ccx - 7.5f * u, ccy + 8 * u);
                clip.cubicTo(ccx - 6.3f * u, ccy + 4.2f * u, ccx - 3.5f * u, ccy + 2.5f * u, ccx, ccy + 2.5f * u);
                clip.cubicTo(ccx + 3.5f * u, ccy + 2.5f * u, ccx + 6.3f * u, ccy + 4.2f * u, ccx + 7.5f * u, ccy + 8 * u);
                c.drawPath(clip, line);
                line.setStrokeCap(Paint.Cap.BUTT);
            } else {
                Symbols.draw(getContext(), c, i == 1 ? "sunny" : "play", ccx, ccy, 34 * k, Brand.INK);
            }
            line.setStyle(Paint.Style.FILL);
            c.drawText(t[i], ccx, y + 146 * k, skipT);
            c.drawText(v[i], ccx, y + 186 * k, skipV);
        }
        c.drawText(T("Volte quando quiser em Ajustes › Preferências › Repetir boas-vindas.", "Come back any time from Settings › Preferences › Repeat welcome.",
                "Vuelve cuando quieras desde Ajustes › Preferencias › Repetir bienvenida."), cx, top + 578 * k, note);
    }

    /** Two key hints side by side (54 high, white 8%), centered at `cx`. */
    private void drawPills(Canvas c, float cx, float top, String a, String b) {
        if (pillText == null || pillText.getTextSize() != 21 * k) pillText = Ui.text(Ui.semibold, 21 * k, 0xCCFFFFFF);
        float ph = 54 * k, gap = 18 * k, wa = pillText.measureText(a) + 48 * k, wb = pillText.measureText(b) + 48 * k;
        float x = cx - (wa + gap + wb) / 2;
        fill.setShader(null);
        fill.setColor(0x14FFFFFF);
        tmp.set(x, top, x + wa, top + ph);
        c.drawRoundRect(tmp, ph / 2, ph / 2, fill);
        c.drawText(a, x + 24 * k, top + ph / 2 + Ui.halfCap(pillText), pillText);
        x += wa + gap;
        tmp.set(x, top, x + wb, top + ph);
        c.drawRoundRect(tmp, ph / 2, ph / 2, fill);
        c.drawText(b, x + 24 * k, top + ph / 2 + Ui.halfCap(pillText), pillText);
    }

    private Paint rowT, rowD, tagP, urlP, note, fieldLabel, fieldText, fieldHint, pillText;
    private final android.graphics.Path heartPath = new android.graphics.Path();

    private void heart(Canvas c, float hx, float hy, float size, int color) {
        if (heartPath.isEmpty()) {
            heartPath.moveTo(0, 0.9f);
            heartPath.cubicTo(-1.2f, 0, -0.9f, -0.9f, 0, -0.35f);
            heartPath.cubicTo(0.9f, -0.9f, 1.2f, 0, 0, 0.9f);
            heartPath.close();
        }
        fill.setShader(null);
        fill.setColor(color);
        c.save();
        c.translate(hx, hy);
        c.scale(size, size);
        c.drawPath(heartPath, fill);
        c.restore();
    }

    /** "Apoya a Borealis", the last step: the code in a ring (with hearts rising) and three reasons, the way the Settings panel shows it. */
    private void drawSupport(Canvas c, float cx, float top) {
        if (rowT == null || rowT.getTextSize() != 29 * k) {
            rowT = Ui.text(Ui.semibold, 29 * k, 0xFFFFFFFF);
            rowD = Ui.text(Ui.medium, 23 * k, 0xD1FFFFFF);
            tagP = Ui.text(Ui.semibold, 21 * k, 0xB3FFFFFF);
            tagP.setTextAlign(Paint.Align.CENTER);
            urlP = Ui.text(Ui.semibold, 29 * k, 0xFFFFFFFF);
            urlP.setTextAlign(Paint.Align.CENTER);
        }
        float t = SystemClock.uptimeMillis() / 1000f;
        int[] tint = {Brand.LILY, Brand.PEACH, Brand.MELON};
        String ttl = stepTitle();
        float tw = title.measureText(ttl), beat = 1 + 0.1f * (float) Math.sin(t * 4.5f);
        heart(c, cx + 28 * k - tw / 2 - 34 * k, top + 121 * k, 22 * k * beat, Brand.PEACH);
        c.drawText(ttl, cx + 28 * k, top + 137 * k, title);
        drawWrapped(c, stepBody(), cx, top + 168 * k, 1100 * k, body, 1);

        float qs = 270 * k, qy = top + 280 * k;
        // the whole group (code column + gap + reasons) centered on the modal
        float leftW = Math.max(qs + 24 * k, urlP.measureText("github.com/sponsors/Alogus")), gapW = 90 * k, rw = 570 * k;
        float gl = cx - (leftW + gapW + rw) / 2, qcx = gl + leftW / 2, qx = qcx - qs / 2;
        // a soft glow behind the code, and hearts rising around it
        float gr = 250 * k * (0.9f + 0.1f * (float) Math.sin(t * 2.2f));
        fill.setShader(new RadialGradient(qcx, qy + qs / 2, gr, Ui.alpha(Brand.LILY, 0.34f), 0x00B79CFE, Shader.TileMode.CLAMP));
        c.drawCircle(qcx, qy + qs / 2, gr, fill);
        fill.setShader(null);
        for (int h = 0; h < 10; h++) {
            float ph = (t * 0.3f + h / 10f) % 1f;
            float hx = qcx + (float) Math.sin(h * 7.3) * qs * 0.62f + (float) Math.sin(t * 1.4f + h) * 14 * k;
            float hy = qy + qs + 20 * k - ph * (qs + 100 * k);
            heart(c, hx, hy, (9 + (h % 3) * 4) * k, Ui.alpha(tint[h % 3], (float) Math.sin(Math.PI * ph) * 0.85f));
        }
        // the code, on white, inside the gradient ring
        tmp.set(qx - 12 * k, qy - 12 * k, qx + qs + 12 * k, qy + qs + 12 * k);
        fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(tmp, 42 * k, 42 * k, fill);
        drawRing(c, tmp, 42 * k, 8 * k, 1f);
        String[] qr = NoticeView.SPONSOR_QR;
        float cell = (qs - 36 * k) / qr.length, ox = qx + 18 * k, oy = qy + 18 * k;
        fill.setColor(Brand.INK);
        for (int y = 0; y < qr.length; y++)
            for (int x = 0; x < qr[y].length(); x++)
                if (qr[y].charAt(x) == '1') c.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell + 0.5f, oy + (y + 1) * cell + 0.5f, fill);
        c.drawText("GITHUB SPONSORS", qcx, qy + qs + 62 * k, tagP);
        c.drawText("github.com/sponsors/Alogus", qcx, qy + qs + 106 * k, urlP);

        // three reasons
        String[] titles = {T("É voluntário", "It is voluntary", "Es voluntario"), T("Não libera nada", "It unlocks nothing", "No desbloquea nada"), T("Ajuda a continuar", "It keeps it going", "Ayuda a que siga")};
        String[] descs = {T("Você decide se apoia e quanto.", "You decide if you help and how much.", "Tú decides si apoyar y cuánto."),
                T("O Borealis é igual para todos.", "Borealis is the same for everyone.", "Borealis es igual para todos."),
                T("Tempo e melhorias para o Borealis.", "Time and improvements for Borealis.", "Tiempo y mejoras para Borealis.")};
        float rx = gl + leftW + gapW, rh = 112 * k;
        for (int i = 0; i < 3; i++) {
            float ry = qy - 12 * k + i * (rh + 22 * k);
            tmp.set(rx, ry, rx + rw, ry + rh);
            fill.setColor(0x14FFFFFF);
            c.drawRoundRect(tmp, 32 * k, 32 * k, fill);
            float icx = rx + 62 * k, icy = ry + rh / 2;
            fill.setColor(tint[i]);
            c.drawCircle(icx, icy, 37 * k, fill);
            if (i == 0) heart(c, icx, icy + k, 15 * k * (1 + 0.1f * (float) Math.sin(t * 4.5f)), Brand.INK);
            else Symbols.draw(getContext(), c, i == 1 ? "check" : "sparkles", icx, icy, 32 * k, Brand.INK);
            c.drawText(titles[i], rx + 126 * k, icy - 6 * k, rowT);
            c.drawText(descs[i], rx + 126 * k, icy + 32 * k, rowD);
        }
    }

    /** The progress dots at the top: the current step is a gradient pill, the ones done are solid, the rest dim. */
    private void drawDots(Canvas c) {
        int n = dotSteps().length, step = Math.max(0, dotPos());
        if (dotPos() < 0) return;
        float cy = (MY + 52.5f) * k, r = 7.5f * k, gap = 21 * k, pill = 39 * k;
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
                fill.setColor(i < step ? 0xD9FFFFFF : 0x4DFFFFFF);
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
                    buttonB.setColor(0x66FFFFFF);
                } else {
                    fill.setShader(gradient(r));
                    fill.setColor(0xFFFFFFFF);
                    c.drawRoundRect(r, rad, rad, fill);
                    fill.setShader(null);
                    if (f > 0.01f) drawRing(c, r, rad, 4.5f * k, f, true);
                    buttonB.setColor(Brand.INK);
                }
                buttonB.setAlpha(Math.round(buttonB.getAlpha() * tf));
                c.drawText(e.label, r.centerX(), r.centerY() + Ui.halfCap(buttonB), buttonB);
                buttonB.setColor(0xFFFFFFFF);
                break;
            case BTN:
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.08f + 0.14f * f));
                c.drawRoundRect(r, rad, rad, fill);
                if (f > 0.01f) drawRing(c, r, rad, 4.5f * k, f);
                else {
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(2 * k);
                    line.setColor(0x4DFFFFFF);
                    tmp.set(r.left + k, r.top + k, r.right - k, r.bottom - k);
                    c.drawRoundRect(tmp, rad - k, rad - k, line);
                    line.setStyle(Paint.Style.FILL);
                }
                buttonS.setColor(Ui.alpha(0xFFFFFFFF, 0.78f + 0.22f * f));
                c.drawText(e.label, r.centerX(), r.centerY() + Ui.halfCap(buttonS), buttonS);
                buttonS.setColor(0xFFFFFFFF);
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
                fill.setColor(Ui.alpha(0xFFFFFFFF, 0.09f));
                c.drawRoundRect(r, rr, rr, fill);
                if (f > 0.01f) {
                    fill.setColor(Ui.alpha(0xFF26212B, f));
                    c.drawRoundRect(r, rr, rr, fill);
                    drawRing(c, r, rr, 4.5f * k, f);
                }
                float fr = 50 * k, fcy = r.top + 40 * k + fr;
                Avatars.drawFlag(c, getContext(), e.idx, r.centerX(), fcy, fr);
                if (e.selected) {   // the chosen one: the Lily check badge, as in Settings
                    float br = 22.5f * k, bx = r.right - 34 * k, by = r.top + 34 * k;
                    fill.setColor(Brand.ink(0xFF));
                    c.drawCircle(bx, by, br, fill);
                    fill.setColor(Brand.LILY);
                    c.drawCircle(bx, by, br - 4.5f * k, fill);
                    Symbols.draw(getContext(), c, "check", bx, by, 24 * k, Brand.INK);
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
                if (e.selected) {   // the current one: a white border and the Lily check badge
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(4.5f * k);
                    line.setColor(0xFFFFFFFF);
                    c.drawCircle(r.centerX(), r.centerY(), ar - 2.25f * k, line);
                    line.setStyle(Paint.Style.FILL);
                    float br = 17 * k, bx = r.right - 9 * k, by = r.bottom - 9 * k;
                    fill.setShader(null);
                    fill.setColor(Brand.ink(0xFF));
                    c.drawCircle(bx, by, br, fill);
                    fill.setColor(Brand.LILY);
                    c.drawCircle(bx, by, br - 3.5f * k, fill);
                    Symbols.draw(getContext(), c, "check", bx, by, 18 * k, Brand.INK);
                }
                if (f > 0.01f) {   // the focus: the gradient ring, with air around the avatar
                    float air = 9 * k;
                    tmp2.set(r.left - air, r.top - air, r.right + air, r.bottom + air);
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
                float bx = r.right - 20 * k, by = r.bottom - 20 * k, br = 30 * k;
                fill.setShader(null);
                fill.setColor(0xFFFFFFFF);   // the pencil: a white circle with a Night border (not the gradient)
                c.drawCircle(bx, by, br, fill);
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(4.5f * k);
                line.setColor(Brand.ink(0xFF));
                c.drawCircle(bx, by, br, line);
                line.setStyle(Paint.Style.FILL);
                drawPencil(c, bx, by, 36 * k, Brand.INK);
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
                c.drawText(e.selected ? T("Escolhida", "Chosen", "Elegida") : T("Escolher", "Choose", "Elegir"), cxm + cr + 14 * k, cym + Ui.halfCap(small), small);
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
            case FIELD: {   // "YOUR NAME" and the field under it, as in My profile
                float rr = 27 * k;
                if (fieldLabel == null || fieldLabel.getTextSize() != 19.5f * k) {
                    fieldLabel = Ui.text(Ui.bold, 19.5f * k, 0x99FFFFFF);
                    fieldLabel.setLetterSpacing(0.08f);
                    fieldLabel.setTextAlign(Paint.Align.CENTER);
                    fieldText = Ui.text(Ui.semibold, 33 * k, 0xFFFFFFFF);
                    fieldHint = Ui.text(Ui.semibold, 21 * k, 0xB3FFFFFF);
                    fieldHint.setTextAlign(Paint.Align.RIGHT);
                }
                c.drawText(T("SEU NOME", "YOUR NAME", "TU NOMBRE"), e.r.centerX(), e.r.top - 20 * k, fieldLabel);
                fill.setShader(null);
                fill.setColor(f > 0.01f ? 0xFF26212B : 0xFF211D26);
                c.drawRoundRect(e.r, rr, rr, fill);
                if (f > 0.01f) drawRing(c, e.r, rr, 4.5f * k, f);
                else {
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(4.5f * k);
                    line.setColor(0x29FFFFFF);
                    tmp.set(e.r.left + 2.25f * k, e.r.top + 2.25f * k, e.r.right - 2.25f * k, e.r.bottom - 2.25f * k);
                    c.drawRoundRect(tmp, rr - 2.25f * k, rr - 2.25f * k, line);
                    line.setStyle(Paint.Style.FILL);
                }
                boolean empty = Prefs.name().isEmpty();
                fieldText.setColor(empty ? 0x80FFFFFF : 0xFFFFFFFF);
                c.drawText(e.value, e.r.left + 36 * k, e.r.centerY() + Ui.halfCap(fieldText), fieldText);
                if (f > 0.01f) {
                    fieldHint.setAlpha(Math.round(179 * f));
                    c.drawText(T("“OK” · Editar", "“OK” · Edit", "“OK” · Editar"), e.r.right - 30 * k, e.r.centerY() + Ui.halfCap(fieldHint), fieldHint);
                }
                break;
            }
            case FORM:
                form.draw(c, k, MX + MW / 2, MY + 501, index == focus && !confirm, frameDt, true);
                break;
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

    private android.graphics.DashPathEffect previewDash;
    private static Bitmap miniWall;
    private static boolean miniLoading;

    /** The wallpaper the person has now, small, for the miniatures of the home screen (decoded once, off the main thread). */
    private void loadMiniWall() {
        if (miniWall != null || miniLoading) return;
        miniLoading = true;
        final Context app = getContext().getApplicationContext();
        new Thread(() -> {
            try (java.io.InputStream in = app.getAssets().open(Prefs.WALLPAPER_FILES[Math.max(0, Math.min(Prefs.WALLPAPER_FILES.length - 1, Prefs.wallpaper()))])) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 4;
                miniWall = android.graphics.BitmapFactory.decodeStream(in, null, o);
            } catch (Exception | OutOfMemoryError ex) {
                AppLog.w("miniature wallpaper: " + ex);
            }
            miniLoading = false;
            postInvalidate();
        }, "onboarding-wall").start();
    }

    /** A small picture of the new home screen with that choice: greeting, cards (a progress bar, or tabs and posters), and the pinned apps ending in a "+". */
    private void drawPreview(Canvas c, RectF r, boolean recent) {
        float rr = 16 * k;
        fill.setShader(null);
        fill.setColor(0xFF211C2B);
        c.drawRoundRect(r, rr, rr, fill);
        c.save();
        clip.rewind();
        clip.addRoundRect(r, rr, rr, Path.Direction.CW);
        c.clipPath(clip);
        Bitmap wb = miniWall;
        if (wb != null) {   // the real wallpaper (center-cropped), with a scrim from 15 % at the top to 55 % at the bottom
            float sc = Math.max(r.width() / wb.getWidth(), r.height() / wb.getHeight());
            float bw = wb.getWidth() * sc, bh = wb.getHeight() * sc;
            tmp2.set(r.centerX() - bw / 2, r.centerY() - bh / 2, r.centerX() + bw / 2, r.centerY() + bh / 2);
            c.drawBitmap(wb, null, tmp2, null);
            fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[] {Ui.alpha(Brand.INK, 0.15f), Ui.alpha(Brand.INK, 0.55f)}, null, Shader.TileMode.CLAMP));
            c.drawRect(r, fill);
            fill.setShader(null);
        } else {
            fill.setColor(0x1AB79CFE);
            c.drawCircle(r.right - 40 * k, r.top + 30 * k, 130 * k, fill);
            fill.setColor(0x14FE9565);
            c.drawCircle(r.left + 60 * k, r.bottom, 120 * k, fill);
        }
        c.restore();
        float pad = 22 * k, x = r.left + pad, y = r.top + 18 * k;
        // the avatar and the greeting (as lines), and the pill at the right
        fill.setColor(Ui.alpha(Brand.LILY, 0.85f));
        c.drawCircle(x + 14 * k, y + 14 * k, 14 * k, fill);
        fill.setColor(0x80FFFFFF);
        tmp.set(x + 38 * k, y + 4 * k, x + 98 * k, y + 10 * k);
        c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
        fill.setColor(0xE6FFFFFF);
        tmp.set(x + 38 * k, y + 16 * k, x + 168 * k, y + 26 * k);
        c.drawRoundRect(tmp, 5 * k, 5 * k, fill);
        fill.setColor(0x33FFFFFF);
        tmp.set(r.right - pad - 74 * k, y + 6 * k, r.right - pad, y + 24 * k);
        c.drawRoundRect(tmp, 9 * k, 9 * k, fill);
        float ry = y + 44 * k;
        int[] tint = {Brand.LILY, Brand.PEACH, Brand.MELON, Brand.LILY, Brand.PEACH};
        if (recent) {
            // a section line, then three wide cards with a progress bar
            fill.setColor(0xB3FFFFFF);
            tmp.set(x, ry, x + 96 * k, ry + 8 * k);
            c.drawRoundRect(tmp, 4 * k, 4 * k, fill);
            float cw = (r.width() - 2 * pad - 2 * 14 * k) / 3, ch = 56 * k, cy0 = ry + 20 * k;
            for (int i = 0; i < 3; i++) {
                float cx0 = x + i * (cw + 14 * k);
                tmp.set(cx0, cy0, cx0 + cw, cy0 + ch);
                fill.setColor(Ui.alpha(tint[i], 0.32f));
                c.drawRoundRect(tmp, 9 * k, 9 * k, fill);
                fill.setColor(0x33FFFFFF);
                tmp2.set(cx0 + 9 * k, cy0 + ch - 13 * k, cx0 + cw - 9 * k, cy0 + ch - 8 * k);
                c.drawRoundRect(tmp2, 2.5f * k, 2.5f * k, fill);
                fill.setColor(0xFFFFFFFF);
                tmp2.set(cx0 + 9 * k, cy0 + ch - 13 * k, cx0 + 9 * k + (cw - 18 * k) * (0.3f + 0.25f * i), cy0 + ch - 8 * k);
                c.drawRoundRect(tmp2, 2.5f * k, 2.5f * k, fill);
            }
        } else {
            // chips (the first one selected), then five posters
            float tx = x;
            for (int i = 0; i < 3; i++) {
                float tw = 50 * k;
                tmp.set(tx, ry, tx + tw, ry + 16 * k);
                if (i == 0) {
                    fill.setShader(gradient(tmp));
                    fill.setColor(0xFFFFFFFF);
                } else {
                    fill.setColor(0x33FFFFFF);
                }
                c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
                fill.setShader(null);
                tx += tw + 10 * k;
            }
            float pw = (r.width() - 2 * pad - 4 * 10 * k) / 5, ph = 62 * k, py = ry + 26 * k;
            for (int i = 0; i < 5; i++) {
                float px = x + i * (pw + 10 * k);
                tmp.set(px, py, px + pw, py + ph);
                fill.setColor(Ui.alpha(tint[i], 0.30f));
                c.drawRoundRect(tmp, 8 * k, 8 * k, fill);
            }
        }
        // the pinned apps: five tiles and the dashed "+" of the next free place
        float th = 30 * k, tw = th / 0.5625f, ty = r.bottom - 18 * k - th, gap = 10 * k;
        for (int i = 0; i < 5; i++) {
            float tx0 = x + i * (tw + gap);
            if (tx0 + tw > r.right - pad) break;
            tmp.set(tx0, ty, tx0 + tw, ty + th);
            fill.setColor(Ui.alpha(tint[(i + 1) % 5], 0.42f));
            c.drawRoundRect(tmp, 7 * k, 7 * k, fill);
        }
        float px = x + 5 * (tw + gap);
        if (px + tw <= r.right - pad + 1) {
            if (previewDash == null) previewDash = new android.graphics.DashPathEffect(new float[] {5 * k, 4 * k}, 0);
            tmp.set(px, ty, px + tw, ty + th);
            tmp2.set(tmp.left + k, tmp.top + k, tmp.right - k, tmp.bottom - k);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2 * k);
            line.setPathEffect(previewDash);
            line.setColor(Brand.LILY);
            c.drawRoundRect(tmp2, 7 * k, 7 * k, line);
            line.setPathEffect(null);
            c.drawLine(tmp.centerX() - 5 * k, tmp.centerY(), tmp.centerX() + 5 * k, tmp.centerY(), line);
            c.drawLine(tmp.centerX(), tmp.centerY() - 5 * k, tmp.centerX(), tmp.centerY() + 5 * k, line);
            line.setStyle(Paint.Style.FILL);
        }
    }
}
