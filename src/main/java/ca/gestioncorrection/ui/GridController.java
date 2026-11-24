package ca.gestioncorrection.ui;

import ca.gestioncorrection.persistence.Database;
import javafx.beans.binding.BooleanBinding;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gère les critères, niveaux et blocs descriptifs de la grille d'une évaluation.
 *
 * @author AI
 */
public class GridController extends ViewSupport {
    @FXML private ListView<Row> criteria;
    @FXML private ListView<Row> levels;
    @FXML private ListView<Row> blocks;
    @FXML private Button addCriterionButton;
    @FXML private Button modifyCriterionButton;
    @FXML private Button addLevelButton;
    @FXML private Button modifyLevelButton;
    @FXML private Button addBlockButton;
    @FXML private Button modifyBlockButton;

    /** Crée le contrôleur instancié par le chargeur FXML. */
    public GridController() {}

    /** Configure les dépendances et active les actions selon les sélections courantes. */
    @Override
    void configure(MainController main, Database db, Stage stage) {
        super.configure(main, db, stage);
        BooleanBinding noEvaluation = main.evaluationProperty().isNull();
        BooleanBinding noCriterion = criteria.getSelectionModel().selectedItemProperty().isNull();
        BooleanBinding noLevel = levels.getSelectionModel().selectedItemProperty().isNull();
        BooleanBinding noBlock = blocks.getSelectionModel().selectedItemProperty().isNull();
        criteria.disableProperty().bind(noEvaluation);
        levels.disableProperty().bind(noEvaluation);
        addCriterionButton.disableProperty().bind(noEvaluation);
        addLevelButton.disableProperty().bind(noEvaluation);
        modifyCriterionButton.disableProperty().bind(noEvaluation.or(noCriterion));
        modifyLevelButton.disableProperty().bind(noEvaluation.or(noLevel));
        blocks.disableProperty().bind(noEvaluation.or(noCriterion).or(noLevel));
        addBlockButton.disableProperty().bind(noEvaluation.or(noCriterion).or(noLevel));
        modifyBlockButton.disableProperty().bind(noEvaluation.or(noCriterion).or(noLevel).or(noBlock));
        criteria.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (!main.isLoading()) safe(this::loadBlocks);
        });
        levels.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (!main.isLoading()) safe(this::loadBlocks);
        });
    }

    /** Recharge les critères et les niveaux de l'évaluation tout en conservant la sélection. */
    void refreshLists() throws Exception {
        if (main.currentEvaluation() == null) {
            criteria.getItems().clear();
            levels.getItems().clear();
            blocks.getItems().clear();
            return;
        }
        long evaluation = main.evaluationId();
        setRows(criteria, rows("SELECT * FROM critere WHERE evaluation_id=? ORDER BY region,id",
                row -> (text(row, "region").isBlank() ? "" : text(row, "region") + " — ")
                        + text(row, "nom") + " | " + text(row, "ponderation"), evaluation));
        setRows(levels, rows("SELECT * FROM niveau WHERE evaluation_id=? ORDER BY minimum,id",
                row -> text(row, "nom") + " [" + text(row, "minimum") + " – "
                        + text(row, "maximum") + "]", evaluation));
    }

    /** Charge les blocs associés au critère et au niveau sélectionnés. */
    void loadBlocks() throws Exception {
        Row criterion = criteria.getSelectionModel().getSelectedItem();
        Row level = levels.getSelectionModel().getSelectedItem();
        if (criterion == null || level == null) {
            blocks.getItems().clear();
            return;
        }
        setRows(blocks, rows("SELECT * FROM bloc WHERE critere_id=? AND niveau_id=? ORDER BY id",
                row -> text(row, "texte"), criterion.id(), level.id()));
    }

    /** Ouvre le formulaire de création d'un critère. */
    @FXML private void addCriterion() { safe(() -> editCriterion(false)); }
    /** Ouvre le formulaire de modification du critère sélectionné. */
    @FXML private void modifyCriterion() { safe(() -> editCriterion(true)); }
    /** Ouvre le formulaire de création d'un niveau. */
    @FXML private void addLevel() { safe(() -> editLevel(false)); }
    /** Ouvre le formulaire de modification du niveau sélectionné. */
    @FXML private void modifyLevel() { safe(() -> editLevel(true)); }
    /** Ouvre le formulaire de création d'un bloc descriptif. */
    @FXML private void addBlock() { safe(() -> editBlock(false)); }
    /** Ouvre le formulaire de modification du bloc sélectionné. */
    @FXML private void modifyBlock() { safe(() -> editBlock(true)); }

    /** Crée ou modifie un critère ; sa région reste inchangée après la saisie de points. */
    private void editCriterion(boolean edit) throws Exception {
        long evaluation = main.evaluationId();
        Row selected = criteria.getSelectionModel().getSelectedItem();
        if (edit) id(selected, "Sélectionnez un critère.");
        Map<String, Object> existing = edit ? selected.data() : Map.of();
        Optional<List<String>> fields = form(edit ? "Modifier le critère" : "Ajouter un critère",
                new String[]{"Région (facultative)", "Critère d'évaluation", "Indicateur", "Pondération"},
                values -> notBlank(values.get(1)) && notBlank(values.get(2)) && validDecimal(values.get(3), true),
                "Le critère et l'indicateur sont obligatoires ; la pondération doit être un nombre positif ou nul.",
                text(existing, "region"), text(existing, "nom"), text(existing, "indicateur"),
                edit ? text(existing, "ponderation") : "0");
        if (fields.isEmpty()) return;

        List<String> values = fields.get();
        String region = values.get(0).trim();
        String name = required(values.get(1), "Le critère");
        String indicator = required(values.get(2), "L'indicateur");
        double weight = decimal(values.get(3), true);
        if (edit) {
            if (!region.equals(text(existing, "region"))
                    && !db.query("SELECT n.copie_id FROM note n JOIN copie cp ON cp.id=n.copie_id WHERE cp.evaluation_id=?", evaluation).isEmpty()) {
                throw new IllegalArgumentException("La région ne peut pas être modifiée après la saisie de points pour cette évaluation.");
            }
            db.execute("UPDATE critere SET region=?,nom=?,indicateur=?,ponderation=? WHERE id=?",
                    region, name, indicator, weight, selected.id());
        } else {
            db.insert("INSERT INTO critere(evaluation_id,region,nom,indicateur,ponderation) VALUES(?,?,?,?,?)",
                    evaluation, region, name, indicator, weight);
        }
        main.refreshAll();
    }

    /** Crée ou modifie un niveau après validation de sa plage. */
    private void editLevel(boolean edit) throws Exception {
        long evaluation = main.evaluationId();
        Row selected = levels.getSelectionModel().getSelectedItem();
        if (edit) id(selected, "Sélectionnez un niveau.");
        Map<String, Object> existing = edit ? selected.data() : Map.of();
        Optional<List<String>> fields = form(edit ? "Modifier le niveau" : "Ajouter un niveau",
                new String[]{"Nom", "Minimum inclus", "Maximum inclus"},
                values -> validLevel(values, edit ? selected : null),
                "Le nom et une plage numérique valide, sans chevauchement avec un autre niveau, sont obligatoires.",
                text(existing, "nom"), text(existing, "minimum"), text(existing, "maximum"));
        if (fields.isEmpty()) return;

        List<String> values = fields.get();
        db.saveLevel(evaluation, edit ? selected.id() : null, required(values.get(0), "Le nom"),
                decimal(values.get(1), false), decimal(values.get(2), false));
        main.refreshAll();
    }

    /** Vérifie les bornes inclusives et leur absence de chevauchement avec les autres niveaux. */
    private boolean validLevel(List<String> values, Row editedLevel) {
        if (!notBlank(values.get(0)) || !validDecimal(values.get(1), false) || !validDecimal(values.get(2), false)) {
            return false;
        }
        double minimum = decimal(values.get(1), false);
        double maximum = decimal(values.get(2), false);
        if (minimum > maximum) return false;
        return levels.getItems().stream()
                .filter(level -> editedLevel == null || level.id() != editedLevel.id())
                .noneMatch(level -> ((Number) level.data().get("minimum")).doubleValue() <= maximum
                        && ((Number) level.data().get("maximum")).doubleValue() >= minimum);
    }

    /** Crée ou modifie un bloc pour le critère et le niveau sélectionnés. */
    private void editBlock(boolean edit) throws Exception {
        long criterion = id(criteria.getSelectionModel().getSelectedItem(), "Sélectionnez un critère.");
        long level = id(levels.getSelectionModel().getSelectedItem(), "Sélectionnez un niveau.");
        Row selected = blocks.getSelectionModel().getSelectedItem();
        if (edit) id(selected, "Sélectionnez un bloc.");
        Optional<List<String>> fields = form(edit ? "Modifier le bloc" : "Ajouter un bloc",
                new String[]{"Texte descriptif"}, values -> notBlank(values.get(0)),
                "Le texte descriptif est obligatoire.", edit ? text(selected.data(), "texte") : "");
        if (fields.isEmpty()) return;

        String description = required(fields.get().get(0), "Le texte");
        if (edit) {
            db.execute("UPDATE bloc SET texte=? WHERE id=?", description, selected.id());
        } else {
            db.insert("INSERT INTO bloc(critere_id,niveau_id,texte) VALUES(?,?,?)", criterion, level, description);
        }
        loadBlocks();
        main.refreshGrading();
    }
}
