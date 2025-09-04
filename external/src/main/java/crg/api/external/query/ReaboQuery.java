package crg.api.external.query;

public class ReaboQuery {

    public static final String SELECT_ALL_PACKAGES_QUERY =
            """
                SELECT id, display_name, description
                        FROM packages
                        WHERE is_active = true
                        ORDER BY
                            CASE id
                                WHEN 'access' THEN 1
                                WHEN 'access_plus' THEN 2
                                WHEN 'evasion' THEN 3
                                WHEN 'tout_canal' THEN 4
                                END
            """;


    public static final String SELECT_PACKAGE_DISPLAY_QUERY = """
        WITH config AS (
                    SELECT :packageId as selected_package
                ),
                prix_base AS (
                    SELECT
                        sd.name,
                        sd.months,
                        sp.price_gnf
                    FROM subscription_prices sp
                    JOIN subscription_durations sd ON sd.id = sp.duration_id
                    JOIN config c ON sp.package_id = c.selected_package
                    WHERE sp.language_option_id = 'sans_option'
                        AND sp.is_active = true
                ),
                options_disponibles AS (
                    SELECT DISTINCT
                        lo.display_name as option_name,
                        CASE c.selected_package
                            WHEN 'access' THEN
                                CASE lo.id
                                    WHEN 'charme' THEN 95000
                                    WHEN 'english' THEN 0
                                END
                            WHEN 'access_plus' THEN
                                CASE lo.id
                                    WHEN 'charme' THEN 95000
                                    WHEN 'english' THEN 30000
                                END
                            WHEN 'evasion' THEN
                                CASE lo.id
                                    WHEN 'charme' THEN 95000
                                    WHEN 'english' THEN 170000
                                END
                            WHEN 'tout_canal' THEN
                                CASE lo.id
                                    WHEN 'charme' THEN 0
                                    WHEN 'english' THEN 0
                                END
                        END as prix_option
                    FROM language_options lo
                    CROSS JOIN config c
                    WHERE lo.id IN ('charme', 'english')
                ),
                options_filtrees AS (
                    SELECT option_name, prix_option
                    FROM options_disponibles
                    WHERE prix_option IS NOT NULL\s
                        AND prix_option > 0
                )
               
                SELECT type_info, valeur FROM (
                    SELECT 1 as ordre, 'OPTIONS :' as type_info, '' as valeur
                    WHERE EXISTS (SELECT 1 FROM options_filtrees)
                    UNION ALL
                    SELECT 2, option_name, TO_CHAR(prix_option, 'FM999,999,999') || ' GNF'
                    FROM options_filtrees
                    UNION ALL
                    SELECT 3, '', ''
                    WHERE EXISTS (SELECT 1 FROM options_filtrees)
                    UNION ALL
                    SELECT 4, 'Choix de la période:', ''
                    UNION ALL
                    SELECT 5, name || ' =', TO_CHAR(price_gnf, 'FM999,999,999') || ' GNF'
                    FROM prix_base
                ) result
                ORDER BY ordre, type_info
    """;


    public static final String INSERT_TRANSACTION_QUERY =
            """
            INSERT INTO transactions(
                decoder_number, package_id, language_option_id,
                duration_id, amount_gnf, transaction_date, status,
                payment_method, reference_number, subscription_start_date, subscription_end_date,
                canal_username,processing_duration_ms,error_message
            )
            VALUES(
                :decoderNumber, :packageId, :languageOptionId,
                :durationId, :amountGnf, :transactionDate, :status,
                :paymentMethod, :referenceNumber, :subscriptionStartDate, :subscriptionEndDate,
                :canalUsername,:processingDurationMs,:errorMessage
            )
            """;


    public static final String SELECT_TRANSACTION =
            """
                 SELECT * FROM transactions
            """;


    public static final String FIND_ALL_ACTIVE_ACCESS_QUERY =
            """
            SELECT id, username, password, createdAt, start_date, end_date, last_used_at
            FROM accesscanal
            WHERE end_date >= CURRENT_DATE
            ORDER BY last_used_at ASC NULLS FIRST
            """;
}
