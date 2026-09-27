// Copyright (C) 2023 The Qt Company Ltd.
// SPDX-License-Identifier: LicenseRef-Qt-Commercial OR LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only

package org.qtproject.qt.android;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.text.method.MetaKeyKeyListener;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.view.Window;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsAnimation.Callback;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;

class QtInputDelegate implements QtInputConnection.QtInputConnectionListener, QtInputInterface
{

    private static final String TAG = "QtInputDelegate";

    // After how many subsequent messages with a good or bad position history
    // the device will be regarded as always producing good or bad histories.
    // The situation tends to be very clear, so a low value like this is fine.
    private static final int HISTORY_QUALITY_THRESHOLD = 16;

    // keyboard methods
    static native void keyDown(int key, int unicode, int modifier, boolean autoRepeat);
    static native void keyUp(int key, int unicode, int modifier, boolean autoRepeat);
    static native void keyboardVisibilityChanged(boolean visibility);
    static native void keyboardGeometryChanged(int x, int y, int width, int height);
    // keyboard methods

    // dispatch events methods
    static native boolean dispatchGenericMotionEvent(MotionEvent event);
    static native boolean dispatchKeyEvent(KeyEvent event);
    // dispatch events methods

    // handle methods
    static native void handleLocationChanged(int id, int x, int y);
    // handle methods

    private QtEditText m_currentEditText = null;
    private InputMethodManager m_imm;

    // We can't rely on a hardcoded value, because screens have different resolutions.
    // That is why we assume that the keyboard should be higher than 0.15 of the screen.
    private static final float KEYBOARD_TO_SCREEN_RATIO = 0.15f;

    private boolean m_keyboardTransitionInProgress = false;
    private boolean m_keyboardIsVisible = false;
    private boolean m_isKeyboardHidingAnimationOngoing = false;
    private long m_showHideTimeStamp = System.nanoTime();
    private int m_portraitKeyboardHeight = 0;
    private int m_landscapeKeyboardHeight = 0;
    private int m_probeKeyboardHeightDelayMs = 50;

    private int m_softInputMode = 0;

    private static Boolean m_tabletEventSupported = null;

    private static int m_oldX, m_oldY;
    private static Map<Integer, Integer> m_historyQualities = new HashMap<>();


    private long m_metaState;
    private int m_lastChar = 0;
    private boolean m_backKeyPressedSent = false;

    // Note: because of the circular call to updateFullScreen() from the delegate, we need
    // a listener to be able to do that call from the delegate, because that's where that
    // logic lives
    interface KeyboardVisibilityListener {
        void onKeyboardVisibilityChange();
    }

    private final KeyboardVisibilityListener m_keyboardVisibilityListener;

    QtInputDelegate(KeyboardVisibilityListener listener)
    {
        m_keyboardVisibilityListener = listener;
    }

    void initInputMethodManager(Activity activity)
    {
        m_imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m_imm == null)
            Log.w(TAG, "getSystemService() returned a null InputMethodManager instance");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            View rootView = activity.getWindow().getDecorView();
            ViewTreeObserver observer = rootView.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                private boolean m_lastImeVisibility = false;

                @Override
                public void onGlobalLayout() {
                    WindowInsets windowInsets = rootView.getRootWindowInsets();
                    if (windowInsets == null)
                        return;

                    boolean imeVisible = windowInsets.isVisible(WindowInsets.Type.ime());
                    if (m_lastImeVisibility != imeVisible) {
                        m_lastImeVisibility = imeVisible;
                        setKeyboardVisibility_internal(imeVisible, System.nanoTime());
                    }

                    if (!isKeyboardHidden())
                        setKeyboardTransitionInProgress(false);
                }
            });
        }
    }

    private void setKeyboardTransitionInProgress(boolean state)
    {
        if (m_currentEditText == null || m_keyboardTransitionInProgress == state)
            return;

        m_keyboardTransitionInProgress = state;
    }

    // QtInputInterface implementation begin
    @Override
    public void updateSelection(final int selStart, final int selEnd,
                                final int candidatesStart, final int candidatesEnd)
    {
        if (m_imm != null) {
            QtNative.runAction(() -> {
                if (m_imm != null) {
                    m_imm.updateSelection(m_currentEditText, selStart, selEnd,
                            candidatesStart, candidatesEnd);
                }
            });
        }
    }

    private void showKeyboard(Activity activity,
                              final int x, final int y, final int width, final int height,
                              final int inputHints, final int enterKeyType)
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Window window = activity.getWindow();
            View decorView = window.getDecorView();
            decorView.setWindowInsetsAnimationCallback(
                new WindowInsetsAnimation.Callback(
                    WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        @Override
                        public WindowInsets onProgress(
                            WindowInsets insets, List<WindowInsetsAnimation> animationList) {
                                return insets;
                        }
                        @Override
                        public void onEnd(WindowInsetsAnimation animation) {
                            decorView.setWindowInsetsAnimationCallback(null);
                            if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0) {
                                QtNativeInputConnection.updateCursorPosition();
                                if (m_softInputMode == 0) {
                                    probeForKeyboardHeight(activity, x, y, width, height,
                                                            inputHints, enterKeyType);
                                }
                            }
                        }
                });
            window.getInsetsController().show(Type.ime());
        } else {
            if (m_imm == null)
                return;
            m_imm.showSoftInput(m_currentEditText, 0, new ResultReceiver(new Handler(Looper.getMainLooper())) {
                @Override
                @SuppressWarnings("fallthrough")
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    switch (resultCode) {
                        case InputMethodManager.RESULT_SHOWN:
                            QtNativeInputConnection.updateCursorPosition();
                            //FALLTHROUGH
                        case InputMethodManager.RESULT_UNCHANGED_SHOWN:
                            setKeyboardVisibility(true, System.nanoTime());
                            if (m_softInputMode == 0) {
                                probeForKeyboardHeight(activity,
                                        x, y, width, height, inputHints, enterKeyType);
                            }
                            break;
                        case InputMethodManager.RESULT_HIDDEN:
                        case InputMethodManager.RESULT_UNCHANGED_HIDDEN:
                            setKeyboardVisibility(false, System.nanoTime());
                            break;
                    }
                }
            });
        }
    }

    @Override
    public void showSoftwareKeyboard(Activity activity,
                                     final int x, final int y, final int width, final int height,
                                     final int inputHints, final int enterKeyType)
    {
        if (m_imm == null)
            return;

        QtNative.runAction(() -> {
            if (m_imm == null || m_currentEditText == null)
                return;

            if (updateSoftInputMode(activity, height))
                return;

            m_currentEditText.setEditTextOptions(enterKeyType, inputHints);
            m_currentEditText.setLayoutParams(new QtLayout.LayoutParams(width, height, x, y));
            m_currentEditText.requestFocus();
            m_currentEditText.postDelayed(() -> {
                showKeyboard(activity, x, y, width, height, inputHints, enterKeyType);
                if (m_currentEditText.m_optionsChanged) {
                    m_imm.restartInput(m_currentEditText);
                    m_currentEditText.m_optionsChanged = false;
                }
            }, 15);
        });
    }

    @Override
    public int getSelectionHandleWidth()
    {
        return m_currentEditText == null ? 0 : m_currentEditText.getSelectionHandleWidth();
    }

    /* called from the C++ code when the position of the cursor or selection handles needs to
       be adjusted.
       mode is one of QAndroidInputContext::CursorHandleShowMode
    */
    @Override
    public void updateHandles(int mode, int editX, int editY, int editButtons,
                              int x1, int y1, int x2, int y2, boolean rtl)
    {
        QtNative.runAction(() -> {
            if (m_currentEditText != null)
                m_currentEditText.updateHandles(mode, editX, editY, editButtons, x1, y1, x2, y2, rtl);
        });
    }

    @Override
    public QtInputConnection.QtInputConnectionListener getInputConnectionListener()
    {
        return this;
    }

    @Override
    public void resetSoftwareKeyboard()
    {
        if (m_imm == null || m_currentEditText == null)
            return;
        m_currentEditText.postDelayed(() -> {
            if (m_imm == null || m_currentEditText == null)
                return;
            m_imm.restartInput(m_currentEditText);
            m_currentEditText.m_optionsChanged = false;
        }, 5);
    }

    @Override
    public void hideSoftwareKeyboard()
    {
        if (m_imm == null || m_currentEditText == null)
            return;

        m_isKeyboardHidingAnimationOngoing = true;
        QtNative.runAction(() -> {
            if (m_imm == null || m_currentEditText == null)
                return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Activity activity = QtNative.activity();
                if (activity == null) {
                    Log.w(TAG, "hideSoftwareKeyboard: The activity reference is null");
                    return;
                }
                activity.getWindow().getInsetsController().hide(Type.ime());
            } else {
                m_imm.hideSoftInputFromWindow(m_currentEditText.getWindowToken(), 0,
                        new ResultReceiver(new Handler(Looper.getMainLooper())) {
                            @Override
                            protected void onReceiveResult(int resultCode, Bundle resultData) {
                                switch (resultCode) {
                                    case InputMethodManager.RESULT_SHOWN:
                                    case InputMethodManager.RESULT_UNCHANGED_SHOWN:
                                        setKeyboardVisibility(true, System.nanoTime());
                                        break;
                                    case InputMethodManager.RESULT_HIDDEN:
                                    case InputMethodManager.RESULT_UNCHANGED_HIDDEN:
                                        setKeyboardVisibility(false, System.nanoTime());
                                        break;
                                }
                            }
                        });
            }
        });
    }

    // Is the keyboard fully visible i.e. visible and no ongoing animation
    @Override
    public boolean isSoftwareKeyboardVisible()
    {
        return isKeyboardVisible() && !m_isKeyboardHidingAnimationOngoing;
    }
    // QtInputInterface implementation end

    // QtInputConnectionListener methods
    @Override
    public boolean keyboardTransitionInProgress() {
       return m_keyboardTransitionInProgress;
    }

    @Override
    public boolean isKeyboardHidden() {
        Activity activity = QtNative.activity();
        if (activity == null) {
            Log.w(TAG, "isKeyboardHidden: The activity reference is null");
            return true;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Rect r = new Rect();
            activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
            DisplayMetrics metrics = new DisplayMetrics();
            QtDisplayManager.getDisplay(activity).getMetrics(metrics);
            int screenHeight = metrics.heightPixels;
            final int kbHeight = screenHeight - r.bottom;
            return kbHeight < screenHeight * KEYBOARD_TO_SCREEN_RATIO;
        }

        return !m_keyboardIsVisible;
    }

    @Override
    public void onSetClosing(boolean closing) {
        if (!closing)
            setKeyboardVisibility(true, System.nanoTime());
    }

    @Override
    public void onHideKeyboardRunnableDone(boolean visibility, long hideTimeStamp) {
        setKeyboardVisibility(visibility, hideTimeStamp);
    }

    @Override
    public void onSendKeyEventDefaultCase() {
        hideSoftwareKeyboard();
    }

    @Override
    public void onEditTextChanged(QtEditText editText) {
        setFocusedView(editText);
    }
    // QtInputConnectionListener methods

    boolean isKeyboardVisible()
    {
        return m_keyboardIsVisible;
    }

    void setSoftInputMode(int inputMode)
    {
        m_softInputMode = inputMode;
    }

    QtEditText getCurrentQtEditText()
    {
        return m_currentEditText;
    }

    private void keyboardVisibilityUpdated(boolean visibility)
    {
        m_isKeyboardHidingAnimationOngoing = false;
        QtInputDelegate.keyboardVisibilityChanged(visibility);
    }

    void setKeyboardVisibility(boolean visibility, long timeStamp)
    {
        // Since API 30 keyboard visibility changes are tracked by the global layout listener
        // observing root window insets. There are no manual changes anymore
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            setKeyboardVisibility_internal(visibility, timeStamp);
    }

    private void setKeyboardVisibility_internal(boolean visibility, long timeStamp)
    {
        if (m_showHideTimeStamp > timeStamp)
            return;
        m_showHideTimeStamp = timeStamp;

        if (m_keyboardIsVisible == visibility)
            return;
        m_keyboardIsVisible = visibility;
        keyboardVisibilityUpdated(m_keyboardIsVisible);
        setKeyboardTransitionInProgress(visibility);

        if (!visibility) {
            // Hiding the keyboard clears the immersive mode, so we need to set it again.
            m_keyboardVisibilityListener.onKeyboardVisibilityChange();
            if (m_currentEditText != null)
                m_currentEditText.clearFocus();
        }
    }

    void setFocusedView(QtEditText currentEditText)
    {
        setKeyboardTransitionInProgress(false);
        m_currentEditText = currentEditText;
    }

    private boolean updateSoftInputMode(Activity activity, int height)
    {
        DisplayMetrics metrics = new DisplayMetrics();
        QtDisplayManager.getDisplay(activity).getMetrics(metrics);

        // If the screen is in portrait mode than we estimate that keyboard height
        // will not be higher than 2/5 of the screen. Otherwise we estimate that keyboard height
        // will not be higher than 2/3 of the screen
        final int visibleHeight;
        if (metrics.widthPixels < metrics.heightPixels) {
            visibleHeight = m_portraitKeyboardHeight != 0 ?
                    m_portraitKeyboardHeight : metrics.heightPixels * 3 / 5;
        } else {
            visibleHeight = m_landscapeKeyboardHeight != 0 ?
                    m_landscapeKeyboardHeight : metrics.heightPixels / 3;
        }

        if (m_softInputMode != 0) {
            activity.getWindow().setSoftInputMode(m_softInputMode);
            int stateHidden = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
            return (m_softInputMode & stateHidden) != 0;
        } else {
            int stateUnchanged = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
            if (height > visibleHeight) {
                int adjustResize = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
                activity.getWindow().setSoftInputMode(stateUnchanged | adjustResize);
            } else {
                int adjustPan = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
                activity.getWindow().setSoftInputMode(stateUnchanged | adjustPan);
            }
        }
        return false;
    }

    private void probeForKeyboardHeight(Activity activity, int x, int y,
                                        int width, int height, int inputHints, int enterKeyType)
    {
        if (m_currentEditText == null) {
            Log.w(TAG, "probeForKeyboardHeight: null QtEditText");
            return;
        }
        m_currentEditText.postDelayed(() -> {
            if (!m_keyboardIsVisible)
                return;
            DisplayMetrics metrics = new DisplayMetrics();
            QtDisplayManager.getDisplay(activity).getMetrics(metrics);
            Rect r = new Rect();
            activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
            if (metrics.heightPixels != r.bottom) {
                if (metrics.widthPixels > metrics.heightPixels) { // landscape
                    if (m_landscapeKeyboardHeight != r.bottom) {
                        m_landscapeKeyboardHeight = r.bottom;
                        showSoftwareKeyboard(activity, x, y, width, height,
                                inputHints, enterKeyType);
                    }
                } else {
                    if (m_portraitKeyboardHeight != r.bottom) {
                        m_portraitKeyboardHeight = r.bottom;
                        showSoftwareKeyboard(activity, x, y, width, height,
                                inputHints, enterKeyType);
                    }
                }
            } else {
                // no luck ?
                // maybe the delay was too short, so let's make it longer
                if (m_probeKeyboardHeightDelayMs < 1000)
                    m_probeKeyboardHeightDelayMs *= 2;
            }
        }, m_probeKeyboardHeightDelayMs);
    }

    boolean onKeyDown(int keyCode, KeyEvent event)
    {
        m_metaState = MetaKeyKeyListener.handleKeyDown(m_metaState, keyCode, event);
        int metaState = MetaKeyKeyListener.getMetaState(m_metaState) | event.getMetaState();
        int c = event.getUnicodeChar(metaState);
        int lc = c;
        m_metaState = MetaKeyKeyListener.adjustMetaAfterKeypress(m_metaState);

        if ((c & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            c = c & KeyCharacterMap.COMBINING_ACCENT_MASK;
            c = KeyEvent.getDeadChar(m_lastChar, c);
        }

        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_MUTE)
                && System.getenv("QT_ANDROID_VOLUME_KEYS") == null) {
            return false;
        }

        m_lastChar = lc;
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            m_backKeyPressedSent = !isKeyboardVisible();
            if (!m_backKeyPressedSent)
                return true;
        }

        QtInputDelegate.keyDown(keyCode, c, event.getMetaState(), event.getRepeatCount() > 0);

        return true;
    }

    boolean onKeyUp(int keyCode, KeyEvent event)
    {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_MUTE)
                && System.getenv("QT_ANDROID_VOLUME_KEYS") == null) {
            return false;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK && !m_backKeyPressedSent) {
            hideSoftwareKeyboard();
            setKeyboardVisibility(false, System.nanoTime());
            return true;
        }

        m_metaState = MetaKeyKeyListener.handleKeyUp(m_metaState, keyCode, event);
        boolean autoRepeat = event.getRepeatCount() > 0;
        QtInputDelegate.keyUp(keyCode, event.getUnicodeChar(), event.getMetaState(), autoRepeat);

        return true;
    }

    boolean handleDispatchKeyEvent(KeyEvent event)
    {
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE
                && event.getCharacters() != null
                && event.getCharacters().length() == 1
                && event.getKeyCode() == 0) {
            keyDown(0, event.getCharacters().charAt(0), event.getMetaState(),
                    event.getRepeatCount() > 0);
            keyUp(0, event.getCharacters().charAt(0), event.getMetaState(),
                    event.getRepeatCount() > 0);
        }

        return dispatchKeyEvent(event);
    }

    boolean handleDispatchGenericMotionEvent(MotionEvent event)
    {
        return dispatchGenericMotionEvent(event);
    }

    //////////////////////////////
    //  Mouse and Touch Input   //
    //////////////////////////////

    // tablet methods
    static native boolean isTabletEventSupported();
    static native void tabletEvent(int winId, int deviceId, long time, int action,
                                          int pointerType, int buttonState, float x, float y,
                                          float pressure, float orientation, float tilt, float rz,
                                          int metaState);
    // tablet methods

    // pointer methods
    static native void mouseDown(int winId, long time, int x, int y, int mouseButtonState, int metaState);
    static native void mouseUp(int winId, long time, int x, int y, int mouseButtonState, int metaState);
    static native void mouseMove(int winId, long time, int x, int y, int mouseButtonState, int metaState);
    static native void mouseWheel(int winId, long time, int x, int y, float hDelta, float vDelta, int metaState);
    static native void touchBegin(int winId);
    static native void touchAdd(int winId, int pointerId, int action, boolean primary,
                                       int x, int y, float major, float minor, float rotation,
                                       float pressure);
    static native void touchEnd(int winId, long time, int action, int metaState);
    static native void touchCancel(int winId, long time, int metaState);
    static native void longPress(int winId, int x, int y, int metaState);
    // pointer methods

    static private int getAction(int index, MotionEvent event)
    {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            return getMoveAction(index, event);
        }
        if (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_POINTER_DOWN && index == event.getActionIndex()) {
            return 0;
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_UP && index == event.getActionIndex()) {
            return 3;
        }
        return 2;
    }

    private static int getMoveAction(int index, MotionEvent event)
    {
        int hsz = event.getHistorySize();
        if (hsz > 0) {
            float x = event.getX(index);
            float y = event.getY(index);
            for (int h = 0; h < hsz; ++h) {
                if (event.getHistoricalX(index, h) != x || event.getHistoricalY(index, h) != y) {
                    return 1;
                }
            }
            return 2;
        }
        return 1;
    }

    private static int getPointerType(MotionEvent event)
    {
        switch (event.getToolType(0)) {
            case MotionEvent.TOOL_TYPE_STYLUS:
                return 1; // QTabletEvent::Pen
            case MotionEvent.TOOL_TYPE_ERASER:
                return 3; // QTabletEvent::Eraser
            default:
                return 0;
        }
    }

    private static float getStylusRotation(MotionEvent event, int historyIndex)
    {
        // In Android, the stylus barrel rotation is measured by the RZ axis on
        // e.g. the Wacom Art Pen 2. The value is measured in radians from the
        // bottom though, whereas QTabletEvent's rotation property measures
        // degrees from the top, so we have to map it accordingly. On styluses
        // that don't support barrel rotation, we return zero, rather than
        // blindly mapping the default zero axis value to 180 for every event.
        InputDevice device = event.getDevice();
        if (device != null) {
            InputDevice.MotionRange range = device.getMotionRange(MotionEvent.AXIS_RZ);
            if (range != null) {
                float rz;
                if (historyIndex < 0) {
                    rz = event.getAxisValue(MotionEvent.AXIS_RZ);
                } else {
                    rz = event.getHistoricalAxisValue(MotionEvent.AXIS_RZ, historyIndex);
                }
                // This is equivalent to std::remainder in C++. It results in
                // values between -180 and 180, which is QTabletEvent's range.
                return (float) Math.IEEEremainder(Math.toDegrees(rz) + 180.0, 360.0);
            }
        }
        return 0.0f;
    }

    private static boolean hasValidHistory(MotionEvent event)
    {
        // The historical events are *supposed* to tell us intermediate inputs
        // from an input device that arrived between the last handled event and
        // this one. That's really good for accuracy when scrolling or drawing,
        // but unfortunately many devices just lie about the history. Instead of
        // real positions, they just linearly interpolate them, leading to very
        // wrong results in the application. We can detect such devices really
        // reliably by checking if the points it gives us are on a straight-ish
        // line. Well-behaved devices will have a significant deviation.
        int historySize = event.getHistorySize();
        if (historySize > 1) {
            // Once we have enough good or bad samples, we'll just assume that
            // the device in question is generally good or bad.
            int deviceId = event.getDeviceId();
            int quality = getHistoryQuality(deviceId);
            if (quality >= HISTORY_QUALITY_THRESHOLD) {
                return true;
            } else if (quality <= -HISTORY_QUALITY_THRESHOLD) {
                return false;
            }

            // Two points will always form a straight line, so we can't check
            // anything meaningful in this case.
            if (historySize == 1) {
                return true;
            }

            double x1 = event.getHistoricalX(0);
            double y1 = event.getHistoricalY(0);
            double x2 = event.getX();
            double y2 = event.getY();

            // If the points are really close to each other, there's no point
            // doing any of this rigmarole, it's not going to be accurate.
            double distance = Math.hypot(x2 - x1, y2 - y1);
            if (distance < 4.0) {
                return true;
            }

            double a = y2 - y1;
            double b = x1 - x2;
            double d = Math.sqrt(a * a + b * b);
            if (d == 0.0) {
                return true; // Avoid division by zero.
            }

            double c = (x2 * y1) - (y2 * x1);
            for (int i = 1; i < historySize; ++i) {
                double x = event.getHistoricalX(i);
                double y = event.getHistoricalY(i);
                double deviation = Math.abs((a * x) + (b * y ) + c) / d;
                // A fudge factor of 0.001 seems safe, since good devices don't
                // really go below 0.1 and bad devices not above around 0.0001.
                if (deviation > 0.001) {
                    setHistoryQuality(deviceId, quality + 1);
                    return true;
                }
            }

            setHistoryQuality(deviceId, quality - 1);
        }
        return false;
    }

    private static int getHistoryQuality(int deviceId)
    {
        Integer quality = m_historyQualities.get(deviceId);
        return quality == null ? 0 : quality;
    }

    private static void setHistoryQuality(int deviceId, int quality)
    {
        m_historyQualities.put(deviceId, quality);
        if (quality >= HISTORY_QUALITY_THRESHOLD) {
            Log.w(TAG, "Device " + deviceId + " reached positive history quality threshold");
        } else if (quality <= -HISTORY_QUALITY_THRESHOLD) {
            Log.w(TAG, "Device " + deviceId + " reached negative history quality threshold");
        }
    }

    static void sendTouchEvent(MotionEvent event, int id)
    {
        if (m_tabletEventSupported == null)
            m_tabletEventSupported = isTabletEventSupported();

        int pointerType = getPointerType(event);

        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            sendMouseEvent(event, id);
        } else if (m_tabletEventSupported && pointerType != 0) {
            sendTabletEvent(event, id, pointerType);
        } else {
            int action = event.getActionMasked();
            int pointerCount = event.getPointerCount();
            int metaState = event.getMetaState();
            if (action == MotionEvent.ACTION_MOVE && hasValidHistory(event)) {
                int historySize = event.getHistorySize();
                for (int historyIndex = 0; historyIndex < historySize; ++historyIndex) {
                    touchBegin(id);
                    for (int i = 0; i < pointerCount; ++i) {
                        touchAdd(id,
                                event.getPointerId(i),
                                getMoveAction(i, event),
                                i == 0,
                                (int)event.getHistoricalX(i, historyIndex),
                                (int)event.getHistoricalY(i, historyIndex),
                                event.getHistoricalTouchMajor(i, historyIndex),
                                event.getHistoricalTouchMinor(i, historyIndex),
                                event.getHistoricalOrientation(i, historyIndex),
                                event.getHistoricalPressure(i, historyIndex));
                    }
                    touchEnd(id, event.getHistoricalEventTime(historyIndex), 1, metaState);
                }
            }

            touchBegin(id);
            for (int i = 0; i < pointerCount; ++i) {
                touchAdd(id,
                        event.getPointerId(i),
                        getAction(i, event),
                        i == 0,
                        (int)event.getX(i),
                        (int)event.getY(i),
                        event.getTouchMajor(i),
                        event.getTouchMinor(i),
                        event.getOrientation(i),
                        event.getPressure(i));
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    touchEnd(id, event.getEventTime(), 0, metaState);
                    break;

                case MotionEvent.ACTION_UP:
                    touchEnd(id, event.getEventTime(), 2, metaState);
                    break;

                case MotionEvent.ACTION_CANCEL:
                    touchCancel(id, event.getEventTime(), metaState);
                    break;

                default:
                    touchEnd(id, event.getEventTime(), 1, metaState);
            }
        }
    }

    static void sendTrackballEvent(MotionEvent event, int id)
    {
        sendMouseEvent(event,id);
    }

    static boolean sendGenericMotionEvent(MotionEvent event, int id)
    {
        if (m_tabletEventSupported == null) {
            m_tabletEventSupported = isTabletEventSupported();
        }

        int pointerType = getPointerType(event);
        if (m_tabletEventSupported && pointerType != 0) {
            return sendTabletEvent(event, id, pointerType);
        }

        int scrollOrHoverMove = MotionEvent.ACTION_SCROLL | MotionEvent.ACTION_HOVER_MOVE;
        int pointerDeviceModifier = (event.getSource() & InputDevice.SOURCE_CLASS_POINTER);
        boolean isPointerDevice = pointerDeviceModifier == InputDevice.SOURCE_CLASS_POINTER;

        if ((event.getAction() & scrollOrHoverMove) == 0 || !isPointerDevice )
            return false;

        return sendMouseEvent(event, id);
    }

    static boolean sendMouseEvent(MotionEvent event, int id)
    {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_UP:
                mouseUp(id, event.getEventTime(), (int) event.getX(), (int) event.getY(),
                        event.getButtonState(), event.getMetaState());
                break;

            case MotionEvent.ACTION_DOWN:
                mouseDown(id, event.getEventTime(), (int) event.getX(), (int) event.getY(),
                        event.getButtonState(), event.getMetaState());
                m_oldX = (int) event.getX();
                m_oldY = (int) event.getY();
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                sendMouseMoveEvent(event, id, -1);
                break;
            case MotionEvent.ACTION_MOVE:
                // Move events are the only ones that can have a history. Some
                // devices report a garbage history, see the comment above.
                if (hasValidHistory(event)) {
                    int historySize = event.getHistorySize();
                    for (int historyIndex = 0; historyIndex < historySize; ++historyIndex) {
                        sendMouseMoveEvent(event, id, historyIndex);
                    }
                }
                sendMouseMoveEvent(event, id, -1);
                break;
            case MotionEvent.ACTION_SCROLL:
                return sendWheelEvent(event, id);
            default:
                return false;
        }
        return true;
    }

    private static void sendMouseMoveEvent(MotionEvent event, int id, int historyIndex)
    {
        long time;
        float x, y;
        if (historyIndex < 0) {
            time = event.getEventTime();
            x = event.getX();
            y = event.getY();
        } else {
            time = event.getHistoricalEventTime(historyIndex);
            x = event.getHistoricalX(historyIndex);
            y = event.getHistoricalY(historyIndex);
        }

        int ix = (int) x;
        int iy = (int) y;
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            mouseMove(id, time, ix, iy, event.getButtonState(), event.getMetaState());
        } else {
            int dx = (int) (x - m_oldX);
            int dy = (int) (y - m_oldY);
            if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                mouseMove(id, time, ix, iy, event.getButtonState(), event.getMetaState());
                m_oldX = ix;
                m_oldY = iy;
            }
        }
    }

    @SuppressWarnings("fallthrough")
    private static boolean sendTabletEvent(MotionEvent event, int id, int pointerType)
    {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                // Move events are the only ones that can have a history. Some
                // devices report a garbage history, see the comment above.
                if (hasValidHistory(event)) {
                    int historySize = event.getHistorySize();
                    for (int historyIndex = 0; historyIndex < historySize; ++historyIndex) {
                        tabletEvent(id, event.getDeviceId(),
                                event.getHistoricalEventTime(historyIndex), action, pointerType,
                                event.getButtonState(), event.getHistoricalX(historyIndex),
                                event.getHistoricalY(historyIndex),
                                event.getHistoricalPressure(historyIndex),
                                event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, historyIndex),
                                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, historyIndex),
                                getStylusRotation(event, historyIndex), event.getMetaState());
                    }
                }
                // Fallthrough.
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_EXIT:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_UP:
                tabletEvent(id, event.getDeviceId(), event.getEventTime(), action,
                        pointerType, event.getButtonState(), event.getX(), event.getY(),
                        event.getPressure(), event.getAxisValue(MotionEvent.AXIS_ORIENTATION),
                        event.getAxisValue(MotionEvent.AXIS_TILT), getStylusRotation(event, -1),
                        event.getMetaState());
                return true;
            case MotionEvent.ACTION_SCROLL:
                return sendWheelEvent(event, id);
            default:
                return false;
        }
    }

    private static boolean sendWheelEvent(MotionEvent event, int id)
    {
        mouseWheel(id, event.getEventTime(), (int) event.getX(), (int) event.getY(),
                event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                event.getMetaState());
        return true;
    }
}
