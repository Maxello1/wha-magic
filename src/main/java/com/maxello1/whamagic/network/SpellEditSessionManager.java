package com.maxello1.whamagic.network;

import com.maxello1.whamagic.parser.Point;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks one server-authoritative spell-paper edit session per player. */
public final class SpellEditSessionManager {
    public enum SaveValidation {
        ACCEPTED,
        NO_SESSION,
        REVISION_MISMATCH,
        HAND_MISMATCH,
        ORIGINAL_HASH_MISMATCH,
        ITEM_MOVED_OR_REPLACED,
        ITEM_CHANGED,
        INVENTORY_CHANGED
    }

    public record SessionToken(long revision, int originalStrokeItemHash) {}

    private record Session(
            long revision,
            InteractionHand hand,
            ItemStack originalStack,
            int originalStrokeItemHash,
            int inventoryRevision) {}

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong nextRevision;

    public SpellEditSessionManager() {
        this(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
    }

    SpellEditSessionManager(long firstRevision) {
        if (firstRevision <= 0) {
            throw new IllegalArgumentException("firstRevision must be positive");
        }
        nextRevision = new AtomicLong(firstRevision);
    }

    public SessionToken open(
            UUID playerId,
            InteractionHand hand,
            ItemStack stack,
            List<List<Point>> strokes,
            int inventoryRevision) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(stack, "stack");

        long revision = nextRevision.getAndUpdate(
                value -> value == Long.MAX_VALUE ? 1 : value + 1);
        int strokeItemHash = strokeItemHash(stack, strokes);
        sessions.put(playerId, new Session(
                revision,
                hand,
                stack,
                strokeItemHash,
                inventoryRevision));
        return new SessionToken(revision, strokeItemHash);
    }

    public SaveValidation validateAndConsume(
            UUID playerId,
            InteractionHand hand,
            long revision,
            int originalStrokeItemHash,
            ItemStack currentStack,
            List<List<Point>> currentStrokes,
            int inventoryRevision) {
        Session session = sessions.get(playerId);
        if (session == null) {
            return SaveValidation.NO_SESSION;
        }
        if (session.revision() != revision) {
            return SaveValidation.REVISION_MISMATCH;
        }

        sessions.remove(playerId, session);
        if (session.hand() != hand) {
            return SaveValidation.HAND_MISMATCH;
        }
        if (session.originalStrokeItemHash() != originalStrokeItemHash) {
            return SaveValidation.ORIGINAL_HASH_MISMATCH;
        }
        if (session.originalStack() != currentStack) {
            return SaveValidation.ITEM_MOVED_OR_REPLACED;
        }
        if (strokeItemHash(currentStack, currentStrokes)
                != session.originalStrokeItemHash()) {
            return SaveValidation.ITEM_CHANGED;
        }
        if (session.inventoryRevision() != inventoryRevision) {
            return SaveValidation.INVENTORY_CHANGED;
        }
        return SaveValidation.ACCEPTED;
    }

    public boolean cancel(
            UUID playerId,
            InteractionHand hand,
            long revision,
            int originalStrokeItemHash) {
        Session session = sessions.get(playerId);
        if (session == null
                || session.revision() != revision
                || session.hand() != hand
                || session.originalStrokeItemHash() != originalStrokeItemHash) {
            return false;
        }
        return sessions.remove(playerId, session);
    }

    public void remove(UUID playerId) {
        sessions.remove(playerId);
    }

    public static int strokeItemHash(ItemStack stack, List<List<Point>> strokes) {
        int hash = ItemStack.hashItemAndComponents(Objects.requireNonNull(stack, "stack"));
        for (List<Point> stroke : Objects.requireNonNull(strokes, "strokes")) {
            hash = 31 * hash + stroke.size();
            for (Point point : stroke) {
                hash = 31 * hash + Double.hashCode(point.x);
                hash = 31 * hash + Double.hashCode(point.y);
            }
        }
        return hash;
    }
}
