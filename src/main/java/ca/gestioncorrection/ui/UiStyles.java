package ca.gestioncorrection.ui;

import javafx.scene.control.DialogPane;

/**
 * Applique la feuille de style commune aux dialogues créés hors de la scène principale.
 *
 * @author AI
 */
final class UiStyles {
    /** Empêche l'instanciation de cette classe utilitaire. */
    private UiStyles() {}

    /** Ajoute la feuille de style de l'application au panneau du dialogue. */
    static void apply(DialogPane pane) {
        pane.getStylesheets().add(UiStyles.class.getResource("application.css").toExternalForm());
    }
}
