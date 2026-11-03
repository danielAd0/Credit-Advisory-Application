package za.co.mafori.creditadvisory.common.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when NO profile is active. Spring then uses its "default"
 * profile, which matches neither development nor production - a server started
 * like that would have no secure cookies and no checks.
 *
 * <p>
 * .\mvnw.cmd spring-boot:run uses "local" (set in pom.xml), the tests use
 * "test", and production must set SPRING_PROFILES_ACTIVE=prod.
 */
@Component
@Profile("default")
public class NoProfileGuard implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        throw new IllegalStateException("No Spring profile is active. Use .\\mvnw.cmd spring-boot:run for"
                + " development (profile 'local'), or set SPRING_PROFILES_ACTIVE=prod on a server.");
    }
}
