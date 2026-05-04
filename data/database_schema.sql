-- SQLite schema for Android (Room-compatible).
-- Design choice: round counts are derived from shot_event for integrity.
-- Optional denormalized counters can be added later if profiling requires it.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS firearm_profile (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    manufacturer TEXT,
    model TEXT,
    caliber TEXT,
    serial_number TEXT,
    type TEXT,
    image_uri TEXT,
    purchase_date TEXT,
    round_count INTEGER NOT NULL DEFAULT 0 CHECK (round_count >= 0),
    notes TEXT,
    previous_round_count INTEGER NOT NULL DEFAULT 0 CHECK (previous_round_count >= 0),
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ammo_profile (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    manufacturer TEXT,
    caliber TEXT,
    grain INTEGER,
    velocity_fps INTEGER,
    image_uri TEXT,
    notes TEXT,
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK (grain IS NULL OR grain > 0),
    CHECK (velocity_fps IS NULL OR velocity_fps > 0)
);

CREATE TABLE IF NOT EXISTS gunshot_count_session (
    id TEXT PRIMARY KEY,
    firearm_id TEXT NOT NULL,
    location_name TEXT,
    created_latitude REAL,
    created_longitude REAL,
    created_accuracy_m REAL,
    notes TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (firearm_id) REFERENCES firearm_profile(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CHECK (created_latitude IS NULL OR (created_latitude >= -90.0 AND created_latitude <= 90.0)),
    CHECK (created_longitude IS NULL OR (created_longitude >= -180.0 AND created_longitude <= 180.0)),
    CHECK (created_accuracy_m IS NULL OR created_accuracy_m >= 0.0)
);

CREATE TABLE IF NOT EXISTS session_ammo_usage (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    ammo_id TEXT NOT NULL,
    lot_number TEXT,
    notes TEXT,
    recorded_round_count INTEGER NOT NULL DEFAULT 0 CHECK (recorded_round_count >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES gunshot_count_session(id) ON UPDATE CASCADE ON DELETE CASCADE,
    FOREIGN KEY (ammo_id) REFERENCES ammo_profile(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    UNIQUE (session_id, ammo_id, lot_number)
);

CREATE TABLE IF NOT EXISTS shot_event (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    session_ammo_usage_id TEXT,
    detected_at TEXT NOT NULL,
    detection_source TEXT NOT NULL CHECK (detection_source IN ('manual', 'microphone', 'import')),
    confidence REAL,
    peak_db REAL,
    is_deleted INTEGER NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES gunshot_count_session(id) ON UPDATE CASCADE ON DELETE CASCADE,
    FOREIGN KEY (session_ammo_usage_id) REFERENCES session_ammo_usage(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CHECK (confidence IS NULL OR (confidence >= 0.0 AND confidence <= 1.0))
);

CREATE INDEX IF NOT EXISTS idx_gunshot_count_session_firearm_id ON gunshot_count_session(firearm_id);
CREATE INDEX IF NOT EXISTS idx_session_ammo_usage_session_id ON session_ammo_usage(session_id);
CREATE INDEX IF NOT EXISTS idx_session_ammo_usage_ammo_id ON session_ammo_usage(ammo_id);
CREATE INDEX IF NOT EXISTS idx_shot_event_session_id_detected_at ON shot_event(session_id, detected_at);
CREATE INDEX IF NOT EXISTS idx_shot_event_session_ammo_usage_id ON shot_event(session_ammo_usage_id);
CREATE INDEX IF NOT EXISTS idx_shot_event_not_deleted ON shot_event(is_deleted);

-- Optional trigger: keep session_ammo_usage.recorded_round_count in sync with events.
CREATE TRIGGER IF NOT EXISTS trg_shot_event_insert_usage_count
AFTER INSERT ON shot_event
WHEN NEW.session_ammo_usage_id IS NOT NULL AND NEW.is_deleted = 0
BEGIN
    UPDATE session_ammo_usage
    SET recorded_round_count = recorded_round_count + 1,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.session_ammo_usage_id;
END;

CREATE TRIGGER IF NOT EXISTS trg_shot_event_soft_delete_usage_count
AFTER UPDATE OF is_deleted ON shot_event
WHEN NEW.session_ammo_usage_id IS NOT NULL AND OLD.is_deleted = 0 AND NEW.is_deleted = 1
BEGIN
    UPDATE session_ammo_usage
    SET recorded_round_count = CASE
        WHEN recorded_round_count > 0 THEN recorded_round_count - 1
        ELSE 0
    END,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = NEW.session_ammo_usage_id;
END;

-- Views for totals used by UI.
CREATE VIEW IF NOT EXISTS v_firearm_round_totals AS
SELECT
    f.id AS firearm_id,
    f.previous_round_count
      + COALESCE(COUNT(se.id), 0) AS total_round_count
FROM firearm_profile f
LEFT JOIN gunshot_count_session gcs ON gcs.firearm_id = f.id
LEFT JOIN shot_event se ON se.session_id = gcs.id AND se.is_deleted = 0
GROUP BY f.id;

CREATE VIEW IF NOT EXISTS v_ammo_round_totals AS
SELECT
    a.id AS ammo_id,
    COALESCE(COUNT(se.id), 0) AS total_round_count
FROM ammo_profile a
LEFT JOIN session_ammo_usage sau ON sau.ammo_id = a.id
LEFT JOIN shot_event se ON se.session_ammo_usage_id = sau.id AND se.is_deleted = 0
GROUP BY a.id;

CREATE VIEW IF NOT EXISTS v_session_round_totals AS
SELECT
    gcs.id AS session_id,
    COALESCE(COUNT(se.id), 0) AS total_round_count
FROM gunshot_count_session gcs
LEFT JOIN shot_event se ON se.session_id = gcs.id AND se.is_deleted = 0
GROUP BY gcs.id;
