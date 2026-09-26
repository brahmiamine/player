# Audit complet de Streamia TV — priorité à la rapidité de l'interface

> Date : 26/09/2026 · Base : branche `claude/app-performance-analysis-yc74df` (commit `53e12ba`)
> Périmètre : les ~30 000 lignes Kotlin de `app/` et `baselineprofile/`, le manifeste, Gradle, la CI.
> Méthode : **lecture statique du code**, sans mesure sur un boîtier. Les gains annoncés sont des
> **estimations** à confirmer par les mesures proposées en §13 (Perfetto, Macrobenchmark, JankStats).
> Les références `fichier:ligne` sont relatives à `app/src/main/java/fr/streamia/tv/`.

---

## 0. Synthèse

Le code montre déjà beaucoup de travail sur la performance : pagination SQLite, écriture en flux,
threads de priorité basse (`BackgroundWork`), clés stables dans les listes, pas de flou temps réel,
baseline profile, caches disque des guides web. Les commentaires expliquent chaque décision. Le
socle est donc sain.

Il reste pourtant **des problèmes structurels** qui rendent l'interface lourde dès que le catalogue
dépasse quelques dizaines de milliers d'entrées, et qui s'aggravent avec l'usage (historique,
enrichissement, profils multiples) :

1. **Un seul ViewModel (3 289 lignes) et un seul état géant (`StreamiaUiState`, ~60 champs).**
   Chaque petite mise à jour (lot de matchs, page chargée, météo, progression, guide) réémet tout
   l'état, recompose la racine et relance des `remember` coûteux sur le thread principal.
2. **Le catalogue en mémoire est recopié en entier à chaque page chargée.** `Catalog` est
   reconstruit (liste complète + index) à chaque page Films/Séries. Si l'utilisateur a réordonné
   ses catégories, `applyUserLibraryToCatalog` reconstruit un `Catalog` **sans reprendre ses index**.
   Ils sont alors recalculés paresseusement, souvent sur le thread principal, pour des dizaines de
   milliers de chaînes.
3. **La base SQLite n'est pas taillée pour les tris et la pagination profonde.** Le tri
   alphabétique utilise `COLLATE LOCALIZED` sans index, la pagination `LIMIT/OFFSET`, et l'index
   plein texte est reconstruit **en entier, pour tous les profils**, à chaque actualisation.
4. **Les préférences utilisateur (favoris, historique…) sont un gros JSON dans SharedPreferences,**
   relu et réécrit en entier à chaque zap, toutes les 15 s de film et à chaque favori. Des
   SharedPreferences sont aussi écrites **à chaque déplacement du focus** dans la grille VOD.
5. **Le parsing XMLTV compile une regex et crée des `SimpleDateFormat` pour chaque date.** Sur un
   guide d'un million de programmes, cela représente des minutes de CPU qui concurrencent l'interface.
6. **Trop de travail de fond et trop de sources web.** On compte environ 15 domaines tiers, du
   scraping HTML (Jsoup) sur le boîtier, des boucles de rafraîchissement toutes les 2 min, et un
   enrichissement à 600 requêtes fournisseur + 800 requêtes TMDB par passage, toutes les 2 h et à
   chaque lancement. Chaque rapprochement guide ↔ chaînes normalise à nouveau toutes les chaînes
   Direct.
7. **Le chargement des images est fait maison.** Il n'a pas de cache d'échec (un logo cassé est
   redemandé à chaque apparition), pas de bitmaps matérielles, pas de préchargement, et son client
   OkHttp est distinct de celui du lecteur.
8. **Il manque les outils de mesure.** Pas de StrictMode, de JankStats ni de traces. Le
   Macrobenchmark ne couvre que le Direct. Le profil de démarrage est identique au baseline profile,
   ce qui annule l'optimisation de la disposition du DEX.

**Les 12 chantiers les plus rentables, par ordre de priorité.** Tableau détaillé en §14.

| # | Chantier | Effet attendu sur la fluidité | Effort |
|---|---|---|---|
| 1 | Découper l'état : un `StateFlow` par domaine (catalogue, bibliothèque, accueil, EPG, matchs, lecteur) | Beaucoup moins de recompositions et de calculs sur le thread principal | M |
| 2 | Corriger `applyUserLibraryToCatalog` (hériter des sections) et arrêter de recopier le `Catalog` à chaque page | Fin des gels de 100 ms à 1 s au chargement des pages VOD | S |
| 3 | Pagination par curseur (keyset) et colonne de tri alphabétique pré-calculée et indexée | Pages VOD triées en millisecondes au lieu de secondes sur « Tout » | M |
| 4 | Bibliothèque (favoris, historique, masqués) en tables SQLite ; plus d'écriture de préférences au focus | Zap et navigation sans écriture disque bloquante | M |
| 5 | Parseur de dates XMLTV écrit à la main, sans regex ni `SimpleDateFormat` | Synchro EPG 10 à 50× moins coûteuse en CPU | S |
| 6 | FTS maintenu par profil et au fil de l'écriture, sans `rebuild` global | Actualisation du catalogue bien plus courte | S/M |
| 7 | Chargeur d'images : cache d'échec, `HARDWARE`, taille exacte, préchargement (ou Coil 3) | Défilement des grilles et listes sans saccade | S/M |
| 8 | Index partagé des chaînes Direct (normalisation une seule fois) pour tous les rapprochements | Accueil et matchs plus rapides, moins de CPU | M |
| 9 | Petit backend agrégateur (guides, matchs, scores, JustWatch) | ~25 requêtes et parsings Jsoup remplacés par 1 JSON | M/L |
| 10 | EPG : ne charger que la fenêtre visible (chaînes × heures) sans descriptions | Ouverture du guide plus rapide, dizaines de Mo en moins | M |
| 11 | Limiter l'enrichissement de métadonnées et le lancer seulement au repos | Moins de concurrence CPU et réseau, moins de risque de bannissement | S |
| 12 | Outils de mesure (StrictMode en debug, JankStats, Macrobenchmark VOD, EPG et accueil) | Mesurer au lieu de supposer | S |

---

## 1. Choix technologiques — analyse et verdict

| Choix | Où | Verdict | Recommandation |
|---|---|---|---|
| **Kotlin 2.4 + Jetpack Compose + `androidx.tv:tv-material` 1.1** | tout `ui/` | ✅ Bon choix moderne ; le *strong skipping* est actif par défaut (compilateur Compose 2.x). Compose reste plus lourd que Leanback sur les petits boîtiers (Mali-450, 1–2 Go) : chaque recomposition inutile se voit. | Garder, mais réduire les recompositions (§3) et l'overdraw (§6). Voir `LazyColumn`/`LazyRow` avec `contentType`. |
| **Un seul `Activity`, navigation par `when(state.screen)`** | `ui/StreamiaApp.kt:111` | ⚠️ Simple, mais chaque écran est **détruit** en le quittant : état de défilement, `remember` et index sont perdus, puis reconstruits à la main (`ContentReturnContext`, `BrowserNavigationStore`, `LiveBrowserReturnState`). Beaucoup de code et de travail au retour. | `SaveableStateHolder` par écran (ou Navigation Compose) pour garder l'état des écrans de menu en mémoire ; supprimer une partie de la logique de restauration manuelle. |
| **Un seul ViewModel + un seul `StreamiaUiState`** | `ui/StreamiaViewModel.kt` (3 289 lignes) | ❌ Principal frein architectural (§3). | Découper en contrôleurs par domaine exposant chacun leur `StateFlow`. |
| **SQLite brut (`SQLiteOpenHelper`)** pour catalogue, EPG, recommandations | `data/CatalogDatabase.kt`, `EpgDatabase.kt`, `RecommendationStore.kt` | ✅ Bon pour la performance (contrôle total, pas de surcoût). WAL activé. ⚠️ Schéma perfectible (§4). | Garder SQLite. Room n'apporterait rien en vitesse ; le gain est dans le schéma, les index et la pagination. |
| **SharedPreferences + blobs JSON** (bibliothèque, historique, navigation, stats de versions, session, réglages) — 12 fichiers | `data/UserLibraryStore.kt`, `BrowserNavigationStore.kt`, `LiveVersionStatsStore.kt`… | ❌ Réécriture complète du fichier à chaque modification, parsing sur le thread principal, `apply()` que l'Activity attend à `onPause`/`onStop`. | Bibliothèque, historique et stats de versions dans SQLite ; SharedPreferences réservées aux petits réglages (ou Jetpack DataStore). |
| **3 piles HTTP** : `HttpURLConnection` (Xtream, XMLTV, M3U, scrapers, football), OkHttp du lecteur, OkHttp des images | `data/XtreamClient.kt:494`, `player/StreamiaPlayerFactory.kt:36`, `ui/RemoteComponents.kt:423` | ⚠️ Connexions et TLS non partagés, pas de cache HTTP commun, réglages de délais disparates. | **Un seul `OkHttpClient` applicatif** (pool, cache disque, HTTP/2, DNS) décliné par `newBuilder()` pour chaque usage. |
| **`org.json` (DOM)** pour les réponses moyennes, `android.util.JsonReader` (flux) pour les grosses listes | `XtreamClient` | ✅ Le flux pour les grosses listes est le bon choix. `org.json` convient aux petites réponses. | Rien d'urgent. Moshi codegen ou kotlinx.serialization seulement si le parsing des fiches ressort dans les traces. |
| **Jsoup** : scraping de 5 sites HTML sur le boîtier | `liveonsat/`, `tvprogramme/`, `ukguide/` | ⚠️ Pages lourdes parsées sur le processeur de la TV, fragiles au moindre changement de site, CGU souvent contraires. | Déporter dans un petit service agrégateur (§10). |
| **Chargeur d'images maison** | `ui/RemoteComponents.kt:381-460` | ⚠️ Correct (LRU en octets, 6 téléchargements parallèles, dédoublonnage) mais incomplet (§7). | Coil 3 (Compose) ou compléter le chargeur maison. |
| **Media3 1.11 + OkHttp + FFmpeg (Jellyfin)** | `player/` | ✅ Bon choix. | Réglages de démarrage du Direct (§8). |
| **WorkManager** (EPG toutes les heures, enrichissement toutes les 2 h + à chaque lancement) | `work/` | ⚠️ Cadence et volume trop élevés pour l'enrichissement (§9). | Contraintes `setRequiresDeviceIdle`, plafonds plus bas. |
| **Firebase Crashlytics** | `logging/CrashReporter.kt` | ✅ Utile ; initialisé au démarrage (`FirebaseInitProvider`), quelques dizaines de ms sur boîtier. | Acceptable. |
| **Baseline profile + `profileinstaller`** | `app/src/release/generated/…` | ✅ Présent. ⚠️ Un seul parcours (Direct + zap). `startup-prof.txt` est **identique** au baseline profile (26 703 lignes chacun), ce qui annule l'optimisation de disposition du DEX au démarrage. Profil généré le 23/09, code modifié depuis. | Parcours supplémentaires (accueil, VOD, EPG, recherche, fiche). Profil de démarrage limité au vrai démarrage. Régénération en CI ou à chaque version. |
| **`minSdk 23`, `largeHeap=true`** | manifeste, Gradle | ⚠️ `largeHeap` agrandit le tas, donc les pauses GC, et le tampon ExoPlayer vit sur ce tas (35 % de `largeMemoryClass`). | Garder `largeHeap` mais réduire les allocations (catalogue recopié, EPG complet en RAM). |
| **R8** | `app/build.gradle.kts` | ✅ `isMinifyEnabled` + `shrinkResources`. `android.r8.strictFullModeForKeepRules=false`. | Vérifier que le mode complet R8 reste actif (il l'est par défaut depuis AGP 8). |

---

## 2. Démarrage à froid — ce qui s'exécute sur le thread principal

Chemin : `MainActivity.onCreate` → `XtreamRepository.get()` → création du ViewModel →
`StreamiaTvRoot`.

| Étape | Fichier | Coût probable | Problème |
|---|---|---|---|
| `XtreamRepository` construit ~20 objets (3 `SQLiteOpenHelper`, stores, clients) | `data/XtreamRepository.kt:53-71` | Faible (ouverture paresseuse des bases) | OK. |
| `init` du ViewModel : `repository.profiles()` | `ui/StreamiaViewModel.kt:217-222` | **Élevé sur boîtier** : déchiffrement Android Keystore (appel système) + JSON | Sur le thread principal avant la première image. |
| `repository.appSettings()` | idem | Faible à moyen : premier accès à SharedPreferences = attente du chargement disque | Sur le thread principal. |
| `StreamiaTvRoot` : `sessionStore.load()`, `canResumeLiveOnStartup` → `repository.library()` | `ui/StreamiaTvRoot.kt:103-125` | **Moyen** : parsing du JSON complet de la bibliothèque (historique de 200 entrées avec résumés) | Sur le thread principal. |
| `resumeStartup` → `repository.library()`, `repository.profiles()` | `ui/StreamiaViewModel.kt:289-300` | Moyen | Sur le thread principal. |
| `LivePlaybackSession` → création d'un `ExoPlayer` | `ui/StreamiaTvRoot.kt:54` | Moyen (≈ 20–60 ms) | Nécessaire, acceptable. |
| `openProfile` → `cache.load` : `hasProfile` + catégories + 3 × récents + clés de contexte + **`COUNT(*) GROUP BY` sur toute la table** | `data/CatalogDatabase.kt:282-302`, `:612` | Élevé sur gros catalogue (parcours complet de l'index) | Sur IO (bien), mais ce passage retarde l'accueil. |

**Recommandations**

- Tout charger dans une coroutine `Dispatchers.IO` lancée dans `init`, avec un état `booting`
  affiché d'ici là. Aucun accès Keystore, SharedPreferences ou JSON ne doit précéder la première image.
- Stocker les **comptes par catégorie** dans une table `catalog_counts` écrite au commit du
  catalogue : l'ouverture ne fait plus de `GROUP BY` sur des centaines de milliers de lignes.
- Garder en mémoire les profils déjà migrés au lieu du `SELECT` de `hasProfile` avant **chaque**
  requête (`data/CatalogCache.kt:88` et suivantes via `ensureMigrated`).
- Supprimer les appels morts `resolvedCatalogIfLayoutUnchanged`/`saveResolvedCatalog`
  (`data/CatalogCache.kt:69-83`) : ils ne font que supprimer un fichier à chaque démarrage et
  à chaque fusion.
- Appeler `reportFullyDrawn()` quand l'accueil est utilisable, pour que Macrobenchmark mesure
  aussi le « temps jusqu'à l'affichage complet ».

---

## 3. Architecture de l'état — cause n° 1 des lenteurs perçues

### 3.1 Constat

- `StreamiaUiState` (`ui/StreamiaViewModel.kt:3106-3186`) regroupe **tout** : catalogue brut et
  personnalisé, bibliothèque, réglages, EPG du jour, guides de l'accueil, matchs, recommandations,
  météo, pages VOD, catégories en chargement, messages, pile de navigation…
- `StreamiaTvRoot` **et** `StreamiaApp` collectent tout l'état
  (`ui/StreamiaTvRoot.kt:52`, `ui/StreamiaApp.kt:55`). Chaque émission recompose la racine.
  Les écrans enfants sont souvent sautés (*strong skipping*), mais :
  - les `remember(...)` de `StreamiaApp` sont réévalués. Exemple : `withoutHiddenChannels` sur
    tous les matchs (`ui/StreamiaApp.kt:66-71`) est **recalculé à chaque lot de 50 matchs résolus**,
    même quand l'écran affiché est le navigateur ;
  - chaque `_uiState.update` compare l'ancien et le nouvel état avec `equals` d'une *data class*
    qui contient un `Catalog` (data class aussi, avec des listes de dizaines de milliers d'entrées).
- Les sources d'émission sont très nombreuses : `setCategoryLoading` (2 par page),
  `categoryLoadErrors`, `mergeVodPage`, `todayEpgGuide`, `liveOnSatMatches` (lot par lot),
  `homeJustWatchRows` (une par section), `homePendingBlocks`, `weather`, `library` (à chaque
  progression), `lastViewedEntry`…

### 3.2 Le `Catalog` recopié à chaque page

- `mergeVodPage` (`ui/StreamiaViewModel.kt:1290-1339`) → `withMaterializedEntries`
  (`domain/XtreamModels.kt`) recopie **toutes** les entrées matérialisées (dont les
  5 000 à 100 000 chaînes Direct) dans une nouvelle `LinkedHashMap`, puis une nouvelle liste.
- Il appelle ensuite `repository.customizedCatalog(...)` → `applyUserLibraryToCatalog`
  (`data/UserLibraryStore.kt:288-333`). Dès qu'un **ordre de catégories** ou un **déplacement**
  existe, cette fonction :
  - parcourt toutes les entrées (`catalog.entries.map`) ;
  - construit un **nouveau `Catalog(...)` (`:326`) sans `inheritSections`**. Les index par
    section (`byCategory`, `byKey`) de **toutes** les sections, Direct compris, sont perdus ;
  - perd aussi `fullSections`.
- Ces index sont reconstruits **paresseusement au premier accès**, souvent **dans la
  composition** : `catalog.entry(...)` dans `LiveCatalogLayout` (`ui/BrowserScreen.kt:664`),
  `computeEntries()` exécuté de façon synchrone au changement de catégorie
  (`ui/BrowserScreen.kt:310`), `favoriteEntriesForType`, etc. Sur 50 000 chaînes, cela représente
  un `filter` + `groupBy` + `associateBy` sur le thread principal, **à chaque page chargée**.

**Correctifs**

1. Dans `applyUserLibraryToCatalog`, construire le résultat par `copy(...)` +
   `inheritSections(...)` pour les sections non touchées, et conserver `fullSections`. Si seul
   `categoryOrder` est non vide, ne pas toucher aux entrées du tout.
2. Remplacer la liste plate `entries` par **une structure par section** (`Map<MediaType,
   SectionIndex>`) : fusionner une page Films ne touche plus que la section Films.
3. Précalculer les index sur `Dispatchers.Default` **avant** de publier le catalogue (forcer
   `section(type)` pour les types concernés), pour que le thread principal ne tombe jamais sur un
   `lazy` froid.

### 3.3 Découpage proposé

```
CatalogController   : StateFlow<CatalogState>       (catalogue léger, pages, chargements)
LibraryController   : StateFlow<UserLibrarySnapshot> (favoris, masqués, historique)
HomeController      : StateFlow<HomeState>          (guides, matchs, recos, météo)
EpgController       : StateFlow<EpgState>
PlayerController    : StateFlow<PlayerUiState>      (déjà séparé — bon exemple à généraliser)
NavigationController: StateFlow<NavState>           (écran, pile, contexte de retour)
```

Chaque écran ne collecte que ce qu'il affiche. Les éléments dérivés (matchs filtrés, cartes de
l'accueil, catégories visibles) sont calculés **dans le contrôleur**, sur `Dispatchers.Default`
avec `stateIn`/`combine`, et plus dans des `remember` de composition.

---

## 4. Bases de données — schéma, index, requêtes

### 4.1 `catalog-v5.db` (`data/CatalogDatabase.kt`)

| Constat | Ligne | Impact | Recommandation |
|---|---|---|---|
| `profile_id TEXT` (UUID de 36 caractères) et `media_type TEXT` répétés dans chaque ligne **et** dans 4 index | `:29-88` | Base et index 2 à 3 fois plus gros, lectures disque plus lentes sur eMMC lent | `profile_rowid INTEGER` (table de correspondance) et `media_type INTEGER` (0/1/2). |
| `plot` stocké dans la table principale et lu à **chaque page** de liste | `:57-76`, `ENTRY_COLUMNS` | Pages plus lourdes (I/O et RAM : 5 000 films retenus × résumé) | Colonne de projection « liste » sans `plot` ; `plot` lu seulement pour une fiche ou pour les recommandations. |
| Tri alphabétique `display_name COLLATE LOCALIZED` **sans index** | `:331` | Tri complet de la section **à chaque page** : sur « Tout Films » (180 000 lignes), plusieurs secondes par page sur boîtier | Colonne `sort_key` (nom normalisé sans accents, minuscules) calculée à l'écriture, avec index `(profile, type, category, sort_key)` et `(profile, type, sort_key)`. |
| Tri par note : expression `(rating IS NULL OR …), rating DESC` | `:336` | Tri complet à chaque page | Colonne `rating_rank` normalisée (NULL/hors échelle → -1) avec index. |
| `RecentlyAdded` par catégorie : `idx_catalog_recent` ne contient pas `category_id` | `:84` | Tri complet de la catégorie | Index `(profile, type, category, navigable, added_at DESC)`. |
| Pagination `LIMIT ? OFFSET ?` | `:351` | Page *n* = parcours de *n × 500* lignes ; coût croissant en descendant | **Pagination par curseur** : `WHERE (sort_key, media_id) > (?, ?) ORDER BY sort_key, media_id LIMIT 500`. |
| Index plein texte FTS4 à **contenu externe**, reconstruit par `'rebuild'` **pour toute la table (tous les profils)** à chaque commit et à chaque suppression | `:96`, `:246`, `:275` | Actualisation d'un profil = réindexation de **tous** les profils (centaines de milliers de lignes) dans la transaction d'écriture | FTS5 (ou FTS4) avec `profile_id UNINDEXED`, insertion **au fil de l'écriture** dans la même transaction, suppression ciblée par profil. |
| Remplacement : `DELETE` de tout le profil puis réinsertion dans **une** transaction avec 4 index actifs | `:146-158` | WAL de plusieurs centaines de Mo, transaction de plusieurs minutes | Écrire dans des tables temporaires sans index, créer les index, puis échanger ; ou mise à jour différentielle (upsert + suppression des absents) pour ne réécrire que ce qui change. |
| `loadCounts` = `COUNT(*) GROUP BY` sur tout le profil à chaque ouverture | `:612` | Parcours complet de l'index à chaque ouverture de liste | Table `catalog_counts` écrite au commit. |
| Recherche : `rowid IN (SELECT docid … MATCH ?)` sur tous les profils, puis tri par `number` | `:437-446` | Correct, mais les correspondances des autres profils sont lues | Colonne de profil dans le FTS (voir plus haut). |
| `PRAGMA` non réglés | — | Réglages par défaut d'Android | En WAL : `synchronous=NORMAL`, `temp_store=MEMORY`, `cache_size` plus grand pendant les imports. |

### 4.2 `epg-v1.db` (`data/EpgDatabase.kt`)

- `loadGuide` (`:265`) lit **toute la journée, pour toutes les chaînes, avec les descriptions**.
  L'écran n'en affiche qu'une fenêtre de 2 h sur une dizaine de chaînes visibles. Résultat :
  des dizaines de Mo en RAM (jusqu'à 3 jours dans `EpgGuideMemoryCache`, plus `todayEpgGuide`
  dans l'état).
  → Requête **par fenêtre** (chaînes visibles ± marge × heures visibles), descriptions chargées
  seulement au focus d'un programme.
- `EpgGuide` construit `channelsByAlias` de façon **impatiente**, avec 3 regex par alias
  (`domain/XtreamModels.kt`, `epgLookupAliases`). La résolution chaîne → guide est refaite à chaque
  ligne composée (`ui/BrowserScreen.kt:921`, `ui/EpgScreen.kt:235`).
  → Stocker à la synchro une table `epg_channel_map(entry_key → channel_id)` : la résolution
  devient une simple jointure.
- Pas d'élagage temporel : tous les jours passés et futurs du XMLTV sont gardés.
  → Ne garder que [maintenant − 1 j, maintenant + 7 j].
- Remplacement complet dans une transaction avec 2 index sur `epg_programs`
  → même conseil que pour le catalogue (tables temporaires, index créés après).
- `loadNowContext` : 3 requêtes par chaîne, toutes les 30 s → acceptable. Une seule requête
  `UNION ALL` suffirait.

### 4.3 `RecommendationStore`

- `features(profileId)` (`data/RecommendationStore.kt:394`) charge **toute** la table (jusqu'à
  15 000 fiches enrichies et plus, avec résumés). Elle est appelée par
  `recommendationContentFeatures` (`data/XtreamRepository.kt:468`) **à chaque ouverture de fiche**
  et à chaque calcul des recommandations de l'accueil, pour n'en garder que quelques centaines.
  → Requête `WHERE (media_type, media_id) IN (...)` par lots, comme `loadEntriesByKeys`.
- Pas d'index sur `tmdb_id`. `keysByTmdbId` fait un parcours complet.

### 4.4 Bibliothèque utilisateur (SharedPreferences + JSON)

`data/UserLibraryStore.kt` : favoris, masqués, verrouillés, vus, ordre des catégories,
déplacements et **200 entrées d'historique avec résumés**, dans **un seul JSON par profil**, et
**tous les profils dans le même fichier** de préférences.

- `mutate` relit, parse, modifie, resérialise et réécrit **tout le fichier XML** des préférences.
- Appelé à chaque zap (`onProgress` après 2,5 s), au bout de 20 s d'aperçu, **toutes les 15 s**
  pendant un film (`player/PlaybackControls.kt:62`), à chaque favori, masquage ou vu.
- `apply()` : Android attend la fin des écritures en attente au `onPause`/`onStop` de l'Activity,
  ce qui provoque un gel (ou un ANR) visible en sortie d'écran.

→ Tables `library_flags(profile, key, kind)` et `history(profile, key, position, duration,
updated_at, snapshot)`. Chaque action devient **un `INSERT`/`DELETE` d'une ligne**.

---

## 5. Écrans, listes et interactions

### 5.1 Navigateur Direct (`ui/BrowserScreen.kt`)

| Problème | Ligne | Recommandation |
|---|---|---|
| `computeEntries()` **synchrone sur le thread principal** au changement de catégorie : `pageKeys.mapNotNull(catalog::entry)`, filtres, et **tri alphabétique** de dizaines de milliers de chaînes si le mémo ne le connaît pas | `:310` | Afficher tout de suite la liste précédente (ou un squelette) et calculer sur `Default`. Mieux : tri fait en base (`sort_key`) ou pré-calculé une fois par catalogue. |
| `entryIndexByKey = entries.associate` recalculé à chaque changement de liste (50 000 entrées) | `:802` | Le calculer en arrière-plan avec la liste, ou utiliser la position connue par le contrôleur. |
| `navigationStore.listPosition(...)` (lecture de SharedPreferences) **dans la composition**, à chaque recomposition de `BrowserScreen` | `:371` | Lire une fois dans un `remember(selectedCategoryId)`. |
| Programme en cours par ligne : `todayEpgGuide.forEntry(entry)` → alias par regex + `epgNowContextAt` (filtre de tous les programmes de la chaîne) à chaque ligne composée pendant le défilement | `:921` | Précalculer `Map<entryKey, EpgProgram>` du programme en cours dans le contrôleur EPG (hors thread principal), rafraîchie chaque minute. |
| Chaque ligne porte ~10 modificateurs (`onPreviewKeyEvent`, `onFocusChanged`, `clickable`, `focusable`, `graphicsLayer`, `clip`, `background`, `border`…) et une ombre colorée au focus | `ui/RemoteComponents.kt:188-238` | Acceptable ; voir §6 pour l'ombre. Ajouter `contentType` aux `items` pour la réutilisation. |
| `LaunchedEffect` qui recalcule toute la liste dès que `catalog` change (donc à chaque page ou fusion en arrière-plan) | `:316-323` | Avec l'état découpé (§3), la liste Direct ne dépend plus des pages VOD. |

### 5.2 Navigateur Films/Séries

| Problème | Ligne | Recommandation |
|---|---|---|
| **Écriture de SharedPreferences à chaque déplacement du focus** dans la grille (`onEntryFocused → navigationStore.saveEntry`) | `:447`, `:1519` | Mémoriser en RAM et écrire à la sortie de l'écran (ou avec un anti-rebond de 1 s). |
| Pages de 500 entrées fusionnées dans le `Catalog` global, puis retrouvées par `pageKeys.mapNotNull(catalog::entry)` | `:275`, VM `:1290` | La grille lit directement une **liste paginée** (Paging 3 `PagingSource` SQLite avec curseur, ou fenêtre maison) indépendante du `Catalog`. |
| 2 catégories voisines préchargées (2 × 500 lignes + 2 fusions du catalogue) à chaque sélection | VM `:1196-1207` | Garder le préchargement, mais seulement les **50 premières** entrées (ce que l'écran montre), pas 500. |
| Affiches de 175 dp décodées jusqu'à 2× 480 px (`inSampleSize` en puissance de 2) | `ui/RemoteComponents.kt:369`, `:439-452` | Décodage à la taille exacte (voir §7) ; réécrire les URL TMDB `/original/` en `/w342/`. |

### 5.3 Accueil (`ui/HomeScreen.kt`)

| Problème | Ligne | Recommandation |
|---|---|---|
| `item { }` **sans `key` ni `contentType`** pour la plupart des rangées (15 et plus) : quand un squelette devient contenu ou qu'une rangée apparaît, les positions se décalent et les rangées sont recomposées | `:358`, `:388`, `:410`… | `item(key = HomeRowKey.X, contentType = "row-kind")` partout. |
| `liveRowsNowEpochMillis` mis à jour toutes les 30 s et lu à la racine de `HomeScreen` : tout l'accueil est recomposé | `:222` | Lire l'horloge **dans** les cartes concernées (ou `derivedStateOf`), ou la passer en lambda `() -> Long`. |
| Boucle de rafraîchissement toutes les 2 min : 4 guides + matchs + météo, chacun relisant son cache disque (JSON) | `:249-256` | Un seul ordonnanceur dans le contrôleur de l'accueil, basé sur les échéances réelles et non sur une boucle. |
| `FootballScoresRow` fait ses propres appels réseau **depuis la composition** (BBC + thesportsdb) avec son cache global | `ui/FootballScoresRow.kt:253-287` | Déplacer dans le contrôleur de l'accueil (ou dans l'agrégateur, §10). |
| Recommandations : `features()` complet + `similarityCandidates` par source (4) + index de candidats | VM `:2199-2304` | Voir §4.3 ; le résultat est déjà mis en cache 5 min (bien). |

### 5.4 Guide TV (`ui/EpgScreen.kt`)

- Liste de toutes les chaînes visibles avec, par ligne, `guide.forEntry(channel)` (alias par regex)
  et `blocksInWindow`. → Carte chaîne → programmes pré-résolue (§4.2) et fenêtre SQLite.
- `timeRange()` crée un formateur de date à chaque appel (`:554-557`) → formateur partagé
  (`DateTimeFormatter`, sûr entre threads).

### 5.5 Lecteur (`ui/PlayerScreen.kt`, 1 886 lignes)

| Problème | Ligne | Recommandation |
|---|---|---|
| VOD : `positionMs`/`durationMs` mis à jour **chaque seconde** et lus dans le corps de `PlayerScreen` (contenu `inline` d'un `Box`, `positionMs = positionMs`) : **tant que le bandeau est affiché, toute la fonction de 1 800 lignes est recomposée chaque seconde** | `:642`, `:1210` | Passer `() -> Long` à `PlayerInfoBand` pour que seule la timeline lise la position. |
| `recordPlayback` toutes les 15 s → réécriture du JSON complet + `publishWatchNext` (≈ 10 appels IPC au fournisseur TV) | VM `:1558-1580`, `:1568` | Publier « Continuer à regarder » seulement à la pause, à la sortie ou en fin de lecture. |
| `LiveVersionStatsStore` : `load` (JSON dans SharedPreferences) **dans un `remember` de composition** ; `recordCheck`/`recordWatch` écrivent les préférences depuis la boucle du thread principal | `:732-760`, `data/LiveVersionStatsStore.kt:74` | Table SQLite (ou cache mémoire + écriture groupée hors thread principal). |
| Boucle de contrôle Direct chaque seconde (`videoDecoderCounters`) : utile, légère | `:788-851` | OK. |
| Un `MediaSession` et un `ExoPlayer` VOD recréés à chaque film | `:234-241` | OK (un seul lecteur VOD à la fois). |

### 5.6 Recherche, fiches, organiseur

- Recherche : anti-rebond de 220 ms, requête SQL (bien). La recherche SQL relit **les déplacements
  de la bibliothèque** à chaque frappe (`data/CatalogCache.kt:175`) → coût faible, sans urgence.
- Fiches : « Autres versions » = une recherche SQL à chaque ouverture (bien). « Similaires » :
  jusqu'à 12 appels `get_vod_info` fournisseur par fiche ouverte (`SIMILAR_ENRICH_LIMIT`), par
  lots de 4 → trafic fournisseur notable. Réduire à 4, ou les réserver au passage de fond.
- Organiseur : charge **les trois sections entières** en mémoire (`showOrganizer`,
  VM `:1133-1136`) → pic mémoire sur un catalogue de 200 000 VOD. Travailler par catégorie
  sélectionnée avec des requêtes SQL paginées.

### 5.7 Import M3U : tout le catalogue en mémoire

`saveM3uImport` renvoie `imported.catalog`, **le catalogue complet déjà en mémoire**
(`data/XtreamRepository.kt:1011`). Il est publié tel quel dans l'état (`mergeCatalog` et
`showCatalog`). Contrairement à Xtream, qui relit la version légère, un profil M3U reste **non
paginé** (`isPaged == false`) jusqu'au prochain redémarrage : filtres et tris en mémoire sur
100 000 entrées et plus, zapping, index… Et une M3U distante s'actualise automatiquement
(6 h par défaut).

→ Après `cache.save`, renvoyer `cache.load(id)` (version légère), comme pour Xtream. Parser le M3U
en flux vers la base (comme `XtreamClient`), sans matérialiser la liste.

---

## 6. Thème, composants, graphismes et animations

| Élément | Fichier | Analyse | Recommandation |
|---|---|---|---|
| **`GlassBackdrop`** : 2 à 3 dégradés radiaux plein écran, dessinés **à chaque image** sous tous les écrans ; `Brush.radialGradient` alloué dans `drawBehind` | `ui/GlassComponents.kt:40-60` | Remplissage plein écran avec mélange alpha à chaque frame (défilement, animation de focus) : coûteux pour les GPU Mali-4xx/T7xx | `drawWithCache` (brosse créée une fois) ; mieux, **une image pré-rendue** (bitmap 960×540 étirée, ou `graphicsLayer` mis en cache) puisque le fond est statique par écran. |
| **Panneaux « verre »** translucides superposés (fond, rail, liste, bandeau, cartes) | `GlassSurface`, `BrowserScreen` | **Overdraw 3 à 5×** : chaque pixel est peint plusieurs fois avec de la transparence | Couleurs opaques pré-composées (le fond est connu), ou n'appliquer la transparence qu'au-dessus de la vidéo. Vérifier avec « Afficher le dépassement GPU ». |
| **`FocusableSurface`** : échelle animée en `graphicsLayer` (bien), **ombre colorée de 18 dp** au focus et de 10 dp en permanence pour les boutons `accent` | `ui/RemoteComponents.kt:120-238` | Une ombre colorée (`ambientColor`/`spotColor`) est chère à rendre ; elle suit le focus à chaque pression de touche | Remplacer l'ombre par un halo pré-rendu (9-patch ou `drawBehind` d'un dégradé mis en cache), ou la supprimer sur les boîtiers « lents ». |
| `AccentPill` : ombre de 14 dp permanente | `ui/GlassComponents.kt:156-175` | Même remarque | Halo statique. |
| Animations : focus (110 ms), panneau Direct (220/160 ms), squelettes, chargeur d'accueil (`rememberInfiniteTransition`) | divers | Courtes et raisonnables ; squelettes bien conçus (une pulsation par groupe, lue en `graphicsLayer`, `HomeSkeletons.kt:80`) | Rien d'urgent. Chaque rangée en squelette a sa propre transition infinie : une seule pulsation partagée pour tout l'accueil suffirait. |
| `ResponsiveTvViewport` : densité recalculée via `BoxWithConstraints` à la racine | `ui/ResponsiveTvViewport.kt` | `BoxWithConstraints` = sous-composition à la racine : toute mesure la recompose | `Layout` simple, ou calcul une fois depuis `LocalConfiguration`. |
| Typographie : police système `sans` | `res/values/themes.xml` | Bon (pas de police à charger) | — |

---

## 7. Images (logos, affiches)

`ui/RemoteComponents.kt:336-460`.

**Ce qui est bien :** LRU dimensionné en octets, 6 chargements parallèles au plus, dédoublonnage
des requêtes identiques, annulation hors écran, cache HTTP disque de 64 Mo, `RGB_565` pour les
affiches.

**Ce qui manque :**

1. **Aucun cache d'échec.** Une URL 404 ou morte (fréquent en IPTV) est redemandée **à chaque
   apparition** de la ligne, donc une requête réseau à chaque défilement. → LRU des échecs (URL →
   instant de l'échec, TTL de 30 min), vidé au retour du réseau.
2. **Pas de `Bitmap.Config.HARDWARE`** (API 26+) : chaque nouvelle image est téléversée vers le GPU
   au premier dessin, sur le thread de rendu, ce qui cause des micro-saccades au défilement.
3. **Décodage en puissance de 2** : jusqu'à 2 fois la taille utile. → `ImageDecoder` (API 28+) avec
   `setTargetSize`, ou `inDensity`/`inTargetDensity` pour une taille exacte.
4. **Pas de préchargement** des lignes ou cartes suivantes dans la direction du défilement.
5. **Client OkHttp séparé** de celui du lecteur (connexions TLS non partagées avec le même
   serveur IPTV).
6. `response.body.bytes()` : l'image entière en mémoire avant décodage (acceptable).

→ **Coil 3** (`coil-compose` + `coil-network-okhttp`) règle les points 2 à 6 : taille exacte,
bitmaps matérielles, préchargement via `ImageLoader.enqueue`, client partagé. Seul le cache d'échec
reste à ajouter via un intercepteur.

---

## 8. Lecteur et zapping (Media3)

**Ce qui est bien :** un seul lecteur Direct partagé entre l'aperçu et le plein écran (pas de
recréation des décodeurs au zap), zap par anti-rebond (350 ms), bascule TS/HLS/HTTP(S) mémorisée,
tampon borné en octets, délai de connexion de 4 s, une seule relance réseau pour le Direct.

**Pistes pour zapper plus vite :**

- `DefaultExtractorsFactory().setTsExtractorFlags(FLAG_ALLOW_NON_IDR_KEYFRAMES or
  FLAG_DETECT_ACCESS_UNITS)` : première image plus tôt sur les flux TS qui n'envoient pas d'IDR
  au début (fréquent en IPTV).
- Tester `DefaultRenderersFactory.forceEnableMediaCodecAsynchronousQueueing()` sur les boîtiers
  Android 9–11 : débit du décodeur plus régulier. Media3 l'active déjà par défaut sur Android 12+.
- Préchauffer la connexion (DNS, TCP, TLS) vers le serveur du fournisseur à l'ouverture du Direct
  via le client OkHttp **partagé**.
- `bufferForPlaybackMs` à 350 ms en Auto : correct. En Faible latence, 250 ms.
- `player.setMediaItem` + `prepare()` à chaque zap : correct. Éviter les `seekTo` inutiles sur le
  Direct.

---

## 9. Tâches de fond — inventaire

| Tâche | Déclencheur | Fréquence | Coût | Risque ou problème |
|---|---|---|---|---|
| `EpgSyncWorker` | WorkManager | 1 h (sort vite si frais) | Faible, sauf téléchargement XMLTV | La synchro **complète** (voir parsing ci-dessous) tourne aussi au premier affichage de l'EPG et après chaque actualisation du catalogue. |
| **Synchro XMLTV** (`XmlTvRepository.parseToSink`) | Worker, ouverture du Direct, bouton | Selon `autoRefreshHours` (6 h) | **Très élevé** : `parseDate` compile `Regex("\\s+")` et crée jusqu'à 3 `SimpleDateFormat` **par date** (2 par programme) (`data/XmlTvRepository.kt:286-302`) | Minutes de CPU sur boîtier. **Correctif prioritaire** : parseur manuel `yyyyMMddHHmmss ±HHMM` → epoch (sans allocation), regex compilée une fois. |
| **Actualisation du catalogue Xtream** | Ouverture de profil (si dû), toutes les 24 h au plus | 24 h | Élevé : téléchargement de 3 listes + écriture de toute la table + **FTS `rebuild` global** | Voir §4.1 (mise à jour différentielle, FTS incrémental). |
| **`MetadataEnrichmentWorker`** | Périodique **toutes les 2 h + à chaque lancement** (30 s après) | 600 `get_vod_info` + 20 lots Wikidata + **800 requêtes TMDB par passage** | Élevé en réseau et CPU, en parallèle de la navigation (seulement suspendu quand une vidéo joue) | **Risque de bannissement par le fournisseur IPTV** (beaucoup limitent l'API) ; concurrence CPU. → `setRequiresDeviceIdle(true)` (ou au moins « aucune interaction depuis 5 min »), plafond de 100 par passage, 1 passage par jour, et plus à chaque lancement. |
| Index des versions du Direct (`LiveVersionIndex`) | Chaque changement de catalogue (après 1,5 s de calme) | Fréquent pendant l'hydratation | Moyen (analyse de toutes les chaînes) | Recharge **toute la section Direct depuis SQLite** tant que le catalogue n'est pas complet (VM `:260`). Partager la liste Direct déjà chargée. |
| Rapprochement matchs ↔ chaînes (`ChannelMatcher.buildIndex`) | Chaque scrape (2 h) et chaque changement de catalogue ou d'EPG | — | Élevé (découpe de tous les noms de chaînes) | Index mis en cache par identité de liste (bien) ; recharge aussi la section Direct depuis la base (VM `:2746`). |
| Rapprochements guides FR / beIN / UK | Chaque retour à l'accueil (si la clé change) | — | Élevé : **`ChannelIndex` reconstruit à chaque appel** (`tvprogramme/TvProgrammeChannelMatcher.kt:32,46`) avec normalisation Unicode + 3 regex par chaîne ; beIN **compile 5 regex par chaîne et par appel** (`beinsports/BeinSportsChannelMatcher.kt:96-100`) et fait un parcours linéaire des candidats par programme | **Un seul index normalisé des chaînes Direct**, construit une fois par catalogue et partagé par les 4 rapprochements ; regex en constantes. |
| Recommandations de l'accueil | Retour à l'accueil (5 min minimum entre deux) | — | Moyen à élevé (`features()` complet) | §4.3. |
| JustWatch (6 sections) | Accueil | TTL 6 h | Moyen : jusqu'à 2 recherches SQL par titre × 40 titres × 6 sections | Acceptable ; possible via l'agrégateur. |
| Météo (`ipwho.is` + open-meteo) | Accueil | 30 min | Faible | Géolocalisation par IP envoyée à un tiers : à signaler dans la politique de confidentialité. |
| Scores football (BBC + thesportsdb) | Accueil (dans la composition) | Variable | Faible à moyen | Voir §5.3. |
| Relance « Mode cache » | Racine | 5 min | Faible | OK. |
| Vérification de mise à jour (GitHub API) | Manuel | — | Faible | OK. |

**Travail de fond qui charge plusieurs fois la section Direct entière** : le catalogue
(`ensureSectionLoaded`), l'index de versions (hors hydratation), le rapprochement liveonsat, le
worker EPG. Le tout en plus de `LiveZapIndex`, `LiveVersionIndex`, l'index liveonsat, les alias
EPG et 3 `ChannelIndex` de guides. → **Un seul « magasin » Direct partagé**, avec ses index
normalisés, construit une fois par version du catalogue.

---

## 10. Réseau — inventaire de toutes les URL

| Domaine | Usage | Fichier | Fréquence | Remarque |
|---|---|---|---|---|
| Serveur Xtream `player_api.php` (auth, catégories, `get_live_streams`, `get_vod_streams`, `get_series`, `get_vod_info`, `get_series_info`, `get_short_epg`) | Catalogue, fiches, EPG court, enrichissement | `data/XtreamClient.kt`, `domain/XtreamUrlBuilder.kt` | Ouverture, 24 h, fiches, **enrichissement massif** | HTTP accepté (`usesCleartextTraffic="true"`) : identifiants en clair sur HTTP. |
| Serveur Xtream `xmltv.php` / XMLTV personnalisé | Guide | `data/XmlTvRepository.kt` | 6 h | Pas d'`If-Modified-Since`/`ETag` : retéléchargement complet même inchangé. |
| Serveur Xtream `get.php` (M3U) | Secours VOD/Séries, M3U distante | `XtreamClient`, `XtreamRepository` | Rare / 6 h | Idem, pas de requête conditionnelle. |
| Flux `/live/`, `/movie/`, `/series/` | Lecture | `player/` | — | OK. |
| URL de logos et d'affiches (fournisseur, TMDB…) | Images | `ui/RemoteComponents.kt` | Défilement | Pas de cache d'échec (§7). |
| `liveonsat.com/2day.php` | Matchs du jour (scraping) | `data/LiveOnSatClient.kt` | 2 h | Scraping HTML, fragile, CGU. |
| `tv-programme.com` (+ repli `programme-tv.net`, `programme-television.org`) | Programme FR en direct et ce soir (scraping) | `data/TvProgrammeClient.kt`, `tvprogramme/FallbackGuideParsers.kt` | 30 min / 2 h | Le code note déjà des 403 en cas d'appels trop fréquents. |
| `beinsports.com` (API `tv-channel`, événements) | Guide beIN | `data/BeinSportsClient.kt` | 30 min | API non publique. |
| `tvguideuk.com` (page + ~16 fragments) | Guide UK | `data/UkGuideClient.kt` | 30 min | ~16 requêtes par rafraîchissement. |
| `web-cdn.api.bbci.co.uk` | Scores football | `ui/FootballScoresRow.kt:53` | Variable | Appel fait depuis l'UI. |
| `thesportsdb.com` | Écussons | `ui/FootballScoresRow.kt:159` | Plafonné | Clé publique « 3 ». |
| `a.espncdn.com` | Logos de compétitions | `ui/FootballScoresRow.kt:61` | — | — |
| `apis.justwatch.com/graphql` | Tendances | `data/JustWatchClient.kt` | 6 h | API non publique. |
| `api.themoviedb.org/3` | Métadonnées anglaises, recommandations | `data/TmdbClient.kt` | Fiches + 800 par passage | Jeton en `BuildConfig` : extractible de l'APK. |
| `query.wikidata.org/sparql` | Sagas | `data/WikidataClient.kt` | 20 lots par passage | — |
| `ipwho.is` | Géolocalisation IP | `data/HomeWeatherClient.kt:28` | 30 min | Vie privée. |
| `api.open-meteo.com`, `geocoding-api.open-meteo.com` | Météo, recherche de ville | `data/HomeWeatherClient.kt` | 30 min | OK. |
| `api.github.com/repos/…` | Mises à jour | `data/UpdateChecker.kt:109` | Manuel | Limite de 60 requêtes/h sans jeton. |

**Recommandation structurante : un agrégateur.** Un petit service (Cloudflare Worker ou fonction
serverless, avec cache de périphérie) interroge liveonsat, tv-programme, beIN, tvguideuk, BBC,
JustWatch et Wikidata, puis renvoie **un seul JSON compact et déjà normalisé** (identités de
chaînes normalisées, horaires en epoch).

- Le boîtier passe d'environ 25 requêtes et 5 parsings Jsoup à **1 requête et un JSON de quelques
  Ko**.
- Un changement de site se corrige côté serveur, sans nouvel APK.
- Le jeton TMDB ne se trouve plus dans l'APK.
- Seul le rapprochement avec les chaînes du fournisseur (données privées) reste sur le boîtier.

**Client HTTP unique** : un `OkHttpClient` applicatif (pool partagé, cache disque, gzip, HTTP/2),
avec requêtes conditionnelles (`ETag`, `If-Modified-Since`) pour XMLTV et M3U.

---

## 11. Mémoire

| Consommateur | Estimation sur gros catalogue | Correctif |
|---|---|---|
| Section Direct entière dans `Catalog` (+ `key` String par entrée, calculée à la construction) | 50 000 × ~0,5 Ko ≈ 25 Mo | Unique et partagée (§9). |
| Copies de `Catalog` en transit à chaque fusion (brut + personnalisé + ancien) | 2 à 3 × ce qui précède, en pic | §3.2. |
| Jusqu'à 5 000 entrées VOD avec `plot` | 5 à 10 Mo | Projection sans `plot`. |
| EPG : jour courant + 3 jours LRU, toutes chaînes, avec descriptions | 10 à 60 Mo | Fenêtre (§4.2). |
| Index : versions, liveonsat, 3 guides, zap, alias EPG, recherche en mémoire (`searchIndex` paresseux) | 10 à 30 Mo | Index partagé. |
| Tampon ExoPlayer : 35 % de `largeMemoryClass` (≤ 200 Mo) sur le tas Java | ≤ 200 Mo | Garder, mais le tas doit rester libre ailleurs. |
| Cache d'images : 1/8 du tas | ≤ 64 Mo | `HARDWARE` → hors tas Java. |

---

## 12. Autres manquements et risques

- **`usesCleartextTraffic="true"` global** : autoriser HTTP seulement pour les serveurs IPTV
  (`network_security_config` par domaine est impossible pour des domaines inconnus). Garder, mais
  avertir clairement l'utilisateur, ce que fait déjà le README.
- **Jeton TMDB dans l'APK** (`BuildConfig.TMDB_TOKEN`) : il est extractible. Proxy via l'agrégateur.
- **Scraping** de sites tiers : risque juridique (CGU) et de blocage (403 déjà constatés).
- **Aucun test d'UI instrumenté** (`app/src/androidTest` absent) : les régressions de focus et de
  fluidité ne sont pas détectées en CI.
- **Code mort** : `CatalogCache.saveResolved`/`loadResolved` (appelés, mais ne font que supprimer
  un fichier), `XtreamRepository.fullEpg`, `CatalogCache.loadFull`/`loadAdjacent` (jamais appelés),
  `Catalog.search`/`searchIndex` (utilisés seulement par les tests : la recherche passe par SQL).
- **`versionCode` = nombre de commits** : fragile en cas de réécriture d'historique ou de
  squash-merge (le numéro peut baisser). Utiliser `GITHUB_RUN_NUMBER` + décalage.
- **CI** : `build-tools;35.0.0` avec `compileSdk 36` ; les tests unitaires tournent, mais pas les
  benchmarks.

---

## 13. Outillage de mesure — à mettre en place d'abord

1. **StrictMode en debug** (`detectDiskReads/Writes`, `detectNetwork`, `penaltyLog`) : il listera
   immédiatement chaque accès disque sur le thread principal (Keystore, SharedPreferences, JSON).
2. **JankStats** (`androidx.metrics:metrics-performance`) avec l'écran courant comme état : un
   rapport de saccades par écran en usage réel, envoyé à Crashlytics sous forme de clés ou de
   journaux.
3. **Traces système** : `androidx.tracing:tracing-ktx` (`trace("mergeVodPage") { … }`) autour des
   fusions, tris, parsings et rapprochements ; capture Perfetto sur un vrai boîtier (Mi Box S,
   Chromecast HD, Fire TV Stick) avec un **gros compte de test** (≥ 50 000 chaînes, ≥ 150 000 VOD).
4. **Macrobenchmark** : ajouter les parcours « Accueil → Films → défiler 5 pages → changer de
   tri », « Guide TV → jour suivant », « Recherche », « Fiche → similaires », avec
   `FrameTimingMetric` et `TraceSectionMetric`.
5. **Baseline profile** : générer avec ces parcours ; `startup-prof.txt` limité au démarrage
   (`includeInStartupProfile = true` seulement pour le parcours de démarrage).
6. **Compose compiler reports / metrics** (`-P plugin:…:reportsDestination`) pour repérer les
   paramètres instables et les fonctions non sautables.
7. **Layout Inspector / « Afficher le dépassement GPU »** pour l'overdraw du thème verre.

---

## 14. Plan d'action priorisé

### P0 — gains rapides, risque faible (1 à 3 jours)

1. `applyUserLibraryToCatalog` : `copy` + `inheritSections`, conserver `fullSections` ; ne rien
   refaire si seul l'ordre des catégories change (`data/UserLibraryStore.kt:288-333`).
2. `XmlTvRepository.parseDate` : parseur manuel, regex en constante (`data/XmlTvRepository.kt:286`).
3. Supprimer l'écriture de SharedPreferences au focus de la grille (`ui/BrowserScreen.kt:447`).
4. `publishWatchNext` seulement à la pause, à la sortie ou en fin de lecture (VM `:1568`).
5. Position VOD lue seulement par la timeline, via `() -> Long` (`ui/PlayerScreen.kt:1210`).
6. `item(key, contentType)` pour toutes les rangées de l'accueil ; horloge lue au plus bas niveau.
7. Cache d'échec des images, `HARDWARE` sur API 26+.
8. Regex beIN en constantes ; `ChannelIndex` des guides mis en cache par identité de liste Direct.
9. `GlassBackdrop` via `drawWithCache` (ou bitmap statique).
10. Retour léger après import M3U (`cache.load` au lieu du catalogue complet).
11. Enrichissement : une fois par jour, au repos, 100 fiches au plus ; plus de passage à chaque
    lancement.
12. StrictMode en debug.

### P1 — fondations (1 à 2 semaines)

13. Découper `StreamiaUiState` et le ViewModel par domaine (§3.3).
14. Bibliothèque, historique et stats de versions en SQLite (§4.4).
15. Schéma catalogue : `sort_key` et `rating_rank` indexés, pagination par curseur, comptes pré-calculés,
    projection sans `plot`, identifiants entiers (migration `DATABASE_VERSION = 3`).
16. FTS incrémental par profil ; remplacement du catalogue différentiel ou par tables temporaires.
17. Grille VOD alimentée par une source paginée (Paging 3 ou fenêtre maison) hors du `Catalog`.
18. EPG par fenêtre + table de correspondance chaîne → guide calculée à la synchro.
19. Magasin Direct partagé (une liste, des index normalisés) pour zap, versions, matchs, guides et EPG.
20. Démarrage : plus aucun accès Keystore, SharedPreferences ou JSON sur le thread principal ;
    `reportFullyDrawn`.

### P2 — structurant (2 à 4 semaines)

21. Agrégateur serveur pour tous les guides, matchs, scores et tendances (§10).
22. Client OkHttp unique ; requêtes conditionnelles XMLTV et M3U.
23. Coil 3 (ou chargeur maison complété : taille exacte, préchargement).
24. Thème verre : réduire l'overdraw (couleurs pré-composées), halo de focus pré-rendu.
25. Macrobenchmarks multi-parcours + baseline et startup profiles régénérés en CI sur émulateur TV.
26. `SaveableStateHolder` par écran pour supprimer une partie de la restauration manuelle.

---

## Annexe — ce qui est déjà bien fait (à conserver)

- Écriture du catalogue **en flux** (`JsonReader` → lots de 1 000 → transaction) : pas de
  matérialisation complète.
- Threads de **priorité basse** séparés pour les longs travaux et les travaux courts.
- Transactions atomiques : un échec d'actualisation garde l'ancien catalogue.
- Zap par anti-rebond, lecteur Direct partagé aperçu / plein écran, surface vidéo jamais déplacée
  (`ui/StreamiaApp.kt:93-110`).
- Échelle de focus en `graphicsLayer` sur une Box interne : pas de `bringIntoView` parasite.
- `LazyColumn`/`LazyVerticalGrid` avec clés stables, éviction LRU des pages VOD
  (`MAX_MATERIALIZED_VOD_ENTRIES = 5 000`).
- Caches disque des guides affichés immédiatement, puis actualisés.
- Tri alphabétique avec clé calculée une fois par titre (`sortedAlphabetically`) et mémo des tris.
- Crashlytics nettoyé des URL et identifiants.
