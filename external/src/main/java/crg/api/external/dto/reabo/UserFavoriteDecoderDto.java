package crg.api.external.dto.reabo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFavoriteDecoderDto {
    private Long id;
    private Long userId;
    private String decoderNumber;
    private String phoneNumber;      // NOUVEAU
    private String clientName;       // NOUVEAU
    private String searchType;       // NOUVEAU: 'TELEPHONE' ou 'DECODEUR'
    private String displayLabel;     // NOUVEAU: label formaté
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt; // NOUVEAU
}
