package crg.api.external.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class JwtService {

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Value("${jwt.expiration.minutes:60}")
    private Long expirationMinutes;

    @Value("${jwt.refresh.expiration.days:7}")
    private Long refreshExpirationDays;

    @Value("${jwt.issuer:external-api}")
    private String issuer;

    /**
     * Génère un token d'accès JWT
     */
    public String generateAccessToken(String username, List<String> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expirationMinutes, ChronoUnit.MINUTES);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(expiry)
                .subject(username)
                .claim("roles", roles)
                .claim("type", "access")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * Génère un refresh token JWT
     */
    public String generateRefreshToken(String username) {
        Instant now = Instant.now();
        Instant expiry = now.plus(refreshExpirationDays, ChronoUnit.DAYS);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(expiry)
                .subject(username)
                .claim("type", "refresh")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * Valide et décode un token JWT
     */
    public Jwt validateToken(String token) {
        try {
            return jwtDecoder.decode(token);
        } catch (JwtException e) {
            throw new IllegalArgumentException("Invalid JWT token", e);
        }
    }

    /**
     * Extrait le nom d'utilisateur du token
     */
    public String extractUsername(String token) {
        Jwt jwt = validateToken(token);
        return jwt.getSubject();
    }

    /**
     * Vérifie si le token est un refresh token
     */
    public boolean isRefreshToken(String token) {
        Jwt jwt = validateToken(token);
        String type = jwt.getClaimAsString("type");
        return "refresh".equals(type);
    }

    /**
     * Vérifie si le token est expiré
     */
    public boolean isTokenExpired(String token) {
        try {
            Jwt jwt = validateToken(token);
            Instant expiry = jwt.getExpiresAt();
            return expiry != null && expiry.isBefore(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }
}