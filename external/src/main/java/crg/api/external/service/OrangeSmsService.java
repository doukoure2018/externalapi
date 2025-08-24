package crg.api.external.service;

import crg.api.external.dto.TokenResponse;

public interface OrangeSmsService {
    public TokenResponse getOAuthToken();

    public void sendSms(String token, String recipient, String senderName, String message);

    int getSmsBalance(String token);
}
