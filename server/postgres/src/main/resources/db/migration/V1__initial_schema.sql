CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE app_users (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL CHECK (length(trim(name)) > 0),
    email varchar(254) NOT NULL UNIQUE,
    role varchar(16) NOT NULL CHECK (role IN ('USER', 'STAFF', 'ADMIN')),
    api_key_hash char(64) NOT NULL UNIQUE
);

CREATE TABLE equipment (
    id uuid PRIMARY KEY,
    inventory_number varchar(64) NOT NULL UNIQUE,
    name varchar(160) NOT NULL,
    category varchar(80) NOT NULL,
    location varchar(160) NOT NULL,
    description varchar(2000) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'RETIRED'))
);
CREATE INDEX equipment_category_idx ON equipment(category);

CREATE TABLE bookings (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_users(id),
    equipment_id uuid NOT NULL REFERENCES equipment(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    purpose varchar(500) NOT NULL,
    created_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'ISSUED', 'RETURNED')),
    reason varchar(500),
    issued_at timestamptz,
    issued_by uuid REFERENCES app_users(id),
    issue_condition varchar(500),
    returned_at timestamptz,
    returned_by uuid REFERENCES app_users(id),
    return_condition varchar(500),
    CHECK (starts_at < ends_at),
    CHECK (created_at < starts_at),
    CHECK ((status IN ('REJECTED', 'CANCELLED')) = (reason IS NOT NULL)),
    CHECK ((status IN ('ISSUED', 'RETURNED')) = (issued_at IS NOT NULL)),
    CHECK ((issued_at IS NOT NULL) = (issued_by IS NOT NULL)),
    CHECK ((issued_at IS NOT NULL) = (issue_condition IS NOT NULL)),
    CHECK (issued_at IS NULL OR (issued_at >= starts_at AND issued_at < ends_at)),
    CHECK ((status = 'RETURNED') = (returned_at IS NOT NULL)),
    CHECK ((returned_at IS NOT NULL) = (returned_by IS NOT NULL)),
    CHECK ((returned_at IS NOT NULL) = (return_condition IS NOT NULL)),
    CHECK (returned_at IS NULL OR returned_at >= issued_at),
    CONSTRAINT no_overlapping_reservations EXCLUDE USING gist (
        equipment_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status IN ('APPROVED', 'ISSUED'))
);
CREATE INDEX bookings_user_idx ON bookings(user_id, created_at, id);
CREATE INDEX bookings_equipment_idx ON bookings(equipment_id);
CREATE INDEX bookings_status_idx ON bookings(status, ends_at);
-- Protect physical possession even when the previous reservation interval has expired.
CREATE UNIQUE INDEX one_issued_loan_per_equipment ON bookings(equipment_id) WHERE status = 'ISSUED';

CREATE TABLE booking_events (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id uuid NOT NULL UNIQUE,
    booking_id uuid NOT NULL REFERENCES bookings(id),
    actor_id uuid NOT NULL REFERENCES app_users(id),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'ISSUED', 'RETURNED')),
    occurred_at timestamptz NOT NULL,
    note varchar(500)
);
CREATE INDEX booking_events_booking_idx ON booking_events(booking_id, sequence);
