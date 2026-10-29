package com.fitnesscoaching.platform.modules.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ChatAccountErasureService implements ChatAccountErasure {
    private final JdbcTemplate jdbc;
    public ChatAccountErasureService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ObjectReference> concealAndListSentObjects(UUID userId) {
        List<ObjectReference> objects = jdbc.query("""
                SELECT DISTINCT mf.id,mf.bucket_name,mf.object_key,
                       mf.storage_provider,mf.owner_user_id,
                       (SELECT count(*) FROM fitness.message_attachments all_links
                        WHERE all_links.media_id=mf.id)
                FROM fitness.messages m
                JOIN fitness.message_attachments ma ON ma.message_id=m.id
                JOIN fitness.media_files mf ON mf.id=ma.media_id
                WHERE m.sender_id=?
                """, (rs, row) -> new ObjectReference(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getString(3), rs.getString(4),
                userId.equals(rs.getObject(5, UUID.class)) && rs.getLong(6) == 1), userId);
        jdbc.update("""
                UPDATE fitness.media_files mf SET deleted_at=COALESCE(mf.deleted_at,clock_timestamp())
                FROM fitness.message_attachments ma JOIN fitness.messages m ON m.id=ma.message_id
                WHERE ma.media_id=mf.id AND m.sender_id=?
                """, userId);
        // Remove sender-authored content in the same transaction as account disable.
        // Retain sender_id temporarily: ambiguous/shared objects need that provenance
        // for owner review before final identity minimization may proceed.
        jdbc.update("""
                UPDATE fitness.messages SET body=NULL,message_type='SYSTEM',
                    shared_entity_type=NULL,shared_entity_id=NULL,
                    deleted_at=COALESCE(deleted_at,clock_timestamp())
                WHERE sender_id=?
                """, userId);
        return objects;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void redactSentMessages(UUID userId) {
        // Keep the other participant's messages and the conversation identity/history.
        jdbc.update("""
                UPDATE fitness.media_files mf SET original_filename=NULL,checksum_sha256=NULL
                FROM fitness.message_attachments ma JOIN fitness.messages m ON m.id=ma.message_id
                WHERE ma.media_id=mf.id AND m.sender_id=? AND mf.owner_user_id=?
                """, userId, userId);
        jdbc.update("""
                UPDATE fitness.messages SET sender_id=NULL,body=NULL,message_type='SYSTEM',
                    shared_entity_type=NULL,shared_entity_id=NULL,deleted_at=COALESCE(deleted_at,clock_timestamp())
                WHERE sender_id=?
                """, userId);
        jdbc.update("UPDATE fitness.conversations SET created_by=NULL WHERE created_by=?", userId);
        // The retained conversation needs its period identity, not the erased user's
        // per-message delivery/read activity or mute preference.
        jdbc.update("""
                UPDATE fitness.message_read_receipts SET delivered_at=NULL,read_at=NULL
                WHERE user_id=?
                """, userId);
        jdbc.update("""
                UPDATE fitness.conversation_members SET left_at=COALESCE(left_at,clock_timestamp()),
                    last_read_at=NULL,is_muted=false WHERE user_id=?
                """, userId);
    }
}
