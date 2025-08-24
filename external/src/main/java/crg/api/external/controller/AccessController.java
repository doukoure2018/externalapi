package crg.api.external.controller;

import crg.api.external.domain.HttpResponse;
import crg.api.external.dto.AccessDto;
import crg.api.external.service.AccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static java.time.LocalDateTime.now;
import static java.util.Map.of;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.HttpStatus.OK;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AccessController {

     private final AccessService accessService;


    @PostMapping("/addAccess")
    public ResponseEntity<HttpResponse> addAccess(@RequestBody AccessDto accessDto)
    {
        accessService.addAccess(accessDto);
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("access", accessDto))
                        .message("Accès ajouté avec succès")
                        .status(CREATED)
                        .statusCode(CREATED.value())
                        .build());
    }

    @PutMapping("/update")
    public ResponseEntity<HttpResponse> updateAccess(@RequestBody AccessDto accessDto)
    {
        accessService.updateAccess(accessDto);
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("access", accessDto))
                        .message("Accès mis à jour avec succès")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @PutMapping("/update-password/{id}")
    public ResponseEntity<HttpResponse> updatePassword(@PathVariable Long id,
                                                       @RequestBody Map<String, String> request) {
        String newPassword = request.get("password");
        accessService.updateAccessPassword(id, newPassword);
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of())
                        .message("Mot de passe mis à jour avec succès")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @GetMapping("/get/{id}")
    public ResponseEntity<HttpResponse> getAccessById(@PathVariable Long id) {
        AccessDto access = accessService.getAccessById(id);
        if (access == null) {
            return ResponseEntity.status(NOT_FOUND).body(
                    HttpResponse.builder()
                            .timeStamp(now().toString())
                            .message("Accès non trouvé")
                            .status(NOT_FOUND)
                            .statusCode(NOT_FOUND.value())
                            .build());
        }
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("access", access))
                        .message("Accès récupéré avec succès")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @GetMapping("/username/{username}")
    public ResponseEntity<HttpResponse> getAccessByUsername(@PathVariable String username) {
        AccessDto access = accessService.getAccessByUsername(username);
        if (access == null) {
            return ResponseEntity.status(NOT_FOUND).body(
                    HttpResponse.builder()
                            .timeStamp(now().toString())
                            .message("Accès non trouvé pour cet utilisateur")
                            .status(NOT_FOUND)
                            .statusCode(NOT_FOUND.value())
                            .build());
        }
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("access", access))
                        .message("Accès récupéré avec succès")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @GetMapping("/all")
    public ResponseEntity<HttpResponse> getAllAccess() {
        List<AccessDto> accessList = accessService.getAllAccess();
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("accessList", accessList, "count", accessList.size()))
                        .message("Liste des accès récupérée avec succès")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @GetMapping("/active")
    public ResponseEntity<HttpResponse> getActiveAccess() {
        AccessDto activeAccess = accessService.getActiveAccess();
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("activeAccess", activeAccess))
                        .message("Liste des accès actifs récupérée avec succès")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @GetMapping("/expired")
    public ResponseEntity<HttpResponse> getExpiredAccess() {
        List<AccessDto> expiredAccess = accessService.getExpiredAccess();
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("expiredAccess", expiredAccess, "count", expiredAccess.size()))
                        .message("Liste des accès expirés récupérée avec succès")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @DeleteMapping("/remove_access/{id}")
    public ResponseEntity<HttpResponse> deleteAccess(@PathVariable Long id) {
        accessService.deleteAccess(id);
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of())
                        .message("Accès supprimé avec succès")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @PostMapping("/verify")
    public ResponseEntity<HttpResponse> verifyAccess(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        boolean isValid = accessService.isAccessValid(username, password);

        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("isValid", isValid))
                        .message(isValid ? "Accès valide" : "Accès invalide ou expiré")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }

    @PutMapping("/extend/{id}")
    public ResponseEntity<HttpResponse> extendAccess(@PathVariable Long id,
                                                     @RequestBody Map<String, Integer> request) {
        Integer days = request.get("days");
        if (days == null || days <= 0) {
            return ResponseEntity.badRequest().body(
                    HttpResponse.builder()
                            .timeStamp(now().toString())
                            .message("Le nombre de jours doit être positif")
                            .status(BAD_REQUEST)
                            .statusCode(BAD_REQUEST.value())
                            .build());
        }

        accessService.extendAccessValidity(id, days);
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .data(of("additionalDays", days))
                        .message("Validité de l'accès étendue avec succès")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }


    @PutMapping("/reset-usage/{id}")
    public ResponseEntity<HttpResponse> resetUsage(@PathVariable Long id) {
        accessService.resetAccessUsage(id);
        return ResponseEntity.ok().body(
                HttpResponse.builder()
                        .timeStamp(now().toString())
                        .message("Utilisation réinitialisée avec succès")
                        .status(OK)
                        .statusCode(OK.value())
                        .build());
    }
}
