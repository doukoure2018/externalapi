package crg.api.external.dto;

import lombok.*;

import java.time.LocalDateTime;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class PackageDto {
    private String id;
    private String name;
    private String displayName;
    private String description;
    private Boolean isActive;
    private LocalDateTime createdAt;
}

