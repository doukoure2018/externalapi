package crg.api.external.util;


import crg.api.external.dto.reabo.ReabonnementRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class ReabonnementRequestNormalizer {
    // Mapping complet des alias d'offres
    private static final Map<String, String> OFFRE_ALIASES = new HashMap<>() {{
        // Format snake_case
        put("access", "ACCESS");
        put("access_plus", "ACCESS+");
        put("evasion", "EVASION");
        put("tout_canal", "TOUT CANAL+");
        put("tout_canal_plus", "TOUT CANAL+");

        // Format avec tiret
        put("access-plus", "ACCESS+");
        put("tout-canal", "TOUT CANAL+");
        put("tout-canal-plus", "TOUT CANAL+");

        // Format lowercase
        put("access+", "ACCESS+");
        put("tout canal+", "TOUT CANAL+");
        put("tout canal", "TOUT CANAL+");

        // Format original (pour être sûr)
        put("ACCESS", "ACCESS");
        put("ACCESS+", "ACCESS+");
        put("EVASION", "EVASION");
        put("TOUT CANAL+", "TOUT CANAL+");
    }};

    // Mapping des alias d'options
    private static final Map<String, String> OPTION_ALIASES = new HashMap<>() {{
        // Options vides
        put("", "SANS_OPTION");
        put("none", "SANS_OPTION");
        put("aucune", "SANS_OPTION");
        put("sans_option", "SANS_OPTION");
        put("sans option", "SANS_OPTION");

        // English
        put("english", "ENGLISH");
        put("english_channels", "ENGLISH");
        put("english-channels", "ENGLISH");
        put("english channels", "ENGLISH");

        // Charme
        put("charme", "CHARME");
        put("chr", "CHARME");

        // PVR
        put("pvr", "PVR");
        put("pvrdd", "PVR");

        // 2 Ecrans
        put("2ecrans", "2ECRANS");
        put("2_ecrans", "2ECRANS");
        put("2-ecrans", "2ECRANS");
        put("2 ecrans", "2ECRANS");
        put("2ecdd", "2ECRANS");

        // Netflix
        put("netflix1", "NETFLIX1");
        put("netflix_1", "NETFLIX1");
        put("netflix-1", "NETFLIX1");
        put("netflix 1", "NETFLIX1");
        put("nfx1smdd", "NETFLIX1");

        put("netflix2", "NETFLIX2");
        put("netflix_2", "NETFLIX2");
        put("netflix-2", "NETFLIX2");
        put("netflix 2", "NETFLIX2");
        put("nfx2smdd", "NETFLIX2");

        put("netflix4", "NETFLIX4");
        put("netflix_4", "NETFLIX4");
        put("netflix-4", "NETFLIX4");
        put("netflix 4", "NETFLIX4");
        put("nfx4smdd", "NETFLIX4");
    }};

    // Mapping des durées
    private static final Map<String, String> DUREE_ALIASES = new HashMap<>() {{
        put("1", "1 mois");
        put("3", "3 mois");
        put("6", "6 mois");
        put("12", "12 mois");
        put("1_month", "1 mois");
        put("3_months", "3 mois");
        put("6_months", "6 mois");
        put("12_months", "12 mois");
        put("1 month", "1 mois");
        put("3 months", "3 mois");
        put("6 months", "6 mois");
        put("12 months", "12 mois");
        put("1 mois", "1 mois");
        put("3 mois", "3 mois");
        put("6 mois", "6 mois");
        put("12 mois", "12 mois");
    }};

    /**
     * Normalise une requête de réabonnement
     */
    public ReabonnementRequest normalize(ReabonnementRequest request) {
        if (request == null) {
            return null;
        }

        log.info("🔄 Normalisation de la requête - Avant: Offre={}, Option={}, Durée={}",
                request.getOffre(), request.getOption(), request.getDuree());

        // Normaliser l'offre
        if (request.getOffre() != null) {
            String offreNormalized = normalizeOffre(request.getOffre());
            request.setOffre(offreNormalized);
        }

        // Normaliser l'option
        if (request.getOption() != null) {
            String optionNormalized = normalizeOption(request.getOption());
            request.setOption(optionNormalized);
        } else {
            request.setOption("SANS_OPTION");
        }

        // Normaliser la durée
        if (request.getDuree() != null) {
            String dureeNormalized = normalizeDuree(request.getDuree());
            request.setDuree(dureeNormalized);
        }

        log.info("✅ Normalisation terminée - Après: Offre={}, Option={}, Durée={}",
                request.getOffre(), request.getOption(), request.getDuree());

        return request;
    }

    /**
     * Normalise le nom de l'offre
     */
    private String normalizeOffre(String offre) {
        if (offre == null || offre.trim().isEmpty()) {
            return "ACCESS"; // Valeur par défaut
        }

        // Nettoyer et convertir en lowercase
        String cleaned = offre.trim().toLowerCase();

        // Chercher dans les alias
        String normalized = OFFRE_ALIASES.get(cleaned);

        if (normalized != null) {
            return normalized;
        }

        // Si pas trouvé, essayer avec underscores
        cleaned = cleaned.replace(" ", "_").replace("-", "_");
        normalized = OFFRE_ALIASES.get(cleaned);

        if (normalized != null) {
            return normalized;
        }

        // Si toujours pas trouvé, retourner en uppercase
        log.warn("⚠️ Offre '{}' non reconnue, utilisation telle quelle en majuscules", offre);
        return offre.toUpperCase();
    }

    /**
     * Normalise le nom de l'option
     */
    private String normalizeOption(String option) {
        if (option == null || option.trim().isEmpty()) {
            return "SANS_OPTION";
        }

        // Nettoyer et convertir en lowercase
        String cleaned = option.trim().toLowerCase();

        // Chercher dans les alias
        String normalized = OPTION_ALIASES.get(cleaned);

        if (normalized != null) {
            return normalized;
        }

        // Si pas trouvé, essayer avec underscores
        cleaned = cleaned.replace(" ", "_").replace("-", "_");
        normalized = OPTION_ALIASES.get(cleaned);

        if (normalized != null) {
            return normalized;
        }

        // Si toujours pas trouvé, retourner en uppercase
        log.warn("⚠️ Option '{}' non reconnue, utilisation telle quelle en majuscules", option);
        return option.toUpperCase();
    }

    /**
     * Normalise la durée
     */
    private String normalizeDuree(String duree) {
        if (duree == null || duree.trim().isEmpty()) {
            return "1 mois"; // Valeur par défaut
        }

        // Nettoyer
        String cleaned = duree.trim().toLowerCase();

        // Chercher dans les alias
        String normalized = DUREE_ALIASES.get(cleaned);

        if (normalized != null) {
            return normalized;
        }

        // Si c'est juste un nombre, ajouter "mois"
        if (cleaned.matches("\\d+")) {
            return cleaned + " mois";
        }

        // Retourner tel quel si non reconnu
        log.warn("⚠️ Durée '{}' non reconnue, utilisation telle quelle", duree);
        return duree;
    }

    /**
     * Valide que la combinaison offre/option est valide
     */
    public boolean isValidCombination(String offre, String option) {
        // ENGLISH n'est disponible que pour EVASION et ACCESS+
        if ("ENGLISH".equals(option)) {
            return "EVASION".equals(offre) || "ACCESS+".equals(offre);
        }

        // CHARME n'est pas disponible pour ACCESS+
        if ("CHARME".equals(option) && "ACCESS+".equals(offre)) {
            log.warn("⚠️ L'option CHARME n'est pas disponible pour ACCESS+");
            return false;
        }

        return true;
    }
}
