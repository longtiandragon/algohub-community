-- 只更新能力标记，不删除原有总量快照；下次同步自动回填公开练习历史。
update platform_account set supports_history = true, updated_at = now() where platform = 'HDU';
