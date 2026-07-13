# JC181 / JT808 — E8 Extended Alarm Mapping

The JC181 does **not** implement the standard ADAS (`0x64`) / DMS (`0x65`) messages, so this table
covers only the **E8 extended alarms** (`0x0200` sub-id `0xE8`, `switch (extendedType)` in
`Jt808ProtocolDecoder`, guarded to JC models). Chapter 7 of the protocol defines three groups.

**Legend** — Status: ✅ implemented (alarm) · ℹ️ not an alarm (status/clear/info — set an
attribute or ignore, don't raise an alarm). "Proposed" is the suggested `Position.ALARM_*` target for
missing entries; `general` = no exact Traccar constant, use `ALARM_GENERAL` (+ a descriptive attribute).

---

## 7.2.1 Video alarms (0x0001–0x000C)

Mostly driver/cabin-camera detections; on the JC181 most of these won't fire unless the cabin AI is present.

| ID | Meaning | Status | Proposed mapping |
|------|---------|--------|------------------|
| 0x0001 | Camera failure | ✅ | `ALARM_FAULT` |
| 0x0002 | Camera obstructed | ✅ | `ALARM_TAMPERING` |
| 0x0003 | Seatbelt unfastened | ✅ | `ALARM_GENERAL` (attr `seatbelt=unfastened`) |
| 0x0004 | Seatbelt fastened | ℹ️ | clear — attr only, no alarm |
| 0x0005 | Face ID failed | ✅ | `ALARM_GENERAL` (attr `faceId=failed`) |
| 0x0006 | Face ID succeeded | ℹ️ | attr only, no alarm |
| 0x0007 | Eyes closed | ✅ | `ALARM_FATIGUE_DRIVING` |
| 0x0008 | Yawning | ✅ | `ALARM_FATIGUE_DRIVING` |
| 0x0009 | Face alignment failed | ℹ️ | attr only |
| 0x000A | Face lost | ✅ | `ALARM_GENERAL` (attr `face=lost`) |
| 0x000B | Drinking | ✅ | `ALARM_GENERAL` (attr `behavior=drinking`) |
| 0x000C | Driver changed | ℹ️ | event — set `driver`/attr, no alarm |

## 7.2.2 Location / driving alarms (0x0400–0x0419)

| ID | Meaning | Status | Proposed mapping |
|------|---------|--------|------------------|
| 0x0400 | Harsh acceleration | ✅ | `ALARM_ACCELERATION` |
| 0x0401 | Harsh braking | ✅ | `ALARM_BRAKING` |
| 0x0402 | Sharp cornering | ✅ | `ALARM_CORNERING` |
| 0x0403 | Overspeed | ✅ | `ALARM_OVERSPEED` |
| 0x0404 | Excessive driving time | ✅ | `ALARM_FATIGUE_DRIVING` |
| 0x0405 | Driving collision | ✅ | `ALARM_ACCIDENT` |
| 0x0406 | Parking vibration | ✅ | `ALARM_VIBRATION` |
| 0x0407 | Towing | ✅ | `ALARM_TOW` |
| 0x0408 | Geofence entry | ✅ | `ALARM_GEOFENCE_ENTER` |
| 0x0409 | Geofence exit | ✅ | `ALARM_GEOFENCE_EXIT` |
| 0x040A | Left turn | ℹ️ | signal event — attr only |
| 0x040B | Right turn | ℹ️ | signal event — attr only |
| 0x040C | Door open | ✅ | `ALARM_DOOR` |
| 0x040D | Door close | ℹ️ | clear — attr only |
| 0x0410 | Sleep mode entered | ℹ️ | status — attr `mode=sleep` |
| 0x0411 | Working mode entered | ℹ️ | status — attr `mode=working` |
| 0x0412 | UBI harsh acceleration | ✅ | `ALARM_ACCELERATION` |
| 0x0413 | UBI harsh braking | ✅ | `ALARM_BRAKING` |
| 0x0414 | UBI sharp cornering | ✅ | `ALARM_CORNERING` |
| 0x0415 | UBI sudden lane change | ✅ | `ALARM_LANE_CHANGE` |
| 0x0416 | UBI collision | ✅ | `ALARM_ACCIDENT` |
| 0x0417 | UBI rollover | ✅ | `ALARM_ACCIDENT` |
| 0x0418 | UBI abnormal attitude | ✅ | `ALARM_GENERAL` (attr `ubi=attitude`) |
| 0x0419 | UBI abnormal Euler angle | ✅ | `ALARM_GENERAL` (attr `ubi=euler`) |

## 7.2.3 Basic alarms (0x0C01–0x0C1A)

| ID | Meaning | Status | Proposed mapping |
|------|---------|--------|------------------|
| 0x0C01 | SOS emergency | ✅ | `ALARM_SOS` |
| 0x0C02 | Low external battery | ✅ | `ALARM_LOW_POWER` |
| 0x0C03 | ACC ON | ✅ ℹ️ | `KEY_IGNITION=true` (not an alarm) |
| 0x0C04 | ACC OFF | ✅ ℹ️ | `KEY_IGNITION=false` (not an alarm) |
| 0x0C05 | Theft / anti-theft | ✅ | `ALARM_GENERAL` |
| 0x0C06 | DMS calibration error | ℹ️ | n/a on JC181 (no DMS) |
| 0x0C07 | Identity recognition alert | ✅ | `ALARM_GENERAL` (attr `identity`) |
| 0x0C08 | Door alert | ✅ | `ALARM_DOOR` |
| 0x0C09 | Fuel sensor abnormal | ✅ | `ALARM_FAULT` (attr `sensor=fuel`) |
| 0x0C0A | Temp / humidity abnormal | ✅ | `ALARM_TEMPERATURE` |
| 0x0C0B | Driver card login (DLT) | ℹ️ | event — set `driverUniqueId`, no alarm |
| 0x0C0C | Driver card logout (DLT) | ℹ️ | event — attr only |
| 0x0C0D | Unauthorized card (DLT) | ✅ | `ALARM_GENERAL` (attr `card=unauthorized`) |
| 0x0C0E | Power failure / cut | ✅ | `ALARM_POWER_CUT` |
| 0x0C0F | Low internal battery | ✅ | `ALARM_LOW_BATTERY` |
| 0x0C10 | Shutdown (low battery) | ✅ | `ALARM_POWER_OFF` |
| 0x0C11 | Ambient sound alert | ✅ | `ALARM_GENERAL` (attr `sound`) |
| 0x0C12 | Tamper alert | ✅ | `ALARM_TAMPERING` |
| 0x0C13 | Active offline | ℹ️ | status — attr only |
| 0x0C14 | SD/TF card inserted/mounted | ℹ️ | status — attr `sdCard=mounted` |
| 0x0C15 | SD/TF card removed / missing | ✅ | `ALARM_FAULT` (attr `sdCard=removed`) |
| 0x0C16 | SD/TF card write error | ✅ | `ALARM_FAULT` (attr `sdCard=writeError`) |
| 0x0C17 | Data overage | ✅ | `ALARM_GENERAL` (attr `dataOverage`) |
| 0x0C1A | Audio file HTTP download failed | ℹ️ | status — attr only |

---

## Implemented

All codes above are now handled in the `0xE8` `switch (extendedType)` (JC-model-guarded): alarms via
`addAlarm(...)`, and status/info codes (ℹ️) as attributes. Original priority notes below.

### Priority (JC181-relevant)

1. **Storage health** (matters for recording/playback): `0x0C15` SD removed, `0x0C16` SD write error → `ALARM_FAULT`; `0x0C14` inserted → attr.
2. **Driving/safety not yet covered:** `0x0404` excessive driving time → `ALARM_FATIGUE_DRIVING`; `0x0002` camera obstructed → `ALARM_TAMPERING`.
3. **Sensors / misc:** `0x0C09` fuel abnormal → `ALARM_FAULT`; `0x0C0A` temp/humidity → `ALARM_TEMPERATURE`; `0x0C07`/`0x0C0D` identity/unauthorized card → `ALARM_GENERAL`.
4. **Cabin-AI (only if the unit has it):** `0x0007`/`0x0008` eyes-closed/yawning → `ALARM_FATIGUE_DRIVING`; `0x0003` seatbelt, `0x000B` drinking → `ALARM_GENERAL`.
5. **Info/status (no alarm):** turns, mode changes, door-close, card login/logout, ACC, SD-insert, download-complete — set attributes only.

> All additions go inside the existing `case 0xE8` `switch (extendedType)` (JC-model-guarded), so they
> don't affect other JT808 devices.
