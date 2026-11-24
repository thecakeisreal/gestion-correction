# Gestion de correction

Application JavaFX en français pour organiser les corrections par session, cours et évaluation. Les données restent sur l'ordinateur dans `~/.gestion-correction/correction-encrypted.db` (SQLite chiffrée) : elles ne sont pas supprimées automatiquement. Sauvegardez cette base et sa clé, notamment avant une anonymisation ; la base contient la correspondance entre les noms originaux et anonymes.

## Démarrage

Installer un JDK 17 à 25 et définir `JAVA_HOME` sur ce JDK (pas sur un JRE). Avant le premier lancement, depuis la racine du projet, créer une clé aléatoire **une seule fois** :

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
$lines = @(Get-Content -LiteralPath .env)
if (@($lines | Where-Object { $_ -match '^\s*DB_KEY\s*[:=]\s*\S' }).Count -gt 0) { throw "DB_KEY existe déjà ; ne la remplacez pas." }
if (@($lines | Where-Object { $_ -match '^\s*DB_KEY\s*[:=]' }).Count -ne 1) { throw "Le fichier .env doit contenir exactement une entrée DB_KEY vide." }
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $bytes = New-Object byte[] 32; $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$key = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$lines | ForEach-Object { if ($_ -match '^\s*DB_KEY\s*[:=]') { "DB_KEY=$key" } else { $_ } } | Set-Content -LiteralPath .env -Encoding UTF8
```

Une autre clé ne peut pas ouvrir la base existante. Le fichier `.env` est lu depuis le répertoire courant et ne contient pas de mot de passe utilisateur SQLite : `DB_KEY` est la clé de chiffrement, constituée de 64 caractères hexadécimaux. C'est actuellement la seule valeur utilisée par l'application ; le format du fichier est celui des propriétés Java. Un fichier manquant ou une clé invalide bloque le démarrage. Protégez `.env` avec les permissions du système et gardez une sauvegarde sécurisée séparée ; si la clé est perdue, la base chiffrée ne peut pas être récupérée. Le fichier `.env` n'est pas chiffré : toute personne pouvant le lire et accéder à la base peut la déchiffrer. Les copies, les exports DOCX et les sauvegardes non chiffrées ne sont pas protégés par cette clé.

Fermez toute ancienne instance avant le premier lancement. Si `~/.gestion-correction/correction.db` existe, l'application crée un instantané chiffré dans `correction-encrypted.db` et conserve la base d'origine **en clair** comme sauvegarde. Protégez cette sauvegarde et ses éventuels fichiers SQLite associés ; elle ne reçoit plus les nouvelles modifications. Un fichier `correction-encrypted.migration` empêche la création ou la restauration silencieuse d'une base si la base chiffrée disparaît après son initialisation. Si l'initialisation ou une migration est interrompue, vérifiez les bases et les sauvegardes avant de supprimer ce marqueur pour réessayer ; un fichier temporaire de migration peut contenir des données en clair après une interruption brutale.

Exécuter ensuite :

```powershell
.\gradlew.bat build
.\gradlew.bat run
```

Le wrapper télécharge Gradle et les dépendances lors de la première utilisation ; une connexion Internet peut être nécessaire. Sous Windows, utiliser un JDK 64 bits. Sur les autres systèmes, utiliser `./gradlew`.

Le téléchargement du wrapper est vérifié avec la somme SHA-256 de la distribution Gradle 9.8.0 publiée par [Gradle](https://gradle.org/release-checksums/). Lors d'une mise à jour de Gradle, modifier également cette somme pour la distribution choisie ; une distribution déjà extraite dans le cache n'est pas téléchargée à nouveau.

Gradle fournit l'option JVM `--enable-native-access=javafx.graphics,ALL-UNNAMED` à `run` pour autoriser l'accès natif de JavaFX et SQLite. Les tests utilisent `--enable-native-access=ALL-UNNAMED`, car leurs dépendances sont chargées sur le classpath. Cette option ne masque pas les autres avertissements de la JVM.

## Structure et tests

- `src/main/resources/ca/gestioncorrection/ui` contient les vues FXML : fenêtre principale, onglets et dialogues.
- `src/main/java/ca/gestioncorrection/ui` contient les contrôleurs et les fonctions communes de l'interface.
- `src/main/java/ca/gestioncorrection/persistence` gère la base SQLite ; `service` contient les opérations sur les copies et l'export DOCX.

`.\gradlew.bat test` exécute les tests sans interface graphique. Pour vérifier aussi le chargement FXML et les interactions entre onglets et dialogues, exécuter `.\gradlew.bat uiTest` dans une session disposant d'un affichage graphique.

## Utilisation

Les actions et champs indisponibles restent désactivés tant que leurs prérequis ne sont pas remplis. Les formulaires indiquent les valeurs attendues ; pour exporter, configurez le dossier d'export et associez à chaque copie un nom et un prénom valides (et distincts pour un export groupé). Les erreurs liées aux fichiers, comme un dossier déplacé ou un DOCX existant, restent signalées au moment de l'opération.

1. Créer une session, un cours et une évaluation, puis configurer ses dossiers de copies et d'exports avec **Dossiers**. Le dossier de copies ne peut plus être changé après importation.
2. Dans **Grille**, ajouter les critères (région facultative, indicateur, pondération), les niveaux communs à l'évaluation et les blocs descriptifs associés à chaque couple critère/niveau. Les bornes des plages sont incluses et deux plages ne peuvent pas se chevaucher.
3. Dans **Copies**, importer les dossiers directement contenus dans le dossier de copies. Le nom `Nom, Prénom` est proposé automatiquement ; pour les autres noms, utiliser **Associer nom et prénom**. Vérifier les associations avant l'export. Sélectionner la copie à corriger dans cette liste.
4. Anonymiser ou désanonymiser une copie ou toutes les copies avec confirmation. Seuls les dossiers directs sont renommés ; le contenu et les noms de fichiers ne sont pas anonymisés. L'aplatissement montre d'abord la chaîne de dossiers uniques à supprimer ; il déplace le contenu du dernier dossier à la racine de la copie. Faire une sauvegarde des copies avant toute opération sur les fichiers.
5. Dans **Correction et export**, choisir la copie dans la liste déroulante, sélectionner un critère, puis un bloc et **Sélectionner le bloc**. Créer ou activer des commentaires réutilisables propres à ce bloc. Leur perte est commune à toutes les copies ; **Personnaliser la perte** la remplace explicitement pour une seule copie, et une valeur vide rétablit la valeur commune. Changer de bloc retire les commentaires sélectionnés pour l'ancien bloc.
6. Saisir les points manuellement par région et/ou par critère, selon les besoins. La sélection des blocs et les pertes de commentaires ne calculent ni ne déduisent de points. Exporter une copie ou toutes les copies au format DOCX, dans le dossier d'exports de l'évaluation. Les fichiers sont nommés `Nom, Prénom.docx` et ne sont jamais écrasés. Les points et blocs non renseignés sont signalés dans le document.

Les opérations groupées s'arrêtent à la première erreur et indiquent combien d'éléments ont déjà été traités. En cas d'interruption pendant un renommage ou un déplacement, vérifier les dossiers et la base avant de reprendre. Les modifications de la grille ou des modèles de commentaires s'appliquent aux corrections existantes ; les pertes personnalisées restent indépendantes.
