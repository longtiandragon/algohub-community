package com.szpu.algohub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import com.szpu.algohub.auth.*;
import com.szpu.algohub.common.ApiException;

@Configuration
public class VerifiedEmailConfig implements WebMvcConfigurer {
 private final AppUserMapper users;
 public VerifiedEmailConfig(AppUserMapper users){this.users=users;}
 @Override public void addInterceptors(InterceptorRegistry registry){
  registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor(){
   @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,Object handler){
    var auth=SecurityContextHolder.getContext().getAuthentication();
    // 管理员通过密码登录即可管理站点；邮箱属于可选的安全设置，敏感操作仍单独验证身份。
    if(auth!=null && auth.getPrincipal() instanceof LoginUser user
       && !"ADMIN".equals(user.getRole()) && users.verifiedEmailCount(user.getId())==0)
      throw ApiException.forbidden("请先绑定并验证登录邮箱");
    return true;
   }
  }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/**","/api/me","/api/me/login-email","/api/me/login-email/**","/api/me/export","/api/me/delete","/api/me/sessions","/api/visits");
 }
}
