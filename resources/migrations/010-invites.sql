ALTER TABLE accounts ADD COLUMN invites_disabled boolean NOT NULL DEFAULT false;
ALTER TABLE accounts ADD COLUMN invite_note text;
CREATE TABLE invite_codes (
  code text PRIMARY KEY,
  available integer NOT NULL CHECK (available > 0),
  disabled boolean NOT NULL DEFAULT false,
  for_account text NOT NULL,
  created_by text NOT NULL DEFAULT 'admin',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX invite_codes_owner ON invite_codes(for_account);
CREATE TABLE invite_uses (
  code text NOT NULL REFERENCES invite_codes(code),
  used_by text NOT NULL UNIQUE REFERENCES accounts(did),
  used_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(code, used_by)
);
