package ca.gestioncorrection.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.sqlite.mc.SQLiteMCChacha20Config;

/**
 * Gère la connexion SQLite, le schéma local et les requêtes paramétrées.
 * Les transactions explicites doivent toujours se terminer par {@link #endTransaction()}.
 *
 * @author AI
 */
public final class Database implements AutoCloseable {
    private final Connection connection;

    /**
     * Ouvre la base, crée son dossier parent et initialise le schéma si nécessaire.
     * @param file chemin du fichier SQLite
     * @param key clé de chiffrement hexadécimale de 256 bits
     * @throws Exception si la création du dossier ou l'ouverture de la base échoue
     */
    public Database(Path file, String key) throws Exception {
        EncryptedDatabase.validateKey(key);
        Files.createDirectories(file.toAbsolutePath().getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(),
                SQLiteMCChacha20Config.getDefault().withKey("raw:" + key).build().toProperties());
        try (Statement s = connection.createStatement()) {
            try (ResultSet version = s.executeQuery("SELECT sqlite3mc_version()")) {
                if (!version.next()) throw new SQLException("Le pilote SQLite ne prend pas en charge le chiffrement.");
            }
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS session (id INTEGER PRIMARY KEY, nom TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS cours (id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL REFERENCES session(id), nom TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS evaluation (id INTEGER PRIMARY KEY, cours_id INTEGER NOT NULL REFERENCES cours(id), nom TEXT NOT NULL, copies TEXT NOT NULL DEFAULT '', exports TEXT NOT NULL DEFAULT '')");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS copie (id INTEGER PRIMARY KEY, evaluation_id INTEGER NOT NULL REFERENCES evaluation(id), dossier TEXT NOT NULL, original TEXT NOT NULL, nom TEXT NOT NULL DEFAULT '', prenom TEXT NOT NULL DEFAULT '', anonyme INTEGER NOT NULL DEFAULT 0, UNIQUE(evaluation_id,dossier), UNIQUE(evaluation_id,original))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS niveau (id INTEGER PRIMARY KEY, evaluation_id INTEGER NOT NULL REFERENCES evaluation(id), nom TEXT NOT NULL, minimum REAL NOT NULL, maximum REAL NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS critere (id INTEGER PRIMARY KEY, evaluation_id INTEGER NOT NULL REFERENCES evaluation(id), region TEXT NOT NULL DEFAULT '', nom TEXT NOT NULL, indicateur TEXT NOT NULL, ponderation REAL NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS bloc (id INTEGER PRIMARY KEY, critere_id INTEGER NOT NULL REFERENCES critere(id), niveau_id INTEGER NOT NULL REFERENCES niveau(id), texte TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS choix (copie_id INTEGER NOT NULL REFERENCES copie(id), critere_id INTEGER NOT NULL REFERENCES critere(id), bloc_id INTEGER NOT NULL REFERENCES bloc(id), PRIMARY KEY(copie_id,critere_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS commentaire (id INTEGER PRIMARY KEY, bloc_id INTEGER NOT NULL REFERENCES bloc(id), texte TEXT NOT NULL, perte REAL NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS commentaire_choisi (copie_id INTEGER NOT NULL REFERENCES copie(id), commentaire_id INTEGER NOT NULL REFERENCES commentaire(id), perte_override REAL, PRIMARY KEY(copie_id,commentaire_id))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS note (copie_id INTEGER NOT NULL REFERENCES copie(id), cible TEXT NOT NULL, points REAL NOT NULL, PRIMARY KEY(copie_id,cible))");
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * Exécute une requête de modification paramétrée.
     * @param sql instruction SQL contenant éventuellement des marqueurs {@code ?}
     * @param args valeurs associées aux marqueurs, dans l'ordre
     * @return nombre de lignes modifiées
     * @throws SQLException si l'instruction échoue
     */
    public int execute(String sql, Object... args) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            bind(p, args);
            return p.executeUpdate();
        }
    }

    /**
     * Insère une ligne et retourne son identifiant généré.
     * @param sql instruction SQL d'insertion paramétrée
     * @param args valeurs associées aux marqueurs, dans l'ordre
     * @return identifiant généré
     * @throws SQLException si l'insertion échoue ou ne retourne pas d'identifiant
     */
    public long insert(String sql, Object... args) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(p, args);
            p.executeUpdate();
            try (ResultSet keys = p.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Identifiant non retourné par la base de données.");
                return keys.getLong(1);
            }
        }
    }

    /**
     * Exécute une requête et indexe chaque résultat par nom de colonne ou alias.
     * @param sql requête SQL paramétrée
     * @param args valeurs associées aux marqueurs, dans l'ordre
     * @return lignes obtenues dans l'ordre renvoyé par SQLite
     * @throws SQLException si la requête échoue
     */
    public List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData meta = r.getMetaData();
                while (r.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i), r.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /** Associe les paramètres aux marqueurs JDBC, dont les indices commencent à un. */
    private static void bind(PreparedStatement p, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
    }

    /**
     * Désactive l'auto-validation pour commencer une transaction.
     * @throws SQLException si la connexion échoue
     */
    public void beginTransaction() throws SQLException { connection.setAutoCommit(false); }
    /**
     * Valide les modifications de la transaction courante.
     * @throws SQLException si la validation échoue
     */
    public void commit() throws SQLException { connection.commit(); }
    /**
     * Annule les modifications de la transaction courante.
     * @throws SQLException si l'annulation échoue
     */
    public void rollback() throws SQLException { connection.rollback(); }
    /**
     * Rétablit l'auto-validation après une validation ou une annulation.
     * @throws SQLException si la connexion échoue
     */
    public void endTransaction() throws SQLException { connection.setAutoCommit(true); }

    /**
     * Crée ou modifie un niveau après validation de ses bornes inclusives et de leur non-chevauchement.
     * @param evaluation identifiant de l'évaluation
     * @param id identifiant du niveau à modifier, ou {@code null} pour une création
     * @param name nom du niveau
     * @param min borne inférieure incluse
     * @param max borne supérieure incluse
     * @throws IllegalArgumentException si les bornes sont invalides ou chevauchent un autre niveau
     * @throws SQLException si la lecture ou l'écriture échoue
     */
    public void saveLevel(long evaluation, Long id, String name, double min, double max) throws SQLException {
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max)
            throw new IllegalArgumentException("La plage doit contenir deux nombres finis, avec un minimum inférieur ou égal au maximum.");
        if (!query("SELECT id FROM niveau WHERE evaluation_id=? AND id<>? AND minimum<=? AND maximum>=?", evaluation, id == null ? -1 : id, max, min).isEmpty())
            throw new IllegalArgumentException("Les plages des niveaux ne doivent pas se chevaucher, même à leurs bornes.");
        if (id == null) insert("INSERT INTO niveau(evaluation_id,nom,minimum,maximum) VALUES(?,?,?,?)", evaluation, name, min, max);
        else execute("UPDATE niveau SET nom=?,minimum=?,maximum=? WHERE id=? AND evaluation_id=?", name, min, max, id, evaluation);
    }

    /**
     * Ferme la connexion à la base.
     * @throws SQLException si la fermeture échoue
     */
    @Override public void close() throws SQLException { connection.close(); }
}
