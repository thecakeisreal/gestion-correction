package ca.gestioncorrection;

import ca.gestioncorrection.persistence.Database;
import ca.gestioncorrection.persistence.EncryptedDatabase;
import ca.gestioncorrection.service.DocxExporter;
import ca.gestioncorrection.service.CopyFileService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Vérifie les opérations de persistance, de fichiers et d'export sur des données temporaires.
 *
 * @author AI
 */
class WorkflowTest {
    @TempDir Path temp;
    /** Clé fictive utilisée uniquement pour les bases temporaires des tests. */
    private static final String TEST_KEY = "0123456789abcdef".repeat(4);

    /** Crée et rouvre une nouvelle base chiffrée avec une configuration UTF-8 Windows. */
    @Test void createsEncryptedDatabaseWithUtf8Bom() throws Exception {
        Path env = temp.resolve(".env");
        Files.writeString(env, "\uFEFFDB_KEY=" + TEST_KEY + "\n");
        try (Database database = EncryptedDatabase.open(temp, env)) {
            database.insert("INSERT INTO session(nom) VALUES(?)", "Nouvelle session");
        }
        try (Database database = EncryptedDatabase.open(temp, env)) {
            assertEquals("Nouvelle session", database.query("SELECT nom FROM session").get(0).get("nom"));
        }
        assertThrows(SQLException.class, () -> new Database(temp.resolve("correction-encrypted.db"), "f".repeat(64)));
        Files.delete(temp.resolve("correction-encrypted.db"));
        assertThrows(java.io.IOException.class, () -> EncryptedDatabase.open(temp, env));
    }

    /** Vérifie la migration d'un instantané, le chiffrement et la conservation de l'original. */
    @Test void migratesPlainDatabaseAndRequiresTheCorrectKey() throws Exception {
        Path original = temp.resolve("correction.db");
        Path env = temp.resolve(".env");
        Files.writeString(env, "DB_KEY=" + TEST_KEY + "\n");
        try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + original);
             Statement statement = source.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE session (id INTEGER PRIMARY KEY, nom TEXT NOT NULL)");
            statement.execute("INSERT INTO session(nom) VALUES ('Données originales')");
            try (Database migrated = EncryptedDatabase.open(temp, env)) {
                assertEquals("Données originales", migrated.query("SELECT nom FROM session").get(0).get("nom"));
                migrated.execute("UPDATE session SET nom='Données modifiées'");
            }
            try (var rows = statement.executeQuery("SELECT nom FROM session")) {
                assertTrue(rows.next());
                assertEquals("Données originales", rows.getString(1));
            }
        }
        Path encrypted = temp.resolve("correction-encrypted.db");
        try (Database reopened = EncryptedDatabase.open(temp, env)) {
            assertEquals("Données modifiées", reopened.query("SELECT nom FROM session").get(0).get("nom"));
        }
        assertThrows(SQLException.class, () -> new Database(encrypted, "f".repeat(64)));
        assertThrows(SQLException.class, () -> {
            try (Connection plain = DriverManager.getConnection("jdbc:sqlite:" + encrypted);
                 Statement statement = plain.createStatement()) {
                statement.executeQuery("SELECT * FROM session").close();
            }
        });
    }

    /** Refuse les clés invalides et ne restaure pas silencieusement une ancienne sauvegarde. */
    @Test void rejectsMissingKeyAndInterruptedMigration() throws Exception {
        Path env = temp.resolve(".env");
        Files.writeString(env, "DB_KEY=\n");
        assertThrows(IllegalArgumentException.class, () -> EncryptedDatabase.open(temp, env));
        assertFalse(Files.exists(temp.resolve("correction-encrypted.db")));
        Files.writeString(env, "DB_KEY=" + TEST_KEY + "\n");
        Files.createFile(temp.resolve("correction-encrypted.migration"));
        assertThrows(java.io.IOException.class, () -> EncryptedDatabase.open(temp, env));
        assertFalse(Files.exists(temp.resolve("correction-encrypted.db")));
    }

    /** Crée la hiérarchie minimale nécessaire aux tests de correction. */
    private long evaluation(Database db, Path copies, Path exports) throws Exception {
        long session = db.insert("INSERT INTO session(nom) VALUES(?)", "Automne");
        long course = db.insert("INSERT INTO cours(session_id,nom) VALUES(?,?)", session, "Java");
        return db.insert("INSERT INTO evaluation(cours_id,nom,copies,exports) VALUES(?,?,?,?)", course, "Examen", copies.toString(), exports.toString());
    }

    /** Vérifie les bornes inclusives et le refus des plages qui se chevauchent. */
    @Test void levelsCannotOverlapEvenAtBoundaries() throws Exception {
        try (Database db = new Database(temp.resolve("test.db"), TEST_KEY)) {
            long e = evaluation(db, temp, temp);
            db.saveLevel(e, null, "A", 0, 10);
            assertThrows(IllegalArgumentException.class, () -> db.saveLevel(e, null, "B", 10, 20));
            assertThrows(IllegalArgumentException.class, () -> db.saveLevel(e, null, "B", 5, 6));
            assertThrows(IllegalArgumentException.class, () -> db.saveLevel(e, null, "B", 20, 10));
            db.saveLevel(e, null, "B", 10.1, 20);
            long a = ((Number) db.query("SELECT id FROM niveau WHERE nom='A'").get(0).get("id")).longValue();
            db.saveLevel(e, a, "A", 0, 9);
            assertThrows(IllegalArgumentException.class, () -> db.saveLevel(e, a, "A", 0, 11));
        }
    }

    /** Vérifie l'import de 36 copies et la réversibilité sans écrasement des dossiers. */
    @Test void scans36CopiesAndReversesAnonymisationWithoutOverwrite() throws Exception {
        Path copies = Files.createDirectory(temp.resolve("copies"));
        for (int i = 0; i < 35; i++) Files.createDirectory(copies.resolve("Nom" + i + ", Prénom"));
        Files.createDirectory(copies.resolve("inconnu"));
        try (Database db = new Database(temp.resolve("test.db"), TEST_KEY)) {
            long e = evaluation(db, copies, temp);
            CopyFileService service = new CopyFileService(db);
            service.scan(e, copies); service.scan(e, copies);
            assertEquals(36, db.query("SELECT * FROM copie WHERE evaluation_id=?", e).size());
            Map<String,Object> copy = db.query("SELECT * FROM copie WHERE original=?", "Nom0, Prénom").get(0);
            assertEquals("Nom0", copy.get("nom")); assertEquals("Prénom", copy.get("prenom"));
            assertEquals("", db.query("SELECT nom FROM copie WHERE original='inconnu'").get(0).get("nom"));
            service.rename(copies, copy, true);
            Map<String,Object> anonymous = db.query("SELECT * FROM copie WHERE id=?", copy.get("id")).get(0);
            assertTrue(Files.isDirectory(copies.resolve(anonymous.get("dossier").toString())));
            Files.createDirectory(copies.resolve("Nom0, Prénom"));
            assertThrows(Exception.class, () -> service.scan(e, copies));
            assertThrows(Exception.class, () -> service.rename(copies, anonymous, false));
            assertEquals(1, ((Number) db.query("SELECT anonyme FROM copie WHERE id=?", copy.get("id")).get(0).get("anonyme")).intValue());
            Files.delete(copies.resolve("Nom0, Prénom"));
            service.rename(copies, anonymous, false);
            assertTrue(Files.isDirectory(copies.resolve("Nom0, Prénom")));
            assertEquals(0, ((Number) db.query("SELECT anonyme FROM copie WHERE id=?", copy.get("id")).get(0).get("anonyme")).intValue());
        }
    }

    /** Vérifie que l'aplatissement respecte l'aperçu et conserve le contenu des fichiers. */
    @Test void flattenRequiresUnchangedPreviewAndPreservesContents() throws Exception {
        Path copy = Files.createDirectory(temp.resolve("copy"));
        Path deep = Files.createDirectories(copy.resolve("one").resolve("two").resolve("three"));
        Files.writeString(deep.resolve("a.txt"), "a");
        Files.writeString(deep.resolve("b.txt"), "b");
        List<Path> plan = CopyFileService.flattenPlan(copy);
        assertEquals(3, plan.size());
        Files.writeString(copy.resolve("extra.txt"), "extra");
        assertThrows(Exception.class, () -> CopyFileService.flatten(copy, plan));
        Files.delete(copy.resolve("extra.txt"));
        CopyFileService.flatten(copy, plan);
        assertEquals("a", Files.readString(copy.resolve("a.txt")));
        assertEquals("b", Files.readString(copy.resolve("b.txt")));
        assertFalse(Files.exists(copy.resolve("one")));
    }

    /** Vérifie le contenu DOCX, les points manuels et une perte de commentaire personnalisée. */
    @Test void docxContainsGridSelectionManualPointsAndCommentOverride() throws Exception {
        Path copies = Files.createDirectory(temp.resolve("copies"));
        Path exports = Files.createDirectory(temp.resolve("exports"));
        try (Database db = new Database(temp.resolve("test.db"), TEST_KEY)) {
            long e = evaluation(db, copies, exports);
            long copy = db.insert("INSERT INTO copie(evaluation_id,dossier,original,nom,prenom) VALUES(?,?,?,?,?)", e, "one", "one", "Nom", "Prénom");
            long level = db.insert("INSERT INTO niveau(evaluation_id,nom,minimum,maximum) VALUES(?,?,?,?)", e, "Bien", 5, 10);
            long c = db.insert("INSERT INTO critere(evaluation_id,region,nom,indicateur,ponderation) VALUES(?,?,?,?,?)", e, "Analyse", "Qualité", "Exactitude", 25);
            long b = db.insert("INSERT INTO bloc(critere_id,niveau_id,texte) VALUES(?,?,?)", c, level, "Travail satisfaisant");
            long comment = db.insert("INSERT INTO commentaire(bloc_id,texte,perte) VALUES(?,?,?)", b, "Justification absente", 2);
            db.insert("INSERT INTO choix(copie_id,critere_id,bloc_id) VALUES(?,?,?)", copy, c, b);
            db.insert("INSERT INTO commentaire_choisi(copie_id,commentaire_id,perte_override) VALUES(?,?,?)", copy, comment, 3);
            db.insert("INSERT INTO note(copie_id,cible,points) VALUES(?,?,?)", copy, "R:Analyse", 18);
            db.insert("INSERT INTO note(copie_id,cible,points) VALUES(?,?,?)", copy, "C:" + c, 7);
            Map<String,Object> evaluation = db.query("SELECT * FROM evaluation WHERE id=?", e).get(0);
            Map<String,Object> student = db.query("SELECT * FROM copie WHERE id=?", copy).get(0);
            Path file = new DocxExporter(db).export(evaluation, student);
            assertEquals("Nom, Prénom.docx", file.getFileName().toString());
            try (XWPFDocument document = new XWPFDocument(Files.newInputStream(file))) {
                String content = String.join("\n", document.getParagraphs().stream().map(p -> p.getText()).toList());
                assertTrue(content.contains("Analyse — Points : 18"));
                assertTrue(content.contains("Qualité") && content.contains("Exactitude") && content.contains("25"));
                assertTrue(content.contains("Pondération : 25.0 | Points : 7.0"));
                assertTrue(content.contains("Bien [5") && content.contains("[X] Travail satisfaisant"));
                assertTrue(content.contains("Justification absente") && content.contains("Perte : 3.0 (personnalisée)"));
            }
            assertThrows(IllegalArgumentException.class, () -> new DocxExporter(db).export(evaluation, student));
            assertThrows(IllegalArgumentException.class, () -> DocxExporter.filename("../X", "Y"));
        }
    }
}
