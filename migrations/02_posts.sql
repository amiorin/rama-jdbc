-- +migrate Up
CREATE TABLE IF NOT EXISTS posts (
    id BIGSERIAL PRIMARY KEY
);

-- +migrate Down
DROP TABLE IF EXISTS posts;
