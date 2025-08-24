package crg.api.external.query;

public class AccessQuery {

    public static final String INSERT_ACCESS_QUERY = "INSERT INTO accesscanal (username, password, createdAt, start_date, end_date) VALUES (:username, :password, :createdAt, :startDate, :endDate)";

    public static final String UPDATE_ACCESS_QUERY = "UPDATE accesscanal SET password = :password, start_date = :startDate, end_date = :endDate, createdAt = :createdAt WHERE id = :id";

    public static final String DELETE_ACCESS_QUERY = "DELETE FROM accesscanal WHERE id = :id";

    public static final String FIND_ACCESS_BY_USERNAME_QUERY = "SELECT id, username, password, createdAt, start_date, end_date FROM accesscanal WHERE username = :username";

    public static final String UPDATE_LAST_USED_AT = "UPDATE accesscanal SET last_used_at = NULL WHERE id = :id";

    public static final String FIND_ALL_ACCESS_QUERY = "SELECT id, username, password, createdAt, start_date, end_date FROM accesscanal ORDER BY createdAt DESC";

    public static final String FIND_ACTIVE_ACCESS_QUERY =
            """
              SELECT id, username, password, createdAt, start_date, end_date, last_used_at
                                  FROM accesscanal
                                  WHERE end_date >= CURRENT_DATE
                                  ORDER BY last_used_at ASC NULLS FIRST
                                  LIMIT 1
            """;

    public static final String FIND_EXPIRED_ACCESS_QUERY = "SELECT id, username, password, createdAt, start_date, end_date FROM accesscanal WHERE end_date < CURRENT_DATE";


    public static final String UPDATE_ACCESS_PASSWORD_QUERY = "UPDATE accesscanal SET password = :password WHERE id = :id";

    public static final String FIND_ACCESS_BY_ID_QUERY = "SELECT id, username, password, createdAt, start_date, end_date FROM accesscanal WHERE id = :id";

}
