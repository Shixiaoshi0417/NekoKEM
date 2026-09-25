import com.shixiaoshi0417.nekokem.files.SaveDocumentContractKt;
import com.shixiaoshi0417.nekokem.files.SaveDocumentRequest;

public final class OutputNameTest {
    private OutputNameTest() {
    }

    public static void main(String[] args) {
        verifyRoundTrip("photo.jpg", "image/jpeg");
        verifyRoundTrip("video.mp4", "video/mp4");
        verifyRoundTrip("notes.txt", "text/plain");

        String decrypted = SaveDocumentContractKt.decryptedOutputName(
            "photo.jpg", "fallback", "decrypted-file");
        requireEqual("decrypted_photo.jpg", decrypted);
        System.out.println("Android output-name and MIME tests passed");
    }

    private static void verifyRoundTrip(String original, String mimeType) {
        SaveDocumentRequest encrypted =
            SaveDocumentContractKt.encryptedDocumentRequest(
                original, "fallback");
        requireEqual(original + ".nkem", encrypted.getDisplayName());
        requireEqual("application/octet-stream", encrypted.getMimeType());
        if (encrypted.getDisplayName().endsWith(".bin")) {
            throw new AssertionError("Encrypted suggestion gained .bin");
        }

        SaveDocumentRequest decrypted =
            SaveDocumentContractKt.decryptedDocumentRequest(
                encrypted.getDisplayName(),
                "fallback",
                "decrypted-file");
        requireEqual(original, decrypted.getDisplayName());
        requireEqual(mimeType, decrypted.getMimeType());
    }

    private static void requireEqual(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(
                "Expected [" + expected + "] but found [" + actual + "]");
        }
    }
}
