-- Green provenance on every request row (v3 lot C.5).
--
-- Until now a green report could only be read against the registry as it stood
-- when the report ran. Edit one coefficient or one provider region and every
-- historical row silently changed meaning: the stored gCO2 stayed put while the
-- explanation of it moved. These columns make a row self-describing —
--
--   grams_co2 = energy_kwh * grid_intensity_g_per_kwh
--
-- is checkable on the row alone, and the labels say what kind of estimate it was.
--
-- Why more than the three columns lot C planned:
--   provider           -> emissions broken down BY PROVIDER without resolving a
--                         model id against the current registry.
--   region_provenance  -> an export can say which regions were ASSUMED rather
--                         than known, on its face, for the data it is showing.
--   grid_zone_source   -> which step of the C.3 chain supplied the zone.
--   dispatch_zone      -> the zone carbon-aware dispatch chose. When it is set and
--                         grid_zone_source is not DISPATCH, it was RECORDED AND
--                         NOT APPLIED: deferring a job does not move a hosted
--                         API's compute. That distinction was promised in C.3 and
--                         this is where it lands.
--   pue                -> the datacenter overhead already inside energy_kwh.
--
-- Every column is nullable, and rows written before this migration keep NULLs:
-- they come back as GreenProvenance.UNKNOWN rather than as a fabricated
-- attribution. What is deliberately NOT stored is the coefficient set itself —
-- energy_kwh is the output of the model in force at the time, so history is
-- immutable, but re-deriving kWh from the token counts would need a per-row
-- snapshot of prefill/decode/fixed. See docs/technical/green-accounting.md.

ALTER TABLE request_log
    ADD COLUMN IF NOT EXISTS provider                 varchar(255),
    ADD COLUMN IF NOT EXISTS grid_zone                varchar(64),
    ADD COLUMN IF NOT EXISTS grid_intensity_g_per_kwh float8,
    ADD COLUMN IF NOT EXISTS grid_zone_source         varchar(32),
    ADD COLUMN IF NOT EXISTS dispatch_zone            varchar(64),
    ADD COLUMN IF NOT EXISTS region_provenance        varchar(16),
    ADD COLUMN IF NOT EXISTS energy_source            varchar(32),
    ADD COLUMN IF NOT EXISTS pue                      float8;

-- Reports group by zone and by provider over a time window; the timestamp index
-- from V1 already narrows the scan, so these two only help the grouping itself.
CREATE INDEX IF NOT EXISTS idx_request_log_grid_zone ON request_log (grid_zone);
CREATE INDEX IF NOT EXISTS idx_request_log_provider ON request_log (provider);
