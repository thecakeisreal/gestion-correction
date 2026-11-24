package ca.gestioncorrection.ui;

import ca.gestioncorrection.persistence.Database;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vérifie le chargement FXML, les états des commandes et la synchronisation entre onglets.
 *
 * @author AI
 */
@Tag("ui")
class FxmlWorkflowTest {
    @TempDir Path temp;

    /** Exécute un parcours de correction et de validation sur le thread JavaFX. */
    @Test
    void fxmlViewsCoordinateCopyAndGradingSelections() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        assertTrue(started.await(15, TimeUnit.SECONDS));
        FutureTask<Void> workflow = new FutureTask<>(() -> {
            try (Database database = new Database(temp.resolve("test.db"), "0123456789abcdef".repeat(4))) {
                long session = database.insert("INSERT INTO session(nom) VALUES(?)", "Session");
                long course = database.insert("INSERT INTO cours(session_id,nom) VALUES(?,?)", session, "Cours");
                long evaluation = database.insert("INSERT INTO evaluation(cours_id,nom,copies) VALUES(?,?,?)",
                        course, "Examen", temp.toString());
                long firstCopy = database.insert("INSERT INTO copie(evaluation_id,dossier,original,nom,prenom) VALUES(?,?,?,?,?)",
                        evaluation, "a", "a", "Alpha", "A");
                long secondCopy = database.insert("INSERT INTO copie(evaluation_id,dossier,original,nom,prenom) VALUES(?,?,?,?,?)",
                        evaluation, "b", "b", "Beta", "B");
                database.execute("UPDATE copie SET anonyme=1 WHERE id=?", secondCopy);
                long level = database.insert("INSERT INTO niveau(evaluation_id,nom,minimum,maximum) VALUES(?,?,?,?)",
                        evaluation, "Bien", 1, 10);
                long criterion = database.insert("INSERT INTO critere(evaluation_id,region,nom,indicateur,ponderation) VALUES(?,?,?,?,?)",
                        evaluation, "Région", "Critère", "Indicateur", 5);
                long block = database.insert("INSERT INTO bloc(critere_id,niveau_id,texte) VALUES(?,?,?)",
                        criterion, level, "Description");
                long comment = database.insert("INSERT INTO commentaire(bloc_id,texte,perte) VALUES(?,?,?)",
                        block, "Commentaire", 2);

                FXMLLoader loader = new FXMLLoader(getClass().getResource("main-view.fxml"));
                Parent root = loader.load();
                Stage stage = new Stage();
                stage.setScene(new Scene(root, 1400, 800));
                ((MainController) loader.getController()).configure(database, stage);
                stage.show();
                try {
                    TabPane tabs = (TabPane) root.lookup(".tab-pane");
                    Parent grid = (Parent) tabs.getTabs().get(0).getContent();
                    Parent copies = (Parent) tabs.getTabs().get(1).getContent();
                    Parent grading = (Parent) tabs.getTabs().get(2).getContent();
                    assertTrue(root.lookup("#courses").isDisabled());
                    assertTrue(root.lookup("#addCourseButton").isDisabled());
                    assertTrue(root.lookup("#evaluations").isDisabled());
                    assertTrue(root.lookup("#addEvaluationButton").isDisabled());
                    assertTrue(root.lookup("#editPathsButton").isDisabled());
                    assertTrue(grid.lookup("#addCriterionButton").isDisabled());
                    assertTrue(grid.lookup("#criteria").isDisabled());
                    assertTrue(copies.lookup("#scanButton").isDisabled());
                    assertTrue(grading.lookup("#gradingCopy").isDisabled());
                    assertFalse(root.lookup("#addSessionButton").isDisabled());

                    ((ComboBox<?>) root.lookup("#sessions")).getSelectionModel().select(0);
                    assertFalse(root.lookup("#addCourseButton").isDisabled());
                    assertTrue(root.lookup("#addEvaluationButton").isDisabled());
                    ((ComboBox<?>) root.lookup("#courses")).getSelectionModel().select(0);
                    assertFalse(root.lookup("#addEvaluationButton").isDisabled());
                    ((ComboBox<?>) root.lookup("#evaluations")).getSelectionModel().select(0);
                    assertFalse(grid.lookup("#addCriterionButton").isDisabled());
                    assertTrue(grid.lookup("#modifyCriterionButton").isDisabled());
                    assertTrue(grid.lookup("#addBlockButton").isDisabled());
                    assertFalse(copies.lookup("#scanButton").isDisabled());
                    assertTrue(copies.lookup("#editStudentButton").isDisabled());
                    assertTrue(grading.lookup("#exportCopyButton").isDisabled());
                    assertTrue(grading.lookup("#exportAllButton").isDisabled());
                    ((ListView<?>) grid.lookup("#criteria")).getSelectionModel().select(0);
                    assertFalse(grid.lookup("#modifyCriterionButton").isDisabled());
                    assertTrue(grid.lookup("#addBlockButton").isDisabled());
                    ((ListView<?>) grid.lookup("#levels")).getSelectionModel().select(0);
                    assertFalse(grid.lookup("#addBlockButton").isDisabled());
                    assertTrue(grid.lookup("#modifyBlockButton").isDisabled());
                    assertEquals(1, ((ListView<?>) grid.lookup("#blocks")).getItems().size());

                    ((ListView<?>) copies.lookup("#copies")).getSelectionModel().select(0);
                    assertFalse(copies.lookup("#anonymiseButton").isDisabled());
                    assertTrue(copies.lookup("#deanonymiseButton").isDisabled());
                    assertFalse(copies.lookup("#anonymiseAllButton").isDisabled());
                    assertFalse(copies.lookup("#deanonymiseAllButton").isDisabled());
                    tabs.getSelectionModel().select(2);
                    ComboBox<?> copySelector = (ComboBox<?>) grading.lookup("#gradingCopy");
                    assertEquals(2, copySelector.getItems().size());
                    assertEquals(0, copySelector.getSelectionModel().getSelectedIndex());
                    ListView<?> criteria = (ListView<?>) grading.lookup("#gradingCriteria");
                    criteria.getSelectionModel().select(0);
                    ListView<?> blocks = (ListView<?>) grading.lookup("#gradingBlocks");
                    blocks.getSelectionModel().select(0);
                    assertFalse(grading.lookup("#selectBlockButton").isDisabled());
                    assertTrue(grading.lookup("#toggleCommentButton").isDisabled());
                    ((Button) grading.lookup("#selectBlockButton")).fire();
                    assertTrue(grading.lookup("#selectBlockButton").isDisabled());
                    assertEquals(1, database.query("SELECT * FROM choix WHERE copie_id=? AND bloc_id=?", firstCopy, block).size());

                    ListView<?> comments = (ListView<?>) grading.lookup("#comments");
                    comments.getSelectionModel().select(0);
                    assertFalse(grading.lookup("#toggleCommentButton").isDisabled());
                    assertTrue(grading.lookup("#overrideCommentButton").isDisabled());
                    ((Button) grading.lookup("#toggleCommentButton")).fire();
                    assertFalse(grading.lookup("#overrideCommentButton").isDisabled());
                    assertEquals(1, database.query("SELECT * FROM commentaire_choisi WHERE copie_id=? AND commentaire_id=?", firstCopy, comment).size());
                    assertEquals(2, ((ListView<?>) grading.lookup("#scores")).getItems().size());

                    copySelector.getSelectionModel().select(1);
                    assertEquals(secondCopy, ((Row) ((ListView<?>) copies.lookup("#copies")).getSelectionModel().getSelectedItem()).id());
                    assertTrue(copies.lookup("#anonymiseButton").isDisabled());
                    assertFalse(copies.lookup("#deanonymiseButton").isDisabled());
                    assertTrue(criteria.getItems().get(0).toString().startsWith("[ ]"));
                    assertTrue(grading.lookup("#toggleCommentButton").isDisabled());
                    assertTrue(grading.lookup("#overrideCommentButton").isDisabled());
                    copySelector.getSelectionModel().select(0);
                    assertTrue(criteria.getItems().get(0).toString().startsWith("[X]"));
                    assertTrue(comments.getItems().get(0).toString().startsWith("[X]"));

                    interactWithDialog(stage, (Button) root.lookup("#editPathsButton"), dialog -> {
                        assertTrue(dialog.lookup("#source").isDisabled());
                        assertTrue(dialog.lookup("#chooseSourceButton").isDisabled());
                        TextField destination = (TextField) dialog.lookup("#destination");
                        destination.setText(temp.resolve("inexistant").toString());
                        assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                        destination.setText(temp.toString());
                        assertFalse(dialog.lookupButton(ButtonType.OK).isDisabled());
                    });
                    assertEquals(temp.toString(), database.query("SELECT exports FROM evaluation WHERE id=?", evaluation).get(0).get("exports"));
                    assertEquals(firstCopy, ((Row) copySelector.getSelectionModel().getSelectedItem()).id());
                    assertFalse(grading.lookup("#exportCopyButton").isDisabled());
                    assertFalse(grading.lookup("#exportAllButton").isDisabled());
                    copySelector.getSelectionModel().select(1);
                    interactWithDialog(stage, (Button) copies.lookup("#editStudentButton"), dialog -> {
                        List<TextInputControl> inputs = formInputs(dialog);
                        inputs.get(0).setText("Alpha");
                        inputs.get(1).setText("A");
                    });
                    assertFalse(grading.lookup("#exportCopyButton").isDisabled());
                    assertTrue(grading.lookup("#exportAllButton").isDisabled());
                    interactWithDialog(stage, (Button) copies.lookup("#editStudentButton"), dialog ->
                            formInputs(dialog).get(0).setText("Nom/interdit"));
                    assertTrue(grading.lookup("#exportCopyButton").isDisabled());
                    assertTrue(grading.lookup("#exportAllButton").isDisabled());
                    interactWithDialog(stage, (Button) copies.lookup("#editStudentButton"), dialog -> {
                        List<TextInputControl> inputs = formInputs(dialog);
                        inputs.get(0).setText("Beta");
                        inputs.get(1).setText("B");
                    });
                    assertFalse(grading.lookup("#exportCopyButton").isDisabled());
                    assertFalse(grading.lookup("#exportAllButton").isDisabled());
                    interactWithDialog(stage, (Button) grid.lookup("#addLevelButton"), dialog -> {
                        List<TextInputControl> inputs = formInputs(dialog);
                        assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                        inputs.get(0).setText("Très bien");
                        inputs.get(1).setText("10");
                        inputs.get(2).setText("20");
                        assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                        inputs.get(1).setText("10,1");
                        assertFalse(dialog.lookupButton(ButtonType.OK).isDisabled());
                    });
                    interactWithDialog(stage, (Button) root.lookup("#addSessionButton"), dialog -> {
                        TextField name = (TextField) dialog.lookup(".text-field");
                        assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                        name.setText("   ");
                        assertTrue(dialog.lookupButton(ButtonType.OK).isDisabled());
                        name.setText("Nouvelle session");
                        assertFalse(dialog.lookupButton(ButtonType.OK).isDisabled());
                    });
                    assertEquals(1, database.query("SELECT id FROM session WHERE nom=?", "Nouvelle session").size());
                    assertTrue(grid.lookup("#addCriterionButton").isDisabled());
                    assertTrue(grading.lookup("#exportCopyButton").isDisabled());
                } finally {
                    stage.close();
                }

            }
            return null;
        });
        Platform.runLater(workflow);
        try {
            workflow.get(30, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
        }
    }

    /** Extrait les champs du formulaire dynamique dans leur ordre d'affichage. */
    private static List<TextInputControl> formInputs(DialogPane dialog) {
        GridPane fields = (GridPane) ((VBox) dialog.getContent()).getChildren().get(0);
        return fields.getChildren().stream().filter(TextInputControl.class::isInstance)
                .map(TextInputControl.class::cast).toList();
    }

    /** Remplit puis confirme un dialogue modal et propage les erreurs de l'interaction. */
    private static void interactWithDialog(Stage owner, Button button, Consumer<DialogPane> edit) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            DialogPane pane = null;
            try {
                Window window = Window.getWindows().stream()
                        .filter(candidate -> candidate.isShowing() && candidate != owner)
                        .findFirst().orElseThrow();
                pane = (DialogPane) window.getScene().getRoot();
                edit.accept(pane);
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                if (pane != null) ((Button) pane.lookupButton(failure.get() == null ? ButtonType.OK : ButtonType.CANCEL)).fire();
            }
        });
        button.fire();
        if (failure.get() != null) throw new AssertionError("Échec du dialogue", failure.get());
    }
}
