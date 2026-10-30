package com.szpu.algohub.school;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SchoolMemberMapper {
    /** 管理员改成员资料的审计记录 */
    @Insert("insert into auth_event(user_id, event, detail) values (#{actor}, 'ADMIN_MEMBER_PROFILE_CHANGED', concat('memberId=', #{memberId}))")
    int auditProfile(@Param("actor") Long actor, @Param("memberId") Long memberId);

    @Select("select id from app_user where id=#{userId} for update")
    Long lockUser(Long userId);

    @Select("""
            select m.*, u.username, u.display_name, s.name as school_name
              from school_member m
              join app_user u on u.id = m.user_id
              join school s on s.id = m.school_id
             where m.user_id = #{userId}
             order by m.id
            """)
    List<SchoolMember> findByUser(@Param("userId") Long userId);

    @Select("""
            select m.*, u.username, u.display_name, s.name as school_name
              from school_member m
              join app_user u on u.id = m.user_id
              join school s on s.id = m.school_id
             where m.id = #{id}
            """)
    SchoolMember findById(@Param("id") Long id);

    @Select("""
            select m.*, u.username, u.display_name, s.name as school_name
              from school_member m
              join app_user u on u.id = m.user_id
              join school s on s.id = m.school_id
             where m.school_id = #{schoolId}
               and (#{status,jdbcType=VARCHAR} is null or m.status = #{status,jdbcType=VARCHAR})
             order by m.status, m.applied_at desc
            """)
    List<SchoolMember> findBySchool(@Param("schoolId") Long schoolId, @Param("status") String status);

    @Insert("""
            insert into school_member (school_id, user_id, student_no, enrollment_year, class_name, member_role, status)
            values (#{schoolId}, #{userId}, #{studentNo,jdbcType=VARCHAR}, #{enrollmentYear,jdbcType=INTEGER},
                    #{className,jdbcType=VARCHAR}, #{memberRole}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SchoolMember member);

    @Update("""
            update school_member
               set status = #{status},
                   reviewed_at = now(),
                   reviewed_by = #{reviewerId},
                   reject_reason = #{reason,jdbcType=VARCHAR}
             where id = #{id}
            """)
    int review(@Param("id") Long id, @Param("status") String status,
               @Param("reviewerId") Long reviewerId, @Param("reason") String reason);

    @Update("""
            update school_member
               set enrollment_year = #{enrollmentYear,jdbcType=INTEGER},
                   class_name = #{className,jdbcType=VARCHAR},
                   student_no = #{studentNo,jdbcType=VARCHAR}
             where id = #{id}
            """)
    int updateProfile(SchoolMember member);

    @Select("""
            select count(*) from school_member
             where user_id = #{userId} and status = 'APPROVED'
            """)
    int countApproved(@Param("userId") Long userId);

    /** 已通过的学校；部分唯一索引保证每人至多一行。 */
    @Select("select school_id from school_member where user_id = #{userId} and status = 'APPROVED'")
    Long approvedSchoolId(@Param("userId") Long userId);
}
