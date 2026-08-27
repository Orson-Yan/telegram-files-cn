package telegram.files.security.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Argon2idPasswordHasherTest {

    @Test
    void hashesWithArgon2idAndVerifiesWithoutLeaking() {
        Argon2idPasswordHasher hasher = new Argon2idPasswordHasher();
        char[] password = "correct horse battery staple".toCharArray();

        Argon2idPasswordHasher.PasswordHash hash = hasher.hash(password);

        assertTrue(hasher.verify(password, hash));
        assertFalse(hasher.verify("incorrect password".toCharArray(), hash));
        assertTrue(hash.parameters().contains("\"algorithm\":\"argon2id\""));
        assertFalse(hash.toString().contains(hash.hash()));
    }

    @Test
    void acceptsEightCharactersAndRejectsShorterPasswords() {
        Argon2idPasswordHasher hasher = new Argon2idPasswordHasher();
        char[] minimumLengthPassword = "12345678".toCharArray();

        Argon2idPasswordHasher.PasswordHash hash = hasher.hash(minimumLengthPassword);

        assertTrue(hasher.verify(minimumLengthPassword, hash));
        assertThrows(
                IllegalArgumentException.class,
                () -> hasher.hash("1234567".toCharArray())
        );
        assertFalse(hasher.verify(
                "correct horse battery staple".toCharArray(),
                new Argon2idPasswordHasher.PasswordHash("invalid", "{}")
        ));
    }
}
