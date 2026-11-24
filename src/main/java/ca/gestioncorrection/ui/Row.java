package ca.gestioncorrection.ui;

import java.util.Map;

/**
 * Associe un identifiant et des données SQLite au libellé affiché dans les sélections.
 *
 * @param id identifiant stable de la ligne affichée
 * @param label texte visible dans l'interface
 * @param data colonnes utilisées par les contrôleurs
 * @author AI
 */
record Row(long id, String label, Map<String, Object> data) {
    /** Retourne le libellé utilisé par les contrôles JavaFX. */
    @Override
    public String toString() {
        return label;
    }
}
