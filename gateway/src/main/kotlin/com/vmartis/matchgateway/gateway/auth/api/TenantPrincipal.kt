package com.vmartis.matchgateway.gateway.auth.api

import com.vmartis.matchgateway.gateway.auth.infra.JwtConfig
import org.springframework.security.oauth2.jwt.Jwt

/** The caller's tenant – always from the validated JWT, never from the request (INV-4, BR-2). */
fun Jwt.tenantId(): String = checkNotNull(getClaimAsString(JwtConfig.TENANT_CLAIM)) { "validated token without tenant_id" }
