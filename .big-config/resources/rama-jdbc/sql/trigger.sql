-- +migrate Up
CREATE TABLE IF NOT EXISTS {{table-name}}_records (
    offset_id BIGSERIAL PRIMARY KEY,
    new_id {{primary-key-type}},
    old_id {{primary-key-type}},
    operation_type VARCHAR(1) NOT NULL, -- 'I', 'U', 'D'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- +migrate StatementBegin
CREATE OR REPLACE FUNCTION {{table-name}}_records_fn()
RETURNS TRIGGER AS $BODY$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        INSERT INTO {{table-name}}_records (new_id, operation_type)
        VALUES (NEW.id, 'I');
        RETURN NEW;
    ELSIF (TG_OP = 'UPDATE') THEN
        INSERT INTO {{table-name}}_records (new_id, old_id, operation_type)
        VALUES (NEW.id, OLD.id, 'U');
        RETURN NEW;
    ELSIF (TG_OP = 'DELETE') THEN
        INSERT INTO {{table-name}}_records (old_id, operation_type)
        VALUES (OLD.id, 'D');
        RETURN OLD;
    END IF;
    RETURN NULL; -- Result is ignored for AFTER triggers
END;
$BODY$ LANGUAGE plpgsql;
-- +migrate StatementEnd

CREATE OR REPLACE TRIGGER {{table-name}}_records_trigger
AFTER INSERT OR UPDATE OR DELETE ON "{{table-name}}"
FOR EACH ROW
EXECUTE FUNCTION {{table-name}}_records_fn();

-- +migrate Down
DROP TRIGGER IF EXISTS {{table-name}}_records_trigger ON "{{table-name}}";
DROP FUNCTION IF EXISTS {{table-name}}_records_fn;
DROP TABLE IF EXISTS {{table-name}}_records;
