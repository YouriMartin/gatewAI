-- Split pricing and an explicit currency (v4 B.6).
--
-- The two cost columns were named for a currency they never enforced: they held
-- whatever cost-per-1k-tokens was declared in. They keep their values and lose
-- the suffix, and each row now says what its costs are in and how they were
-- priced.
--
--   cached_prompt_tokens  prompt tokens the provider served from its prompt
--                         cache, a part of prompt_tokens. NULL when the provider
--                         did not report them, which is not the same as zero.
--   pricing_basis         SPLIT (input/output/cached-input rates per million),
--                         BLENDED (the deprecated cost-per-1k-tokens) or NONE (no
--                         price declared: the cost is zero because it is not
--                         priced).
--   currency              ISO 4217 code of cost and cost_avoided
--                         (gatewai.billing.currency).
--
-- Rows written before this migration read BLENDED and EUR: that is what they
-- were, and they are never back-dated to a split price.

ALTER TABLE request_log RENAME COLUMN cost_eur TO cost;
ALTER TABLE request_log RENAME COLUMN cost_avoided_eur TO cost_avoided;

ALTER TABLE request_log
    ADD COLUMN IF NOT EXISTS cached_prompt_tokens integer,
    ADD COLUMN IF NOT EXISTS pricing_basis varchar(16) NOT NULL DEFAULT 'BLENDED',
    ADD COLUMN IF NOT EXISTS currency varchar(3) NOT NULL DEFAULT 'EUR';

-- The defaults only describe the rows above; every new row states its own.
ALTER TABLE request_log ALTER COLUMN pricing_basis DROP DEFAULT;
ALTER TABLE request_log ALTER COLUMN currency DROP DEFAULT;
