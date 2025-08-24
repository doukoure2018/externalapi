package crg.api.external.service;

public interface SlackService {
    void sendLoginSuccess(String username);
    void sendSearchingDecoder(String decoderNumber);
    void sendDecoderFound(String decoderNumber, boolean found);
    void sendReabonnementProgress(String step, String details);

    void sendValidationStatus(String status, String details);
    void sendCustomMessage(String message, String emoji);
}
