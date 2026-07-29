package com.backend.security;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.backend.config.TenantAuthProperties;
import com.backend.entity.LoginSessionEntity;
import com.backend.entity.TenantEntity;
import com.backend.entity.TenantUserEntity;
import com.backend.repository.LoginSessionRepository;
import com.backend.repository.TenantUserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class TenantSessionAuthenticationFilter extends OncePerRequestFilter {

    private final LoginSessionRepository loginSessionRepository;
    private final TenantUserRepository tenantUserRepository;
    private final SecureTokenService secureTokenService;
    private final TenantAuthProperties properties;

    public TenantSessionAuthenticationFilter(
            LoginSessionRepository loginSessionRepository,
            TenantUserRepository tenantUserRepository,
            SecureTokenService secureTokenService,
            TenantAuthProperties properties) {
        this.loginSessionRepository = loginSessionRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.secureTokenService = secureTokenService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String rawToken = readCookie(request, properties.cookieName());
            if (rawToken != null) {
                loginSessionRepository
                        .findActiveTenantSession(secureTokenService.hash(rawToken), Instant.now())
                        .ifPresent(this::authenticate);
            }
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        filterChain.doFilter(request, response);
    }

    private void authenticate(LoginSessionEntity session) {
        TenantUserEntity user = session.getTenantUser();
        TenantEntity tenant = user.getTenant();
        List<String> roles = tenantUserRepository.findRoleCodes(user.getId(), tenant.getId());
        List<String> permissions =
                tenantUserRepository.findPermissionCodes(user.getId(), tenant.getId());
        TenantPrincipal principal = new TenantPrincipal(
                session.getId(),
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                tenant.getId(),
                tenant.getTenantCode(),
                tenant.getTenantName(),
                List.copyOf(roles),
                List.copyOf(permissions),
                user.getCredential().isMustChangePassword(),
                session.getExpiresAt());

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if ("AUTHENTICATED".equals(session.getAuthStage())
                && !user.getCredential().isMustChangePassword()) {
            authorities.add(new SimpleGrantedAuthority("SESSION_AUTHENTICATED"));
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
        } else {
            authorities.add(new SimpleGrantedAuthority("PASSWORD_CHANGE_REQUIRED"));
        }
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    public static String readCookie(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
