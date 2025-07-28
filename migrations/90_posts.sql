-- +migrate Up
CREATE TABLE IF NOT EXISTS posts_records (
    offset_id BIGSERIAL PRIMARY KEY,
    new_id UUID,
    old_id UUID,
    operation_type VARCHAR(1) NOT NULL, -- 'I', 'U', 'D'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- +migrate StatementBegin
CREATE OR REPLACE FUNCTION posts_records_fn()
RETURNS TRIGGER AS $BODY$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        INSERT INTO posts_records (new_id, operation_type)
        VALUES (NEW.id, 'I');
        RETURN NEW;
    ELSIF (TG_OP = 'UPDATE') THEN
        INSERT INTO posts_records (new_id, old_id, operation_type)
        VALUES (NEW.id, OLD.id, 'U');
        RETURN NEW;
    ELSIF (TG_OP = 'DELETE') THEN
        INSERT INTO posts_records (old_id, operation_type)
        VALUES (OLD.id, 'D');
        RETURN OLD;
    END IF;
    RETURN NULL; -- Result is ignored for AFTER triggers
END;
$BODY$ LANGUAGE plpgsql;
-- +migrate StatementEnd

CREATE OR REPLACE TRIGGER posts_records_trigger
AFTER INSERT OR UPDATE OR DELETE ON "posts"
FOR EACH ROW
EXECUTE FUNCTION posts_records_fn();

-- +migrate Down
DROP TRIGGER IF EXISTS posts_records_trigger ON "posts";
DROP FUNCTION IF EXISTS posts_records_fn;
DROP TABLE IF EXISTS posts_records;
