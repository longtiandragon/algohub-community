package com.szpu.algohub;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TestDatabaseIsolationTest {
    @Test void refusesNonTestAndRemoteTargetsBeforeConnecting() {
        for (String url : java.util.List.of("jdbc:postgresql://127.0.0.1:5433/algohub",
                "jdbc:postgresql://db.example.com/algohub_test",
                "jdbc:postgresql://127.0.0.1/algohub_test?host=db.example.com")) {
            assertThatThrownBy(() -> TestDatabaseIsolation.checkDatabase(url)).isInstanceOf(IllegalStateException.class);
        }
        assertThatCode(() -> TestDatabaseIsolation.checkDatabase("jdbc:postgresql://127.0.0.1:15433/algohub_test"))
                .doesNotThrowAnyException();
    }
}
