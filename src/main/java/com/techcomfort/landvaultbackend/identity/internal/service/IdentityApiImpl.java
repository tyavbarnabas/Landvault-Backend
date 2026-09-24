package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.identity.IdentityApi;
import com.techcomfort.landvaultbackend.identity.dto.UserDto;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link IdentityApi}'s implementation.
 * <p>
 * Written when {@code kyc} became the interface's first real caller —
 * {@code IdentityApi} had been declared since the module was created but
 * never implemented, so injecting it anywhere would have failed at startup
 * rather than at compile time.
 * <p>
 * {@code users} is not RLS-policied (see AGENTS.md), so an ordinary
 * repository read is correct here; there is no scope to fail closed on.
 */
@Service
@RequiredArgsConstructor
public class IdentityApiImpl implements IdentityApi {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserDto> findById(UUID id) {
        return userRepository.findById(id).map(IdentityApiImpl::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> countryOf(UUID userId) {
        return userRepository.findById(userId).map(User::getCountry);
    }

    private static UserDto toDto(User user) {
        return new UserDto(
                user.getId(),
                user.getTenantId(),
                user.getBranchId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getCountry(),
                user.getCurrency(),
                // The wire value, never the internal enum — see UserDto.
                user.getStatus() == null ? null : user.getStatus().getValue(),
                user.getCreatedAt());
    }
}
