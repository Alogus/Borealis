# Borealis

**A light, beautiful home screen for Google TV and Android TV.** Your apps, your screensaver and "Continue watching",
in your language (Español, English, Português). Designed and developed by **Alogus Tov**.

> Beta. Tested so far on one TV (Android TV 11, 1280×720). Use it with care and tell me what you find.

## What it does
- A calm home screen with your apps, a shelf you can arrange, the weather and the time.
- **Continue watching**: pick up what you left half-watched in your apps, or discover movies and shows (TMDB).
- A screensaver and wallpapers, a welcome that sets everything up in a minute, and your own avatar.
- Fast on modest TVs: it is drawn directly, no heavy libraries.
- No accounts, no ads, no tracking: see [PRIVACY.md](PRIVACY.md).

## Install (about 10 minutes, one time)
Step by step in [INSTALL.md](INSTALL.md). In short: turn on USB debugging on the TV, then from a computer run
`adb connect <TV-IP>:5555` and `adb install Borealis.apk`, and open Borealis.

You can go back any time: in Borealis's settings choose **Original** and the TV's own home screen returns.

## Build it yourself
Needs a JDK 17+ and the Android SDK command-line tools (build-tools 34+, platform android-34).

    ./build.sh

The first build creates `config.properties` (your city and an optional free TMDB key) and `borealis.keystore` +
`borealis.keystore.pass` (your signing key and its password: keep copies outside this folder, updates must use the same key).

## Maintenance and support
Borealis is maintained by its author. Expect updates over time: fixes, small improvements and support for new TVs
(LG webOS and Samsung Tizen are planned). It is a personal project, so there are no guarantees and no fixed dates.
Found a problem? Open an [issue](../../issues) and say which TV you have.

## Support the project
Borealis is free to install and use, and always will be. If you like it, you can support its development with a
voluntary donation through the **Sponsor** button at the top of this page (GitHub Sponsors). A donation does not
unlock anything: it only helps the work continue. Thank you!

## License
Free for noncommercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE). Commercial use belongs to the
author. The name, logo, avatars and sound are not covered; see [NOTICE.md](NOTICE.md). Based on
[Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT, kept in [LICENSE-FreeMyTV](LICENSE-FreeMyTV)).

---

# Borealis (Español)

**Una pantalla de inicio ligera y bonita para Google TV y Android TV.** Tus apps, tu protector de pantalla y
"Continuar viendo", en tu idioma. Diseñado y desarrollado por **Alogus Tov**.

> Beta. Probado hasta ahora en una sola TV (Android TV 11, 1280×720). Úsalo con cuidado y cuéntame qué encuentras.

## Qué hace
- Una pantalla de inicio tranquila con tus apps, un estante que organizas, el clima y la hora.
- **Continuar viendo**: retoma lo que dejaste a medias en tus apps, o descubre películas y series (TMDB).
- Protector de pantalla y fondos, una bienvenida que lo configura todo en un minuto, y tu propio avatar.
- Rápido en TVs modestas: se dibuja directamente, sin librerías pesadas.
- Sin cuentas, sin anuncios, sin rastreo: mira [PRIVACY.md](PRIVACY.md).

## Instalación (unos 10 minutos, una sola vez)
Paso a paso en [INSTALL.md](INSTALL.md). En resumen: activa la depuración USB en la TV; desde un computador ejecuta
`adb connect <IP-de-la-TV>:5555` y `adb install Borealis.apk`, y abre Borealis.

Puedes volver atrás cuando quieras: en los ajustes de Borealis elige **Original** y vuelve el inicio de tu TV.

## Mantenimiento y soporte
Borealis lo mantiene su autor. Espera actualizaciones con el tiempo: correcciones, pequeñas mejoras y soporte para
nuevas TVs (están planeadas LG webOS y Samsung Tizen). Es un proyecto personal, así que no hay garantías ni fechas
fijas. ¿Encontraste un problema? Abre un [issue](../../issues) e indica qué TV tienes.

## Apoyar el proyecto
Borealis es gratis de instalar y usar, y lo seguirá siendo. Si te gusta, puedes apoyar su desarrollo con una donación
voluntaria desde el botón **Sponsor** en la parte superior de esta página (GitHub Sponsors). Donar no desbloquea nada:
solo ayuda a que el trabajo continúe. ¡Gracias!

## Licencia
Gratis para uso no comercial bajo la [PolyForm Noncommercial License 1.0.0](LICENSE). El uso comercial pertenece al
autor. El nombre, el logo, los avatares y el sonido no están cubiertos; mira [NOTICE.md](NOTICE.md). Basado en
[Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT, en [LICENSE-FreeMyTV](LICENSE-FreeMyTV)).
