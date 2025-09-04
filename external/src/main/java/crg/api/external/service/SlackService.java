package crg.api.external.service;

import crg.api.external.dto.reabo.ReabonnementRequest;

public interface SlackService {
    void sendSearchingDecoder(String decoderNumber);
    void sendDecoderFound(String decoderNumber, boolean found);
    void sendReabonnementProgress(String step, String details);
    void sendReabonnementSuccess(ReabonnementRequest request, String montant, String reference);
    void sendValidationStatus(String status, String details);
    void sendCustomMessage(String message, String emoji);

    void sendReabonnementError(ReabonnementRequest request, String errorType, String errorDetails);
    void sendMessage(String message);
}
