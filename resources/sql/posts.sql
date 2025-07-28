-- :name get-max-offset-id :? :1
-- :doc Retrieves the maximum offset_id (exclusive) from the posts_records table.
SELECT (COALESCE(MAX(offset_id), 0) + 1) AS max_offset_id FROM posts_records;

-- :name get-min-offset-id :? :1
-- :doc Retrieves the minimum offset_id (inclusive) from the posts_records table.
SELECT COALESCE(MIN(offset_id), 1) AS min_offset_id FROM posts_records;

-- :name get-records :? :*
-- :doc Selects a join of posts and posts_records, starting from a given offset_id.
SELECT
    src.*,
    recs.offset_id AS recs_offset_id,
    recs.new_id AS recs_new_id,
    recs.old_id AS recs_old_id,
    recs.operation_type AS recs_operation_type,
    recs.created_at AS recs_created_at
FROM
    posts_records AS recs
LEFT JOIN
    "posts" AS src ON src.id = recs.new_id
WHERE
    recs.offset_id >= :start-offset
    AND recs.offset_id < :end-offset
ORDER BY
    recs.offset_id
