package com.szpu.algohub.platform.adapter;

import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.platform.Platform;
import com.szpu.algohub.platform.PlatformHttpClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NowcoderCatalogTest {
    @Test void readsOfficialProblemIdDifficultyAndTagsAndTreatsMinusOneAsUnknown() {
        var http = mock(PlatformHttpClient.class);
        when(http.get(eq(Platform.NOWCODER), anyString())).thenReturn("""
                {"code":0,"data":{"problemCount":2,"problemSets":[
                  {"id":99,"problemId":14713,"name":"勇敢的妞妞","difficulty":1600,
                   "acceptPerson":10,"tagList":[{"name":"枚举"}]},
                  {"problemId":319567,"name":"新题","difficulty":-1,"tagList":[]}
                ]}}
                """);
        var result = new NowcoderAdapter(http).fetchProblemCatalog();
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().problem().problemKey()).isEqualTo("14713");
        assertThat(result.getFirst().problem().difficultyLabel()).isEqualTo("1600");
        assertThat(result.getFirst().difficultyValue()).isEqualTo(1600);
        assertThat(result.getFirst().problem().tags()).containsExactly("枚举");
        assertThat(result.get(1).difficultyValue()).isNull();
        assertThat(result.get(1).problem().difficultyLabel()).isNull();
    }

    @Test void paginatesUntilTheLastPage() {
        var http = mock(PlatformHttpClient.class);
        String first = java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(i -> "{\"problemId\":" + i + ",\"name\":\"题" + i + "\",\"difficulty\":800}")
                .collect(java.util.stream.Collectors.joining(","));
        when(http.get(eq(Platform.NOWCODER), contains("page=1&")))
                .thenReturn("{\"code\":0,\"data\":{\"problemCount\":51,\"problemSets\":[" + first + "]}}");
        when(http.get(eq(Platform.NOWCODER), contains("page=2&")))
                .thenReturn("{\"code\":0,\"data\":{\"problemCount\":51,\"problemSets\":[{\"problemId\":51,\"name\":\"尾题\"}]}}");
        assertThat(new NowcoderAdapter(http).fetchProblemCatalog()).hasSize(51);
        verify(http, times(2)).get(eq(Platform.NOWCODER), anyString());
    }

    @Test void rejectsFailureEmptyAndIncompleteResponses() {
        for (String body : java.util.List.of("{\"code\":1}", "<html>登录</html>",
                "{\"code\":0,\"data\":{\"problemCount\":1,\"problemSets\":[]}}",
                "{\"code\":0,\"data\":{\"problemCount\":100,\"problemSets\":[{\"problemId\":1,\"name\":\"题\"}]}}")) {
            var http = mock(PlatformHttpClient.class);
            when(http.get(eq(Platform.NOWCODER), anyString())).thenReturn(body);
            assertThatThrownBy(() -> new NowcoderAdapter(http).fetchProblemCatalog()).isInstanceOf(ApiException.class);
        }
    }
}
