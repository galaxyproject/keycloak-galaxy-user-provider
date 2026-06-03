package org.galaxyproject.keycloak;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Java password check matches Galaxy's own implementation.
 *
 * The vectors below were produced by galaxyproject/galaxy's
 * {@code galaxy.security.passwords.hash_password()} (and confirmed with its
 * {@code check_password()}), so they are an independent oracle rather than a
 * round-trip of this class against itself.
 */
class GalaxyPasswordUtilTest {

    // galaxy.security.passwords.hash_password("s3cr3t-pa55word!")
    private static final String PBKDF2_PASSWORD = "s3cr3t-pa55word!";
    private static final String PBKDF2_HASH =
            "PBKDF2$sha256$100000$0fJqtBbIOEV/gsgb$jvl7ztTjRPnA6woFYDckhr91Jp4jHhv+";

    // Legacy unsalted SHA-1 hex (very old Galaxy accounts): sha1("hunter2")
    private static final String SHA1_PASSWORD = "hunter2";
    private static final String SHA1_HASH = "f3bbbd66a63d4bf1747940578ec3d0103530e21d";

    @Test
    void pbkdf2_correctPassword() {
        assertTrue(GalaxyPasswordUtil.verify(PBKDF2_PASSWORD, PBKDF2_HASH));
    }

    @Test
    void pbkdf2_wrongPassword() {
        assertFalse(GalaxyPasswordUtil.verify("not-the-password", PBKDF2_HASH));
    }

    @Test
    void pbkdf2_caseSensitive() {
        assertFalse(GalaxyPasswordUtil.verify(PBKDF2_PASSWORD.toUpperCase(), PBKDF2_HASH));
    }

    @Test
    void legacySha1_correctPassword() {
        assertTrue(GalaxyPasswordUtil.verify(SHA1_PASSWORD, SHA1_HASH));
    }

    @Test
    void legacySha1_wrongPassword() {
        assertFalse(GalaxyPasswordUtil.verify("wrong", SHA1_HASH));
    }

    @Test
    void rejectsNullEmptyAndMalformed() {
        assertFalse(GalaxyPasswordUtil.verify("x", null));
        assertFalse(GalaxyPasswordUtil.verify("x", ""));
        assertFalse(GalaxyPasswordUtil.verify("x", "PBKDF2$sha256$100000$only-three-parts"));
        assertFalse(GalaxyPasswordUtil.verify("x", "not-a-hash"));
    }
}
