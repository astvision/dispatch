package dispatch.worker;

import dispatch.Log;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.store.Tasks;
import dispatch.store.Tx;
import dispatch.store.Workers;
import dispatch.telegram.TelegramNames;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Who may reach the worker endpoints. A member asks the bot for a one-time code, their computer exchanges it for a
 * random 256-bit key, and from then on that key names the member. The team machine keeps only SHA-256 of both: a copy of
 * its database gives nobody access to anyone's computer, and the key itself is written to exactly one HTTP answer and to
 * no log.
 */
public final class WorkerKeys {

    public static final int CODE_LENGTH = 8;
    public static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    /** No I, O, 0 or 1: the member reads this code off one screen and types it on another. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int KEY_BYTES = 32;
    /** How long a worker's name may run before /worker's list gets unreadable; matches ConfigLoader's group-name cap. */
    private static final int MAX_NAME_LENGTH = 40;

    private final Database db;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public WorkerKeys(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** A fresh one-time code for {@code member}; only its hash is kept, so it can never be shown again. */
    public String newCode(Requester member) {
        return db.transactionReturning(tx -> newCode(tx, member));
    }

    /** As {@link #newCode(Requester)}, inside a transaction the caller already has open (e.g. the bot's /worker). */
    public String newCode(Tx tx, Requester member) {
        Instant now = clock.instant();
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        String text = code.toString();
        Workers.deleteExpiredCodes(tx, now);
        Workers.insertCode(tx, sha256(text), member, now, now.plus(CODE_LIFETIME));
        tx.afterCommit(() -> Log.info("worker.code_issued", "member", member.ref(), "expires_in_minutes", CODE_LIFETIME.toMinutes()));
        return text;
    }

    public List<Workers.Paired> of(String memberRef) {
        return db.transactionReturning(tx -> of(tx, memberRef));
    }

    /** As {@link #of(String)}, inside a transaction the caller already has open. */
    public List<Workers.Paired> of(Tx tx, String memberRef) {
        return Workers.ofMember(tx, memberRef);
    }

    /** What a worker is told once, when it pairs. */
    public record NewKey(long workerId, String memberRef, String key) {

        @Override
        public String toString() {
            // Never let a key reach a log line through a record's own toString.
            return "NewKey[workerId=" + workerId + ", memberRef=" + memberRef + ", key=***]";
        }
    }

    /**
     * Exchanges a pairing code for a new key; empty when the code is unknown, used or older than ten minutes. Consuming
     * the code and creating the worker happen in one transaction, so a failed insert never burns a code the member
     * could otherwise retry.
     *
     * @throws IllegalArgumentException when {@code name} is blank once cleaned of control characters
     */
    public Optional<NewKey> pair(String code, String name) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String boundedName = boundName(name);
        Instant now = clock.instant();
        // Upper case in ROOT: the alphabet is ASCII, and a member may well type the code back in lower case.
        String codeSha256 = sha256(code.strip().toUpperCase(Locale.ROOT));
        Optional<NewKey> paired = db.transactionReturning(tx -> {
            Optional<Requester> member = Workers.useCode(tx, codeSha256, now);
            if (member.isEmpty()) {
                return Optional.empty();
            }
            byte[] secret = new byte[KEY_BYTES];
            random.nextBytes(secret);
            String key = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            long id = Workers.insert(tx, member.get().ref(), boundedName, sha256(key), now);
            return Optional.of(new NewKey(id, member.get().ref(), key));
        });
        if (paired.isEmpty()) {
            Log.warn("worker.pairing_refused", "reason", "unknown, used or expired code");
        } else {
            NewKey newKey = paired.get();
            Log.info("worker.paired", "worker", newKey.workerId(), "member", newKey.memberRef(), "name", boundedName);
        }
        return paired;
    }

    /** @throws IllegalArgumentException when {@code name} is blank once cleaned */
    private static String boundName(String name) {
        String cleaned = TelegramNames.clean(name == null ? "" : name);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("a worker's name cannot be blank");
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }

    /**
     * The worker a key belongs to; empty for no key, an unknown key and a revoked one alike, so nobody can learn which
     * keys once existed. The compare is constant-time over the hashes.
     */
    public Optional<Workers.Paired> authenticate(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        byte[] presented = sha256Bytes(key);
        return db.transactionReturning(tx -> {
            // ponytail: a linear scan over a team's handful of workers; index by hash if a team ever has hundreds.
            Optional<Workers.Paired> found = Workers.active(tx).stream()
                    .filter(worker -> MessageDigest.isEqual(presented, HexFormat.of().parseHex(worker.keySha256())))
                    .findFirst();
            found.ifPresent(worker -> Workers.touch(tx, worker.id(), clock.instant()));
            return found;
        });
    }

    /** @param admin an admin may revoke anyone's worker; everybody else only their own */
    public boolean revoke(long workerId, String memberRef, boolean admin) {
        return db.transactionReturning(tx -> revoke(tx, workerId, memberRef, admin));
    }

    /** As {@link #revoke(long, String, boolean)}, inside a transaction the caller already has open. */
    public boolean revoke(Tx tx, long workerId, String memberRef, boolean admin) {
        Optional<Workers.Paired> worker = Workers.find(tx, workerId);
        if (worker.isEmpty() || (!admin && !worker.get().memberRef().equals(memberRef))) {
            return false;
        }
        Instant now = clock.instant();
        boolean revoked = Workers.revoke(tx, workerId, now);
        if (revoked) {
            // Its unfinished tasks are no longer pinned to it, so a follow-up or retry goes to whichever of the
            // member's other computers is live instead of waiting on this one forever.
            Tasks.clearWorkerPin(tx, workerId, now);
            tx.afterCommit(() -> Log.info("worker.revoked", "worker", workerId, "member", worker.get().memberRef(), "by", memberRef));
        }
        return revoked;
    }

    /**
     * Someone taken out of the config takes their computers' access with them.
     *
     * @param currentMembers every member the config still names; an empty set means nobody is a member any more, so
     *                       every worker is revoked
     */
    public void revokeWorkersOfEveryoneExcept(Set<String> currentMembers) {
        int revoked = db.transactionReturning(tx -> {
            Instant now = clock.instant();
            List<Long> ids = Workers.revokeMembersExcept(tx, currentMembers, now);
            ids.forEach(id -> Tasks.clearWorkerPin(tx, id, now));
            return ids.size();
        });
        if (revoked > 0) {
            Log.warn("worker.revoked_former_members", "workers", revoked);
        }
    }

    static String sha256(String value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
