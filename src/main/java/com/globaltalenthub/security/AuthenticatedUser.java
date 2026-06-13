package com.globaltalenthub.security;

public record AuthenticatedUser(String userId, String email, String orgId, String orgRole) {}
