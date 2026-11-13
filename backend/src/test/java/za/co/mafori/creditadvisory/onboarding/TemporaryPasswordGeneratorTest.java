package za.co.mafori.creditadvisory.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.user.PasswordPolicy;

class TemporaryPasswordGeneratorTest {

    private final TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();

    @Test
    void passwordsPassThePolicyAndMixCharacterKinds() {
        for (int i = 0; i < 500; i++) {
            String p = generator.next();
            assertThat(p).hasSize(TemporaryPasswordGenerator.LENGTH);
            assertThat(p.length()).isGreaterThanOrEqualTo(PasswordPolicy.MIN_LENGTH);
            assertThat(p).containsPattern("[A-Z]").containsPattern("[a-z]").containsPattern("[2-9]")
                    .containsPattern("[!#$%&*+\\-=?@^_~]");
            assertThat(p).as("no look-alike characters, no JSON or HTML specials")
                    .doesNotContainPattern("[0O1lI\"\\\\<> ]");
            PasswordPolicy.check(p, "someone@test.local");
        }
    }

    @Test
    void passwordsDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(generator.next())).isTrue();
        }
    }
}
