package crg.api.external.service;

import crg.api.external.dto.PackageDto;
import crg.api.external.dto.reabo.PackageDetailsResponse;
import crg.api.external.dto.reabo.ReabonnementRequest;
import crg.api.external.dto.reabo.TransactionDto;
import crg.api.external.dto.reabo.UserFavoriteDecoderDto;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ReabonnementService {

    String effectuerReabonnement(ReabonnementRequest request) throws InterruptedException;

    Optional<Map<String, Object>> rechercherInfosAbonne(String numAbonne);

    List<PackageDto> getAllPackages();

    PackageDetailsResponse getPackageDetailsStructured(String packageId);


    void addTransaction(TransactionDto transactionDto);

    List<TransactionDto> getAllTransactions();

    Map<String, Object> getAccountsStatus();
}
