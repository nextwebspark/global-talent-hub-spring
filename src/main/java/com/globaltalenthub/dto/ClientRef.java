package com.globaltalenthub.dto;

/**
 * How a project names its client: EITHER an existing {@code clientId} OR a {@code newClient}
 * to create — exactly one must be set (else 400).
 */
public record ClientRef(Long clientId, NewClientInput newClient) {}
