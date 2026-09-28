# Cozmo Modern

Réécriture moderne de l'application Cozmo pour Android actuel, sans dépendance d'exécution à l'ancien moteur natif.

## V0.18 — Humeur, besoins et activités d'origine

Complète le scheduler à identifiants d'origine de la 0.17 :

- **Émotions** (`MoodManager`) : Happy, Calm, Excited, Brave, Confident, Social, WantToPlay dans [-1, 1], retour vers 0 selon les courbes d'origine, pénalité de répétition par événement (`CliffDetected`, `LookAtFaceVerified`, `MotionReact`…).
- **Besoins** (`NeedsManager`) : Energy / Play / Repair avec taux de décroissance, tranches et cooldown de plénitude (20 min) d'origine. Boutons *Nourrir* et *Réparer* en remplacement des mini-jeux.
- **Frustration** : dérivée de Confident (mineure ≤ -0,6 → `ReactToFrustrationMinor`, majeure ≤ -0,9 → `ReactToFrustrationMajor`), rebond de confiance en fin de réaction.
- **Activités** (`PersonalityBrain`) : visage → Socialize (si Social ≤ 0,3) sinon PlayWithHumans, cube → PlayAlone, rien → Hiking, avec durées et cooldowns d'origine ; besoins critiques → activités dédiées au rythme ralenti. Le comportement est ensuite choisi par `OriginalBehaviorScheduler` dans l'activité imposée.
- Corrige l'arrêt de l'autonomie après quelques minutes (énergie vidée par les ticks, puis verrou `energy < 0.18`).

### Tuning d'origine optionnel

Les valeurs d'origine (humeur, besoins, frustration, durées) sont intégrées. Pour les recharger depuis ton propre APK : générer `cozmo_personality.json` avec `extract_cozmo_personality.py` sur `assets/cozmo_resources/config/engine/`, puis le copier dans `Android/data/fr.nvmods.cozmo/files/`. La source active est affichée dans l'onglet personnalité.

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
