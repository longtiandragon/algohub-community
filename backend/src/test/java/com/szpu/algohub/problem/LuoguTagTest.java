package com.szpu.algohub.problem;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class LuoguTagTest {
    @Test void keepsMultipleAlgorithmTagsAndExcludesOtherCategories() throws Exception {
        var ids=new ObjectMapper().readTree("[1,2,1,999]");
        assertThat(LuoguTagEnrichment.algorithmTags(ids,Map.of(1,"模拟",2,"图论"))).containsExactly("模拟","图论");
    }
    @Test void invalidPayloadIsNotMarkedAsComplete() throws Exception {
        assertThatThrownBy(() -> LuoguTagEnrichment.algorithmTags(new ObjectMapper().readTree("null"),Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
