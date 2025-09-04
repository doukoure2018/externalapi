package crg.api.external.repository.impl;

import crg.api.external.dto.AccessDto;
import crg.api.external.dto.PackageDto;
import crg.api.external.dto.reabo.DisplayPackageDto;
import crg.api.external.dto.reabo.PackageDetailsResponse;
import crg.api.external.dto.reabo.TransactionDto;
import crg.api.external.exception.ApiException;
import crg.api.external.repository.ReabonnementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

import static crg.api.external.query.ReaboQuery.*;

@Repository
@RequiredArgsConstructor
@Slf4j
public class ReabonnementRepositoryImpl implements ReabonnementRepository {

    private final JdbcClient jdbcClient;
    @Override
    public List<PackageDto> getAllPackages() {
        try {
            return jdbcClient.sql(SELECT_ALL_PACKAGES_QUERY).query(PackageDto.class).list();
        }catch (EmptyResultDataAccessException exception) {
            throw new ApiException("No Package found");
        } catch (Exception exception) {
            log.error(exception.getMessage());
            throw new ApiException("An error occurred. Please try again.");
        }
    }

    @Override
    public List<DisplayPackageDto> getPackagesById(String packageId) {
        try {
            return jdbcClient.sql(SELECT_PACKAGE_DISPLAY_QUERY)
                    .param("packageId", packageId)  // Paramètre nommé
                    .query((rs, rowNum) -> DisplayPackageDto.builder()
                            .typeInfo(rs.getString("type_info"))
                            .valeur(rs.getString("valeur"))
                            .build())
                    .list();
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException("No Package found for ID: " + packageId);
        } catch (Exception exception) {
            log.error("Error fetching package details: {}", exception.getMessage());
            throw new ApiException("An error occurred. Please try again.");
        }
    }

    @Override
    public List<AccessDto> findAllActiveAccess() {
        try {
            return jdbcClient.sql(FIND_ALL_ACTIVE_ACCESS_QUERY)
                    .query(AccessDto.class)
                    .list();
        } catch (Exception exception) {
            log.error("Erreur lors de la récupération des accès actifs: {}", exception.getMessage());
            throw new RuntimeException("Erreur lors de la récupération des accès actifs", exception);
        }
    }


    @Override
    public PackageDetailsResponse getPackageDetailsStructured(String packageId) {
        List<DisplayPackageDto> rawData = getPackagesById(packageId);

        List<PackageDetailsResponse.OptionDetail> options = new ArrayList<>();
        List<PackageDetailsResponse.PeriodDetail> periods = new ArrayList<>();

        boolean inOptions = false;
        boolean inPeriods = false;

        for (DisplayPackageDto row : rawData) {
            if ("OPTIONS :".equals(row.getTypeInfo())) {
                inOptions = true;
                inPeriods = false;
                continue;
            }
            if ("Choix de la période:".equals(row.getTypeInfo())) {
                inOptions = false;
                inPeriods = true;
                continue;
            }

            if (inOptions && !row.getTypeInfo().isEmpty()) {
                options.add(PackageDetailsResponse.OptionDetail.builder()
                        .name(row.getTypeInfo())
                        .price(row.getValeur())
                        .build());
            }

            if (inPeriods && !row.getTypeInfo().isEmpty()) {
                periods.add(PackageDetailsResponse.PeriodDetail.builder()
                        .duration(row.getTypeInfo())
                        .price(row.getValeur())
                        .build());
            }
        }

        return PackageDetailsResponse.builder()
                .packageId(packageId)
                .options(options)
                .periods(periods)
                .build();
    }

    @Override
    public void addTransaction(TransactionDto transactionDto) {
        try {
            jdbcClient.sql(INSERT_TRANSACTION_QUERY)
                    .paramSource(getSqlParameterSourceTransaction(transactionDto))
                    .update();
        } catch (DataIntegrityViolationException exception) {
            log.error("Erreur d'intégrité des données: {}", exception.getMessage());
            throw new ApiException("Erreur de données: vérifiez que le décodeur et le compte Canal existent.");
        } catch (Exception exception) {
            log.error("Erreur lors de l'insertion de la transaction: {}", exception.getMessage());
            throw new ApiException("An error occurred. Please try again.");
        }
    }

    @Override
    public List<TransactionDto> getAllTransactionByUserId() {
        try {
            return jdbcClient.sql(SELECT_TRANSACTION).query(TransactionDto.class).list();
        }catch (EmptyResultDataAccessException exception) {
            throw new ApiException("No Package found");
        } catch (Exception exception) {
            log.error(exception.getMessage());
            throw new ApiException("An error occurred. Please try again.");
        }
    }

    private SqlParameterSource getSqlParameterSourceTransaction(TransactionDto transaction) {
        return new MapSqlParameterSource()
                .addValue("decoderNumber", transaction.getDecoderNumber())
                .addValue("packageId", transaction.getPackageId())
                .addValue("languageOptionId", transaction.getLanguageOptionId())
                .addValue("durationId", transaction.getDurationId())
                .addValue("amountGnf", transaction.getAmountGnf())
                .addValue("transactionDate", transaction.getTransactionDate())
                .addValue("status", transaction.getStatus())
                .addValue("paymentMethod", transaction.getPaymentMethod())
                .addValue("referenceNumber", transaction.getReferenceNumber())
                .addValue("subscriptionStartDate", transaction.getSubscriptionStartDate())
                .addValue("subscriptionEndDate", transaction.getSubscriptionEndDate())
                .addValue("canalUsername", transaction.getCanalUsername())
                .addValue("processingDurationMs", transaction.getProcessingDurationMs())
                .addValue("errorMessage", transaction.getErrorMessage());
    }
}
