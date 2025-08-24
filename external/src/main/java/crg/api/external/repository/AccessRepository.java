package crg.api.external.repository;

import crg.api.external.dto.AccessDto;

import java.util.List;

public interface AccessRepository {
    void addAccess(AccessDto accessDto);

    void updateAccessPassword(Long id, String hashedPassword);

    AccessDto findAccessById(Long id);

    void updateAccess(AccessDto accessDto);

    void deleteAccess(Long id);

    AccessDto findAccessByUsername(String username);

    void resetAccessUsage(Long id);

    List<AccessDto> findAllAccess();

    AccessDto findActiveAccess();

    List<AccessDto> findExpiredAccess();
}
