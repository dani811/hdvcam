package com.hdvcam.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class CameraMediaFileTest {
    @Test public void mapsMstarDcfVideoPathsSizeAndThumbnail() {
        String[] row = {"0", "/DCIM/VID_001.MP4", "/DCIM/VID_001.sec", "/thumb/VID_001.jpg", "4", "5", "6", "4096"};
        CameraMediaFile file = CameraMediaFile.fromMstarDcfRow(row);
        assertEquals("VID_001.MP4", file.name());
        assertEquals(CameraMediaFile.VIDEO, file.type());
        assertEquals("http://192.168.1.1/DCIM/VID_001.MP4", file.url());
        assertEquals(Long.valueOf(4096), file.sizeBytes());
        assertEquals("http://192.168.1.1/DCIM/VID_001.sec", file.secureVideoUrl());
        assertEquals("http://192.168.1.1/thumb/VID_001.jpg", file.thumbnailUrl());
    }

    @Test public void mapsV316MediaAndDerivedSidecars() {
        CameraMediaFile video = CameraMediaFile.fromV316MediaFile100Row(new String[]{"id", "type", "folder/clip.mp4"});
        assertEquals("clip.mp4", video.name());
        assertEquals(CameraMediaFile.VIDEO, video.type());
        assertEquals("http://192.168.10.1:8082/file/media/folder/clip.mp4", video.url());
        assertEquals("http://192.168.10.1:8082/file/media/folder/clip.sec", video.secureVideoUrl());
        assertEquals("http://192.168.10.1:8082/file/media/folder/clip.thm", video.thumbnailUrl());
        assertNull(video.sizeBytes());

        CameraMediaFile photo = CameraMediaFile.fromV316MediaFile100Row(new String[]{"id", "type", "folder/pic.jpg"});
        assertEquals(CameraMediaFile.PHOTO, photo.type());
        assertNull(photo.secureVideoUrl());
        assertEquals("http://192.168.10.1:8082/file/media/folder/pic.thm", photo.thumbnailUrl());
    }

    @Test public void mapsV3CdrFilePathTypeAndTime() {
        CameraMediaFile file = CameraMediaFile.fromV3CdrFileRow(new String[]{"/mnt/extsd/photo/a.jpg", "1730000000", "photo"});
        assertEquals("a.jpg", file.name());
        assertEquals("photo", file.type());
        assertEquals("http://192.168.100.1:80//mnt/extsd/photo/a.jpg", file.url());
        assertEquals(Long.valueOf(1730000000), file.time());
        assertNull(file.sizeBytes());
        assertNull(file.thumbnailUrl());
    }

    @Test public void rejectsRowsTooShortForObservedPositionalMapper() {
        assertThrows(IllegalArgumentException.class,
                () -> CameraMediaFile.fromMstarDcfRow(new String[]{"only", "seven", "columns"}));
    }
}
