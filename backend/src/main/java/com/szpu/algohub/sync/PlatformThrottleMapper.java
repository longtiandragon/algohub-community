package com.szpu.algohub.sync;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 平台级冷却：某平台限流后，同平台所有账号的任务一起退避。 */
@Mapper
public interface PlatformThrottleMapper {

    /** 冷却到 now()+seconds；已有更晚的冷却时间则保留更晚的，不会被缩短。 */
    @Insert("""
            insert into platform_throttle (platform, blocked_until, reason, updated_at)
            values (#{platform}, now() + (#{seconds} * interval '1 second'), #{reason,jdbcType=VARCHAR}, now())
            on conflict (platform) do update
               set blocked_until = greatest(platform_throttle.blocked_until, excluded.blocked_until),
                   reason = excluded.reason,
                   updated_at = now()
            """)
    int coolDown(@Param("platform") String platform, @Param("seconds") long seconds, @Param("reason") String reason);

    @Select("""
            select platform, blocked_until as "blockedUntil", reason
              from platform_throttle
             where blocked_until > now()
             order by blocked_until desc
            """)
    List<Map<String, Object>> active();
}
