# Cozmo Modern

Réécriture progressive de l'application mobile Cozmo pour Android récent.

## Objectif V0

La V0 retire complètement de la boucle d'exécution :

- `libcozmoEngine.so`
- Acapela TTS
- l'ancien Unity Downloader / Google LVL
- les APK/OBB historiques pour le cœur de communication

Le premier jalon est volontairement réduit : établir une connexion directe avec le robot, initialiser son protocole et piloter les moteurs avec une application Android moderne.

## Architecture

```
Android / Kotlin / Compose
        |
        +-- cozmo.protocol
        |      +-- UDP 172.31.1.1:5551
        |      +-- framing COZ\x03RE\x01
        |      +-- ACK / séquences
        |      +-- commandes moteurs / état robot
        |
        +-- UI diagnostic
               +-- connexion
               +-- batterie
               +-- roues
               +-- tête
               +-- lift
               +-- phare
```

## Source du protocole

L'implémentation est écrite en Kotlin à partir du comportement documenté publiquement du protocole Cozmo et de la référence **PyCozmo** (MIT), notamment :

- adresse robot : `172.31.1.1:5551/UDP`
- identifiant de trame : `COZ\x03RE\x01`
- commandes `Enable`, `DriveWheels`, `MoveHead`, `MoveLift`, `StopAllMotors`, `SetOrigin`, `SyncTime`
- événement `RobotState`

Référence : https://github.com/zayfod/pycozmo

Aucun binaire propriétaire Anki/Digital Dream Labs n'est inclus dans ce nouveau projet.

## Test V0 sur téléphone

1. Connecter manuellement le téléphone au Wi-Fi émis par Cozmo.
2. Ouvrir **Cozmo Modern**.
3. Appuyer sur **Connecter Cozmo**.
4. Attendre l'état `READY`.
5. Tester d'abord le phare, puis tête/lift, puis les chenilles à faible vitesse.

Le bouton **Arrêter les moteurs** doit être utilisé après les tests de tête/lift : les commandes V0 sont des commandes de vitesse continues.

## Roadmap immédiate

1. valider le handshake sur un vrai Cozmo ;
2. durcir ACK/retransmission et liaison Android vers le réseau Wi-Fi Cozmo ;
3. décoder la caméra ;
4. gérer cubes et événements ;
5. charger/jouer les animations ;
6. générer du PCM moderne et utiliser `OutputAudio` pour la voix ;
7. reconstruire les écrans et mini-jeux.
