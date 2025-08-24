package crg.api.external.dto.reabo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ReabonnementRequest {
    private String numAbonne;
    private String offre;
    private String duree;
    private String option;
}