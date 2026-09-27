# Audio de personnalité Cozmo

## Source analysée

Ressources de l'application officielle Cozmo 3.6.6 fournies pour le projet.

Le paquet audio se trouve dans :

`assets/cozmo_resources/sound/AudioAssets.zip`

Contenu observé :
- 2 215 médias WEM ;
- 837 événements Wwise ;
- banques Init, SFX, Music, UI, Dev_Debug et Cozmo ;
- SoundbanksInfo.xml avec les noms d'origine des médias.

## Résultat du tri personnalité

Le pack d'extraction local contient :
- 1 034 médias utiles aux réactions/personnalité ;
- 830 vocalisations `Robot_VO__...` ;
- 159 SFX `Robot_SFX__...` ;
- 153 SFX convertis en PCM WAV mono 22,05 kHz, format directement compatible avec le pipeline audio actuel de CozmoModern.

Catégories préparées :
- greeting / réaction visage ;
- happy ;
- curious / interested ;
- bored ;
- angry / frustrated ;
- sad / disappointed ;
- surprised ;
- pickup ;
- self-right / turtle ;
- cliff / fall ;
- effort / lift ;
- sleep / wake ;
- playful / pounce ;
- win ;
- lose.

## Architecture côté code

Le moteur ne référence pas directement un fichier.

`PersonalityEngine -> RobotAction.PlaySound(PersonalitySoundCue.*) -> adaptateur audio -> variante choisie dans le catalogue`

Cela permet :
- de choisir aléatoirement plusieurs variantes d'un même état ;
- de synchroniser son, visage et mouvement dans un BehaviorEngine ;
- de remplacer les ressources sans toucher au moteur de personnalité.

Les fichiers audio officiels ne sont volontairement pas commités dans ce dépôt public.
