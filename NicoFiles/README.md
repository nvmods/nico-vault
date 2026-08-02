# NicoFiles

Gestionnaire de fichiers Android personnel, sans publicité, sans compte, sans télémétrie et sans permission Internet.

## Fonctions de la version 1.0

- Navigation dans le stockage interne, les cartes SD et les volumes USB visibles par Android.
- Autorisation Android « Accès à tous les fichiers » pour les opérations complètes.
- Vue liste ou grille, tri par nom/date/taille/type et affichage facultatif des fichiers cachés.
- Recherche dans le dossier courant ou récursive.
- Sélection multiple, copie, déplacement, renommage et création de dossiers.
- Corbeille locale avec restauration et suppression définitive.
- Compression ZIP et extraction ZIP avec protection contre le ZIP Slip.
- Ouverture et partage via les applications Android installées.
- Favoris, historique des éléments récents et état des espaces de stockage.
- Miniatures simples pour les images et vidéos.
- Action d'envoi vers NicoVault.

## Limites Android

Même avec `MANAGE_EXTERNAL_STORAGE`, Android peut bloquer certaines zones privées appartenant à d'autres applications, notamment des parties de `Android/data` et `Android/obb`.

## Compilation

```bash
./gradlew clean testDebugUnitTest assembleDebug
```

APK : `app/build/outputs/apk/debug/app-debug.apk`

## Sécurité et vie privée

L'application ne déclare pas la permission Internet. Elle ne contient aucune publicité, aucun SDK analytique et aucun service en arrière-plan.
