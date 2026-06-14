-- V7: Add onboarding_completed column to profiles table
alter table profiles
    add column if not exists onboarding_completed boolean not null default false;
