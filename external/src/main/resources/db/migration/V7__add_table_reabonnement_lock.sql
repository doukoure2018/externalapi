--- migration for reabonnement lock
create table reabonnement_locks
(
    id             serial
        primary key,
    decoder_number varchar(50) not null,
    created_at     timestamp default CURRENT_TIMESTAMP,
    unique (decoder_number)
);