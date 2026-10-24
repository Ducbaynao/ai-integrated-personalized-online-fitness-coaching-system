SET search_path TO fitness, public;

-- A durable cutover distinguishes pre-existing completion backfill from newly committed
-- completions even when the scheduler first runs much later than this migration.
CREATE TABLE attention_rule_activations (
    rule_code varchar(100) PRIMARY KEY,
    activated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO attention_rule_activations(rule_code) VALUES ('RETURN_AFTER_100_DAYS_V1');

CREATE TRIGGER attention_rule_activations_append_only
    BEFORE UPDATE OR DELETE ON attention_rule_activations
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
