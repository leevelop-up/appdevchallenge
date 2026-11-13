-- CO-DRIVE CONNECT 초기 스키마 (PostgreSQL / H2(PostgreSQL mode) 공용 문법)

create table vehicle_installations (
    installation_id uuid primary key,
    display_name    varchar(100) not null,
    public_key      text         not null,
    created_at      timestamp with time zone not null,
    last_seen_at    timestamp with time zone,
    revoked_at      timestamp with time zone
);

create table vehicle_sessions (
    session_id           uuid primary key,
    installation_id      uuid         not null references vehicle_installations (installation_id),
    launch_id            varchar(64)  not null,
    alias                varchar(60)  not null,
    status               varchar(20)  not null,
    created_at           timestamp with time zone not null,
    expires_at           timestamp with time zone not null,
    last_vehicle_seen_at timestamp with time zone,
    suspended_at         timestamp with time zone,
    ended_at             timestamp with time zone,
    end_reason           varchar(40)
);
create index idx_sessions_installation_status on vehicle_sessions (installation_id, status);
create index idx_sessions_status on vehicle_sessions (status);

-- QR / 참여 코드. 원문은 저장하지 않고 SHA-256 해시만 저장한다.
create table join_tokens (
    token_id       uuid primary key,
    session_id     uuid        not null references vehicle_sessions (session_id),
    token_hash     varchar(64) not null unique,
    code_hash      varchar(64) not null unique,
    created_at     timestamp with time zone not null,
    expires_at     timestamp with time zone not null,
    consumed_at    timestamp with time zone,
    invalidated_at timestamp with time zone
);
create index idx_join_tokens_session on join_tokens (session_id);

create table join_requests (
    request_id   uuid primary key,
    session_id   uuid        not null references vehicle_sessions (session_id),
    nickname     varchar(30) not null,
    status       varchar(20) not null,
    token_hash   varchar(64) not null unique,
    requested_at timestamp with time zone not null,
    resolved_at  timestamp with time zone
);
create index idx_join_requests_session on join_requests (session_id, status);

create table participants (
    participant_id    uuid primary key,
    session_id        uuid        not null references vehicle_sessions (session_id),
    request_id        uuid        not null unique references join_requests (request_id),
    nickname          varchar(30) not null,
    role              varchar(20) not null,
    connection_status varchar(20) not null,
    token_hash        varchar(64) not null unique,
    joined_at         timestamp with time zone not null,
    disconnected_at   timestamp with time zone,
    revoked_at        timestamp with time zone
);
create index idx_participants_session on participants (session_id);

create table participant_permissions (
    participant_id    uuid        not null references participants (participant_id),
    feature_code      varchar(40) not null,
    access_level      varchar(10) not null,
    capability_status varchar(30),
    granted_at        timestamp with time zone not null,
    revoked_at        timestamp with time zone,
    primary key (participant_id, feature_code)
);

create table command_logs (
    request_id     uuid primary key,
    session_id     uuid        not null references vehicle_sessions (session_id),
    participant_id uuid        not null references participants (participant_id),
    action         varchar(40) not null,
    params         text,
    status         varchar(30) not null,
    error_code     varchar(40),
    simulated      boolean     not null default false,
    created_at     timestamp with time zone not null,
    completed_at   timestamp with time zone
);
create index idx_command_logs_session on command_logs (session_id, status);
