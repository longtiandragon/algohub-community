-- 按数据项记录同步状态：同一个账号的"平台统计题数""逐题通过列表""提交历史"来源不同、成败各自独立。
-- 总量成功、历史失败时账号显示"部分成功"；未获取、获取失败与真实为零各自区分，不混成"0 题"。
create table account_data_status (
    account_id      bigint       not null references platform_account (id) on delete cascade,
    -- TOTALS 平台统计题数；SOLVED_LIST 逐题通过列表（无时间）；HISTORY 带时间的提交历史
    item            varchar(24)  not null,
    -- SUCCESS 本次成功；FAILED 本次失败（保留上次成功的数据）；NEEDS_CREDENTIAL 需要有效登录态
    status          varchar(24)  not null,
    last_attempt_at timestamptz  not null default now(),
    last_success_at timestamptz,
    error_kind      varchar(32),
    error_msg       varchar(512),
    -- 本项最近一次成功时平台给出的数量（统计题数 / 列表题数 / 本轮写入提交数），失败时保留上次的值
    observed_count  int,
    detail          varchar(255),
    primary key (account_id, item)
);

-- 账号的整体同步状态增加 PARTIAL（部分成功）。sync_status 是自由文本列，这里只记录约定：
-- OK / PARTIAL / FAILED / PAUSED / QUEUED / RUNNING / IDLE。

-- 码蹄集昵称索引：排行榜是唯一能按昵称找到数字 UID 的公开来源。每次翻页看到的账号都记下来，
-- 同名直接给出候选，不必每次从第一页翻起；绑定后一律按 UID 同步。
create table matiji_user_index (
    uid         varchar(32)  primary key,
    nickname    varchar(128) not null,
    school      varchar(255),
    pass_num    int,
    rank_order  int,
    observed_at timestamptz  not null default now()
);
create index idx_matiji_index_nickname on matiji_user_index (nickname);

-- 后台低频补全索引的进度（单行）
create table matiji_index_state (
    id            int          primary key default 1 check (id = 1),
    next_start    int          not null default 0,
    total         int,
    round_started timestamptz,
    last_run_at   timestamptz,
    last_error    varchar(255)
);
insert into matiji_index_state (id) values (1);
