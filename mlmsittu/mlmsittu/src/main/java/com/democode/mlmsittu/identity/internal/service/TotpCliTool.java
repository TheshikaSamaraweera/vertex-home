package com.democode.mlmsittu.identity.internal.service;

/**
 * Prints the TOTP code for a secret so the 2FA login step can be exercised in Postman without
 * reaching for a phone.
 *
 * <pre>
 *   ./gradlew totp -Psecret=JBSWY3DPEHPK3PXP
 * </pre>
 *
 * <p>A developer convenience, not a backdoor: it needs the shared secret as input, which is
 * exactly the thing an attacker would not have. Nothing in the running application calls it.
 */
public final class TotpCliTool {

    private TotpCliTool() {}

    public static void main(String[] args) {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Usage: ./gradlew totp -Psecret=<base32 secret>");
            System.exit(2);
            return;
        }

        TotpService totp = new TotpService("MLM Sittu");
        String secret = args[0].trim();
        long secondsIntoStep = System.currentTimeMillis() / 1000 % 30;

        System.out.println();
        System.out.println("  secret : " + secret);
        System.out.println("  code   : " + totp.currentCode(secret));
        System.out.println("  valid  : ~" + (30 - secondsIntoStep) + "s longer");
        System.out.println();
    }
}
