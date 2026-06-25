CREATE TABLE admin_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_type VARCHAR(50) NOT NULL, -- 'B2B_PARTNER', 'EXPERIENCE', 'JOB'
    item_id UUID NOT NULL,
    claimed_by_admin_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    claimed_at TIMESTAMP WITH TIME ZONE,
    internal_notes TEXT,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
    CONSTRAINT unique_item_review UNIQUE(item_type, item_id)
);

CREATE INDEX idx_admin_reviews_item ON admin_reviews(item_type, item_id);
CREATE INDEX idx_admin_reviews_admin ON admin_reviews(claimed_by_admin_id);
