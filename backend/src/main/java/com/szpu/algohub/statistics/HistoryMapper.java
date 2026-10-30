package com.szpu.algohub.statistics;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface HistoryMapper {
    @Select("""
        select distinct p.problem_key as pid, p.title, p.url, p.difficulty_raw as difficulty,
          (select coalesce(json_agg(t.tag_raw order by t.tag_raw)::text, '[]')
           from problem_tag t where t.problem_id=p.id) as "tagsJson"
        from user_solved_problem s join problem p on p.id=s.problem_id
        where s.user_id=#{userId} and s.platform=#{platform} and s.platform <> 'LANQIAO'
        order by pid limit 100001
        """)
    List<Map<String,Object>> solvedRows(@Param("userId") Long userId, @Param("platform") String platform);
    String FILTER = """
        from (
          select id,account_id,problem_id,platform,platform_sub_id,verdict,accepted,submitted_at,time_precision,
                 'SUBMISSION'::text as record_kind from submission
          union all
          select -u.id,u.account_id,u.problem_id,u.platform,null,'AC',true,u.earliest_known_ac_at,
                 u.first_ac_confidence,'SOLVED'
          from user_solved_problem u
          where not exists(select 1 from submission actual where actual.account_id=u.account_id
                           and actual.problem_id=u.problem_id and actual.accepted)
        ) s join platform_account a on a.id=s.account_id
        left join problem p on p.id=s.problem_id
        where a.user_id=#{userId} and a.platform <> 'LANQIAO'
          and (#{platform,jdbcType=VARCHAR} is null or s.platform=#{platform})
          and (#{accepted,jdbcType=BOOLEAN} is null or s.accepted=#{accepted})
          and (cast(#{from,jdbcType=TIMESTAMP} as timestamptz) is null or s.submitted_at >= #{from})
          and (cast(#{until,jdbcType=TIMESTAMP} as timestamptz) is null or s.submitted_at < #{until})
        """;
    @Select("""
        select s.id, s.platform, s.platform_sub_id as "submissionId", s.verdict, s.accepted,
               s.submitted_at as "submittedAt", s.time_precision as "timePrecision", s.record_kind as "recordKind",
               p.problem_key as "problemKey", p.title, p.url, p.difficulty_label as difficulty,
               p.difficulty_source as "difficultySource",
               (select string_agg(t.tag_raw,' | ' order by t.tag_raw) from problem_tag t where t.problem_id=p.id) as tags
        """ + FILTER + " order by s.submitted_at desc nulls last, s.id desc limit #{limit} offset #{offset}")
    List<Map<String,Object>> rows(@Param("userId") Long userId, @Param("platform") String platform,
        @Param("accepted") Boolean accepted, @Param("from") Instant from, @Param("until") Instant until,
        @Param("limit") int limit, @Param("offset") long offset);

    @Select("select count(*) " + FILTER)
    long count(@Param("userId") Long userId, @Param("platform") String platform,
        @Param("accepted") Boolean accepted, @Param("from") Instant from, @Param("until") Instant until);

    String PUBLIC_FILTER = FILTER + " and a.ownership_status = 'VERIFIED' ";

    @Select("""
        select s.id, s.platform, s.platform_sub_id as "submissionId", s.verdict, s.accepted,
               s.submitted_at as "submittedAt", s.time_precision as "timePrecision", s.record_kind as "recordKind",
               p.problem_key as "problemKey", p.title, p.url, p.difficulty_label as difficulty,
               p.difficulty_source as "difficultySource",
               (select string_agg(t.tag_raw,' | ' order by t.tag_raw) from problem_tag t where t.problem_id=p.id) as tags
        """ + PUBLIC_FILTER + " order by s.submitted_at desc nulls last, s.id desc limit #{limit} offset #{offset}")
    List<Map<String,Object>> publicRows(@Param("userId") Long userId, @Param("platform") String platform,
        @Param("accepted") Boolean accepted, @Param("from") Instant from, @Param("until") Instant until,
        @Param("limit") int limit, @Param("offset") long offset);

    @Select("select count(*) " + PUBLIC_FILTER)
    long publicCount(@Param("userId") Long userId, @Param("platform") String platform,
        @Param("accepted") Boolean accepted, @Param("from") Instant from, @Param("until") Instant until);
}
