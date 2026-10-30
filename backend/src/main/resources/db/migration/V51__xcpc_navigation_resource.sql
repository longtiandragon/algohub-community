-- Managed common resource, available regardless of school membership.
insert into announcement(title,content,published,kind,url)
select '算法竞赛资源导航', 'XCPC Link：查找算法竞赛平台、赛事信息、学习资料与实用工具。', true, 'LINK', 'https://xcpc.link/'
where not exists (select 1 from announcement where kind='LINK' and url in ('https://xcpc.link','https://xcpc.link/'));
