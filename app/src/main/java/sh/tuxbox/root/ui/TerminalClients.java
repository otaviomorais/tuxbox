package sh.tuxbox.root.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalViewClient;

import java.util.function.Consumer;

/** Minimal TerminalSessionClient + TerminalViewClient for the embedded terminal. */
final class TerminalClients implements TerminalSessionClient, TerminalViewClient {

    private static final String TAG = "tuxbox-term";

    private final Context context;
    private final Consumer<TerminalSession> onSessionFinished;
    private final View imeTarget;

    /** Sticky modifier state for the Termux-style extra keys row (CTRL/ALT toggles). */
    private volatile boolean ctrlHeld = false;
    private volatile boolean altHeld = false;

    TerminalClients(Context context, Consumer<TerminalSession> onSessionFinished, View imeTarget) {
        this.context = context;
        this.onSessionFinished = onSessionFinished;
        this.imeTarget = imeTarget;
    }

    void setCtrlHeld(boolean held) { this.ctrlHeld = held; }
    void setAltHeld(boolean held) { this.altHeld = held; }
    boolean isCtrlHeld() { return ctrlHeld; }
    boolean isAltHeld() { return altHeld; }

    /** Give the view focus (in touch mode) and pop the soft keyboard. */
    static void showSoftInput(Context ctx, View v) {
        if (ctx == null || v == null) return;
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.requestFocus();
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(v, 0, null);
    }

    // ---- TerminalSessionClient ----

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        // Termux redraws the terminal from here: without invalidating the view the
        // screen only repaints on touch (seems "not live"). The session notifies on
        // the main thread, so a direct onScreenUpdated()/invalidate() is safe.
        if (imeTarget instanceof com.termux.view.TerminalView) {
            ((com.termux.view.TerminalView) imeTarget).onScreenUpdated();
        } else if (imeTarget != null) {
            imeTarget.invalidate();
        }
    }
    @Override public void onTitleChanged(TerminalSession changedSession) { }
    @Override public void onBell(TerminalSession session) { }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        if (onSessionFinished != null) onSessionFinished.accept(finishedSession);
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData cd = cm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence text = cd.getItemAt(0).getText();
                if (text != null) session.write(text.toString());
            }
        }
    }

    @Override public void onColorsChanged(TerminalSession session) { }

    @Override
    public void onTerminalCursorStateChange(boolean state) { }

    @Nullable
    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    @Override public void logError(String tag, String message) { Log.e(TAG + "/" + tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(TAG + "/" + tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(TAG + "/" + tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(TAG + "/" + tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(TAG + "/" + tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(TAG + "/" + tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(TAG + "/" + tag, "stacktrace", e); }

    // ---- TerminalViewClient ----

    @Override public float onScale(float scale) { return 1.0f / 3.0f; }
    @Override public void onSingleTapUp(MotionEvent e) { showSoftInput(context, imeTarget); }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return false; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) { }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return ctrlHeld; }
    @Override public boolean readAltKey() { return altHeld; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public void clearControlKey() { setCtrlHeld(false); }
    @Override public void clearAltKey() { setAltHeld(false); }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override public void onEmulatorSet() { }
}