package ca.gestioncorrection.ui;

import ca.gestioncorrection.persistence.Database;
import ca.gestioncorrection.service.DocxExporter;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Gère la sélection des blocs et commentaires, les notes manuelles et les exports.
 *
 * @author AI
 */
public class GradingController extends ViewSupport {
    @FXML private ComboBox<Row> gradingCopy;
    @FXML private ListView<Row> gradingCriteria;
    @FXML private ListView<Row> gradingBlocks;
    @FXML private ListView<Row> comments;
    @FXML private ListView<Row> scores;
    @FXML private Button selectBlockButton;
    @FXML private Button addCommentButton;
    @FXML private Button editCommentButton;
    @FXML private Button toggleCommentButton;
    @FXML private Button overrideCommentButton;
    @FXML private Button setScoreButton;
    @FXML private Button exportCopyButton;
    @FXML private Button exportAllButton;
    @FXML private Label exportHint;
    private boolean refreshing;

    /** Crée le contrôleur instancié par le chargeur FXML. */
    public GradingController() {}

    /** Configure les sélections et les liaisons qui désactivent les actions indisponibles. */
    @Override
    void configure(MainController main, Database db, Stage stage) {
        super.configure(main, db, stage);
        BooleanBinding noEvaluation = main.evaluationProperty().isNull();
        BooleanBinding noCopy = gradingCopy.valueProperty().isNull();
        BooleanBinding noCriterion = gradingCriteria.getSelectionModel().selectedItemProperty().isNull();
        BooleanBinding noBlock = gradingBlocks.getSelectionModel().selectedItemProperty().isNull();
        BooleanBinding noComment = comments.getSelectionModel().selectedItemProperty().isNull();
        BooleanBinding noScore = scores.getSelectionModel().selectedItemProperty().isNull();
        BooleanBinding blockNotChosen = Bindings.createBooleanBinding(() -> {
            Row block = gradingBlocks.getSelectionModel().getSelectedItem();
            return block == null || block.data().get("selected") == null
                    || number(block.data(), "selected") != block.id();
        }, gradingBlocks.getSelectionModel().selectedItemProperty());
        BooleanBinding commentNotChosen = Bindings.createBooleanBinding(() -> {
            Row comment = comments.getSelectionModel().getSelectedItem();
            return comment == null || comment.data().get("selected") == null;
        }, comments.getSelectionModel().selectedItemProperty());
        BooleanBinding noExportPath = Bindings.createBooleanBinding(
                () -> main.currentEvaluation() == null || text(main.currentEvaluation().data(), "exports").isBlank(),
                main.evaluationProperty());
        BooleanBinding invalidCopyName = Bindings.createBooleanBinding(
                () -> !hasValidExportName(gradingCopy.getValue()), gradingCopy.valueProperty());
        BooleanBinding invalidBatch = Bindings.createBooleanBinding(
                () -> !hasDistinctExportNames(), gradingCopy.getItems());

        gradingCopy.disableProperty().bind(noEvaluation.or(Bindings.isEmpty(gradingCopy.getItems())));
        gradingCriteria.disableProperty().bind(noEvaluation.or(noCopy));
        scores.disableProperty().bind(noEvaluation.or(noCopy));
        gradingBlocks.disableProperty().bind(noEvaluation.or(noCopy).or(noCriterion));
        comments.disableProperty().bind(noEvaluation.or(noCopy).or(noCriterion).or(noBlock));
        selectBlockButton.disableProperty().bind(noEvaluation.or(noCopy).or(noCriterion).or(noBlock).or(blockNotChosen.not()));
        addCommentButton.disableProperty().bind(noEvaluation.or(noCopy).or(noCriterion).or(noBlock));
        editCommentButton.disableProperty().bind(noEvaluation.or(noCopy).or(noCriterion).or(noBlock).or(noComment));
        toggleCommentButton.disableProperty().bind(noEvaluation.or(noCopy).or(noCriterion).or(noBlock).or(noComment).or(blockNotChosen));
        overrideCommentButton.disableProperty().bind(noEvaluation.or(noCopy).or(noCriterion).or(noBlock).or(noComment).or(blockNotChosen).or(commentNotChosen));
        setScoreButton.disableProperty().bind(noEvaluation.or(noCopy).or(noScore));
        exportCopyButton.disableProperty().bind(noExportPath.or(invalidCopyName));
        exportAllButton.disableProperty().bind(noExportPath.or(invalidBatch));
        exportHint.visibleProperty().bind(exportCopyButton.disableProperty().or(exportAllButton.disableProperty()));
        exportHint.managedProperty().bind(exportHint.visibleProperty());
        exportCopyButton.setTooltip(new Tooltip("Configurer le dossier d'export et renseigner un nom et un prénom valides."));
        exportAllButton.setTooltip(new Tooltip("Configurer le dossier d'export et renseigner des noms et prénoms valides et distincts pour toutes les copies."));
        gradingCopy.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!main.isLoading() && !refreshing && !Objects.equals(main.selectedCopy(), newValue)) {
                main.selectCopy(newValue);
            }
        });
        gradingCriteria.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (!main.isLoading() && !refreshing) safe(this::loadBlocks);
        });
        gradingBlocks.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (!main.isLoading() && !refreshing) safe(this::loadComments);
        });
    }

    /** Vérifie qu'une copie possède un nom de fichier accepté par l'exporteur. */
    private boolean hasValidExportName(Row copy) {
        if (copy == null) return false;
        try {
            DocxExporter.filename(text(copy.data(), "nom"), text(copy.data(), "prenom"));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Vérifie que le lot n'est pas vide et ne contient aucun nom de fichier invalide ou dupliqué. */
    private boolean hasDistinctExportNames() {
        if (gradingCopy.getItems().isEmpty()) return false;
        Set<String> names = new HashSet<>();
        for (Row copy : gradingCopy.getItems()) {
            if (!hasValidExportName(copy)) return false;
            String filename = DocxExporter.filename(text(copy.data(), "nom"), text(copy.data(), "prenom"));
            if (!names.add(filename)) return false;
        }
        return true;
    }

    /** Synchronise la liste des copies de correction avec celle de l'onglet Copies. */
    void refreshCopies() {
        gradingCopy.getItems().setAll(main.copies());
        gradingCopy.setValue(main.selectedCopy());
    }

    /** Met à jour la copie sans relancer la synchronisation des sélections. */
    void selectCopy(Row copy) {
        refreshing = true;
        try {
            gradingCopy.setValue(copy);
        } finally {
            refreshing = false;
        }
    }

    /** Recharge les critères et les notes de la copie sans calculer de points. */
    void refresh() throws Exception {
        refreshing = true;
        try {
            Row copy = main.selectedCopy();
            if (main.currentEvaluation() == null || copy == null) {
                gradingCriteria.getItems().clear();
                gradingBlocks.getItems().clear();
                comments.getItems().clear();
                scores.getItems().clear();
                return;
            }
            long evaluation = main.evaluationId();
            setRows(gradingCriteria, rows(
                    "SELECT c.*, ch.bloc_id FROM critere c LEFT JOIN choix ch ON ch.critere_id=c.id AND ch.copie_id=? WHERE c.evaluation_id=? ORDER BY c.region,c.id",
                    row -> (row.get("bloc_id") == null ? "[ ] " : "[X] ")
                            + (text(row, "region").isBlank() ? "" : text(row, "region") + " — ")
                            + text(row, "nom"), copy.id(), evaluation));

            List<Row> scoreRows = new ArrayList<>();
            Set<String> regions = new HashSet<>();
            for (Map<String, Object> criterion : db.query("SELECT * FROM critere WHERE evaluation_id=? ORDER BY region,id", evaluation)) {
                String region = text(criterion, "region");
                long criterionId = number(criterion, "id");
                if (!region.isBlank() && regions.add(region)) {
                    scoreRows.add(scoreRow(copy.id(), -criterionId, "R:" + region, "Région : " + region));
                }
                scoreRows.add(scoreRow(copy.id(), criterionId, "C:" + criterionId, "Critère : " + text(criterion, "nom")));
            }
            setRows(scores, scoreRows);
        } finally {
            refreshing = false;
        }
        loadBlocks();
    }

    /** Construit une ligne de note manuelle à partir de sa clé de région ou de critère. */
    private Row scoreRow(long copy, long rowId, String key, String label) throws Exception {
        List<Map<String, Object>> value = db.query("SELECT points FROM note WHERE copie_id=? AND cible=?", copy, key);
        Map<String, Object> data = new HashMap<>();
        data.put("key", key);
        data.put("points", value.isEmpty() ? "" : value.get(0).get("points"));
        return new Row(rowId, label + " — " + (value.isEmpty() ? "non saisis" : text(value.get(0), "points")), data);
    }

    /** Recharge les blocs du critère sélectionné et marque celui choisi pour la copie. */
    private void loadBlocks() throws Exception {
        Row criterion = gradingCriteria.getSelectionModel().getSelectedItem();
        Row copy = main.selectedCopy();
        if (criterion == null || copy == null) {
            gradingBlocks.getItems().clear();
            comments.getItems().clear();
            return;
        }
        List<Row> blocks = rows(
                "SELECT b.*,n.nom niveau,n.minimum,n.maximum,ch.bloc_id selected FROM bloc b JOIN niveau n ON n.id=b.niveau_id LEFT JOIN choix ch ON ch.critere_id=b.critere_id AND ch.copie_id=? WHERE b.critere_id=? ORDER BY n.minimum,b.id",
                row -> (row.get("selected") != null && number(row, "selected") == number(row, "id") ? "[X] " : "[ ] ")
                        + text(row, "niveau") + " [" + text(row, "minimum") + " – "
                        + text(row, "maximum") + "] : " + text(row, "texte"), copy.id(), criterion.id());
        refreshing = true;
        try {
            setRows(gradingBlocks, blocks);
        } finally {
            refreshing = false;
        }
        loadComments();
    }

    /** Recharge les commentaires du bloc et leur sélection pour la copie. */
    private void loadComments() throws Exception {
        Row block = gradingBlocks.getSelectionModel().getSelectedItem();
        Row copy = main.selectedCopy();
        if (block == null || copy == null) {
            comments.getItems().clear();
            return;
        }
        setRows(comments, rows(
                "SELECT c.*,cc.copie_id selected,cc.perte_override FROM commentaire c LEFT JOIN commentaire_choisi cc ON cc.commentaire_id=c.id AND cc.copie_id=? WHERE c.bloc_id=? ORDER BY c.id",
                row -> (row.get("selected") == null ? "[ ] " : "[X] ") + text(row, "texte") + " | perte : "
                        + (row.get("perte_override") == null ? text(row, "perte")
                        : text(row, "perte_override") + " (personnalisée)"), copy.id(), block.id()));
    }

    /** Enregistre le bloc choisi pour le critère courant. */
    @FXML private void selectBlock() { safe(this::saveBlockSelection); }
    /** Crée un commentaire réutilisable pour le bloc affiché. */
    @FXML private void addComment() { safe(this::createComment); }
    /** Modifie le modèle de commentaire sélectionné. */
    @FXML private void editComment() { safe(this::modifyComment); }
    /** Active ou retire le commentaire pour la copie courante. */
    @FXML private void toggleComment() { safe(this::changeCommentSelection); }
    /** Ouvre la saisie d'une perte propre à cette copie. */
    @FXML private void overrideComment() { safe(this::customizeCommentLoss); }
    /** Ouvre la saisie manuelle des points sélectionnés. */
    @FXML private void setScore() { safe(this::saveScore); }
    /** Exporte la grille de la copie sélectionnée. */
    @FXML private void exportCopy() { safe(this::exportSelectedCopy); }
    /** Exporte les grilles de toutes les copies après confirmation. */
    @FXML private void exportAll() { safe(this::exportAllCopies); }

    /** Remplace le bloc et retire les anciens commentaires dans une même transaction. */
    private void saveBlockSelection() throws Exception {
        Row criterion = gradingCriteria.getSelectionModel().getSelectedItem();
        Row block = gradingBlocks.getSelectionModel().getSelectedItem();
        Row copy = main.selectedCopy();
        id(criterion, "Sélectionnez un critère.");
        id(block, "Sélectionnez un bloc.");
        id(copy, "Sélectionnez une copie.");
        List<Map<String, Object>> old = db.query("SELECT bloc_id FROM choix WHERE copie_id=? AND critere_id=?",
                copy.id(), criterion.id());
        if (!old.isEmpty() && number(old.get(0), "bloc_id") != block.id()
                && !confirm("Changer de bloc", "Les commentaires sélectionnés pour l'ancien bloc seront retirés. Continuer ?")) return;

        db.beginTransaction();
        try {
            db.execute("DELETE FROM commentaire_choisi WHERE copie_id=? AND commentaire_id IN (SELECT cm.id FROM commentaire cm JOIN bloc b ON b.id=cm.bloc_id WHERE b.critere_id=? AND b.id<>?)",
                    copy.id(), criterion.id(), block.id());
            db.execute("INSERT INTO choix(copie_id,critere_id,bloc_id) VALUES(?,?,?) ON CONFLICT(copie_id,critere_id) DO UPDATE SET bloc_id=excluded.bloc_id",
                    copy.id(), criterion.id(), block.id());
            db.commit();
        } catch (Exception exception) {
            db.rollback();
            throw exception;
        } finally {
            db.endTransaction();
        }
        refresh();
    }

    /** Ajoute un commentaire et sa perte commune au bloc sélectionné. */
    private void createComment() throws Exception {
        Row block = gradingBlocks.getSelectionModel().getSelectedItem();
        id(block, "Sélectionnez un bloc.");
        Optional<List<String>> fields = form("Nouveau commentaire réutilisable",
                new String[]{"Texte du commentaire", "Perte commune"},
                values -> notBlank(values.get(0)) && validDecimal(values.get(1), true),
                "Le texte est obligatoire et la perte doit être un nombre positif ou nul.", "", "0");
        if (fields.isEmpty()) return;
        db.insert("INSERT INTO commentaire(bloc_id,texte,perte) VALUES(?,?,?)", block.id(),
                required(fields.get().get(0), "Le texte"), decimal(fields.get().get(1), true));
        loadComments();
    }

    /** Modifie un commentaire commun après confirmation de son effet sur les autres copies. */
    private void modifyComment() throws Exception {
        Row comment = comments.getSelectionModel().getSelectedItem();
        id(comment, "Sélectionnez un commentaire.");
        Optional<List<String>> fields = form("Modifier le commentaire commun",
                new String[]{"Texte du commentaire", "Perte commune"},
                values -> notBlank(values.get(0)) && validDecimal(values.get(1), true),
                "Le texte est obligatoire et la perte doit être un nombre positif ou nul.",
                text(comment.data(), "texte"), text(comment.data(), "perte"));
        if (fields.isEmpty()) return;
        if (!confirm("Modifier le modèle",
                "Cette modification s'applique à toutes les copies utilisant ce commentaire, sauf les pertes personnalisées. Continuer ?")) return;
        db.execute("UPDATE commentaire SET texte=?,perte=? WHERE id=?",
                required(fields.get().get(0), "Le texte"), decimal(fields.get().get(1), true), comment.id());
        loadComments();
    }

    /** Active ou retire un commentaire, uniquement si son bloc est choisi pour la copie. */
    private void changeCommentSelection() throws Exception {
        Row comment = comments.getSelectionModel().getSelectedItem();
        Row block = gradingBlocks.getSelectionModel().getSelectedItem();
        Row copy = main.selectedCopy();
        id(comment, "Sélectionnez un commentaire.");
        id(block, "Sélectionnez un bloc.");
        id(copy, "Sélectionnez une copie.");
        if (comment.data().get("selected") != null) {
            db.execute("DELETE FROM commentaire_choisi WHERE copie_id=? AND commentaire_id=?", copy.id(), comment.id());
        } else {
            if (db.query("SELECT 1 FROM choix WHERE copie_id=? AND bloc_id=?", copy.id(), block.id()).isEmpty()) {
                throw new IllegalArgumentException("Sélectionnez d'abord ce bloc pour la copie.");
            }
            db.insert("INSERT INTO commentaire_choisi(copie_id,commentaire_id) VALUES(?,?)", copy.id(), comment.id());
        }
        loadComments();
    }

    /** Définit une perte locale ou rétablit la valeur commune si la saisie est vide. */
    private void customizeCommentLoss() throws Exception {
        Row comment = comments.getSelectionModel().getSelectedItem();
        Row copy = main.selectedCopy();
        id(comment, "Sélectionnez un commentaire.");
        id(copy, "Sélectionnez une copie.");
        if (comment.data().get("selected") == null) throw new IllegalArgumentException("Activez d'abord ce commentaire.");
        Optional<List<String>> fields = form("Perte personnalisée pour cette copie",
                new String[]{"Perte (vide pour rétablir la valeur commune)"},
                values -> values.get(0).isBlank() || validDecimal(values.get(0), true),
                "La perte doit être vide ou être un nombre positif ou nul.", text(comment.data(), "perte_override"));
        if (fields.isEmpty()) return;
        String value = fields.get().get(0).trim();
        db.execute("UPDATE commentaire_choisi SET perte_override=? WHERE copie_id=? AND commentaire_id=?",
                value.isEmpty() ? null : decimal(value, true), copy.id(), comment.id());
        loadComments();
    }

    /** Enregistre les points saisis pour une région ou un critère, sans déductions automatiques. */
    private void saveScore() throws Exception {
        Row score = scores.getSelectionModel().getSelectedItem();
        Row copy = main.selectedCopy();
        id(score, "Sélectionnez une région ou un critère.");
        id(copy, "Sélectionnez une copie.");
        Optional<List<String>> fields = form("Points saisis manuellement",
                new String[]{"Points (aucun calcul automatique)"},
                values -> validDecimal(values.get(0), true), "Les points doivent être un nombre positif ou nul.",
                text(score.data(), "points"));
        if (fields.isEmpty()) return;
        db.execute("INSERT INTO note(copie_id,cible,points) VALUES(?,?,?) ON CONFLICT(copie_id,cible) DO UPDATE SET points=excluded.points",
                copy.id(), text(score.data(), "key"), decimal(fields.get().get(0), true));
        refresh();
    }

    /** Exporte un document pour la copie courante et affiche son chemin. */
    private void exportSelectedCopy() throws Exception {
        main.evaluationId();
        Row copy = main.selectedCopy();
        id(copy, "Sélectionnez une copie.");
        Path result = new DocxExporter(db).export(main.currentEvaluation().data(), copy.data());
        info("Grille exportée :\n" + result);
    }

    /** Exporte le lot et indique le nombre de documents créés si une erreur l'interrompt. */
    private void exportAllCopies() throws Exception {
        main.evaluationId();
        List<Row> copies = main.copies();
        if (copies.isEmpty()) throw new IllegalArgumentException("Aucune copie à exporter.");
        if (!confirm("Exporter les grilles",
                copies.size() + " grille(s) seront créées sans écraser les fichiers existants. Continuer ?")) return;
        int completed = 0;
        try {
            for (Row copy : copies) {
                new DocxExporter(db).export(main.currentEvaluation().data(), copy.data());
                completed++;
            }
        } catch (Exception exception) {
            throw new Exception(completed + " grille(s) exportées avant l'erreur : " + exception.getMessage(), exception);
        }
        info(completed + " grille(s) exportées.");
    }
}
