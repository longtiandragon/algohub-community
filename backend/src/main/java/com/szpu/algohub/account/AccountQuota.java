package com.szpu.algohub.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;

/**
 * 每个用户在每个平台上的账号数上限（正式绑定、待认领、个人关注都算）。每个账号都会带来后台同步请求，
 * 不设上限就能用一个本站账号挂上大量外站账号，放大对源站的请求。
 * 已有同一平台 UID 的记录（刷新、认领）不占新名额。
 */
@Component
public class AccountQuota {

    private final JdbcTemplate jdbc;
    private final int maxPerPlatform;

    public AccountQuota(JdbcTemplate jdbc, @Value("${algohub.accounts.max-per-platform:3}") int maxPerPlatform) {
        if (maxPerPlatform < 1) {
            throw new IllegalArgumentException("algohub.accounts.max-per-platform 必须大于 0");
        }
        this.jdbc = jdbc;
        this.maxPerPlatform = maxPerPlatform;
    }

    public int maxPerPlatform() {
        return maxPerPlatform;
    }

    /**
     * 新增一个账号前调用。locked=true 时在当前事务里锁住用户行，同一用户并发添加时串行计数，
     * 必须在事务内使用；只做预检（外站请求之前）时传 false。
     */
    public void requireSlot(Long userId, String platform, String platformUid, boolean locked) {
        if (locked) {
            jdbc.queryForObject("select id from app_user where id = ? for update", Long.class, userId);
        }
        if (platformUid != null) {
            Integer mine = jdbc.queryForObject("""
                    select count(*) from platform_account where user_id = ? and platform = ? and platform_uid = ?
                    """, Integer.class, userId, platform, platformUid);
            if (mine != null && mine > 0) {
                return;
            }
        }
        Integer count = jdbc.queryForObject("select count(*) from platform_account where user_id = ? and platform = ?",
                Integer.class, userId, platform);
        if (count != null && count >= maxPerPlatform) {
            throw ApiException.conflict("每个平台最多添加 " + maxPerPlatform + " 个账号（正式绑定、待认领和个人关注都算），"
                    + "你在" + Platform.parse(platform).getDisplayName() + "上已经有 " + count + " 个，请先解绑不用的账号");
        }
    }
}
