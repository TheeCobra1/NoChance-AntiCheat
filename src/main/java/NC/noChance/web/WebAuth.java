package NC.noChance.web;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WebAuth {
    private static final long CODE_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_ACTIVE_CODES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();

    public Pending issue(UUID issuerUuid, String issuerName) {
        sweep();
        codes.entrySet().removeIf(e -> e.getValue().issuerUuid.equals(issuerUuid));
        if (codes.size() >= MAX_ACTIVE_CODES) {
            Iterator<Map.Entry<String, Pending>> it = codes.entrySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
        String code;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
        } while (codes.containsKey(code));
        Pending p = new Pending(code, issuerUuid, issuerName, System.currentTimeMillis());
        codes.put(code, p);
        return p;
    }

    public Pending peek(String code) {
        if (code == null) return null;
        Pending p = codes.get(code);
        if (p == null) return null;
        if (p.expired()) { codes.remove(code); return null; }
        return p;
    }

    public void invalidate(String code) {
        if (code != null) codes.remove(code);
    }

    public void sweep() {
        codes.entrySet().removeIf(e -> e.getValue().expired());
    }

    public static class Pending {
        public final String code;
        public final UUID issuerUuid;
        public final String issuerName;
        public final long issuedAt;

        Pending(String code, UUID issuerUuid, String issuerName, long issuedAt) {
            this.code = code;
            this.issuerUuid = issuerUuid;
            this.issuerName = issuerName;
            this.issuedAt = issuedAt;
        }

        public boolean expired() {
            return System.currentTimeMillis() - issuedAt > CODE_TTL_MS;
        }

        public long remainingMs() {
            return Math.max(0, CODE_TTL_MS - (System.currentTimeMillis() - issuedAt));
        }
    }
}
