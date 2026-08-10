# Brandwag — Requirements

Fixed-location weather monitoring app for a farm, driving two use cases:

1. **Daily work-planning notification** (weekdays, ~8am) — heat/rain heads-up ahead of the
   morning meeting.
2. **Burn-day wind alarm** — when a manually-armed "burning today" toggle is on, monitor
   for unexpected high wind and raise a real alarm (not just a notification) if gusts are
   forecast in the near term, since permission to burn is contingent on wind/temp and
   conditions can shift after the day's already been cleared with the fire department.

Secondary: a home screen showing a scrollable stack of daily forecast cards (today on top,
extending as far as Open-Meteo provides).

## Happy path
- Poll Open-Meteo for one settings-configurable lat/lon.
- **Idle mode** (burn toggle off): check at ~08:00, ~16:00, ~00:00 daily.
- **Armed mode** (burn toggle on): check hourly, all day, until auto-disarm at end of day.
- 08:00 weekday check → normal notification if temp > threshold and/or measurable rain
  (any mm > 0) predicted for the day; escalates to full alarm if temp or rain each cross
  their own separate, higher "severe" threshold.
- Armed-mode check → full alarm if forecast wind **gusts** (`wind_gusts_10m`) exceed
  threshold at any point in the **next 5 hours** from the check time. Sustained wind
  (`wind_speed_10m`) shown as context alongside the gust figure, not itself a trigger.
- Burn toggle: manual on each burn morning, auto-off at end of day (no auto re-arm, no
  scheduled reminder to arm it).
- Home screen: card stack, today first, one card per day going forward.

## Must-never list
- Must never fire the burn-wind alarm while the burn toggle is off.
- Must never treat a failed/stale poll as "all clear" — a failed fetch must not suppress
  an alarm that a successful fetch would have raised.
- Must never let Doze/battery optimization silently prevent the alarm from sounding
  (Huawei P20 Pro/EMUI is the known-hostile test device here).
- Must never lose the burn-armed state or scheduled checks across a reboot.
- Must never let two overlapping poll triggers (e.g. a manual refresh + a scheduled one)
  double-fire the same alarm/notification.

## Real-world inputs to handle
- No connectivity / API timeout / malformed API response.
- Open-Meteo per-model nulls — not all variables guaranteed present.
- App killed/reinstalled mid-burn-day — armed state must survive an app restart (assume
  yes, since it's a full day; use persisted state, not just in-memory).
- Device reboot during an armed day.
- User toggles burn mode on, then off, then on again same day — check logic must handle
  re-arming cleanly, not just a one-shot.
- Clock/timezone edge cases (device timezone vs farm location timezone) — low priority
  given fixed farm use, but worth a note.

## Component tiering

| Component | Tier |
|---|---|
| Burn-wind alarm trigger logic (5hr lookahead, gust threshold) | Critical |
| "Never fire while disarmed" guard | Critical |
| Alarm delivery reliability (sound/full-screen/reboot survival) | Critical |
| 8am notification logic + severe escalation | Important |
| Polling scheduler (mode switching idle/armed) | Important |
| Threshold settings persistence | Important |
| Stale-poll warning display | Important |
| Card stack UI | Cosmetic |
| Icons/colors (Meteocons, reused) | Cosmetic |

## Environment
- Min SDK 29 (Android 10), Target SDK 36
- Kotlin, XML Views
- Package: `cvc.dashingdog.brandwag`
- Known-hostile test device: Huawei P20 Pro (EMUI) — required for Phase 4 alarm delivery testing
