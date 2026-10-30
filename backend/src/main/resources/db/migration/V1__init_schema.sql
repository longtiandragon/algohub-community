-- 深圳职业技术大学算法学习平台：初始结构
-- 时间列统一使用 timestamptz，写入 UTC；校内每日统计在查询层按 Asia/Shanghai 划分。

create table school (
    id            bigserial primary key,
    name          varchar(128) not null,
    short_name    varchar(64),
    invite_code   varchar(64)  not null unique,
    created_at    timestamptz  not null default now()
);

create table app_user (
    id             bigserial primary key,
    username       varchar(64)  not null unique,
    password_hash  varchar(128) not null,
    display_name   varchar(64)  not null,
    email          varchar(128),
    role           varchar(16)  not null default 'STUDENT',
    status         varchar(16)  not null default 'ACTIVE',
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now()
);

-- 学校成员关系：入校审核、年级、班级
create table school_member (
    id              bigserial primary key,
    school_id       bigint      not null references school (id),
    user_id         bigint      not null references app_user (id),
    student_no      varchar(32),
    enrollment_year int,
    class_name      varchar(64),
    member_role     varchar(16) not null default 'MEMBER',
    status          varchar(16) not null default 'PENDING',
    applied_at      timestamptz not null default now(),
    reviewed_at     timestamptz,
    reviewed_by     bigint references app_user (id),
    reject_reason   varchar(255),
    unique (school_id, user_id)
);
create index idx_school_member_status on school_member (school_id, status);

-- 平台账号绑定。platform_uid 为平台稳定 ID，handle 为可变展示标识。
create table platform_account (
    id                bigserial primary key,
    user_id           bigint       not null references app_user (id),
    platform          varchar(32)  not null,
    platform_uid      varchar(128),
    handle            varchar(128) not null,
    ownership_status  varchar(24)  not null default 'UNVERIFIED',
    auth_mode         varchar(16)  not null default 'PUBLIC',
    sync_status       varchar(16)  not null default 'IDLE',
    supports_history  boolean      not null default false,
    history_complete  boolean      not null default false,
    last_success_at   timestamptz,
    last_attempt_at   timestamptz,
    last_error_kind   varchar(32),
    last_error_msg    varchar(512),
    note              varchar(255),
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    unique (user_id, platform, handle)
);
-- 同一平台同一 UID 只能归属一个本地用户
create unique index uk_platform_uid on platform_account (platform, platform_uid) where platform_uid is not null;
create index idx_account_user on platform_account (user_id);

-- 平台登录态：与普通资料分表保存，密文存储
create table platform_credential (
    id                bigserial primary key,
    account_id        bigint      not null unique references platform_account (id) on delete cascade,
    cipher_text       text        not null,
    key_version       int         not null default 1,
    cred_version      int         not null default 1,
    status            varchar(16) not null default 'ACTIVE',
    last_verified_at  timestamptz,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

-- 标识变更记录（改名、handle 变化）
create table account_identity_history (
    id           bigserial primary key,
    account_id   bigint      not null references platform_account (id) on delete cascade,
    old_handle   varchar(128),
    new_handle   varchar(128),
    changed_at   timestamptz not null default now()
);

create table problem (
    id                bigserial primary key,
    platform          varchar(32)  not null,
    problem_key       varchar(128) not null,
    title             varchar(255),
    url               varchar(512),
    difficulty_raw    varchar(64),
    difficulty_label  varchar(64),
    difficulty_source varchar(32),
    tags_complete     boolean      not null default false,
    updated_at        timestamptz  not null default now(),
    unique (platform, problem_key)
);

create table problem_tag (
    id          bigserial primary key,
    problem_id  bigint       not null references problem (id) on delete cascade,
    tag_raw     varchar(128) not null,
    tag_source  varchar(32)  not null default 'OFFICIAL',
    unique (problem_id, tag_raw)
);

-- 原始标签到统一分类的映射，未命中保留 UNMAPPED
create table tag_category_mapping (
    id        bigserial primary key,
    platform  varchar(32)  not null,
    tag_raw   varchar(128) not null,
    category  varchar(64)  not null,
    unique (platform, tag_raw)
);

create table submission (
    id               bigserial primary key,
    account_id       bigint       not null references platform_account (id) on delete cascade,
    problem_id       bigint       references problem (id),
    platform         varchar(32)  not null,
    platform_sub_id  varchar(64)  not null,
    verdict          varchar(32)  not null,
    accepted         boolean      not null default false,
    submitted_at     timestamptz  not null,
    time_precision   varchar(16)  not null default 'EXACT',
    language         varchar(64),
    contest_ref      varchar(64),
    collected_at     timestamptz  not null default now(),
    unique (platform, platform_sub_id)
);
create index idx_submission_account_time on submission (account_id, submitted_at);
create index idx_submission_accepted on submission (account_id, accepted, submitted_at);

-- 去重后的通过关系
create table user_solved_problem (
    id                   bigserial primary key,
    user_id              bigint      not null references app_user (id),
    account_id           bigint      not null references platform_account (id) on delete cascade,
    problem_id           bigint      not null references problem (id),
    platform             varchar(32) not null,
    earliest_known_ac_at timestamptz,
    first_ac_confidence  varchar(24) not null default 'UNKNOWN',
    source               varchar(32) not null default 'SYNC',
    created_at           timestamptz not null default now(),
    unique (account_id, problem_id)
);
create index idx_solved_user on user_solved_problem (user_id, platform);
create index idx_solved_time on user_solved_problem (user_id, earliest_known_ac_at);

-- 日统计。metric: FIRST_AC / UNIQUE_AC / AC_SUBMISSIONS / SUBMISSIONS
create table daily_stat (
    id          bigserial primary key,
    user_id     bigint      not null references app_user (id),
    account_id  bigint      not null references platform_account (id) on delete cascade,
    platform    varchar(32) not null,
    stat_date   date        not null,
    metric      varchar(32) not null,
    value       int         not null default 0,
    source      varchar(32) not null default 'SUBMISSION',
    updated_at  timestamptz not null default now(),
    unique (account_id, stat_date, metric)
);
create index idx_daily_user_date on daily_stat (user_id, stat_date);

-- 只提供总数的平台：保存快照与观察增量，不伪造逐日历史
create table total_snapshot (
    id               bigserial primary key,
    account_id       bigint      not null references platform_account (id) on delete cascade,
    captured_at      timestamptz not null default now(),
    solved_total     int,
    submission_total int,
    source           varchar(32) not null default 'SYNC',
    raw_extra        text
);
create index idx_snapshot_account on total_snapshot (account_id, captured_at);

create table contest (
    id           bigserial primary key,
    platform     varchar(32)  not null,
    contest_key  varchar(128) not null,
    title        varchar(255) not null,
    start_at     timestamptz  not null,
    end_at       timestamptz,
    duration_sec int,
    status       varchar(24)  not null default 'SCHEDULED',
    category     varchar(64),
    url          varchar(512),
    source       varchar(32)  not null,
    updated_at   timestamptz  not null default now(),
    unique (platform, contest_key)
);
create index idx_contest_start on contest (start_at);

create table sync_task (
    id             bigserial primary key,
    task_type      varchar(32) not null,
    platform       varchar(32),
    account_id     bigint references platform_account (id) on delete cascade,
    status         varchar(16) not null default 'PENDING',
    priority       int         not null default 100,
    attempt        int         not null default 0,
    max_attempt    int         not null default 5,
    next_run_at    timestamptz not null default now(),
    lease_owner    varchar(64),
    lease_until    timestamptz,
    error_kind     varchar(32),
    error_msg      varchar(512),
    duration_ms    bigint,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    finished_at    timestamptz
);
create index idx_task_pick on sync_task (status, next_run_at);
create unique index uk_task_active_account on sync_task (account_id, task_type)
    where status in ('PENDING', 'RUNNING') and account_id is not null;

create table sync_cursor (
    id            bigserial primary key,
    account_id    bigint      not null references platform_account (id) on delete cascade,
    cursor_kind   varchar(32) not null,
    cursor_value  varchar(128),
    covered_from  timestamptz,
    covered_to    timestamptz,
    completed     boolean     not null default false,
    updated_at    timestamptz not null default now(),
    unique (account_id, cursor_kind)
);

create table nav_resource (
    id          bigserial primary key,
    category    varchar(64)  not null,
    title       varchar(128) not null,
    url         varchar(512) not null,
    description varchar(512),
    sort_order  int          not null default 100,
    enabled     boolean      not null default true,
    created_at  timestamptz  not null default now()
);
create index idx_nav_category on nav_resource (category, sort_order);
