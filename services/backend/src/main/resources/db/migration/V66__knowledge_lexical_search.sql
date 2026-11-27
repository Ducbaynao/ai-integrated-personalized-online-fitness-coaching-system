-- Stored generated vector follows chunk insert/update/delete without an async backfill worker.
-- The simple dictionary preserves Vietnamese/English terms; it is not BM25.
ALTER TABLE fitness.knowledge_chunks
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple'::regconfig, coalesce(heading_path, '')), 'A') ||
        setweight(to_tsvector('simple'::regconfig, coalesce(content, '')), 'B')
    ) STORED;

CREATE INDEX idx_knowledge_chunks_lexical
    ON fitness.knowledge_chunks USING gin (search_vector);
