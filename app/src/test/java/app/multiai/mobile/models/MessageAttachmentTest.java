package app.multiai.mobile.models;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import app.multiai.mobile.utils.ImageAttachments;
import static org.junit.Assert.*;

public class MessageAttachmentTest {
    @Test public void editingPersistedSinglePhotoAppendsInsteadOfReplacing() throws Exception {
        Path image = Files.createTempFile("multiai-photo-test", ".jpg");
        try {
            byte[] bytes = {1, 2, 3};
            Files.write(image, bytes);
            Message message = new Message("text", Message.TYPE_USER);
            message.setImagePath(image.toString());
            String original = Base64.getEncoder().encodeToString(bytes);
            assertEquals(original, message.getImageBase64());
            message.replaceImages(ImageAttachments.append(message.getImageBase64(), "BAUG"));
            assertEquals(2, ImageAttachments.unpack(message.getImageBase64()).size());
            assertNull(message.getImagePath());
            message.replaceImages(null);
            assertFalse(message.hasImage());
            assertNull(message.getImageBase64());
        } finally { Files.deleteIfExists(image); }
    }
}
