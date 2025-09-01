-- =====================================================
-- V4: Création des tables Canal+ (sans user_id)
-- =====================================================

-- Table des décodeurs
CREATE TABLE IF NOT EXISTS decoders (
                                        id SERIAL PRIMARY KEY,
                                        decoder_number VARCHAR(14) UNIQUE NOT NULL,
    status VARCHAR(20) DEFAULT 'active', -- active, inactive, blocked
    installation_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Table des packages
CREATE TABLE IF NOT EXISTS packages (
                                        id VARCHAR(50) PRIMARY KEY, -- 'tout_canal', 'evasion', 'access', 'access_plus'
    name VARCHAR(100) NOT NULL, -- 'TOUT CANAL+', 'EVASION', 'ACCESS', 'ACCESS+'
    display_name VARCHAR(100) NOT NULL,
    description TEXT,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Table des options linguistiques
CREATE TABLE IF NOT EXISTS language_options (
                                                id VARCHAR(50) PRIMARY KEY, -- 'charme', 'english_basic', 'english_plus'
    name VARCHAR(100) NOT NULL, -- 'CHARME', 'ENGLISH BASIC', 'ENGLISH PLUS'
    display_name VARCHAR(100) NOT NULL, -- peut contenir \n pour affichage
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Table des durées d'abonnement
CREATE TABLE IF NOT EXISTS subscription_durations (
                                                      id VARCHAR(50) PRIMARY KEY, -- '1_month', '3_months'
    name VARCHAR(100) NOT NULL, -- '1 Mois', '3 Mois'
    months INTEGER NOT NULL, -- 1, 3
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Table des prix d'abonnement
CREATE TABLE IF NOT EXISTS subscription_prices (
                                                   id SERIAL PRIMARY KEY,
                                                   package_id VARCHAR(50) REFERENCES packages(id),
    language_option_id VARCHAR(50) REFERENCES language_options(id),
    duration_id VARCHAR(50) REFERENCES subscription_durations(id),
    price_gnf INTEGER NOT NULL, -- prix en francs guinéens
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(package_id, language_option_id, duration_id)
    );

-- Table des transactions (SANS user_id dès le départ)
CREATE TABLE IF NOT EXISTS transactions (
                                            id SERIAL PRIMARY KEY,
                                            decoder_number VARCHAR(14) REFERENCES decoders(decoder_number),
    package_id VARCHAR(50) REFERENCES packages(id),
    language_option_id VARCHAR(50) REFERENCES language_options(id),
    duration_id VARCHAR(50) REFERENCES subscription_durations(id),
    amount_gnf INTEGER NOT NULL,
    transaction_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) DEFAULT 'completed', -- pending, completed, failed
    payment_method VARCHAR(50), -- mobile_money, bank_transfer, etc.
    reference_number VARCHAR(100),
    subscription_start_date DATE,
    subscription_end_date DATE,
    canal_username VARCHAR(14),
    processing_duration_ms INTEGER, -- Durée du traitement en ms
    error_message TEXT -- Message d'erreur si échec
    );

-- Table des décodeurs favoris (SANS user_id dès le départ)
CREATE TABLE IF NOT EXISTS user_favorite_decoders (
                                                      id SERIAL PRIMARY KEY,
                                                      decoder_number VARCHAR(14) REFERENCES decoders(decoder_number),
    phone_number VARCHAR(20),
    client_name VARCHAR(255),
    search_type VARCHAR(20) DEFAULT 'DECODEUR', -- 'TELEPHONE' ou 'DECODEUR'
    display_label VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Création des index
CREATE INDEX IF NOT EXISTS idx_subscription_prices_lookup ON subscription_prices(package_id, language_option_id, duration_id);
CREATE INDEX IF NOT EXISTS idx_transactions_decoder ON transactions(decoder_number);
CREATE INDEX IF NOT EXISTS idx_transactions_date ON transactions(transaction_date);
CREATE INDEX IF NOT EXISTS idx_transactions_canal_username ON transactions(canal_username);
CREATE INDEX IF NOT EXISTS idx_favorites_phone ON user_favorite_decoders(phone_number);
CREATE INDEX IF NOT EXISTS idx_favorites_search_type ON user_favorite_decoders(search_type);

-- Note: La contrainte de clé étrangère vers accesscanal sera ajoutée dans une migration ultérieure
-- après vérification que la table accesscanal existe