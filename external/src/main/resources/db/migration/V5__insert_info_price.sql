-- =====================================================
-- V5: Insertion des données de référence Canal+
-- =====================================================

-- Insertion des packages
INSERT INTO packages (id, name, display_name)
VALUES
    ('access', 'ACCESS', 'ACCESS'),
    ('access_plus', 'ACCESS+', 'ACCESS+'),
    ('evasion', 'EVASION', 'EVASION'),
    ('tout_canal', 'TOUT CANAL+', 'TOUT CANAL+')
    ON CONFLICT (id) DO NOTHING;

-- Insertion des options linguistiques
INSERT INTO language_options (id, name, display_name)
VALUES
    ('charme', 'CHARME', 'CHARME'),
    ('english', 'ENGLISH', 'ENGLISH'),
    ('sans_option', 'SANS OPTION', 'Sans option'),
    ('pvr', 'PVR', 'PVR'),
    ('2ecrans', '2 ECRANS', '2 ECRANS'),
    ('netflix_1', 'NETFLIX 1 ECRAN', 'NETFLIX 1 ECRAN'),
    ('netflix_2', 'NETFLIX 2 ECRANS', 'NETFLIX 2 ECRANS'),
    ('netflix_4', 'NETFLIX 4 ECRANS', 'NETFLIX 4 ECRANS')
    ON CONFLICT (id) DO NOTHING;

-- Insertion des durées d'abonnement
INSERT INTO subscription_durations (id, name, months)
VALUES
    ('1_month', '1 Mois', 1),
    ('3_months', '3 Mois', 3),
    ('6_months', '6 Mois', 6),
    ('12_months', '12 Mois', 12)
    ON CONFLICT (id) DO NOTHING;

-- Insertion des prix d'abonnement
-- ACCESS PLUS avec SANS OPTION
INSERT INTO subscription_prices (package_id, language_option_id, duration_id, price_gnf, is_active)
VALUES
    ('access_plus', 'sans_option', '3_months', 664000, true),
    ('access_plus', 'sans_option', '1_month', 223540, true)
    ON CONFLICT (package_id, language_option_id, duration_id) DO NOTHING;

-- EVASION avec SANS OPTION
INSERT INTO subscription_prices (package_id, language_option_id, duration_id, price_gnf, is_active)
VALUES
    ('evasion', 'sans_option', '3_months', 484000, true),
    ('evasion', 'sans_option', '1_month', 163540, true)
    ON CONFLICT (package_id, language_option_id, duration_id) DO NOTHING;

-- TOUT CANAL avec SANS OPTION
INSERT INTO subscription_prices (package_id, language_option_id, duration_id, price_gnf, is_active)
VALUES
    ('tout_canal', 'sans_option', '3_months', 1144000, true),
    ('tout_canal', 'sans_option', '1_month', 383540, true)
    ON CONFLICT (package_id, language_option_id, duration_id) DO NOTHING;

-- ACCESS avec SANS OPTION
INSERT INTO subscription_prices (package_id, language_option_id, duration_id, price_gnf, is_active)
VALUES
    ('access', 'sans_option', '1_month', 83540, true),
    ('access', 'sans_option', '3_months', 250620, true)
    ON CONFLICT (package_id, language_option_id, duration_id) DO NOTHING;

-- EVASION avec ENGLISH
INSERT INTO subscription_prices (package_id, language_option_id, duration_id, price_gnf, is_active)
VALUES
    ('evasion', 'english', '3_months', 990000, false),
    ('evasion', 'english', '1_month', 330000, true)
    ON CONFLICT (package_id, language_option_id, duration_id) DO NOTHING;

-- TOUT CANAL avec CHARME
INSERT INTO subscription_prices (package_id, language_option_id, duration_id, price_gnf, is_active)
VALUES
    ('tout_canal', 'charme', '1_month', 380000, false),
    ('tout_canal', 'charme', '3_months', 1140000, false)
    ON CONFLICT (package_id, language_option_id, duration_id) DO NOTHING;

-- TOUT CANAL avec ENGLISH
INSERT INTO subscription_prices (package_id, language_option_id, duration_id, price_gnf, is_active)
VALUES
    ('tout_canal', 'english', '1_month', 410000, false),
    ('tout_canal', 'english', '3_months', 1230000, false)
    ON CONFLICT (package_id, language_option_id, duration_id) DO NOTHING;

-- ACCESS PLUS avec CHARME
INSERT INTO subscription_prices (package_id, language_option_id, duration_id, price_gnf, is_active)
VALUES
    ('access_plus', 'charme', '1_month', 315000, true),
    ('access_plus', 'charme', '3_months', 945000, true)
    ON CONFLICT (package_id, language_option_id, duration_id) DO NOTHING;

-- ACCESS PLUS avec ENGLISH
INSERT INTO subscription_prices (package_id, language_option_id, duration_id, price_gnf, is_active)
VALUES
    ('access_plus', 'english', '1_month', 250000, true),
    ('access_plus', 'english', '3_months', 750000, true)
    ON CONFLICT (package_id, language_option_id, duration_id) DO NOTHING;

-- EVASION avec CHARME
INSERT INTO subscription_prices (package_id, language_option_id, duration_id, price_gnf, is_active)
VALUES
    ('evasion', 'charme', '1_month', 255000, true),
    ('evasion', 'charme', '3_months', 765000, true)
    ON CONFLICT (package_id, language_option_id, duration_id) DO NOTHING;