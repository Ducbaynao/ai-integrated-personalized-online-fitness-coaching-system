-- Forward Migration V59: Support case sequence for collision-free case numbers
SET search_path TO fitness, public;

CREATE SEQUENCE IF NOT EXISTS support_case_number_seq START WITH 100001;

-- If existing numeric case numbers exist in support_cases (e.g. SUP-100005), advance sequence past maximum
DO $$
DECLARE
    max_val bigint;
BEGIN
    SELECT COALESCE(MAX(NULLIF(regexp_replace(case_number, '^SUP-', ''), '')::bigint), 100000)
    INTO max_val
    FROM support_cases
    WHERE case_number ~ '^SUP-[0-9]+$';

    IF max_val >= 100001 THEN
        PERFORM setval('fitness.support_case_number_seq', max_val);
    END IF;
EXCEPTION
    WHEN OTHERS THEN
        NULL;
END $$;
