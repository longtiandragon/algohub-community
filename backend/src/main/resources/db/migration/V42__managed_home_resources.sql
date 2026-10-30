alter table announcement add column kind varchar(16) not null default 'TEXT' check (kind in ('TEXT','LINK'));
alter table announcement add column url varchar(512);
insert into announcement(title,content,published,kind,url) values
('深职大 · 蓝桥杯获奖情况','查看我校蓝桥杯获奖记录，了解同学们的竞赛成绩。',true,'LINK','https://c16h22o4.github.io/?mode=person&school=%E6%B7%B1%E5%9C%B3%E8%81%8C%E4%B8%9A%E6%8A%80%E6%9C%AF'),
('深职大 · XCPC 竞赛情况','查看我校 XCPC 参赛表现与成绩，关注校队的比赛历程。',true,'LINK','https://hei-maom.github.io/xcpcrating/#/school/%E6%B7%B1%E5%9C%B3%E8%81%8C%E4%B8%9A%E6%8A%80%E6%9C%AF%E5%A4%A7%E5%AD%A6'),
('算法知识 · OI Wiki','查阅算法与数据结构知识，从基础入门到专题学习。',true,'LINK','https://oi-wiki.org/'),
('算法交流群与比赛赛站群','查找算法交流和各比赛赛站群，交流备赛经验、获取赛事消息。',true,'LINK','https://acmer.info/groups');
