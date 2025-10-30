package crg.api.external.dto.reabo;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class TransactionDto {
    private Long id;
    private String decoderNumber;
    private String packageId;
    private String languageOptionId;
    private String durationId;
    private Integer amountGnf;
    private LocalDateTime transactionDate;
    private String status;
    private String paymentMethod;
    private String referenceNumber;
    private LocalDate subscriptionStartDate;  // Ajouté pour votre table
    private LocalDate subscriptionEndDate;    // Existant
    private String canalUsername;
    // Optionnel : champs supplémentaires pour monitoring
    private Integer processingDurationMs;
    private String errorMessage;
    private String phoneNumber;
}

