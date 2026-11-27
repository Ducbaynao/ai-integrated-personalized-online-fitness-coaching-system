-- Forward migration V68: Diacritic-folded lexical search vector for Vietnamese/English hybrid retrieval.
-- Preserves original chunk content/heading_path for exact citation grounding while indexing folded tokens (incl. đ/Đ -> d/D).
CREATE OR REPLACE FUNCTION fitness.fold_vi_text(input text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT translate(
        regexp_replace(
            normalize(coalesce(input, ''), NFD),
            E'[\u0300-\u036f]',
            '',
            'g'
        ),
        'đĐ',
        'dD'
    );
$$;

ALTER TABLE fitness.knowledge_chunks
    ADD COLUMN search_vector_folded tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple'::regconfig, fitness.fold_vi_text(coalesce(heading_path, ''))), 'A') ||
        setweight(to_tsvector('simple'::regconfig, fitness.fold_vi_text(coalesce(content, ''))), 'B')
    ) STORED;

CREATE INDEX idx_knowledge_chunks_lexical_folded
    ON fitness.knowledge_chunks USING gin (search_vector_folded);
