package crg.api.external.dto.reabo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PackageDetailsResponse {
    private String packageId;
    private List<OptionDetail> options;
    private List<PeriodDetail> periods;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionDetail {
        private String name;
        private String price;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodDetail {
        private String duration;
        private String price;
    }
}