# Signature de release stable

L'APK distribué (`optimized`, publié en GitHub Release) était jusqu'ici signé avec la clé
debug d'Android — la même sur toutes les machines, jamais destinée à un usage public. Ce
document décrit comment générer une vraie clé de release et la brancher sur la CI.

## 1. Générer la clé (une seule fois, en local — jamais dans la CI)

```bash
keytool -genkeypair -v \
  -keystore streamia-release.keystore \
  -alias streamia \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` demande un mot de passe de keystore, un mot de passe de clé (peut être identique)
et quelques informations (nom, organisation…) qui ne sont pas vérifiées. Conservez ce fichier
et ces mots de passe en lieu sûr, en dehors du dépôt — **`*.keystore`/`*.jks` sont dans
`.gitignore`, ne les committez jamais.** Si ce fichier est perdu, il est impossible de publier
une mise à jour signée avec la même identité : mieux vaut le sauvegarder dans un gestionnaire
de mots de passe ou un stockage chiffré séparé.

## 2. Ajouter les secrets GitHub Actions

Dans le dépôt : **Settings → Secrets and variables → Actions → New repository secret**.
Ajoutez ces quatre secrets :

| Secret | Valeur |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 streamia-release.keystore` (sortie complète) |
| `RELEASE_STORE_PASSWORD` | Le mot de passe du keystore |
| `RELEASE_KEY_ALIAS` | `streamia` (ou l'alias choisi à l'étape 1) |
| `RELEASE_KEY_PASSWORD` | Le mot de passe de la clé |

Tant que ces secrets ne sont pas configurés, la CI continue de fonctionner normalement et
retombe sur la clé debug (comportement actuel, inchangé).

## 3. Effet une fois les secrets ajoutés

Le prochain build publié en `latest`/tag sera signé avec cette clé. **Point d'attention
important : Android refuse d'installer une mise à jour dont la signature a changé.** Toute
installation existante de l'app (signée avec la clé debug) devra être **désinstallée avant**
d'installer le premier APK signé avec la nouvelle clé — sans quoi le système renvoie une
erreur `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Les installations suivantes se mettront à jour
normalement tant que la même clé de release est utilisée.
