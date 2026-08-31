package com.democode.mlmsittu;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application context against the test database.
 *
 * <p>Cheap, and it catches a surprising amount: a missing bean, a Flyway migration that will not
 * apply, and — because {@code ddl-auto=validate} is on — any entity that has drifted from the
 * schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class MlmsittuApplicationTests {

    @Test
    void contextLoads() {}
}
