ALTER TABLE conversations
  ALTER COLUMN ai_mode SET DEFAULT 'SUGGEST_ONLY';

UPDATE conversations
SET ai_mode = 'SUGGEST_ONLY'
WHERE ai_mode = 'AUTO';

