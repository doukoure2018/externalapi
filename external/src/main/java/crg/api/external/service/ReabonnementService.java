package crg.api.external.service;

import crg.api.external.dto.reabo.ReabonnementRequest;

import java.util.Map;
import java.util.Optional;

public interface ReabonnementService {

    String effectuerReabonnement(ReabonnementRequest request);

    Optional<Map<String, Object>> rechercherInfosAbonne(String numAbonne);
}
