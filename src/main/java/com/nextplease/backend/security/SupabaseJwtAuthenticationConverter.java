package com.nextplease.backend.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class SupabaseJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_authenticated"));
        authorities.addAll(extractRoleAuthorities(jwt));

        CurrentUserPrincipal principal = new CurrentUserPrincipal(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString("email"),
                extractAppRoles(jwt)
        );

        return new JwtAuthenticationToken(jwt, authorities, principal.email());
    }

    private Collection<GrantedAuthority> extractRoleAuthorities(Jwt jwt) {
        return extractAppRoles(jwt).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    private Set<String> extractAppRoles(Jwt jwt) {
        Map<String, Object> appMetadata = jwt.getClaim("app_metadata");
        if (appMetadata == null) {
            return Set.of();
        }

        Object roles = appMetadata.get("roles");
        if (roles instanceof Collection<?> roleCollection) {
            Set<String> result = new HashSet<>();
            for (Object role : roleCollection) {
                if (role instanceof String roleText && !roleText.isBlank()) {
                    result.add(roleText);
                }
            }
            return result;
        }

        return Set.of();
    }
}
