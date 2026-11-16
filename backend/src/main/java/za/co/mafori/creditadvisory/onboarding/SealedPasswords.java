package za.co.mafori.creditadvisory.onboarding;

/**
 * The purpose the temporary passwords of queued login emails are sealed for,
 * for
 * code outside this package that has to open and re-seal them: the ID-number
 * key rotation (keys package). Kept here so there is one source:
 * OnboardingService.SEAL_PURPOSE.
 */
public final class SealedPasswords {

    public static final String PURPOSE = OnboardingService.SEAL_PURPOSE;

    private SealedPasswords() {
    }
}
