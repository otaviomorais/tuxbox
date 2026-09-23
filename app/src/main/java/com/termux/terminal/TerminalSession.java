package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session bridged to our ptyd helper (PTY bridge daemon) over its stdio.
 * <p>
 * ptyd is exec'd by this app through {@code su} with {@code --stdio} mode: it creates a
 * pseudo-terminal as root, forks/execs the requested command (e.g. the tuxbox container launcher) and bridges the pty
 * master back to its stdin/stdout using framed messages: [u8 type][u32 BE len][payload]:
 *   0x01 data (both directions), 0x02 resize rows:u32 cols:u32, 0x03 exit code (u32 BE).
 * <p>
 * This replaces Termux's JNI.createSubprocess() with a stdio/socket bridge so the terminal
 * works inside a root-required container.
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** A queue written to from a separate thread when the process outputs, and read by main thread to process by terminal emulator. */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /** A queue written to from the main thread due to user interaction, and read by a background thread which forwards to the ptyd. */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;

    /** 1 while running, -1 when finished (the ptyd side owns the real pid). */
    int mShellPid = 1;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /** Streams to/from the ptyd process (its stdin/stdout). */
    private InputStream mStreamIn;
    private OutputStream mStreamOut;

    /** Serializes all writes to mStreamOut so frames never interleave. */
    private final Object mWriteLock = new Object();

    final Integer mTranscriptRows;

    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler(Looper.getMainLooper());

    private static final String LOG_TAG = "TerminalSession";

    /**
     * Create a session bridged to a running ptyd via its stdio streams.
     *
     * @param streamIn  the process's stdout (frames coming from ptyd)
     * @param streamOut the process's stdin (frames sent to ptyd)
     * @param transcriptRows number of rows kept in the transcript buffer, or null for default
     * @param client callback interface
     */
    public TerminalSession(InputStream streamIn, OutputStream streamOut, Integer transcriptRows, TerminalSessionClient client) {
        this.mStreamIn = streamIn;
        this.mStreamOut = streamOut;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the ptyd of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            sendResize(rows, columns);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation
     * and the frame IO threads against the ptyd streams.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

        sendResize(rows, columns);

        new Thread("TermSessionInputReader[" + mHandle.substring(0, 8) + "]") {
            @Override
            public void run() {
                final byte[] hdr = new byte[5];
                final byte[] payload = new byte[65536];
                try (InputStream in = new BufferedInputStream(mStreamIn, 8192)) {
                    while (true) {
                        int read = readFully(in, hdr, 5);
                        if (read == -1 || read < 5) return;
                        int type = hdr[0] & 0xff;
                        long len = ((hdr[1] & 0xffL) << 24) | ((hdr[2] & 0xffL) << 16) | ((hdr[3] & 0xffL) << 8) | (hdr[4] & 0xffL);
                        if (type == 0x01) {
                            // Normal pty output
                            int bytesToRead = (int) Math.min(len, payload.length);
                            if (readFully(in, payload, bytesToRead) != bytesToRead) return;
                            if (!mProcessToTerminalIOQueue.write(payload, 0, bytesToRead)) return;
                            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                        } else if (type == 0x03) {
                            // Process exit: payload is 4-byte BE exit code
                            int exitCode = 0;
                            if (len >= 4 && readFully(in, payload, 4) == 4) {
                                exitCode = ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16) | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
                            }
                            mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, (Object) exitCode));
                            return;
                        } else {
                            return;
                        }
                    }
                } catch (Exception e) {
                    // Stream closed; treat as process exit only if we haven't already exited.
                }
                if (mShellPid != -1) {
                    mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, (Object) 0));
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[" + mHandle.substring(0, 8) + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[8192];
                try (OutputStream out = new BufferedOutputStream(mStreamOut, 8192)) {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        synchronized (mWriteLock) {
                            writeDataFrame(out, buffer, bytesToWrite);
                        }
                    }
                } catch (IOException e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();
    }

    /** Send a resize message [0x02][len=8][rows:u32 BE][cols:u32 BE] to ptyd. */
    private void sendResize(int rows, int cols) {
        byte[] body = new byte[13];
        body[0] = 0x02;
        body[1] = 0; body[2] = 0; body[3] = 0; body[4] = 8;
        body[5] = (byte) ((rows >> 24) & 0xff); body[6] = (byte) ((rows >> 16) & 0xff);
        body[7] = (byte) ((rows >> 8) & 0xff);  body[8] = (byte) (rows & 0xff);
        body[9] = (byte) ((cols >> 24) & 0xff); body[10] = (byte) ((cols >> 16) & 0xff);
        body[11] = (byte) ((cols >> 8) & 0xff);  body[12] = (byte) (cols & 0xff);
        synchronized (mWriteLock) {
            try {
                OutputStream out = new BufferedOutputStream(mStreamOut, 64);
                out.write(body);
                out.flush();
            } catch (IOException e) {
                // Ignore; just shutting down.
            }
        }
    }

    /** Write a data frame [0x01][len:u32 BE][bytes] to ptyd. */
    private static void writeDataFrame(OutputStream out, byte[] data, int count) throws IOException {
        byte[] hdr = new byte[5];
        hdr[0] = 0x01;
        hdr[1] = (byte) ((count >> 24) & 0xff);
        hdr[2] = (byte) ((count >> 16) & 0xff);
        hdr[3] = (byte) ((count >> 8) & 0xff);
        hdr[4] = (byte) (count & 0xff);
        out.write(hdr);
        out.write(data, 0, count);
        out.flush();
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r == -1) return off == 0 ? -1 : off;
            if (r == 0) continue;
            off += r;
        }
        return off;
    }

    /** Write data to the ptyd (which forwards to the pty slave). */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (isRunning()) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* 21 bits max */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    protected void notifyScreenUpdate() {
        if (mClient != null) mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by closing the streams; ptyd kills the child. */
    public void finishIfRunning() {
        if (isRunning()) {
            try {
                if (mStreamIn != null) mStreamIn.close();
            } catch (IOException e) {
                // ignored
            }
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        try {
            if (mStreamIn != null) mStreamIn.close();
        } catch (IOException e) {
            // ignored
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        return null;
    }

    class MainThreadHandler extends Handler {

        MainThreadHandler(Looper looper) {
            super(looper);
        }

        final byte[] mReceiveBuffer = new byte[4 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
                // A single ptyd frame may carry more than the 4 KiB buffer: if
                // anything is still queued, drain it with another message instead
                // of leaving output stuck until the next frame arrives.
                if (mProcessToTerminalIOQueue.available() > 0) {
                    sendEmptyMessage(MSG_NEW_INPUT);
                }
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                if (mShellPid == -1) return; // already handled (socket close race)
                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);

                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += " - press Enter]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                if (mClient != null) mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}