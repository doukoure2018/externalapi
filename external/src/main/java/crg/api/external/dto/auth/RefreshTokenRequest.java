package crg.api.external.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Requête de rafraîchissement du token")
public class RefreshTokenRequest {

    @NotBlank(message = "Refresh token is required")
    @Schema(
            description = "Refresh token JWT",
            example = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJleHRlcm5h22bC1hcGkiLCJpYXQiOjE3MzI...",
            required = true
    )
    private String refreshToken;
}