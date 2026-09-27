# Cozmo Modern

Réécriture de l'application mobile Cozmo pour Android actuel, sans dépendance d'exécution à l'ancien moteur natif.

## V0.5

Cette version regroupe plusieurs fonctions dans un seul jalon pour éviter une succession de petites APK :

- connexion directe UDP au robot ;
- handshake, Enable, SetOrigin et SyncTime ;
- télémétrie batterie / tête / lift ;
- chenilles, tête et lift ;
- LED infrarouge de caméra ;
- LEDs du backpack (rouge, vert, bleu, blanc, off) ;
- volume robot ;
- caméra 320×240 avec réassemblage des ImageChunk et décodage JPEG MiniGray ;
- découverte et connexion automatique des cubes + RSSI / état / batterie ;
- synthèse vocale Android -> PCM mono 22,05 kHz -> μ-law -> OutputAudio vers le haut-parleur du robot ;
- UI Android moderne en trois onglets ;
- signature de test persistante pour les prochaines V0.x.

## Architecture

```
Android / Kotlin / Compose
        |
        +-- protocole Cozmo UDP natif
        |      +-- 172.31.1.1:5551
        |      +-- framing COZ\x03RE\x01
        |      +-- commandes / événements
        |
        +-- caméra
        |      +-- ImageChunk
        |      +-- MiniGray -> JPEG -> Bitmap Android
        |
        +-- voix
        |      +-- android.speech.tts.TextToSpeech
        |      +-- PCM 16 bits mono 22050 Hz
        |      +-- μ-law / OutputAudio 0x8e
        |
        +-- cubes / éclairage / pilotage
```

Le protocole est réimplémenté à partir du comportement documenté publiquement et de PyCozmo (MIT) :
https://github.com/zayfod/pycozmo

Aucun binaire propriétaire Anki/Digital Dream Labs n'est inclus dans ce nouveau projet.

## Test conseillé

1. Connecter Android au Wi-Fi COZMO_xxxxxx.
2. Connecter l'application et vérifier READY + batterie.
3. Tester LEDs backpack et IR.
4. Activer la caméra.
5. Activer la recherche des cubes.
6. Tester une courte phrase dans l'onglet Voix.
7. Tester ensuite les mouvements.

La V0 installée précédemment utilisait la clé debug temporaire de GitHub Actions. Le passage à V0.5 nécessite donc probablement une désinstallation unique. À partir de V0.5 la clé est persistante et les V0.x suivantes pourront être installées par-dessus.

## Après validation V0.5

Le prochain lot prévu est : visages 128×32, animations et premiers comportements/mini-jeux. Ces fonctions s'appuient sur le flux 30 FPS, donc elles sont volontairement ajoutées après validation de la caméra et de l'audio.
