package ca.gestioncorrection.service;

import ca.gestioncorrection.persistence.Database;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Importe, anonymise et aplatit les dossiers de copies sans écraser les destinations.
 * La correspondance des noms originaux est conservée dans la base locale.
 *
 * @author AI
 */
public final class CopyFileService {
    private final Database db;
    /**
     * Initialise le service avec la base contenant les copies.
     * @param db base utilisée pour conserver les associations et les renommages
     */
    public CopyFileService(Database db) { this.db = db; }

    /**
     * Résout un chemin et exige un dossier existant qui n'est pas un lien symbolique.
     * @param text chemin saisi par l'utilisateur
     * @return chemin absolu normalisé du dossier
     * @throws IOException si le dossier n'est pas configuré ou accessible
     */
    public static Path directory(String text) throws IOException {
        if (text == null || text.isBlank()) throw new IOException("Le dossier n'est pas configuré.");
        Path p = Path.of(text).toAbsolutePath().normalize();
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Dossier inaccessible ou lien symbolique : " + p);
        return p;
    }

    /**
     * Résout un nom de copie directement sous sa racine sans autoriser de traversée.
     * @param root dossier racine absolu et normalisé
     * @param name nom d'un seul dossier enfant
     * @return chemin normalisé du dossier enfant
     * @throws IOException si le nom ne désigne pas un enfant direct
     */
    public static Path child(Path root, String name) throws IOException {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || !Path.of(name).getFileName().toString().equals(name))
            throw new IOException("Nom de dossier invalide : " + name);
        Path p = root.resolve(name).normalize();
        if (!root.equals(p.getParent())) throw new IOException("Dossier hors du répertoire des copies.");
        return p;
    }

    /**
     * Importe les sous-dossiers directs non enregistrés et propose les noms au format « Nom, Prénom ».
     * Un conflit avec le nom original d'une copie anonymisée interrompt l'importation.
     * @param evaluation identifiant de l'évaluation
     * @param root dossier contenant les copies
     * @throws Exception si la lecture du dossier ou l'écriture en base échoue
     */
    public void scan(long evaluation, Path root) throws Exception {
        try (Stream<Path> stream = Files.list(root)) {
            for (Path p : stream.filter(x -> Files.isDirectory(x, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                String name = p.getFileName().toString();
                List<Map<String, Object>> existing = db.query("SELECT dossier FROM copie WHERE evaluation_id=? AND (dossier=? OR original=?)", evaluation, name, name);
                if (!existing.isEmpty()) {
                    if (!existing.get(0).get("dossier").toString().equals(name))
                        throw new IOException("Un dossier porte le nom original d'une copie anonymisée : " + p + ". Vérifiez les copies avant l'importation.");
                    continue;
                }
                String[] parts = name.split(",", 2);
                String nom = parts.length == 2 ? parts[0].trim() : "";
                String prenom = parts.length == 2 ? parts[1].trim() : "";
                db.insert("INSERT INTO copie(evaluation_id,dossier,original,nom,prenom) VALUES(?,?,?,?,?)", evaluation, name, name, nom, prenom);
            }
        }
    }

    /**
     * Renomme une copie et met à jour son état en base ; en cas d'échec, tente de revenir
     * au nom précédent et signale explicitement tout échec de cette annulation.
     * @param root dossier contenant les copies
     * @param copy ligne SQLite de la copie, incluant son identifiant et ses noms de dossier
     * @param anonymise {@code true} pour anonymiser, {@code false} pour rétablir le nom original
     * @throws Exception si la copie, la destination, le déplacement ou la transaction est invalide
     */
    public void rename(Path root, Map<String, Object> copy, boolean anonymise) throws Exception {
        long id = ((Number) copy.get("id")).longValue();
        boolean anonymous = ((Number) copy.get("anonyme")).intValue() != 0;
        if (anonymous == anonymise) throw new IllegalArgumentException(anonymise ? "Cette copie est déjà anonymisée." : "Cette copie n'est pas anonymisée.");
        String oldName = copy.get("dossier").toString();
        String newName = anonymise ? String.format("Copie-%06d", id) : copy.get("original").toString();
        Path from = child(root, oldName), to = child(root, newName);
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Dossier de copie introuvable ou lien symbolique : " + from);
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Le dossier de destination existe déjà : " + to);
        db.beginTransaction();
        boolean moved = false;
        try {
            db.execute("UPDATE copie SET dossier=?,anonyme=? WHERE id=?", newName, anonymise ? 1 : 0, id);
            Files.move(from, to);
            moved = true;
            db.commit();
        } catch (Exception e) {
            try { db.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            if (moved) {
                try { Files.move(to, from); } catch (IOException rollback) {
                    e.addSuppressed(rollback);
                    throw new IOException("Échec du renommage et de son annulation. Vérifiez manuellement : " + from + " et " + to, e);
                }
            }
            throw e;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Prépare la chaîne de sous-dossiers uniques, sans suivre les liens symboliques.
     * @param copy dossier racine de la copie
     * @return chaîne ordonnée de la racine vers le dossier le plus profond, sans inclure la racine
     * @throws IOException si un dossier ne peut pas être lu
     */
    public static List<Path> flattenPlan(Path copy) throws IOException {
        if (!Files.isDirectory(copy, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Dossier de copie inaccessible : " + copy);
        List<Path> chain = new ArrayList<>();
        Path current = copy;
        while (true) {
            List<Path> entries;
            try (Stream<Path> s = Files.list(current)) { entries = s.toList(); }
            if (entries.size() != 1 || !Files.isDirectory(entries.get(0), LinkOption.NOFOLLOW_LINKS)) break;
            current = entries.get(0);
            chain.add(current);
        }
        return chain;
    }

    /**
     * Vérifie à nouveau le plan confirmé, déplace les éléments du dernier dossier à la racine
     * et supprime les dossiers intermédiaires vides ; aucun fichier existant n'est écrasé.
     * @param copy dossier racine de la copie
     * @param planned chaîne précédemment obtenue avec {@link #flattenPlan(Path)}
     * @throws IOException si le contenu a changé, si un nom entre en conflit ou si une opération échoue
     */
    public static void flatten(Path copy, List<Path> planned) throws IOException {
        if (planned.isEmpty()) return;
        if (!flattenPlan(copy).equals(planned)) throw new IOException("Le contenu a changé depuis l'aperçu ; recommencez.");
        Path deepest = planned.get(planned.size() - 1);
        List<Path> entries;
        try (Stream<Path> s = Files.list(deepest)) { entries = s.toList(); }
        for (Path entry : entries) {
            if (Files.exists(copy.resolve(entry.getFileName()), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Conflit de nom : " + entry.getFileName());
        }
        List<Path> moved = new ArrayList<>();
        try {
            for (Path entry : entries) {
                Files.move(entry, copy.resolve(entry.getFileName()));
                moved.add(entry);
            }
        } catch (IOException e) {
            for (Path entry : moved) {
                try { Files.move(copy.resolve(entry.getFileName()), entry); } catch (IOException rollback) { e.addSuppressed(rollback); }
            }
            throw new IOException("Déplacement interrompu ; vérifiez la copie et les erreurs d'annulation.", e);
        }
        for (int i = planned.size() - 1; i >= 0; i--) Files.delete(planned.get(i));
    }
}
