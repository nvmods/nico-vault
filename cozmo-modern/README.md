# Cozmo Modern

Réécriture moderne de l'application Cozmo pour Android actuel, sans dépendance d'exécution à l'ancien moteur natif.

## V0.6

Cette version consolide les fonctions déjà validées et renforce surtout la couche de communication.

### Communication

- transport UDP sérialisé ;
- fenêtre de commandes non acquittées ;
- ACK traités comme dans le protocole Cozmo ;
- retransmission automatique après environ 100 ms ;
- limitation des bursts ;
- déduplication des trames robot réémises ;
- compteur de retransmissions visible dans l'interface ;
- coalescing des changements rapides de LEDs backpack.

L'objectif est d'éviter les pertes de session observées lorsque plusieurs commandes sont envoyées trop rapidement.

### Pilotage / robot

- roues, tête, lift ;
- batterie / état tête / lift ;
- volume ;
- LED IR caméra avec état persistant entre les onglets ;
- LEDs backpack rouge / vert / bleu / blanc / off.

### Caméra

- flux 320×240 ;
- réassemblage ImageChunk ;
- décodage MiniGray/JPEG ;
- état de la LED IR partagé entre Pilotage et Caméra ;
- réapplication automatique de l'IR lors de la réactivation du pipeline caméra.

### Cubes

- découverte et connexion automatique ;
- factory ID, object ID, type, RSSI et batterie lorsqu'elle est publiée par le cube ;
- commande des quatre LEDs d'un cube ;
- sélection de couleur par cube ;
- commande de couleur pour tous les cubes connectés.

Le pilotage suit le protocole public utilisé par PyCozmo : sélection du cube par CubeId (0x10), puis CubeLights (0x04).

### Voix

- synthèse Android locale ;
- pitch et vitesse réglables directement dans l'application ;
- conversion mono PCM 16 bits 22,05 kHz ;
- filtrage passe-haut léger + normalisation pour le petit haut-parleur ;
- encodage μ-law ;
- OutputAudio 0x8e à environ 30 FPS.

Le réglage pitch/vitesse évite de devoir recompiler à chaque essai de rendu vocal.

## Architecture

```
Android / Kotlin / Compose
        |
        +-- transport Cozmo fiable
        |      +-- UDP 172.31.1.1:5551
        |      +-- fenêtre / ACK / retransmission
        |
        +-- pilotage / lumières
        +-- caméra
        +-- cubes
        +-- Android TTS -> PCM -> μ-law -> Cozmo
```

Le protocole est réimplémenté à partir du comportement documenté publiquement et de PyCozmo (MIT) :
https://github.com/zayfod/pycozmo

Aucun binaire propriétaire Anki/Digital Dream Labs n'est inclus dans ce nouveau projet.

## Test conseillé V0.6

1. Connexion et état READY.
2. Tester rapidement plusieurs couleurs backpack et vérifier que la session reste stable.
3. Passer Pilotage -> Caméra -> Pilotage avec IR activé et vérifier que l'état est conservé.
4. Activer les cubes, attendre leur état connecté, puis tester les LEDs individuellement.
5. Tester une phrase courte et ajuster pitch / vitesse.
6. Surveiller le compteur de retransmissions : quelques retransmissions sont normales ; une hausse continue indique une liaison Wi-Fi ou une saturation à examiner.

La signature de test est persistante depuis V0.5 : les versions suivantes s'installent avec `adb install -r`.

## Prochain gros lot

Après stabilisation de cette base :
- visages 128×32 ;
- animations ;
- comportements ;
- premiers mini-jeux.
