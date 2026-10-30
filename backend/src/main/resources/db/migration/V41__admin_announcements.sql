alter table school alter column invite_code drop not null;

create table announcement (
    id bigserial primary key,
    title varchar(120) not null,
    content text not null,
    published boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
