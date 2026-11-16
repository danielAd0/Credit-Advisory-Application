package za.co.mafori.creditadvisory.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * A new install has no products or employers until the first import (V27): the
 * demo data adds the few it needs, so it still works on a fresh database.
 */
class DemoDataEmptyInstallTest extends ApiTestBase {

    @Autowired
    private DemoData demoData;

    @Override
    protected boolean standardCatalogue() {
        return false;
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void addsItsOwnProductsAndEmployers() {
        assertThat(count("select count(*) from products")).as("a new install").isZero();

        DemoData.Summary made = demoData.seed(PASSWORD).orElseThrow();

        assertThat(count("select count(*) from products")).isEqualTo(7);
        assertThat(count("select count(*) from employers where name like 'DEMO EMPLOYER %'")).isEqualTo(3);
        assertThat(count("select count(*) from client_transactions")).isEqualTo(made.sales());
        assertThat(count("select count(*) from client_transactions t"
                + " where not exists (select 1 from client_transaction_lines l where l.transaction_id = t.id)"))
                .as("every sale has at least one product").isZero();
        assertThat(count("select count(*) from clients where employer_id is not null")).isPositive();
    }
}
