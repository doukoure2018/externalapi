package crg.api.external.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(NON_DEFAULT)
public class TokenResponse {
    private String token;
    private Long status;
    private Long expiresIn;

    // Nouveaux champs pour la gestion mobile
    private String refreshToken;
    private String tokenType = "Bearer";
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;

    // Session mobile info
    private boolean mobileSessionActive;
    private LocalDateTime mobileSessionExpiry;

    // Constructeur de compatibilité
    public TokenResponse(String token, Long expiresIn) {
        this.token = token;
        this.expiresIn = expiresIn;
        this.tokenType = "Bearer";
        this.issuedAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusSeconds(expiresIn);
    }

    public boolean isSuccess() {
        return status != null && status == 200 && token != null && !token.isEmpty();
    }
}
