package crg.api.external.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AccessDto {
    private Long id;
    private String username;
    private String password;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate createdAt;
    private LocalDateTime lastUsedAt;
}