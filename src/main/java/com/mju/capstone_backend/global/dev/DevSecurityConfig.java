package com.mju.capstone_backend.global.dev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * 개발 환경 전용 보안 설정.
 *
 * 1. /dev/**, /actuator/** 경로 인증 생략 (SecurityWebFilterChain @Order(1))
 *    - /actuator/**(Prometheus 스크레이프)는 dev에서만 공개. prod에선 이 체인이 없어
 *      메인 체인의 anyExchange().authenticated() 에 걸려 인증 대상이 된다(공개 노출 방지).
 * 2. "dev-{clerkId}" 형태의 토큰을 Swagger Authorize에 입력하면
 *    Clerk API 호출 없이 즉시 인증 처리 (ReactiveJwtDecoder 교체)
 *
 * 사용 예) Swagger Authorize → Value: dev-user_3Berb37qpNgd8EosSGzFfSzf7jn
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwksUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Bean
    @Order(1)
    public SecurityWebFilterChain devSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/dev/**", "/actuator/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }

    /**
     * dev-{clerkId} 토큰을 직접 인증으로 처리하는 커스텀 JWT 디코더.
     * 실제 Clerk JWT가 오면 기존 Nimbus 디코더로 위임한다.
     * Spring Boot 자동 구성의 @ConditionalOnMissingBean 에 의해 이 빈이 우선 사용된다.
     */
    @Bean
    public ReactiveJwtDecoder devJwtDecoder() {
        NimbusReactiveJwtDecoder realDecoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(jwksUri)
                .build();

        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuerUri);
        realDecoder.setJwtValidator(validator);

        return token -> {
            if (token.startsWith("dev-")) {
                String clerkId = token.substring(4);
                Instant now = Instant.now();
                Jwt jwt = Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .header("typ", "JWT")
                        .subject(clerkId)
                        .issuer(issuerUri)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(86400))
                        .build();
                return Mono.just(jwt);
            }
            return realDecoder.decode(token);
        };
    }
}
