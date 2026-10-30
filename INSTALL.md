# Installing Borealis / Instalar Borealis

You need: your TV (Google TV or Android TV), a computer on the same Wi-Fi, and the file `Borealis.apk`
(download it from the project's *Releases* page). / Necesitas: tu TV, un computador en el mismo Wi-Fi y el archivo
`Borealis.apk` (de la página *Releases* del proyecto).

## Easiest way (English): no computer for the install / La forma más fácil (Español)

> Beta: tested so far with the computer method below. / Beta: probado hasta ahora con el método del computador de abajo.

**English.** 1) On the TV, install the free app **Downloader** (by AFTVnews) from the app store. 2) Allow it to install apps:
Settings → Apps → Security & restrictions → Unknown sources → Downloader on. 3) Open Downloader and type the code **3357424** → Go → Install. 4) Turn on developer mode and
*USB debugging* (steps 1–2 below) and open Borealis. If Borealis cannot make itself the home screen on your TV, use
the computer method once.

**Español.** 1) En la TV instala la app gratuita **Downloader** (de AFTVnews) desde la tienda. 2) Permítele instalar apps:
Ajustes → Apps → Seguridad y restricciones → Fuentes desconocidas → Downloader activado. 3) Abre Downloader y escribe
el código **3357424** → Ir → Instalar. 4) Activa el modo desarrollador y la
*Depuración USB* (pasos 1–2 de abajo) y abre Borealis. Si Borealis no logra activarse como inicio en tu TV, usa una vez
el método del computador.

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
