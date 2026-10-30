package com.szpu.algohub.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地部署相关配置。频率与限速均为可调配置，不是实时性承诺。
 */
@ConfigurationProperties(prefix = "algohub")
public class AppProperties {

    private final Bootstrap bootstrap = new Bootstrap();
    private final Security security = new Security();
    private final Oauth oauth = new Oauth();
    private final Credential credential = new Credential();
    private final Sync sync = new Sync();
    private final Http http = new Http();

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Security getSecurity() {
        return security;
    }

    public Oauth getOauth() {
        return oauth;
    }

    public Credential getCredential() {
        return credential;
    }

    public Sync getSync() {
        return sync;
    }

    public Http getHttp() {
        return http;
    }

    public static class Bootstrap {
        /** 首次启动时创建的管理员账号；生产使用前必须修改密码。 */
        private String adminUsername = "admin";
        private String adminPassword = "admin12345";
        private String adminDisplayName = "本地管理员";

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        public String getAdminDisplayName() {
            return adminDisplayName;
        }

        public void setAdminDisplayName(String adminDisplayName) {
            this.adminDisplayName = adminDisplayName;
        }
    }

    public static class Security {
        private boolean production = false;
        private boolean secureCookie = false;
        private boolean requireExistingKeys = false;
        public boolean isProduction() { return production; }
        public void setProduction(boolean value) { production = value; }
        public boolean isSecureCookie() { return secureCookie; }
        public void setSecureCookie(boolean value) { secureCookie = value; }
        public boolean isRequireExistingKeys() { return requireExistingKeys; }
        public void setRequireExistingKeys(boolean value) { requireExistingKeys = value; }
        /** JWT 签名密钥（Base64 或至少 32 字节的明文）。留空则启动时生成并写入 keyFile。 */
        private String jwtSecret = "";
        private String jwtSecretFile = "./local-secret/jwt-key.txt";
        /** 访问令牌有效期（分钟），短时效，泄露影响有限。 */
        private long accessTokenMinutes = 30;
        /** 刷新令牌有效期（天），保存在 Redis，可单个或全部撤销。 */
        private long refreshTokenDays = 14;
        /** 单个账号允许同时存在的刷新令牌数量（多端登录上限）。 */
        private int maxSessionsPerUser = 10;
        /** 连续登录失败多少次后临时锁定。 */
        private int maxLoginFailures = 8;
        /** 登录失败锁定时长（分钟）。 */
        private long loginLockMinutes = 15;

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public String getJwtSecretFile() {
            return jwtSecretFile;
        }

        public void setJwtSecretFile(String jwtSecretFile) {
            this.jwtSecretFile = jwtSecretFile;
        }

        public long getAccessTokenMinutes() {
            return accessTokenMinutes;
        }

        public void setAccessTokenMinutes(long accessTokenMinutes) {
            this.accessTokenMinutes = accessTokenMinutes;
        }

        public long getRefreshTokenDays() {
            return refreshTokenDays;
        }

        public void setRefreshTokenDays(long refreshTokenDays) {
            this.refreshTokenDays = refreshTokenDays;
        }

        public int getMaxSessionsPerUser() {
            return maxSessionsPerUser;
        }

        public void setMaxSessionsPerUser(int maxSessionsPerUser) {
            this.maxSessionsPerUser = maxSessionsPerUser;
        }

        public int getMaxLoginFailures() {
            return maxLoginFailures;
        }

        public void setMaxLoginFailures(int maxLoginFailures) {
            this.maxLoginFailures = maxLoginFailures;
        }

        public long getLoginLockMinutes() {
            return loginLockMinutes;
        }

        public void setLoginLockMinutes(long loginLockMinutes) {
            this.loginLockMinutes = loginLockMinutes;
        }
    }

    public static class Oauth {
        private final Github github = new Github();

        public Github getGithub() {
            return github;
        }

        public static class Github {
            /** 在 GitHub 上创建 OAuth App 后填写；留空表示未启用绑定功能。 */
            private String clientId = "";
            private String clientSecret = "";
            /** 必须与 GitHub OAuth App 中登记的回调地址完全一致。 */
            private String redirectUri = "http://127.0.0.1:5174/oauth/github/callback";
            /** GitHub 网页与 API 地址：一般不用改；GitHub Enterprise 或本机模拟联调时改 */
            private String webBaseUrl = "https://github.com";
            private String apiBaseUrl = "https://api.github.com";

            public boolean isEnabled() {
                return clientId != null && !clientId.isBlank()
                        && clientSecret != null && !clientSecret.isBlank();
            }

            public String getClientId() {
                return clientId;
            }

            public void setClientId(String clientId) {
                this.clientId = clientId;
            }

            public String getClientSecret() {
                return clientSecret;
            }

            public void setClientSecret(String clientSecret) {
                this.clientSecret = clientSecret;
            }

            public String getRedirectUri() {
                return redirectUri;
            }

            public void setRedirectUri(String redirectUri) {
                this.redirectUri = redirectUri;
            }

            public String getWebBaseUrl() {
                return webBaseUrl;
            }

            public void setWebBaseUrl(String webBaseUrl) {
                this.webBaseUrl = webBaseUrl;
            }

            public String getApiBaseUrl() {
                return apiBaseUrl;
            }

            public void setApiBaseUrl(String apiBaseUrl) {
                this.apiBaseUrl = apiBaseUrl;
            }
        }
    }

    public static class Credential {
        /** Base64 编码的 AES-256 主密钥。留空则启动时生成并写入 keyFile，与数据库分离保存。 */
        private String masterKey = "";
        private String keyFile = "./local-secret/credential-key.txt";

        public String getMasterKey() {
            return masterKey;
        }

        public void setMasterKey(String masterKey) {
            this.masterKey = masterKey;
        }

        public String getKeyFile() {
            return keyFile;
        }

        public void setKeyFile(String keyFile) {
            this.keyFile = keyFile;
        }
    }

    public static class Sync {
        private boolean enabled = true;
        private int workerThreads = 2;
        /** 任务轮询间隔（毫秒）。 */
        private long pollIntervalMs = 5000;
        /**
         * 任务租约时长（秒）。执行中每隔约 1/4 租约续租一次；进程退出或卡死不再续租，
         * 租约过期后其他 worker 可以接管，旧执行的结果不会再被接受。
         */
        private long leaseSeconds = 120;
        /** 同一平台同时执行的任务数上限。源站限速按平台共享，同平台并行只会互相拖慢并占满线程。 */
        private int perPlatformConcurrency = 1;
        /** 活跃账号自动同步间隔（分钟）。 */
        private long activeAccountIntervalMinutes = 45;
        /** 不活跃账号自动同步间隔（分钟）。 */
        private long idleAccountIntervalMinutes = 720;
        /** 账号在多少天内有提交视为活跃。 */
        private int activeWindowDays = 14;
        /** 比赛列表刷新间隔（分钟）。 */
        private long contestIntervalMinutes = 45;
        /** 独立刷新题目评级的间隔；0 关闭自动题库刷新，手动刷新仍可用。 */
        private long catalogIntervalMinutes = 1440;
        /** 增量同步时向前回查的天数，用于处理重判与迟到数据。 */
        private int recheckDays = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getLeaseSeconds() {
            return leaseSeconds;
        }

        public void setLeaseSeconds(long leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }

        public int getPerPlatformConcurrency() {
            return perPlatformConcurrency;
        }

        public void setPerPlatformConcurrency(int perPlatformConcurrency) {
            this.perPlatformConcurrency = perPlatformConcurrency;
        }

        public long getActiveAccountIntervalMinutes() {
            return activeAccountIntervalMinutes;
        }

        public void setActiveAccountIntervalMinutes(long activeAccountIntervalMinutes) {
            this.activeAccountIntervalMinutes = activeAccountIntervalMinutes;
        }

        public long getIdleAccountIntervalMinutes() {
            return idleAccountIntervalMinutes;
        }

        public void setIdleAccountIntervalMinutes(long idleAccountIntervalMinutes) {
            this.idleAccountIntervalMinutes = idleAccountIntervalMinutes;
        }

        public int getActiveWindowDays() {
            return activeWindowDays;
        }

        public void setActiveWindowDays(int activeWindowDays) {
            this.activeWindowDays = activeWindowDays;
        }

        public long getContestIntervalMinutes() {
            return contestIntervalMinutes;
        }

        public void setContestIntervalMinutes(long contestIntervalMinutes) {
            this.contestIntervalMinutes = contestIntervalMinutes;
        }

        public int getRecheckDays() {
            return recheckDays;
        }

        public long getCatalogIntervalMinutes() { return catalogIntervalMinutes; }
        public void setCatalogIntervalMinutes(long value) { catalogIntervalMinutes = value; }

        public void setRecheckDays(int recheckDays) {
            this.recheckDays = recheckDays;
        }
    }

    public static class Http {
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 30;
        private String userAgent = "SZPU-AlgoHub/0.1 (local study tracker; contact: campus algorithm club)";
        /** 各平台最小请求间隔（毫秒），所有账号与任务共享。 */
        private Map<String, Long> minIntervalMs = new LinkedHashMap<>();
        /** 单个响应（传输与解压后都算）的字节上限，超过就停止读取。目前最大的正常响应约 3 MB。 */
        private long maxBodyBytes = 16L * 1024 * 1024;

        public long getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(long maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public Map<String, Long> getMinIntervalMs() {
            return minIntervalMs;
        }

        public void setMinIntervalMs(Map<String, Long> minIntervalMs) {
            this.minIntervalMs = minIntervalMs;
        }
    }
}
