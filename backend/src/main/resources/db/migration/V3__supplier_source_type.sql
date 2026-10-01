ALTER TABLE supplier ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'FARMER';
UPDATE supplier SET source_type='SELF' WHERE name='演示果园';
