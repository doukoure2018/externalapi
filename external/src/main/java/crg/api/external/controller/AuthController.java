package crg.api.external.controller;

import crg.api.external.dto.auth.AuthResponse;
import crg.api.external.dto.auth.LoginRequest;
import crg.api.external.dto.auth.RefreshTokenRequest;
import crg.api.external.dto.auth.RegisterRequest;
import crg.api.external.entity.User;
import crg.api.external.repository.UserRepository;
import crg.api.external.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Endpoints pour l'authentification et la gestion des tokens JWT")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Value("${jwt.expiration.minutes:60}")
    private Long expirationMinutes;

    @PostMapping("/register")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Créer un nouveau compte (Admin uniquement)",
            description = "Enregistre un nouvel utilisateur dans le système. **Nécessite le rôle ADMIN**",
            security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Compte créé avec succès",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Nom d'utilisateur ou email déjà utilisé",
                    content = @Content(schema = @Schema(implementation = String.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Accès refusé - Rôle ADMIN requis",
                    content = @Content(schema = @Schema(implementation = String.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Non authentifié",
                    content = @Content(schema = @Schema(implementation = String.class))
            )
    })
    @SecurityRequirement(name = "Bearer Authentication")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            return ResponseEntity.badRequest()
                    .body("Error: Username is already taken!");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            return ResponseEntity.badRequest()a
                    .body("Error: Email is already in use!");
        }

        Set<String> roles = new HashSet<>();
        roles.add("USER");

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .roles(roles)
                .enabled(true)
                .locked(false)
                .build();

        userRepository.save(user);

        String accessToken = jwtService.generateAccessToken(
                user.getUsername(),
                new ArrayList<>(user.getRoles())
        );
        String refreshToken = jwtService.generateRefreshToken(user.getUsername());

        return ResponseEntity.ok(AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(expirationMinutes * 60)
                .username(user.getUsername())
                .build());
    }

    @PostMapping("/login")
    @Operation(
            summary = "Se connecter",
            description = "Authentifie un utilisateur et retourne les tokens JWT (access et refresh)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Connexion réussie",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Identifiants invalides",
                    content = @Content(schema = @Schema(implementation = String.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Compte désactivé ou verrouillé",
                    content = @Content(schema = @Schema(implementation = String.class))
            )
    })
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        // Votre code existant...
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid username or password");
        }

        if (!user.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Account is disabled");
        }

        if (user.isLocked()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Account is locked");
        }

        String accessToken = jwtService.generateAccessToken(
                user.getUsername(),
                new ArrayList<>(user.getRoles())
        );
        String refreshToken = jwtService.generateRefreshToken(user.getUsername());

        return ResponseEntity.ok(AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(expirationMinutes * 60)
                .username(user.getUsername())
                .build());
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Rafraîchir le token",
            description = "Génère un nouveau token d'accès en utilisant le refresh token"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Token rafraîchi avec succès",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Refresh token invalide ou expiré",
                    content = @Content(schema = @Schema(implementation = String.class))
            )
    })
    public ResponseEntity<?> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        // Votre code existant...
        try {
            String refreshToken = request.getRefreshToken();

            if (!jwtService.isRefreshToken(refreshToken)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("Invalid refresh token");
            }

            String username = jwtService.extractUsername(refreshToken);
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new BadCredentialsException("User not found"));

            String newAccessToken = jwtService.generateAccessToken(
                    user.getUsername(),
                    new ArrayList<>(user.getRoles())
            );
            String newRefreshToken = jwtService.generateRefreshToken(user.getUsername());

            return ResponseEntity.ok(AuthResponse.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(newRefreshToken)
                    .tokenType("Bearer")
                    .expiresIn(expirationMinutes * 60)
                    .username(user.getUsername())
                    .build());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Invalid refresh token: " + e.getMessage());
        }
    }

    @GetMapping("/validate")
    @Operation(
            summary = "Valider un token",
            description = "Vérifie la validité d'un token JWT",
            security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Token valide",
                    content = @Content(schema = @Schema(implementation = String.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Token invalide ou expiré",
                    content = @Content(schema = @Schema(implementation = String.class))
            )
    })
    @SecurityRequirement(name = "Bearer Authentication")
    public ResponseEntity<?> validateToken(
            @Parameter(description = "Token JWT avec le préfixe Bearer", required = true)
            @RequestHeader("Authorization") String authHeader) {
        // Votre code existant...
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("Invalid authorization header");
            }

            String token = authHeader.substring(7);
            Jwt jwt = jwtService.validateToken(token);

            return ResponseEntity.ok()
                    .body("Token is valid for user: " + jwt.getSubject());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Invalid token: " + e.getMessage());
        }
    }
}