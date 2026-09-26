-- index idx_app_user_status
CREATE INDEX idx_app_user_status ON app_user (status);
-- index idx_audit_log_time
CREATE INDEX idx_audit_log_time ON audit_log (occurred_at);
-- index idx_audit_log_user_time
CREATE INDEX idx_audit_log_user_time ON audit_log (user_id, occurred_at);
-- index idx_event_log_type
CREATE INDEX idx_event_log_type ON event_log (type);
-- index idx_event_log_user_time
CREATE INDEX idx_event_log_user_time ON event_log (user_id, occurred_at);
-- index idx_persona_definition_user
CREATE INDEX idx_persona_definition_user ON persona_definition (user_id);
-- index idx_recovery_code_user
CREATE INDEX idx_recovery_code_user ON recovery_code (user_id);
-- index idx_stock_master_chosung
CREATE INDEX idx_stock_master_chosung ON stock_master (chosung);
-- index idx_stock_master_isin
CREATE INDEX idx_stock_master_isin ON stock_master (isin);
-- index idx_stock_master_name
CREATE INDEX idx_stock_master_name ON stock_master (name);
-- index idx_user_session_user_expires
CREATE INDEX idx_user_session_user_expires ON user_session (user_id, expires_at);
-- index idx_watchlist_group_user
CREATE INDEX idx_watchlist_group_user ON watchlist_group (user_id);
-- index idx_watchlist_item_symbol
CREATE INDEX idx_watchlist_item_symbol ON watchlist_item (market, code);
-- index uq_credential_meta_kind_scope_user
CREATE UNIQUE INDEX uq_credential_meta_kind_scope_user ON credential_meta (kind, scope, user_id);
-- index uq_event_log_user_device_seq
CREATE UNIQUE INDEX uq_event_log_user_device_seq ON event_log (user_id, device_id, seq);
-- table app_user
CREATE TABLE app_user ( user_id TEXT NOT NULL PRIMARY KEY, role TEXT NOT NULL, display_name TEXT NOT NULL, password_hash TEXT NOT NULL, totp_enrolled_at TEXT, totp_last_counter INTEGER NOT NULL DEFAULT 0, failed_logins INTEGER NOT NULL DEFAULT 0, locked_until TEXT, status TEXT NOT NULL, toss_key_decision TEXT NOT NULL, extra_key_wrap INTEGER NOT NULL DEFAULT 0, auto_stop_on_logout INTEGER NOT NULL DEFAULT 0, email TEXT, slack_user_id TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL );
-- table audit_log
CREATE TABLE audit_log ( audit_id TEXT NOT NULL PRIMARY KEY, occurred_at TEXT NOT NULL, user_id TEXT, device_id TEXT, action TEXT NOT NULL, target TEXT, result TEXT NOT NULL, detail_json TEXT );
-- table credential_meta
CREATE TABLE credential_meta ( credential_id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, scope TEXT NOT NULL, user_id TEXT REFERENCES app_user (user_id) ON DELETE CASCADE, last4 TEXT, status TEXT NOT NULL, status_detail TEXT, verified_at TEXT, response_ms INTEGER, extra_json TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL );
-- table device
CREATE TABLE device ( device_id TEXT NOT NULL PRIMARY KEY, public_key TEXT NOT NULL, name TEXT NOT NULL, approved_by_device_id TEXT, registered_at TEXT NOT NULL, revoked_at TEXT );
-- table event_log
CREATE TABLE event_log ( event_no INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, device_id TEXT NOT NULL, seq INTEGER NOT NULL, occurred_at TEXT NOT NULL, type TEXT NOT NULL, payload_version INTEGER NOT NULL DEFAULT 1, payload_json TEXT NOT NULL, sync_scope TEXT NOT NULL, received_at TEXT );
-- table exchange_rate
CREATE TABLE exchange_rate ( fx_from TEXT NOT NULL, fx_to TEXT NOT NULL, fx_as_of TEXT NOT NULL, fx_rate TEXT NOT NULL, source TEXT NOT NULL, PRIMARY KEY (fx_from, fx_to, fx_as_of) );
-- table installation
CREATE TABLE installation ( installation_id TEXT NOT NULL PRIMARY KEY, setup_state TEXT NOT NULL, admin_user_id TEXT, llm_preset TEXT, last_public_ip TEXT, stock_master_synced_at TEXT, last_login_user_id TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL );
-- table market_calendar
CREATE TABLE market_calendar ( market TEXT NOT NULL, trading_date TEXT NOT NULL, sessions_json TEXT NOT NULL, is_holiday INTEGER NOT NULL DEFAULT 0, fetched_at TEXT NOT NULL, PRIMARY KEY (market, trading_date) );
-- table persona_definition
CREATE TABLE persona_definition ( persona_id TEXT NOT NULL, version INTEGER NOT NULL, user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, emoji TEXT, name TEXT NOT NULL, role TEXT NOT NULL, stance TEXT, prompt_snapshot TEXT NOT NULL, query_template_json TEXT, is_current INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, PRIMARY KEY (persona_id, version) );
-- table recovery_code
CREATE TABLE recovery_code ( recovery_code_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, code_hash TEXT NOT NULL, used_at TEXT, created_at TEXT NOT NULL );
-- table registration_code
CREATE TABLE registration_code ( registration_code_id TEXT NOT NULL PRIMARY KEY, code_hash TEXT NOT NULL, issued_by TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, issued_at TEXT NOT NULL, expires_at TEXT NOT NULL, used_at TEXT, used_by_user_id TEXT );
-- table shared_setting
CREATE TABLE shared_setting ( key TEXT NOT NULL PRIMARY KEY, value_json TEXT NOT NULL, updated_by TEXT, updated_at TEXT NOT NULL );
-- table stock_master
CREATE TABLE stock_master ( market TEXT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL, name_abbrev TEXT, name_en TEXT, chosung TEXT, isin TEXT, security_group TEXT, is_preferred INTEGER NOT NULL DEFAULT 0, listed_at TEXT, delisted INTEGER NOT NULL DEFAULT 0, leverage_multiple TEXT, nxt_supported INTEGER NOT NULL DEFAULT 0, sector_code TEXT, sector_name TEXT, market_cap_amount TEXT, market_cap_currency TEXT, source_json TEXT, updated_at TEXT NOT NULL, PRIMARY KEY (market, code) );
-- table stock_warning
CREATE TABLE stock_warning ( market TEXT NOT NULL, code TEXT NOT NULL, investment_warning INTEGER NOT NULL DEFAULT 0, investment_risk INTEGER NOT NULL DEFAULT 0, administrative INTEGER NOT NULL DEFAULT 0, trading_halted INTEGER NOT NULL DEFAULT 0, vi_static INTEGER NOT NULL DEFAULT 0, vi_dynamic INTEGER NOT NULL DEFAULT 0, overheated INTEGER NOT NULL DEFAULT 0, liquidation INTEGER NOT NULL DEFAULT 0, fetched_at TEXT NOT NULL, PRIMARY KEY (market, code) );
-- table sync_cursor
CREATE TABLE sync_cursor ( user_id TEXT NOT NULL, source_device_id TEXT NOT NULL, last_seq INTEGER NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY (user_id, source_device_id) );
-- table user_session
CREATE TABLE user_session ( session_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, device_id TEXT NOT NULL REFERENCES device (device_id) ON DELETE CASCADE, kind TEXT NOT NULL, issued_at TEXT NOT NULL, expires_at TEXT NOT NULL, last_step_up_at TEXT, revoked_at TEXT );
-- table user_setting
CREATE TABLE user_setting ( user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, key TEXT NOT NULL, value_json TEXT NOT NULL, updated_at TEXT NOT NULL, updated_by_device_id TEXT, PRIMARY KEY (user_id, key) );
-- table watchlist_group
CREATE TABLE watchlist_group ( group_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, name TEXT NOT NULL, sort_order INTEGER NOT NULL, updated_at TEXT NOT NULL );
-- table watchlist_item
CREATE TABLE watchlist_item ( group_id TEXT NOT NULL REFERENCES watchlist_group (group_id) ON DELETE CASCADE, market TEXT NOT NULL, code TEXT NOT NULL, sort_order INTEGER NOT NULL, added_at TEXT NOT NULL, PRIMARY KEY (group_id, market, code) );
