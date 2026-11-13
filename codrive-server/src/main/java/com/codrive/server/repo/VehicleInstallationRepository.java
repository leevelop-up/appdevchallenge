package com.codrive.server.repo;

import com.codrive.server.domain.VehicleInstallation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VehicleInstallationRepository extends JpaRepository<VehicleInstallation, UUID> {
}
