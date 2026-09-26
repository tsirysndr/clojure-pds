-- Stable, bounded owner pagination without sorting every historical session.
CREATE INDEX oauth_sessions_owner_page ON oauth_sessions(did, session_id COLLATE "C")
WHERE revoked_at IS NULL;
