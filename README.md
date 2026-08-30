# Streamia TV

Streamia TV est un lecteur Android TV natif, rapide et entièrement pilotable à la télécommande pour les comptes compatibles avec l'API Xtream.

## Fonctionnalités

- connexion par adresse du serveur, identifiant et mot de passe ;
- catégories et chaînes en listes paresseuses adaptées aux grands catalogues ;
- logos de chaînes mis en cache en mémoire ;
- lecture plein écran avec Media3 ExoPlayer (flux TS et HLS) ;
- zapping avec `CH+` / `CH-` ou `↑` / `↓` ;
- guide catégories + chaînes avec `←` ou la touche Menu ;
- reprise du dernier catalogue hors ligne si le fournisseur ne répond pas ;
- identifiants chiffrés localement avec Android Keystore ;
- splash screen, icône adaptative et bannière Android TV ;
- APK généré automatiquement par GitHub Actions.

## Commandes de la télécommande

| Écran | Touche | Action |
|---|---|---|
| Catalogue | Flèches | Naviguer entre catégories et chaînes |
| Catalogue | OK | Ouvrir la catégorie ou lancer la chaîne |
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

Sur GitHub, ouvrez l'onglet **Actions**, lancez **Android TV APK**, puis téléchargez l'artefact `streamia-tv-debug-apk`. Un tag `v1.0.0` crée également une Release contenant l'APK.

## Installation sur Android TV

Activez les options développeur et le débogage réseau de la TV, puis :

```bash
adb connect ADRESSE_IP_TV:5555
adb install -r app-debug.apk
```

L'application vise uniquement Android TV (`android.software.leanback`) et n'apparaît pas dans le lanceur des téléphones.

## Confidentialité et usage légal

Streamia TV n'inclut aucune chaîne, playlist, adresse de serveur ou abonnement. Utilisez uniquement des flux que vous êtes autorisé à regarder. Les identifiants restent sur la TV et sont chiffrés avec Android Keystore. Les serveurs `http://` sont acceptés pour compatibilité, mais la connexion n'est alors pas chiffrée ; préférez toujours `https://`.

## Sources techniques

- [Compose for TV](https://developer.android.com/jetpack/androidx/releases/tv)
- [Media3 / ExoPlayer](https://developer.android.com/jetpack/androidx/releases/media3)
- [Android TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality)
