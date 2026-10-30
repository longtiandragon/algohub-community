package com.szpu.algohub.problem;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProblemGroupMapper {

    @Insert("""
            insert into problem_group (canonical_key, title, note, created_by)
            values (#{canonicalKey}, #{title,jdbcType=VARCHAR}, #{note,jdbcType=VARCHAR}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertGroup(ProblemGroup group);

    @Select("select * from problem_group where id = #{id}")
    ProblemGroup findGroup(@Param("id") Long id);

    @Select("select * from problem_group where upper(canonical_key) = upper(#{key})")
    ProblemGroup findGroupByKey(@Param("key") String key);

    @Select("""
            select g.*, (select count(*) from problem_group_member m where m.group_id = g.id) as "memberCount"
              from problem_group g
             order by g.created_at desc
             limit #{limit}
            """)
    List<ProblemGroup> listGroups(@Param("limit") int limit);

    @Update("""
            update problem_group
               set title = #{title,jdbcType=VARCHAR}, note = #{note,jdbcType=VARCHAR}, updated_at = now()
             where id = #{id}
            """)
    int updateGroup(ProblemGroup group);

    @Delete("delete from problem_group where id = #{id}")
    int deleteGroup(@Param("id") Long id);

    @Insert("""
            insert into problem_group_member (group_id, problem_id, relation, source, created_by)
            values (#{groupId}, #{problemId}, #{relation}, 'MANUAL', #{createdBy})
            """)
    int insertMember(@Param("groupId") Long groupId, @Param("problemId") Long problemId,
                     @Param("relation") String relation, @Param("createdBy") Long createdBy);

    @Delete("delete from problem_group_member where problem_id = #{problemId}")
    int deleteMemberByProblem(@Param("problemId") Long problemId);

    @Select("""
            select m.problem_id as "problemId", p.platform, p.problem_key as "problemKey", p.title,
                   p.url, m.relation, m.source, m.created_at as "createdAt"
              from problem_group_member m
              join problem p on p.id = m.problem_id
             where m.group_id = #{groupId}
             order by p.platform, p.problem_key
            """)
    List<Map<String, Object>> listMembers(@Param("groupId") Long groupId);

    @Select("select group_id from problem_group_member where problem_id = #{problemId}")
    Long findGroupIdByProblem(@Param("problemId") Long problemId);

    /** 题目搜索：按平台、题号或标题匹配，供管理员建立映射。 */
    @Select("""
            select p.id, p.platform, p.problem_key as "problemKey", p.title, p.url,
                   p.difficulty_label as "difficultyLabel",
                   m.group_id as "groupId", g.canonical_key as "groupKey"
              from problem p
              left join problem_group_member m on m.problem_id = p.id
              left join problem_group g on g.id = m.group_id
             where (#{platform,jdbcType=VARCHAR} is null or p.platform = #{platform,jdbcType=VARCHAR})
               and (#{keyword,jdbcType=VARCHAR} is null
                    or p.problem_key ilike '%' || #{keyword,jdbcType=VARCHAR} || '%' escape '\\'
                    or p.title ilike '%' || #{keyword,jdbcType=VARCHAR} || '%' escape '\\')
             order by p.platform, p.problem_key
             limit #{limit}
            """)
    List<Map<String, Object>> searchProblems(@Param("platform") String platform,
                                             @Param("keyword") String keyword,
                                             @Param("limit") int limit);

    /** 映射覆盖情况：已建立的题目组数量与被映射的题目数量。 */
    @Select("""
            select (select count(*) from problem_group) as "groupCount",
                   (select count(*) from problem_group_member) as "mappedProblemCount"
            """)
    Map<String, Object> mappingCoverage();
}
