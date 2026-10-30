package com.szpu.algohub.account;

import java.time.Instant;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlatformCredentialMapper {

    @Select("select cipher_text from platform_credential where account_id = #{accountId} and status = 'ACTIVE'")
    String findCipherText(@Param("accountId") Long accountId);

    @Select("select status from platform_credential where account_id = #{accountId}")
    String findStatus(@Param("accountId") Long accountId);

    @Select("select last_verified_at from platform_credential where account_id = #{accountId}")
    Instant findLastVerifiedAt(@Param("accountId") Long accountId);

    @Insert("""
            insert into platform_credential (account_id, cipher_text, status, last_verified_at)
            values (#{accountId}, #{cipherText}, 'ACTIVE', now())
            on conflict (account_id) do update
               set cipher_text = excluded.cipher_text,
                   cred_version = platform_credential.cred_version + 1,
                   status = 'ACTIVE',
                   last_verified_at = now(),
                   updated_at = now()
            """)
    int save(@Param("accountId") Long accountId, @Param("cipherText") String cipherText);

    @Update("update platform_credential set status = #{status}, updated_at = now() where account_id = #{accountId}")
    int updateStatus(@Param("accountId") Long accountId, @Param("status") String status);

    @Delete("delete from platform_credential where account_id = #{accountId}")
    int delete(@Param("accountId") Long accountId);
}
