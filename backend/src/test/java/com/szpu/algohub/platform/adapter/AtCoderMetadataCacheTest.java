package com.szpu.algohub.platform.adapter;
import org.junit.jupiter.api.Test;
import com.szpu.algohub.platform.*;
import java.util.concurrent.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AtCoderMetadataCacheTest {
 @Test void absentOrNullDifficultyDoesNotBecomeZero() {
  var http=mock(PlatformHttpClient.class);
  when(http.get(eq(Platform.ATCODER),endsWith("/problems.json"))).thenReturn("[{\"id\":\"abc001_a\",\"title\":\"A\",\"contest_id\":\"abc001\"}]");
  when(http.get(eq(Platform.ATCODER),endsWith("/problem-models.json"))).thenReturn("{\"abc001_a\":{\"difficulty\":null}}");
  assertThat(new AtCoderAdapter(http).fetchProblemCatalog().getFirst().difficultyValue()).isNull();
 }

 @Test void failedModelResponseIsNotCachedAsAnEmptySuccess() {
  var http=mock(PlatformHttpClient.class);
  when(http.get(eq(Platform.ATCODER),endsWith("/problems.json"))).thenReturn("[{\"id\":\"abc001_a\",\"title\":\"A\",\"contest_id\":\"abc001\"}]");
  when(http.get(eq(Platform.ATCODER),endsWith("/problem-models.json")))
    .thenReturn("[]", "{\"abc001_a\":{\"difficulty\":1600}}");
  var adapter=new AtCoderAdapter(http);
  assertThatThrownBy(adapter::fetchProblemCatalog).isInstanceOf(com.szpu.algohub.common.ApiException.class);
  assertThat(adapter.fetchProblemCatalog().getFirst().difficultyValue()).isEqualTo(1600);
 }

 @Test void concurrentCatalogReadersShareOneMetadataDownload() throws Exception {
  var http=mock(PlatformHttpClient.class);
  when(http.get(eq(Platform.ATCODER),endsWith("/problems.json"))).thenReturn("[{\"id\":\"abc001_a\",\"title\":\"A\",\"contest_id\":\"abc001\"}]");
  when(http.get(eq(Platform.ATCODER),endsWith("/problem-models.json"))).thenReturn("{\"abc001_a\":{\"difficulty\":100}}");
  var adapter=new AtCoderAdapter(http);
  var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(8)) {
   var results=new ArrayList<Future<Integer>>();
   for(int i=0;i<8;i++)results.add(pool.submit(()->{start.await();return adapter.fetchProblemCatalog().size();}));
   start.countDown();
   for(var result:results)assertThat(result.get(10,TimeUnit.SECONDS)).isEqualTo(1);
  }
  verify(http,times(1)).get(Platform.ATCODER,"https://kenkoooo.com/atcoder/resources/problems.json");
  verify(http,times(1)).get(Platform.ATCODER,"https://kenkoooo.com/atcoder/resources/problem-models.json");
 }
}
