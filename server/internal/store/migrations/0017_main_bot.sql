-- The person's main bot: the one that looks after the others. It is first
-- in the roster, wears the app's mark, and can't be archived. New accounts
-- are seeded with one; spaces made before this keep the bots they have.
ALTER TABLE bots ADD COLUMN main boolean NOT NULL DEFAULT false;

-- At most one main bot per space, so "the main bot" is never ambiguous.
CREATE UNIQUE INDEX bots_one_main_per_space ON bots (space_id) WHERE main AND archived_at IS NULL;
