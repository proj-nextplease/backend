-- V18 intended to allow 'COMPLETED' on applications but only added the
-- reject_reason column; the ck_applications_status constraint was never updated.
-- Without 'COMPLETED', employers cannot mark a job applicant as completed
-- (the step required before a candidate can be rated). quest_applications
-- already allows COMPLETED, which is why quest rating worked but job rating did not.

alter table applications
    drop constraint if exists ck_applications_status;

alter table applications
    add constraint ck_applications_status
        check (status in ('SUBMITTED', 'VIEWED', 'SHORTLISTED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'COMPLETED'));
