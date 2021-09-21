// Copyright (C) 2021 Sharaf Zaman
// SPDX-License-Identifier: LicenseRef-Qt-Commercial OR LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only
#ifndef __QANDROIDPLATFORMCURSOR_H_
#define __QANDROIDPLATFORMCURSOR_H_

#include <QtCore/qjniobject.h>
#include <qpa/qplatformcursor.h>

class QAndroidPlatformScreen;

class QAndroidPlatformCursor : public QPlatformCursor
{
public:
    explicit QAndroidPlatformCursor(QAndroidPlatformScreen *screen);

    void changeCursor(QCursor *windowCursor, QWindow *window) override;
    QPoint pos() const override;

private:
    QJniObject m_qtPointer;
    QAndroidPlatformScreen *m_screen;
};

#endif // __QANDROIDPLATFORMCURSOR_H_
