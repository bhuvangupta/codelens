package com.codelens.security;

import java.security.Principal;

public record AuthenticatedUser(
        String email,
        String name,
        String picture,
        String providerId
) implements Principal {

    @Override
    public String getName() {
        return email;
    }
}
