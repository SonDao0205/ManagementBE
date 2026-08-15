-- Allow the customer-profile compliance gate introduced for AI responses.
ALTER TABLE ai_quality_checks
  DROP CONSTRAINT IF EXISTS ck_ai_quality_checks_type;

ALTER TABLE ai_quality_checks
  ADD CONSTRAINT ck_ai_quality_checks_type CHECK (
    check_type IN (
      'HALLUCINATION',
      'SHOP_POLICY',
      'TONE',
      'PII',
      'SAFETY',
      'LANGUAGE',
      'ORDER_FACTS',
      'DUPLICATE',
      'PROFILE_COMPLIANCE',
      'OVERALL'
    )
  );
