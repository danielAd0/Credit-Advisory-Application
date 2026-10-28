package za.co.mafori.creditadvisory.user;

public enum RoleName {
    CONSULTANT,
    MANAGER,
    ADMIN;

    /** Spring security expects role authorities to start with "ROLE_" */
    public String getAuthority() {
        return "ROLE_" + name();
    }
}
