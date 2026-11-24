package ca.gestioncorrection.persistence;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Charge la clé locale et migre une base SQLite existante sans modifier l'original.
 *
 * @author AI
 */
public final class EncryptedDatabase {
    private EncryptedDatabase() {}

    /**
     * Ouvre la base chiffrée, en créant une copie de l'ancienne base au premier lancement.
     * @param directory dossier des bases locales
     * @param envFile fichier local contenant la clé
     * @return connexion à la base chiffrée
     * @throws Exception si la configuration, la migration ou l'ouverture échoue
     */
    public static Database open(Path directory, Path envFile) throws Exception {
        String key = loadKey(envFile);
        Files.createDirectories(directory);
        Path encrypted = directory.resolve("correction-encrypted.db");
        Path original = directory.resolve("correction.db");
        Path marker = directory.resolve("correction-encrypted.migration");

        if (!Files.exists(encrypted)) {
            if (Files.exists(marker)) {
                throw new IOException("La base chiffrée est absente après une initialisation ou migration. "
                        + "Vérifiez les fichiers et sauvegardes avant de supprimer " + marker + " pour réessayer.");
            }
            Files.createFile(marker);
            if (Files.exists(original)) {
                migrate(original, encrypted, key);
            }
        }
        return new Database(encrypted, key);
    }

    /**
     * Refuse les clés absentes ou dont le format ne représente pas 256 bits.
     * @param key clé hexadécimale fournie localement
     */
    static void validateKey(String key) {
        if (key == null || !key.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("DB_KEY doit contenir exactement 64 caractères hexadécimaux aléatoires dans .env.");
        }
    }

    /** Lit la clé sans la journaliser et accepte la marque UTF-8 produite par Windows PowerShell. */
    private static String loadKey(Path envFile) throws IOException {
        Properties properties = new Properties();
        String content = Files.readString(envFile, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        properties.load(new StringReader(content));
        String key = properties.getProperty("DB_KEY");
        validateKey(key);
        return key;
    }

    /** Copie un instantané cohérent, chiffre la copie et vérifie son intégrité avant publication. */
    private static void migrate(Path original, Path encrypted, String key) throws Exception {
        Path temporary = Files.createTempFile(encrypted.toAbsolutePath().getParent(), "correction-migration-", ".db");
        try {
            String sourceUrl = "jdbc:sqlite:" + original.toAbsolutePath().toUri().toASCIIString() + "?mode=ro";
            try (Connection source = DriverManager.getConnection(sourceUrl);
                 PreparedStatement snapshot = source.prepareStatement("VACUUM INTO ?")) {
                snapshot.setString(1, temporary.toString());
                snapshot.execute();
            }
            try (Connection copy = DriverManager.getConnection("jdbc:sqlite:" + temporary);
                 Statement statement = copy.createStatement()) {
                try (ResultSet version = statement.executeQuery("SELECT sqlite3mc_version()")) {
                    if (!version.next()) throw new SQLException("Le pilote SQLite ne prend pas en charge le chiffrement.");
                }
                statement.execute("PRAGMA cipher='chacha20'");
                statement.execute("PRAGMA rekey='raw:" + key + "'");
            }
            try (Database verified = new Database(temporary, key)) {
                if (!"ok".equals(verified.query("PRAGMA integrity_check").get(0).get("integrity_check"))) {
                    throw new SQLException("La vérification d'intégrité de la base chiffrée a échoué.");
                }
            }
            Files.move(temporary, encrypted);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
