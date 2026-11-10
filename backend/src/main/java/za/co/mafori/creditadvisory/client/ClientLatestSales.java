package za.co.mafori.creditadvisory.client;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Finds each client's most recent sale in one query, for a whole page of the
 * Clients list at once (no query per row). Uses the index
 * ix_transactions_client_date.
 */
@Repository
public class ClientLatestSales {

    private final JdbcTemplate jdbc;

    public ClientLatestSales(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Client id to its latest sale. Clients with no sales are not in the map. */
    public Map<Long, LatestSale> forClients(Collection<Long> clientIds) {
        Map<Long, LatestSale> latest = new HashMap<>();
        if (clientIds.isEmpty()) {
            return latest;
        }
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    select distinct on (t.client_id) t.client_id, t.transaction_date, pt.name, s.name
                    from client_transactions t
                    left join payment_terms pt on pt.id = t.payment_term_id
                    left join structures s on s.id = t.structure_id
                    where t.client_id = any(?)
                    order by t.client_id, t.transaction_date desc, t.id desc
                    """);
            Array ids = connection.createArrayOf("bigint", clientIds.toArray());
            statement.setArray(1, ids);
            return statement;
        }, row -> {
            latest.put(row.getLong(1), new LatestSale(row.getObject(2, LocalDate.class), row.getString(3),
                    row.getString(4)));
        });
        return latest;
    }

    /** The latest sale of one client, or null if they have none. */
    public LatestSale forClient(long clientId) {
        return forClients(List.of(clientId)).get(clientId);
    }
}
