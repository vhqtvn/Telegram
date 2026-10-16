package org.telegram.hardkey;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;

/**
 * Auto-init entry point (D0). Declared in this library's manifest; the
 * platform instantiates and initializes it before Application.onCreate().
 * That slot is used to register the activity-lifecycle installer. The
 * provider surface itself is a no-op: query returns null, mutators return 0.
 */
public class HardkeyInit extends ContentProvider {

    @Override
    public boolean onCreate() {
        if (getContext() != null && getContext().getApplicationContext() instanceof Application) {
            Application app = (Application) getContext().getApplicationContext();
            app.registerActivityLifecycleCallbacks(new HardkeyInstaller());
            DriftReport.event("init: lifecycle installer registered (pid " + Process.myPid() + ")");
        } else {
            DriftReport.event("init: no Application context, installer NOT registered");
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
