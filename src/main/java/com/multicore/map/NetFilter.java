package com.multicore.map;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** address/prefix-length allow list, e.g. "192.168.0.0/16". Unparseable entries are skipped (and logged). */
final class NetFilter {
    private record Net(byte[] addr, int bits) {
    }

    private final List<Net> nets = new ArrayList<>();

    NetFilter(Collection<? extends String> entries) {
        for (String e : entries) {
            try {
                String s = e.trim();
                int slash = s.indexOf('/');
                InetAddress a = InetAddress.getByName(slash < 0 ? s : s.substring(0, slash));
                byte[] b = a.getAddress();
                int bits = slash < 0 ? b.length * 8 : Integer.parseInt(s.substring(slash + 1));
                this.nets.add(new Net(b, Math.max(0, Math.min(bits, b.length * 8))));
            } catch (Exception ex) {
                com.multicore.MultiCore.LOGGER.warn("[MultiCore] map: ignoring allowedNetworks entry '{}': {}", e, ex.toString());
            }
        }
    }

    boolean allows(InetAddress address) {
        byte[] b = address.getAddress();
        // IPv4 clients on a dual-stack socket arrive as ::ffff:a.b.c.d
        if (b.length == 16 && isMappedV4(b)) {
            b = new byte[]{b[12], b[13], b[14], b[15]};
        }
        for (Net n : this.nets) {
            if (n.addr.length == b.length && matches(n.addr, b, n.bits)) return true;
        }
        return false;
    }

    private static boolean isMappedV4(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return b[10] == (byte) 0xff && b[11] == (byte) 0xff;
    }

    private static boolean matches(byte[] net, byte[] a, int bits) {
        int full = bits / 8;
        for (int i = 0; i < full; i++) if (net[i] != a[i]) return false;
        int rest = bits % 8;
        if (rest == 0) return true;
        int mask = (0xff << (8 - rest)) & 0xff;
        return (net[full] & mask) == (a[full] & mask);
    }
}
