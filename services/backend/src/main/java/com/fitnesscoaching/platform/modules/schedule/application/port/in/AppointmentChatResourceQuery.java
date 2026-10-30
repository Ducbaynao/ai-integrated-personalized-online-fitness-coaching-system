package com.fitnesscoaching.platform.modules.schedule.application.port.in;

import java.util.UUID;

/** Schedule-owned Chat read boundary; never infer access from a message ID. */
public interface AppointmentChatResourceQuery {
    boolean canRead(UUID actor, UUID student, UUID period, UUID appointmentId);
}
