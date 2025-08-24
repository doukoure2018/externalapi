CREATE TABLE accesscanal (
                             id SERIAL PRIMARY KEY,

                             username VARCHAR(14),
                             password VARCHAR(50),
                             createdAt DATE,
                             start_date DATE,
                             end_date DATE
);