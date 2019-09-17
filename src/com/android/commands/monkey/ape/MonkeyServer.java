package com.android.commands.monkey.ape;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalServerSocket;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;

import java.lang.Runnable;
import java.lang.RuntimeException;
import com.android.commands.monkey.ape.AppTerminatedException;
/**
 * This notices idle status
 *
 */
public class MonkeyServer implements Runnable {
    public static final String SOCK_ADDRESS = "/dev/mt/ape";

    private LocalServerSocket lss;
    private int value = 0;

    // connection-specific values
    private LocalSocket socket;
    private InputStream is;
    private OutputStream os;

    // Store time for last idle time
    private long last_idle_time;

    // buffer to read 8 bytes
    private byte[] buffer;

    public MonkeyServer() throws IOException {
        lss = new LocalServerSocket(SOCK_ADDRESS);
        last_idle_time = 0;
        buffer = new byte[8];
    }

    /**
     * return -1 for timeout otherwise last idle time
     */
    public long waitForIdle(long fromMillis, long timeoutMillis) {
        long time_end = System.currentTimeMillis() + timeoutMillis;
        long last_time_fetched;
        long time_current;
        while (true) {
            synchronized (this) {
                last_time_fetched = last_idle_time;
            }
            time_current = System.currentTimeMillis();
            if (fromMillis < last_time_fetched || time_current > time_end) {
                break;
            }
            synchronized (this) {
                try {
                    wait(time_end - time_current);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (time_current > time_end)
            return -1;
        return last_time_fetched;
    }

    public long waitForFirstIdle() {
        long last_time_fetched;
        while (true) {
            synchronized (this) {
                last_time_fetched = last_idle_time;
            }
            if (last_time_fetched != 0) {
                break;
            }
            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                }
            }
        }
        return last_time_fetched;
    }

    @Override
    public void run() {
        /* It may be enough to one-to-one with client */
        while (true) {
            try {
                socket = lss.accept();
                is = socket.getInputStream();
                os = socket.getOutputStream();
            } catch (IOException e) {
                throw new RuntimeException("accept");
            }

            while (true) {
                // Read idle time!
                int byte_written = 0;
                int cur_written;
                while (byte_written < 8) {
                    try {
                        cur_written = is.read(buffer, byte_written, 8-byte_written);
                    } catch (IOException e) {
                        cur_written = -1;
                    }
                    if (cur_written == -1) {
                        byte_written = -1;
                        break;
                    }

                    byte_written += cur_written;
                }

                if (byte_written == -1) {
                    // App would be terminated, so wait for accept
                    System.out.println("** The target seems to be terminated ");
                    try {
                        socket.close();
                    } catch (IOException e) {}
                    last_idle_time = 0;
                    break;
                }

                // store idle time
                synchronized (this) {
                    last_idle_time = ((long)buffer[0] & 0xFF)
                                     + ((long)(buffer[1] & 0xFF) << 8)
                                     + ((long)(buffer[2] & 0xFF) << 16)
                                     + ((long)(buffer[3] & 0xFF) << 24)
                                     + ((long)(buffer[4] & 0xFF) << 32)
                                     + ((long)(buffer[5] & 0xFF) << 40)
                                     + ((long)(buffer[6] & 0xFF) << 48)
                                     + ((long)(buffer[7] & 0xFF) << 56);
                    System.out.println("Idle time received: " +last_idle_time);
                    notify();
                }
            }

            // Socket is broken, wait for new accept
        }
    }
}