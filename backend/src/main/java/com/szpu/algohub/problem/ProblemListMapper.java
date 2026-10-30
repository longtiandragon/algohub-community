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

/**
 * 题单查询。
 *
 * 完成进度用 user_solved_problem 实时计算，不单独保存“已完成”标记：
 * 重算统计或解绑账号后进度会跟着回退，不会出现两份对不上的数据。
 */
@Mapper
public interface ProblemListMapper {

    @Insert("""
            insert into problem_list (code, title, description, category, platform, source,
                                      generated_rule, visible, sort_order, created_by,
                                      owner_id, share_scope, school_id)
            values (#{code}, #{title}, #{description,jdbcType=VARCHAR}, #{category,jdbcType=VARCHAR},
                    #{platform,jdbcType=VARCHAR}, #{source}, #{generatedRule,jdbcType=VARCHAR},
                    #{visible}, #{sortOrder}, #{createdBy,jdbcType=BIGINT},
                    #{ownerId,jdbcType=BIGINT}, #{shareScope}, #{schoolId,jdbcType=BIGINT})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertList(ProblemList list);

    @Select("select * from problem_list where id = #{id}")
    ProblemList findById(@Param("id") Long id);

    @Select("select * from problem_list where code = #{code}")
    ProblemList findByCode(@Param("code") String code);

    /**
     * 详情用：除题单本身外，把作者昵称与当前用户的收藏状态一起带出来，
     * 免得前端为了这几个字段再拉一次列表接口。
     */
    @Select("""
            select l.*,
                   u.display_name as "ownerName",
                   (select count(*) from problem_list_favorite f where f.list_id = l.id) as "favoriteCount",
                   exists (select 1 from problem_list_favorite f
                            where f.list_id = l.id and f.user_id = #{userId}) as "favorited"
              from problem_list l
              left join app_user u on u.id = l.owner_id
             where l.id = #{id}
            """)
    ProblemList findByIdForUser(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 题单概览：题目数、当前用户已通过数、作者与收藏状态一次算好。
     *
     * 可见性：自己的题单总是可见；PUBLIC 全站可见；SCHOOL 只对同一所学校（schoolId 由服务端按查看者的
     * 成员记录传入）可见；PRIVATE 只有作者。系统题单（owner_id 为空）另需 visible。
     * 管理员视角（includeHidden）可以看到全部。
     */
    @Select("""
            <script>
            select l.*,
                   u.display_name as "ownerName",
                   (select count(*) from problem_list_item i where i.list_id = l.id) as "itemCount",
                   (select count(*)
                      from problem_list_item i
                      join user_solved_problem s
                        on s.problem_id = i.problem_id and s.user_id = #{userId}
                     where i.list_id = l.id) as "solvedCount",
                   (select count(*) from problem_list_favorite f where f.list_id = l.id) as "favoriteCount",
                   exists (select 1 from problem_list_favorite f
                            where f.list_id = l.id and f.user_id = #{userId}) as "favorited"
              from problem_list l
              left join app_user u on u.id = l.owner_id
             where 1 = 1
            <if test="!includeHidden">
               and (
                     l.owner_id = #{userId}
                  or ((l.owner_id is not null or l.visible = true)
                      and (l.share_scope = 'PUBLIC'
                           or (l.share_scope = 'SCHOOL' and l.school_id = #{schoolId,jdbcType=BIGINT})))
                   )
            </if>
            <if test="scope == 'MINE'"> and l.owner_id = #{userId} </if>
            <if test="scope == 'FAVORITE'">
               and exists (select 1 from problem_list_favorite f
                            where f.list_id = l.id and f.user_id = #{userId})
            </if>
            <if test="scope == 'SYSTEM'"> and l.owner_id is null </if>
             order by l.sort_order, l.id
            </script>
            """)
    List<ProblemList> findAll(@Param("userId") Long userId, @Param("schoolId") Long schoolId,
                              @Param("includeHidden") boolean includeHidden, @Param("scope") String scope);

    @Update("""
            update problem_list
               set title = #{title}, description = #{description,jdbcType=VARCHAR},
                   category = #{category,jdbcType=VARCHAR}, visible = #{visible},
                   share_scope = #{shareScope}, school_id = #{schoolId,jdbcType=BIGINT},
                   sort_order = #{sortOrder}, updated_at = now()
             where id = #{id}
            """)
    int updateList(ProblemList list);

    @Delete("delete from problem_list where id = #{id}")
    int deleteList(@Param("id") Long id);

    /** 题单内容：带题目信息与「我是否已通过」。 */
    @Select("""
            select i.id as "itemId", i.position, i.note,
                   p.id as "problemId", p.platform, p.problem_key as "problemKey", p.title, p.url,
                   p.difficulty_label as "difficultyLabel", p.difficulty_value as "difficultyValue",
                   p.difficulty_source as "difficultySource", p.solved_count as "solvedCount",
                   exists (select 1 from user_solved_problem s
                            where s.problem_id = p.id and s.user_id = #{userId}) as "solvedByMe"
              from problem_list_item i
              join problem p on p.id = i.problem_id
             where i.list_id = #{listId}
             order by i.position, i.id
            """)
    List<Map<String, Object>> findItems(@Param("listId") Long listId, @Param("userId") Long userId);

    @Insert("""
            insert into problem_list_item (list_id, problem_id, position, note)
            values (#{listId}, #{problemId}, #{position}, #{note,jdbcType=VARCHAR})
            on conflict (list_id, problem_id) do nothing
            """)
    int insertItem(@Param("listId") Long listId, @Param("problemId") Long problemId,
                   @Param("position") int position, @Param("note") String note);

    @Delete("delete from problem_list_item where list_id = #{listId} and problem_id = #{problemId}")
    int deleteItem(@Param("listId") Long listId, @Param("problemId") Long problemId);

    @Select("select coalesce(max(position), 0) from problem_list_item where list_id = #{listId}")
    int maxPosition(@Param("listId") Long listId);

    @Update("""
            update problem_list_item set position = #{position}
             where list_id = #{listId} and problem_id = #{problemId}
            """)
    int updatePosition(@Param("listId") Long listId, @Param("problemId") Long problemId,
                       @Param("position") int position);

    /**
     * 题单的校内完成榜：只列查看者本校的同学（全站题单也一样），
     * 参与资格统一取自 school_board_account，与排行榜口径一致。
     */
    @Select("""
            select e.user_id as "userId", e.display_name as "displayName", e.avatar_url as "avatarUrl",
                   count(distinct i.problem_id) as "solvedCount",
                   max(s.earliest_known_ac_at) as "lastSolvedAt"
              from problem_list_item i
              join user_solved_problem s on s.problem_id = i.problem_id
              join school_board_account e on e.account_id = s.account_id and e.school_id = #{schoolId}
             where i.list_id = #{listId}
             group by e.user_id, e.display_name, e.avatar_url
             order by "solvedCount" desc, "lastSolvedAt"
             limit #{limit}
            """)
    List<Map<String, Object>> leaderboard(@Param("schoolId") Long schoolId, @Param("listId") Long listId,
                                          @Param("limit") int limit);

    @Insert("""
            insert into problem_list_favorite (user_id, list_id) values (#{userId}, #{listId})
            on conflict do nothing
            """)
    int addFavorite(@Param("userId") Long userId, @Param("listId") Long listId);

    @Delete("delete from problem_list_favorite where user_id = #{userId} and list_id = #{listId}")
    int removeFavorite(@Param("userId") Long userId, @Param("listId") Long listId);

    @Select("select count(*) from problem_list where owner_id = #{userId}")
    int countOwned(@Param("userId") Long userId);

    /**
     * 按规则从题库挑题：指定平台与难度区间，按平台公开通过人数排序取前若干。
     * 只取题库已同步的题；没有通过人数的平台按题号排序，保证结果可复现。
     */
    @Select("""
            <script>
            select p.id
              from problem p
             where p.platform = #{platform}
               and p.catalog_synced_at is not null
               and p.difficulty_value between #{minDifficulty} and #{maxDifficulty}
            <if test="tagCategory != null">
               and exists (select 1 from problem_tag t
                             left join tag_category_mapping m
                                    on m.platform = p.platform and m.tag_raw = t.tag_raw
                            where t.problem_id = p.id
                              and coalesce(m.category, 'UNMAPPED') = #{tagCategory})
            </if>
             order by p.solved_count desc nulls last, p.problem_key
             limit #{size}
            </script>
            """)
    List<Long> pickByRule(@Param("platform") String platform, @Param("minDifficulty") int minDifficulty,
                          @Param("maxDifficulty") int maxDifficulty,
                          @Param("tagCategory") String tagCategory, @Param("size") int size);
}
