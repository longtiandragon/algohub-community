package com.szpu.algohub.auth;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppUserMapper {
    @Select("""
        select exists(select 1 from school_administrator a
          join app_user viewer on viewer.id=a.user_id and viewer.status='ACTIVE' and viewer.role='STUDENT'
          join school_member m on m.school_id=a.school_id and m.status='APPROVED'
          join app_user target on target.id=m.user_id and target.status='ACTIVE' and target.role='STUDENT'
          where a.user_id=#{viewerId} and m.user_id=#{targetId})
        """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    boolean managesStudentProfile(@Param("viewerId") Long viewerId, @Param("targetId") Long targetId);

    @Select("select s.id, s.name from school_administrator a join school s on s.id=a.school_id where a.user_id=#{id} order by s.name")
    List<java.util.Map<String,Object>> managedSchools(@Param("id") Long id);


    @Select("select * from app_user where id = #{id}")
    AppUser findById(@Param("id") Long id);

    @Select("select * from app_user where lower(username) = lower(#{username})")
    AppUser findByUsername(@Param("username") String username);

    @Select("select u.* from app_user u join verified_login_email e on e.user_id=u.id where e.email=lower(#{email})")
    AppUser findByVerifiedEmail(@Param("email") String email);

    // 等到锁以后要重新读：MyBatis 一级缓存里可能是加锁前的旧数据
    @Select("select * from app_user where id=#{id} for update")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    AppUser lockIdentity(@Param("id") Long id);

    @Select("select count(*) from verified_login_email where user_id=#{id}")
    int verifiedEmailCount(@Param("id") Long id);

    @Select("select * from app_user order by id")
    List<AppUser> findAll();

    @Insert("""
            insert into app_user (username, password_hash, display_name, email, role, status, nickname_needs_review)
            values (#{username}, #{passwordHash}, #{displayName}, #{email,jdbcType=VARCHAR}, #{role}, #{status}, #{nicknameNeedsReview})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AppUser user);

    @Update("""
        update app_user set display_name=#{displayName}, enrollment_year=#{enrollmentYear,jdbcType=INTEGER},
        class_name=#{className,jdbcType=VARCHAR},student_no=#{studentNo,jdbcType=VARCHAR},
        onboarding_completed=#{onboardingCompleted},nickname_needs_review=#{nicknameNeedsReview},updated_at=now() where id=#{id}
        """)
    int updateOnboarding(AppUser user);

    @Update("""
            update app_user
               set display_name = #{displayName},
                   email = #{email,jdbcType=VARCHAR},
                   updated_at = now()
             where id = #{id}
            """)
    int updateProfile(AppUser user);

    @Update("update app_user set password_hash = #{passwordHash}, updated_at = now() where id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    @Update("""
            update app_user
               set display_name = #{displayName},
                   email = #{email,jdbcType=VARCHAR},
                   bio = #{bio,jdbcType=VARCHAR},
                   nickname_needs_review = #{nicknameNeedsReview},
                   updated_at = now()
             where id = #{id}
            """)
    int updateDetail(AppUser user);

    @Update("update app_user set last_login_at = now(), updated_at = now() where id = #{id}")
    int touchLastLogin(@Param("id") Long id);

    /** 改密或“退出所有设备”后推进该时间，早于它签发的访问令牌全部失效。 */
    @Update("update app_user set tokens_valid_from = clock_timestamp(), updated_at = now() where id = #{id}")
    int invalidateIssuedTokens(@Param("id") Long id);

    @Select("select count(*) from app_user where role = 'ADMIN' and status = 'ACTIVE'")
    int countActiveAdmins();

    @org.apache.ibatis.annotations.Delete("delete from app_user where id = #{id}")
    int delete(@Param("id") Long id);

    @Update("update app_user set role = #{role}, updated_at = now() where id = #{id}")
    int updateRole(@Param("id") Long id, @Param("role") String role);
}
