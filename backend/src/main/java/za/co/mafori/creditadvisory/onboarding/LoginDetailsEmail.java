package za.co.mafori.creditadvisory.onboarding;

/**
 * The login-details email: the app's address, the consultant's email, the
 * temporary password and what happens at the first sign-in. Every value is
 * HTML-escaped (a password can contain &amp; or &lt;).
 *
 * <p>
 * Kept to simple tables and inline styles so it looks the same in Outlook,
 * Gmail and phone mail apps. The colours and wording are easy to change here.
 */
public final class LoginDetailsEmail {

    public static final String SUBJECT = "Your Credit Advisory login details";

    private LoginDetailsEmail() {
    }

    public static LoginDetailsMailer.Message build(String to, String consultantName, String loginUrl,
            String temporaryPassword, String companyName, String supportContact, int validDays) {
        String html = """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="utf-8"><title>%1$s</title></head>
                <body style="margin:0;padding:0;background:#f4f5f7;font-family:Segoe UI,Arial,sans-serif;color:#1f2933;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f5f7;padding:24px 0;">
                <tr><td align="center">
                <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background:#ffffff;border-radius:8px;overflow:hidden;">
                <tr><td style="background:#0b3d5c;padding:20px 28px;color:#ffffff;font-size:18px;font-weight:600;">%2$s Credit Advisory</td></tr>
                <tr><td style="padding:28px;">
                <p style="margin:0 0 16px;font-size:15px;">Hi %3$s,</p>
                <p style="margin:0 0 16px;font-size:15px;line-height:1.5;">An account has been created for you on the %2$s Credit Advisory application. Use the details below to sign in for the first time.</p>
                <table role="presentation" cellpadding="0" cellspacing="0" style="width:100%%;border:1px solid #d9dee3;border-radius:6px;margin:0 0 20px;">
                <tr><td style="padding:10px 14px;font-size:13px;color:#52606d;width:170px;">Sign-in page</td><td style="padding:10px 14px;font-size:14px;"><a href="%4$s" style="color:#0b6fa4;">%4$s</a></td></tr>
                <tr><td style="padding:10px 14px;font-size:13px;color:#52606d;border-top:1px solid #d9dee3;">Email</td><td style="padding:10px 14px;font-size:14px;border-top:1px solid #d9dee3;">%5$s</td></tr>
                <tr><td style="padding:10px 14px;font-size:13px;color:#52606d;border-top:1px solid #d9dee3;">Temporary password</td><td style="padding:10px 14px;font-size:16px;font-family:Consolas,Menlo,monospace;letter-spacing:1px;border-top:1px solid #d9dee3;">%6$s</td></tr>
                </table>
                <p style="margin:0 0 8px;font-size:15px;font-weight:600;">When you sign in for the first time</p>
                <ol style="margin:0 0 20px;padding-left:20px;font-size:14px;line-height:1.6;">
                <li>Open the sign-in page and enter your email and the temporary password above. The temporary password works for %8$s days from this email; after that, ask %7$s for new login details.</li>
                <li>Choose your own password (at least 12 characters). The temporary password stops working once you have.</li>
                <li>Set up two-step sign-in with an authenticator app on your phone when asked.</li>
                </ol>
                <p style="margin:0 0 16px;font-size:13px;color:#52606d;line-height:1.5;">Keep this email private and delete it after your first sign-in. Nobody from %2$s will ever ask you for your password. If you did not expect this email, contact %7$s.</p>
                </td></tr>
                <tr><td style="background:#f4f5f7;padding:14px 28px;font-size:12px;color:#7b8794;">This message was sent automatically by the %2$s Credit Advisory application. Please do not reply.</td></tr>
                </table>
                </td></tr></table>
                </body></html>
                """
                .formatted(escape(SUBJECT), escape(companyName), escape(consultantName), escape(loginUrl),
                        escape(to), escape(temporaryPassword), escape(supportContact), validDays);
        return new LoginDetailsMailer.Message(to, SUBJECT, html);
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
