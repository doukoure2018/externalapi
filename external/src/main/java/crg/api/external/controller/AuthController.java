package crg.api.external.controller;

import crg.api.external.dto.auth.AuthResponse;
import crg.api.external.dto.auth.LoginRequest;
import crg.api.external.dto.auth.RefreshTokenRequest;
import crg.api.external.dto.auth.RegisterRequest;
import crg.api.external.entity.User;
import crg.api.external.repository.UserRepository;
import crg.api.external.service.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Value("${jwt.expiration.minutes:60}")
    private Long expirationMinutes;

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        // Vérifier si l'utilisateur existe déjà
        if (userRepository.existsByUsername(request.getUsername())) {
            return ResponseEntity.badRequest()
                    .body("Error: Username is already taken!");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            return ResponseEntity.badRequest()
                    .body("Error: Email is already in use!");
        }

        // Créer nouvel utilisateur
        Set<String> roles = new HashSet<>();
        roles.add("USER"); // Role par défaut

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

        // Générer les tokens
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
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        // Chercher l'utilisateur
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        // Vérifier le mot de passe
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid username or password");
        }

        // Vérifier si le compte est actif
        if (!user.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Account is disabled");
        }

        if (user.isLocked()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Account is locked");
        }

        // Générer les tokens
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
    public ResponseEntity<?> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        try {
            String refreshToken = request.getRefreshToken();

            // Valider le refresh token
            if (!jwtService.isRefreshToken(refreshToken)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("Invalid refresh token");
            }

            // Extraire le username
            String username = jwtService.extractUsername(refreshToken);

            // Chercher l'utilisateur
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new BadCredentialsException("User not found"));

            // Générer nouveau access token
            String newAccessToken = jwtService.generateAccessToken(
                    user.getUsername(),
                    new ArrayList<>(user.getRoles())
            );

            // Générer nouveau refresh token
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
    public ResponseEntity<?> validateToken(@RequestHeader("Authorization") String authHeader) {
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