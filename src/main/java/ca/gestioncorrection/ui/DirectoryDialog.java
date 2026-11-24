package ca.gestioncorrection.ui;

import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Affiche et valide les dossiers de copies et d'exports d'une évaluation.
 *
 * @author AI
 */
public class DirectoryDialog {
    @FXML private TextField source;
    @FXML private TextField destination;
    @FXML private Button chooseSourceButton;
    @FXML private Label pathsHint;
    private Stage owner;

    /** Crée le contrôleur instancié par le chargeur FXML. */
    public DirectoryDialog() {}

    /** Contient les chemins confirmés dans le dialogue, éventuellement vides. */
    record Paths(String copies, String exports) {}

    /** Charge le dialogue et bloque la modification de la source lorsque des copies sont importées. */
    static Optional<Paths> show(Stage owner, String copies, String exports, boolean copiesLocked) throws IOException {
        FXMLLoader loader = new FXMLLoader(DirectoryDialog.class.getResource("directory-dialog.fxml"));
        Parent content = loader.load();
        DirectoryDialog controller = loader.getController();
        controller.owner = owner;
        controller.source.setText(copies);
        controller.destination.setText(exports);
        controller.source.setDisable(copiesLocked);
        controller.chooseSourceButton.setDisable(copiesLocked);
        controller.pathsHint.setText(copiesLocked
                ? "Le dossier des copies ne peut plus être modifié après leur importation. Le dossier d'export doit exister."
                : "Les dossiers indiqués doivent exister ; laissez un champ vide pour le configurer plus tard.");
        if (copiesLocked) controller.source.setTooltip(new Tooltip("Des copies ont déjà été importées."));

        Dialog<Paths> dialog = new Dialog<>();
        UiStyles.apply(dialog.getDialogPane());
        dialog.initOwner(owner);
        dialog.setTitle("Dossiers de l'évaluation");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(Bindings.createBooleanBinding(
                () -> (!copiesLocked && !validDirectory(controller.source.getText()))
                        || !validDirectory(controller.destination.getText()),
                controller.source.textProperty(), controller.destination.textProperty()));
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? new Paths(controller.source.getText().trim(), controller.destination.getText().trim()) : null);
        return dialog.showAndWait();
    }

    /** Accepte un chemin vide ou un dossier existant qui n'est pas un lien symbolique. */
    private static boolean validDirectory(String value) {
        if (value.isBlank()) return true;
        try {
            return Files.isDirectory(Path.of(value.trim()), LinkOption.NOFOLLOW_LINKS);
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    /** Ouvre le sélecteur pour le dossier de copies. */
    @FXML private void chooseSource() { chooseDirectory(source); }
    /** Ouvre le sélecteur pour le dossier d'exports. */
    @FXML private void chooseDestination() { chooseDirectory(destination); }

    /** Remplace le champ seulement si l'utilisateur choisit un dossier. */
    private void chooseDirectory(TextField field) {
        var selected = new DirectoryChooser().showDialog(owner);
        if (selected != null) field.setText(selected.getAbsolutePath());
    }
}
