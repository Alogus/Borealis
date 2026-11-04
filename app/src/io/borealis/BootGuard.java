package io.borealis;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Safety net against a TV that reboots again and again: every boot is recorded, and on the 4th within 20 minutes
 * (a normal TV never does that) Borealis gives the TV back to its original home screen and stops working at boot.
 * File: files/boots.txt (main process).
 */
final class BootGuard {
    private BootGuard() {}

    private static final long WINDOW = 20 * 60_000L;
    private static final int LIMIT = 4;

    /** Records this boot. True when it is the LIMIT-th within WINDOW: the TV is in a boot loop. */
    static boolean loop(Context c) {
        try {
            long now = System.currentTimeMillis();
            List<Long> list = recent(c, now);
            list.add(now);
            StringBuilder sb = new StringBuilder();
            for (long at : list) sb.append(at).append('\n');
            try (FileOutputStream out = new FileOutputStream(file(c))) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            return list.size() >= LIMIT;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static List<Long> recent(Context c, long now) {
        List<Long> list = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return list;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 4096)];
            int read = in.read(data);
            if (read <= 0) return list;
            for (String line : new String(data, 0, read, StandardCharsets.UTF_8).split("\n")) {
                try {
                    long at = Long.parseLong(line.trim());
                    if (now - at < WINDOW && at <= now) list.add(at);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return list;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "boots.txt");
    }
}
