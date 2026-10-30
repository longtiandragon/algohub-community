alter table announcement drop constraint announcement_kind_check;
alter table announcement add constraint announcement_kind_check check (kind in ('TEXT','LINK','UPDATE'));
alter table announcement add column items jsonb not null default '[]'::jsonb;
alter table announcement add constraint announcement_items_array check (jsonb_typeof(items) = 'array');
alter table announcement add column published_at timestamptz;
update announcement set published_at=updated_at where published;

-- 管理端展示通用名称，学生端继续按已审核学校生成查询链接。
update announcement set title='本校蓝桥杯获奖情况',content='按用户已审核通过的学校展示蓝桥杯获奖情况。'
where school_id is null and kind='LINK' and url like 'https://c16h22o4.github.io/%';
update announcement set title='本校XCPC竞赛情况',content='按用户已审核通过的学校展示 XCPC 竞赛情况。'
where school_id is null and kind='LINK' and url like 'https://hei-maom.github.io/xcpcrating/%';
