package org.hdvcam.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.hdvcam.core.DeviceFamily;
import org.junit.Test;

import java.io.IOException;

public final class CameraMediaRepositoryTest {
    @Test public void resolvesObservedDatabaseEndpointForEachFirmwareFamily() {
        CameraMediaRepository repository = new CameraMediaRepository();
        assertEquals("http://192.168.100.1:80/data/sunxi.db", repository.databaseUrl(DeviceFamily.V3));
        assertEquals("http://192.168.10.1:8082/tmp/data/.data/sqlite/sunxi.db", repository.databaseUrl(DeviceFamily.V316));
        assertEquals("http://192.168.1.1:80/tmp/DCF.db", repository.databaseUrl(DeviceFamily.MSTAR01));
    }

    @Test public void thumbnailLoaderRejectsHostsOutsideKnownCameraEndpoints() {
        assertThrows(IOException.class,
                () -> new CameraMediaRepository().loadThumbnail(null, "http://example.com/image.jpg"));
    }

    @Test public void rejectsUnknownFamilyInsteadOfChoosingAnEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraMediaRepository().databaseUrl(null));
    }
}
