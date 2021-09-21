// Copyright (C) 2021 Sharaf Zaman
// SPDX-License-Identifier: LicenseRef-Qt-Commercial OR LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only
package org.qtproject.qt.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;
import android.util.LruCache;
import android.view.PointerIcon;

@SuppressWarnings("unused")
class CursorShape {
    public static final int ArrowCursor = 0;
    public static final int UpArrowCursor = 1;
    public static final int CrossCursor = 2;
    public static final int WaitCursor = 3;
    public static final int IBeamCursor = 4;
    public static final int SizeVerCursor = 5;
    public static final int SizeHorCursor = 6;
    public static final int SizeBDiagCursor = 7;
    public static final int SizeFDiagCursor = 8;
    public static final int SizeAllCursor = 9;
    public static final int BlankCursor = 10;
    public static final int SplitVCursor = 11;
    public static final int SplitHCursor = 12;
    public static final int PointingHandCursor = 13;
    public static final int ForbiddenCursor = 14;
    public static final int WhatsThisCursor = 15;
    public static final int BusyCursor = 16;
    public static final int OpenHandCursor = 17;
    public static final int ClosedHandCursor = 18;
    public static final int DragCopyCursor = 19;
    public static final int DragMoveCursor = 20;
    public static final int DragLinkCursor = 21;
    public static final int LastCursor = DragLinkCursor;
    public static final int BitmapCursor = 24;
    public static final int CustomCursor = 5;

    public static PointerIcon getPointerIconQt(int type) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return null;
        }
        switch (type) {
            case ArrowCursor:
                    return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_ARROW);
            case CrossCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_CROSSHAIR);
            case WaitCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_WAIT);
            case BlankCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_NULL);
            case IBeamCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_TEXT);
            case SizeBDiagCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW);
            case SizeFDiagCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW);
            case SplitVCursor:
            case SizeVerCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW);
            case SplitHCursor:
            case SizeHorCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW);
            case PointingHandCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_HAND);
            case ForbiddenCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_NO_DROP);
            case OpenHandCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_GRAB);
            case ClosedHandCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_GRABBING);
            case DragMoveCursor:
            case DragCopyCursor:
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_COPY);
            default:
                // TYPE_DEFAULT exists, but it's deprecated and the same as TYPE_ARROW.
                return PointerIcon.getSystemIcon(QtNative.getContext(), PointerIcon.TYPE_ARROW);
        }
    }
}

@SuppressWarnings("UnusedDeclaration")
public class QtPointerIcon {

    private static final String TAG = "QtPointerIcon";
    private static QtPointerIcon sQtPointer;
    // we cache on Java side, passing data over Jni can be expensive
    private final LruCache<Long, PointerIcon> iconCache = new LruCache<>(10);
    private PointerIcon icon;

    public static QtPointerIcon instance() {
        if (sQtPointer == null) {
            sQtPointer = new QtPointerIcon();
        }
        return sQtPointer;
    }

    public PointerIcon getIcon() {
        return icon;
    }

    public void setIcon(int type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            icon = CursorShape.getPointerIconQt(type);
        }
    }

    public void setCachedBitmapIcon(long cacheKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            icon = iconCache.get(cacheKey);
        }
    }

    public void setBitmapIcon(byte[] colors, int w, int h, int hX, int hY, long cacheKey) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(colors, 0, colors.length);
        if (bitmap == null) {
            Log.e(TAG, "PointerIcon bitmap is null!");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            icon = PointerIcon.create(bitmap, hX, hY);
            iconCache.put(cacheKey, icon);
        }
    }

    public boolean existsInCache(long key) {
        return iconCache.get(key) != null;
    }
}
