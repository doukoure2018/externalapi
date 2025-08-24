package crg.api.external.repository.impl;

import crg.api.external.dto.AccessDto;
import crg.api.external.repository.AccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static crg.api.external.query.AccessQuery.*;

@Repository
@RequiredArgsConstructor
@Slf4j
public class AccessRepositoryImpl implements AccessRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcClient jdbcClient;
    @Override
    public void addAccess(AccessDto accessDto) {
        try {
            SqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("username", accessDto.getUsername())
                    .addValue("password", accessDto.getPassword())
                    .addValue("createdAt", accessDto.getCreatedAt())
                    .addValue("startDate", accessDto.getStartDate())
                    .addValue("endDate", accessDto.getEndDate());

            jdbc.update(INSERT_ACCESS_QUERY, parameters);
            log.info("Accès ajouté avec succès pour l'utilisateur: {}", accessDto.getUsername());
        } catch (Exception exception) {
            log.error("Erreur lors de l'ajout de l'accès: {}", exception.getMessage());
            throw new RuntimeException("Erreur lors de l'ajout de l'accès", exception);
        }
    }

    @Override
    public void updateAccessPassword(Long id, String hashedPassword) {
        try {
            SqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("password", hashedPassword);

            int rowsAffected = jdbc.update(UPDATE_ACCESS_PASSWORD_QUERY, parameters);
            if (rowsAffected == 0) {
                throw new RuntimeException("Aucun accès trouvé avec l'ID: " + id);
            }
            log.info("Mot de passe mis à jour avec succès pour l'accès ID: {}", id);
        } catch (Exception exception) {
            log.error("Erreur lors de la mise à jour du mot de passe: {}", exception.getMessage());
            throw new RuntimeException("Erreur lors de la mise à jour du mot de passe", exception);
        }
    }

    @Override
    public AccessDto findAccessById(Long id)
    {
        try {
            SqlParameterSource parameters = new MapSqlParameterSource("id", id);
            return jdbc.queryForObject(FIND_ACCESS_BY_ID_QUERY, parameters, this::mapRowToAccess);
        } catch (EmptyResultDataAccessException exception) {
            log.debug("Aucun accès trouvé avec l'ID: {}", id);
            return null;
        } catch (Exception exception) {
            log.error("Erreur lors de la recherche de l'accès par ID: {}", exception.getMessage());
            throw new RuntimeException("Erreur lors de la recherche de l'accès", exception);
        }
    }

    @Override
    public void updateAccess(AccessDto accessDto) {
        try {
            SqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("id", accessDto.getId())
                    .addValue("password", accessDto.getPassword())
                    .addValue("startDate", accessDto.getStartDate())
                    .addValue("endDate", accessDto.getEndDate())
                    .addValue("createdAt", LocalDate.now());

            int rowsAffected = jdbc.update(UPDATE_ACCESS_QUERY, parameters);
            if (rowsAffected == 0) {
                throw new RuntimeException("Aucun accès trouvé avec l'ID: " + accessDto.getId());
            }
            log.info("Accès mis à jour avec succès pour l'ID: {}", accessDto.getId());
        } catch (Exception exception) {
            log.error("Erreur lors de la mise à jour de l'accès: {}", exception.getMessage());
            throw new RuntimeException("Erreur lors de la mise à jour de l'accès", exception);
        }
    }

    @Override
    public void deleteAccess(Long id) {
        try {
            SqlParameterSource parameters = new MapSqlParameterSource("id", id);
            int rowsAffected = jdbc.update(DELETE_ACCESS_QUERY, parameters);
            if (rowsAffected == 0) {
                throw new RuntimeException("Aucun accès trouvé avec l'ID: " + id);
            }
            log.info("Accès supprimé avec succès pour l'ID: {}", id);
        } catch (Exception exception) {
            log.error("Erreur lors de la suppression de l'accès: {}", exception.getMessage());
            throw new RuntimeException("Erreur lors de la suppression de l'accès", exception);
        }
    }

    @Override
    public AccessDto findAccessByUsername(String username) {
        try {
            SqlParameterSource parameters = new MapSqlParameterSource("username", username);
            return jdbc.queryForObject(FIND_ACCESS_BY_USERNAME_QUERY, parameters, this::mapRowToAccess);
        } catch (EmptyResultDataAccessException exception) {
            log.debug("Aucun accès trouvé pour l'utilisateur: {}", username);
            return null;
        } catch (Exception exception) {
            log.error("Erreur lors de la recherche de l'accès par username: {}", exception.getMessage());
            throw new RuntimeException("Erreur lors de la recherche de l'accès", exception);
        }
    }

    @Override
    public void resetAccessUsage(Long id) {
        jdbcClient.sql(UPDATE_LAST_USED_AT).param("id",id).update();
    }

    @Override
    public List<AccessDto> findAllAccess() {
        try {
            return jdbc.query(FIND_ALL_ACCESS_QUERY, this::mapRowToAccess);
        } catch (Exception exception) {
            log.error("Erreur lors de la récupération de tous les accès: {}", exception.getMessage());
            throw new RuntimeException("Erreur lors de la récupération des accès", exception);
        }
    }

    @Override
    public AccessDto findActiveAccess() {
        try {
            return jdbcClient.sql(FIND_ACTIVE_ACCESS_QUERY).query(AccessDto.class).single();
        } catch (Exception exception) {
            log.error("Erreur lors de la récupération des accès actifs: {}", exception.getMessage());
            throw new RuntimeException("Erreur lors de la récupération des accès actifs", exception);
        }
    }

    @Override
    public List<AccessDto> findExpiredAccess() {
        try {
            return jdbc.query(FIND_EXPIRED_ACCESS_QUERY, this::mapRowToAccess);
        } catch (Exception exception) {
            log.error("Erreur lors de la récupération des accès expirés: {}", exception.getMessage());
            throw new RuntimeException("Erreur lors de la récupération des accès expirés", exception);
        }
    }

    private AccessDto mapRowToAccess(ResultSet rs, int rowNum) throws SQLException {
        AccessDto access = new AccessDto();
        access.setId(rs.getLong("id"));
        access.setUsername(rs.getString("username"));
        access.setPassword(rs.getString("password"));
        access.setCreatedAt(rs.getDate("createdAt").toLocalDate());
        access.setStartDate(rs.getDate("start_date").toLocalDate());
        access.setEndDate(rs.getDate("end_date").toLocalDate());
        return access;
    }
}
