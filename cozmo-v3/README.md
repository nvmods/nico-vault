# Cozmo 3.6.6 — Android 15 TTS V3

Branche de diagnostic/compatibilité pour le crash immédiat quand Cozmo doit parler.

Cette V3 repart de l'APK 3.6.6 original et **ne reprend pas le garde TLSF** de la V2.

Modifications :
- targetSdkVersion : 35 -> 29 (test de compatibilité avec le comportement Android pré-11 pour le vieux natif) ;
- android:allowNativeHeapPointerTagging="false" ;
- android:requestLegacyExternalStorage="true" ;
- patch ARM64 de libacattsandroid.so : le strerror() interne s'appelait lui-même récursivement à 0x447d0. Le branchement est redirigé vers le fallback interne qui formate le code d'erreur sans récursion.

Important :
- c'est un build de diagnostic, signé avec une clé de test différente de Digital Dream Labs ;
- il faut désinstaller la version officielle/une version signée avec une autre clé avant installation ;
- l'OBB original main.11.com.digitaldreamlabs.cozmo2.obb reste inchangé ;
- si le TTS plante encore, le prochain axe est le transport audio : les dumps originaux atteignaient strerror avec le code 101 (0x65), qui correspond à ENETUNREACH sur Linux/Android.
