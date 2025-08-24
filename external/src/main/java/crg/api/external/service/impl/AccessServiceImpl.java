package crg.api.external.service.impl;

import crg.api.external.dto.AccessDto;
import crg.api.external.repository.AccessRepository;
import crg.api.external.service.AccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccessServiceImpl implements AccessService {

    private final AccessRepository accessRepository;
    @Override
    public AccessDto getAccessByUsername(String username) {
        log.debug("Recherche de l'accès pour l'utilisateur: {}", username);
        return accessRepository.findAccessByUsername(username);
    }

    @Override
    public AccessDto getAccessById(Long id) {
        log.debug("Recherche de l'accès avec l'ID: {}", id);
        return accessRepository.findAccessById(id);
    }

    @Override
    public void addAccess(AccessDto accessDto) {
        log.info("Ajout d'un nouvel accès pour l'utilisateur: {}", accessDto.getUsername());

        // Validation des données
        validateAccessDto(accessDto);

        // Hachage du mot de passe avant stockage
        accessDto.setPassword(accessDto.getPassword());

        // Définir la date de création
        accessDto.setCreatedAt(LocalDate.now());

        // Si les dates ne sont pas fournies, définir des valeurs par défaut
        if (accessDto.getStartDate() == null) {
            accessDto.setStartDate(LocalDate.now());
        }
        if (accessDto.getEndDate() == null) {
            // Par défaut, accès valide pour 30 jours
            accessDto.setEndDate(LocalDate.now().plusDays(30));
        }

        accessRepository.addAccess(accessDto);
        log.info("Accès ajouté avec succès pour l'utilisateur: {}", accessDto.getUsername());
    }

    private void validateAccessDto(AccessDto accessDto) {
        if (accessDto.getUsername() == null || accessDto.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("Le nom d'utilisateur est requis");
        }

        if (accessDto.getPassword() == null || accessDto.getPassword().trim().isEmpty()) {
            throw new IllegalArgumentException("Le mot de passe est requis");
        }

        if (accessDto.getUsername().length() > 14) {
            throw new IllegalArgumentException("Le nom d'utilisateur ne peut pas dépasser 14 caractères");
        }

        // Vérifier que les dates sont cohérentes
        if (accessDto.getStartDate() != null && accessDto.getEndDate() != null) {
            if (accessDto.getEndDate().isBefore(accessDto.getStartDate())) {
                throw new IllegalArgumentException("La date de fin doit être après la date de début");
            }
        }
    }

    @Override
    public void updateAccess(AccessDto accessDto) {
        log.info("Mise à jour de l'accès ID: {}", accessDto.getId());

        if (accessDto.getId() == null) {
            throw new IllegalArgumentException("L'ID de l'accès est requis pour la mise à jour");
        }

        // Récupérer l'accès existant
        AccessDto existingAccess = accessRepository.findAccessById(accessDto.getId());
        if (existingAccess == null) {
            throw new RuntimeException("Accès non trouvé avec l'ID: " + accessDto.getId());
        }

        // Si un nouveau mot de passe est fourni, le hacher
        if (accessDto.getPassword() != null && !accessDto.getPassword().isEmpty()) {
            accessDto.setPassword(accessDto.getPassword());
        } else {
            // Conserver l'ancien mot de passe si aucun nouveau n'est fourni
            accessDto.setPassword(existingAccess.getPassword());
        }

        accessRepository.updateAccess(accessDto);
        log.info("Accès mis à jour avec succès pour l'ID: {}", accessDto.getId());
    }

    @Override
    public void updateAccessPassword(Long id, String newPassword) {
        log.info("Mise à jour du mot de passe pour l'accès ID: {}", id);

        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new IllegalArgumentException("Le nouveau mot de passe ne peut pas être vide");
        }

        // Vérifier que l'accès existe
        AccessDto existingAccess = accessRepository.findAccessById(id);
        if (existingAccess == null) {
            throw new RuntimeException("Accès non trouvé avec l'ID: " + id);
        }

        // Hacher le nouveau mot de passe
        String hashedPassword = newPassword;

        accessRepository.updateAccessPassword(id, hashedPassword);
        log.info("Mot de passe mis à jour avec succès pour l'accès ID: {}", id);
    }

    @Override
    public List<AccessDto> getAllAccess() {
        log.debug("Récupération de tous les accès");
        return accessRepository.findAllAccess();
    }

    @Override
    public AccessDto getActiveAccess() {
        log.debug("Récupération des accès actifs");
        return accessRepository.findActiveAccess();
    }

    @Override
    public List<AccessDto> getExpiredAccess() {
        log.debug("Récupération des accès expirés");
        return accessRepository.findExpiredAccess();
    }

    @Override
    public void deleteAccess(Long id) {
        log.info("Suppression de l'accès ID: {}", id);

        // Vérifier que l'accès existe
        AccessDto existingAccess = accessRepository.findAccessById(id);
        if (existingAccess == null) {
            throw new RuntimeException("Accès non trouvé avec l'ID: " + id);
        }

        accessRepository.deleteAccess(id);
        log.info("Accès supprimé avec succès pour l'ID: {}", id);
    }

    @Override
    public boolean isAccessValid(String username, String password) {
        log.debug("Vérification de la validité de l'accès pour l'utilisateur: {}", username);

        AccessDto access = accessRepository.findAccessByUsername(username);
        if (access == null) {
            return false;
        }

        // Vérifier le mot de passe
        if (!password.equalsIgnoreCase(access.getPassword())) {
            return false;
        }
        // Vérifier les dates de validité
        LocalDate today = LocalDate.now();
        return !today.isBefore(access.getStartDate()) && !today.isAfter(access.getEndDate());
    }

    @Override
    public void extendAccessValidity(Long id, Integer days) {
        log.info("Extension de la validité de l'accès ID: {} de {} jours", id, days);

        AccessDto access = accessRepository.findAccessById(id);
        if (access == null) {
            throw new RuntimeException("Accès non trouvé avec l'ID: " + id);
        }

        LocalDate newEndDate = access.getEndDate().plusDays(days);
        access.setEndDate(newEndDate);

        accessRepository.updateAccess(access);
        log.info("Validité étendue jusqu'au: {} pour l'accès ID: {}", newEndDate, id);
    }

    @Override
    public void resetAccessUsage(Long id) {
        accessRepository.resetAccessUsage(id);
    }
}
