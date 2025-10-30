-- Ajouter la colonne phone à la table transactions
ALTER TABLE public.transactions
    ADD COLUMN phone_number character varying(20) COLLATE pg_catalog."default";

-- Ajouter un commentaire pour documenter la colonne
COMMENT ON COLUMN public.transactions.phone_number
IS 'Numéro de téléphone du client associé à la transaction';

-- Créer un index pour améliorer les performances des recherches par téléphone
CREATE INDEX IF NOT EXISTS idx_transactions_phone_number
    ON public.transactions USING btree
    (phone_number COLLATE pg_catalog."default" ASC NULLS LAST)
    TABLESPACE pg_default;
