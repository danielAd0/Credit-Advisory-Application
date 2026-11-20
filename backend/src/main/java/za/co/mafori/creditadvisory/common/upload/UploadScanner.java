package za.co.mafori.creditadvisory.common.upload;

import java.io.InputStream;

/**
 * Scans one uploaded file for viruses (docs/security-accounts-and-uploads.md).
 */
public interface UploadScanner {

    /**
     * @return the verdict; never null
     * @throws UploadScanUnavailableException when the file could not be scanned
     *                                        (scanner down, timeout, file too big
     *                                        for the scanner): the upload is then
     *                                        refused, never let through unscanned
     */
    Verdict scan(InputStream content);

    /** @param signature the virus found, null when clean */
    record Verdict(boolean clean, String signature) {

        public static Verdict ok() {
            return new Verdict(true, null);
        }

        public static Verdict infected(String signature) {
            return new Verdict(false, signature);
        }
    }

    /**
     * The file could not be scanned. The message never contains the file's content.
     */
    class UploadScanUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UploadScanUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public UploadScanUnavailableException(String message) {
            super(message);
        }
    }
}
