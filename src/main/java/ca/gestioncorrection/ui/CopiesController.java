package ca.gestioncorrection.ui;

import ca.gestioncorrection.persistence.Database;
import ca.gestioncorrection.service.CopyFileService;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Gère les associations d'étudiants et les opérations sur les dossiers de copies.
 *
 * @author AI
 */
public class CopiesController extends ViewSupport {
    @FXML private ListView<Row> copies;
    @FXML private Button scanButton;
    @FXML private Button editStudentButton;
    @FXML private Button anonymiseButton;
    @FXML private Button deanonymiseButton;
    @FXML private Button anonymiseAllButton;
    @FXML private Button deanonymiseAllButton;
    @FXML private Button flattenButton;
    @FXML private Label sourceHint;
    private CopyFileService files;

    /** Crée le contrôleur instancié par le chargeur FXML. */
    public CopiesController() {}

    /** Configure le service de fichiers et lie les actions à leurs prérequis. */
    @Override
    void configure(MainController main, Database db, Stage stage) {
        super.configure(main, db, stage);
        files = new CopyFileService(db);
        BooleanBinding noEvaluation = main.evaluationProperty().isNull();
        BooleanBinding noSource = Bindings.createBooleanBinding(
                () -> main.currentEvaluation() == null || text(main.currentEvaluation().data(), "copies").isBlank(),
                main.evaluationProperty());
        BooleanBinding noCopy = copies.getSelectionModel().selectedItemProperty().isNull();
        BooleanBinding selectedAnonymous = Bindings.createBooleanBinding(
                () -> selectedCopy() != null && number(selectedCopy().data(), "anonyme") != 0,
                copies.getSelectionModel().selectedItemProperty());
        BooleanBinding noIdentifiedCopies = Bindings.createBooleanBinding(
                () -> copies.getItems().stream().noneMatch(row -> number(row.data(), "anonyme") == 0),
                copies.getItems());
        BooleanBinding noAnonymousCopies = Bindings.createBooleanBinding(
                () -> copies.getItems().stream().noneMatch(row -> number(row.data(), "anonyme") != 0),
                copies.getItems());
        copies.disableProperty().bind(noEvaluation);
        scanButton.disableProperty().bind(noSource);
        editStudentButton.disableProperty().bind(noEvaluation.or(noCopy));
        anonymiseButton.disableProperty().bind(noSource.or(noCopy).or(selectedAnonymous));
        deanonymiseButton.disableProperty().bind(noSource.or(noCopy).or(selectedAnonymous.not()));
        anonymiseAllButton.disableProperty().bind(noSource.or(noIdentifiedCopies));
        deanonymiseAllButton.disableProperty().bind(noSource.or(noAnonymousCopies));
        flattenButton.disableProperty().bind(noSource.or(noCopy));
        sourceHint.visibleProperty().bind(noSource);
        sourceHint.managedProperty().bind(sourceHint.visibleProperty());
        copies.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (!main.isLoading()) safe(main::copyChanged);
        });
    }

    /** Retourne la copie sélectionnée, ou {@code null}. */
    Row selectedCopy() {
        return copies.getSelectionModel().getSelectedItem();
    }

    /** Retourne un instantané des copies affichées. */
    List<Row> copies() {
        return List.copyOf(copies.getItems());
    }

    /** Répercute dans la liste la copie choisie depuis l'onglet de correction. */
    void selectCopy(Row copy) {
        copies.getSelectionModel().select(copy);
    }

    /** Recharge les copies et leurs états d'anonymisation pour l'évaluation courante. */
    void refreshList() throws Exception {
        if (main.currentEvaluation() == null) {
            copies.getItems().clear();
            return;
        }
        setRows(copies, rows("SELECT * FROM copie WHERE evaluation_id=? ORDER BY nom,prenom,dossier", row ->
                text(row, "dossier") + " — "
                        + (text(row, "nom").isBlank() ? "association à compléter"
                        : text(row, "nom") + ", " + text(row, "prenom"))
                        + (number(row, "anonyme") == 1 ? " [anonyme]" : ""), main.evaluationId()));
    }

    /** Résout et valide le dossier de copies configuré pour l'évaluation. */
    private Path source() throws Exception {
        return CopyFileService.directory(text(main.currentEvaluation().data(), "copies"));
    }

    /** Déclenche l'importation des sous-dossiers de copies. */
    @FXML private void scan() { safe(this::importCopies); }
    /** Ouvre l'association du nom et du prénom de la copie sélectionnée. */
    @FXML private void editStudent() { safe(this::associateStudent); }
    /** Anonymise la copie sélectionnée après confirmation. */
    @FXML private void anonymise() { safe(() -> rename(false, true)); }
    /** Rétablit le nom original de la copie sélectionnée après confirmation. */
    @FXML private void deanonymise() { safe(() -> rename(false, false)); }
    /** Anonymise les copies non anonymisées après confirmation. */
    @FXML private void anonymiseAll() { safe(() -> rename(true, true)); }
    /** Rétablit les noms originaux des copies anonymisées après confirmation. */
    @FXML private void deanonymiseAll() { safe(() -> rename(true, false)); }
    /** Présente l'aperçu d'aplatissement de la copie sélectionnée. */
    @FXML private void flatten() { safe(this::flattenCopy); }

    /** Importe les dossiers détectés et invite à vérifier les associations proposées. */
    private void importCopies() throws Exception {
        long evaluation = main.evaluationId();
        files.scan(evaluation, source());
        main.refreshAll();
        info("Dossiers importés. Vérifiez ou complétez les associations nom/prénom avant l'export.");
    }

    /** Enregistre les nom et prénom associés à la copie sélectionnée. */
    private void associateStudent() throws Exception {
        Row copy = selectedCopy();
        id(copy, "Sélectionnez une copie.");
        Optional<List<String>> fields = form("Associer l'étudiant", new String[]{"Nom", "Prénom"},
                values -> notBlank(values.get(0)) && notBlank(values.get(1)), "Le nom et le prénom sont obligatoires.",
                text(copy.data(), "nom"), text(copy.data(), "prenom"));
        if (fields.isEmpty()) return;
        db.execute("UPDATE copie SET nom=?,prenom=? WHERE id=?", required(fields.get().get(0), "Le nom"),
                required(fields.get().get(1), "Le prénom"), copy.id());
        main.refreshAll();
    }

    /** Renomme une ou plusieurs copies et indique le nombre traité si une erreur interrompt le lot. */
    private void rename(boolean all, boolean anonymise) throws Exception {
        main.evaluationId();
        Path root = source();
        List<Row> selected;
        if (all) {
            selected = copies().stream()
                    .filter(row -> (number(row.data(), "anonyme") == 0) == anonymise).toList();
        } else {
            Row copy = selectedCopy();
            id(copy, "Sélectionnez une copie.");
            selected = List.of(copy);
        }
        if (selected.isEmpty()) {
            info("Aucune copie à modifier.");
            return;
        }
        if (!confirm(anonymise ? "Anonymiser" : "Désanonymiser", selected.size()
                + " dossier(s) seront renommés dans :\n" + root + "\nUne sauvegarde préalable est recommandée.")) return;
        int completed = 0;
        try {
            for (Row copy : selected) {
                files.rename(root, copy.data(), anonymise);
                completed++;
            }
        } catch (Exception exception) {
            throw new Exception(completed + " dossier(s) traités avant l'erreur : " + exception.getMessage(), exception);
        } finally {
            main.refreshAll();
        }
        info(completed + " dossier(s) renommés.");
    }

    /** Affiche la chaîne détectée et ne déplace les fichiers qu'après confirmation. */
    private void flattenCopy() throws Exception {
        Row copy = selectedCopy();
        id(copy, "Sélectionnez une copie.");
        Path directory = CopyFileService.child(source(), text(copy.data(), "dossier"));
        List<Path> plan = CopyFileService.flattenPlan(directory);
        if (plan.isEmpty()) {
            info("Aucun dossier imbriqué unique à aplatir.");
            return;
        }
        String chain = String.join("\n", plan.stream().map(Path::toString).toList());
        if (confirm("Aperçu de l'aplatissement",
                "Les éléments de ce dernier dossier seront déplacés à la racine de la copie, puis les dossiers vides supprimés :\n"
                        + chain + "\n\nUne sauvegarde préalable est recommandée.")) {
            CopyFileService.flatten(directory, plan);
            info("Aplatissement terminé.");
        }
    }
}
