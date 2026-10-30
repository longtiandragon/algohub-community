package com.szpu.algohub.platform.adapter;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeforcesCatalogTest {
    @Test void nullRatingIsUnknownAndPointsAreNotUsedAsDifficulty() {
        var http = mock(PlatformHttpClient.class);
        when(http.get(eq(Platform.CODEFORCES), anyString())).thenReturn("""
                {"status":"OK","result":{"problems":[
                  {"contestId":123,"index":"A","name":"A","rating":null,"points":1000},
                  {"contestId":123,"index":"B","name":"B","rating":1600}
                ],"problemStatistics":[]}}
                """);
        var result = new CodeforcesAdapter(http).fetchProblemCatalog();
        assertThat(result.getFirst().difficultyValue()).isNull();
        assertThat(result.get(1).difficultyValue()).isEqualTo(1600);
    }

    @Test void malformedCatalogIsAnErrorRatherThanSuccessfulNoOp() {
        var http = mock(PlatformHttpClient.class);
        when(http.get(eq(Platform.CODEFORCES), anyString())).thenReturn("{\"status\":\"OK\",\"result\":{}}");
        assertThatThrownBy(() -> new CodeforcesAdapter(http).fetchProblemCatalog()).isInstanceOf(ApiException.class);
    }
}
