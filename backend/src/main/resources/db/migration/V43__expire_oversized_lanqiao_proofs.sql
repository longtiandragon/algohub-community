-- 旧蓝桥验证串会被外站截断；使其过期，由原有续签流程生成短串。
update binding_request
set expires_at = least(expires_at, clock_timestamp()), attempt_token = null, attempt_until = null
where platform = 'LANQIAO' and verified_at is null and length(token) > 19;
