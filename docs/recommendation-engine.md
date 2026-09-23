# Moteur de recommandation

## Fiches Film et Série

Les recommandations d’une fiche détail utilisent un pool borné et rapide :

1. La majorité des candidats est lue dans la même catégorie que le contenu affiché, autour de sa position dans la playlist.
2. La requête s’appuie sur l’index SQLite `idx_catalog_category`, afin de rester rapide même avec plusieurs centaines de milliers d’entrées.
3. Le pool est complété par quelques contenus récents si la catégorie ne fournit pas assez de candidats.
4. Le moteur classe ensuite les candidats avec les métadonnées disponibles ou déjà enrichies.

Le score privilégie fortement :

- la proximité du synopsis et des thèmes ;
- le genre ;
- puis, comme signaux secondaires, le titre ou la saga, le réalisateur et la distribution.

La catégorie IPTV, l’année et la note ne suffisent jamais à elles seules pour déclarer deux contenus similaires. Une proximité descriptive réelle est obligatoire. Une normalisation légère rapproche aussi plusieurs termes français et anglais fréquents dans les synopsis, sans ajouter de modèle lourd sur l’Android TV.

## Accueil

Le moteur de l’accueil ne traite que les **Films** et **Séries**. Le direct n’y figure pas : les rangées TV (Programme TV FR, beIN Sports, UK) ont leurs propres sources, plus précises, et sont indépendantes du moteur.

L’accueil affiche **au maximum deux rangées** de recommandation (`MAX_HOME_AI_ROWS = 2`).

### Rangée principale

- confiance du profil `< 0.30` → « Sélection pour vous » (classement par note, fraîcheur et présence de métadonnées) ;
- confiance `>= 0.30` → « Recommandé pour vous » (similarité avec les goûts, catégories appréciées, note, fraîcheur, qualité des métadonnées, pénalité pour les contenus proches de ceux rejetés).

### Rangée secondaire

Un seul bloc est retenu, dans cet ordre strict. Un bloc qui n’a pas au moins deux résultats au-dessus du seuil de qualité (`MIN_SECONDARY_QUALITY`, appliqué à chaque élément) est ignoré au profit du suivant : aucun remplissage artificiel.

1. « Parce que vous aimez X » : dernier contenu marqué « Plus comme ça ».
2. « Parce que vous avez regardé X » : dernière lecture forte (progression ≥ 60 %, avec décroissance temporelle ; quelques minutes ne suffisent pas).
3. « Récemment ajoutés » / « Récemment ajoutés pour vous » (profil personnalisé) : **date d’ajout** fournisseur (`addedAtEpochSeconds`) récente.
4. « Sorties récentes » : **année de sortie** (date fournisseur, sinon année en fin de titre) égale à l’année courante ou à la précédente.

« Récemment ajoutés » et « Sorties récentes » ne sont jamais confondus : une playlist M3U sans date d’ajout ne produit jamais « Récemment ajoutés », et sans année fiable aucune des deux rangées n’apparaît.

Les blocs 1 et 2 réutilisent la similarité des fiches (synopsis, thèmes, genre, saga, réalisateur, casting) ; le contenu source n’apparaît jamais dans sa propre rangée.

### Exclusions et doublons

- Contenus masqués, catégories masquées/verrouillées et « Moins comme ça » sont exclus avant tout calcul.
- Les contenus déjà vus ou en cours ne sont pas reproposés.
- Un contenu de la rangée principale n’est jamais répété dans la rangée secondaire.
- Un même contenu publié sous deux identifiants (même type, titre canonique et année) n’apparaît qu’une fois.

## Performance et confidentialité

- Aucun parcours complet du catalogue n’est nécessaire à l’ouverture d’une fiche.
- Les calculs de similarité sont déterministes et exécutés hors du thread principal.
- Les enrichissements réseau sont strictement bornés et mis en cache.
- Les règles sont couvertes par les tests unitaires du moteur et le lint Android.
- Les identifiants présents dans les URLs de playlists ne font pas partie du modèle de recommandation et ne doivent jamais être journalisés, documentés ou exportés.
