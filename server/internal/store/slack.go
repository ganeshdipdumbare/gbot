package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

// SlackInstall is one connected Slack workspace. BotToken and SigningKey
// are AES-GCM ciphertext, like vault secrets: never send either to a
// client or put one in a model prompt.
type SlackInstall struct {
	ID         string    `json:"id"`
	SpaceID    string    `json:"-"`
	OwnerID    string    `json:"-"`
	TeamID     string    `json:"teamId"`
	TeamName   string    `json:"teamName"`
	BotToken   []byte    `json:"-"`
	SigningKey []byte    `json:"-"`
	CreatedAt  time.Time `json:"createdAt"`
}

// SlackLink points one channel at one bot.
type SlackLink struct {
	InstallID string    `json:"installId"`
	ChannelID string    `json:"channelId"`
	Channel   string    `json:"channel"`
	BotID     string    `json:"botId"`
	CreatedAt time.Time `json:"createdAt"`
}

const slackCols = `id, space_id, owner_id, team_id, team_name, bot_token, signing_key, created_at`

func scanSlack(row pgx.Row) (*SlackInstall, error) {
	s := &SlackInstall{}
	if err := row.Scan(&s.ID, &s.SpaceID, &s.OwnerID, &s.TeamID, &s.TeamName, &s.BotToken, &s.SigningKey, &s.CreatedAt); err != nil {
		return nil, notFound(err)
	}
	return s, nil
}

func (s *Store) ListSlackInstalls(ctx context.Context, spaceID string) ([]*SlackInstall, error) {
	rows, err := s.DB.Query(ctx, `SELECT `+slackCols+` FROM slack_installs WHERE space_id=$1 ORDER BY created_at`, spaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*SlackInstall
	for rows.Next() {
		in, err := scanSlack(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, in)
	}
	return out, rows.Err()
}

// SlackInstallByTeam finds the workspace an inbound request belongs to.
// It is the only lookup that is not scoped to a space, because an inbound
// request carries no session: the signature is what proves it.
func (s *Store) SlackInstallByTeam(ctx context.Context, teamID string) (*SlackInstall, error) {
	return scanSlack(s.DB.QueryRow(ctx, `SELECT `+slackCols+` FROM slack_installs WHERE team_id=$1`, teamID))
}

// SaveSlackInstall connects a workspace, or replaces the secrets of one
// already connected to this space.
func (s *Store) SaveSlackInstall(ctx context.Context, in *SlackInstall) (*SlackInstall, error) {
	return scanSlack(s.DB.QueryRow(ctx, `INSERT INTO slack_installs(space_id,owner_id,team_id,team_name,bot_token,signing_key)
		VALUES($1,$2,$3,$4,$5,$6)
		ON CONFLICT (team_id) DO UPDATE SET
			team_name=EXCLUDED.team_name, bot_token=EXCLUDED.bot_token, signing_key=EXCLUDED.signing_key
		WHERE slack_installs.space_id=EXCLUDED.space_id
		RETURNING `+slackCols,
		in.SpaceID, in.OwnerID, in.TeamID, in.TeamName, in.BotToken, in.SigningKey))
}

// SlackInstallByID is used to post a reply, after the inbound request
// that created the pending reply was already verified.
func (s *Store) SlackInstallByID(ctx context.Context, id string) (*SlackInstall, error) {
	return scanSlack(s.DB.QueryRow(ctx, `SELECT `+slackCols+` FROM slack_installs WHERE id=$1`, id))
}

// SlackInstallInSpace is the settings lookup: a space only sees its own.
func (s *Store) SlackInstallInSpace(ctx context.Context, spaceID, id string) (*SlackInstall, error) {
	return scanSlack(s.DB.QueryRow(ctx, `SELECT `+slackCols+` FROM slack_installs WHERE id=$1 AND space_id=$2`, id, spaceID))
}

func (s *Store) DeleteSlackInstall(ctx context.Context, spaceID, id string) error {
	t, err := s.DB.Exec(ctx, `DELETE FROM slack_installs WHERE id=$1 AND space_id=$2`, id, spaceID)
	if err == nil && t.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}

func (s *Store) ListSlackLinks(ctx context.Context, installID string) ([]*SlackLink, error) {
	rows, err := s.DB.Query(ctx, `SELECT install_id, channel_id, channel, bot_id, created_at
		FROM slack_links WHERE install_id=$1 ORDER BY channel`, installID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*SlackLink
	for rows.Next() {
		l := &SlackLink{}
		if err := rows.Scan(&l.InstallID, &l.ChannelID, &l.Channel, &l.BotID, &l.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

// SlackLinkFor is the routing step for an inbound message: which bot, if
// any, answers in this channel.
func (s *Store) SlackLinkFor(ctx context.Context, installID, channelID string) (*SlackLink, error) {
	l := &SlackLink{}
	err := s.DB.QueryRow(ctx, `SELECT install_id, channel_id, channel, bot_id, created_at
		FROM slack_links WHERE install_id=$1 AND channel_id=$2`, installID, channelID).
		Scan(&l.InstallID, &l.ChannelID, &l.Channel, &l.BotID, &l.CreatedAt)
	if err != nil {
		return nil, notFound(err)
	}
	return l, nil
}

func (s *Store) SetSlackLink(ctx context.Context, l *SlackLink) error {
	_, err := s.DB.Exec(ctx, `INSERT INTO slack_links(install_id,channel_id,channel,bot_id)
		VALUES($1,$2,$3,$4)
		ON CONFLICT (install_id,channel_id) DO UPDATE SET channel=EXCLUDED.channel, bot_id=EXCLUDED.bot_id`,
		l.InstallID, l.ChannelID, l.Channel, l.BotID)
	return err
}

func (s *Store) DeleteSlackLink(ctx context.Context, installID, channelID string) error {
	t, err := s.DB.Exec(ctx, `DELETE FROM slack_links WHERE install_id=$1 AND channel_id=$2`, installID, channelID)
	if err == nil && t.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}
