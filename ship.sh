#!/bin/bash
# Borealis: aplica el parche, compila, instala en la TV, sube el codigo y publica la Release.
# Uso:   ./ship.sh "mensaje de la version"            -> todo (TV + GitHub)
#        ./ship.sh "mensaje de la version" --local    -> solo parche + compilar + TV (no publica nada)
# Toma solo el parche mas reciente de Descargas (Borealis-v*-patch.zip).
set -e

TV="192.168.1.23:5555"
DL="/mnt/c/Users/Andres/Downloads"
REPO="Alogus/Borealis"
MSG="${1:?Escribe el mensaje de la version entre comillas}"
LOCAL="$2"

cd ~/borealis

ZIP=$(ls -t "$DL"/Borealis-v*-patch.zip 2>/dev/null | head -1)
[ -n "$ZIP" ] || { echo "No encuentro ningun Borealis-v*-patch.zip en Descargas"; exit 1; }
echo ">> Parche: $(basename "$ZIP")"

unzip -o "$ZIP" -d .. > /dev/null
VER=$(grep -o 'versionName="[^"]*"' app/AndroidManifest.xml | head -1 | cut -d'"' -f2)
echo ">> Version: $VER"

./build.sh
adb connect "$TV" > /dev/null
adb install -r Borealis.apk
echo ">> Instalada en la TV."

if [ "$LOCAL" = "--local" ]; then
  echo ">> Modo local: no se publico nada en GitHub."
  exit 0
fi

if gh release view "v$VER" --repo "$REPO" > /dev/null 2>&1; then
  echo "!! La Release v$VER ya existe en GitHub. Sube la version en el parche y vuelve a intentar."
  exit 1
fi

git add -A
git commit -m "$VER: $MSG" || echo ">> (nada nuevo que guardar en el codigo)"
git push

mkdir -p /tmp/borealis-release && rm -f /tmp/borealis-release/*
cp Borealis.apk /tmp/borealis-release/Borealis.apk
cp Borealis.apk "/tmp/borealis-release/Borealis-$VER.apk"
(cd /tmp/borealis-release && sha256sum Borealis.apk > Borealis.apk.sha256)

gh release create "v$VER" \
  /tmp/borealis-release/Borealis.apk \
  "/tmp/borealis-release/Borealis-$VER.apk" \
  /tmp/borealis-release/Borealis.apk.sha256 \
  --repo "$REPO" --title "Borealis TV Launcher $VER" --notes "$MSG" --latest

echo ">> Publicada la Release v$VER: https://github.com/$REPO/releases/tag/v$VER"
