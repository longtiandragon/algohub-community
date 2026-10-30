package com.szpu.algohub.auth.identity;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IdentityMapper {

    // ---------- 隐私设置 ----------

    @Select("select * from user_privacy where user_id = #{userId}")
    UserPrivacy findPrivacy(@Param("userId") Long userId);

    @Insert("""
            insert into user_privacy (user_id, profile_visibility, show_on_ranking, show_accounts,
                                      show_student_no, show_email, public_activity, updated_at)
            values (#{userId}, #{profileVisibility}, #{showOnRanking}, #{showAccounts},
                    #{showStudentNo}, #{showEmail}, #{publicActivity}, now())
            on conflict (user_id) do update
               set profile_visibility = excluded.profile_visibility,
                   show_on_ranking = excluded.show_on_ranking,
                   show_accounts = excluded.show_accounts,
                   show_student_no = excluded.show_student_no,
                   show_email = excluded.show_email,
                   public_activity = excluded.public_activity,
                   updated_at = now()
            """)
    int savePrivacy(UserPrivacy privacy);

    @Insert("insert into user_privacy (user_id) values (#{userId}) on conflict (user_id) do nothing")
    int ensurePrivacy(@Param("userId") Long userId);

    // ---------- 第三方账号绑定 ----------

    @Select("select * from user_oauth_binding where user_id = #{userId} order by provider")
    List<OauthBinding> findBindingsByUser(@Param("userId") Long userId);

    @Select("""
            select * from user_oauth_binding
             where provider = #{provider} and provider_uid = #{providerUid}
            """)
    OauthBinding findBinding(@Param("provider") String provider, @Param("providerUid") String providerUid);

    @Insert("""
            insert into user_oauth_binding (user_id, provider, provider_uid, login, display_name,
                                            avatar_url, profile_url, last_login_at)
            values (#{userId}, #{provider}, #{providerUid}, #{login,jdbcType=VARCHAR},
                    #{displayName,jdbcType=VARCHAR}, #{avatarUrl,jdbcType=VARCHAR},
                    #{profileUrl,jdbcType=VARCHAR}, now())
            on conflict (user_id, provider) do update
               set provider_uid = excluded.provider_uid,
                   login = excluded.login,
                   display_name = excluded.display_name,
                   avatar_url = excluded.avatar_url,
                   profile_url = excluded.profile_url,
                   last_login_at = now()
             where user_oauth_binding.provider_uid = excluded.provider_uid
            """)
    int saveBinding(OauthBinding binding);

    /** 第三方已验证的邮箱直接作为登录邮箱（与邮箱验证码首次登录写入同一张表） */
    @Insert("insert into verified_login_email (user_id, email) values (#{userId}, #{email})")
    int insertVerifiedEmail(@Param("userId") Long userId, @Param("email") String email);

    @Update("update user_oauth_binding set last_login_at = now() where id = #{id}")
    int touchBinding(@Param("id") Long id);

    @Delete("delete from user_oauth_binding where user_id = #{userId} and provider = #{provider}")
    int deleteBinding(@Param("userId") Long userId, @Param("provider") String provider);

    @Delete("delete from email_login_challenge where email in (select email from verified_login_email where user_id = #{userId})")
    int deleteLoginChallenges(@Param("userId") Long userId);

    @Delete("delete from password_reset_challenge where user_id = #{userId}")
    int deleteResetChallenges(@Param("userId") Long userId);

    @Delete("delete from email_verification_challenge where user_id = #{userId}")
    int deleteBindingChallenges(@Param("userId") Long userId);

    // ---------- 认证审计 ----------

    @Insert("""
            insert into auth_event (user_id, username, event, ip, user_agent, detail)
            values (#{userId,jdbcType=BIGINT}, #{username,jdbcType=VARCHAR}, #{event},
                    #{ip,jdbcType=VARCHAR}, #{userAgent,jdbcType=VARCHAR}, #{detail,jdbcType=VARCHAR})
            """)
    int insertEvent(@Param("userId") Long userId, @Param("username") String username,
                    @Param("event") String event, @Param("ip") String ip,
                    @Param("userAgent") String userAgent, @Param("detail") String detail);

    @Select("""
            select event, ip, user_agent as "userAgent", detail, created_at as "createdAt"
              from auth_event
             where user_id = #{userId}
             order by created_at desc
             limit #{limit}
            """)
    List<Map<String, Object>> recentEvents(@Param("userId") Long userId, @Param("limit") int limit);
}
