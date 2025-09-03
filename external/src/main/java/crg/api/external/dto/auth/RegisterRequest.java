package crg.api.external.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;


@Data
@Schema(description = "Requête d'inscription")
public class RegisterRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50)
    @Schema(
            description = "Nom d'utilisateur",
            example = "john_doe",
            required = true
    )
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    @Schema(
            description = "Adresse email",
            example = "john.doe@example.com",
            required = true,
            format = "email"
    )
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    @Schema(
            description = "Mot de passe",
            example = "SecurePass123!",
            required = true,
            format = "password"
    )
    private String password;

    @Schema(
            description = "Nom complet",
            example = "John Doe",
            required = false
    )
    private String fullName;
}