SET search_path TO fitness, public;

CREATE TABLE trainer_verification_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE CASCADE,
    status verification_status NOT NULL DEFAULT 'PENDING',
    submitted_at timestamptz NOT NULL DEFAULT now(),
    reviewed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at timestamptz,
    rejection_reason text,
    review_notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE trainer_verification_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    verification_request_id uuid NOT NULL REFERENCES trainer_verification_requests(id) ON DELETE CASCADE,
    media_id uuid NOT NULL REFERENCES media_files(id) ON DELETE RESTRICT,
    document_type varchar(60) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id uuid REFERENCES users(id) ON DELETE SET NULL,
    reported_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    report_type varchar(80) NOT NULL,
    subject varchar(200) NOT NULL,
    description text NOT NULL,
    status report_status NOT NULL DEFAULT 'OPEN',
    assigned_admin_id uuid REFERENCES users(id) ON DELETE SET NULL,
    resolution text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);

CREATE TABLE user_report_attachments (
    report_id uuid NOT NULL REFERENCES user_reports(id) ON DELETE CASCADE,
    media_id uuid NOT NULL REFERENCES media_files(id) ON DELETE RESTRICT,
    PRIMARY KEY (report_id, media_id)
);

CREATE TABLE admin_actions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    action_type varchar(100) NOT NULL,
    target_type varchar(80) NOT NULL,
    target_id uuid,
    reason text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE system_configurations (
    config_key varchar(120) PRIMARY KEY,
    config_value jsonb NOT NULL,
    description text,
    is_sensitive boolean NOT NULL DEFAULT false,
    updated_by uuid REFERENCES users(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE trainer_packages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE CASCADE,
    name varchar(200) NOT NULL,
    description text,
    delivery_mode varchar(30) NOT NULL CHECK (delivery_mode IN ('ONLINE', 'IN_PERSON', 'HYBRID')),
    status varchar(30) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE trainer_package_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_package_id uuid NOT NULL REFERENCES trainer_packages(id) ON DELETE CASCADE,
    version_number integer NOT NULL CHECK (version_number > 0),
    duration_weeks integer CHECK (duration_weeks IS NULL OR duration_weeks > 0),
    total_sessions integer CHECK (total_sessions IS NULL OR total_sessions > 0),
    sessions_per_week numeric(5,2) CHECK (sessions_per_week IS NULL OR sessions_per_week > 0),
    session_duration_minutes integer CHECK (session_duration_minutes IS NULL OR session_duration_minutes > 0),
    price_amount numeric(18,2) NOT NULL CHECK (price_amount >= 0),
    currency char(3) NOT NULL,
    effective_from timestamptz NOT NULL DEFAULT now(),
    effective_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (trainer_package_id, version_number),
    CONSTRAINT package_version_dates_ck CHECK (effective_until IS NULL OR effective_until > effective_from)
);

CREATE TABLE subscriptions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE RESTRICT,
    trainer_package_version_id uuid NOT NULL REFERENCES trainer_package_versions(id) ON DELETE RESTRICT,
    coaching_relationship_id uuid REFERENCES coaching_relationships(id) ON DELETE SET NULL,
    status subscription_status NOT NULL DEFAULT 'PENDING',
    starts_at timestamptz,
    ends_at timestamptz,
    sessions_included integer CHECK (sessions_included IS NULL OR sessions_included >= 0),
    sessions_used integer NOT NULL DEFAULT 0 CHECK (sessions_used >= 0),
    agreed_price_amount numeric(18,2) NOT NULL CHECK (agreed_price_amount >= 0),
    currency char(3) NOT NULL,
    cancelled_at timestamptz,
    cancellation_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT subscription_dates_ck CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT subscription_usage_ck CHECK (sessions_included IS NULL OR sessions_used <= sessions_included)
);

CREATE TABLE payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE RESTRICT,
    subscription_id uuid REFERENCES subscriptions(id) ON DELETE SET NULL,
    amount numeric(18,2) NOT NULL CHECK (amount >= 0),
    currency char(3) NOT NULL,
    status payment_status NOT NULL DEFAULT 'PENDING',
    payment_provider varchar(60),
    provider_payment_id varchar(255),
    idempotency_key varchar(120) NOT NULL UNIQUE,
    paid_at timestamptz,
    failed_at timestamptz,
    failure_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (payment_provider, provider_payment_id)
);

CREATE TABLE payment_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id uuid NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    transaction_type varchar(30) NOT NULL CHECK (transaction_type IN ('AUTHORIZE', 'CAPTURE', 'REFUND', 'VOID', 'FAILURE', 'WEBHOOK')),
    provider_transaction_id varchar(255),
    amount numeric(18,2) CHECK (amount IS NULL OR amount >= 0),
    currency char(3),
    status varchar(30) NOT NULL,
    provider_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (payment_id, provider_transaction_id)
);

CREATE TABLE invoices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id uuid REFERENCES payments(id) ON DELETE SET NULL,
    subscription_id uuid REFERENCES subscriptions(id) ON DELETE SET NULL,
    invoice_number varchar(80) NOT NULL UNIQUE,
    issued_to_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    subtotal_amount numeric(18,2) NOT NULL CHECK (subtotal_amount >= 0),
    discount_amount numeric(18,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    tax_amount numeric(18,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    total_amount numeric(18,2) NOT NULL CHECK (total_amount >= 0),
    currency char(3) NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    paid_at timestamptz,
    status varchar(30) NOT NULL CHECK (status IN ('DRAFT', 'ISSUED', 'PAID', 'VOID'))
);

CREATE UNIQUE INDEX uq_invoice_payment
    ON invoices(payment_id)
    WHERE payment_id IS NOT NULL;

CREATE TABLE invoice_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id uuid NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    description varchar(255) NOT NULL,
    quantity numeric(18,4) NOT NULL CHECK (quantity > 0),
    unit_price numeric(18,2) NOT NULL CHECK (unit_price >= 0),
    line_total numeric(18,2) NOT NULL CHECK (line_total >= 0),
    sort_order integer NOT NULL DEFAULT 0
);

CREATE TABLE trainer_reviews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id uuid NOT NULL REFERENCES subscriptions(id) ON DELETE RESTRICT,
    trainer_id uuid NOT NULL REFERENCES trainer_profiles(user_id) ON DELETE CASCADE,
    student_id uuid NOT NULL REFERENCES student_profiles(user_id) ON DELETE CASCADE,
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    review_text text,
    status varchar(30) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PENDING', 'PUBLISHED', 'HIDDEN', 'REMOVED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (subscription_id)
);

CREATE TABLE audit_logs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    actor_role varchar(40),
    action varchar(120) NOT NULL,
    target_type varchar(80) NOT NULL,
    target_id uuid,
    request_id uuid,
    ip_address inet,
    user_agent text,
    before_data jsonb,
    after_data jsonb,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE data_deletion_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    requested_at timestamptz NOT NULL DEFAULT now(),
    scheduled_for timestamptz,
    status request_status NOT NULL DEFAULT 'PENDING',
    handled_by uuid REFERENCES users(id) ON DELETE SET NULL,
    handled_at timestamptz,
    resolution_note text
);

CREATE TRIGGER audit_logs_append_only_update
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_update_delete();
