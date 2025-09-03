package crg.api.external.dto.auth;

import lombok.Builder;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Builder
@Schema(description = "Réponse d'authentification contenant les tokens JWT")
public class AuthResponse {

    @Schema(
            description = "Token d'accès JWT",
            example = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJleHRlcm5hbC1hcGkiLCJpYXQiOjE3MzI..."
    )
    private String accessToken;

    @Schema(
            description = "Refresh token JWT",
            example = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJleHRlcm5hbC1hcGkiLCJpYXQiOjE3MzI..."
    )
    private String refreshToken;

    @Schema(
            description = "Type de token",
            example = "Bearer",
            defaultValue = "Bearer"
    )
    private String tokenType;

    @Schema(
            description = "Durée de validité du token en secondes",
            example = "3600"
    )
    private Long expiresIn;

    @Schema(
            description = "Nom d'utilisateur",
            example = "john_doe"
    )
    private String username;
}