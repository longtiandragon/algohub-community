package com.szpu.algohub.problem;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TagMappingMapper {
    @Delete("delete from tag_category_mapping where platform=#{platform} and tag_raw=#{tagRaw}")
    int remove(@Param("platform") String platform, @Param("tagRaw") String tagRaw);
    @Select("""
            with tags as (
                select p.platform, t.tag_raw, count(distinct p.id) as problem_count
                from problem_tag t join problem p on p.id=t.problem_id
                group by p.platform, t.tag_raw
            )
            select coalesce(t.platform,m.platform) as platform,
                   coalesce(t.tag_raw,m.tag_raw) as "tagRaw", m.category,
                   coalesce(t.problem_count,0) as "problemCount"
            from tags t full join tag_category_mapping m
              on m.platform=t.platform and m.tag_raw=t.tag_raw
            order by coalesce(t.platform,m.platform), coalesce(t.tag_raw,m.tag_raw)
            """)
    List<Map<String,Object>> list();

    @Insert("""
            insert into tag_category_mapping(platform,tag_raw,category)
            values(#{platform},#{tagRaw},#{category})
            on conflict(platform,tag_raw) do update set category=excluded.category
            """)
    int save(@Param("platform") String platform, @Param("tagRaw") String tagRaw,
             @Param("category") String category);
}
