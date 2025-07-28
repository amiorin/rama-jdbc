-- :name get-max-offset-id :? :1
-- :doc Retrieves the maximum offset_id (exclusive) from the users_records table.
SELECT (COALESCE(MAX(offset_id), 0) + 1) AS max_offset_id FROM users_records;

-- :name get-min-offset-id :? :1
-- :doc Retrieves the minimum offset_id (inclusive) from the users_records table.
SELECT COALESCE(MIN(offset_id), 1) AS min_offset_id FROM users_records;

-- :name get-records :? :*
-- :doc Selects a join of users and users_records, starting from a given offset_id.
SELECT
    src.*,
    recs.offset_id AS recs_offset_id,
    recs.new_id AS recs_new_id,
    recs.old_id AS recs_old_id,
    recs.operation_type AS recs_operation_type,
    recs.created_at AS recs_created_at
FROM
    users_records AS recs
LEFT JOIN
    "users" AS src ON src.id = recs.new_id
WHERE
    recs.offset_id >= :start-offset
    AND recs.offset_id < :end-offset
ORDER BY
    recs.offset_id

-- :name insert-user-record! :<!
-- :doc Inserts a new record into the users_records table.
INSERT INTO users_records (
  offset_id,
  new_id,
  old_id,
  operation_type
) VALUES (
  :offset-id,
  :new-id,
  :old-id,
  :operation-type
);

-- :name delete-all-users-records! :<!
-- :doc Deletes all records from the users_records table.
DELETE FROM users_records;
SELECT setval(
    pg_get_serial_sequence('users_records', 'offset_id'),
    1,
    false
);

-- :name insert-user! :<!
-- :doc Inserts a new record into the users table.
INSERT INTO users (
  id,
  user_id,
  friends
) VALUES (
  :id,
  :user-id,
  :friends
);

-- :name update-user! :<!
-- :doc Updates an existing record in the users table.
UPDATE users
SET
  id = :new-id,
  user_id = :user-id,
  friends = :friends
WHERE
  id = :old-id;

-- :name delete-user! :<!
-- :doc Deletes a record from the users table.
DELETE FROM users
WHERE
  id = :id;

-- :name delete-all-user! :<!
-- :doc Deletes all records from the users table.
DELETE FROM users;
