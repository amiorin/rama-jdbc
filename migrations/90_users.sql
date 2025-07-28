-- +migrate Up
CREATE TABLE IF NOT EXISTS users_records (
    offset_id BIGSERIAL PRIMARY KEY,
    new_id UUID,
    old_id UUID,
    operation_type VARCHAR(1) NOT NULL, -- 'I', 'U', 'D'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- +migrate StatementBegin
CREATE OR REPLACE FUNCTION users_records_fn()
RETURNS TRIGGER AS $BODY$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        INSERT INTO users_records (new_id, operation_type)
        VALUES (NEW.id, 'I');
        RETURN NEW;
    ELSIF (TG_OP = 'UPDATE') THEN
        INSERT INTO users_records (new_id, old_id, operation_type)
        VALUES (NEW.id, OLD.id, 'U');
        RETURN NEW;
    ELSIF (TG_OP = 'DELETE') THEN
        INSERT INTO users_records (old_id, operation_type)
        VALUES (OLD.id, 'D');
        RETURN OLD;
    END IF;
    RETURN NULL; -- Result is ignored for AFTER triggers
END;
$BODY$ LANGUAGE plpgsql;
-- +migrate StatementEnd

CREATE OR REPLACE TRIGGER users_records_trigger
AFTER INSERT OR UPDATE OR DELETE ON "users"
FOR EACH ROW
EXECUTE FUNCTION users_records_fn();

-- +migrate Down
DROP TRIGGER IF EXISTS users_records_trigger ON "users";
DROP FUNCTION IF EXISTS users_records_fn;
DROP TABLE IF EXISTS users_records;
