package com.szpu.algohub.school;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SchoolMapper {

    @Select("select * from school where id = #{id}")
    School findById(@Param("id") Long id);

    @Select("select * from school where upper(invite_code) = upper(#{code})")
    School findByInviteCode(@Param("code") String code);

    @Select("select * from school order by id")
    List<School> findAll();

    /** 换邀请码：旧码立刻失效 */
    @Update("update school set invite_code = #{code} where id = #{id}")
    int updateInviteCode(@Param("id") Long id, @Param("code") String code);
}
