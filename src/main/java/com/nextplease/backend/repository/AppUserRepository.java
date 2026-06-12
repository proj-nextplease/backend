package com.nextplease.backend.repository;

import com.nextplease.backend.entity.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findBySupabaseUserId(UUID supabaseUserId);
}
