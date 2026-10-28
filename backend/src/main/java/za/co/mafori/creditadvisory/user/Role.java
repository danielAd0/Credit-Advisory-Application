package za.co.mafori.creditadvisory.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One of the fixed application roles. Rows are seeded by V5 and never created
 * by the app,
 * so there is no public constructor and no generated id.
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, length = 32, updatable = false)
    private RoleName name;

    /** Required by JPA */
    protected Role() {
    }

    public Long getId() {
        return id;
    }

    public RoleName getName() {
        return name;
    }

    /** Two Role objects are equal if they are the same role, e.g. both ADMIN. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Role other && name == other.name;
    }

    @Override
    public int hashCode() {
        return name == null ? 0 : name.hashCode();
    }
}
