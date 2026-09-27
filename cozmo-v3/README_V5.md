# Cozmo 3.6.6 — Android 15 TTS V5

Diagnostic ciblé sur le crash immédiat au moment où Cozmo doit parler.

Ce que les logs V4 montrent :
- la récursion Acapela `strerror()` n'est plus le crash observé ;
- les pointeurs utiles du heap sont désormais non tagués dans le crash, donc le pointer tagging n'est probablement pas la cause racine ;
- le crash revient plus tôt dans le free TLSF à `0x12d3e18` ;
- le header du bloc courant contient une taille impossible : `0x95b6db579716a060`.

V5 garde :
- targetSdkVersion 29 ;
- android:allowNativeHeapPointerTagging="false" ;
- android:requestLegacyExternalStorage="true" ;
- patch Acapela ARM64 contre la récursion `strerror()`.

V5 change le garde moteur :
- juste après lecture du header du bloc courant, avant tout calcul de pointeur ;
- si les 32 bits hauts de la taille sont non nuls (donc taille impossible pour ce pool), la routine de free retourne immédiatement ;
- le bloc corrompu n'est pas libéré : petite fuite mémoire volontaire pour ce test ;
- les blocs normaux continuent dans le code d'origine.

But du test :
1. lancer l'appli ;
2. connecter Cozmo ;
3. déclencher une seule phrase ;
4. voir s'il parle ou si le crash se déplace encore.

Ce build est diagnostique. Si la parole fonctionne, on remontera ensuite à l'écriture qui corrompt le header au lieu de conserver ce contournement comme solution finale.
