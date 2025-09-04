package crg.api.external.controller;

import crg.api.external.domain.HttpResponse;
import crg.api.external.dto.reabo.ReabonnementRequest;
import crg.api.external.dto.reabo.TransactionDto;
import crg.api.external.dto.reabo.UserFavoriteDecoderDto;
import crg.api.external.service.ReabonnementService;
import crg.api.external.util.ReabonnementRequestNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static java.time.LocalTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;

@RestController
@RequestMapping("/api/reabo")
@RequiredArgsConstructor
@Slf4j
public class ReabonnementController {

    private final ReabonnementService reabonnementService;

    private final ReabonnementRequestNormalizer normalizer;

    // Cache simple pour les vérifications d'abonnés (TTL 5 minutes)
    private final Map<String, CacheEntry<Map<String, Object>>> abonneCache = new ConcurrentHashMap<>();

    private static class CacheEntry<T> {
        final T value;
        final long timestamp;

        CacheEntry(T value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long ttlMillis) {
            return System.currentTimeMillis() - timestamp > ttlMillis;
        }
    }


    @PostMapping("/check-decoder")
    public ResponseEntity<?> verifierAbonne(@RequestParam String numAbonne) {
        long startTime = System.currentTimeMillis();

        try {
            Optional<Map<String, Object>> infos = reabonnementService.rechercherInfosAbonne(numAbonne);
            long duration = System.currentTimeMillis() - startTime;

            if (infos.isPresent()) {
                Map<String, Object> data = new HashMap<>(infos.get());

                // Ajouter les métadonnées standard
                data.put("existe", true);
                data.put("source", "verification");
                data.put("dureeExecution", duration + "ms");

                // Gérer le message selon le nombre de résultats
                @SuppressWarnings("unchecked")
                List<Map<String, String>> resultats = (List<Map<String, String>>) data.get("resultats");

                if (resultats != null) {
                    int count = resultats.size();
                    if (count == 1) {
                        data.put("message", "Abonné trouvé");
                        // Pour la rétrocompatibilité, ajouter les infos du premier résultat au niveau racine
                        data.putAll(resultats.get(0));
                    } else {
                        data.put("message", count + " abonnés trouvés");
                    }
                } else {
                    data.put("message", "Abonné trouvé");
                }

                return ResponseEntity.ok(data);

            } else {
                return ResponseEntity.ok(Map.of(
                        "existe", false,
                        "message", "Aucun abonné correspondant trouvé",
                        "source", "verification",
                        "dureeExecution", duration + "ms",
                        "query", numAbonne,
                        "nombre_resultats", 0
                ));
            }

        } catch (Exception e) {
            log.error("Erreur vérification abonné '{}': {}", numAbonne, e.getMessage());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "existe", false,
                            "error", true,
                            "message", "Erreur lors de la vérification",
                            "details", e.getMessage()
                    ));
        }
    }


    @GetMapping("/allPackages")
    public ResponseEntity<HttpResponse> getAllPackages()
    {

        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(LocalDateTime.now().toString())
                        .data(of("packages", reabonnementService.getAllPackages()
                        ))
                        .message("All Packages")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }


    @GetMapping("/packages/{packageId}/structured")
    public ResponseEntity<HttpResponse> getPackageDetailsStructured(
            @PathVariable(name = "packageId") String packageId) {

        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(LocalDateTime.now().toString())
                        .data(Map.of("packageDetails", reabonnementService.getPackageDetailsStructured(packageId)))
                        .message("Package details retrieved successfully")
                        .status(OK)
                        .statusCode(OK.value())
                        .build()
        );
    }

    @PostMapping("/reabonnement")
    public ResponseEntity<HttpResponse> reabonner(@RequestBody ReabonnementRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            request = normalizer.normalize(request);
            // Vérifier la validité de la combinaison
            if (!normalizer.isValidCombination(request.getOffre(), request.getOption())) {
                return ResponseEntity.status(400)
                        .body(HttpResponse.builder()
                                .timeStamp(now().toString())
                                .data(Map.of(
                                        "erreur", "Combinaison offre/option invalide",
                                        "offre", request.getOffre(),
                                        "option", request.getOption()
                                ))
                                .message("Cette option n'est pas disponible pour cette offre")
                                .status(org.springframework.http.HttpStatus.BAD_REQUEST)
                                .statusCode(400)
                                .build());
            }

            String statut = reabonnementService.effectuerReabonnement(request);
            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ Réabonnement terminé en {}ms - Statut: {}", duration, statut);

            // Invalider le cache pour cet abonné
            abonneCache.remove(request.getNumAbonne());

            // Vérifier si c'est une erreur de solde insuffisant
            if (statut.contains("Solde insuffisant")) {
                return ResponseEntity.status(402) // 402 Payment Required
                        .body(HttpResponse.builder()
                                .timeStamp(now().toString())
                                .data(Map.of(
                                        "resultat", statut,
                                        "errorCode", "INSUFFICIENT_BALANCE",
                                        "errorType", "SOLDE_INSUFFISANT",
                                        "dureeExecution", duration + "ms"
                                ))
                                .message("Solde insuffisant pour effectuer le réabonnement")
                                .status(org.springframework.http.HttpStatus.PAYMENT_REQUIRED)
                                .statusCode(402)
                                .build());
            }

            // Si c'est un succès
            if (statut.contains("succès")) {
                return ResponseEntity.created(URI.create(""))
                        .body(HttpResponse.builder()
                                .timeStamp(now().toString())
                                .data(Map.of(
                                        "resultat", statut,
                                        "dureeExecution", duration + "ms"
                                ))
                                .message("Réabonnement effectué avec succès")
                                .status(CREATED)
                                .statusCode(CREATED.value())
                                .build());
            }

            // Pour toute autre erreur
            return ResponseEntity.status(400)
                    .body(HttpResponse.builder()
                            .timeStamp(now().toString())
                            .data(Map.of(
                                    "resultat", statut,
                                    "dureeExecution", duration + "ms"
                            ))
                            .message(statut)
                            .status(org.springframework.http.HttpStatus.BAD_REQUEST)
                            .statusCode(400)
                            .build());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("❌ Erreur réabonnement après {}ms", duration, e);

            // Gérer spécifiquement l'exception de solde insuffisant
            if (e.getMessage() != null &&
                    (e.getMessage().contains("SOLDE_INSUFFISANT") ||
                            e.getMessage().contains("DTA-1009"))) {

                return ResponseEntity.status(402)
                        .body(HttpResponse.builder()
                                .timeStamp(now().toString())
                                .data(Map.of(
                                        "erreur", "Solde insuffisant",
                                        "errorCode", "INSUFFICIENT_BALANCE",
                                        "dureeExecution", duration + "ms"
                                ))
                                .message("Solde insuffisant sur votre compte distributeur")
                                .status(org.springframework.http.HttpStatus.PAYMENT_REQUIRED)
                                .statusCode(402)
                                .build());
            }

            return ResponseEntity.status(500)
                    .body(HttpResponse.builder()
                            .timeStamp(now().toString())
                            .data(Map.of(
                                    "erreur", e.getMessage(),
                                    "dureeExecution", duration + "ms"
                            ))
                            .message("Erreur : " + e.getMessage())
                            .status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                            .statusCode(500)
                            .build());
        }
    }

    @PostMapping("/addTransactions")
    public ResponseEntity<HttpResponse> addTransactions(@RequestBody TransactionDto transactionDto)
    {
         reabonnementService.addTransaction(transactionDto);
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(LocalDateTime.now().toString())
                        .data(of())
                        .message("Transaction Added Successfully")
                        .status(CREATED)
                        .statusCode(CREATED.value())
                        .build());
    }

    @GetMapping("/allTransactions/{userId}")
    public ResponseEntity<HttpResponse> allTransactions() {

        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(LocalDateTime.now().toString())
                        .data(of("transactions",reabonnementService.getAllTransactionByUserId()
                        ))
                        .message("Toutes les transactions")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @GetMapping("/cache/stats")
    public ResponseEntity<?> getCacheStats() {
        long validEntries = abonneCache.entrySet().stream()
                .filter(e -> !e.getValue().isExpired(300000))
                .count();

        return ResponseEntity.ok(Map.of(
                "totalEntries", abonneCache.size(),
                "validEntries", validEntries,
                "hitRate", String.format("%.2f%%",
                        abonneCache.isEmpty() ? 0 : (validEntries * 100.0 / abonneCache.size()))
        ));
    }

    @Async
    protected void cleanupCacheAsync() {
        CompletableFuture.runAsync(() -> {
            abonneCache.entrySet().removeIf(entry ->
                    entry.getValue().isExpired(300000)
            );
        });
    }



    @GetMapping("/health/detailed")
    public ResponseEntity<?> detailedHealth() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "cache", Map.of(
                        "size", abonneCache.size(),
                        "enabled", true
                ),
                "selenium", Map.of(
                        "enabled", true,
                        "pooling", true
                )
        ));
    }
}
