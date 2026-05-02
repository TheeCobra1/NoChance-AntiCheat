package NC.noChance.core;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class CombatTracker {
    private final Map<UUID, CombatContext> contexts;

    public CombatTracker() {
        this.contexts = new ConcurrentHashMap<>();
    }

    public static class CombatContext {
        private volatile long lastHitTime;
        private volatile long lastDamagedTime;
        private volatile UUID lastAttacker;
        private volatile UUID lastVictim;
        private final ConcurrentLinkedDeque<CombatEvent> recentCombat;
        private volatile boolean inCombat;
        private volatile int combatTicks;

        public CombatContext() {
            this.recentCombat = new ConcurrentLinkedDeque<>();
            this.lastHitTime = 0;
            this.lastDamagedTime = 0;
            this.inCombat = false;
            this.combatTicks = 0;
        }

        public void recordHit(UUID victim, double damage) {
            long now = System.currentTimeMillis();
            this.lastHitTime = now;
            this.lastVictim = victim;
            this.inCombat = true;
            this.combatTicks = 100;

            recentCombat.addLast(new CombatEvent(CombatEvent.Type.HIT, victim, damage, now));
            if (recentCombat.size() > 20) {
                recentCombat.pollFirst();
            }
        }

        public void recordDamage(UUID attacker, double damage) {
            long now = System.currentTimeMillis();
            this.lastDamagedTime = now;
            this.lastAttacker = attacker;
            this.inCombat = true;
            this.combatTicks = 100;

            recentCombat.addLast(new CombatEvent(CombatEvent.Type.DAMAGED, attacker, damage, now));
            if (recentCombat.size() > 20) {
                recentCombat.pollFirst();
            }
        }

        public void tick() {
            if (combatTicks > 0) {
                combatTicks--;
            } else {
                inCombat = false;
            }
        }

        public boolean isInCombat() {
            return inCombat;
        }

        public long getTimeSinceLastHit() {
            return lastHitTime > 0 ? System.currentTimeMillis() - lastHitTime : Long.MAX_VALUE;
        }

        public long getTimeSinceLastDamage() {
            return lastDamagedTime > 0 ? System.currentTimeMillis() - lastDamagedTime : Long.MAX_VALUE;
        }

        public double getRecentCPS() {
            long now = System.currentTimeMillis();
            long cutoff = now - 1000;

            long recentHits = recentCombat.stream()
                    .filter(e -> e.type == CombatEvent.Type.HIT && e.timestamp > cutoff)
                    .count();

            return recentHits;
        }

        public List<CombatEvent> getRecentEvents(long windowMs) {
            long cutoff = System.currentTimeMillis() - windowMs;
            List<CombatEvent> result = new ArrayList<>();

            for (CombatEvent event : recentCombat) {
                if (event.timestamp > cutoff) {
                    result.add(event);
                }
            }

            return result;
        }
    }

    public static class CombatEvent {
        public enum Type {
            HIT,
            DAMAGED
        }

        public final Type type;
        public final UUID entityId;
        public final double damage;
        public final long timestamp;

        public CombatEvent(Type type, UUID entityId, double damage, long timestamp) {
            this.type = type;
            this.entityId = entityId;
            this.damage = damage;
            this.timestamp = timestamp;
        }
    }

    public CombatContext getContext(UUID playerId) {
        return contexts.computeIfAbsent(playerId, k -> new CombatContext());
    }

    public void recordPlayerHit(Player attacker, Entity victim, double damage) {
        CombatContext context = getContext(attacker.getUniqueId());
        context.recordHit(victim.getUniqueId(), damage);
    }

    public void recordPlayerDamaged(Player victim, Entity attacker, double damage) {
        CombatContext context = getContext(victim.getUniqueId());
        context.recordDamage(attacker.getUniqueId(), damage);
    }

    public boolean isInCombat(UUID playerId) {
        CombatContext context = contexts.get(playerId);
        return context != null && context.isInCombat();
    }

    public void tick() {
        for (CombatContext context : contexts.values()) {
            context.tick();
        }
    }

    public void cleanup(UUID playerId) {
        contexts.remove(playerId);
    }
}
