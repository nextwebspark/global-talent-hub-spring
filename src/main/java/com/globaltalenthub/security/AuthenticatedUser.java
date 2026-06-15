package com.globaltalenthub.security;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, String email, UUID orgId, String orgRole) {}
