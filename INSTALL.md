# Installing Borealis / Instalar Borealis

You need: your TV (Google TV or Android TV), a computer on the same Wi-Fi, and the file `Borealis.apk`
(download it from the project's *Releases* page). / Necesitas: tu TV, un computador en el mismo Wi-Fi y el archivo
`Borealis.apk` (de la página *Releases* del proyecto).

## Easiest way (English): no computer for the install / La forma más fácil (Español)

> Beta: tested on one TV (Android TV 11) and by a few people on other TVs. / Beta: probado en una TV (Android TV 11) y por algunas personas en otras TVs.

**English.** 1) On the TV, install the free app **Downloader** (by AFTVnews) from the app store. 2) Allow it to install apps:
Settings → Apps → Security & restrictions → Unknown sources → Downloader on. 3) Open Downloader and type the code **3357424** → Go → Install. 4) Turn on developer mode and
*USB debugging* (steps 1–2 below) and open Borealis. If your TV does not open the debugging port, Borealis shows *Plan B*: turn Borealis on in Settings → Accessibility and
the Home button will open it (no computer). See "If something blocks the install" below.

**Español.** 1) En la TV instala la app gratuita **Downloader** (de AFTVnews) desde la tienda. 2) Permítele instalar apps:
Ajustes → Apps → Seguridad y restricciones → Fuentes desconocidas → Downloader activado. 3) Abre Downloader y escribe
el código **3357424** → Ir → Instalar. 4) Activa el modo desarrollador y la
*Depuración USB* (pasos 1–2 de abajo) y abre Borealis. Si tu TV no abre el puerto de depuración, Borealis muestra el *Plan B*: activa Borealis en Ajustes → Accesibilidad y el
botón Home lo abrirá (sin computador). Mira "Si algo bloquea la instalación" más abajo.

## English

1. **Turn on developer mode on the TV.** Settings → System → About → press *Android TV OS build* seven times.
2. **Turn on USB debugging.** Settings → System → Developer options → *USB debugging* on.
3. **Find the TV's address.** Settings → Network & Internet → your Wi-Fi: write down the IP (like `192.168.1.23`).
4. **Install adb on the computer** (Google's free tool): download *SDK Platform-Tools* from
   developer.android.com/tools/releases/platform-tools and unzip it. On Windows you can also use WSL.
5. **Connect.** In a terminal inside that folder: `adb connect 192.168.1.23:5555` (use your IP).
   The TV asks *Allow USB debugging?* → tick *Always allow* → OK.
6. **Install.** `adb install -r Borealis.apk` → it should say *Success*.
7. **Open Borealis** from your apps. Follow the welcome. At the end it makes itself the TV's home screen
   (the TV may ask again to allow debugging: *Always allow*).

**Go back to the original home screen:** Borealis → Settings → choose *Original*.
**Uninstall:** first choose *Original*, then `adb uninstall io.borealis`.
If something fails, open an issue on the project's page with what you saw.

## Español

1. **Activa el modo desarrollador en la TV.** Ajustes → Sistema → Acerca de → presiona siete veces *Compilación de Android TV OS*.
2. **Activa la depuración USB.** Ajustes → Sistema → Opciones para desarrolladores → *Depuración USB*.
3. **Busca la dirección de la TV.** Ajustes → Red e internet → tu Wi-Fi: anota la IP (como `192.168.1.23`).
4. **Instala adb en el computador** (herramienta gratuita de Google): descarga *SDK Platform-Tools* en
   developer.android.com/tools/releases/platform-tools y descomprímela. En Windows también puedes usar WSL.
5. **Conéctate.** En una terminal dentro de esa carpeta: `adb connect 192.168.1.23:5555` (con tu IP).
   La TV pregunta *¿Permitir depuración USB?* → marca *Siempre permitir* → OK.
6. **Instala.** `adb install -r Borealis.apk` → debe decir *Success*.
7. **Abre Borealis** desde tus apps. Sigue la bienvenida. Al final se activa como inicio de la TV
   (la TV puede volver a preguntar por la depuración: *Siempre permitir*).

**Volver al inicio original:** Borealis → Ajustes → elige *Original*.
**Desinstalar:** primero elige *Original*, luego `adb uninstall io.borealis`.
Si algo falla, abre un "issue" en la página del proyecto contando qué viste.

## If something blocks the install / Si algo bloquea la instalación

**English**
- **"Google Play Protect blocked the app".** Play Protect distrusts apps that don't come from the store. On the TV: Google Play Store → your profile picture → *Play Protect* → settings (gear) → turn off *Scan apps with Play Protect*, install Borealis again, then turn it back on. Menus change by brand; if you see *Install anyway* or *More details*, use that.
- **"Couldn't turn on Borealis: debugging is off on the TV (port 5555 closed)".** Check *USB debugging* is on (not *Wireless debugging*). If your TV still keeps the port closed, use **Plan B**: Settings → Accessibility → Borealis → on. The Home button then opens Borealis. Or open the port once from a computer: `adb connect IP:5555` then `adb tcpip 5555`.
- **Can't turn Borealis on in Accessibility (Android 13/14).** Android blocks the accessibility of apps installed from outside the store ("Restricted setting"). Go to Settings → Apps → Borealis → menu (⋮) → *Allow restricted settings*, then try again. From a computer: `adb shell cmd appops set io.borealis ACCESS_RESTRICTED_SETTINGS allow`.
- **"The TV's first setup saved the Wi-Fi".** Settings → Network & Internet → forget your network, connect again, and try once more.

**Español**
- **"Google Play Protect bloqueó la app".** Play Protect desconfía de las apps que no vienen de la tienda. En la TV: Google Play Store → tu foto de perfil → *Play Protect* → ajustes (engranaje) → apaga *Analizar apps con Play Protect*, instala Borealis otra vez y vuelve a encenderlo. Los menús cambian según la marca; si ves *Instalar de todos modos* o *Más detalles*, usa esa opción.
- **"No se pudo activar Borealis: la depuración del TV está apagada (puerto 5555)".** Revisa que esté encendida la *Depuración USB* (no la *Depuración inalámbrica*). Si tu TV sigue con el puerto cerrado, usa el **Plan B**: Ajustes → Accesibilidad → Borealis → activado. El botón Home abrirá Borealis. O abre el puerto una vez desde un computador: `adb connect IP:5555` y luego `adb tcpip 5555`.
- **No puedo activar Borealis en Accesibilidad (Android 13/14).** Android bloquea la accesibilidad de las apps instaladas fuera de la tienda ("Configuración restringida"). Ve a Ajustes → Aplicaciones → Borealis → menú (⋮) → *Permitir ajustes restringidos* y vuelve a intentar. Desde un computador: `adb shell cmd appops set io.borealis ACCESS_RESTRICTED_SETTINGS allow`.
- **"La configuración inicial del TV guardó el Wi-Fi".** Ajustes → Red e Internet → olvida tu red, vuelve a conectarte y vuelve a intentarlo.
