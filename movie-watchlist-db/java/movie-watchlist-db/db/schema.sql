-- Movie Watchlist DB - schema for Postgres / Supabase.
-- Run this yourself in the Supabase SQL editor (Project -> SQL Editor -> New query).
-- Idempotent: safe to re-run.

-- Our own small movie catalog. A movie must exist here before it can be added to a watch list.
CREATE TABLE IF NOT EXISTS movies (
    id           UUID PRIMARY KEY,
    title        TEXT NOT NULL,
    release_year INT,
    director     TEXT NOT NULL DEFAULT '',
    actors       TEXT[] NOT NULL DEFAULT '{}',
    genres       TEXT[] NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE movies ADD COLUMN IF NOT EXISTS director TEXT NOT NULL DEFAULT '';
ALTER TABLE movies ADD COLUMN IF NOT EXISTS actors TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE movies ADD COLUMN IF NOT EXISTS genres TEXT[] NOT NULL DEFAULT '{}';

-- GIN indexes array elements so genre containment queries can avoid scanning every movie row.
CREATE INDEX IF NOT EXISTS idx_movies_genres ON movies USING GIN (genres);

CREATE TABLE IF NOT EXISTS watchlists (
    id         UUID PRIMARY KEY,
    owner_id   UUID,
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Movies-in-a-list as a JOIN TABLE (not a JSON array on watchlists). This is the design that scales:
--   * Composite PK (watchlist_id, movie_id) makes duplicates impossible and dedup an index lookup.
--   * Add is atomic via INSERT ... ON CONFLICT DO NOTHING (no read-modify-write, no lost update).
--   * Remove is a targeted, indexed DELETE - O(index), never a scan of the whole list.
--   * A list with tens of thousands of movies costs the same per-op as a tiny one.
CREATE TABLE IF NOT EXISTS watchlist_movies (
    watchlist_id UUID NOT NULL REFERENCES watchlists(id) ON DELETE CASCADE,
    movie_id     UUID NOT NULL REFERENCES movies(id),
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (watchlist_id, movie_id)
);

-- The composite PK already indexes (watchlist_id, movie_id) left-to-right, covering
-- "does this list contain this movie" and "list all movies in this list".
-- Add the reverse index only if you also query "which lists contain movie X".
CREATE INDEX IF NOT EXISTS idx_watchlist_movies_movie ON watchlist_movies (movie_id);
