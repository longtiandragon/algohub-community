package com.szpu.algohub.auth;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.szpu.algohub.config.AppProperties;
class DomainMigrationTest {
 final AuthService auth=mock(AuthService.class);
 final DomainMigrationController controller=new DomainMigrationController(auth,new RefreshCookies(new AppProperties()),"https://algohub.top");
 MockHttpServletRequest req(String host,String name,String value){var r=new MockHttpServletRequest();r.setServerName(host);if(name!=null)r.setCookies(new Cookie(name,value));return r;}
 @Test void bridgeNeverExposesCredentialInUrl(){var r=controller.start(req("www.algohub.top",AuthController.REFRESH_COOKIE,"a".repeat(64)),"/accounts");assertFalse(r.getHeaders().getLocation().toString().contains("a".repeat(64)));String c=r.getHeaders().getFirst("Set-Cookie");assertTrue(c.contains("HttpOnly"));assertTrue(c.contains("Secure"));assertTrue(c.contains("Max-Age=60"));assertTrue(c.contains("Path="+DomainMigrationController.FINISH));verifyNoInteractions(auth);}
 @Test void finishRotatesAndSetsHostOnlyCookie(){when(auth.refresh(anyString(),anyString(),nullable(String.class))).thenReturn(new AuthService.SessionResult(null,"new-token"));when(auth.refreshTokenTtl()).thenReturn(Duration.ofDays(14));var r=controller.finish(req("algohub.top",DomainMigrationController.BRIDGE,"old"),"/accounts");assertEquals("https://algohub.top/accounts",r.getHeaders().getLocation().toString());String last=r.getHeaders().get("Set-Cookie").get(1);assertTrue(last.contains("algohub_refresh=new-token"));assertFalse(last.contains("Domain="));assertTrue(r.getHeaders().getFirst("Set-Cookie").contains("Max-Age=0"));}
 @Test void existingTargetSessionWins(){var r=req("algohub.top",AuthController.REFRESH_COOKIE,"existing");r.setCookies(new Cookie(AuthController.REFRESH_COOKIE,"existing"),new Cookie(DomainMigrationController.BRIDGE,"old"));controller.finish(r,"/");verifyNoInteractions(auth);}
 @Test void unauthorizedOriginsAndRedirectsRejected(){assertThrows(RuntimeException.class,()->controller.start(req("admin.algohub.top",null,null),"/"));assertEquals("/",DomainMigrationController.destination("//evil.example"));assertEquals("/",DomainMigrationController.destination("/\\evil"));assertEquals("/",DomainMigrationController.destination("/api/auth/logout"));assertEquals("/accounts?x=1",DomainMigrationController.destination("/accounts?x=1"));}
 @Test void missingAndExpiredCookiesDoNotLoop(){assertEquals("https://algohub.top/login",controller.start(req("www.algohub.top",null,null),"/login").getHeaders().getLocation().toString());when(auth.refresh(anyString(),anyString(),nullable(String.class))).thenThrow(new com.szpu.algohub.common.ApiException(com.szpu.algohub.common.ErrorKind.UNAUTHENTICATED,"expired"));assertEquals(302,controller.finish(req("algohub.top",DomainMigrationController.BRIDGE,"old"),"/login").getStatusCode().value());}
}
