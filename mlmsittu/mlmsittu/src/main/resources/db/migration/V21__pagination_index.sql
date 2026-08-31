-- The index that makes keyset pagination actually seek (P7-08).
--
-- Found by EXPLAIN against 53,000 items, which is the point of doing the query pass against
-- realistic volume rather than a development database holding twenty rows. Without this index the
-- paginated catalogue was a sequential scan plus a top-N sort — 78 ms to return eleven rows, and
-- getting worse with every item added.
--
-- The column order matters and matches the query exactly: ORDER BY name, id, and a keyset
-- predicate on the same pair. An index on (name) alone would still have to sort ties by id.
CREATE INDEX idx_item_name_id ON item (name, id);
