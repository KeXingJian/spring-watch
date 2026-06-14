ALTER TABLE alert_rule
    ADD COLUMN level VARCHAR(16) NOT NULL DEFAULT 'warning';
