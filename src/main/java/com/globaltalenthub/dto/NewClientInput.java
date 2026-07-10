package com.globaltalenthub.dto;

/** Create a new client inline: a name (required) + an optional domain + an optional catalog link. */
public record NewClientInput(String name, String domain, Long linkedCompanyId) {}
