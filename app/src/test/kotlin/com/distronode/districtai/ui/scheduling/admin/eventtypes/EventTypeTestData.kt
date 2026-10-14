package com.distronode.districtai.ui.scheduling.admin.eventtypes

import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.core.model.SchedulingHost
import com.distronode.districtai.core.model.SchedulingQuestion
import com.distronode.districtai.core.model.SchedulingUser

/** An event type with every fact the details card can show. */
fun fullEventType(slug: String = "intro") = SchedulingEventType(
    id = "et-$slug",
    slug = slug,
    name = "Intro call",
    description = "A first conversation.",
    durationMinutes = 30,
    slotIntervalMinutes = 15,
    locationType = "in_person",
    locationValue = "1 Main St",
    routingMode = "round_robin",
    rrStrategy = "priority",
    bufferBeforeMinutes = 5,
    bufferAfterMinutes = 10,
    minNoticeMinutes = 60,
    maxFutureDays = 30,
    maxActiveBookings = 2,
    isActive = true,
    showTakenSlots = true,
    isPublic = false,
)

fun schedulingHost(
    userId: String = "u1",
    name: String = "Ana Lima",
    role: String = "required",
    archived: Boolean = false,
) = SchedulingHost(
    userId = userId,
    name = name,
    email = "$userId@example.com",
    role = role,
    priority = 0,
    archived = archived,
)

fun schedulingQuestion(
    id: String = "q1",
    label: String = "Company",
    type: String = "text",
    required: Boolean = false,
    position: Int = 0,
    options: List<String>? = null,
) = SchedulingQuestion(
    id = id,
    eventTypeId = "et-intro",
    label = label,
    type = type,
    options = options,
    required = required,
    position = position,
)

fun schedulerUser(id: String, name: String = "", archived: Boolean = false) = SchedulingUser(
    id = id,
    email = "$id@example.com",
    name = name,
    isAdmin = false,
    isOwner = false,
    role = "member",
    archived = archived,
)
