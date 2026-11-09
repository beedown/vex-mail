package com.vexmail.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/** Hands a saved attachment to the app that opens it (a photo viewer, a PDF reader...). */
public class AttachmentProvider extends ContentProvider {
    static final String AUTHORITY = "com.vexmail.app.files";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..") || getContext() == null) return null;
        return new File(new File(getContext().getCacheDir(), "att"), name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException("gone");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
        int dot = name.lastIndexOf('.');
        String t = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
        return t == null ? "application/octet-stream" : t;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = file(uri);
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        if (f != null && f.exists()) c.addRow(new Object[]{f.getName().replaceFirst("^\\d+_", ""), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
