-- 不再接入 PTA、AcWing：平台枚举里已去掉这两项，库里留下的行会让按平台解析的地方报「未知平台」，这里一并删掉。
-- PTA 原来只有「从文件导入题集」一种接入方式，导入功能随之删除；AcWing 从未接入，只有导航链接。
-- 先删引用题目、账号的行，再删题目和账号本身；有 on delete cascade 的也写明，便于核对。

-- 引用 PTA / AcWing 题目的行
delete from problem_list_item
 where problem_id in (select id from problem where platform in ('PTA', 'ACWING'));
delete from problem_bookmark
 where problem_id in (select id from problem where platform in ('PTA', 'ACWING'));
delete from problem_tag
 where problem_id in (select id from problem where platform in ('PTA', 'ACWING'));
delete from problem_group_member
 where problem_id in (select id from problem where platform in ('PTA', 'ACWING'));
delete from submission
 where platform in ('PTA', 'ACWING')
    or problem_id in (select id from problem where platform in ('PTA', 'ACWING'));
delete from user_solved_problem
 where platform in ('PTA', 'ACWING')
    or problem_id in (select id from problem where platform in ('PTA', 'ACWING'));

-- 按平台记的统计、任务与申请
delete from daily_stat where platform in ('PTA', 'ACWING');
delete from import_batch where platform in ('PTA', 'ACWING');
delete from sync_task where platform in ('PTA', 'ACWING');
delete from platform_throttle where platform in ('PTA', 'ACWING');
delete from binding_request where platform in ('PTA', 'ACWING');
delete from ownership_appeal where platform in ('PTA', 'ACWING');
delete from weekly_goal where platform in ('PTA', 'ACWING');
delete from tag_category_mapping where platform in ('PTA', 'ACWING');
delete from contest where platform in ('PTA', 'ACWING');
delete from problem_list where platform in ('PTA', 'ACWING');

-- 账号（登录态、同步游标、快照等按账号级联删除）与题目
delete from platform_account where platform in ('PTA', 'ACWING');
delete from problem where platform in ('PTA', 'ACWING');

-- 只装过 PTA / AcWing 题目的题目组，成员删完后成了空组
delete from problem_group g
 where (g.canonical_key like 'PTA/%' or g.canonical_key like 'ACWING/%')
   and not exists (select 1 from problem_group_member m where m.group_id = g.id);

-- V2 写入的首页导航链接
delete from nav_resource
 where category = '在线评测'
   and url in ('https://pintia.cn/', 'https://www.acwing.com/');
