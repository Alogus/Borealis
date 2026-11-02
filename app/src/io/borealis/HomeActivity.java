package io.borealis;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The Borealis home screen (Android's "Home" role in Borealis mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity implements TextEntry.Host {
    /** True while the home screen is the one on top (the settings use it to know where a fade-out lands). */
    static volatile boolean resumed;
    private HomeView home;
    private SplashView splash;
    private OnboardingView onboarding;
    private NoticeView notice;
    private TextEntryView entry;   // Borealis's keyboard (the welcome asks the name, the country and the city with it)
    private FrameLayout root;
    static final String EXTRA_UPDATED = "io.borealis.UPDATED";
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Diag.event("HomeActivity.onCreate " + Diag.memory());
        Diag.monitor();
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("O Início do Borealis teve um problema.\nVoltando para o Início original da TV…",
                    "Borealis's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        HomeMode.clearStaleBusy(this);
        Backdrop.preparePanel(this);   // the blurred backdrop of the keyboard, ready before it opens
        home = new HomeView(this);
        root = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        fakeUpdate(getIntent());
        showWelcomeIfNeeded();   // under the opening, so it appears once the opening is over
        boolean updated = getIntent() != null && getIntent().getBooleanExtra(EXTRA_UPDATED, false);
        if (updated) getSharedPreferences("splash", MODE_PRIVATE).edit().putLong("at", android.os.SystemClock.elapsedRealtime()).apply();
        if (updated || (savedInstanceState == null && coldStart())) playSplash();
        setContentView(root);
        getWindow().setBackgroundDrawable(null);
    }

    /** The welcome (first time, or asked for in Settings): a modal over the home screen. */
    private void showWelcomeIfNeeded() {
        if (root == null || onboarding != null || !OnboardingView.pending(this)) return;
        final OnboardingBackdrop backdrop = new OnboardingBackdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        final OnboardingView mine = new OnboardingView(this, welcome -> {
            // The special opening goes in front first, so there is no flash between the welcome and it.
            if (welcome && root != null) {
                if (splash != null) { root.removeView(splash); splash = null; }
                newSplash(false, true);
            }
            if (root != null) { root.removeView(onboarding); root.removeView(backdrop); }
            onboarding = null;
            markNoticeSeen();   // the welcome already told the news of this version
            if (home != null) home.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (home != null) home.resume();
        });
        onboarding = mine;
        if (home != null) home.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.addView(mine, new FrameLayout.LayoutParams(-1, -1));
        if (splash != null) splash.bringToFront();
    }

    /** Borealis's keyboard (see TextEntry), over the home screen and the welcome. */
    @Override public void askText(TextEntry.Request r) {
        if (root == null || entry != null) return;
        final TextEntryView[] mine = new TextEntryView[1];
        mine[0] = new TextEntryView(this, r, () -> {
            if (root != null && mine[0] != null) root.removeView(mine[0]);
            if (entry == mine[0]) entry = null;
        });
        entry = mine[0];
        root.addView(entry, new FrameLayout.LayoutParams(-1, -1));
        if (splash != null) splash.bringToFront();
    }

    /** For testing the bell: `adb shell am start -n io.borealis/.HomeActivity --es fakeupdate 9.9.9` ("" clears it); `--ez fakenews true` shows the news again. */
    private void fakeUpdate(Intent intent) {
        // For screenshots: `--ez fakenews true` forgets which news were seen, so the modals come up again.
        if (intent != null && intent.getBooleanExtra("fakenews", false)) {
            getSharedPreferences("notice", MODE_PRIVATE).edit().remove("ids").remove("news").remove("seen").apply();
        }
        if (intent == null || !intent.hasExtra("fakeupdate")) return;
        String v = intent.getStringExtra("fakeupdate");
        Prefs.setUpdateFound(v == null ? "" : v);
        Prefs.setUpdateApk("", "");
        Prefs.setUpdateAt(System.currentTimeMillis());   // so the next check does not undo it for a day
    }

    /** The news the modal tells, in order. Each is shown once, by id; add a new id at the end for news worth telling. */
    private static final String[] NEWS = {"shelf-menu", "fixes-3.45.2", "weather-city"};   // "downloads-1000" (showMilestone) waits for the notification service

    private java.util.Set<String> seenNews() {
        android.content.SharedPreferences sp = getSharedPreferences("notice", MODE_PRIVATE);
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String id : sp.getString("ids", "").split(",")) if (!id.isEmpty()) out.add(id);
        if (NEWS[0].equals(sp.getString("news", ""))) out.add(NEWS[0]);   // from the versions that kept a single id
        return out;
    }

    private void markNewsSeen(String... ids) {
        java.util.Set<String> seen = seenNews();
        for (String id : ids) seen.add(id);
        getSharedPreferences("notice", MODE_PRIVATE).edit().putString("ids", android.text.TextUtils.join(",", seen))
                .putString("seen", Update.installed(this)).apply();
    }

    private void markNoticeSeen() { markNewsSeen(NEWS); }

    /** A modal over the home screen (see NoticeView). Does nothing while another one or the welcome is up. */
    void showNotice(boolean picture, String title, String body, String yes, Runnable onYes, String no, Runnable onNo) {
        if (root == null || notice != null || onboarding != null) return;
        final NoticeView[] mine = new NoticeView[1];
        mine[0] = new NoticeView(this, picture, title, body, yes, onYes, no, onNo, () -> {
            if (root != null && mine[0] != null) root.removeView(mine[0]);
            if (notice == mine[0]) notice = null;
            if (home != null) home.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        });
        notice = mine[0];
        if (home != null) home.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.addView(notice, new FrameLayout.LayoutParams(-1, -1));
        if (splash != null) splash.bringToFront();
    }

    /** What's new: the first news not seen yet, one at a time (the next one comes up when this closes). */
    private void showWhatsNewIfNeeded() {
        if (root == null || notice != null || onboarding != null || OnboardingView.pending(this)) return;
        if (splash != null && (splash.active() || splash.waiting())) return;
        String now = Update.installed(this);
        if (now.isEmpty()) return;
        android.content.SharedPreferences sp = getSharedPreferences("notice", MODE_PRIVATE);
        String old = sp.getString("seen", "");   // from the versions that remembered it by version: seen from 3.44.0 on means seen
        if (!old.isEmpty() && !Update.isNewer("3.44.0", old)) markNewsSeen(NEWS[0]);
        java.util.Set<String> seen = seenNews();
        for (final String id : NEWS) {
            if (seen.contains(id)) continue;
            showNewsEntry(id, false, this::showWhatsNewIfNeeded);
            return;
        }
    }

    /** The bell with nothing to update: the news again, the newest first (the history). */
    void showNewsHistory() {
        showHistoryFrom(NEWS.length - 1);
    }

    private void showHistoryFrom(final int i) {
        if (i < 0) return;
        showNewsEntry(NEWS[i], true, () -> showHistoryFrom(i - 1));
    }

    /** One news as a modal. `after` runs once it is closed; when it is not a replay, the news counts as seen. */
    private void showNewsEntry(final String id, final boolean history, final Runnable after) {
        String now = Update.installed(this);
        if (id.equals("downloads-1000")) {
            showMilestone(id, history, after);
            return;
        }
        if (id.equals("weather-city")) {
            showWeatherNews(id, history, after);
            return;
        }
        String title = history
                ? Ui.tr("Novidades · Versão ", "What's new · Version ", "Novedades · Versión ") + now
                : Ui.tr("Nova atualização · Versão ", "New update · Version ", "Nueva actualización · Versión ") + now;
        final String yes = Ui.tr("Entendi", "Got it", "Entendido");
        boolean shelf = id.equals("shelf-menu");
        String body = shelf
                ? Ui.tr("Personalize o seu estante de apps. ", "Make your app shelf yours. ", "Personaliza tu estante de apps. ")
                    + Ui.tr("Segure OK sobre um app do estante para movê-lo, removê-lo ou ocultá-lo. Os apps ocultos ficam no fim de Todos os apps, e de lá você pode mostrá-los de novo.",
                    "Hold OK on a shelf app to move it, remove it or hide it. Hidden apps wait at the end of All apps, and you can bring them back from there.",
                    "Mantén OK sobre una app del estante para moverla, quitarla u ocultarla. Las apps ocultas quedan al final de Todas las apps y desde ahí puedes volver a mostrarlas.")
                : Ui.tr("Corrigimos pequenos bugs e problemas de compatibilidade. Agora você também pode silenciar o som dos cliques em Ajustes.",
                    "We fixed minor bugs and compatibility issues. You can now also mute the click sounds in Settings.",
                    "Se repararon bugs y problemas de compatibilidad menores. Ahora también puedes silenciar el sonido de los clics en Ajustes.");
        showNotice(shelf, title, body, yes, () -> {
            if (!history) markNewsSeen(id);
            if (after != null) after.run();
        }, null, null);
        if (!shelf && notice != null) notice.art(new String[] {"check", "tv", "speaker"},
                new String[] {Ui.tr("Bugs corrigidos", "Bugs fixed", "Bugs corregidos"), Ui.tr("Mais compatível", "More compatible", "Más compatible"), Ui.tr("Cliques opcionais", "Optional clicks", "Clics opcionales")},
                new int[] {Brand.LILY, Brand.PEACH, Brand.MELON});
    }

    /** The weather location: "Set up now" opens Settings straight at the country and city; "Later" tells where to find it. */
    private void showWeatherNews(final String id, final boolean history, final Runnable after) {
        final Runnable done = () -> {
            if (!history) markNewsSeen(id);
            if (after != null) after.run();
        };
        showNotice(false,
                Ui.tr("Agora você escolhe a sua cidade", "Now you can choose your city", "Ahora puedes elegir tu ciudad"),
                Ui.tr("O clima e o pôr do sol do Borealis agora seguem a cidade que você escolher. Configure agora ou depois em Ajustes > Tela principal > Localização do clima.",
                        "Borealis's weather and sunset now follow the city you pick. Set it up now, or later in Settings > Home screen > Weather location.",
                        "El clima y el atardecer de Borealis ahora siguen la ciudad que elijas. Configúralo ahora o después en Ajustes > Pantalla principal > Ubicación del clima."),
                Ui.tr("Configurar agora", "Set up now", "Configurar ahora"), () -> {
                    if (!history) markNewsSeen(id);
                    startActivity(new Intent(this, SettingsActivity.class).putExtra("place", true));
                },
                Ui.tr("Depois", "Later", "Más tarde"), done);
        if (notice != null) notice.weather();
    }

    /** The thank-you for the first 1,000 downloads: "Support" shows a QR code to the sponsors page, "Not now" closes. */
    private void showMilestone(final String id, final boolean history, final Runnable after) {
        final Runnable done = () -> {
            if (!history) markNewsSeen(id);
            if (after != null) after.run();
        };
        showNotice(false,
                Ui.tr("Passamos de 1.000 downloads: obrigado!", "We passed 1,000 downloads: thank you!", "¡Superamos las 1.000 descargas: gracias!"),
                Ui.tr("Obrigado por confiar no Borealis. Ele continua gratuito e vive de doações voluntárias. Se quiser ajudar, apoie quando quiser. E, se você gostou, conte para amigos e família.",
                        "Thank you for trusting Borealis. It stays free and lives on voluntary donations. If you want to help, you can support it any time. And if you like it, tell your friends and family.",
                        "Gracias por confiar en Borealis. Sigue siendo gratis y vive de donaciones voluntarias. Si quieres ayudar, puedes apoyarlo cuando quieras. Y si te gusta, cuéntale a tus amigos y familia."),
                Ui.tr("Apoiar", "Support", "Patrocinar"), () -> { if (!history) markNewsSeen(id); showSponsorQr(after); },
                Ui.tr("Agora não", "Not now", "Ahora no"), done);
        if (notice != null) notice.milestone(Ui.tr("1.000", "1,000", "1.000"), Ui.tr("downloads", "downloads", "descargas"));
    }

    private void showSponsorQr(final Runnable after) {
        showNotice(false,
                Ui.tr("Apoie o Borealis", "Support Borealis", "Apoya a Borealis"),
                Ui.tr("Aponte a câmera do celular para o código. Doar é voluntário e não libera nada: só ajuda o Borealis a continuar.",
                        "Point your phone's camera at the code. Donating is voluntary and unlocks nothing: it only helps Borealis go on.",
                        "Apunta la cámara de tu celular al código. Donar es voluntario y no desbloquea nada: solo ayuda a que Borealis siga."),
                Ui.tr("Pronto", "Done", "Listo"), after, null, null);
        if (notice != null) notice.qr(NoticeView.SPONSOR_QR, "github.com/sponsors/Alogus");
    }

    private SplashView newSplash(boolean waiting) { return newSplash(waiting, false); }

    private SplashView newSplash(boolean waiting, boolean welcome) {
        final SplashView mine = new SplashView(this, waiting, welcome);
        splash = mine;
        mine.setDone(v -> {
            if (root != null) root.removeView(v);
            if (splash == v) splash = null;
        });
        root.addView(mine, new FrameLayout.LayoutParams(-1, -1));
        return mine;
    }

    /** Plays the opening over the home screen: the one already waiting in front, or a new one. */
    private void playSplash() {
        if (root == null) return;
        App.wakePending = false;
        if (!Prefs.splash()) return;
        if (splash != null) {
            if (splash.waiting()) splash.start();
            return;
        }
        newSplash(false);
    }

    /** The screen is going off: put the opening in place (waiting), so the TV wakes straight onto it and never shows the home first. */
    private void prepareSplash() {
        if (root == null || splash != null || !Prefs.splash()) return;
        newSplash(true);
    }

    /**
     * The opening plays when the TV was just turned on (the clock since boot restarted) or the home process starts again
     * after a long time; not every time the process is rebuilt after a short break, nor when the screen is redrawn for a new language.
     */
    private boolean coldStart() {
        long now = android.os.SystemClock.elapsedRealtime();
        android.content.SharedPreferences sp = getSharedPreferences("splash", MODE_PRIVATE);
        long last = sp.getLong("at", -1);
        boolean show = last < 0 || now < last || now - last > 30 * 60 * 1000L;
        if (show) sp.edit().putLong("at", now).apply();
        return show;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        Diag.event("onResume splash=" + (splash == null ? "none" : splash.waiting() ? "waiting" : "playing") + " " + Diag.memory());
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        if (onboarding != null) onboarding.resume(); else { showWelcomeIfNeeded(); showWhatsNewIfNeeded(); }
        // The TV was just turned on (the screen woke up): the opening again, whether or not the process was rebuilt.
        boolean woke = App.wakePending && android.os.SystemClock.elapsedRealtime() - App.wokeAt < 8000;
        if (woke) playSplash();
        else if (splash != null && splash.waiting()) {
            // The wake signal can arrive a moment after the activity resumes: give it 1.5 s before giving up on the opening.
            final SplashView waiting = splash;
            waiting.postDelayed(() -> { if (waiting.waiting()) waiting.dismiss(); }, 1500);
        }
        App.wakePending = false;
        App.onWake = this::playSplash;
        App.onSleep = this::prepareSplash;
        App.onTrim = () -> { if (home != null) home.trimMemory(); };
    }

    @Override protected void onPause() {
        resumed = false;
        Diag.event("onPause");
        App.onWake = null;
        App.onSleep = null;
        // The screen went off (standby): get the opening ready in front of the home screen.
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) prepareSplash();
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        fakeUpdate(intent);
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        App.onTrim = null;
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (entry != null) return entry.onKey(event) || super.dispatchKeyEvent(event);
        if (onboarding != null && !(splash != null && splash.active())) {   // the welcome takes the keys (the volume still goes to the system)
            if (onboarding.onKey(event)) return true;
            int kc = event.getKeyCode();
            if (kc != KeyEvent.KEYCODE_VOLUME_UP && kc != KeyEvent.KEYCODE_VOLUME_DOWN && kc != KeyEvent.KEYCODE_VOLUME_MUTE) return true;
        }
        // A safety net: an opening that got stuck (or one still waiting while the screen is on and someone is pressing keys) must not hold the remote.
        if (splash != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            boolean on = pm == null || pm.isInteractive();
            if (splash.stuck() || (on && splash.waiting() && splash.waitedMs() > 2500)) {
                Diag.event("splash released by a key: " + (splash.waiting() ? "waiting " + splash.waitedMs() + " ms" : "stuck"));
                final SplashView stale = splash;
                stale.dismiss();
            }
        }
        if (splash != null && splash.active()) {   // any key leaves the opening (and is not passed on)
            if (event.getAction() == KeyEvent.ACTION_DOWN) splash.skip();
            return true;
        }
        if (notice != null) {   // a modal takes the keys (the volume still goes to the system)
            if (notice.onKey(event)) return true;
            return super.dispatchKeyEvent(event);
        }
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
