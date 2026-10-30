package com.nodotextil.trazatex.apigateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** JWT como los emite organization-access (RS256, sub/role/companyId/iat/exp, kid), y variantes inválidas para probar el rechazo. */
public final class TestTokens {

    public static final String KID = "test-kid-1";
    public static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID COMPANY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    public static final RSAKey KEY = generate(KID);

    private TestTokens() {
    }

    public static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JWKS como el de organization-access: solo las partes públicas. */
    public static String jwks(RSAKey... keys) {
        return new JWKSet(java.util.Arrays.stream(keys).map(RSAKey::toPublicJWK).map(k -> (com.nimbusds.jose.jwk.JWK) k).toList()).toString();
    }

    public static JWTClaimsSet.Builder claims(UUID userId, String role, UUID companyId) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().subject(userId.toString()).claim("role", role)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(3600)));
        if (companyId != null) {
            b.claim("companyId", companyId.toString());
        }
        return b;
    }

    public static JWTClaimsSet.Builder operatorClaims() {
        return claims(USER_ID, "OPERATOR", COMPANY_ID);
    }

    /** Un token válido de un operario. */
    public static String operator() {
        return sign(KEY, operatorClaims().build());
    }

    public static String forUser(UUID userId, String role, UUID companyId) {
        return sign(KEY, claims(userId, role, companyId).build());
    }

    public static String sign(RSAKey key, JWTClaimsSet claims) {
        return sign(key, key.getKeyID(), claims);
    }

    public static String sign(RSAKey key, String kid, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ataque de confusión de algoritmo: HS256 firmado con los bytes de la clave pública como secreto. */
    public static String hs256WithPublicKeyAsSecret(JWTClaimsSet claims) {
        try {
            JWSSigner signer = new MACSigner(KEY.toRSAPublicKey().getEncoded());
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KID).build(), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code alg: none}, sin firma. */
    public static String unsigned(JWTClaimsSet claims) {
        return new PlainJWT(claims).serialize();
    }
}
