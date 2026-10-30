-- 提交记录的 source 是当初区分「同步 / 文件导入」加的；文件导入已删（V34），剩下全是 SYNC，删掉这一列和它的索引
drop index if exists idx_submission_source;
alter table submission drop column if exists source;
