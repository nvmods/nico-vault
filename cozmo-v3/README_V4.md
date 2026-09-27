# Cozmo 3.6.6 — Android 15 TTS V4

Build de diagnostic ciblé sur le crash immédiat lors de la synthèse vocale.

Constats ayant conduit à cette V4 :
- le correctif V3 supprime la récursion Acapela `strerror()` ;
- le crash restant est reproductible dans `libcozmoEngine.so` à `0x12d3fa4/0x12d3fac` ;
- le registre de taille voisin vaut de façon répétée `0x9856c67c996e466c`, impossible pour le pool TLSF ;
- le moteur traite alors ce voisin corrompu comme un bloc libre et déréférence ses pointeurs de liste.

V4 conserve :
- targetSdkVersion 29 ;
- android:allowNativeHeapPointerTagging="false" ;
- android:requestLegacyExternalStorage="true" ;
- correctif Acapela ARM64 : `strerror()` ne s'appelle plus récursivement.

V4 ajoute un garde très étroit dans `libcozmoEngine.so` :
- les tailles TLSF normales (< 4 Gio) suivent exactement le chemin original ;
- si la taille du bloc voisin a des bits dans les 32 bits hauts, le moteur n'essaie plus de le retirer de la free-list ;
- il récupère la taille du bloc courant et l'insère sans fusionner le voisin corrompu.

Ce n'est pas présenté comme la correction de la cause racine. C'est un contournement diagnostique destiné à empêcher le crash déterministe et à vérifier si la synthèse peut aller plus loin.

Important :
- APK signée avec une clé de test différente de Digital Dream Labs ;
- désinstaller la version précédente avant installation ;
- conserver/remettre l'OBB original `main.11.com.digitaldreamlabs.cozmo2.obb` ;
- si Cozmo parle enfin, le prochain travail sera de remonter à l'écriture qui corrompt le bloc plutôt que de conserver ce garde comme solution définitive.
