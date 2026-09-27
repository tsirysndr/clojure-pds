CREATE TABLE account_preferences (
    did text PRIMARY KEY REFERENCES accounts(did) ON DELETE CASCADE,
    preferences jsonb NOT NULL CHECK (jsonb_typeof(preferences) = 'array' AND jsonb_array_length(preferences) <= 1000)
);
