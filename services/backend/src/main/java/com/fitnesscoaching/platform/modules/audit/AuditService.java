package com.fitnesscoaching.platform.modules.audit;

public interface AuditService {

    void recordAudit(AuditRecord record);

    void recordSecurityEvent(SecurityEventRecord record);
}
