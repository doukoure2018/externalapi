-- =====================================================
-- V6: Ajout de la contrainte de clé étrangère vers accesscanal
-- =====================================================

-- Vérifier si la table accesscanal existe avant d'ajouter la contrainte
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_name = 'accesscanal'
    ) THEN
        -- Ajouter la contrainte seulement si elle n'existe pas déjà
        IF NOT EXISTS (
            SELECT 1
            FROM information_schema.table_constraints
            WHERE constraint_name = 'fk_transactions_canal_username'
        ) THEN
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_canal_username
        FOREIGN KEY (canal_username)
            REFERENCES accesscanal(username);

RAISE NOTICE 'Contrainte fk_transactions_canal_username ajoutée avec succès';
ELSE
            RAISE NOTICE 'La contrainte fk_transactions_canal_username existe déjà';
END IF;
ELSE
        RAISE WARNING 'La table accesscanal n''existe pas. La contrainte de clé étrangère ne sera pas créée.';
        RAISE WARNING 'Assurez-vous de créer la table accesscanal avec une colonne username avant d''exécuter cette migration.';
END IF;
END $$;