package crg.api.external.service.impl;

import crg.api.external.dto.reabo.ReabonnementRequest;
import crg.api.external.service.SlackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
@Service
@Slf4j
public class SlackServiceImpl implements SlackService {

    @Value("${slack.credential}")
    private String slackWebhookUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @Override
    public void sendSearchingDecoder(String decoderNumber) {
        String message = String.format(
                "🔍 *RECHERCHE DÉCODEUR*\n" +
                        "```\n" +
                        "📡 Numéro: %s\n" +
                        "🕐 Heure: %s\n" +
                        "```",
                decoderNumber,
                LocalDateTime.now().format(dateFormatter)
        );

        sendSlackMessageAsync(message);
    }

    @Override
    public void sendDecoderFound(String decoderNumber, boolean found) {
        String emoji = found ? "✅" : "❌";
        String status = found ? "TROUVÉ" : "INTROUVABLE";

        String message = String.format(
                "%s *DÉCODEUR %s*\n" +
                        "```\n" +
                        "📡 Numéro: %s\n" +
                        "📊 Statut: %s\n" +
                        "🕐 Heure: %s\n" +
                        "```",
                emoji,
                status,
                decoderNumber,
                found ? "Abonné actif" : "Abonné introuvable",
                LocalDateTime.now().format(dateFormatter)
        );

        sendSlackMessageAsync(message);
    }

    @Override
    public void sendReabonnementProgress(String step, String details) {
        String message = String.format(
                "⚙️ *PROGRESSION*\n" +
                        "```\n" +
                        "📌 Étape: %s\n" +
                        "📝 Détails: %s\n" +
                        "🕐 Heure: %s\n" +
                        "```",
                step,
                details,
                LocalDateTime.now().format(dateFormatter)
        );

        sendSlackMessageAsync(message);
    }

    @Override
    public void sendValidationStatus(String status, String details) {
        String emoji = status.equalsIgnoreCase("SUCCESS") ? "✅" :
                status.equalsIgnoreCase("ERROR") ? "❌" : "⏳";

        String message = String.format(
                "%s *VALIDATION: %s*\n" +
                        "```\n" +
                        "📝 Détails: %s\n" +
                        "🕐 Heure: %s\n" +
                        "```",
                emoji,
                status.toUpperCase(),
                details,
                LocalDateTime.now().format(dateFormatter)
        );

        sendSlackMessageAsync(message);
    }

    @Override
    public void sendReabonnementSuccess(ReabonnementRequest request,
                                        String montant, String reference) {
        String message = String.format(
                "🎉 *RÉABONNEMENT RÉUSSI*\n" +
                        "```\n" +
                        "✨ SUCCÈS COMPLET ✨\n" +
                        "────────────────────\n" +

                        "📡 Décodeur: %s\n" +
                        "📦 Offre: %s %s\n" +
                        "💰 Montant: %s\n" +
                        "🎯 Option: %s\n" +
                        "📋 Référence: %s\n" +
                        "🕐 Complété à: %s\n" +
                        "────────────────────\n" +
                        "✅ Transaction validée avec succès!\n" +
                        "```",

                request.getNumAbonne(),
                request.getOffre(),
                request.getDuree(),
                montant != null && !montant.equals("N/A") ? montant : "Non spécifié",
                request.getOption() != null ? request.getOption() : "SANS_OPTION",
                reference != null ? reference : "N/A",
                LocalDateTime.now().format(dateFormatter)
        );

        sendSlackMessageAsync(message);
    }

    @Override
    public void sendReabonnementError(ReabonnementRequest request,
                                      String errorType, String errorDetails) {
        // Déterminer le type d'erreur et l'emoji approprié
        String errorEmoji = "❌";
        String errorCategory = "ERREUR";

        if (errorDetails != null) {
            if (errorDetails.contains("SOLDE_INSUFFISANT") || errorDetails.contains("DTA-1009")) {
                errorEmoji = "💸";
                errorCategory = "SOLDE INSUFFISANT";
            } else if (errorDetails.contains("introuvable")) {
                errorEmoji = "🔍";
                errorCategory = "ABONNÉ INTROUVABLE";
            } else if (errorDetails.contains("timeout") || errorDetails.contains("Timeout")) {
                errorEmoji = "⏱️";
                errorCategory = "TIMEOUT";
            } else if (errorDetails.contains("Service temporairement indisponible")) {
                errorEmoji = "🔧";
                errorCategory = "SERVICE INDISPONIBLE";
            }
        }

        String message = String.format(
                "%s *ÉCHEC RÉABONNEMENT - %s*\n" +
                        "```\n" +
                        "⚠️ ERREUR DÉTECTÉE ⚠️\n" +
                        "────────────────────\n" +

                        "📡 Décodeur: %s\n" +
                        "📦 Offre tentée: %s %s\n" +
                        "🎯 Option: %s\n" +
                        "────────────────────\n" +
                        "🚨 Type d'erreur: %s\n" +
                        "📝 Détails: %s\n" +
                        "🕐 Heure: %s\n" +
                        "────────────────────\n" +
                        "Action requise: Vérifier et réessayer\n" +
                        "```",
                errorEmoji,
                errorCategory,
                request.getNumAbonne(),
                request.getOffre(),
                request.getDuree(),
                request.getOption() != null ? request.getOption() : "SANS_OPTION",
                errorType != null ? errorType : "ERREUR_INCONNUE",
                errorDetails != null ? errorDetails : "Aucun détail disponible",
                LocalDateTime.now().format(dateFormatter)
        );

        sendSlackMessageAsync(message);
    }

    @Override
    public void sendCustomMessage(String message, String emoji) {
        String formattedMessage = String.format(
                "%s *NOTIFICATION*\n```\n%s\n🕐 %s\n```",
                emoji != null ? emoji : "ℹ️",
                message,
                LocalDateTime.now().format(dateFormatter)
        );

        sendSlackMessageAsync(formattedMessage);
    }

    @Override
    public void sendMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }

        // Envoyer directement sans formatage supplémentaire
        sendSlackMessageAsync(message);
    }

    private void sendSlackMessageAsync(String message) {
        // Envoyer de manière asynchrone pour ne pas bloquer le processus principal
        CompletableFuture.runAsync(() -> {
            try {
                sendSlackMessage(message);
            } catch (Exception e) {
                log.error("Erreur lors de l'envoi du message Slack: {}", e.getMessage());
            }
        });
    }

    private void sendSlackMessage(String message) {
        if (slackWebhookUrl == null || slackWebhookUrl.isEmpty()) {
            log.warn("URL Slack non configurée, message non envoyé");
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> slackMessage = new HashMap<>();
            slackMessage.put("text", message);

            // Optionnel: Ajouter des blocks pour un formatage plus riche
            Map<String, Object> block = new HashMap<>();
            block.put("type", "section");
            Map<String, Object> text = new HashMap<>();
            text.put("type", "mrkdwn");
            text.put("text", message);
            block.put("text", text);

            slackMessage.put("blocks", new Object[]{block});

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(slackMessage, headers);

            restTemplate.postForEntity(slackWebhookUrl, request, String.class);

            log.debug("Message Slack envoyé avec succès");
        } catch (Exception e) {
            log.error("Erreur lors de l'envoi du message Slack: {}", e.getMessage());
        }
    }

    // Méthode utilitaire pour envoyer un récapitulatif quotidien
    public void sendDailySummary(int totalReabos, int success, int failures, double totalAmount) {
        String message = String.format(
                "📊 *RAPPORT QUOTIDIEN - RÉABONNEMENTS*\n" +
                        "```\n" +
                        "📅 Date: %s\n" +
                        "────────────────────\n" +
                        "📈 Total tentatives: %d\n" +
                        "✅ Réussis: %d (%.1f%%)\n" +
                        "❌ Échoués: %d (%.1f%%)\n" +
                        "💰 Montant total: %.0f GNF\n" +
                        "────────────────────\n" +
                        "```",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                totalReabos,
                success,
                totalReabos > 0 ? (success * 100.0 / totalReabos) : 0,
                failures,
                totalReabos > 0 ? (failures * 100.0 / totalReabos) : 0,
                totalAmount
        );

        sendSlackMessageAsync(message);
    }
}