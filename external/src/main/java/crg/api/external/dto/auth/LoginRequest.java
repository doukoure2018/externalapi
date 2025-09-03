
// LoginRequest.java
package crg.api.external.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Requête de connexion")
public class LoginRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50)
    @Schema(
            description = "Nom d'utilisateur",
            example = "admin",
            required = true
    )
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 6)
    @Schema(
            description = "Mot de passe",
            example = "external0092",
            required = true,
            format = "password"
    )
    private String password;
}