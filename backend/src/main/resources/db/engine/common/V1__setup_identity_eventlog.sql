-- V1: 설치·계정·자격(4장), 이벤트 로그·projection(5장), 종목 마스터·경고·환율·달력(6장 일부).
-- 자리표시자: ${id} ${decimal} ${instant} ${date} ${bool} ${json} ${text}. 값은 application-{profile}.yml 에서 벤더별로 넣음.
-- 물리 FK는 부모가 작고 안정적이며 CASCADE 가 타당하고 적재 순서가 보장되는 곳에만 둠. event_log·sync_cursor·audit_log 에는 없음.

CREATE TABLE installation (
    installation_id        ${id}      NOT NULL PRIMARY KEY,
    setup_state            TEXT       NOT NULL,
    admin_user_id          ${id},
    llm_preset             TEXT,
    last_public_ip         TEXT,
    stock_master_synced_at ${instant},
    last_login_user_id     ${id},
    created_at             ${instant} NOT NULL,
    updated_at             ${instant} NOT NULL
);

CREATE TABLE app_user (
    user_id             ${id}      NOT NULL PRIMARY KEY,
    role                TEXT       NOT NULL,
    display_name        TEXT       NOT NULL,
    password_hash       TEXT       NOT NULL,
    totp_enrolled_at    ${instant},
    totp_last_counter   INTEGER    NOT NULL DEFAULT 0,
    failed_logins       INTEGER    NOT NULL DEFAULT 0,
    locked_until        ${instant},
    status              TEXT       NOT NULL,
    toss_key_decision   TEXT       NOT NULL,
    extra_key_wrap      ${bool}    NOT NULL DEFAULT 0,
    auto_stop_on_logout ${bool}    NOT NULL DEFAULT 0,
    email               TEXT,
    slack_user_id       TEXT,
    created_at          ${instant} NOT NULL,
    updated_at          ${instant} NOT NULL
);
CREATE INDEX idx_app_user_status ON app_user (status);

CREATE TABLE recovery_code (
    recovery_code_id ${id}      NOT NULL PRIMARY KEY,
    user_id          ${id}      NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    code_hash        TEXT       NOT NULL,
    used_at          ${instant},
    created_at       ${instant} NOT NULL
);
CREATE INDEX idx_recovery_code_user ON recovery_code (user_id);

CREATE TABLE registration_code (
    registration_code_id ${id}      NOT NULL PRIMARY KEY,
    code_hash            TEXT       NOT NULL,
    issued_by            ${id}      NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    issued_at            ${instant} NOT NULL,
    expires_at           ${instant} NOT NULL,
    used_at              ${instant},
    used_by_user_id      ${id}
);

CREATE TABLE device (
    device_id             ${id}      NOT NULL PRIMARY KEY,
    public_key            TEXT       NOT NULL,
    name                  TEXT       NOT NULL,
    approved_by_device_id ${id},
    registered_at         ${instant} NOT NULL,
    revoked_at            ${instant}
);

CREATE TABLE user_session (
    session_id      TEXT       NOT NULL PRIMARY KEY,
    user_id         ${id}      NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    device_id       ${id}      NOT NULL REFERENCES device (device_id) ON DELETE CASCADE,
    kind            TEXT       NOT NULL,
    issued_at       ${instant} NOT NULL,
    expires_at      ${instant} NOT NULL,
    last_step_up_at ${instant},
    revoked_at      ${instant}
);
CREATE INDEX idx_user_session_user_expires ON user_session (user_id, expires_at);

CREATE TABLE credential_meta (
    credential_id ${id}      NOT NULL PRIMARY KEY,
    kind          TEXT       NOT NULL,
    scope         TEXT       NOT NULL,
    user_id       ${id}      REFERENCES app_user (user_id) ON DELETE CASCADE,
    last4         TEXT,
    status        TEXT       NOT NULL,
    status_detail TEXT,
    verified_at   ${instant},
    response_ms   INTEGER,
    extra_json    ${json},
    created_at    ${instant} NOT NULL,
    updated_at    ${instant} NOT NULL
);
CREATE UNIQUE INDEX uq_credential_meta_kind_scope_user ON credential_meta (kind, scope, user_id);

CREATE TABLE shared_setting (
    key        TEXT       NOT NULL PRIMARY KEY,
    value_json ${json}    NOT NULL,
    updated_by ${id},
    updated_at ${instant} NOT NULL
);

CREATE TABLE audit_log (
    audit_id    ${id}      NOT NULL PRIMARY KEY,
    occurred_at ${instant} NOT NULL,
    user_id     ${id},
    device_id   ${id},
    action      TEXT       NOT NULL,
    target      TEXT,
    result      TEXT       NOT NULL,
    detail_json ${json}
);
CREATE INDEX idx_audit_log_time ON audit_log (occurred_at);
CREATE INDEX idx_audit_log_user_time ON audit_log (user_id, occurred_at);

CREATE TABLE event_log (
    event_no        INTEGER    NOT NULL PRIMARY KEY AUTOINCREMENT,
    user_id         ${id}      NOT NULL,
    device_id       ${id}      NOT NULL,
    seq             INTEGER    NOT NULL,
    occurred_at     ${instant} NOT NULL,
    type            TEXT       NOT NULL,
    payload_version INTEGER    NOT NULL DEFAULT 1,
    payload_json    ${json}    NOT NULL,
    sync_scope      TEXT       NOT NULL,
    received_at     ${instant}
);
CREATE UNIQUE INDEX uq_event_log_user_device_seq ON event_log (user_id, device_id, seq);
CREATE INDEX idx_event_log_user_time ON event_log (user_id, occurred_at);
CREATE INDEX idx_event_log_type ON event_log (type);

CREATE TABLE sync_cursor (
    user_id          ${id}      NOT NULL,
    source_device_id ${id}      NOT NULL,
    last_seq         INTEGER    NOT NULL,
    updated_at       ${instant} NOT NULL,
    PRIMARY KEY (user_id, source_device_id)
);

CREATE TABLE user_setting (
    user_id              ${id}      NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    key                  TEXT       NOT NULL,
    value_json           ${json}    NOT NULL,
    updated_at           ${instant} NOT NULL,
    updated_by_device_id ${id},
    PRIMARY KEY (user_id, key)
);

CREATE TABLE watchlist_group (
    group_id   ${id}      NOT NULL PRIMARY KEY,
    user_id    ${id}      NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    name       TEXT       NOT NULL,
    sort_order INTEGER    NOT NULL,
    updated_at ${instant} NOT NULL
);
CREATE INDEX idx_watchlist_group_user ON watchlist_group (user_id);

CREATE TABLE watchlist_item (
    group_id   ${id}      NOT NULL REFERENCES watchlist_group (group_id) ON DELETE CASCADE,
    market     TEXT       NOT NULL,
    code       TEXT       NOT NULL,
    sort_order INTEGER    NOT NULL,
    added_at   ${instant} NOT NULL,
    PRIMARY KEY (group_id, market, code)
);
CREATE INDEX idx_watchlist_item_symbol ON watchlist_item (market, code);

CREATE TABLE persona_definition (
    persona_id          ${id}      NOT NULL,
    version             INTEGER    NOT NULL,
    user_id             ${id}      NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    emoji               TEXT,
    name                TEXT       NOT NULL,
    role                TEXT       NOT NULL,
    stance              ${text},
    prompt_snapshot     ${text}    NOT NULL,
    query_template_json ${json},
    is_current          ${bool}    NOT NULL DEFAULT 1,
    created_at          ${instant} NOT NULL,
    PRIMARY KEY (persona_id, version)
);
CREATE INDEX idx_persona_definition_user ON persona_definition (user_id);

CREATE TABLE stock_master (
    market              TEXT       NOT NULL,
    code                TEXT       NOT NULL,
    name                TEXT       NOT NULL,
    name_abbrev         TEXT,
    name_en             TEXT,
    chosung             TEXT,
    isin                TEXT,
    security_group      TEXT,
    is_preferred        ${bool}    NOT NULL DEFAULT 0,
    listed_at           ${date},
    delisted            ${bool}    NOT NULL DEFAULT 0,
    leverage_multiple   ${decimal},
    nxt_supported       ${bool}    NOT NULL DEFAULT 0,
    sector_code         TEXT,
    sector_name         TEXT,
    market_cap_amount   ${decimal},
    market_cap_currency TEXT,
    source_json         ${json},
    updated_at          ${instant} NOT NULL,
    PRIMARY KEY (market, code)
);
CREATE INDEX idx_stock_master_name ON stock_master (name);
CREATE INDEX idx_stock_master_chosung ON stock_master (chosung);
CREATE INDEX idx_stock_master_isin ON stock_master (isin);

CREATE TABLE stock_warning (
    market             TEXT       NOT NULL,
    code               TEXT       NOT NULL,
    investment_warning ${bool}    NOT NULL DEFAULT 0,
    investment_risk    ${bool}    NOT NULL DEFAULT 0,
    administrative     ${bool}    NOT NULL DEFAULT 0,
    trading_halted     ${bool}    NOT NULL DEFAULT 0,
    vi_static          ${bool}    NOT NULL DEFAULT 0,
    vi_dynamic         ${bool}    NOT NULL DEFAULT 0,
    overheated         ${bool}    NOT NULL DEFAULT 0,
    liquidation        ${bool}    NOT NULL DEFAULT 0,
    fetched_at         ${instant} NOT NULL,
    PRIMARY KEY (market, code)
);

CREATE TABLE exchange_rate (
    fx_from  TEXT       NOT NULL,
    fx_to    TEXT       NOT NULL,
    fx_as_of ${instant} NOT NULL,
    fx_rate  ${decimal} NOT NULL,
    source   TEXT       NOT NULL,
    PRIMARY KEY (fx_from, fx_to, fx_as_of)
);

CREATE TABLE market_calendar (
    market        TEXT       NOT NULL,
    trading_date  ${date}    NOT NULL,
    sessions_json ${json}    NOT NULL,
    is_holiday    ${bool}    NOT NULL DEFAULT 0,
    fetched_at    ${instant} NOT NULL,
    PRIMARY KEY (market, trading_date)
);
