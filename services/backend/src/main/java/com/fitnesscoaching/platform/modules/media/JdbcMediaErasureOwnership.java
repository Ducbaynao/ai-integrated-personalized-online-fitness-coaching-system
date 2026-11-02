package com.fitnesscoaching.platform.modules.media;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class JdbcMediaErasureOwnership implements MediaErasureOwnership {
    private final JdbcTemplate jdbc;

    public JdbcMediaErasureOwnership(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean hasNoNonChatReferences(UUID mediaId) {
        // V6–V50 have 11 FK columns to media_files, including Chat and the
        // erasure worklist. A new unclassified FK fails closed until reviewed.
        Integer knownFks = jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE contype='f' AND confrelid='fitness.media_files'::regclass
                """, Integer.class);
        if (knownFks == null || knownFks != 11) return false;
        return Boolean.FALSE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM fitness.users WHERE avatar_media_id=?
                    UNION ALL SELECT 1 FROM fitness.trainer_certificates WHERE document_media_id=?
                    UNION ALL SELECT 1 FROM fitness.exercise_media WHERE media_id=?
                    UNION ALL SELECT 1 FROM fitness.progress_photos WHERE media_id=?
                    UNION ALL SELECT 1 FROM fitness.food_analysis_requests WHERE image_media_id=?
                    UNION ALL SELECT 1 FROM fitness.trainer_verification_documents WHERE media_id=?
                    UNION ALL SELECT 1 FROM fitness.user_report_attachments WHERE media_id=?
                    UNION ALL SELECT 1 FROM fitness.pose_analysis_sessions WHERE source_video_media_id=?
                    UNION ALL SELECT 1 FROM fitness.pose_keypoint_artifacts WHERE media_id=?
                )
                """, Boolean.class, mediaId, mediaId, mediaId, mediaId, mediaId,
                mediaId, mediaId, mediaId, mediaId));
    }
}
