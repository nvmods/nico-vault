# Cozmo Modern

Réécriture moderne de l'application Cozmo pour Android actuel, sans dépendance d'exécution à l'ancien moteur natif.

## V0.7 — CubeManager

La V0.7 garde les fonctions validées (mouvements, caméra, IR, backpack, voix) et remplace la gestion simplifiée des cubes par une vraie couche dédiée.

### Connexion des 3 cubes

- découverte des LightCube 1 / 2 / 3 par `ObjectAvailable` ;
- table séparant `object_type`, `factory_id` permanent et `object_id` temporaire ;
- connexion BLE séquentielle, un cube à la fois ;
- attente de `ObjectConnectionState` avant de passer au cube suivant ;
- timeout / retry sans flood de `ObjectConnect` ;
- affichage RSSI, nombre d'essais, batterie et `missed_packets`.

### LEDs

Chaque cube possède 4 RGB LEDs indépendantes.

La V0.7 permet :
- couleur identique sur les 4 LEDs ;
- couleur indépendante LED 1 / 2 / 3 / 4 ;
- motifs alternés 1+3 / 2+4 ;
- commandes globales pour tous les cubes ;
- chaser simple via `CubeId.rotation_period_frames` ;
- sérialisation stricte `CubeId` puis `CubeLights`, sans qu'une sélection d'un autre cube puisse s'intercaler.

### Accéléromètres et événements

Chaque cube possède un accéléromètre 3 axes.

La V0.7 ajoute :
- `StreamObjectAccel` activable par cube ou pour tous ;
- lecture `ObjectAccel` X/Y/Z (~30 ms) ;
- `ObjectMoved` ;
- `ObjectStoppedMoving` ;
- `ObjectTapped` ;
- `ObjectTapFiltered` ;
- `ObjectUpAxisChanged` avec face haute ±X / ±Y / ±Z.

Ces événements constituent la base nécessaire pour les jeux utilisant tap, secousse, retournement et orientation des cubes.

### Base déjà validée

- UDP Cozmo fiable avec ACK / retransmissions ;
- chenilles, tête, lift ;
- caméra 320×240 ;
- LED IR persistante ;
- LEDs backpack ;
- TTS Android -> PCM 22050 Hz -> codec audio Cozmo.

Le protocole est réimplémenté à partir du comportement documenté publiquement, de PyCozmo (MIT) et des exemples du SDK Anki.

Aucun binaire propriétaire Anki/Digital Dream Labs n'est inclus dans ce projet.
