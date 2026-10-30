package org.hdvcam.app;

import android.net.Network;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.hdvcam.core.CameraMediaFile;
import com.hdvcam.core.DeviceFamily;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fetches the original app's remote media index DB and maps its rows, read-only. */
public final class CameraMediaRepository {
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final long MAX_DATABASE_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024;
    private static final int MAX_THUMBNAIL_DIMENSION = 192;
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public interface Progress { void onProgress(long bytes); }

    public String databaseUrl(DeviceFamily family) {
        if (family == null) throw new IllegalArgumentException("Familia de cámara no soportada: null");
        switch (family) {
            case V3: return "http://192.168.100.1:80/data/sunxi.db";
            case V316: return "http://192.168.10.1:8082/tmp/data/.data/sqlite/sunxi.db";
            case MSTAR01: return "http://192.168.1.1:80/tmp/DCF.db";
            default: throw new IllegalArgumentException("Familia de cámara no soportada: " + family);
        }
    }

    /** Downloads a fresh DB to a temporary file, validates its SQLite header, then atomically replaces cache. */
    public File refresh(Network network, File cacheDirectory, DeviceFamily family, Progress progress) throws IOException {
        if (family == null || cacheDirectory == null) throw new IllegalArgumentException("Familia y caché son obligatorias.");
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) throw new IOException("No se pudo crear la caché de medios.");
        File target = new File(cacheDirectory, family.name().toLowerCase(Locale.ROOT) + "-camera-media.db");
        File temporary = new File(cacheDirectory, target.getName() + ".download");
        HttpURLConnection connection = null;
        try {
            URL url = new URL(databaseUrl(family));
            connection = (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("La cámara respondió HTTP " + status + " al descargar su índice.");
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_DATABASE_BYTES) throw new IOException("La base de medios supera el límite local de 128 MiB.");
            long copied = 0;
            byte[] header = new byte[SQLITE_MAGIC.length];
            int headerLength = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    copied += read;
                    if (copied > MAX_DATABASE_BYTES) throw new IOException("La base de medios supera el límite local de 128 MiB.");
                    if (headerLength < header.length) {
                        int amount = Math.min(read, header.length - headerLength);
                        System.arraycopy(buffer, 0, header, headerLength, amount);
                        headerLength += amount;
                    }
                    output.write(buffer, 0, read);
                    if (progress != null) progress.onProgress(copied);
                }
            }
            if (headerLength != SQLITE_MAGIC.length || !java.util.Arrays.equals(header, SQLITE_MAGIC)) {
                throw new IOException("El recurso recibido no tiene una cabecera SQLite válida.");
            }
            if (copied == 0) throw new IOException("La cámara devolvió una base vacía.");
            File previous = new File(cacheDirectory, target.getName() + ".previous");
            if (previous.exists() && !previous.delete()) throw new IOException("No se pudo limpiar la caché temporal anterior.");
            boolean hadTarget = target.exists();
            if (hadTarget && !target.renameTo(previous)) throw new IOException("No se pudo reservar la caché anterior.");
            if (!temporary.renameTo(target)) {
                if (hadTarget) previous.renameTo(target);
                throw new IOException("No se pudo finalizar la descarga de la base de medios.");
            }
            if (previous.exists()) previous.delete();
            return target;
        } finally {
            if (connection != null) connection.disconnect();
            if (temporary.exists()) temporary.delete();
        }
    }

    /** Loads a small preview only from the camera IPs recovered for this app, using the chosen Wi-Fi. */
    public Bitmap loadThumbnail(Network network, String thumbnailUrl) throws IOException {
        if (thumbnailUrl == null || thumbnailUrl.isEmpty()) return null;
        URL url = new URL(thumbnailUrl);
        String host = url.getHost();
        if (!"http".equalsIgnoreCase(url.getProtocol())
                || !("192.168.1.1".equals(host) || "192.168.10.1".equals(host) || "192.168.100.1".equals(host))) {
            throw new IOException("La URL de thumbnail no pertenece a un endpoint local de cámara conocido.");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            if (connection.getContentLengthLong() > MAX_THUMBNAIL_BYTES) return null;
            byte[] bytes;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_THUMBNAIL_BYTES) return null;
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > MAX_THUMBNAIL_DIMENSION
                    || bounds.outHeight / sample > MAX_THUMBNAIL_DIMENSION) sample *= 2;
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public int count(File database, DeviceFamily family) throws IOException {
        String table = table(family);
        try (SQLiteDatabase sqlite = open(database)) {
            try (Cursor cursor = sqlite.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
                if (!cursor.moveToFirst()) return 0;
                return cursor.getInt(0);
            }
        } catch (RuntimeException failure) {
            throw new IOException("No se pudo consultar la tabla " + table + " de la base de cámara.", failure);
        }
    }

    public List<CameraMediaFile> page(File database, DeviceFamily family, int offset, int limit) throws IOException {
        if (offset < 0 || limit < 1 || limit > 500) throw new IllegalArgumentException("Paginación fuera de rango.");
        String table = table(family);
        String order = family == DeviceFamily.V3 ? "time" : family == DeviceFamily.V316 ? "id" : "ID";
        String sql = "SELECT * FROM " + table + " ORDER BY " + order + " DESC LIMIT " + limit + " OFFSET " + offset;
        List<CameraMediaFile> files = new ArrayList<>();
        try (SQLiteDatabase sqlite = open(database); Cursor cursor = sqlite.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                String[] row = new String[cursor.getColumnCount()];
                for (int i = 0; i < row.length; i++) row[i] = cursor.isNull(i) ? null : cursor.getString(i);
                if (family == DeviceFamily.MSTAR01) files.add(CameraMediaFile.fromMstarDcfRow(row));
                else if (family == DeviceFamily.V316) files.add(CameraMediaFile.fromV316MediaFile100Row(row));
                else files.add(CameraMediaFile.fromV3CdrFileRow(row));
            }
            return files;
        } catch (RuntimeException failure) {
            throw new IOException("No se pudo leer una página de " + table + " en la base de cámara.", failure);
        }
    }

    private static SQLiteDatabase open(File database) throws IOException {
        if (database == null || !database.isFile()) throw new IOException("Primero actualiza el índice remoto de medios.");
        try { return SQLiteDatabase.openDatabase(database.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY); }
        catch (RuntimeException failure) { throw new IOException("La caché de medios no es una base SQLite legible.", failure); }
    }

    private static String table(DeviceFamily family) {
        if (family == DeviceFamily.MSTAR01) return "DCF";
        if (family == DeviceFamily.V316) return "MediaFile100";
        if (family == DeviceFamily.V3) return "CdrFile";
        throw new IllegalArgumentException("Familia de cámara no soportada: " + family);
    }
}
