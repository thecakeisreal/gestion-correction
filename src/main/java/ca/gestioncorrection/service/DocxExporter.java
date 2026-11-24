package ca.gestioncorrection.service;

import ca.gestioncorrection.persistence.Database;

import org.apache.poi.xwpf.usermodel.*;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

/**
 * Exporte les données de correction enregistrées en DOCX sans recalculer les points.
 *
 * @author AI
 */
public final class DocxExporter {
    private final Database db;
    /**
     * Initialise l'exporteur avec la base contenant les grilles et corrections.
     * @param db base de données locale
     */
    public DocxExporter(Database db) { this.db = db; }

    /**
     * Produit le nom du document à partir du nom et du prénom de l'étudiant.
     * @param nom nom de famille
     * @param prenom prénom
     * @return nom de fichier DOCX compatible avec les restrictions appliquées sous Windows
     * @throws IllegalArgumentException si un nom est vide ou contient un caractère interdit
     */
    public static String filename(String nom, String prenom) {
        if (nom.isBlank() || prenom.isBlank()) throw new IllegalArgumentException("Le nom et le prénom sont requis pour exporter.");
        String base = nom.trim() + ", " + prenom.trim();
        if (base.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*") || base.endsWith(".") || base.endsWith(" "))
            throw new IllegalArgumentException("Le nom ou le prénom contient un caractère interdit dans un nom de fichier Windows.");
        return base + ".docx";
    }

    /**
     * Écrit la grille complète, les choix, les points manuels et les commentaires dans le dossier
     * d'export de l'évaluation. Un document existant n'est jamais volontairement écrasé.
     * @param evaluation ligne SQLite de l'évaluation, incluant son dossier d'export
     * @param copy ligne SQLite de la copie, incluant son nom et son prénom
     * @return chemin du document créé
     * @throws Exception si les données, le dossier ou l'écriture du document sont invalides
     */
    public Path export(Map<String, Object> evaluation, Map<String, Object> copy) throws Exception {
        Path targetDir = CopyFileService.directory(evaluation.get("exports").toString());
        Path target = targetDir.resolve(filename(copy.get("nom").toString(), copy.get("prenom").toString()));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Le fichier existe déjà : " + target);
        long evalId = ((Number) evaluation.get("id")).longValue();
        long copyId = ((Number) copy.get("id")).longValue();
        Path temp = Files.createTempFile(targetDir, ".grille-", ".tmp");
        try {
            try (XWPFDocument doc = new XWPFDocument()) {
                line(doc, "Grille de correction — " + evaluation.get("nom"), true);
                line(doc, "Étudiant : " + copy.get("nom") + ", " + copy.get("prenom"), true);
                List<Map<String, Object>> criteria = db.query("SELECT * FROM critere WHERE evaluation_id=? ORDER BY region, id", evalId);
                if (criteria.isEmpty()) line(doc, "Aucun critère défini.", false);
                String lastRegion = null;
                for (Map<String, Object> criterion : criteria) {
                    long criterionId = ((Number) criterion.get("id")).longValue();
                    String region = criterion.get("region").toString();
                    if (!region.isBlank() && !region.equals(lastRegion)) {
                        line(doc, "Région : " + region + " — Points : " + score(copyId, "R:" + region), true);
                    }
                    lastRegion = region;
                    line(doc, "Critère : " + criterion.get("nom") + " | Indicateur : " + criterion.get("indicateur") + " | Pondération : " + criterion.get("ponderation")
                            + " | Points : " + score(copyId, "C:" + criterionId), true);
                    List<Map<String, Object>> choices = db.query("SELECT bloc_id FROM choix WHERE copie_id=? AND critere_id=?", copyId, criterionId);
                    long selected = choices.isEmpty() ? -1 : ((Number) choices.get(0).get("bloc_id")).longValue();
                    for (Map<String, Object> level : db.query("SELECT * FROM niveau WHERE evaluation_id=? ORDER BY minimum, id", evalId)) {
                        line(doc, "Niveau : " + level.get("nom") + " [" + level.get("minimum") + " – " + level.get("maximum") + "]", false);
                        for (Map<String, Object> block : db.query("SELECT * FROM bloc WHERE critere_id=? AND niveau_id=? ORDER BY id", criterionId, level.get("id"))) {
                            long blockId = ((Number) block.get("id")).longValue();
                            line(doc, (blockId == selected ? "[X] " : "[ ] ") + block.get("texte"), false);
                            if (blockId == selected) {
                                for (Map<String, Object> comment : db.query("SELECT c.texte, c.perte, cc.perte_override FROM commentaire_choisi cc JOIN commentaire c ON c.id=cc.commentaire_id WHERE cc.copie_id=? AND c.bloc_id=? ORDER BY c.id", copyId, blockId)) {
                                    Object override = comment.get("perte_override");
                                    line(doc, "  Commentaire : " + comment.get("texte") + " | Perte : " + (override == null ? comment.get("perte") : override + " (personnalisée)"), false);
                                }
                            }
                        }
                    }
                    if (selected == -1) line(doc, "Aucun bloc sélectionné.", false);
                }
                try (OutputStream out = Files.newOutputStream(temp)) { doc.write(out); }
            }
            Files.move(temp, target);
            return target;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Retourne la note enregistrée pour une région ou un critère, ou indique qu'elle manque. */
    private String score(long copy, String key) throws Exception {
        List<Map<String, Object>> rows = db.query("SELECT points FROM note WHERE copie_id=? AND cible=?", copy, key);
        return rows.isEmpty() ? "non saisis" : rows.get(0).get("points").toString();
    }

    /** Ajoute un paragraphe au document en conservant les retours à la ligne du texte. */
    private static void line(XWPFDocument doc, String text, boolean bold) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setBold(bold);
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) run.addBreak();
            run.setText(lines[i]);
        }
    }
}
