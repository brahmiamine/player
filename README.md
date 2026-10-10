# Streamia TV

Streamia TV est un lecteur Android TV natif, rapide et entièrement pilotable à la télécommande pour les comptes Xtream et les playlists M3U étendues compatibles.

## Fonctionnalités

### Listes et catalogue

- plusieurs listes enregistrées (**Mes listes**) : comptes Xtream (serveur, identifiant, mot de passe, test de connexion) et playlists M3U étendues, en fichier local ou en URL distante avec actualisation automatique ;
- URL XMLTV facultative par liste, en complément de l'EPG du fournisseur ;
- espaces séparés **Direct**, **Films** et **Séries**, avec catégories indépendantes ;
- catalogue stocké en base SQLite, lu page par page, pour rester fluide avec de très grands catalogues ;
- reprise hors ligne (**Mode cache**) si le fournisseur ne répond pas, et rechargement automatique au retour du réseau ;
- actualisation quotidienne des catalogues Xtream en arrière-plan ;
- tri des chaînes (ordre du fournisseur, par numéro, alphabétique) et des films/séries (récemment ajoutés, mieux notés, alphabétique) ;
- **Organiser** : réordonner, masquer, déplacer et verrouiller catégories et contenus, en sélection multiple.

### Accueil

- blocs activables un par un : reprendre la lecture, favoris, dernières chaînes regardées, recommandations, matchs en direct avec leurs chaînes, scores football du jour, programme TV FR en direct et ce soir, UK en direct et à suivre ;
- aperçu TV en direct pendant la navigation, avec délai réglable ;
- météo et heures de prière de la ville choisie (plusieurs méthodes de calcul : France, Tunisie, Égypte, Umm al-Qura, Ligue islamique mondiale, ISNA, Karachi).

### Films et séries

- fiches détaillées : affiche, résumé, genre, durée, note, réalisateur, distribution, pays ;
- **bouton Bande-annonce** : ouvre la bande-annonce fournie par le serveur dans l'appli YouTube (identifiant seul ou lien YouTube), ou le lien direct fourni ;
- saisons et épisodes via l'API Xtream, avec progression par épisode et reprise sur le dernier épisode vu ;
- épisode suivant enchaîné automatiquement (désactivable) ;
- favoris, marquage « vu », reprise à la dernière position ou lecture depuis le début ;
- titres similaires (TMDB, MovieLens, Wikidata, tendances JustWatch) et autres versions d'un même titre (langues, qualités).

### Direct et guide TV

- programme en cours et barre de progression sous chaque chaîne ;
- grille EPG alignée sur l'heure (fenêtre de 2 h, repère « maintenant », navigation jour par jour) ;
- décalage horaire EPG réglable si le guide du fournisseur est décalé ;
- guides complémentaires : programme TV FR, beIN SPORTS et chaînes UK ;
- zapping rapide, saisie directe du numéro de chaîne et retour à la dernière chaîne ;
- panneau **Versions** (touche `→`) : les autres versions d'une chaîne (HD, FHD, UHD, autres pays…), reconnues par leur nom nettoyé ou leur identifiant de guide TV, et classées sur ce que le lecteur **mesure réellement à l'écran** : résolution, fps réellement affichés, images perdues, image absente ou figée, son absent, coupures. Alertes « annoncée UHD, réellement 1080p », « annoncée 50 fps, réellement 25 », « 4K douteuse », « images perdues » ; bouton **Tester toutes les versions**, qui les affiche une par une (jamais deux connexions à la fois) puis garde la meilleure ;
- **secours automatique** (activé par défaut, désactivable dans Paramètres › Lecture & direct) : passage sur une autre version de la même langue si l'image est coupée, figée ou sans son pendant 6 secondes, ou après 3 coupures d'au moins 5 secondes en 5 minutes ; sans effet sur une chaîne qui n'a qu'une version ou quand Internet est coupé.

### Lecteur

- Media3 ExoPlayer (TS, HLS et formats VOD usuels) avec décodeur FFmpeg pour les pistes audio non prises en charge par la TV ;
- choix de la piste audio et des sous-titres (préférences mémorisées), sous-titres externes `.srt` / `.vtt` depuis un fichier ou une URL, taille et fond des sous-titres réglables ;
- format d'image (adapter, remplir, zoom), format du flux Live (automatique, MPEG-TS, HLS), stabilité du flux (faible latence, automatique, stable) ;
- bascule automatique de la résolution et de la fréquence d'affichage (24/25/50 Hz), mode tunnel pour la 4K HDR, prise en charge Dolby ;
- reprise automatique après une coupure réseau et messages d'erreur expliquant quoi faire ;
- pas d'avance/retour VOD réglable (10, 30 ou 60 s).

### Recherche, historique et Google TV

- recherche plein texte indexée dans tout le catalogue (préfixes de mots, insensible aux accents), filtrable par Direct, Films ou Séries ;
- historiques Direct, Films et Séries, effaçables séparément ;
- rangée « Continuer à regarder » sur l'accueil Google TV, avec reprise directe dans l'application ;
- titre, numéro et logo de la chaîne publiés dans la MediaSession Android.

### Réglages et application

- contrôle parental par code à 4 chiffres et catégories verrouillées ;
- sauvegarde et restauration des préférences dans un fichier ;
- mises à jour intégrées depuis les Releases GitHub, avec installation silencieuse lorsque la TV l'autorise ;
- identifiants chiffrés localement avec Android Keystore ;
- ouverture sur la dernière page consultée, splash screen, icône adaptative et bannière Android TV.

### Assistant IA (facultatif)

Tout est coupé dès que l'assistant est désactivé (Paramètres › Assistant IA) : aucune requête, travaux annulés, écrans fermés. Chaque fonction tient en **une requête JSON**, construite à partir d'une liste courte de candidats réels de la playlist ; seuls des contenus existants, jamais masqués ni verrouillés, sont affichés. Une icône IA animée signale chaque chargement.

- **Recherche en langage naturel** (« un film d'action des années 90 », au clavier, à la voix sur mobile) : l'IA produit des filtres (genre, région, période) appliqués à l'index local ; ses titres proposés ne sont montrés que s'ils existent dans le catalogue.
- **Ce soir ?** : humeur, durée, compagnie → cinq propositions avec la raison, mêlant films, séries et programmes TV du soir.
- **Collections** : sagas (dans l'ordre) et thèmes du catalogue. **Raisons des recommandations** sous les rangées de l'accueil.
- **Fiches** : avis rapide (public, ambiance, points sensibles) et « Précédemment dans… » sans spoiler pour les séries reprises.
- **Quoi de neuf maintenant ?** : résumé des matchs et programmes en direct, avec accès à la chaîne.
- **Lecteur** : sous-titres bilingues, glossaire de noms propres cohérent entre épisodes, « Conseil de lecture » d'après les mesures du lecteur.
- **Télécommande téléphone** : page web locale (QR code) pour écrire « mets beIN Sports 1 », « reprends ma série », « trouve le match du PSG ».
- **Économie d'appels** : cache disque, pré-calcul de nuit (traductions des nouveautés, collections), cache de prompt chez Claude pour les longs messages, aucune requête à la frappe.

## Commandes de la télécommande

| Écran | Touche | Action |
|---|---|---|
| Partout | Flèches / OK | Naviguer et ouvrir |
| Partout | Retour | Revenir à l'écran précédent (deux appuis sur l'accueil pour quitter) |
| Film / série | OK sur **Bande-annonce** | Ouvrir la bande-annonce dans YouTube |
| Série | Flèches | Choisir une saison et un épisode |
| Direct | `↑` / `CH+` | Chaîne suivante de la catégorie |
| Direct | `↓` / `CH-` | Chaîne précédente de la catégorie |
| Direct | `0`–`9` | Aller directement à un numéro de chaîne |
| Direct | `⏪` / Dernière chaîne | Revenir à la chaîne précédemment regardée |
| Direct | `←` / OK / Menu | Ouvrir la liste des catégories et chaînes |
| Direct | `→` | Liste des versions de la chaîne (réglages de lecture s'il n'y en a qu'une) |
| Versions | OK | Lancer la version ; OK sur la version en cours ferme le panneau |
| Direct | Réglages | Ouvrir les réglages de lecture (audio, sous-titres, format) |
| Direct | Info | Afficher les informations de la chaîne |
| Film / épisode | OK / Lecture-Pause | Mettre en pause ou reprendre |
| Film / épisode | `←` / `⏪` | Reculer du pas choisi (10 s par défaut) |
| Film / épisode | `→` / `⏩` | Avancer du pas choisi (10 s par défaut) |
| Film / épisode | Info / Réglages | Ouvrir les réglages de lecture |

## Construire l'APK

Prérequis : JDK 17, Android SDK 36 et Gradle 9.6.0. La configuration Firebase `app/google-services.json` n'est pas versionnée : placez la vôtre dans `app/` avant de construire.

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

L'APK installable est produit dans `app/build/outputs/apk/debug/app-debug.apk`.

Sur GitHub, le workflow **Android TV APK** s'exécute à chaque push sur `main` :

- l'artefact `streamia-tv-debug-apk` contient l'APK de debug ;
- l'APK optimisé et signé est publié dans la Release `latest` (`streamia-tv.apk`), que l'application utilise pour ses mises à jour ;
- un tag `vX.Y.Z` crée en plus une Release versionnée contenant l'APK.

La signature de l'APK optimisé est décrite dans [docs/release-signing.md](docs/release-signing.md).

## Formats Xtream et M3U pris en charge

L'import reconnaît les URL contenant `/live/`, `/movie/` et `/series/`. Les champs M3U utilisés sont `tvg-id`, `tvg-name`, `tvg-logo` et `group-title`. Une virgule située à l'intérieur d'un attribut entre guillemets ou dans le nom affiché est conservée correctement.

Les données détaillées absentes du M3U (épisodes, saisons, EPG) sont demandées séparément à l'API Xtream, ou à l'URL XMLTV configurée pour la liste. Un `tvg-id` numérique est conservé comme identifiant du fournisseur ; il n'est pas considéré automatiquement comme un identifiant XMLTV universel.

La bande-annonce est lue dans le champ `youtube_trailer` (ou `trailer`) renvoyé par `get_vod_info` / `get_series_info`. Un identifiant YouTube seul, un lien `youtube.com`, `youtu.be`, `embed` ou `shorts` ouvre l'appli YouTube ; un autre lien `http(s)` est ouvert tel quel. Si le champ est vide ou invalide, le bouton n'est pas affiché.

Pour protéger les accès, l'import n'enregistre pas les URL complètes de la playlist dans le cache. Le serveur, l'identifiant et le mot de passe communs sont extraits une seule fois puis chiffrés avec Android Keystore. Les entrées provenant d'un second compte dans le même fichier sont ignorées.

## Installation sur Android TV

Téléchargez `streamia-tv.apk` depuis la Release `latest`, ou activez les options développeur et le débogage réseau de la TV, puis :

```bash
adb connect ADRESSE_IP_TV:5555
adb install -r app-debug.apk
```

L'application détecte au lancement s'il s'agit d'une TV ou d'un téléphone/tablette (`UiModeManager` et `android.software.leanback`, voir `ui/mobile/DeviceKind.kt`) : la TV garde l'interface pilotée à la télécommande en paysage ; le mobile affiche l'interface tactile en portrait (barre d'onglets Accueil / Direct / Films / Séries / Plus, tirer pour actualiser, appui long, feuilles du bas, pavé numérique) et passe en paysage pour le lecteur. Écrans mobiles : Mes listes, Accueil, Direct/Films/Séries, Plus, fiches film/série, Recherche, Guide TV, Matchs du jour, Paramètres, Organiser (catégories), et dans le lecteur : balayage pour zapper, luminosité/volume sur les bords, glisser pour chercher, double-tap ±pas. Contrôle parental, À propos et « Réglages avancés » (ville, IA, sauvegarde, mises à jour) gardent la mise en page TV, en paysage.

## Confidentialité et usage légal

Streamia TV n'inclut aucune chaîne, playlist, adresse de serveur ou abonnement. Utilisez uniquement des flux que vous êtes autorisé à regarder. Les identifiants restent sur la TV et sont chiffrés avec Android Keystore. Ils ne doivent jamais être ajoutés au dépôt, aux journaux ou à un outil d'analyse ; les rapports de plantage Crashlytics sont nettoyés des URL et identifiants avant envoi. Les serveurs `http://` sont acceptés pour compatibilité, mais la connexion n'est alors pas chiffrée ; préférez toujours `https://`.

## Sources techniques

- [Compose for TV](https://developer.android.com/jetpack/androidx/releases/tv)
- [Media3 / ExoPlayer](https://developer.android.com/jetpack/androidx/releases/media3)
- [Android TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality)
- [Moteur de recommandations](docs/recommendation-engine.md)
