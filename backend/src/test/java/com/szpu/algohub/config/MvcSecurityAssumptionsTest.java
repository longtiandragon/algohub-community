package com.szpu.algohub.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.view.xslt.XsltView;
import org.springframework.web.servlet.view.xslt.XsltViewResolver;

import static org.assertj.core.api.Assertions.assertThat;

/** Revisit the two time-limited OSV exceptions before adding MVC views or SSE. */
@SpringBootTest
class MvcSecurityAssumptionsTest {
    @Autowired ApplicationContext context;
    @Autowired RequestMappingHandlerMapping mappings;

    @Test void applicationEndpointsUseResponseBodiesWithoutViewFragmentsOrSse() {
        var applicationHandlers = mappings.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().getPackageName().startsWith("com.szpu.algohub"))
                .toList();
        assertThat(applicationHandlers).isNotEmpty();
        for (var handler : applicationHandlers) {
            assertThat(AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), ResponseBody.class)
                    || handler.hasMethodAnnotation(ResponseBody.class))
                    .as("Explicit response body for %s; review CVE-2026-47884 before adding MVC views", handler)
                    .isTrue();
            assertThat(handler.getMethod().getGenericReturnType().getTypeName())
                    .as("Review CVE-2026-47890 before adding SSE/view fragments: %s", handler)
                    .doesNotContain("SseEmitter", "ResponseBodyEmitter", "FragmentsRendering", "ModelAndView", "ServerSentEvent");
        }
    }

    @Test void noXsltViewOrResolverIsRegistered() {
        assertThat(context.getBeansOfType(XsltView.class)).isEmpty();
        assertThat(context.getBeansOfType(XsltViewResolver.class)).isEmpty();
    }
}
