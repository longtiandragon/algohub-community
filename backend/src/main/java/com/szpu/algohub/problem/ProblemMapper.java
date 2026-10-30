package com.szpu.algohub.problem;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProblemMapper {

    @Select("select * from problem where platform = #{platform} and problem_key = #{problemKey}")
    Problem find(@Param("platform") String platform, @Param("problemKey") String problemKey);

    @Select("select * from problem where id = #{id}")
    Problem findById(@Param("id") Long id);

    /**
     * 题目按平台与题号去重。难度与标题允许后续补全，但不会用未知值覆盖已有值。
     */
    @Insert("""
            insert into problem (platform, problem_key, title, url, difficulty_raw, difficulty_label,
                                 difficulty_source, difficulty_value, tags_complete, updated_at)
            values (#{platform}, #{problemKey}, #{title,jdbcType=VARCHAR}, #{url,jdbcType=VARCHAR},
                    #{difficultyRaw,jdbcType=VARCHAR}, #{difficultyLabel,jdbcType=VARCHAR},
                    #{difficultySource,jdbcType=VARCHAR}, #{difficultyValue,jdbcType=INTEGER}, #{tagsComplete}, now())
            on conflict (platform, problem_key) do update
               set title = coalesce(excluded.title, problem.title),
                   url = coalesce(excluded.url, problem.url),
                   difficulty_raw = coalesce(excluded.difficulty_raw, problem.difficulty_raw),
                   difficulty_label = coalesce(excluded.difficulty_label, problem.difficulty_label),
                   difficulty_source = case when excluded.difficulty_raw is not null
                       then excluded.difficulty_source else problem.difficulty_source end,
                   difficulty_value = coalesce(excluded.difficulty_value, problem.difficulty_value),
                   tags_complete = problem.tags_complete or excluded.tags_complete,
                   updated_at = now()
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(Problem problem);

    /**
     * 题库同步的批量写入：只补全难度、标签完整性与热度，不覆盖已有的非空标题与链接。
     */
    @Insert("""
            insert into problem (platform, problem_key, title, url, difficulty_raw, difficulty_label,
                                 difficulty_source, difficulty_value, solved_count, tags_complete,
                                 catalog_synced_at, updated_at)
            values (#{platform}, #{problemKey}, #{title,jdbcType=VARCHAR}, #{url,jdbcType=VARCHAR},
                    #{difficultyRaw,jdbcType=VARCHAR}, #{difficultyLabel,jdbcType=VARCHAR},
                    #{difficultySource,jdbcType=VARCHAR}, #{difficultyValue,jdbcType=INTEGER},
                    #{solvedCount,jdbcType=INTEGER}, #{tagsComplete}, now(), now())
            on conflict (platform, problem_key) do update
               set title = coalesce(excluded.title, problem.title),
                   url = coalesce(excluded.url, problem.url),
                   difficulty_raw = coalesce(excluded.difficulty_raw, problem.difficulty_raw),
                   difficulty_label = coalesce(excluded.difficulty_label, problem.difficulty_label),
                   difficulty_source = coalesce(excluded.difficulty_source, problem.difficulty_source),
                   difficulty_value = coalesce(excluded.difficulty_value, problem.difficulty_value),
                   solved_count = coalesce(excluded.solved_count, problem.solved_count),
                   tags_complete = problem.tags_complete or excluded.tags_complete,
                   catalog_synced_at = now(),
                   updated_at = now()
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsertCatalog(Problem problem);

    @Select("select count(*) from problem where platform = #{platform} and catalog_synced_at is not null")
    int countCatalog(@Param("platform") String platform);

    @Insert("""
            insert into problem_tag (problem_id, tag_raw, tag_source)
            values (#{problemId}, #{tagRaw}, #{tagSource})
            on conflict (problem_id, tag_raw) do nothing
            """)
    int insertTag(@Param("problemId") Long problemId, @Param("tagRaw") String tagRaw,
                  @Param("tagSource") String tagSource);
}
