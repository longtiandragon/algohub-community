create table school_student_remark (
    school_id bigint not null,
    owner_id bigint not null references app_user(id) on delete cascade,
    student_id bigint not null,
    remark varchar(64) not null check (length(btrim(remark)) > 0),
    primary key (school_id,owner_id,student_id),
    foreign key (school_id,student_id) references school_member(school_id,user_id) on delete cascade
);
