package com.szpu.algohub.resource;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NavResourceMapper {

    @Select("""
            select * from nav_resource
             where (#{includeDisabled} = true or enabled = true)
             order by category, sort_order, id
            """)
    List<NavResource> findAll(@Param("includeDisabled") boolean includeDisabled);

    @Insert("""
            insert into nav_resource (category, title, url, description, sort_order, enabled)
            values (#{category}, #{title}, #{url}, #{description,jdbcType=VARCHAR}, #{sortOrder}, #{enabled})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NavResource resource);

    @Update("""
            update nav_resource
               set category = #{category}, title = #{title}, url = #{url},
                   description = #{description,jdbcType=VARCHAR}, sort_order = #{sortOrder}, enabled = #{enabled}
             where id = #{id}
            """)
    int update(NavResource resource);

    @Delete("delete from nav_resource where id = #{id}")
    int delete(@Param("id") Long id);
}
