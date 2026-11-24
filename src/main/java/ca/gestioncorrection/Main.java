package ca.gestioncorrection;

import ca.gestioncorrection.persistence.Database;
import ca.gestioncorrection.persistence.EncryptedDatabase;
import ca.gestioncorrection.ui.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Path;

/**
 * Démarre l'application JavaFX, ouvre la base locale et charge la vue principale.
 *
 * @author AI
 */
public class Main extends Application {
    private Database database;

    /** Crée l'application instanciée par JavaFX. */
    public Main() {}

    /**
     * Lance le cycle de vie JavaFX avec les arguments de la commande.
     * @param args arguments transmis à JavaFX
     */
    public static void main(String[] args) {
        launch(args);
    }

    /** Initialise les dépendances et affiche la fenêtre principale. */
    @Override
    public void start(Stage stage) throws Exception {
        database = EncryptedDatabase.open(Path.of(System.getProperty("user.home"), ".gestion-correction"),
                Path.of(".env"));
        FXMLLoader loader = new FXMLLoader(Main.class.getResource("/ca/gestioncorrection/ui/main-view.fxml"));
        Parent root = loader.load();
        MainController controller = loader.getController();
        controller.configure(database, stage);

        stage.setTitle("Gestion de correction");
        stage.setScene(new Scene(root, 1400, 800));
        stage.setMinWidth(1000);
        stage.setMinHeight(650);
        stage.show();
    }

    /** Ferme la connexion SQLite lorsque l'application s'arrête. */
    @Override
    public void stop() throws Exception {
        if (database != null) database.close();
    }
}
