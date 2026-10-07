package com.lhdcprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * 实验 CLI 入口：adb shell content call --uri content://com.lhdcprobe.cli
 *   --method <命令> [--arg <参数>]
 * 仅允许 shell/root/system/自身 uid 调用。同步返回文本结果。
 */
public class CliProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID
                && uid != Process.SYSTEM_UID && uid != Process.myUid()) {
            throw new SecurityException("cli: uid " + uid + " not allowed");
        }
        String result = DirectorCore.get(requireContext()).exec(method, arg);
        Bundle b = new Bundle();
        b.putString("result", result);
        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
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
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        return 0;
    }
}
