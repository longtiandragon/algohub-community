package com.szpu.algohub.auth;

import com.szpu.algohub.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.assertThat;

class JwtClockSkewTest {
    @TempDir Path temp;

    @Test void freshlyIssuedTokenDoesNotPredateItsDatabaseIdentityVersion() {
        var properties = new AppProperties();
        properties.getSecurity().setJwtSecretFile(temp.resolve("jwt-key.txt").toString());
        var jwt = new JwtService(properties);
        var user = new AppUser();
        user.setId(1L);
        user.setUsername("clock-test");
        user.setRole("STUDENT");
        var databaseVersion = Instant.now().plusSeconds(2);
        user.setTokensValidFrom(databaseVersion);
        var issued = jwt.issueAccessToken(user);
        var claims = jwt.parse(issued.token());
        assertThat(claims.getIssuedAt().toInstant())
                .isAfterOrEqualTo(databaseVersion.truncatedTo(ChronoUnit.SECONDS));
        assertThat(claims.get("identityVersion", String.class)).isEqualTo(databaseVersion.toString());
    }
}
