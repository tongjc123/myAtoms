-- One lock shared by every reservation; per-run locks cannot enforce a deployment ceiling.
CREATE TABLE provider_budget_lock (
    id INT NOT NULL PRIMARY KEY,
    CONSTRAINT ck_single_budget_lock CHECK (id = 1)
) ENGINE=InnoDB;
INSERT INTO provider_budget_lock (id) VALUES (1);

-- A failed local operation does not prove a submitted request was free.
-- Retain all historical reservations conservatively until billing is reconciled.
ALTER TABLE provider_spend
    ADD COLUMN submitted TINYINT(1) NOT NULL DEFAULT 1 AFTER state;
