-- +migrate Up
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY NOT NULL,
    user_id TEXT NOT NULL,
    friends INTEGER [] NOT NULL DEFAULT array []::integer []
);

-- +migrate Down
DROP TABLE IF EXISTS users;
