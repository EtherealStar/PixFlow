create table app_activity_projection (
    source_kind varchar(32) not null,
    source_id varchar(128) not null,
    administrator_id bigint not null,
    activity_id varchar(192) not null,
    source_revision bigint not null,
    sequence bigint not null,
    removed boolean not null,
    activity_kind varchar(32),
    activity_status varchar(32),
    view_json json,
    updated_at timestamp(6) not null,
    primary key (source_kind, source_id),
    unique key uk_app_activity_id (activity_id),
    key idx_app_activity_admin_current (administrator_id, removed, updated_at)
);

create table app_activity_event_outbox (
    sequence bigint not null auto_increment,
    administrator_id bigint not null,
    operation varchar(16) not null,
    activity_id varchar(192) not null,
    view_json json,
    delivered_at timestamp(6),
    created_at timestamp(6) not null,
    primary key (sequence),
    key idx_app_activity_delivery (delivered_at, sequence),
    key idx_app_activity_admin_sequence (administrator_id, sequence)
);
