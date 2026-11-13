-- V24: emailed temporary passwords expire (docs/consultant-onboarding.md, section 3).
--
-- The first sign-in also sets up two-step sign-in, so whoever reads a login email
-- first owns the account. A temporary password that was EMAILED therefore stops
-- working app.onboarding.temporary-password-days (default 7) after Microsoft 365
-- accepted the email. The admin then regenerates it on the Consultant logins page.
--
-- Set by LoginDetailsSender when the email is sent. Cleared by every password
-- change (the user's own, or an admin reset: UserAccount.changePasswordHash), so
-- passwords an admin hands over in person never expire.

ALTER TABLE users
    ADD COLUMN temporary_password_expires_at TIMESTAMPTZ;

-- Login emails already sent before this change: 7 days from when they were sent.
UPDATE users u
SET temporary_password_expires_at = i.sent_at + INTERVAL '7 days'
FROM consultant_login_invites i
WHERE i.user_id = u.id
  AND i.status = 'SENT'
  AND u.must_change_password;
