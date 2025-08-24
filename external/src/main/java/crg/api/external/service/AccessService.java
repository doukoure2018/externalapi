package crg.api.external.service;

import crg.api.external.dto.AccessDto;

import java.util.List;

public interface AccessService {
    AccessDto getAccessByUsername(String username);

    AccessDto getAccessById(Long id);

    void addAccess(AccessDto accessDto);

    void updateAccess(AccessDto accessDto);

    void updateAccessPassword(Long id, String newPassword);

    List<AccessDto> getAllAccess();

    AccessDto getActiveAccess();

    List<AccessDto> getExpiredAccess();

    void deleteAccess(Long id);

    boolean isAccessValid(String username, String password);

    void extendAccessValidity(Long id, Integer days);

    void resetAccessUsage(Long id);
}
