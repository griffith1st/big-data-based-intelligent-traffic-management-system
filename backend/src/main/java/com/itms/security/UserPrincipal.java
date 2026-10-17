package com.itms.security;

import java.security.Principal;

/** Current database identity; JWT role claims are never used as permissions. */
public record UserPrincipal(Long userId, String username, String role, long authVersion) implements Principal {
    @Override public String getName() { return username; }
}
