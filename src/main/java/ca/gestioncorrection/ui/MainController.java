package ca.gestioncorrection.ui;

import ca.gestioncorrection.persistence.Database;
import ca.gestioncorrection.service.CopyFileService;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Coordonne la sélection session-cours-évaluation et synchronise les trois onglets.
 *
 * @author AI
 */
public class MainController extends ViewSupport {
    @FXML private ComboBox<Row> sessions;
    @FXML private ComboBox<Row> courses;
    @FXML private ComboBox<Row> evaluations;
    @FXML private Label status;
    @FXML private Button addCourseButton;
    @FXML private Button addEvaluationButton;
    @FXML private Button editPathsButton;
    @FXML private GridController gridViewController;
    @FXML private CopiesController copiesViewController;
    @FXML private GradingController gradingViewController;

    private boolean loading;

    /** Crée le contrôleur instancié par le chargeur FXML. */
    public MainController() {}

    /**
     * Injecte les dépendances, lie les états des commandes et charge les sessions.
     * @param database base locale partagée par les contrôleurs
     * @param window fenêtre propriétaire des dialogues
     * @throws Exception si la lecture initiale des données échoue
     */
    public void configure(Database database, Stage window) throws Exception {
        super.configure(this, database, window);
        courses.disableProperty().bind(sessions.valueProperty().isNull());
        addCourseButton.disableProperty().bind(sessions.valueProperty().isNull());
        evaluations.disableProperty().bind(courses.valueProperty().isNull());
        addEvaluationButton.disableProperty().bind(courses.valueProperty().isNull());
        editPathsButton.disableProperty().bind(evaluations.valueProperty().isNull());
        gridViewController.configure(this, database, window);
        copiesViewController.configure(this, database, window);
        gradingViewController.configure(this, database, window);

        sessions.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!loading) safe(this::loadCourses);
        });
        courses.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!loading) safe(this::loadEvaluations);
        });
        evaluations.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!loading) safe(this::refreshAll);
        });
        loadSessions();
    }

    /** Indique si un rafraîchissement doit ignorer les événements de sélection intermédiaires. */
    boolean isLoading() {
        return loading;
    }

    /** Retourne l'évaluation sélectionnée, ou {@code null} si aucune ne l'est. */
    Row currentEvaluation() {
        return evaluations.getValue();
    }

    /** Expose la sélection d'évaluation pour les liaisons d'état des onglets. */
    ReadOnlyObjectProperty<Row> evaluationProperty() {
        return evaluations.valueProperty();
    }

    /** Exige une évaluation sélectionnée et retourne son identifiant. */
    long evaluationId() {
        return id(currentEvaluation(), "Sélectionnez une évaluation.");
    }

    /** Retourne la copie sélectionnée dans l'onglet Copies. */
    Row selectedCopy() {
        return copiesViewController.selectedCopy();
    }

    /** Retourne un instantané des copies de l'évaluation courante. */
    List<Row> copies() {
        return copiesViewController.copies();
    }

    /** Répercute la sélection d'une copie dans l'onglet Copies. */
    void selectCopy(Row copy) {
        copiesViewController.selectCopy(copy);
    }

    /** Synchronise la copie et les données de correction après un changement de sélection. */
    void copyChanged() throws Exception {
        gradingViewController.selectCopy(selectedCopy());
        gradingViewController.refresh();
        updateStatus();
    }

    /** Recharge la correction après une modification de la grille. */
    void refreshGrading() throws Exception {
        gradingViewController.refresh();
    }

    /** Recharge les onglets en évitant les réactions aux sélections transitoires. */
    void refreshAll() throws Exception {
        loading = true;
        try {
            gridViewController.refreshLists();
            copiesViewController.refreshList();
            gradingViewController.refreshCopies();
            updateStatus();
        } finally {
            loading = false;
        }
        gridViewController.loadBlocks();
        gradingViewController.refresh();
    }

    /** Affiche l'évaluation et la copie actuellement sélectionnées. */
    private void updateStatus() {
        Row evaluation = currentEvaluation();
        Row copy = selectedCopy();
        if (evaluation == null) {
            status.setText("Sélectionnez ou créez une évaluation.");
        } else {
            status.setText("Évaluation : " + evaluation.label() + " | Copie sélectionnée : "
                    + (copy == null ? "aucune" : copy.label()));
        }
    }

    /** Recharge les sessions et leur sélection, puis actualise les cours dépendants. */
    private void loadSessions() throws Exception {
        loading = true;
        try {
            Row selected = sessions.getValue();
            sessions.getItems().setAll(rows("SELECT * FROM session ORDER BY nom,id", row -> text(row, "nom")));
            restoreSelection(sessions, selected);
        } finally {
            loading = false;
        }
        loadCourses();
    }

    /** Recharge les cours de la session et actualise les évaluations dépendantes. */
    private void loadCourses() throws Exception {
        loading = true;
        try {
            Row selected = courses.getValue();
            courses.getItems().clear();
            if (sessions.getValue() != null) {
                courses.getItems().setAll(rows("SELECT * FROM cours WHERE session_id=? ORDER BY nom,id",
                        row -> text(row, "nom"), sessions.getValue().id()));
            }
            restoreSelection(courses, selected);
        } finally {
            loading = false;
        }
        loadEvaluations();
    }

    /** Recharge les évaluations du cours puis actualise les onglets. */
    private void loadEvaluations() throws Exception {
        loading = true;
        try {
            Row selected = evaluations.getValue();
            evaluations.getItems().clear();
            if (courses.getValue() != null) {
                evaluations.getItems().setAll(rows("SELECT * FROM evaluation WHERE cours_id=? ORDER BY nom,id",
                        row -> text(row, "nom"), courses.getValue().id()));
            }
            restoreSelection(evaluations, selected);
        } finally {
            loading = false;
        }
        refreshAll();
    }

    /** Rétablit une sélection précédente si elle existe encore dans la liste. */
    private void restoreSelection(ComboBox<Row> combo, Row selected) {
        if (selected != null) selectById(combo, selected.id());
    }

    /** Sélectionne la ligne correspondant à un identifiant dans une liste déroulante. */
    private void selectById(ComboBox<Row> combo, long id) {
        combo.getItems().stream().filter(row -> row.id() == id).findFirst().ifPresent(combo::setValue);
    }

    /** Déclenche la création d'une session avec affichage des erreurs. */
    @FXML private void addSession() { safe(this::createSession); }
    /** Déclenche la création d'un cours avec affichage des erreurs. */
    @FXML private void addCourse() { safe(this::createCourse); }
    /** Déclenche la création d'une évaluation avec affichage des erreurs. */
    @FXML private void addEvaluation() { safe(this::createEvaluation); }
    /** Ouvre la configuration des dossiers avec affichage des erreurs. */
    @FXML private void editPaths() { safe(this::configurePaths); }

    /** Demande un nom de session, l'enregistre et sélectionne la session créée. */
    private void createSession() throws Exception {
        Optional<List<String>> fields = form("Nouvelle session", new String[]{"Nom"},
                values -> notBlank(values.get(0)), "Le nom est obligatoire.");
        if (fields.isEmpty()) return;
        long id = db.insert("INSERT INTO session(nom) VALUES(?)", required(fields.get().get(0), "Le nom"));
        loadSessions();
        selectById(sessions, id);
    }

    /** Crée et sélectionne un cours dans la session courante. */
    private void createCourse() throws Exception {
        long session = id(sessions.getValue(), "Sélectionnez une session.");
        Optional<List<String>> fields = form("Nouveau cours", new String[]{"Nom"},
                values -> notBlank(values.get(0)), "Le nom est obligatoire.");
        if (fields.isEmpty()) return;
        long id = db.insert("INSERT INTO cours(session_id,nom) VALUES(?,?)",
                session, required(fields.get().get(0), "Le nom"));
        loadCourses();
        selectById(courses, id);
    }

    /** Crée et sélectionne une évaluation dans le cours courant. */
    private void createEvaluation() throws Exception {
        long course = id(courses.getValue(), "Sélectionnez un cours.");
        Optional<List<String>> fields = form("Nouvelle évaluation", new String[]{"Nom"},
                values -> notBlank(values.get(0)), "Le nom est obligatoire.");
        if (fields.isEmpty()) return;
        long id = db.insert("INSERT INTO evaluation(cours_id,nom) VALUES(?,?)",
                course, required(fields.get().get(0), "Le nom"));
        loadEvaluations();
        selectById(evaluations, id);
    }

    /** Enregistre les dossiers valides en préservant la racine des copies déjà importées. */
    private void configurePaths() throws Exception {
        long evaluationId = evaluationId();
        Map<String, Object> evaluation = currentEvaluation().data();
        boolean copiesLocked = !db.query("SELECT id FROM copie WHERE evaluation_id=?", evaluationId).isEmpty();
        Optional<DirectoryDialog.Paths> paths = DirectoryDialog.show(stage, text(evaluation, "copies"),
                text(evaluation, "exports"), copiesLocked);
        if (paths.isEmpty()) return;
        String copiesPath = paths.get().copies();
        String exportPath = paths.get().exports();
        if (!copiesLocked && !copiesPath.isEmpty()) CopyFileService.directory(copiesPath);
        if (!exportPath.isEmpty()) CopyFileService.directory(exportPath);
        if (!copiesPath.equals(text(evaluation, "copies")) && copiesLocked) {
            throw new IllegalArgumentException("Le dossier des copies ne peut pas être modifié après leur importation.");
        }
        db.execute("UPDATE evaluation SET copies=?,exports=? WHERE id=?", copiesPath, exportPath, evaluationId);
        loadEvaluations();
    }

}
