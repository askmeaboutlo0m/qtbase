// Copyright (C) 2021 Sharaf Zaman
// SPDX-License-Identifier: LicenseRef-Qt-Commercial OR LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only
#include "qandroidplatformcursor.h"
#include "qandroidplatformscreen.h"
#include <QBitmap>
#include <QBuffer>
#include <QPixmap>
#include <QtCore/private/qjnihelpers_p.h>
#include <androidjnimain.h>
#include <private/qguiapplication_p.h>
#include <private/qhighdpiscaling_p.h>
#include <qpa/qplatformwindow.h>

QAndroidPlatformCursor::QAndroidPlatformCursor(QAndroidPlatformScreen *screen)
    : m_qtPointer(QJniObject::callStaticObjectMethod("org/qtproject/qt/android/QtPointerIcon",
                                                     "instance",
                                                     "()Lorg/qtproject/qt/android/QtPointerIcon;")),
      m_screen(screen)
{
}

void QAndroidPlatformCursor::changeCursor(QCursor *cursor, QWindow *window)
{
    if (!window || !window->handle()) {
        return;
    }

    if (cursor) {
        if (cursor->shape() == Qt::BitmapCursor) {
            qint64 key = cursor->bitmap().cacheKey();
            bool exists = m_qtPointer.callMethod<jboolean>("existsInCache", "(J)Z", key);
            if (exists) {
                m_qtPointer.callMethod<void>("setCachedBitmapIcon", "(J)V", key);
                return;
            }

            QImage mask = cursor->mask().toImage().convertToFormat(QImage::Format_Mono);
            mask.invertPixels();

            QImage bitmap = cursor->bitmap().toImage();
            bitmap.setAlphaChannel(mask);

            QByteArray bytes;
            QBuffer buffer(&bytes);
            buffer.open(QIODevice::WriteOnly);
            bitmap.save(&buffer, "PNG");

            jbyte *pixels = (jbyte *)bytes.data();

            QJniEnvironment env;
            // we don't need to release the memory since we don't pin it
            jbyteArray array = env->NewByteArray(bytes.size());
            env->SetByteArrayRegion(array, 0, bytes.size(), pixels);

            m_qtPointer.callMethod<void>("setBitmapIcon", "([BIIIIJ)V", array, bitmap.width(),
                                         bitmap.height(), cursor->hotSpot().x(),
                                         cursor->hotSpot().y(), key);
        } else {
            m_qtPointer.callMethod<void>("setIcon", "(I)V", static_cast<int>(cursor->shape()));
        }
    } else {
        m_qtPointer.callMethod<void>("setIcon", "(I)V", static_cast<int>(Qt::BlankCursor));
    }
}

QPoint QAndroidPlatformCursor::pos() const
{
    return QHighDpi::toNativePixels(QGuiApplicationPrivate::lastCursorPosition.toPoint(),
                                    m_screen->screen());
}
