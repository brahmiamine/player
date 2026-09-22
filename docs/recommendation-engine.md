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

L’accueil sépare les notions de nouveauté et de personnalisation :

- un pool récent alimente « À découvrir » et « Nouveautés » ;
- les derniers contenus regardés et favoris ajoutent des candidats proches des goûts du profil ;
- l’historique, les favoris, les contenus vus et les retours explicites « plus comme ça » / « moins comme ça » participent au classement final.

Quand les signaux du profil ne sont pas encore suffisants, l’accueil affiche « À découvrir ». Avec suffisamment de confiance, cette rangée devient « Recommandé pour vous ».

## Performance et confidentialité

- Aucun parcours complet du catalogue n’est nécessaire à l’ouverture d’une fiche.
- Les calculs de similarité sont déterministes et exécutés hors du thread principal.
- Les enrichissements réseau sont strictement bornés et mis en cache.
- Les règles sont couvertes par les tests unitaires du moteur et le lint Android.
- Les identifiants présents dans les URLs de playlists ne font pas partie du modèle de recommandation et ne doivent jamais être journalisés, documentés ou exportés.
