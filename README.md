# Gestion de correction

![Version 0.0.1](https://img.shields.io/badge/version-0.0.1-blue)
[![Tests](https://github.com/thecakeisreal/gestion-correction/actions/workflows/tests.yml/badge.svg?branch=main)](https://github.com/thecakeisreal/gestion-correction/actions/workflows/tests.yml)
[![AI Generated](https://www.aihonestybadge.com/badges/ai-generated-small.svg)](https://www.aihonestybadge.com)

Application de correction en français pour organiser les évaluations, anonymiser les copies, utiliser des grilles de correction et exporter les résultats en DOCX.

## Installation

1. Installez un JDK 17 à 25 et définissez `JAVA_HOME`. Une connexion Internet est nécessaire au premier démarrage.
2. À la racine du projet, copiez `.env.example` vers `.env` s'il n'existe pas. Définissez `DB_KEY` avec une clé aléatoire de 64 caractères hexadécimaux. Sous Windows PowerShell, utilisez **une seule fois** :

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

   Sur les autres systèmes, `openssl rand -hex 32` génère une clé à inscrire dans `.env`. Ne remplacez jamais une clé existante : elle est indispensable pour ouvrir la base.
3. Fermez les anciennes instances, puis lancez l'application depuis la racine du projet :

   ```powershell
   .\gradlew.bat run
   ```

   Sous Linux ou macOS, utilisez `bash gradlew run`.

La base est chiffrée, mais `.env` contient la clé en clair : protégez ce fichier et sauvegardez la clé séparément. Les copies et exports ne sont pas chiffrés. Une ancienne base est conservée **en clair** comme sauvegarde lors du premier lancement ; protégez-la également. Si l'initialisation est interrompue, vérifiez les bases et les sauvegardes avant de supprimer le fichier `correction-encrypted.migration` dans `~/.gestion-correction` pour réessayer.

## Signaler un problème

Utilisez les [issues GitHub](https://github.com/thecakeisreal/gestion-correction/issues) pour signaler un bogue ou demander une fonctionnalité. N'y publiez ni clé, ni données d'étudiants, ni autre information sensible.
