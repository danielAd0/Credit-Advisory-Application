package za.co.mafori.creditadvisory.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local development only: fills your database with made-up demo data (see
 * DemoData) on start-up when application-local.properties has
 * app.dev.demo-data=true. Never runs outside the "local" profile. Does nothing
 * if the demo data is already there; to start again, wipe your local database.
 *
 * Every demo login uses app.dev.demo-password, or the local admin's password
 * when that is not set.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.dev.demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DemoData demoData;
    private final String password;

    public DemoDataSeeder(DemoData demoData,
            @Value("${app.dev.demo-password:${app.dev.admin-password:}}") String password) {
        this.demoData = demoData;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password.isBlank()) {
            log.info("No demo password (app.dev.demo-password or app.dev.admin-password) - skipping demo data");
            return;
        }
        demoData.seed(password).ifPresentOrElse(
                made -> log.info("Demo data created: {} consultants, {} clients, {} sales, {} credit cases."
                        + " Sign in as {} or a consultant (lerato.demo@example.com and so on)",
                        made.consultants(), made.clients(), made.sales(), made.creditCases(),
                        DemoData.MANAGER_EMAIL),
                () -> log.info("Demo data already there - skipping"));
    }
}
