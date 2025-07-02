CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    name VARCHAR(10),
    age INT
);

CREATE TABLE users_cdc (
    change_id SERIAL PRIMARY KEY,
    operation VARCHAR(10),
    old_data JSONB,
    new_data JSONB,
    change_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE OR REPLACE FUNCTION capture_changes()
RETURNS TRIGGER AS $$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        INSERT INTO users_cdc (operation, new_data, change_timestamp)
        VALUES ('INSERT', row_to_json(NEW)::JSONB, CURRENT_TIMESTAMP);
    ELSIF (TG_OP = 'UPDATE') THEN
        INSERT INTO users_cdc (operation, old_data, new_data, change_timestamp)
        VALUES ('UPDATE', row_to_json(OLD)::JSONB, row_to_json(NEW)::JSONB, CURRENT_TIMESTAMP);
    ELSIF (TG_OP = 'DELETE') THEN
        INSERT INTO users_cdc (operation, old_data, change_timestamp)
        VALUES ('DELETE', row_to_json(OLD)::JSONB, CURRENT_TIMESTAMP);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_audit_trigger
AFTER INSERT OR UPDATE OR DELETE ON users
FOR EACH ROW EXECUTE FUNCTION capture_changes();

INSERT INTO users (name, age)
VALUES
 ('id', 8),
 ('zh', 6),
 ('ko', 5),
 ('ms', 5),
 ('fa', 3),
 ('ca', 2),
 ('pl', 2),
 ('my', 1);
