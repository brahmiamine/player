# Streamia TV

Streamia TV est un lecteur Android TV natif, rapide et entièrement pilotable à la télécommande pour les comptes Xtream et les playlists M3U étendues compatibles.

## Fonctionnalités

- connexion par serveur, identifiant et mot de passe, avec ajout automatique de `https://` si nécessaire ;
- import local d'une playlist M3U étendue en UTF-8, analysée ligne par ligne ;
- espaces séparés **Direct**, **Films** et **Séries**, avec catégories indépendantes ;
- saisons et épisodes récupérés par l'API Xtream lorsqu'ils sont disponibles ;
- programme en cours récupéré avec l'EPG court Xtream ;
- catégories et médias en listes paresseuses adaptées aux grands catalogues ;
- logos HTTP ou HTTPS redimensionnés et mis en cache en mémoire ;
- lecture plein écran avec Media3 ExoPlayer (TS, HLS et formats VOD usuels) ;
- zapping avec `CH+` / `CH-` ou `↑` / `↓` ;
- guide catégories + chaînes avec `←` ou la touche Menu ;
- cache catalogue JSON lu et écrit en flux, puis reprise hors ligne si le fournisseur ne répond pas ;
- identifiants chiffrés localement avec Android Keystore ;
- splash screen, icône adaptative et bannière Android TV ;
- APK généré automatiquement par GitHub Actions.

## Commandes de la télécommande

| Écran | Touche | Action |
|---|---|---|
| Catalogue | Flèches | Naviguer entre catégories et chaînes |
| Catalogue | OK | Ouvrir une catégorie, une chaîne, un film ou une série |
| Série | Flèches | Choisir une saison et un épisode |
| Série | OK | Lancer l'épisode |
| Lecteur | `↑` / `CH+` | Chaîne précédente de la catégorie |
| Lecteur | `↓` / `CH-` | Chaîne suivante de la catégorie |
| Lecteur | `←` / Menu | Ouvrir le guide |
| Lecteur | OK | Afficher ou masquer les informations |
| Lecteur | Lecture/Pause | Mettre en pause ou reprendre |
| Lecteur | Retour | Fermer le guide puis quitter le lecteur |

## Construire l'APK

Prérequis : JDK 17, Android SDK 36 et Gradle 8.13.

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

L'APK installable est produit dans `app/build/outputs/apk/debug/app-debug.apk`.

Sur GitHub, ouvrez l'onglet **Actions**, lancez **Android TV APK**, puis téléchargez l'artefact `streamia-tv-debug-apk`. Un tag `v1.1.0` crée également une Release contenant l'APK.

## Formats Xtream et M3U pris en charge

L'import reconnaît les URL contenant `/live/`, `/movie/` et `/series/`. Les champs M3U utilisés sont `tvg-id`, `tvg-name`, `tvg-logo` et `group-title`. Une virgule située à l'intérieur d'un attribut entre guillemets ou dans le nom affiché est conservée correctement.

Les données détaillées absentes du M3U — épisodes, saisons et EPG — sont demandées séparément à l'API Xtream. Un `tvg-id` numérique est conservé comme identifiant du fournisseur ; il n'est pas considéré automatiquement comme un identifiant XMLTV universel.

Pour protéger les accès, l'import n'enregistre pas les URL complètes de la playlist dans le cache. Le serveur, l'identifiant et le mot de passe communs sont extraits une seule fois puis chiffrés avec Android Keystore. Les entrées provenant d'un second compte dans le même fichier sont ignorées.

## Installation sur Android TV

Activez les options développeur et le débogage réseau de la TV, puis :

```bash
adb connect ADRESSE_IP_TV:5555
adb install -r app-debug.apk
```

L'application vise uniquement Android TV (`android.software.leanback`) et n'apparaît pas dans le lanceur des téléphones.

## Confidentialité et usage légal

Streamia TV n'inclut aucune chaîne, playlist, adresse de serveur ou abonnement. Utilisez uniquement des flux que vous êtes autorisé à regarder. Les identifiants restent sur la TV et sont chiffrés avec Android Keystore. Ils ne doivent jamais être ajoutés au dépôt, aux journaux ou à un outil d'analyse. Les serveurs `http://` sont acceptés pour compatibilité, mais la connexion n'est alors pas chiffrée ; préférez toujours `https://`.

## Sources techniques

- [Compose for TV](https://developer.android.com/jetpack/androidx/releases/tv)
- [Media3 / ExoPlayer](https://developer.android.com/jetpack/androidx/releases/media3)
- [Android TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality)
