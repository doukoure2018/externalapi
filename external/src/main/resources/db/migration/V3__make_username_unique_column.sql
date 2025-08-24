-- 1. Rendre username unique dans accesscanal
ALTER TABLE accesscanal
    ADD CONSTRAINT uk_accesscanal_username UNIQUE (username);

-- 2. Ajouter la colonne last_used_at pour la rotation
ALTER TABLE accesscanal
    ADD COLUMN last_used_at TIMESTAMP DEFAULT NULL;

-- 3. Initialiser last_used_at pour les comptes existants
UPDATE accesscanal
SET last_used_at = CURRENT_TIMESTAMP - (INTERVAL '1 hour' * id)
WHERE last_used_at IS NULL;

-- 4. Ajouter un index pour optimiser les requêtes
CREATE INDEX idx_accesscanal_last_used ON accesscanal(last_used_at);