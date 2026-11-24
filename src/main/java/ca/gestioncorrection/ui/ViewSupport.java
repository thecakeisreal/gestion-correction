package ca.gestioncorrection.ui;

import ca.gestioncorrection.persistence.Database;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Fournit aux contrôleurs les dialogues, la conversion des données et la gestion visible des erreurs.
 *
 * @author AI
 */
abstract class ViewSupport {
    /** Base locale partagée par les contrôleurs. */
    protected Database db;
    /** Fenêtre propriétaire des dialogues. */
    protected Stage stage;
    /** Coordinateur des sélections et des onglets. */
    protected MainController main;

    /** Représente une action d'interface susceptible de produire une exception. */
    @FunctionalInterface
    protected interface Action {
        /** Exécute l'action afin que l'appelant puisse afficher toute erreur. */
        void run() throws Exception;
    }

    /** Partage le contrôleur principal, la base et la fenêtre avec un onglet. */
    void configure(MainController main, Database db, Stage stage) {
        this.main = main;
        this.db = db;
        this.stage = stage;
    }

    /**
     * Exécute une action et affiche explicitement toute erreur à l'utilisateur.
     * @param action opération à exécuter
     */
    protected void safe(Action action) {
        try {
            action.run();
        } catch (Exception exception) {
            exception.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR);
            UiStyles.apply(alert.getDialogPane());
            alert.initOwner(stage);
            alert.setTitle("Erreur");
            alert.setHeaderText("L'opération n'a pas pu être terminée.");
            alert.setContentText(exception.getMessage() == null ? exception.toString() : exception.getMessage());
            alert.showAndWait();
        }
    }

    /**
     * Demande confirmation et considère la fermeture du dialogue comme une annulation.
     * @param title titre du dialogue
     * @param text description de l'opération
     * @return {@code true} si l'utilisateur confirme
     */
    protected boolean confirm(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        UiStyles.apply(alert.getDialogPane());
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * Affiche un message d'information dans un dialogue rattaché à la fenêtre principale.
     * @param text message à présenter
     */
    protected void info(String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        UiStyles.apply(alert.getDialogPane());
        alert.initOwner(stage);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    /**
     * Construit un formulaire dont le bouton de confirmation suit la validation des champs.
     * @param title titre du formulaire
     * @param labels libellés des champs, dans l'ordre
     * @param valid prédicat appliqué aux valeurs courantes dans le même ordre
     * @param hint indication affichée sur les valeurs attendues
     * @param values valeurs initiales facultatives, dans l'ordre des champs
     * @return valeurs confirmées, ou résultat vide si le dialogue est annulé
     * @throws IOException si le modèle FXML du formulaire ne peut pas être chargé
     */
    protected Optional<List<String>> form(String title, String[] labels, Predicate<List<String>> valid,
                                           String hint, String... values) throws IOException {
        Dialog<List<String>> dialog = new Dialog<>();
        UiStyles.apply(dialog.getDialogPane());
        dialog.initOwner(stage);
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        GridPane grid = FXMLLoader.load(ViewSupport.class.getResource("form-fields.fxml"));
        List<TextInputControl> fields = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            TextInputControl field;
            if (labels[i].contains("Texte") || labels[i].contains("Indicateur")) {
                TextArea area = new TextArea();
                area.setPrefRowCount(3);
                area.setWrapText(true);
                field = area;
            } else {
                field = new TextField();
            }
            if (i < values.length) field.setText(values[i]);
            field.setPrefWidth(400);
            fields.add(field);
            grid.add(new Label(labels[i]), 0, i);
            grid.add(field, 1, i);
        }
        Label validationHint = new Label(hint);
        validationHint.getStyleClass().add("validation-hint");
        validationHint.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(4, grid, validationHint));
        Observable[] dependencies = fields.stream().map(TextInputControl::textProperty).toArray(Observable[]::new);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(Bindings.createBooleanBinding(
                () -> !valid.test(fields.stream().map(TextInputControl::getText).toList()), dependencies));
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? fields.stream().map(TextInputControl::getText).toList() : null);
        return dialog.showAndWait();
    }

    /**
     * Convertit les résultats d'une requête en lignes identifiables pour les contrôles JavaFX.
     * @param sql requête retournant une colonne {@code id}
     * @param label fonction construisant le texte affiché pour chaque ligne
     * @param args paramètres SQL
     * @return lignes prêtes à afficher
     * @throws Exception si la requête échoue
     */
    protected List<Row> rows(String sql, Function<Map<String, Object>, String> label, Object... args) throws Exception {
        return db.query(sql, args).stream()
                .map(row -> new Row(number(row, "id"), label.apply(row), row))
                .toList();
    }

    /**
     * Remplace les lignes d'une liste et rétablit si possible la sélection par identifiant.
     * @param list liste à mettre à jour
     * @param rows nouvelles lignes
     */
    protected static void setRows(ListView<Row> list, List<Row> rows) {
        Row selected = list.getSelectionModel().getSelectedItem();
        list.getItems().setAll(rows);
        if (selected != null) {
            rows.stream().filter(row -> row.id() == selected.id())
                    .findFirst().ifPresent(row -> list.getSelectionModel().select(row));
        }
    }

    /**
     * Exige une ligne sélectionnée et retourne son identifiant.
     * @param row ligne sélectionnée, éventuellement nulle
     * @param message message d'erreur si la ligne manque
     * @return identifiant de la ligne
     * @throws IllegalArgumentException si aucune ligne n'est sélectionnée
     */
    protected static long id(Row row, String message) {
        if (row == null) throw new IllegalArgumentException(message);
        return row.id();
    }

    /**
     * Lit une colonne textuelle, en représentant une valeur absente ou nulle par une chaîne vide.
     * @param row données de la ligne
     * @param key nom de la colonne
     * @return représentation textuelle ou chaîne vide
     */
    protected static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    /**
     * Convertit une colonne numérique requise en entier.
     * @param row données de la ligne
     * @param key nom de la colonne numérique
     * @return valeur convertie en {@code long}
     */
    protected static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /**
     * Analyse un nombre fini avec virgule ou point, en imposant au besoin une valeur positive ou nulle.
     * @param value texte à analyser
     * @param nonnegative indique si les valeurs négatives sont interdites
     * @return nombre analysé
     * @throws IllegalArgumentException si la saisie est invalide
     */
    protected static double decimal(String value, boolean nonnegative) {
        double result;
        try {
            result = Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Nombre invalide : " + value);
        }
        if (!Double.isFinite(result) || (nonnegative && result < 0))
            throw new IllegalArgumentException("Le nombre doit être fini" + (nonnegative ? " et positif ou nul." : "."));
        return result;
    }

    /**
     * Indique si une saisie numérique respecte les règles de {@link #decimal(String, boolean)}.
     * @param value texte à vérifier
     * @param nonnegative indique si les valeurs négatives sont interdites
     * @return {@code true} si la saisie est valide
     */
    protected static boolean validDecimal(String value, boolean nonnegative) {
        try {
            decimal(value, nonnegative);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Indique si un champ contient au moins un caractère non blanc.
     * @param value texte à vérifier, éventuellement nul
     * @return {@code true} si le texte n'est pas vide ou blanc
     */
    protected static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Retourne une valeur obligatoire sans espaces périphériques ou signale son absence.
     * @param value texte à vérifier
     * @param field nom du champ à utiliser dans le message d'erreur
     * @return valeur sans espaces périphériques
     * @throws IllegalArgumentException si la valeur est vide ou blanche
     */
    protected static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " est obligatoire.");
        return value.trim();
    }
}
