package com.szpu.algohub.auth.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.szpu.algohub.auth.AppUser;
import com.szpu.algohub.auth.AppUserMapper;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.auth.TokenStore;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.ErrorKind;
import com.szpu.algohub.config.AppProperties;

/**
 * GitHub 账号绑定与登录。
 *
 * 约定：
 * - 授权链接里的 state 是一次性的，存在 Redis 中 10 分钟，回调时消费，防止 CSRF 与重放；
 * - 已登录时发起的是绑定（state 记录发起绑定的用户）；
 * - 未登录时发起的是 GitHub 登录：绑定过的直接登录；没绑定过的第一次登录自动开户，与邮箱验证码首次登录一致——
 *   不设密码、不自动加入学校，之后和注册一样选择学校；GitHub 已验证的主邮箱直接作为登录邮箱，取不到时再到站内验证邮箱；
 * - 这个邮箱已属于本站其他账号时不自动合并（避免借邮箱接管账号），请对方用原账号登录后在「账号安全」绑定；
 * - 只申请 read:user 与 user:email（只读），不读取仓库内容；
 * - 只保存 GitHub 的数字 ID、登录名与头像，不保存 access token。
 */
@Service
public class GithubOauthService {

    private static final Logger log = LoggerFactory.getLogger(GithubOauthService.class);
    public static final String PROVIDER = "GITHUB";
    private static final String SCOPE = "read:user user:email";
    private static final int DISPLAY_NAME_MAX = 32;
    private static final int USERNAME_MAX = 32;

    private final AppProperties properties;
    private final TokenStore tokenStore;
    private final IdentityMapper identityMapper;
    private final AppUserMapper userMapper;
    private final AuthService authService;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GithubOauthService(AppProperties properties, TokenStore tokenStore, IdentityMapper identityMapper,
                              AppUserMapper userMapper, AuthService authService, TransactionTemplate tx) {
        this.properties = properties;
        this.tokenStore = tokenStore;
        this.identityMapper = identityMapper;
        this.userMapper = userMapper;
        this.authService = authService;
        this.tx = tx;
    }

    public boolean isEnabled() {
        return properties.getOauth().getGithub().isEnabled();
    }

    private AppProperties.Oauth.Github config() {
        AppProperties.Oauth.Github github = properties.getOauth().getGithub();
        if (!github.isEnabled()) {
            throw new ApiException(ErrorKind.UNSUPPORTED,
                    "尚未配置 GitHub OAuth：请在 backend/local-secret/github.properties 中填写 algohub.oauth.github 的 client-id 与 client-secret");
        }
        return github;
    }

    /**
     * 生成授权链接。userId 为空表示“用 GitHub 登录”，非空表示已登录用户要绑定。
     */
    public Map<String, Object> authorizeUrl(Long userId) {
        AppProperties.Oauth.Github github = config();
        String state = tokenStore.createOauthState(userId == null ? 0L : userId, PROVIDER);
        String url = base(github.getWebBaseUrl()) + "/login/oauth/authorize"
                + "?client_id=" + enc(github.getClientId())
                + "&redirect_uri=" + enc(github.getRedirectUri())
                + "&scope=" + enc(SCOPE)
                + "&state=" + enc(state)
                // 还没有 GitHub 账号的新生可以在授权页顺手注册一个
                + "&allow_signup=true";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorizeUrl", url);
        result.put("state", state);
        result.put("mode", userId == null ? "LOGIN" : "BIND");
        result.put("scope", SCOPE);
        result.put("note", "只读取公开资料与邮箱地址，不读取仓库内容；本平台不保存 GitHub 访问令牌");
        return result;
    }

    /** verifiedEmail：GitHub 已验证的邮箱（优先主邮箱），取不到为 null */
    public record GithubUser(String id, String login, String name, String avatarUrl, String htmlUrl,
                             String verifiedEmail) {
    }

    /** GitHub 登录的结果：created 表示这次是第一次登录、刚建好的账号 */
    public record LoginResult(AppUser user, boolean created) {
    }

    /** 回调处理：校验 state → 用 code 换取 access token → 读取用户资料。 */
    public GithubUser exchange(String code, String state, Long[] stateUserIdHolder) {
        AppProperties.Oauth.Github github = config();
        Long stateUserId = tokenStore.consumeOauthState(state, PROVIDER);
        if (stateUserId == null) {
            throw new ApiException(ErrorKind.VALIDATION, "授权状态已失效，请重新发起绑定");
        }
        stateUserIdHolder[0] = stateUserId == 0L ? null : stateUserId;

        String body = "client_id=" + enc(github.getClientId())
                + "&client_secret=" + enc(github.getClientSecret())
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(github.getRedirectUri());
        JsonNode tokenNode = post(base(github.getWebBaseUrl()) + "/login/oauth/access_token", body);
        String accessToken = tokenNode.path("access_token").asText(null);
        if (accessToken == null) {
            throw new ApiException(ErrorKind.UPSTREAM_ERROR,
                    "GitHub 未返回访问令牌：" + tokenNode.path("error_description").asText("未知错误"));
        }
        String api = base(github.getApiBaseUrl());
        JsonNode user = get(api + "/user", accessToken);
        return new GithubUser(user.path("id").asText(), user.path("login").asText(),
                user.path("name").asText(null), user.path("avatar_url").asText(null),
                user.path("html_url").asText(null), verifiedEmail(api, accessToken));
    }

    /**
     * GitHub 已验证的邮箱：优先主邮箱，否则取任一已验证的；跳过 noreply 转发地址（收不到邮件）。
     * 读取失败只当作没有邮箱，不影响登录——之后到站内验证邮箱即可。
     */
    private String verifiedEmail(String api, String accessToken) {
        try {
            String fallback = null;
            for (JsonNode item : get(api + "/user/emails", accessToken)) {
                String address = item.path("email").asText("").strip().toLowerCase(Locale.ROOT);
                if (!item.path("verified").asBoolean(false) || address.isEmpty()
                        || address.endsWith("@users.noreply.github.com")) {
                    continue;
                }
                if (item.path("primary").asBoolean(false)) return address;
                if (fallback == null) fallback = address;
            }
            return fallback;
        } catch (ApiException e) {
            log.info("读取 GitHub 邮箱失败，按未提供邮箱处理: {}", e.getKind());
            return null;
        }
    }

    /**
     * 用 GitHub 登录：绑定过的直接登录；没绑定过的第一次登录自动开户。
     * 同一个 GitHub 账号并发首次登录时，唯一约束保证只建一个账号，后到的请求直接登录已建好的那个。
     */
    public LoginResult loginOrRegister(GithubUser githubUser, String ip, String userAgent) {
        AppUser bound = boundUser(githubUser);
        if (bound != null) {
            return new LoginResult(bound, false);
        }
        try {
            return new LoginResult(tx.execute(status -> register(githubUser, ip, userAgent)), true);
        } catch (DuplicateKeyException e) {
            AppUser raced = boundUser(githubUser);
            if (raced == null) {
                throw ApiException.conflict("创建账号时与其他请求冲突，请重新用 GitHub 登录");
            }
            return new LoginResult(raced, false);
        }
    }

    private AppUser register(GithubUser githubUser, String ip, String userAgent) {
        String email = githubUser.verifiedEmail();
        if (email != null && userMapper.findByVerifiedEmail(email) != null) {
            throw ApiException.conflict("这个 GitHub 账号的邮箱已经注册过本平台：请先用该邮箱验证码或原账号登录，"
                    + "再到「设置与安全 → 账号安全」绑定 GitHub，之后就能直接用 GitHub 登录");
        }
        AppUser user = new AppUser();
        user.setUsername(uniqueUsername(githubUser.login()));
        user.setDisplayName(displayName(githubUser));
        user.setEmail(email);
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        identityMapper.ensurePrivacy(user.getId());
        if (email != null) {
            identityMapper.insertVerifiedEmail(user.getId(), email);
        }
        OauthBinding binding = new OauthBinding();
        binding.setUserId(user.getId());
        binding.setProvider(PROVIDER);
        binding.setProviderUid(githubUser.id());
        binding.setLogin(githubUser.login());
        binding.setDisplayName(githubUser.name());
        binding.setAvatarUrl(githubUser.avatarUrl());
        binding.setProfileUrl(githubUser.htmlUrl());
        identityMapper.saveBinding(binding);
        authService.audit(user.getId(), user.getUsername(), "REGISTER", ip, userAgent,
                "GITHUB:" + githubUser.login() + (email == null ? "，未取得已验证邮箱" : "，使用 GitHub 已验证邮箱"));
        return user;
    }

    /** 用户名取 gh_ + GitHub 登录名，被占用时加随机后缀；本地用户名规则：字母数字、下划线、点、连字符，3–32 位 */
    private String uniqueUsername(String login) {
        String cleaned = login == null ? "" : login.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "");
        String base = ("gh_" + cleaned);
        if (base.length() > USERNAME_MAX) base = base.substring(0, USERNAME_MAX);
        if (cleaned.length() > 0 && userMapper.findByUsername(base) == null) return base;
        String prefix = base.length() > USERNAME_MAX - 7 ? base.substring(0, USERNAME_MAX - 7) : base;
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] bytes = new byte[3];
            random.nextBytes(bytes);
            String candidate = prefix + "_" + HexFormat.of().formatHex(bytes);
            if (userMapper.findByUsername(candidate) == null) return candidate;
        }
        throw ApiException.conflict("没能生成可用的用户名，请重试");
    }

    /** 昵称用 GitHub 上的名字，没填名字就用登录名；之后可以在设置里改 */
    private static String displayName(GithubUser githubUser) {
        String name = githubUser.name() != null && !githubUser.name().isBlank()
                ? githubUser.name().strip() : githubUser.login();
        return name.codePointCount(0, name.length()) > DISPLAY_NAME_MAX
                ? name.substring(0, name.offsetByCodePoints(0, DISPLAY_NAME_MAX)) : name;
    }

    /** 绑定到指定用户。同一个 GitHub 账号不能绑定到两个本地用户。 */
    public OauthBinding bind(Long userId, GithubUser githubUser) {
        OauthBinding existing = identityMapper.findBinding(PROVIDER, githubUser.id());
        if (existing != null && !existing.getUserId().equals(userId)) {
            throw ApiException.conflict("该 GitHub 账号已绑定到其他用户");
        }
        OauthBinding binding = new OauthBinding();
        binding.setUserId(userId);
        binding.setProvider(PROVIDER);
        binding.setProviderUid(githubUser.id());
        binding.setLogin(githubUser.login());
        binding.setDisplayName(githubUser.name());
        binding.setAvatarUrl(githubUser.avatarUrl());
        binding.setProfileUrl(githubUser.htmlUrl());
        if (identityMapper.saveBinding(binding) == 0) {
            throw ApiException.conflict("当前账号已绑定其他 GitHub，请先在账号安全中解绑，再关联新账号");
        }
        return identityMapper.findBinding(PROVIDER, githubUser.id());
    }

    /** 已绑定的 GitHub 账号对应的本地用户；没绑定过返回 null */
    private AppUser boundUser(GithubUser githubUser) {
        OauthBinding binding = identityMapper.findBinding(PROVIDER, githubUser.id());
        if (binding == null) {
            return null;
        }
        identityMapper.touchBinding(binding.getId());
        AppUser user = userMapper.findById(binding.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new ApiException(ErrorKind.FORBIDDEN, "绑定的账号不可用");
        }
        return user;
    }

    @org.springframework.transaction.annotation.Transactional
    public void unbind(Long userId, String ip, String userAgent) {
        AppUser user = userMapper.lockIdentity(userId);
        if (user != null && !com.szpu.algohub.auth.AuthService.passwordEnabled(user) && userMapper.verifiedEmailCount(userId)==0) {
            throw ApiException.conflict("请先验证登录邮箱，确保解绑后仍能登录");
        }
        if (identityMapper.deleteBinding(userId, PROVIDER) == 0) {
            throw ApiException.notFound("没有可解绑的 GitHub 账号");
        }
        authService.audit(userId, user == null ? null : user.getUsername(),
                "OAUTH_UNLINKED", ip, userAgent, PROVIDER);
    }

    private JsonNode post(String url, String formBody) {
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();
            var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorKind.INTERNAL, "GitHub 请求被中断");
        } catch (Exception e) {
            log.warn("GitHub 令牌交换失败: {}", e.toString());
            throw new ApiException(ErrorKind.NETWORK, "无法连接 GitHub，请检查网络");
        }
    }

    private JsonNode get(String url, String accessToken) {
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("User-Agent", "SZPU-AlgoHub")
                    .GET()
                    .build();
            var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ApiException(ErrorKind.UPSTREAM_ERROR, "GitHub 返回 " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorKind.INTERNAL, "GitHub 请求被中断");
        } catch (Exception e) {
            throw new ApiException(ErrorKind.NETWORK, "读取 GitHub 用户资料失败");
        }
    }

    private static String base(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
