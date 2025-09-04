package crg.api.external.repository;

import crg.api.external.dto.AccessDto;
import crg.api.external.dto.PackageDto;
import crg.api.external.dto.reabo.DisplayPackageDto;
import crg.api.external.dto.reabo.PackageDetailsResponse;
import crg.api.external.dto.reabo.TransactionDto;

import java.util.List;


public interface ReabonnementRepository {
    List<PackageDto> getAllPackages();

    PackageDetailsResponse getPackageDetailsStructured(String packageId);

    void addTransaction(TransactionDto transactionDto);

    List<TransactionDto> getAllTransactionByUserId();

    List<DisplayPackageDto> getPackagesById(String packageId);

    List<AccessDto> findAllActiveAccess();
}
