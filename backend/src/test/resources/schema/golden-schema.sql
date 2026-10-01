-- index idx_app_user_status
CREATE INDEX idx_app_user_status ON app_user (status);
-- index idx_audit_log_time
CREATE INDEX idx_audit_log_time ON audit_log (occurred_at);
-- index idx_audit_log_user_time
CREATE INDEX idx_audit_log_user_time ON audit_log (user_id, occurred_at);
-- index idx_broker_order_replaces
CREATE INDEX idx_broker_order_replaces ON broker_order (replaces_broker_order_id);
-- index idx_broker_order_user_client_order
CREATE INDEX idx_broker_order_user_client_order ON broker_order (user_id, client_order_id);
-- index idx_broker_order_user_status
CREATE INDEX idx_broker_order_user_status ON broker_order (user_id, status);
-- index idx_broker_order_user_symbol_time
CREATE INDEX idx_broker_order_user_symbol_time ON broker_order (user_id, market, code, ordered_at);
-- index idx_conditional_submission_user_state
CREATE INDEX idx_conditional_submission_user_state ON conditional_submission (user_id, state);
-- index idx_dart_corp_stock_code
CREATE INDEX idx_dart_corp_stock_code ON dart_corp (stock_code);
-- index idx_edgar_ticker_cik
CREATE INDEX idx_edgar_ticker_cik ON edgar_ticker (cik);
-- index idx_event_log_type
CREATE INDEX idx_event_log_type ON event_log (type);
-- index idx_event_log_user_time
CREATE INDEX idx_event_log_user_time ON event_log (user_id, occurred_at);
-- index idx_fill_queue_user_state_time
CREATE INDEX idx_fill_queue_user_state_time ON fill_queue (user_id, state, executed_at);
-- index idx_krx_daily_price_date
CREATE INDEX idx_krx_daily_price_date ON krx_daily_price (bas_dd);
-- index idx_lot_disposal_lot
CREATE INDEX idx_lot_disposal_lot ON lot_disposal (lot_id);
-- index idx_lot_disposal_user_symbol_time
CREATE INDEX idx_lot_disposal_user_symbol_time ON lot_disposal (user_id, market, code, disposed_at);
-- index idx_lot_disposal_user_time
CREATE INDEX idx_lot_disposal_user_time ON lot_disposal (user_id, disposed_at);
-- index idx_lot_user_origin_time
CREATE INDEX idx_lot_user_origin_time ON lot (user_id, origin, bought_at);
-- index idx_lot_user_symbol_closed
CREATE INDEX idx_lot_user_symbol_closed ON lot (user_id, market, code, closed_at);
-- index idx_notification_user_acked
CREATE INDEX idx_notification_user_acked ON notification (user_id, acked_at);
-- index idx_order_submission_user_broker
CREATE INDEX idx_order_submission_user_broker ON order_submission (user_id, broker_order_id);
-- index idx_order_submission_user_state
CREATE INDEX idx_order_submission_user_state ON order_submission (user_id, state);
-- index idx_persona_definition_user
CREATE INDEX idx_persona_definition_user ON persona_definition (user_id);
-- index idx_recovery_code_user
CREATE INDEX idx_recovery_code_user ON recovery_code (user_id);
-- index idx_stock_master_board
CREATE INDEX idx_stock_master_board ON stock_master (listing_board);
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
-- index uq_lot_id_user
CREATE UNIQUE INDEX uq_lot_id_user ON lot (lot_id, user_id);
-- index uq_notification_user_kind_dedupe
CREATE UNIQUE INDEX uq_notification_user_kind_dedupe ON notification (user_id, kind, dedupe_key);
-- table app_user
CREATE TABLE app_user ( user_id TEXT NOT NULL PRIMARY KEY, role TEXT NOT NULL, display_name TEXT NOT NULL, password_hash TEXT NOT NULL, totp_enrolled_at TEXT, totp_last_counter INTEGER NOT NULL DEFAULT 0, failed_logins INTEGER NOT NULL DEFAULT 0, locked_until TEXT, status TEXT NOT NULL, toss_key_decision TEXT NOT NULL, extra_key_wrap INTEGER NOT NULL DEFAULT 0, auto_stop_on_logout INTEGER NOT NULL DEFAULT 0, email TEXT, slack_user_id TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL );
-- table audit_log
CREATE TABLE audit_log ( audit_id TEXT NOT NULL PRIMARY KEY, occurred_at TEXT NOT NULL, user_id TEXT, device_id TEXT, action TEXT NOT NULL, target TEXT, result TEXT NOT NULL, detail_json TEXT );
-- table broker_order
CREATE TABLE broker_order ( broker_order_id TEXT NOT NULL PRIMARY KEY, client_order_id TEXT, replaces_broker_order_id TEXT, user_id TEXT NOT NULL, market TEXT NOT NULL, code TEXT NOT NULL, side TEXT NOT NULL, kind TEXT NOT NULL, time_in_force TEXT NOT NULL, limit_price_amount TEXT, limit_price_currency TEXT, quantity TEXT, order_amount_amount TEXT, order_amount_currency TEXT, status TEXT NOT NULL, filled_quantity TEXT NOT NULL, avg_price_amount TEXT, avg_price_currency TEXT, fee_amount TEXT, tax_amount TEXT, filled_at TEXT, canceled_at TEXT, reject_reason TEXT, origin TEXT NOT NULL, trigger_type TEXT NOT NULL, trigger_json TEXT, remote INTEGER NOT NULL DEFAULT 0, high_value_confirmed INTEGER NOT NULL DEFAULT 0, ordered_at TEXT NOT NULL, updated_at TEXT NOT NULL, fetched_at TEXT NOT NULL , filled_amount_amount TEXT, filled_amount_currency TEXT, version INTEGER NOT NULL DEFAULT 0, queued_quantity TEXT, queued_amount TEXT, queued_fee TEXT, queued_tax TEXT);
-- table candle
CREATE TABLE candle ( market TEXT NOT NULL, code TEXT NOT NULL, interval TEXT NOT NULL, open_time TEXT NOT NULL, open TEXT NOT NULL, high TEXT NOT NULL, low TEXT NOT NULL, close TEXT NOT NULL, currency TEXT NOT NULL, volume TEXT NOT NULL, adjusted INTEGER NOT NULL DEFAULT 1, source TEXT NOT NULL, is_final INTEGER NOT NULL DEFAULT 1, fetched_at TEXT NOT NULL, PRIMARY KEY (market, code, interval, open_time) );
-- table candle_coverage
CREATE TABLE candle_coverage ( market TEXT NOT NULL, code TEXT NOT NULL, interval TEXT NOT NULL, covered_from TEXT NOT NULL, covered_to TEXT NOT NULL, reached_start INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL, PRIMARY KEY (market, code, interval) );
-- table conditional_submission
CREATE TABLE conditional_submission ( client_order_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL, device_id TEXT NOT NULL, market TEXT NOT NULL, code TEXT NOT NULL, type TEXT NOT NULL, quantity TEXT NOT NULL, currency TEXT NOT NULL, first_side TEXT NOT NULL, first_trigger_price TEXT NOT NULL, first_order_price TEXT NOT NULL, second_side TEXT, second_trigger_price TEXT, second_order_price TEXT, expire_date TEXT NOT NULL, intended_at TEXT NOT NULL, high_value_confirmed INTEGER NOT NULL, replaces_conditional_order_id TEXT, state TEXT NOT NULL, conditional_order_id TEXT, reason TEXT, sent_at TEXT NOT NULL, updated_at TEXT NOT NULL );
-- table credential_meta
CREATE TABLE credential_meta ( credential_id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, scope TEXT NOT NULL, user_id TEXT REFERENCES app_user (user_id) ON DELETE CASCADE, last4 TEXT, status TEXT NOT NULL, status_detail TEXT, verified_at TEXT, response_ms INTEGER, extra_json TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL );
-- table dart_corp
CREATE TABLE dart_corp ( corp_code TEXT NOT NULL PRIMARY KEY, stock_code TEXT, corp_name TEXT NOT NULL, corp_name_eng TEXT, corp_cls TEXT, induty_code TEXT, acc_mt TEXT, est_dt TEXT, updated_at TEXT NOT NULL );
-- table device
CREATE TABLE device ( device_id TEXT NOT NULL PRIMARY KEY, public_key TEXT NOT NULL, name TEXT NOT NULL, approved_by_device_id TEXT, registered_at TEXT NOT NULL, revoked_at TEXT );
-- table edgar_entity
CREATE TABLE "edgar_entity" ( cik TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, sic TEXT, fiscal_year_end TEXT, updated_at TEXT NOT NULL );
-- table edgar_ticker
CREATE TABLE edgar_ticker ( code TEXT NOT NULL PRIMARY KEY, cik TEXT NOT NULL, exchange TEXT, updated_at TEXT NOT NULL );
-- table event_log
CREATE TABLE event_log ( event_no INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, device_id TEXT NOT NULL, seq INTEGER NOT NULL, occurred_at TEXT NOT NULL, type TEXT NOT NULL, payload_version INTEGER NOT NULL DEFAULT 1, payload_json TEXT NOT NULL, sync_scope TEXT NOT NULL, received_at TEXT );
-- table exchange_rate
CREATE TABLE exchange_rate ( fx_from TEXT NOT NULL, fx_to TEXT NOT NULL, fx_as_of TEXT NOT NULL, fx_rate TEXT NOT NULL, source TEXT NOT NULL, PRIMARY KEY (fx_from, fx_to, fx_as_of) );
-- table fill_queue
CREATE TABLE fill_queue ( fill_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL, broker_order_id TEXT NOT NULL, market TEXT NOT NULL, code TEXT NOT NULL, side TEXT NOT NULL, quantity TEXT NOT NULL, amount TEXT NOT NULL, fee TEXT NOT NULL, tax TEXT NOT NULL, currency TEXT NOT NULL, order_origin TEXT NOT NULL, executed_at TEXT NOT NULL, state TEXT NOT NULL, reason TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL );
-- table installation
CREATE TABLE installation ( installation_id TEXT NOT NULL PRIMARY KEY, setup_state TEXT NOT NULL, admin_user_id TEXT, llm_preset TEXT, last_public_ip TEXT, stock_master_synced_at TEXT, last_login_user_id TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL );
-- table krx_daily_price
CREATE TABLE krx_daily_price ( code TEXT NOT NULL, bas_dd TEXT NOT NULL, open TEXT, high TEXT, low TEXT, close TEXT, volume TEXT, value TEXT, mktcap TEXT, list_shrs TEXT, fetched_at TEXT NOT NULL, PRIMARY KEY (code, bas_dd) );
-- table krx_etf_daily
CREATE TABLE krx_etf_daily ( code TEXT NOT NULL, bas_dd TEXT NOT NULL, close TEXT, nav TEXT, net_assets TEXT, index_name TEXT, index_close TEXT, index_chg_rate TEXT, fetched_at TEXT NOT NULL, PRIMARY KEY (code, bas_dd) );
-- table krx_index_daily
CREATE TABLE krx_index_daily ( idx_class TEXT NOT NULL, idx_name TEXT NOT NULL, bas_dd TEXT NOT NULL, open TEXT, high TEXT, low TEXT, close TEXT, volume TEXT, value TEXT, mktcap TEXT, fetched_at TEXT NOT NULL, PRIMARY KEY (idx_class, idx_name, bas_dd) );
-- table lot
CREATE TABLE lot ( lot_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, market TEXT NOT NULL, code TEXT NOT NULL, bought_quantity TEXT NOT NULL, remaining_quantity TEXT NOT NULL, unit_cost_amount TEXT NOT NULL, unit_cost_currency TEXT NOT NULL, fx_from TEXT, fx_to TEXT, fx_rate TEXT, fx_as_of TEXT, bought_at TEXT NOT NULL, origin TEXT NOT NULL, broker_order_id TEXT, recommendation_id TEXT, aged_out_at TEXT, closed_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL , opening INTEGER NOT NULL DEFAULT 0);
-- table lot_disposal
CREATE TABLE "lot_disposal" ( disposal_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL, lot_id TEXT NOT NULL, broker_order_id TEXT NOT NULL, market TEXT NOT NULL, code TEXT NOT NULL, quantity TEXT NOT NULL, sell_price_amount TEXT NOT NULL, sell_price_currency TEXT NOT NULL, buy_unit_cost_amount TEXT NOT NULL, buy_unit_cost_currency TEXT NOT NULL, fee_amount TEXT NOT NULL, tax_amount TEXT NOT NULL, fx_from TEXT, fx_to TEXT, fx_rate TEXT, fx_as_of TEXT, realized_amount TEXT NOT NULL, realized_currency TEXT NOT NULL, realized_krw TEXT, fx_pnl_krw TEXT, holding_days INTEGER NOT NULL, lot_origin TEXT NOT NULL, disposed_at TEXT NOT NULL, created_at TEXT NOT NULL, FOREIGN KEY (lot_id, user_id) REFERENCES lot (lot_id, user_id) ON DELETE CASCADE );
-- table lot_ledger
CREATE TABLE lot_ledger ( user_id TEXT NOT NULL PRIMARY KEY, started_at TEXT NOT NULL, created_at TEXT NOT NULL );
-- table market_calendar
CREATE TABLE market_calendar ( market TEXT NOT NULL, trading_date TEXT NOT NULL, sessions_json TEXT NOT NULL, is_holiday INTEGER NOT NULL DEFAULT 0, fetched_at TEXT NOT NULL, PRIMARY KEY (market, trading_date) );
-- table market_index_quote
CREATE TABLE market_index_quote ( index_code TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, change_amount TEXT, change_ratio TEXT, as_of TEXT, closed INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL, fetched_at TEXT NOT NULL );
-- table notification
CREATE TABLE notification ( notification_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL, kind TEXT NOT NULL, dedupe_key TEXT NOT NULL, title TEXT NOT NULL, body TEXT, link_json TEXT, created_at TEXT NOT NULL, read_at TEXT, acked_at TEXT, slack_sent_at TEXT );
-- table order_submission
CREATE TABLE order_submission ( client_order_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL, device_id TEXT NOT NULL, market TEXT NOT NULL, code TEXT NOT NULL, side TEXT NOT NULL, kind TEXT NOT NULL, time_in_force TEXT NOT NULL, limit_price_amount TEXT, limit_price_currency TEXT, quantity TEXT, order_amount_amount TEXT, order_amount_currency TEXT, origin TEXT NOT NULL, trigger_type TEXT NOT NULL, trigger_json TEXT, intended_at TEXT NOT NULL, high_value_confirmed INTEGER NOT NULL, state TEXT NOT NULL, broker_order_id TEXT, reason TEXT, sent_at TEXT NOT NULL, updated_at TEXT NOT NULL , replaces_broker_order_id TEXT);
-- table persona_definition
CREATE TABLE persona_definition ( persona_id TEXT NOT NULL, version INTEGER NOT NULL, user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, emoji TEXT, name TEXT NOT NULL, role TEXT NOT NULL, stance TEXT, prompt_snapshot TEXT NOT NULL, query_template_json TEXT, is_current INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, PRIMARY KEY (persona_id, version) );
-- table portfolio_cache
CREATE TABLE portfolio_cache ( user_id TEXT NOT NULL, market TEXT NOT NULL, snapshot_json TEXT NOT NULL, as_of TEXT NOT NULL, stale INTEGER NOT NULL DEFAULT 0, error TEXT, PRIMARY KEY (user_id, market) );
-- table recovery_code
CREATE TABLE recovery_code ( recovery_code_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, code_hash TEXT NOT NULL, used_at TEXT, created_at TEXT NOT NULL );
-- table registration_code
CREATE TABLE registration_code ( registration_code_id TEXT NOT NULL PRIMARY KEY, code_hash TEXT NOT NULL, issued_by TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, issued_at TEXT NOT NULL, expires_at TEXT NOT NULL, used_at TEXT, used_by_user_id TEXT );
-- table shared_setting
CREATE TABLE shared_setting ( key TEXT NOT NULL PRIMARY KEY, value_json TEXT NOT NULL, updated_by TEXT, updated_at TEXT NOT NULL );
-- table stock_master
CREATE TABLE stock_master ( market TEXT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL, name_abbrev TEXT, name_en TEXT, chosung TEXT, isin TEXT, security_group TEXT, is_preferred INTEGER NOT NULL DEFAULT 0, listed_at TEXT, delisted INTEGER NOT NULL DEFAULT 0, leverage_multiple TEXT, nxt_supported INTEGER NOT NULL DEFAULT 0, sector_code TEXT, sector_name TEXT, market_cap_amount TEXT, market_cap_currency TEXT, source_json TEXT, updated_at TEXT NOT NULL, listing_board TEXT, PRIMARY KEY (market, code) );
-- table stock_warning
CREATE TABLE stock_warning ( market TEXT NOT NULL, code TEXT NOT NULL, investment_warning INTEGER NOT NULL, investment_risk INTEGER NOT NULL, administrative INTEGER, trading_halted INTEGER, vi_static INTEGER NOT NULL, vi_dynamic INTEGER NOT NULL, overheated INTEGER NOT NULL, liquidation INTEGER NOT NULL, fetched_at TEXT NOT NULL, unknown_warning INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (market, code) );
-- table sync_cursor
CREATE TABLE sync_cursor ( user_id TEXT NOT NULL, source_device_id TEXT NOT NULL, last_seq INTEGER NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY (user_id, source_device_id) );
-- table us_ticker_ref
CREATE TABLE us_ticker_ref ( code TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, cik TEXT, composite_figi TEXT, sic_code TEXT, sic_description TEXT, market_cap TEXT, shares_outstanding TEXT, list_date TEXT, primary_exchange TEXT, fetched_at TEXT NOT NULL );
-- table user_session
CREATE TABLE user_session ( session_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, device_id TEXT NOT NULL REFERENCES device (device_id) ON DELETE CASCADE, kind TEXT NOT NULL, issued_at TEXT NOT NULL, expires_at TEXT NOT NULL, last_step_up_at TEXT, revoked_at TEXT );
-- table user_setting
CREATE TABLE user_setting ( user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, key TEXT NOT NULL, value_json TEXT NOT NULL, updated_at TEXT NOT NULL, updated_by_device_id TEXT, PRIMARY KEY (user_id, key) );
-- table watchlist_group
CREATE TABLE watchlist_group ( group_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE, name TEXT NOT NULL, sort_order INTEGER NOT NULL, updated_at TEXT NOT NULL );
-- table watchlist_item
CREATE TABLE watchlist_item ( group_id TEXT NOT NULL REFERENCES watchlist_group (group_id) ON DELETE CASCADE, market TEXT NOT NULL, code TEXT NOT NULL, sort_order INTEGER NOT NULL, added_at TEXT NOT NULL, PRIMARY KEY (group_id, market, code) );
