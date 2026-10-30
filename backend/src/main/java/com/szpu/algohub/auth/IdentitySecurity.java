package com.szpu.algohub.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.szpu.algohub.auth.identity.IdentityMapper;

/**
 * 登录方式变了（换登录邮箱、绑定或解绑 GitHub）：所有设备退出，没完成的验证码、授权一并作废。
 * 「身份版本」就是令牌生效时间：每次撤销都会往后推，刷新令牌、敏感操作凭证、GitHub 授权都记着签发时的版本，对不上就失效。
 */
@Service
public class IdentitySecurity {

    private final AppUserMapper users;
    private final TokenStore tokens;
    private final IdentityMapper identities;

    public IdentitySecurity(AppUserMapper users, TokenStore tokens, IdentityMapper identities) {
        this.users = users;
        this.tokens = tokens;
        this.identities = identities;
    }

    public static String version(AppUser user) {
        return user.getTokensValidFrom() == null ? "" : user.getTokensValidFrom().toString();
    }

    @Transactional
    public void changed(long userId) {
        users.lockIdentity(userId);
        users.invalidateIssuedTokens(userId);
        tokens.revokeAllSessions(userId);
        identities.deleteResetChallenges(userId);
        identities.deleteLoginChallenges(userId);
        identities.deleteBindingChallenges(userId);
    }
}
