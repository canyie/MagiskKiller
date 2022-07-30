package top.canyie.magiskkiller;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @author canyie
 */
public class PropArea {
    private static final int PROP_AREA_MAGIC = 0x504f5250;
    private static final int PROP_AREA_VERSION = 0xfc6ed0ab;

    private ByteBuffer data;
    private int byteUsed;

    public static PropArea any(String... areas) {
        for (String area : areas) {
            try {
                return new PropArea(area);
            } catch (FileNotFoundException ignored) {
                // Ignore and try the next
            } catch (IOException e) {
                throw new RuntimeException("open " + area, e);
            }
        }
        return null;
    }

    public PropArea(String area) throws IOException {
        area = "/dev/__properties__/u:object_r:" + area + ":s0";
        File file = new File(area);
        if (!file.isFile()) throw new FileNotFoundException("Not a file: " + area);
        long size = file.length();
        if (size <= 0 || size >= 0x7fffffffL) throw new IllegalArgumentException("invalid file size " + size);

        try (FileChannel channel = new FileInputStream(area).getChannel()) {
            data = channel.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.nativeOrder());
        }

        byteUsed = data.getInt();
        data.getInt(); // serial
        int magic = data.getInt();
        if (magic != PROP_AREA_MAGIC) throw new IllegalArgumentException("Bad file magic: " + magic);
        int version = data.getInt();
        if (version != PROP_AREA_VERSION) throw new IllegalArgumentException("Bad area versin: " + version);
        data.position(data.position() + 28); // reserved
    }

    public List<String> findPossibleValues(String name) {
        //  atomic_uint_least32_t serial;
        //  union {
        //    char value[PROP_VALUE_MAX];
        //    struct {
        //      char error_message[kLongLegacyErrorBufferSize];
        //      uint32_t offset;
        //    } long_property;
        //  };
        final int LONG_PROP_FLAG = 1 << 16;
        final int PROP_VALUE_MAX = 92;
        final int VALUE_OFFSET = 4;
        final int NAME_OFFSET = VALUE_OFFSET + 92;
        List<String> values = new ArrayList<>(2);
        findFromBuffer(data.slice(), name.getBytes(StandardCharsets.UTF_8), (buffer, offset) -> {
            if (offset < NAME_OFFSET) return;
            int base = offset - NAME_OFFSET;
            int serial = buffer.getInt(base);
            if ((serial & LONG_PROP_FLAG) != 0) return; // Long properties are not supported
            values.add(toString(buffer, base + VALUE_OFFSET, PROP_VALUE_MAX));
        });
        Log.i(MagiskKiller.TAG, "Found " + name + "=" + values);
        return values;
    }

    private static void findFromBuffer(ByteBuffer buffer, byte[] valueToFind, FindCallback callback) {
        outer: for (int i = 0;i < buffer.capacity();i++) {
            for (int j = 0;j < valueToFind.length;j++) {
                if (buffer.get(i + j) != valueToFind[j]) continue outer;
            }
            callback.onFind(buffer, i);
        }
    }

    private static String toString(ByteBuffer buffer, int offset, int limit) {
        StringBuilder sb = new StringBuilder(16);
        try {
            int i = 0;
            for (byte b; (b = buffer.get(i + offset)) != 0; i++) {
                if (i > limit) return "<index reached limit but no null terminator found>";
                sb.append((char) b);
            }
        } catch (IndexOutOfBoundsException e) {
            return "<index out of bounds>";
        }
        return sb.toString();
    }

    public interface FindCallback {
        void onFind(ByteBuffer buffer, int offset);
    }
}
