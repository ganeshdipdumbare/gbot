-- Bots asking bots: the exchange cards are the record of every ask, so
-- they are looked up by round and by ask, and the open ones on start.
CREATE INDEX messages_botmsg_round ON messages ((data->>'round')) WHERE role = 'event' AND data->>'kind' = 'botmsg';
CREATE INDEX messages_botmsg_ask ON messages ((data->>'ask')) WHERE role = 'event' AND data->>'kind' = 'botmsg';
CREATE INDEX messages_botmsg_open ON messages (created_at) WHERE role = 'event' AND data->>'kind' = 'botmsg' AND data->>'status' IN ('sent', 'working');
