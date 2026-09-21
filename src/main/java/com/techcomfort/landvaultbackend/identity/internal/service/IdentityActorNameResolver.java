package com.techcomfort.landvaultbackend.identity.internal.service;

import com.techcomfort.landvaultbackend.audit.ActorNameResolver;
import com.techcomfort.landvaultbackend.identity.internal.domain.User;
import com.techcomfort.landvaultbackend.identity.internal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code identity}'s side of {@link ActorNameResolver} — see that interface
 * for why the dependency is inverted rather than {@code audit} calling
 * {@code IdentityApi}.
 * <p>
 * Reads through this module's own repository rather than {@code IdentityApi},
 * because it already lives inside {@code identity}; the API exists for
 * <em>other</em> modules. One {@code findAllById} per page, never per row.
 */
@Service
@RequiredArgsConstructor
public class IdentityActorNameResolver implements ActorNameResolver {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> displayNamesFor(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, IdentityActorNameResolver::displayName, (a, b) -> a));
    }

    private static String displayName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        // An account with no name still needs something a reviewer can act
        // on; the email identifies the actor just as well.
        return name.isEmpty() ? user.getEmail() : name;
    }
}
