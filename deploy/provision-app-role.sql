-- 在备份、审阅后，以数据库所有者 algohub 连接 algohub 库执行。
-- 不切换应用、不修改现有密码；随后用 psql 的 \password algohub_app 交互设置密码。
-- 应用首次迁移须先完成；保留 Flyway 的所有者连接与 DB_PASSWORD。
\set ON_ERROR_STOP on
BEGIN;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'algohub_app') THEN
    CREATE ROLE algohub_app LOGIN;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member
             WHERE r.rolname='algohub_app') THEN
    RAISE EXCEPTION 'algohub_app 已有其他角色成员资格，请先人工审查，不能按新受限角色使用';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_roles r ON r.oid=c.relowner
             WHERE r.rolname='algohub_app') THEN
    RAISE EXCEPTION 'algohub_app 已拥有数据库对象，请先人工审查其 DDL 权限';
  END IF;
END $$;
ALTER ROLE algohub_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT CONNECT ON DATABASE algohub TO algohub_app;
GRANT USAGE ON SCHEMA public TO algohub_app;
REVOKE CREATE ON SCHEMA public FROM algohub_app;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM algohub_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM algohub_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO algohub_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO algohub_app;
-- 后续迁移由 algohub 创建对象，授予新业务表相同的 DML 权限。
ALTER DEFAULT PRIVILEGES FOR ROLE algohub IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO algohub_app;
ALTER DEFAULT PRIVILEGES FOR ROLE algohub IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO algohub_app;
-- 运行时不应篡改迁移账本。
REVOKE ALL ON TABLE public.flyway_schema_history FROM algohub_app;
DO $$
BEGIN
  IF has_schema_privilege('algohub_app', 'public', 'CREATE') THEN
    RAISE EXCEPTION 'public schema 仍通过 PUBLIC 等路径允许建表，请审查并处理共享授权后再执行';
  END IF;
END $$;
COMMIT;
