package net.codeverse.voice.storage;

import net.codeverse.voice.model.VoiceBan;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for voice restrictions, captures and the audit trail. */
public final class VoiceBanRepository {

    private final Database database;

    public VoiceBanRepository(Database database) {
        this.database = database;
    }

    /**
     * Returns the active restriction for an identity, if any. Expiry is not
     * filtered in SQL because a row that has lapsed still needs to be returned
     * once so the caller can retire it and tell the person their restriction is
     * over rather than silently letting them speak again.
     */
    public Optional<VoiceBan> findActive(UUID internalId) throws SQLException {
        String sql = "SELECT internal_id, reason, issued_by, issued_at, expires_at, active FROM "
                + database.table("voice_bans")
                + " WHERE internal_id = ? AND active = 1 ORDER BY issued_at DESC LIMIT 1";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, toBytes(internalId));
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(read(results)) : Optional.empty();
            }
        }
    }

    public long insert(VoiceBan ban) throws SQLException {
        String sql = "INSERT INTO " + database.table("voice_bans")
                + " (internal_id, reason, issued_by, issued_at, expires_at, active) VALUES (?, ?, ?, ?, ?, 1)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setBytes(1, toBytes(ban.internalId()));
            statement.setString(2, ban.reason());
            if (ban.issuedBy() == null) {
                statement.setNull(3, java.sql.Types.BINARY);
            } else {
                statement.setBytes(3, toBytes(ban.issuedBy()));
            }
            statement.setLong(4, ban.issuedAt());
            statement.setLong(5, ban.expiresAt());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    /** Retires every active restriction for an identity. Returns how many were affected. */
    public int lift(UUID internalId, UUID liftedBy) throws SQLException {
        String sql = "UPDATE " + database.table("voice_bans")
                + " SET active = 0, lifted_by = ?, lifted_at = ? WHERE internal_id = ? AND active = 1";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (liftedBy == null) {
                statement.setNull(1, java.sql.Types.BINARY);
            } else {
                statement.setBytes(1, toBytes(liftedBy));
            }
            statement.setLong(2, System.currentTimeMillis());
            statement.setBytes(3, toBytes(internalId));
            return statement.executeUpdate();
        }
    }

    /** Retires restrictions whose expiry has passed. Returns how many were retired. */
    public int retireExpired() throws SQLException {
        String sql = "UPDATE " + database.table("voice_bans")
                + " SET active = 0 WHERE active = 1 AND expires_at > 0 AND expires_at <= ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            return statement.executeUpdate();
        }
    }

    public List<VoiceBan> history(UUID internalId, int limit) throws SQLException {
        String sql = "SELECT internal_id, reason, issued_by, issued_at, expires_at, active FROM "
                + database.table("voice_bans")
                + " WHERE internal_id = ? ORDER BY issued_at DESC LIMIT ?";
        List<VoiceBan> out = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, toBytes(internalId));
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    out.add(read(results));
                }
            }
        }
        return out;
    }

    public void recordCapture(UUID internalId, UUID capturedBy, long capturedAt, int durationMillis,
                              String fileName, String note, long expiresAt) throws SQLException {
        String sql = "INSERT INTO " + database.table("voice_captures")
                + " (internal_id, captured_by, captured_at, duration_ms, file_name, note, expires_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, toBytes(internalId));
            if (capturedBy == null) {
                statement.setNull(2, java.sql.Types.BINARY);
            } else {
                statement.setBytes(2, toBytes(capturedBy));
            }
            statement.setLong(3, capturedAt);
            statement.setInt(4, durationMillis);
            statement.setString(5, fileName);
            statement.setString(6, note);
            statement.setLong(7, expiresAt);
            statement.executeUpdate();
        }
    }

    /** File names of captures whose retention window has passed. */
    public List<String> expiredCaptureFiles() throws SQLException {
        String sql = "SELECT file_name FROM " + database.table("voice_captures")
                + " WHERE expires_at > 0 AND expires_at <= ?";
        List<String> out = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    out.add(results.getString(1));
                }
            }
        }
        return out;
    }

    public int deleteExpiredCaptureRows() throws SQLException {
        String sql = "DELETE FROM " + database.table("voice_captures")
                + " WHERE expires_at > 0 AND expires_at <= ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            return statement.executeUpdate();
        }
    }

    /**
     * Appends to the audit trail. Monitoring another person's audio is a
     * capability that has to leave a record, otherwise there is no way to tell
     * legitimate moderation from someone abusing staff access.
     */
    public void audit(UUID actorId, UUID targetId, String action, String detail) throws SQLException {
        String sql = "INSERT INTO " + database.table("voice_audit")
                + " (actor_id, target_id, action, detail, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (actorId == null) {
                statement.setNull(1, java.sql.Types.BINARY);
            } else {
                statement.setBytes(1, toBytes(actorId));
            }
            if (targetId == null) {
                statement.setNull(2, java.sql.Types.BINARY);
            } else {
                statement.setBytes(2, toBytes(targetId));
            }
            statement.setString(3, action);
            statement.setString(4, detail);
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private static VoiceBan read(ResultSet results) throws SQLException {
        byte[] issuedBy = results.getBytes("issued_by");
        return new VoiceBan(
                fromBytes(results.getBytes("internal_id")),
                results.getString("reason"),
                issuedBy == null ? null : fromBytes(issuedBy),
                results.getLong("issued_at"),
                results.getLong("expires_at"),
                results.getBoolean("active"));
    }

    public static byte[] toBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    public static UUID fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
